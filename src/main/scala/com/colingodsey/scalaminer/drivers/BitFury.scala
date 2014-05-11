package com.colingodsey.scalaminer.drivers

import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer.{MinerIdentity, ScalaMiner}
import scala.concurrent.duration._
import com.colingodsey.io.usb.{BufferedReader, Usb}
import akka.actor.{Props, ActorRef}
import com.colingodsey.scalaminer.metrics.MetricsWorker
import com.colingodsey.scalaminer.ScalaMiner.HashType

class NanoFury(val deviceId: Usb.DeviceId,
		val workRefs: Map[ScalaMiner.HashType, ActorRef]) extends MCP2210Actor
			with BufferedReader with AbstractMiner with MetricsWorker {
	import MCP2210._

	def readDelay = 2.millis
	def readSize = 64
	def nonceTimeout = 10.seconds
	def hashType = ScalaMiner.SHA256
	def identity = BitFury.NFU

	implicit def ec = system.dispatcher

	def receive = mcpReceive orElse usbBufferReceive orElse metricsReceive orElse workReceive

	def init() {
		getPins()
	}

	override def preStart() {
		super.preStart()

		getDevice {
			context.system.scheduler.scheduleOnce(400.millis) {
				init()
			}
		}

		stratumSubscribe(stratumRef)
	}
}

case object BitFury extends USBDeviceDriver {
	sealed trait Command

	val defaultTimeout = 100.millis

	def hashType = ScalaMiner.SHA256

	lazy val identities: Set[USBIdentity] = Set(NFU)

	case object NFU extends USBIdentity {
		import UsbDeviceManager._

		def drv = BitFury
		def idVendor = 0x04d8
		def idProduct = 0x00de
		def iManufacturer = ""
		def iProduct = "NanoFury xxxx"
		def config = 1
		def timeout = defaultTimeout

		val interfaces = Set(
			Usb.Interface(0, Set(
				Usb.InputEndpoint(64, 1, 0),
				Usb.OutputEndpoint(64, 1, 0)
			))
		)

		override def usbDeviceActorProps(device: Usb.DeviceId,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			Props(classOf[NanoFury], device, workRefs)
	}
}
