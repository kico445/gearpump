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

import java.util
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, Cancellable, Stash}
import org.apache.gearpump.TimeStamp
import org.apache.gearpump.streaming.AppMasterToExecutor.StartClock
import org.apache.gearpump.streaming.appmaster.ClockService._
import org.apache.gearpump.streaming.storage.AppDataStore
import org.apache.gearpump.streaming.task._
import org.apache.gearpump.streaming.{DAG, TaskGroup}
import org.apache.gearpump.util.LogUtil
import org.slf4j.Logger

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
 * The clockService will maintain a global view of message timestamp in the application
 */
class ClockService(dag : DAG, store: AppDataStore) extends Actor with Stash {
  private val LOG: Logger = LogUtil.getLogger(getClass)

  import context.dispatcher

  private var startClock: Long = 0
  private val taskGroupClocks = new util.TreeSet[TaskGroupClock]()
  private val taskGroupLookup = new util.HashMap[TaskGroup, TaskGroupClock]()

  private var reportScheduler : Cancellable = null
  private var snapshotScheduler : Cancellable = null

  override def receive = null

  override def preStart() : Unit = {
    LOG.info("Initializing Clock service, get snapshotted StartClock ....")

    store.get(START_CLOCK).asInstanceOf[Future[TimeStamp]].map { clock =>
      val startClock = Option(clock).getOrElse(0L)
      self ! StartClock(startClock)
      LOG.info(s"Start Clock Retrived, starting ClockService, startClock: $startClock")
    }

    context.become(waitForStartClock)
  }

  override def postStop() : Unit = {
    Option(reportScheduler).map(_.cancel)
    Option(snapshotScheduler).map(_.cancel)
  }

  private def initializeDagWithStartClock(startClock: TimeStamp) = {
    this.startClock = startClock
    dag.tasks.foreach {
      taskIdWithDescription =>
        val (taskGroupId, description) = taskIdWithDescription
        val taskClocks = new Array[TimeStamp](description.parallelism).map(_ => startClock)
        val taskGroupClock = new TaskGroupClock(taskGroupId, startClock, taskClocks)
        taskGroupClocks.add(taskGroupClock)
        taskGroupLookup.put(taskGroupId, taskGroupClock)
    }
  }

  def waitForStartClock: Receive = {
    case StartClock(startClock) =>

      initializeDagWithStartClock(startClock)

      import context.dispatcher

      //period report current clock
      reportScheduler = context.system.scheduler.schedule(new FiniteDuration(5, TimeUnit.SECONDS),
        new FiniteDuration(5, TimeUnit.SECONDS))(reportGlobalMinClock())

      //period snpashot latest min startclock to external storage
      snapshotScheduler = context.system.scheduler.schedule(new FiniteDuration(5, TimeUnit.SECONDS),
        new FiniteDuration(5, TimeUnit.SECONDS))(snapshotStartClock())

      unstashAll()
      context.become(clockService)
    case _ =>
      stash()
  }

  def clockService: Receive = {
    case UpdateClock(task, clock) =>
      val TaskId(taskGroupId, taskIndex) = task

      val taskGroup = taskGroupLookup.get(taskGroupId)
      taskGroupClocks.remove(taskGroup)
      taskGroup.taskClocks(taskIndex) = clock
      taskGroup.minClock = taskGroup.taskClocks.min
      taskGroupClocks.add(taskGroup)
      sender ! ClockUpdated(minClock)
    case GetLatestMinClock =>
      sender ! LatestMinClock(minClock)
  }

  private def minClock: TimeStamp = {
    if (taskGroupClocks.isEmpty) {
      LOG.warn("Try to get MinClock for a empty DAG")
      startClock
    } else {
      val taskGroup = taskGroupClocks.first()
      taskGroup.minClock
    }
  }

  def reportGlobalMinClock() : Unit = {
    val minTimeStamp = new Date(minClock)
    LOG.info(s"Application minClock tracking: $minTimeStamp")
  }
  private def snapshotStartClock() : Unit = {
    store.put(START_CLOCK, minClock)
  }
}

object ClockService {
  val START_CLOCK = "startClock"

  class TaskGroupClock(val taskGroup : TaskGroup, var minClock : TimeStamp = Long.MaxValue,
                       var taskClocks : Array[TimeStamp] = null) extends Comparable[TaskGroupClock] {
    override def equals(obj: Any): Boolean = {
      this.eq(obj.asInstanceOf[AnyRef])
    }

    override def compareTo(o: TaskGroupClock): Int = {
      val delta = minClock - o.minClock
      if (delta > 0) {
        1
      } else if (delta < 0) {
        -1
      } else {
        taskGroup - o.taskGroup
      }
    }
  }
}