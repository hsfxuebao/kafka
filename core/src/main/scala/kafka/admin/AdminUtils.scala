/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.admin

import kafka.common._
import kafka.cluster.Broker
import kafka.log.LogConfig
import kafka.server.ConfigType
import kafka.utils._
import kafka.utils.ZkUtils._

import java.util.Random
import java.util.Properties
import org.apache.kafka.common.Node
import org.apache.kafka.common.errors.{ReplicaNotAvailableException, InvalidTopicException, LeaderNotAvailableException}
import org.apache.kafka.common.protocol.{Errors, SecurityProtocol}
import org.apache.kafka.common.requests.MetadataResponse

import scala.collection._
import JavaConverters._
import mutable.ListBuffer
import scala.collection.mutable
import collection.Map
import collection.Set
import org.I0Itec.zkclient.exception.ZkNodeExistsException

object AdminUtils extends Logging {
  val rand = new Random
  val AdminClientId = "__admin_client"
  val EntityConfigChangeZnodePrefix = "config_change_"

  /**
   * There are 3 goals of replica assignment:
   *
   * 1. Spread the replicas evenly among brokers.
   * 2. For partitions assigned to a particular broker, their other replicas are spread over the other brokers.
   * 3. If all brokers have rack information, assign the replicas for each partition to different racks if possible
   *
   * To achieve this goal for replica assignment without considering racks, we:
   * 1. Assign the first replica of each partition by round-robin, starting from a random position in the broker list.
   * 2. Assign the remaining replicas of each partition with an increasing shift.
   *
   * Here is an example of assigning
   * broker-0  broker-1  broker-2  broker-3  broker-4
   * p0        p1        p2        p3        p4       (1st replica)
   * p5        p6        p7        p8        p9       (1st replica)
   * p4        p0        p1        p2        p3       (2nd replica)
   * p8        p9        p5        p6        p7       (2nd replica)
   * p3        p4        p0        p1        p2       (3nd replica)
   * p7        p8        p9        p5        p6       (3nd replica)
   *
   * To create rack aware assignment, this API will first create a rack alternated broker list. For example,
   * from this brokerID -> rack mapping:
   *
   * 0 -> "rack1", 1 -> "rack3", 2 -> "rack3", 3 -> "rack2", 4 -> "rack2", 5 -> "rack1"
   *
   * The rack alternated list will be:
   *
   * 0, 3, 1, 5, 4, 2
   *
   * Then an easy round-robin assignment can be applied. Assume 6 partitions with replication factor of 3, the assignment
   * will be:
   *
   * 0 -> 0,3,1
   * 1 -> 3,1,5
   * 2 -> 1,5,4
   * 3 -> 5,4,2
   * 4 -> 4,2,0
   * 5 -> 2,0,3
   *
   * Once it has completed the first round-robin, if there are more partitions to assign, the algorithm will start
   * shifting the followers. This is to ensure we will not always get the same set of sequences.
   * In this case, if there is another partition to assign (partition #6), the assignment will be:
   *
   * 6 -> 0,4,2 (instead of repeating 0,3,1 as partition 0)
   *
   * The rack aware assignment always chooses the 1st replica of the partition using round robin on the rack alternated
   * broker list. For rest of the replicas, it will be biased towards brokers on racks that do not have
   * any replica assignment, until every rack has a replica. Then the assignment will go back to round-robin on
   * the broker list.
   *
   * As the result, if the number of replicas is equal to or greater than the number of racks, it will ensure that
   * each rack will get at least one replica. Otherwise, each rack will get at most one replica. In a perfect
   * situation where the number of replicas is the same as the number of racks and each rack has the same number of
   * brokers, it guarantees that the replica distribution is even across brokers and racks.
   *
   * @return a Map from partition id to replica ids
   * @throws AdminOperationException If rack information is supplied but it is incomplete, or if it is not possible to
   *                                 assign each replica to a unique rack.
   *
   */
  def assignReplicasToBrokers(brokerMetadatas: Seq[BrokerMetadata],
                              nPartitions: Int,
                              replicationFactor: Int,
                              fixedStartIndex: Int = -1,
                              startPartitionId: Int = -1): Map[Int, Seq[Int]] = {
    // 检查参数
    if (nPartitions <= 0)
      throw new AdminOperationException("number of partitions must be larger than 0")
    if (replicationFactor <= 0)
      throw new AdminOperationException("replication factor must be larger than 0")
    if (replicationFactor > brokerMetadatas.size)
      throw new AdminOperationException(s"replication factor: $replicationFactor larger than available brokers: ${brokerMetadatas.size}")
    if (brokerMetadatas.forall(_.rack.isEmpty))
      // 不需要机架感知的分配
      assignReplicasToBrokersRackUnaware(nPartitions, replicationFactor, brokerMetadatas.map(_.id), fixedStartIndex,
        startPartitionId)
    else {
      if (brokerMetadatas.exists(_.rack.isEmpty))
        throw new AdminOperationException("Not all brokers have rack information for replica rack aware assignment")
      // 需要机架感知的分配
      assignReplicasToBrokersRackAware(nPartitions, replicationFactor, brokerMetadatas, fixedStartIndex,
        startPartitionId)
    }
  }

