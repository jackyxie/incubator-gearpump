/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gearpump.streaming.appmaster

import akka.actor._
import org.apache.gearpump._
import org.apache.gearpump.cluster.ClientToMaster.{GetLastFailure, GetStallingTasks, QueryHistoryMetrics, ShutdownApplication}
import org.apache.gearpump.cluster.MasterToAppMaster.{AppMasterDataDetailRequest, MessageLoss, ReplayFromTimestampWindowTrailingEdge}
import org.apache.gearpump.cluster.MasterToClient.LastFailure
import org.apache.gearpump.cluster._
import org.apache.gearpump.metrics.{Metrics, MetricsReporterService, JvmMetricsSet}
import org.apache.gearpump.metrics.Metrics.{ReportMetrics, MetricType}
import org.apache.gearpump.partitioner.PartitionerDescription
import org.apache.gearpump.streaming.ExecutorToAppMaster._
import org.apache.gearpump.streaming._
import org.apache.gearpump.streaming.appmaster.AppMaster.{ExecutorBrief, AllocateResourceTimeOut, LookupTaskActorRef, ServiceNotAvailableException}
import org.apache.gearpump.streaming.appmaster.DagManager.{GetLatestDAG, LatestDAG, ReplaceProcessor}
import org.apache.gearpump.streaming.appmaster.ExecutorManager.{ExecutorInfo, GetExecutorInfo}
import org.apache.gearpump.util._
import HistoryMetricsService.HistoryMetricsConfig
import org.apache.gearpump.streaming.appmaster.TaskManager.{GetTaskList, TaskList}
import org.apache.gearpump.streaming.executor.Executor.{QueryExecutorConfig, GetExecutorSummary}
import org.apache.gearpump.streaming.storage.InMemoryAppStoreOnMaster
import org.apache.gearpump.streaming.task._
import org.apache.gearpump.streaming.util.ActorPathUtil
import org.apache.gearpump.util.Constants._
import org.slf4j.Logger

import scala.concurrent.Future

class AppMaster(appContext : AppMasterContext, app : AppDescription)  extends ApplicationMaster {
  import app.userConfig
  import appContext.{appId, masterProxy, username}

  implicit val actorSystem = context.system
  implicit val timeOut = FUTURE_TIMEOUT
  import akka.pattern.ask
  implicit val dispatcher = context.dispatcher

  val startTime: TimeStamp = System.currentTimeMillis()

  private val LOG: Logger = LogUtil.getLogger(getClass, app = appId)
  LOG.info(s"AppMaster[$appId] is launched by $username, app: $app xxxxxxxxxxxxxxxxx")
  LOG.info(s"AppMaster actor path: ${ActorUtil.getFullPath(context.system, self.path)}")

  private val address = ActorUtil.getFullPath(context.system, self.path)

  private val dagManager = context.actorOf(Props(new DagManager(appContext.appId, userConfig,
    Some(getUpdatedDAG()))))

  private var taskManager: Option[ActorRef] = None
  private var clockService: Option[ActorRef] = None
  private val systemConfig = context.system.settings.config
  private var lastFailure = LastFailure(0L, null)

  private val getHistoryMetricsConfig = HistoryMetricsConfig(systemConfig)

  val metricsEnabled = systemConfig.getBoolean(GEARPUMP_METRIC_ENABLED)

  private val historyMetricsService = if (metricsEnabled) {
    // register jvm metrics
    Metrics(context.system).register(new JvmMetricsSet(s"app${appId}.appmaster"))

    val historyMetricsService = context.actorOf(Props(new HistoryMetricsService(s"app$appId", getHistoryMetricsConfig)))

    val metricsReportService = context.actorOf(Props(new MetricsReporterService(Metrics(context.system))))
    historyMetricsService.tell(ReportMetrics, metricsReportService)

    Some(historyMetricsService)
  } else {
    None
  }

  private val executorManager: ActorRef =
    context.actorOf(ExecutorManager.props(userConfig, appContext, app.clusterConfig, app.name),
    ActorPathUtil.executorManagerActorName)
  for (dag <- getDAG) {
    val store = new InMemoryAppStoreOnMaster(appId, appContext.masterProxy)
    clockService = Some(context.actorOf(Props(new ClockService(dag, store))))
    val subDAGManager = new SubDAGManager(appId, app.name, systemConfig)

    taskManager = Some(context.actorOf(Props(new TaskManager(appContext.appId, dagManager,
      subDAGManager, executorManager, clockService.get, self, app.name))))
  }

  override def receive : Receive =
      taskMessageHandler orElse
      executorMessageHandler orElse
      recover orElse
      appMasterService orElse
      ActorUtil.defaultMsgHandler(self)

  def taskMessageHandler: Receive = {
    case clock: ClockEvent =>
      taskManager.foreach(_ forward clock)
    case register: RegisterTask =>
      taskManager.foreach(_ forward register)
    case replay: ReplayFromTimestampWindowTrailingEdge =>
      taskManager.foreach(_ forward replay)
    case messageLoss: MessageLoss =>
      lastFailure = LastFailure(System.currentTimeMillis(), messageLoss.cause)
      taskManager.foreach(_ forward messageLoss)
    case lookupTask: LookupTaskActorRef =>
      taskManager.foreach(_ forward lookupTask)
    case checkpoint: ReportCheckpointClock =>
      clockService.foreach(_ forward checkpoint)
  }

