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
	implicit val system = ActorSystem("scalaminer")

	val usbDrivers: Set[USBDeviceDriver] = Set(DualMiner, BFLSC, GridSeed)

	val tcpManager = IO(Tcp)

	val btcConn = StratumConnection("nl1.ghash.io", 3333, "colinrgodsey.testtt2d", "x")
	val scryptConn = StratumConnection("doge.ghash.io", 3333, "colinrgodsey.testtt2d", "x")

	val btcConnRef = system.actorOf(Props(classOf[StratumActor],
		tcpManager, btcConn, ScalaMiner.SHA256), name = "btc-conn")

	val scryptConnRef = system.actorOf(Props(classOf[StratumActor],
		tcpManager, scryptConn, ScalaMiner.Scrypt), name = "scrypt-conn")

	val stratumProxy = system.actorOf(Props(classOf[StratumProxy], btcConnRef), name = "stratumProxy")

	val usbManager = system.actorOf(Props[USBManager], name = "usb-manager")

	usbManager ! USBManager.AddStratumRef(ScalaMiner.Scrypt, scryptConnRef)
	usbManager ! USBManager.AddStratumRef(ScalaMiner.SHA256, btcConnRef)
	usbDrivers.foreach(x => usbManager ! USBManager.AddDriver(x))

	sys addShutdownHook {
		println("Shuttdown down...")
		system.shutdown()
		Thread.sleep(1500)
		println("Shut down")
	}
}

object MinerDriver {

}

trait MinerDriver {

}



case class Work(hashType: ScalaMiner.HashType, data: Seq[Byte],
				midstate: Seq[Byte], target: Seq[Byte])

case class Nonce(work: Work, nonce: Seq[Byte], extraNonce: Seq[Byte])


case class MinerStats(started: Int = -1, short: Int = -1, submitted: Int = -1,
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
}