  // 不需要考虑机架感知的分配
  private def assignReplicasToBrokersRackUnaware(nPartitions: Int,
                                                 replicationFactor: Int,
                                                 brokerList: Seq[Int],
                                                 fixedStartIndex: Int,
                                                 startPartitionId: Int): Map[Int, Seq[Int]] = {
    // 用于记录副本分配结果
    val ret = mutable.Map[Int, Seq[Int]]()
    val brokerArray = brokerList.toArray
    // 如果没有指定起始的Broker Index，则随机选择一个起始Broker进行分配
    val startIndex = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(brokerArray.length)
    // 选择起始分区
    var currentPartitionId = math.max(0, startPartitionId)
    // nextReplicaShift指定了副本的间隔，目的是为了更均匀地将副本分配到不同的Broker上
    var nextReplicaShift = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(brokerArray.length)
    // 遍历次数为分区数
    for (_ <- 0 until nPartitions) {
      if (currentPartitionId > 0 && (currentPartitionId % brokerArray.length == 0))
        // 递增nextReplicaShift
        nextReplicaShift += 1
      // 将"优先副本"分配到startIndex指定的Broker上
      val firstReplicaIndex = (currentPartitionId + startIndex) % brokerArray.length
      // 记录"优先副本"的分配结果
      val replicaBuffer = mutable.ArrayBuffer(brokerArray(firstReplicaIndex))
      for (j <- 0 until replicationFactor - 1) // 分配当前分区的其他副本
        replicaBuffer += brokerArray(replicaIndex(firstReplicaIndex, nextReplicaShift, j, brokerArray.length))
      ret.put(currentPartitionId, replicaBuffer)
      currentPartitionId += 1 // 分配下一个分区
    }
    ret
  }

  // 需要考虑机架感知的分配
  private def assignReplicasToBrokersRackAware(nPartitions: Int,
                                               replicationFactor: Int,
                                               brokerMetadatas: Seq[BrokerMetadata],
                                               fixedStartIndex: Int,
                                               startPartitionId: Int): Map[Int, Seq[Int]] = {
    // 对机架信息进行转换，得到的字典中，键是Broker的ID，值为所在的机架名称
    val brokerRackMap = brokerMetadatas.collect { case BrokerMetadata(id, Some(rack)) =>
      id -> rack
    }.toMap
    // 统计机架个数
    val numRacks = brokerRackMap.values.toSet.size
    // 基于机架信息生产一个Broker列表，轮询每个机架上的Broker，不同机架上的Broker交替出现
    val arrangedBrokerList = getRackAlternatedBrokerList(brokerRackMap)
    // 所有机架上的Broker数量
    val numBrokers = arrangedBrokerList.size
    // 用于记录副本分配结果
    val ret = mutable.Map[Int, Seq[Int]]()
    // 选择起始Broker进行分配
    val startIndex = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(arrangedBrokerList.size)
    // 选择起始分区
    var currentPartitionId = math.max(0, startPartitionId)
    // 指定副本的间隔
    var nextReplicaShift = if (fixedStartIndex >= 0) fixedStartIndex else rand.nextInt(arrangedBrokerList.size)
    // 遍历所有分区
    for (_ <- 0 until nPartitions) {
      if (currentPartitionId > 0 && (currentPartitionId % arrangedBrokerList.size == 0))
        // 递增nextReplicaShift
        nextReplicaShift += 1
      // 计算"优先副本"所在的Broker
      val firstReplicaIndex = (currentPartitionId + startIndex) % arrangedBrokerList.size
      val leader = arrangedBrokerList(firstReplicaIndex)
      // 记录"优先副本"所在的Broker
      val replicaBuffer = mutable.ArrayBuffer(leader)
      // 记录以及分配了当前分区的副本的机架信息
      val racksWithReplicas = mutable.Set(brokerRackMap(leader))
      // 记录已经分配了当前分区的副本的Broker信息
      val brokersWithReplicas = mutable.Set(leader)
      var k = 0
      // 遍历分配剩余的副本
      for (_ <- 0 until replicationFactor - 1) {
        var done = false
        while (!done) {
          // 通过replicaIndex()方法计算当前副本所在的Broker
          val broker = arrangedBrokerList(replicaIndex(firstReplicaIndex, nextReplicaShift * numRacks, k, arrangedBrokerList.size))
          val rack = brokerRackMap(broker)
          // Skip this broker if
          // 1. there is already a broker in the same rack that has assigned a replica AND there is one or more racks
          //    that do not have any replica, or
          // 2. the broker has already assigned a replica AND there is one or more brokers that do not have replica assigned
          /**
            * 检测是否跳过此Broker，满足以下之一就跳过：
            * 1. 当前机架上已经分配过其他副本，而且存在机架还未分配副本；
            * 2. 当前Broker上已经分配过其他副本，而且存在其他Broker还未分配副本。
            */
          if ((!racksWithReplicas.contains(rack) || racksWithReplicas.size == numRacks)
              && (!brokersWithReplicas.contains(broker) || brokersWithReplicas.size == numBrokers)) {
            // 记录分配结果
            replicaBuffer += broker
            // 记录此机架已经分配了当前分区的副本
            racksWithReplicas += rack
            // 记录此Broker已经分配了当前分区的副本
            brokersWithReplicas += broker
            // 标识此副本的分配完成
            done = true
          }
          k += 1
        }
      }
      ret.put(currentPartitionId, replicaBuffer)
      currentPartitionId += 1
    }
    ret
  }

