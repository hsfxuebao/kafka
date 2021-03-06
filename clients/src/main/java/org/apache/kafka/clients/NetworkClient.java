/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients;

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.network.NetworkReceive;
import org.apache.kafka.common.network.Selectable;
import org.apache.kafka.common.network.Send;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.protocol.ProtoUtils;
import org.apache.kafka.common.protocol.types.Struct;
import org.apache.kafka.common.requests.MetadataRequest;
import org.apache.kafka.common.requests.MetadataResponse;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.RequestSend;
import org.apache.kafka.common.requests.ResponseHeader;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.common.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * A network client for asynchronous request/response network i/o. This is an internal class used to implement the
 * user-facing producer and consumer clients.
 * <p>
 * This class is not thread-safe!
 */
public class NetworkClient implements KafkaClient {

    private static final Logger log = LoggerFactory.getLogger(NetworkClient.class);

    /* the selector used to perform network i/o */
    private final Selectable selector;

    private final MetadataUpdater metadataUpdater;

    private final Random randOffset;

    /* the state of each node's connection */
	// 管理连接状态，底层是Map<String, NodeConnectionState>实现，key是NodeId，value是NodeConnectionState对象
    private final ClusterConnectionStates connectionStates;

    /* the set of requests currently being sent or awaiting a response */
    private final InFlightRequests inFlightRequests;

    /* the socket send buffer size in bytes */
    private final int socketSendBuffer;

    /* the socket receive size buffer in bytes */
    private final int socketReceiveBuffer;

    /* the client id used to identify this client in requests to the server */
    private final String clientId;

    /* the current correlation id to use when sending requests to servers */
    private int correlation;

    /* max time in ms for the producer to wait for acknowledgement from server*/
    private final int requestTimeoutMs;

    private final Time time;

    public NetworkClient(Selectable selector,
                         Metadata metadata,
                         String clientId,
                         int maxInFlightRequestsPerConnection,
                         long reconnectBackoffMs,
                         int socketSendBuffer,
                         int socketReceiveBuffer,
                         int requestTimeoutMs,
                         Time time) {
        this(null, metadata, selector, clientId, maxInFlightRequestsPerConnection,
                reconnectBackoffMs, socketSendBuffer, socketReceiveBuffer, requestTimeoutMs, time);
    }

    public NetworkClient(Selectable selector,
                         MetadataUpdater metadataUpdater,
                         String clientId,
                         int maxInFlightRequestsPerConnection,
                         long reconnectBackoffMs,
                         int socketSendBuffer,
                         int socketReceiveBuffer,
                         int requestTimeoutMs,
                         Time time) {
        this(metadataUpdater, null, selector, clientId, maxInFlightRequestsPerConnection, reconnectBackoffMs,
                socketSendBuffer, socketReceiveBuffer, requestTimeoutMs, time);
    }

    private NetworkClient(MetadataUpdater metadataUpdater,
                          Metadata metadata,
                          Selectable selector,
                          String clientId,
                          int maxInFlightRequestsPerConnection,
                          long reconnectBackoffMs,
                          int socketSendBuffer,
                          int socketReceiveBuffer,
                          int requestTimeoutMs,
                          Time time) {

        /* It would be better if we could pass `DefaultMetadataUpdater` from the public constructor, but it's not
         * possible because `DefaultMetadataUpdater` is an inner class and it can only be instantiated after the
         * super constructor is invoked.
         */
        if (metadataUpdater == null) {
            if (metadata == null)
                throw new IllegalArgumentException("`metadata` must not be null");
            this.metadataUpdater = new DefaultMetadataUpdater(metadata);
        } else {
            this.metadataUpdater = metadataUpdater;
        }
        this.selector = selector;
        this.clientId = clientId;
        this.inFlightRequests = new InFlightRequests(maxInFlightRequestsPerConnection);
        this.connectionStates = new ClusterConnectionStates(reconnectBackoffMs);
        this.socketSendBuffer = socketSendBuffer;
        this.socketReceiveBuffer = socketReceiveBuffer;
        this.correlation = 0;
        this.randOffset = new Random();
        this.requestTimeoutMs = requestTimeoutMs;
        this.time = time;
    }

