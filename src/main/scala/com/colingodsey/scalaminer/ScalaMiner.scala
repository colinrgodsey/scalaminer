package com.colingodsey.scalaminer

import javax.usb._
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import javax.usb.event.{UsbDeviceDataEvent, UsbDeviceErrorEvent, UsbDeviceEvent, UsbDeviceListener}
import java.io.{ByteArrayOutputStream, DataOutputStream}
import akka.actor._
import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer.drivers.{GridSeed, BFLSC, DualMiner}
import akka.io.{ IO, Tcp }
import com.colingodsey.scalaminer.network.Stratum.StratumConnection
import com.colingodsey.scalaminer.network.{Stratum, StratumProxy, StratumActor}
import spray.can.Http
import akka.util.ByteString
import com.colingodsey.scalaminer.metrics.{MinerMetrics, MetricsActor}
import com.typesafe.config.{Config, ConfigFactory}

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
	val smConfig = config.getConfig("com.mediamath.scalaminer")
	implicit val system = ActorSystem("scalaminer", config, classLoader)

	val usbDrivers: Set[USBDeviceDriver] = Set(DualMiner, BFLSC, GridSeed)

	val tcpManager = IO(Tcp)

	def readStConn(cfg: Config) = {
		if(cfg.hasPath("host")) {
			StratumConnection(cfg getString "host", cfg getInt "port",
				cfg getString "user", cfg getString "pass")
		} else ??? //TODO: implement parsing for multiple stratums
	}

	val metricsRef = system.actorOf(Props[MetricsActor], name = "metrics")

	val usbManager = if(smConfig.hasPath("devices.usb.enabled") &&
			smConfig.getBoolean("devices.usb.enabled")) {
		val ref = system.actorOf(Props[USBManager], name = "usb-manager")
		ref.tell(MinerMetrics.Subscribe, metricsRef)
		usbDrivers.foreach(x => ref ! USBManager.AddDriver(x))
		Some(ref)
	} else None

	if(smConfig.hasPath("stratum.scrypt")) {
		val conn = readStConn(smConfig getConfig "stratum.scrypt")
		val connRef = system.actorOf(Props(classOf[StratumActor],
			tcpManager, conn, ScalaMiner.Scrypt), name = "stratum-scrypt")

		if(usbManager.isDefined)
			usbManager.get ! USBManager.AddStratumRef(ScalaMiner.Scrypt, connRef)
	}

	if(smConfig.hasPath("stratum.sha256")) {
		val conn = readStConn(smConfig getConfig "stratum.sha256")
		val connRef = system.actorOf(Props(classOf[StratumActor],
			tcpManager, conn, ScalaMiner.SHA256), name = "stratum-sha256")

		if(usbManager.isDefined)
			usbManager.get ! USBManager.AddStratumRef(ScalaMiner.SHA256, connRef)

		if(smConfig.hasPath("stratum-proxy") && smConfig.getBoolean("stratum-proxy.enabled")) {
			val stratumProxy = system.actorOf(Props(classOf[StratumProxy], connRef,
				smConfig.getConfig("stratum-proxy")), name = "stratum-proxy")

			stratumProxy.tell(MinerMetrics.Subscribe, metricsRef)
		}
	}

	sys addShutdownHook {
		println("Shuttdown down...")
		system.shutdown()
		system.awaitTermination(15.seconds)
		println("Shut down")
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