  /**
    * Given broker and rack information, returns a list of brokers alternated by the rack. Assume
    * this is the rack and its brokers:
    *
    * rack1: 0, 1, 2
    * rack2: 3, 4, 5
    * rack3: 6, 7, 8
    *
    * This API would return the list of 0, 3, 6, 1, 4, 7, 2, 5, 8
    *
    * This is essential to make sure that the assignReplicasToBrokers API can use such list and
    * assign replicas to brokers in a simple round-robin fashion, while ensuring an even
    * distribution of leader and replica counts on each broker and that replicas are
    * distributed to all racks.
    */
  private[admin] def getRackAlternatedBrokerList(brokerRackMap: Map[Int, String]): IndexedSeq[Int] = {
    // 得到 机架 -> 该机架上的Broker的ID集合的迭代器
    val brokersIteratorByRack = getInverseMap(brokerRackMap).map { case (rack, brokers) =>
      (rack, brokers.toIterator)
    }
    // 对机架进行排序
    val racks = brokersIteratorByRack.keys.toArray.sorted
    // 定义结果数组
    val result = new mutable.ArrayBuffer[Int]
    // 机架索引
    var rackIndex = 0
    /**
      * brokerRackMap的大小即为Broker的数量。
      * 这里的操作实现如下：
      * 因为之前排过序了，所以brokersIteratorByRack中机架对应的BrokerID是有序的；
      * 按照Broker的数量为遍历次数进行循环，轮流取每个机架上的Broker中的一个。
      * 例如，有以下Broker分配信息：
      *     rack1: 0, 1, 2
      *     rack2: 3, 4, 5
      *     rack3: 6, 7, 8
      * 一共9个Broker，那么会遍历9次：
      * 第1次：取rack1，取rack1上的Broker-0
      * 第2次：取rack2，取rack1上的Broker-3
      * 第3次：取rack3，取rack1上的Broker-6
      * 第4次：取rack1，取rack2上的Broker-1
      * 第5次：取rack2，取rack2上的Broker-5
      * 第6次：取rack3，取rack2上的Broker-7
      * 第7次：取rack1，取rack3上的Broker-2
      * 第8次：取rack2，取rack3上的Broker-5
      * 第9次：取rack3，取rack3上的Broker-8
      *
      * 最终得到的Broker ID序列为：
      * 0, 3, 6, 1, 4, 7, 2, 5, 8
      */
    while (result.size < brokerRackMap.size) {
      // 获取机架对应的BrokerID集合迭代器
      val rackIterator = brokersIteratorByRack(racks(rackIndex))
      // 取其中一个Broker
      if (rackIterator.hasNext)
        result += rackIterator.next()
      // 机架索引自增
      rackIndex = (rackIndex + 1) % racks.length
    }
    result
  }