    /**
     * Begin connecting to the given node, return true if we are already connected and ready to send to that node.
	 *
	 * 检查Node是否准备好接受数据
     *
     * @param node The node to check
     * @param now The current timestamp
     * @return True if we are ready to send to the given node
     */
    @Override
    public boolean ready(Node node, long now) {
        if (node.isEmpty())
            throw new IllegalArgumentException("Cannot connect to empty node " + node);

        // 检查是否可以向一个Node发送请求
        if (isReady(node, now))
            return true;

        // 如果不能发送请求，则尝试建立连接
        if (connectionStates.canConnect(node.idString(), now))
            // if we are interested in sending to a node and we don't have a connection to it, initiate one
        	// 发起连接
            initiateConnect(node, now);

        return false;
    }

    /**
     * Closes the connection to a particular node (if there is one).
     *
     * @param nodeId The id of the node
     */
    @Override
    public void close(String nodeId) {
        selector.close(nodeId);
        for (ClientRequest request : inFlightRequests.clearAll(nodeId))
            metadataUpdater.maybeHandleDisconnection(request);
        connectionStates.remove(nodeId);
    }

    /**
     * Returns the number of milliseconds to wait, based on the connection state, before attempting to send data. When
     * disconnected, this respects the reconnect backoff time. When connecting or connected, this handles slow/stalled
     * connections.
     *
     * @param node The node to check
     * @param now The current timestamp
     * @return The number of milliseconds to wait.
     */
    @Override
    public long connectionDelay(Node node, long now) {
        return connectionStates.connectionDelay(node.idString(), now);
    }

    /**
     * Check if the connection of the node has failed, based on the connection state. Such connection failure are
     * usually transient and can be resumed in the next {@link #ready(org.apache.kafka.common.Node, long)} }
     * call, but there are cases where transient failures needs to be caught and re-acted upon.
     *
     * @param node the node to check
     * @return true iff the connection has failed and the node is disconnected
     */
    @Override
    public boolean connectionFailed(Node node) {
        return connectionStates.connectionState(node.idString()).equals(ConnectionState.DISCONNECTED);
    }

    /**
     * Check if the node with the given id is ready to send more requests.
     *
     * @param node The node
     * @param now The current time in ms
     * @return true if the node is ready
     */
    @Override
    public boolean isReady(Node node, long now) {
        // if we need to update our metadata now declare all requests unready to make metadata requests first
        // priority
		// Metadata没有处于正在更新或需要更新状态
		// 已经成功建立连接并连接正常，inFlightRequests还可以发送更多数据
        return !metadataUpdater.isUpdateDue(now) && canSendRequest(node.idString());
    }

    /**
     * Are we connected and ready and able to send more requests to the given connection?
     *
     * @param node The node
     */
    private boolean canSendRequest(String node) {
    	// 已成功建立连接，连接正常，nFlightRequests还可以发送更多数据
        return connectionStates.isConnected(node) && selector.isChannelReady(node) && inFlightRequests.canSendMore(node);
    }

    /**
     * Queue up the given request for sending. Requests can only be sent out to ready nodes.
     *
     * @param request The request
     * @param now The current timestamp
     */
    @Override
    public void send(ClientRequest request, long now) {
        String nodeId = request.request().destination();
        // 检测是否能够向指定Node发送请求
        if (!canSendRequest(nodeId))
            throw new IllegalStateException("Attempt to send a request to node " + nodeId + " which is not ready.");
        // 设置KafkaChannel.send字段，并将请求放入InFlightRequests等待响应
        doSend(request, now);
    }

    private void doSend(ClientRequest request, long now) {
        request.setSendTimeMs(now);
        // 将请求添加到inFlightRequest队列中等待响应
        this.inFlightRequests.add(request);
        // 将请求交由KafkaChannel处理
        selector.send(request.request());
    }

    /**
     * Do actual reads and writes to sockets.
     *
     * @param timeout The maximum amount of time to wait (in ms) for responses if there are none immediately,
     *                must be non-negative. The actual timeout will be the minimum of timeout, request timeout and
     *                metadata timeout
     * @param now The current time in milliseconds
     * @return The list of responses received
     */
    @Override
    public List<ClientResponse> poll(long timeout, long now) {
    	// 每次poll()的时候都会判断是否需要更新Metadata
        long metadataTimeout = metadataUpdater.maybeUpdate(now);
        try {
        	// 执行IO操作
            this.selector.poll(Utils.min(timeout, metadataTimeout, requestTimeoutMs));
        } catch (IOException e) {
            log.error("Unexpected error during I/O", e);
        }

        // process completed actions
        long updatedNow = this.time.milliseconds();
        // 响应队列
        List<ClientResponse> responses = new ArrayList<>();
        // 处理completeSends队列
        handleCompletedSends(responses, updatedNow);
        // 处理completeReceives队列
        handleCompletedReceives(responses, updatedNow);
        // 处理disconnected列表
        handleDisconnections(responses, updatedNow);
        // 处理connected列表
        handleConnections();
        // 处理InFlightRequest中超时请求
        handleTimedOutRequests(responses, updatedNow);

        // invoke callbacks
		// 遍历ClientResponse
        for (ClientResponse response : responses) {
        	// 如果对应的ClientRequest有回调就执行回调
            if (response.request().hasCallback()) {
                try {
                    response.request().callback().onComplete(response);
                } catch (Exception e) {
                    log.error("Uncaught error in request completion:", e);
                }
            }
        }

        return responses;
    }