  def executorMessageHandler: Receive = {
    case register: RegisterExecutor =>
      executorManager forward register
    case ReportMetrics =>
      historyMetricsService.foreach(_ forward ReportMetrics)
  }

  def appMasterService: Receive = {
    case appMasterDataDetailRequest: AppMasterDataDetailRequest =>
      LOG.debug(s"AppMaster got AppMasterDataDetailRequest for $appId ")

      val executorsFuture = executorBrief
      val clockFuture = getMinClock
      val taskFuture = getTaskList
      val dagFuture = getDAG

      val appMasterDataDetail = for {executors <- executorsFuture
        clock <- clockFuture
        tasks <- taskFuture
        dag <- dagFuture
      } yield {
        val graph = dag.graph

        val userDir = System.getProperty("user.dir")
        val logFile = LogUtil.applicationLogDir(actorSystem.settings.config)

        val executorToTasks = tasks.tasks.groupBy(_._2).mapValues {_.keys.toList}

        val processors = dag.processors.map { kv =>
          val processor = kv._2
          import processor._
          val tasks = executorToTasks.map { kv =>
            (kv._1, TaskCount(kv._2.count(_.processorId == id)))
          }.filter(_._2.count != 0)
          (id,
          ProcessorSummary(id, taskClass, parallelism, description, taskConf, life, tasks.keys.toList, tasks))
        }

        StreamAppMasterSummary(
          appId,
          app.name,
          processors,
          graph.vertexHierarchyLevelMap(),
          graph.mapEdge {(node1, edge, node2) =>
            edge.partitionerFactory.name
          },
          address,
          clock,
          executors,
          status = MasterToAppMaster.AppMasterActive,
          startTime = startTime,
          user = username,
          homeDirectory = userDir,
          logFile = logFile.getAbsolutePath
        )
      }

      val client = sender()

      appMasterDataDetail.map{appData =>
        client ! appData
      }
// TODO: WebSocket is buggy and disabled.
//    case appMasterMetricsRequest: AppMasterMetricsRequest =>
//      val client = sender()
//      actorSystem.eventStream.subscribe(client, classOf[MetricType])
    case query: QueryHistoryMetrics =>
      historyMetricsService.foreach(_ forward query)
    case getStalling: GetStallingTasks =>
      clockService.foreach(_ forward getStalling)
    case replaceDAG: ReplaceProcessor =>
      dagManager forward replaceDAG
    case GetLastFailure(_) =>
      sender ! lastFailure
    case  get@ GetExecutorSummary(executorId) =>
      val client = sender
      ActorUtil.askActor[Map[ExecutorId, ExecutorInfo]](executorManager, GetExecutorInfo).map { map =>
        map.get(executorId).foreach{ executor =>
          executor.executor.tell(get, client)
        }
      }
    case query@ QueryExecutorConfig(executorId) =>
      val client = sender
      ActorUtil.askActor[Map[ExecutorId, ExecutorInfo]](executorManager, GetExecutorInfo).map { map =>
        map.get(executorId).foreach{ executor =>
          executor.executor.tell(query, client)
        }
      }
   }

  def recover: Receive = {
    case AllocateResourceTimeOut =>
      LOG.error(s"Failed to allocate resource in time, shutdown application $appId")
      masterProxy ! ShutdownApplication(appId)
      context.stop(self)
  }

  private def getMinClock: Future[TimeStamp] = {
    clockService match {
      case Some(clockService) =>
        (clockService ? GetLatestMinClock).asInstanceOf[Future[LatestMinClock]].map(_.clock)
      case None =>
        Future.failed(new ServiceNotAvailableException("clock service not ready"))
    }
  }

  private def executorBrief: Future[List[ExecutorBrief]] = {
    ActorUtil.askActor[Map[ExecutorId, ExecutorInfo]](executorManager, GetExecutorInfo).map { infos =>
      infos.values.map { info =>
        ExecutorBrief(info.executorId,
          info.executor.path.toSerializationFormat,
          info.worker.workerId,
          "active")
      }.toList
    }
  }

  private def getTaskList: Future[TaskList] = {
    taskManager match {
      case Some(taskManager) =>
        (taskManager ? GetTaskList).asInstanceOf[Future[TaskList]]
      case None =>
        Future.failed(new ServiceNotAvailableException("task manager not ready"))
    }
  }

  private def getDAG: Future[DAG] = {
    (dagManager ? GetLatestDAG).asInstanceOf[Future[LatestDAG]].map(_.dag)
  }

  private def getUpdatedDAG(): DAG = {
    val dag = DAG(userConfig.getValue[Graph[ProcessorDescription, PartitionerDescription]](StreamApplication.DAG).get)
    val updated = dag.processors.map{ idAndProcessor =>
      val (id, processor) = idAndProcessor
      (id, processor.copy(jar = appContext.appJar.get))
    }
    DAG(dag.version, updated, dag.graph)
  }
}

object AppMaster {
  case object AllocateResourceTimeOut

  case class LookupTaskActorRef(taskId: TaskId)

  case class TaskActorRef(task: ActorRef)

  class ServiceNotAvailableException(reason: String) extends Exception(reason)

  case class ExecutorBrief(executorId: ExecutorId, executor: String, workerId: Int, status: String)
}