  private[admin] def getInverseMap(brokerRackMap: Map[Int, String]): Map[String, Seq[Int]] = {
    brokerRackMap.toSeq.map { case (id, rack) => (rack, id) }
      .groupBy { case (rack, _) => rack } // 以机架进行分组，得到的结果为，Map[机架 -> Seq[该机架上Broker的ID]]
      // 遍历每个分组，对每个机架上的Broker的ID进行升序排序
      .map { case (rack, rackAndIdList) => (rack, rackAndIdList.map { case (_, id) => id }.sorted) }
  }
 /**
  * Add partitions to existing topic with optional replica assignment
  *
  * @param zkUtils Zookeeper utilities
  * @param topic Topic for adding partitions to
  * @param numPartitions Number of partitions to be set
  * @param replicaAssignmentStr Manual replica assignment
  * @param checkBrokerAvailable Ignore checking if assigned replica broker is available. Only used for testing
  */
  def addPartitions(zkUtils: ZkUtils,
                    topic: String,
                    numPartitions: Int = 1,
                    replicaAssignmentStr: String = "",
                    checkBrokerAvailable: Boolean = true,
                    rackAwareMode: RackAwareMode = RackAwareMode.Enforced) {
    // 从Zookeeper获取此Topic当前的副本分配情况
    val existingPartitionsReplicaList = zkUtils.getReplicaAssignmentForTopics(List(topic))
    if (existingPartitionsReplicaList.size == 0)
      throw new AdminOperationException("The topic %s does not exist".format(topic))

    // 获取ID为0的分区的副本分配情况
    val existingReplicaListForPartitionZero = existingPartitionsReplicaList.find(p => p._1.partition == 0) match {
      case None => throw new AdminOperationException("the topic does not have partition with id 0, it should never happen")
      case Some(headPartitionReplica) => headPartitionReplica._2
    }

    // 获取ID未0的分区的副本数量
    val partitionsToAdd = numPartitions - existingPartitionsReplicaList.size
    if (partitionsToAdd <= 0)
      throw new AdminOperationException("The number of partitions for a topic can only be increased")

    // create the new partition replication list
    // 只能增加分区数量，如果指定的分区数量小于当前分区数，则抛出异常
    val brokerMetadatas = getBrokerMetadatas(zkUtils, rackAwareMode)
    val newPartitionReplicaList =
      if (replicaAssignmentStr == null || replicaAssignmentStr == "") {
        // 确定startIndex
        val startIndex = math.max(0, brokerMetadatas.indexWhere(_.id >= existingReplicaListForPartitionZero.head))
        // 对新增分区进行副本自动分配，注意fixedStartIndex和startPartitionId参数的取值
        AdminUtils.assignReplicasToBrokers(brokerMetadatas, partitionsToAdd, existingReplicaListForPartitionZero.size,
          startIndex, existingPartitionsReplicaList.size)
      }
      else
        // 解析replica-assignment参数，其中会进行一系列有效性检测
        getManualReplicaAssignment(replicaAssignmentStr, brokerMetadatas.map(_.id).toSet,
          existingPartitionsReplicaList.size, checkBrokerAvailable)

    // check if manual assignment has the right replication factor
    // 检测新增Partition的副本数是否正常
    val unmatchedRepFactorList = newPartitionReplicaList.values.filter(p => (p.size != existingReplicaListForPartitionZero.size))
    if (unmatchedRepFactorList.size != 0)
      throw new AdminOperationException("The replication factor in manual replication assignment " +
        " is not equal to the existing replication factor for the topic " + existingReplicaListForPartitionZero.size)

    info("Add partition list for %s is %s".format(topic, newPartitionReplicaList))
    // 将原有分区的新增分区的副本分配整理为集合
    val partitionReplicaList = existingPartitionsReplicaList.map(p => p._1.partition -> p._2)
    // add the new list
    // 将最终的副本分配结果写入Zookeeper
    partitionReplicaList ++= newPartitionReplicaList
    // 该方法最后一个参数表示只更新副本分配情况
    AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, topic, partitionReplicaList, update = true)
  }

  def getManualReplicaAssignment(replicaAssignmentList: String, availableBrokerList: Set[Int], startPartitionId: Int, checkBrokerAvailable: Boolean = true): Map[Int, List[Int]] = {
    var partitionList = replicaAssignmentList.split(",")
    val ret = new mutable.HashMap[Int, List[Int]]()
    var partitionId = startPartitionId
    partitionList = partitionList.takeRight(partitionList.size - partitionId)
    for (i <- 0 until partitionList.size) {
      val brokerList = partitionList(i).split(":").map(s => s.trim().toInt)
      if (brokerList.size <= 0)
        throw new AdminOperationException("replication factor must be larger than 0")
      if (brokerList.size != brokerList.toSet.size)
        throw new AdminOperationException("duplicate brokers in replica assignment: " + brokerList)
      if (checkBrokerAvailable && !brokerList.toSet.subsetOf(availableBrokerList))
        throw new AdminOperationException("some specified brokers not available. specified brokers: " + brokerList.toString +
          "available broker:" + availableBrokerList.toString)
      ret.put(partitionId, brokerList.toList)
      if (ret(partitionId).size != ret(startPartitionId).size)
        throw new AdminOperationException("partition " + i + " has different replication factor: " + brokerList)
      partitionId = partitionId + 1
    }
    ret.toMap
  }

  def deleteTopic(zkUtils: ZkUtils, topic: String) {
    try {
      zkUtils.createPersistentPath(getDeleteTopicPath(topic))
    } catch {
      case e1: ZkNodeExistsException => throw new TopicAlreadyMarkedForDeletionException(
        "topic %s is already marked for deletion".format(topic))
      case e2: Throwable => throw new AdminOperationException(e2.toString)
    }
  }

  def isConsumerGroupActive(zkUtils: ZkUtils, group: String) = {
    zkUtils.getConsumersInGroup(group).nonEmpty
  }

  /**
   * Delete the whole directory of the given consumer group if the group is inactive.
   *
   * @param zkUtils Zookeeper utilities
   * @param group Consumer group
   * @return whether or not we deleted the consumer group information
   */
  def deleteConsumerGroupInZK(zkUtils: ZkUtils, group: String) = {
    if (!isConsumerGroupActive(zkUtils, group)) {
      val dir = new ZKGroupDirs(group)
      zkUtils.deletePathRecursive(dir.consumerGroupDir)
      true
    }
    else false
  }

  /**
   * Delete the given consumer group's information for the given topic in Zookeeper if the group is inactive.
   * If the consumer group consumes no other topics, delete the whole consumer group directory.
   *
   * @param zkUtils Zookeeper utilities
   * @param group Consumer group
   * @param topic Topic of the consumer group information we wish to delete
   * @return whether or not we deleted the consumer group information for the given topic
   */
  def deleteConsumerGroupInfoForTopicInZK(zkUtils: ZkUtils, group: String, topic: String) = {
    val topics = zkUtils.getTopicsByConsumerGroup(group)
    if (topics == Seq(topic)) {
      deleteConsumerGroupInZK(zkUtils, group)
    }
    else if (!isConsumerGroupActive(zkUtils, group)) {
      val dir = new ZKGroupTopicDirs(group, topic)
      zkUtils.deletePathRecursive(dir.consumerOwnerDir)
      zkUtils.deletePathRecursive(dir.consumerOffsetDir)
      true
    }
    else false
  }

  /**
   * Delete every inactive consumer group's information about the given topic in Zookeeper.
   *
   * @param zkUtils Zookeeper utilities
   * @param topic Topic of the consumer group information we wish to delete
   */
  def deleteAllConsumerGroupInfoForTopicInZK(zkUtils: ZkUtils, topic: String) {
    val groups = zkUtils.getAllConsumerGroupsForTopic(topic)
    groups.foreach(group => deleteConsumerGroupInfoForTopicInZK(zkUtils, group, topic))
  }

  def topicExists(zkUtils: ZkUtils, topic: String): Boolean =
    zkUtils.zkClient.exists(getTopicPath(topic))

  def getBrokerMetadatas(zkUtils: ZkUtils, rackAwareMode: RackAwareMode = RackAwareMode.Enforced,
                        brokerList: Option[Seq[Int]] = None): Seq[BrokerMetadata] = {
    val allBrokers = zkUtils.getAllBrokersInCluster()
    val brokers = brokerList.map(brokerIds => allBrokers.filter(b => brokerIds.contains(b.id))).getOrElse(allBrokers)
    val brokersWithRack = brokers.filter(_.rack.nonEmpty)
    if (rackAwareMode == RackAwareMode.Enforced && brokersWithRack.nonEmpty && brokersWithRack.size < brokers.size) {
      throw new AdminOperationException("Not all brokers have rack information. Add --disable-rack-aware in command line" +
        " to make replica assignment without rack information.")
    }
    val brokerMetadatas = rackAwareMode match {
      case RackAwareMode.Disabled => brokers.map(broker => BrokerMetadata(broker.id, None))
      case RackAwareMode.Safe if brokersWithRack.size < brokers.size =>
        brokers.map(broker => BrokerMetadata(broker.id, None))
      case _ => brokers.map(broker => BrokerMetadata(broker.id, broker.rack))
    }
    brokerMetadatas.sortBy(_.id)
  }

  def createTopic(zkUtils: ZkUtils,
                  topic: String,
                  partitions: Int,
                  replicationFactor: Int,
                  topicConfig: Properties = new Properties,
                  rackAwareMode: RackAwareMode = RackAwareMode.Enforced) {
    // 获取Broker信息
    val brokerMetadatas = getBrokerMetadatas(zkUtils, rackAwareMode)
    // 根据Broker信息和副本分配信息进行自动分配
    val replicaAssignment = AdminUtils.assignReplicasToBrokers(brokerMetadatas, partitions, replicationFactor)
    // 创建并更新Zookeeper中的主题分区副本分配信息
    AdminUtils.createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils, topic, replicaAssignment, topicConfig)
  }

  // 创建并更新Zookeeper中的主题分区副本分配信息
  def createOrUpdateTopicPartitionAssignmentPathInZK(zkUtils: ZkUtils,
                                                     topic: String,
                                                     partitionReplicaAssignment: Map[Int, Seq[Int]],
                                                     config: Properties = new Properties,
                                                     update: Boolean = false) {
    // validate arguments
    // 检测Topic名称是否符合要求
    Topic.validate(topic)

    // --replica-assignment参数指定的每个分区的副本数应该相同
    require(partitionReplicaAssignment.values.map(_.size).toSet.size == 1, "All partitions should have the same number of replicas.")

    // 得到/brokers/topics/[topic_name]主题路径
    val topicPath = getTopicPath(topic)

    if (!update) {
      if (zkUtils.zkClient.exists(topicPath)) // Topic已存在
        throw new TopicExistsException("Topic \"%s\" already exists.".format(topic))
      else if (Topic.hasCollisionChars(topic)) { // Topic名称存在"."或"_"
        // 获取所有的Topic名称集合，打印提示出可能与新建Topic名称产生冲突的Topic
        val allTopics = zkUtils.getAllTopics()
        val collidingTopics = allTopics.filter(t => Topic.hasCollision(topic, t))
        if (collidingTopics.nonEmpty) {
          throw new InvalidTopicException("Topic \"%s\" collides with existing topics: %s".format(topic, collidingTopics.mkString(", ")))
        }
      }
    }

    // 日志打印
    partitionReplicaAssignment.values.foreach(reps => require(reps.size == reps.toSet.size, "Duplicate replica assignment found: "  + partitionReplicaAssignment))

    // Configs only matter if a topic is being created. Changing configs via AlterTopic is not supported
    if (!update) {
      // write out the config if there is any, this isn't transactional with the partition assignments
      // 验证配置
      LogConfig.validate(config)
      // 写入配置到Zookeeper
      writeEntityConfig(zkUtils, ConfigType.Topic, topic, config)
    }

    // create the partition assignment
    // 创建分区分配
    writeTopicPartitionAssignment(zkUtils, topic, partitionReplicaAssignment, update)
  }

  // 写入主题分区副本分配信息到Zookeeper
  private def writeTopicPartitionAssignment(zkUtils: ZkUtils, topic: String, replicaAssignment: Map[Int, Seq[Int]], update: Boolean) {
    try {
      // 得到/brokers/topics/[topic_name]路径
      val zkPath = getTopicPath(topic)
      // 格式化副本分配信息为JSON字符串
      val jsonPartitionData = zkUtils.replicaAssignmentZkData(replicaAssignment.map(e => (e._1.toString -> e._2)))

      if (!update) {
        info("Topic creation " + jsonPartitionData.toString)
        // 创建持久节点
        zkUtils.createPersistentPath(zkPath, jsonPartitionData)
      } else {
        // 更新持久节点
        info("Topic update " + jsonPartitionData.toString)
        zkUtils.updatePersistentPath(zkPath, jsonPartitionData)
      }
      debug("Updated path %s with %s for replica assignment".format(zkPath, jsonPartitionData))
    } catch {
      case e: ZkNodeExistsException => throw new TopicExistsException("topic %s already exists".format(topic))
      case e2: Throwable => throw new AdminOperationException(e2.toString)
    }
  }

  /**
   * Update the config for a client and create a change notification so the change will propagate to other brokers
   *
   * @param zkUtils Zookeeper utilities used to write the config to ZK
   * @param clientId: The clientId for which configs are being changed
   * @param configs: The final set of configs that will be applied to the topic. If any new configs need to be added or
   *                 existing configs need to be deleted, it should be done prior to invoking this API
   *
   */
  def changeClientIdConfig(zkUtils: ZkUtils, clientId: String, configs: Properties) {
    changeEntityConfig(zkUtils, ConfigType.Client, clientId, configs)
  }

  /**
   * Update the config for an existing topic and create a change notification so the change will propagate to other brokers
   *
   * @param zkUtils Zookeeper utilities used to write the config to ZK
   * @param topic: The topic for which configs are being changed
   * @param configs: The final set of configs that will be applied to the topic. If any new configs need to be added or
   *                 existing configs need to be deleted, it should be done prior to invoking this API
   *
   */
  def changeTopicConfig(zkUtils: ZkUtils, topic: String, configs: Properties) {
    if(!topicExists(zkUtils, topic))
      throw new AdminOperationException("Topic \"%s\" does not exist.".format(topic))
    // remove the topic overrides
    LogConfig.validate(configs)
    changeEntityConfig(zkUtils, ConfigType.Topic, topic, configs)
  }

  private def changeEntityConfig(zkUtils: ZkUtils, entityType: String, entityName: String, configs: Properties) {
    // write the new config--may not exist if there were previously no overrides
    writeEntityConfig(zkUtils, entityType, entityName, configs)

    // create the change notification
    val seqNode = ZkUtils.EntityConfigChangesPath + "/" + EntityConfigChangeZnodePrefix
    val content = Json.encode(getConfigChangeZnodeData(entityType, entityName))
    zkUtils.zkClient.createPersistentSequential(seqNode, content)
  }

  def getConfigChangeZnodeData(entityType: String, entityName: String) : Map[String, Any] = {
    Map("version" -> 1, "entity_type" -> entityType, "entity_name" -> entityName)
  }

  /**
   * Write out the topic config to zk, if there is any
   */
  private def writeEntityConfig(zkUtils: ZkUtils, entityType: String, entityName: String, config: Properties) {
    val configMap: mutable.Map[String, String] = {
      import JavaConversions._
      config
    }
    val map = Map("version" -> 1, "config" -> configMap)
    zkUtils.updatePersistentPath(getEntityConfigPath(entityType, entityName), Json.encode(map))
  }

  /**
   * Read the entity (topic or client) config (if any) from zk
    * 获取对应的配置信息
    * 路径为/config/[entity_type]/[entity_name]
   */
  def fetchEntityConfig(zkUtils: ZkUtils, entityType: String, entity: String): Properties = {
    val str: String = zkUtils.zkClient.readData(getEntityConfigPath(entityType, entity), true)
    val props = new Properties()
    if(str != null) {
      Json.parseFull(str) match {
        case None => // there are no config overrides
        case Some(mapAnon: Map[_, _]) =>
          val map = mapAnon collect { case (k: String, v: Any) => k -> v }
          require(map("version") == 1)
          map.get("config") match {
            case Some(config: Map[_, _]) =>
              for(configTup <- config)
                configTup match {
                  case (k: String, v: String) =>
                    props.setProperty(k, v)
                  case _ => throw new IllegalArgumentException("Invalid " + entityType + " config: " + str)
                }
            case _ => throw new IllegalArgumentException("Invalid " + entityType + " config: " + str)
          }

        case o => throw new IllegalArgumentException("Unexpected value in config:(%s), entity_type: (%s), entity: (%s)"
                                                             .format(str, entityType, entity))
      }
    }
    props
  }

  def fetchAllTopicConfigs(zkUtils: ZkUtils): Map[String, Properties] =
    zkUtils.getAllTopics().map(topic => (topic, fetchEntityConfig(zkUtils, ConfigType.Topic, topic))).toMap

  def fetchAllEntityConfigs(zkUtils: ZkUtils, entityType: String): Map[String, Properties] =
    zkUtils.getAllEntitiesWithConfig(entityType).map(entity => (entity, fetchEntityConfig(zkUtils, entityType, entity))).toMap

  def fetchTopicMetadataFromZk(topic: String, zkUtils: ZkUtils): MetadataResponse.TopicMetadata =
    fetchTopicMetadataFromZk(topic, zkUtils, new mutable.HashMap[Int, Broker])

  def fetchTopicMetadataFromZk(topics: Set[String], zkUtils: ZkUtils): Set[MetadataResponse.TopicMetadata] = {
    val cachedBrokerInfo = new mutable.HashMap[Int, Broker]()
    topics.map(topic => fetchTopicMetadataFromZk(topic, zkUtils, cachedBrokerInfo))
  }

  private def fetchTopicMetadataFromZk(topic: String,
                                       zkUtils: ZkUtils,
                                       cachedBrokerInfo: mutable.HashMap[Int, Broker],
                                       protocol: SecurityProtocol = SecurityProtocol.PLAINTEXT): MetadataResponse.TopicMetadata = {
    if(zkUtils.pathExists(getTopicPath(topic))) {
      val topicPartitionAssignment = zkUtils.getPartitionAssignmentForTopics(List(topic)).get(topic).get
      val sortedPartitions = topicPartitionAssignment.toList.sortWith((m1, m2) => m1._1 < m2._1)
      val partitionMetadata = sortedPartitions.map { partitionMap =>
        val partition = partitionMap._1
        val replicas = partitionMap._2
        val inSyncReplicas = zkUtils.getInSyncReplicasForPartition(topic, partition)
        val leader = zkUtils.getLeaderForPartition(topic, partition)
        debug("replicas = " + replicas + ", in sync replicas = " + inSyncReplicas + ", leader = " + leader)

        var leaderInfo: Node = Node.noNode()
        var replicaInfo: Seq[Node] = Nil
        var isrInfo: Seq[Node] = Nil
        try {
          leaderInfo = leader match {
            case Some(l) =>
              try {
                getBrokerInfoFromCache(zkUtils, cachedBrokerInfo, List(l)).head.getNode(protocol)
              } catch {
                case e: Throwable => throw new LeaderNotAvailableException("Leader not available for partition [%s,%d]".format(topic, partition), e)
              }
            case None => throw new LeaderNotAvailableException("No leader exists for partition " + partition)
          }
          try {
            replicaInfo = getBrokerInfoFromCache(zkUtils, cachedBrokerInfo, replicas).map(_.getNode(protocol))
            isrInfo = getBrokerInfoFromCache(zkUtils, cachedBrokerInfo, inSyncReplicas).map(_.getNode(protocol))
          } catch {
            case e: Throwable => throw new ReplicaNotAvailableException(e)
          }
          if(replicaInfo.size < replicas.size)
            throw new ReplicaNotAvailableException("Replica information not available for following brokers: " +
              replicas.filterNot(replicaInfo.map(_.id).contains(_)).mkString(","))
          if(isrInfo.size < inSyncReplicas.size)
            throw new ReplicaNotAvailableException("In Sync Replica information not available for following brokers: " +
              inSyncReplicas.filterNot(isrInfo.map(_.id).contains(_)).mkString(","))
          new MetadataResponse.PartitionMetadata(Errors.NONE, partition, leaderInfo, replicaInfo.asJava, isrInfo.asJava)
        } catch {
          case e: Throwable =>
            debug("Error while fetching metadata for partition [%s,%d]".format(topic, partition), e)
            new MetadataResponse.PartitionMetadata(Errors.forException(e), partition, leaderInfo, replicaInfo.asJava, isrInfo.asJava)
        }
      }
      new MetadataResponse.TopicMetadata(Errors.NONE, topic, Topic.isInternal(topic), partitionMetadata.asJava)
    } else {
      // topic doesn't exist, send appropriate error code
      new MetadataResponse.TopicMetadata(Errors.UNKNOWN_TOPIC_OR_PARTITION, topic, Topic.isInternal(topic), java.util.Collections.emptyList())
    }
  }

  private def getBrokerInfoFromCache(zkUtils: ZkUtils,
                                     cachedBrokerInfo: scala.collection.mutable.Map[Int, Broker],
                                     brokerIds: Seq[Int]): Seq[Broker] = {
    var failedBrokerIds: ListBuffer[Int] = new ListBuffer()
    val brokerMetadata = brokerIds.map { id =>
      val optionalBrokerInfo = cachedBrokerInfo.get(id)
      optionalBrokerInfo match {
        case Some(brokerInfo) => Some(brokerInfo) // return broker info from the cache
        case None => // fetch it from zookeeper
          zkUtils.getBrokerInfo(id) match {
            case Some(brokerInfo) =>
              cachedBrokerInfo += (id -> brokerInfo)
              Some(brokerInfo)
            case None =>
              failedBrokerIds += id
              None
          }
      }
    }
    brokerMetadata.filter(_.isDefined).map(_.get)
  }

  private def replicaIndex(firstReplicaIndex: Int, secondReplicaShift: Int, replicaIndex: Int, nBrokers: Int): Int = {
    val shift = 1 + (secondReplicaShift + replicaIndex) % (nBrokers - 1)
    (firstReplicaIndex + shift) % nBrokers
  }
}