    /**
     * Get the number of in-flight requests
     */
    @Override
    public int inFlightRequestCount() {
        return this.inFlightRequests.inFlightRequestCount();
    }

    /**
     * Get the number of in-flight requests for a given node
     */
    @Override
    public int inFlightRequestCount(String node) {
        return this.inFlightRequests.inFlightRequestCount(node);
    }

    /**
     * Generate a request header for the given API key
     *
     * @param key The api key
     * @return A request header with the appropriate client id and correlation id
     */
    @Override
    public RequestHeader nextRequestHeader(ApiKeys key) {
        return new RequestHeader(key.id, clientId, correlation++);
    }

    /**
     * Generate a request header for the given API key and version
     *
     * @param key The api key
     * @param version The api version
     * @return A request header with the appropriate client id and correlation id
     */
    @Override
    public RequestHeader nextRequestHeader(ApiKeys key, short version) {
        return new RequestHeader(key.id, version, clientId, correlation++);
    }

    /**
     * Interrupt the client if it is blocked waiting on I/O.
     */
    @Override
    public void wakeup() {
        this.selector.wakeup();
    }

    /**
     * Close the network client
     */
    @Override
    public void close() {
        this.selector.close();
    }

    /**
     * Choose the node with the fewest outstanding requests which is at least eligible for connection. This method will
     * prefer a node with an existing connection, but will potentially choose a node for which we don't yet have a
     * connection if all existing connections are in use. This method will never choose a node for which there is no
     * existing connection and from which we have disconnected within the reconnect backoff period.
     *
     * @return The node with the fewest in-flight requests.
     */
    @Override
    public Node leastLoadedNode(long now) {
		// 获取存放在Cluster中的Node节点列表
        List<Node> nodes = this.metadataUpdater.fetchNodes();
        int inflight = Integer.MAX_VALUE;
        Node found = null;
		
        // 获得随机偏移
        int offset = this.randOffset.nextInt(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
        	// 根据随机偏移随机选一个Node
            int idx = (offset + i) % nodes.size();
            Node node = nodes.get(idx);
            // 获得该Node节点中请求队列的中的请求数量
            int currInflight = this.inFlightRequests.inFlightRequestCount(node.idString());
            if (currInflight == 0 && this.connectionStates.isConnected(node.idString())) {
            	// 如果该Node节点的请求队列中没有请求，且该Node节点是连接的，就选该节点
                // if we find an established connection with no in-flight requests we can stop right away
                return node;
            } else if (!this.connectionStates.isBlackedOut(node.idString(), now) && currInflight < inflight) {
                // otherwise if this is the best we have found so far, record that
				// 如果不符合，则重新选择
                inflight = currInflight;
                found = node;
            }
        }

        return found;
    }

    public static Struct parseResponse(ByteBuffer responseBuffer, RequestHeader requestHeader) {
        ResponseHeader responseHeader = ResponseHeader.parse(responseBuffer);
        // Always expect the response version id to be the same as the request version id
        short apiKey = requestHeader.apiKey();
        short apiVer = requestHeader.apiVersion();
        Struct responseBody = ProtoUtils.responseSchema(apiKey, apiVer).read(responseBuffer);
        correlate(requestHeader, responseHeader);
        return responseBody;
    }

