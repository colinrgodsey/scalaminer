/*
 * scalaminer
 * ----------
 * https://github.com/colinrgodsey/scalaminer
 *
 * Copyright (c) 2014 Colin R Godsey <colingodsey.com>
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.  See COPYING for more details.
 */

package com.colingodsey.scalaminer

import javax.usb._
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import javax.usb.event.{UsbDeviceDataEvent, UsbDeviceErrorEvent, UsbDeviceEvent, UsbDeviceListener}
import java.io.{ByteArrayOutputStream, DataOutputStream}
import akka.actor._
import akka.pattern._
import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer.drivers.{GridSeed, BFLSC, DualMiner}
import akka.io.{ IO, Tcp }
import com.colingodsey.scalaminer.network.Stratum.StratumConnection
import com.colingodsey.scalaminer.network.{Stratum, StratumProxy, StratumActor}
import spray.can.Http
import akka.util.{Timeout, ByteString}
import com.colingodsey.scalaminer.metrics.{MinerMetrics, MetricsActor}
import com.typesafe.config.{Config, ConfigFactory}
import java.util.concurrent._
import java.util
import com.colingodsey.scalaminer.network.Stratum.StratumConnection
import scala.Some
import com.colingodsey.scalaminer.Work
import scala.concurrent.duration.TimeUnit
import scala.util.{Failure, Success}
import scala.concurrent.Await
import com.colingodsey.io.usb.Usb

object ScalaMiner {
	type BufferType = ByteString
	val BufferType = ByteString

	//type BufferType = Stream[Byte]
	//val BufferType = Stream

  sealed trait HashType

  case object Scrypt extends HashType
  case object SHA256 extends HashType

  //val LTC = Scrypt
}

object ScalaMinerMain extends App {
	val classLoader = getClass.getClassLoader
	val config = ConfigFactory.load(classLoader)
	val smConfig = config.getConfig("com.colingodsey.scalaminer")
	implicit val system = ActorSystem("scalaminer", config, classLoader)

	val usbDrivers: Set[USBDeviceDriver] = Set(DualMiner, BFLSC, GridSeed)

	def readStConn(cfg: Config) = {
		if(cfg.hasPath("host")) {
			StratumConnection(cfg getString "host", cfg getInt "port",
				cfg getString "user", cfg getString "pass")
		} else ??? //TODO: implement parsing for multiple stratums
	}

	val metricsRef = system.actorOf(Props[MetricsActor], name = "metrics")

	val usbDeviceManager = if(smConfig.hasPath("devices.usb.enabled") &&
			smConfig.getBoolean("devices.usb.enabled")) {
		val ref = system.actorOf(Props(classOf[UsbDeviceManager],
			smConfig getConfig "devices.usb"), name = "usb-manager")
		ref.tell(MinerMetrics.Subscribe, metricsRef)
		usbDrivers.foreach(x => ref ! UsbDeviceManager.AddDriver(x))
		Some(ref)
	} else None

	if(smConfig.hasPath("stratum.scrypt")) {
		val conn = readStConn(smConfig getConfig "stratum.scrypt")
		val connRef = system.actorOf(Props(classOf[StratumActor],
			conn, ScalaMiner.Scrypt), name = "stratum-scrypt")

		if(usbDeviceManager.isDefined)
			usbDeviceManager.get ! UsbDeviceManager.AddStratumRef(ScalaMiner.Scrypt, connRef)
	}

	if(smConfig.hasPath("stratum.sha256")) {
		val conn = readStConn(smConfig getConfig "stratum.sha256")
		val connRef = system.actorOf(Props(classOf[StratumActor],
			conn, ScalaMiner.SHA256), name = "stratum-sha256")

		if(usbDeviceManager.isDefined)
			usbDeviceManager.get ! UsbDeviceManager.AddStratumRef(ScalaMiner.SHA256, connRef)

		if(smConfig.hasPath("stratum-proxy") && smConfig.getBoolean("stratum-proxy.enabled")) {
			val stratumProxy = system.actorOf(Props(classOf[StratumProxy], connRef,
				smConfig.getConfig("stratum-proxy")), name = "stratum-proxy")

			stratumProxy.tell(MinerMetrics.Subscribe, metricsRef)
		}
	}

