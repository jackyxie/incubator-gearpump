/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gearpump.experiments.storm.util

import java.util.{List => JList}

import backtype.storm.generated.GlobalStreamId
import backtype.storm.grouping.CustomStreamGrouping
import backtype.storm.task.TopologyContext
import backtype.storm.tuple.Fields

import scala.collection.JavaConversions._
import scala.util.Random

/**
 * Grouper is identical to that in storm but return gearpump
 * partitions for storm tuple values
 */
sealed trait Grouper {
  /**
   * @param taskId storm task id of source task
   * @param values storm tuple values
   * @return a list of gearpump partitions
   */
  def getPartitions(taskId: Int, values: JList[AnyRef]): List[Int]
}

class GlobalGrouper extends Grouper {
  override def getPartitions(taskId: Int, values: JList[AnyRef]): List[Int] = List(0)
}

/**
 * @param numTasks number of target tasks
 */
class NoneGrouper(numTasks: Int) extends Grouper {
  private val random = new Random

  override def getPartitions(taskId: Int, values: JList[AnyRef]): List[Int] = {
    val partition = (random.nextInt % numTasks + numTasks) % numTasks
    List(partition)
  }
}

/**
 * @param numTasks number of target tasks
 */
class ShuffleGrouper(numTasks: Int) extends Grouper {
  private val random = new Random
  private var index = -1
  private var partitions = List.empty[Int]

  override def getPartitions(taskId: Int, values: JList[AnyRef]): List[Int] = {
    index += 1
    if (partitions.isEmpty) {
      partitions = 0.until(numTasks).toList
      partitions = random.shuffle(partitions)
    } else if (index >= numTasks) {
      index = 0
      partitions = random.shuffle(partitions)
    }
    List(partitions(index))
  }
}

/**
 * @param outFields declared output fields of source task
 * @param groupFields grouping fields of target tasks
 * @param numTasks number of target tasks
 */
class FieldsGrouper(outFields: Fields, groupFields: Fields, numTasks: Int) extends Grouper {

  override def getPartitions(taskId: Int, values: JList[AnyRef]): List[Int] = {
    val hash = outFields.select(groupFields, values).hashCode()
    List((hash & Integer.MAX_VALUE) % numTasks)
  }
}

/**
 * @param numTasks number of target tasks
 */
class AllGrouper(numTasks: Int) extends Grouper {

  override def getPartitions(taskId: Int, values: JList[AnyRef]): List[Int] = {
    (0 until numTasks).toList
  }
}

/**
 * @param grouping see [[CustomStreamGrouping]]
 */
class CustomGrouper(grouping: CustomStreamGrouping) extends Grouper {

  def prepare(topologyContext: TopologyContext, globalStreamId: GlobalStreamId, sourceTasks: JList[Integer]): Unit = {
    grouping.prepare(topologyContext, globalStreamId, sourceTasks)
  }

  override def getPartitions(taskId: Int, values: JList[AnyRef]): List[Int] = {
    grouping.chooseTasks(taskId, values).map(StormUtil.stormTaskIdToGearpump(_).index).toList
  }
}