    /**
     * Post process disconnection of a node
     *
     * @param responses The list of responses to update
     * @param nodeId Id of the node to be disconnected
     * @param now The current time
     */
    private void processDisconnection(List<ClientResponse> responses, String nodeId, long now) {
    	// 更新连接状态
        connectionStates.disconnected(nodeId, now);
        for (ClientRequest request : this.inFlightRequests.clearAll(nodeId)) {
            log.trace("Cancelled request {} due to node {} being disconnected", request, nodeId);
            // 调用metadataUpdater.maybeHandleDisconnection()处理MetadataRequest
            if (!metadataUpdater.maybeHandleDisconnection(request))
            	// 如果不是MetadataRequest，则创建ClientResponse并添加到responses集合，第三个参数为true标识不是正常响应，而是断开连接的响应
                responses.add(new ClientResponse(request, now, true, null));
        }
    }

    /**
     * Iterate over all the inflight requests and expire any requests that have exceeded the configured requestTimeout.
     * The connection to the node associated with the request will be terminated and will be treated as a disconnection.
     *
     * @param responses The list of responses to update
     * @param now The current time
     */
    private void handleTimedOutRequests(List<ClientResponse> responses, long now) {
    	// 从inFlightRequests中获取请求超时的Node的集合
		// requestTimeoutMs由timeout.ms参数决定，但该配置已废弃推荐使用request.timeout.ms配置
        List<String> nodeIds = this.inFlightRequests.getNodesWithTimedOutRequests(now, this.requestTimeoutMs);
        // 遍历Node关闭连接并更新状态
        for (String nodeId : nodeIds) {
            // close connection to the node
            this.selector.close(nodeId);
            log.debug("Disconnecting from node {} due to request timeout.", nodeId);
            processDisconnection(responses, nodeId, now);
        }

        // we disconnected, so we should probably refresh our metadata
		// 有超时请求，因此需要更新Metadata元数据
        if (nodeIds.size() > 0)
            metadataUpdater.requestUpdate();
    }

    /**
     * Handle any completed request send. In particular if no response is expected consider the request complete.
     *
     * @param responses The list of responses to update
     * @param now The current time
     */
    private void handleCompletedSends(List<ClientResponse> responses, long now) {
        // if no response is expected then when the send is completed, return it
        for (Send send : this.selector.completedSends()) {
        	// 获取指定队列的第一个元素
            ClientRequest request = this.inFlightRequests.lastSent(send.destination());
            // 检测请求是否需要响应
            if (!request.expectResponse()) {
            	// 将inFlightRequests中对应队列中的第一个请求删除
                this.inFlightRequests.completeLastSent(send.destination());
                // 生成ClientResponse对象，添加到responses集合
                responses.add(new ClientResponse(request, now, false, null));
            }
        }
    }

    /**
     * Handle any completed receives and update the response list with the responses received.
     *
     * @param responses The list of responses to update
     * @param now The current time
     */
    private void handleCompletedReceives(List<ClientResponse> responses, long now) {
    	// 遍历completeReceives中的NetworkReceive，completeReceives中存储了接收完成的封装了响应的NetworkReceives对象
        for (NetworkReceive receive : this.selector.completedReceives()) {
        	// 获取返回响应的NodeId
            String source = receive.source();
            // 从inFlightRequests中取出对应的ClientRequest
            ClientRequest req = inFlightRequests.completeNext(source);
            // 解析响应
            Struct body = parseResponse(receive.payload(), req.request().header());
			/**
			 * 调用metadataUpdater.maybeHandleCompletedReceive()方法处理MetadataResponse，
			 * 会更新Metadata中的记录的集群元数据，并唤醒所有等待Metadata更新完成的线程
			 */
			if (!metadataUpdater.maybeHandleCompletedReceive(req, now, body))
				// 如果不是MetadataResponse，则创建ClientResponse并添加到responses集合
                responses.add(new ClientResponse(req, now, false, body));
        }
    }

    /**
     * Handle any disconnected connections
     *
     * @param responses The list of responses that completed with the disconnection
     * @param now The current time
     */
    private void handleDisconnections(List<ClientResponse> responses, long now) {
    	// 更新连接状态，并清理掉InFlightRequests中断开连接的Node对应的ClientRequest
        for (String node : this.selector.disconnected()) {
            log.debug("Node {} disconnected.", node);
            processDisconnection(responses, node, now);
        }
        // we got a disconnect so we should probably refresh our metadata and see if that broker is dead
        if (this.selector.disconnected().size() > 0)
        	// 有连接断开了，标识需要更新集群元数据
            metadataUpdater.requestUpdate();
    }

    /**
     * Record any newly completed connections
     */
    private void handleConnections() {
    	// 遍历connected列表，修改节点的连接状态
        for (String node : this.selector.connected()) {
            log.debug("Completed connection to node {}", node);
            this.connectionStates.connected(node);
        }
    }