	if(usbDeviceManager.isDefined) {
		//start after register stratum
		usbDeviceManager.get ! UsbDeviceManager.Start
	}

	sys addShutdownHook {
		println("Shuttdown down...")
		system.shutdown()
		system.awaitTermination(15.seconds)
		println("Shut down")
	}
}

class ExecutorActor extends Actor with ActorLogging {
	import scala.concurrent.blocking

	def receive: Receive = {
		case "alive" => sender ! true

		case x: Callable[_] => try blocking(x.call()) catch {
			case x: Throwable =>
				log.error(x, "ExecutorActor fail")
		}

		case x: Runnable => try blocking(x.run()) catch {
			case x: Throwable =>
				log.error(x, "ExecutorActor fail")
		}
	}
}

class ScalaMinerEC extends org.usb4java.javax.ExecutorServiceProvider {
	def system = ScalaMinerMain.system
	lazy val log = akka.event.Logging(system, this.getClass)

	def newExecutorService(): ExecutorService = new ExecutorService {
		val ref = ScalaMinerEC.this.synchronized(system.actorOf(Props[ExecutorActor].withDispatcher(
			"com.colingodsey.scalaminer.usb-blocking-dispatcher")))
		@volatile private var running = true
		@volatile private var terminated = false

		override def shutdown(): Unit = {
			system stop ref
			running = false
		}

		override def execute(command: Runnable): Unit = if(running) {
			ref ! command
		} else sys.error("Already shut down!")

		override def invokeAny[T](tasks: util.Collection[_ <: Callable[T]],
				timeout: Long, unit: TimeUnit): T = ???

		override def invokeAny[T](tasks: util.Collection[_ <: Callable[T]]): T = ???

		override def invokeAll[T](tasks: util.Collection[_ <: Callable[T]],
				timeout: Long, unit: TimeUnit): util.List[Future[T]] = ???

		override def invokeAll[T](tasks: util.Collection[_ <: Callable[T]]): util.List[Future[T]] = ???

		override def submit(task: Runnable): Future[_] = ???

		override def submit[T](task: Runnable, result: T): Future[T] = ???

		override def submit[T](task: Callable[T]): Future[T] = ???

		override def awaitTermination(timeout: Long, unit: TimeUnit): Boolean = {
			val dur = FiniteDuration(timeout, unit)
			implicit def to = Timeout(dur)
			implicit def ec = system.dispatcher

			try Await.result((ref ? "alive"), dur + 200.millis) catch {
				case x: Throwable =>
					//log.error(x, "Failied killing ec actor!")
			}

			terminated = true

			true
		}

		override def isTerminated: Boolean = terminated

		override def isShutdown: Boolean = !running

		override def shutdownNow(): util.List[Runnable] = {
			shutdown()
			Nil
		}
	}
}

object MinerDriver {

}

//should be a case object
trait MinerDriver {
	def identities: Set[_ <: MinerIdentity]
}

//should be a case object
trait MinerIdentity {
	def drv: MinerDriver
}


case class Work(hashType: ScalaMiner.HashType, data: Seq[Byte],
				midstate: Seq[Byte], target: Seq[Byte])

case class Nonce(work: Work, nonce: Seq[Byte], extraNonce: Seq[Byte])


/*case class MinerStats(started: Int = -1, short: Int = -1, submitted: Int = -1,
		failed: Int = -1, accepted: Int = -1, tooLow: Int = -1,
		stale: Int = -1, timeout: Int = -1) {
	val values = Map(
		'started -> started,
		'short -> short,
		'submitted -> submitted,
		'failed -> failed,
		'accepted -> accepted,
		'tooLow -> tooLow,
		'stale -> stale,
		'timeout -> timeout
	).map(x => x._1.toString.drop(1) -> x._2)

	override def toString = s"MinerStats(${values.mkString(", ")})"
}*/