    /**
     * Validate that the response corresponds to the request we expect or else explode
     */
    private static void correlate(RequestHeader requestHeader, ResponseHeader responseHeader) {
        if (requestHeader.correlationId() != responseHeader.correlationId())
            throw new IllegalStateException("Correlation id for response (" + responseHeader.correlationId()
                    + ") does not match request (" + requestHeader.correlationId() + ")");
    }

    /**
     * Initiate a connection to the given node
     */
    private void initiateConnect(Node node, long now) {
        String nodeConnectionId = node.idString();
        try {
            log.debug("Initiating connection to node {} at {}:{}.", node.id(), node.host(), node.port());
            this.connectionStates.connecting(nodeConnectionId, now);
            // 发起连接
            selector.connect(nodeConnectionId,
                             new InetSocketAddress(node.host(), node.port()),
                             this.socketSendBuffer,
                             this.socketReceiveBuffer);
        } catch (IOException e) {
            /* attempt failed, we'll try again after the backoff */
            connectionStates.disconnected(nodeConnectionId, now);
            /* maybe the problem is our metadata, update it */
			// 如果发起连接失败，可能是由于集群元数据发生了变化，这里会触发一次Metadata的更新
            metadataUpdater.requestUpdate();
            log.debug("Error connecting to node {} at {}:{}:", node.id(), node.host(), node.port(), e);
        }
    }

    class DefaultMetadataUpdater implements MetadataUpdater {

        /* the current cluster metadata */
        // 指向记录了集群数据的Metadata
        private final Metadata metadata;

        /* true iff there is a metadata request that has been sent and for which we have not yet received a response */
		// 标识是否已经发送了MetadataRequest请求更新Metadata
        private boolean metadataFetchInProgress;

        /* the last timestamp when no broker node is available to connect */
		// 当检测到没有可用节点时，会用此字段记录时间戳
        private long lastNoNodeAvailableMs;

        DefaultMetadataUpdater(Metadata metadata) {
            this.metadata = metadata;
            this.metadataFetchInProgress = false;
            this.lastNoNodeAvailableMs = 0;
        }

        @Override
        public List<Node> fetchNodes() {
            return metadata.fetch().nodes();
        }

        @Override
        public boolean isUpdateDue(long now) {
            return !this.metadataFetchInProgress && this.metadata.timeToNextUpdate(now) == 0;
        }

        @Override
        public long maybeUpdate(long now) {
			/**
			 * 调用Metadata.timeToNextUpdate()方法，其中会检测needUpdate的值、退避时间、是否长时间未更新
			 * 最终得到一个下次更新集群元数据的时间戳
			 */
			// should we update our metadata?
            long timeToNextMetadataUpdate = metadata.timeToNextUpdate(now);
            // 获取下次尝试重新连接服务端的时间戳
			long timeToNextReconnectAttempt = Math.max(this.lastNoNodeAvailableMs + metadata.refreshBackoff() - now, 0);
			// 检测metadataFetchInProgress字段，判断是否已经发送了请求
			long waitForMetadataFetch = this.metadataFetchInProgress ? Integer.MAX_VALUE : 0;
            // if there is no node available to connect, back off refreshing metadata
			// 计算当前距离下次可以发送MetadataRequest请求的时间差
            long metadataTimeout = Math.max(Math.max(timeToNextMetadataUpdate, timeToNextReconnectAttempt),
                    waitForMetadataFetch);
            
			if (metadataTimeout == 0) {
            	// 允许发送MetadataRequest请求
                // Beware that the behavior of this method and the computation of timeouts for poll() are
                // highly dependent on the behavior of leastLoadedNode.
				// 找到负载最小的Node，若没有可用就返回null
                Node node = leastLoadedNode(now);
                // 将跟新Metadata的请求发送给这个Node；这里只会创建并缓存MetadataRequest，等待下次poll()方法发送请求
                maybeUpdate(now, node);
            }

            return metadataTimeout;
        }

        @Override
        public boolean maybeHandleDisconnection(ClientRequest request) {
            ApiKeys requestKey = ApiKeys.forId(request.request().header().apiKey());
			
            // 检测是否为MetadataRequest请求
            if (requestKey == ApiKeys.METADATA) {
                Cluster cluster = metadata.fetch();
                if (cluster.isBootstrapConfigured()) {
                    int nodeId = Integer.parseInt(request.request().destination());
                    Node node = cluster.nodeById(nodeId);
                    if (node != null)
                        log.warn("Bootstrap broker {}:{} disconnected", node.host(), node.port());
                }
				// 更新metadataFetchInProgress字段
                metadataFetchInProgress = false;
                return true;
            }

            return false;
        }

        @Override
        public boolean maybeHandleCompletedReceive(ClientRequest req, long now, Struct body) {
            short apiKey = req.request().header().apiKey();
            // 检测是否是MetadataRequest请求的响应
            if (apiKey == ApiKeys.METADATA.id && req.isInitiatedByNetworkClient()) {
            	// 处理请求响应
                handleResponse(req.request().header(), body, now);
                return true;
            }
            return false;
        }

        @Override
        public void requestUpdate() {
            this.metadata.requestUpdate();
        }

        private void handleResponse(RequestHeader header, Struct body, long now) {
        	// 修改metadataFetchInProgress
            this.metadataFetchInProgress = false;
            // 解析MetadataResponse
            MetadataResponse response = new MetadataResponse(body);
            // 根据response返回的数据，创建新的Cluster对象
            Cluster cluster = response.cluster();
            // check if any topics metadata failed to get updated
            Map<String, Errors> errors = response.errors();
            if (!errors.isEmpty())
                log.warn("Error while fetching metadata with correlation id {} : {}", header.correlationId(), errors);

            // don't update the cluster if there are no valid nodes...the topic we want may still be in the process of being
            // created which means we will get errors and no nodes until it exists
            if (cluster.nodes().size() > 0) {
            	// 首先通知Metadata上的监听器，然后更新cluster字段，最后唤醒等待Metadata更新完成的线程
                this.metadata.update(cluster, now);
            } else {
                log.trace("Ignoring empty metadata response with correlation id {}.", header.correlationId());
                // 更新Metadata失败，将lastRefreshMs字段更新
                this.metadata.failedUpdate(now);
            }
        }

        /**
         * Create a metadata request for the given topics
         */
        private ClientRequest request(long now, String node, MetadataRequest metadata) {
			// 注意，此时RequestSend的destination字段的值置为了NodeId
            RequestSend send = new RequestSend(node, nextRequestHeader(ApiKeys.METADATA), metadata.toStruct());
            return new ClientRequest(now, true, send, null, true);
        }

        /**
         * Add a metadata request to the list of sends if we can make one
         */
        private void maybeUpdate(long now, Node node) {
        	// 如果没有可用Node，将直接返回
            if (node == null) {
                log.debug("Give up sending metadata request since no node is available");
                // mark the timestamp for no node available to connect
				// 记录lastNoNodeAvailableMs字段
                this.lastNoNodeAvailableMs = now;
                return;
            }
            String nodeConnectionId = node.idString();

            // 检测是否允许向该Node发送请求
            if (canSendRequest(nodeConnectionId)) {
                this.metadataFetchInProgress = true;
                MetadataRequest metadataRequest;
                // 指定需要更新元数据的Topic
                if (metadata.needMetadataForAllTopics())
					// 更新全部主题的元数据
                    metadataRequest = MetadataRequest.allTopics();
                else
					// 更新部分主题的元数据
                    metadataRequest = new MetadataRequest(new ArrayList<>(metadata.topics()));
                // 将请求更新Metadata的MetadataRequest封装成ClientRequest
                ClientRequest clientRequest = request(now, nodeConnectionId, metadataRequest);
                log.debug("Sending metadata request {} to node {}", metadataRequest, node.id());
                // 缓存请求，在下次poll()操作中会将其发出，返回的response会在handleCompleteReceives()中处理
                doSend(clientRequest, now);
            } else if (connectionStates.canConnect(nodeConnectionId, now)) {
                // we don't have a connection to this node right now, make one
                log.debug("Initialize connection to node {} for sending metadata request", node.id());
                // 初始化连接
                initiateConnect(node, now);
                // If initiateConnect failed immediately, this node will be put into blackout and we
                // should allow immediately retrying in case there is another candidate node. If it
                // is still connecting, the worst case is that we end up setting a longer timeout
                // on the next round and then wait for the response.
            } else { // connected, but can't send more OR connecting
                // In either case, we just need to wait for a network event to let us know the selected
                // connection might be usable again.
				// 已成功连接到指定节点，但不能发送请求，则更新lastNoNodeAvailableMs
                this.lastNoNodeAvailableMs = now;
            }
        }

    }

}
