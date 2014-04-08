package com.colingodsey.scalaminer.drivers

import javax.usb.{UsbDevice, UsbConst}
import akka.actor._
import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer._
import scala.concurrent.duration._
import com.colingodsey.scalaminer.usb.USBManager.{OutputEndpoint, InputEndpoint, Interface}

case object GridSeed extends USBDeviceDriver {
	import USBUtils._

	def hashType: ScalaMiner.HashType = ScalaMiner.Scrypt

	val gsTimeout = 100.millis

	val MINER_THREADS = 1
	val LATENCY = 4

	val DEFAULT_BAUD = 115200
	val DEFAULT_FREQUENCY = 750
	val DEFAULT_CHIPS = 5
	val DEFAULT_USEFIFO = 0
	val DEFAULT_BTCORE = 16

	val COMMAND_DELAY = 20
	val READ_SIZE = 12
	val MCU_QUEUE_LEN = 0
	val SOFT_QUEUE_LEN = (MCU_QUEUE_LEN+2)
	val READBUF_SIZE = 8192
	val HASH_SPEED = 0.0851128926.millis  // in ms
	val F_IN = 25  // input frequency

	val PROXY_PORT = 3350

	val PERIPH_BASE = 0x40000000
	val APB2PERIPH_BASE = (PERIPH_BASE + 0x10000)
	val GPIOA_BASE = (APB2PERIPH_BASE + 0x0800)
	val CRL_OFFSET = 0x00
	val ODR_OFFSET = 0x0c

	val detectBytes = BigInt("55aac000909090900000000001000000",
		16).toByteArray.toIndexedSeq

	case object GSD extends USBIdentity {
		def drv = GridSeed
		def idVendor = 0x0483
		def idProduct = 0x5740
		def iManufacturer = "STMicroelectronics"
		def iProduct = "STM32 Virtual COM Port"
		def config = 1
		def timeout = gsTimeout

		val interfaces = Set(Interface(0, Set(
			//Endpoint(UsbConst.ENDPOINT_TYPE_INTERRUPT, 8, epi(2), 0, false),
			InputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 1, 0),
			OutputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 3, 0)
		)))

		override def usbDeviceActorProps(device: UsbDevice, workRef: ActorRef): Props = ???
	}

	case object GSD1 extends USBIdentity {
		def drv = GridSeed
		override def name = "GSD"
		def idVendor = 0x10c4
		def idProduct = 0xea60.toShort
		def iManufacturer = ""
		def iProduct = "CP2102 USB to UART Bridge Controller"
		def config = 1
		def timeout = gsTimeout

		val interfaces = Set(Interface(0, Set(
			InputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 1, 0),
			OutputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 1, 0)
		)))

		override def usbDeviceActorProps(device: UsbDevice, workRef: ActorRef): Props = ???
	}

	case object GSD2 extends USBIdentity {
		def drv = GridSeed
		override def name = "GSD"
		def idVendor = FTDI.vendor
		def idProduct = 0x6010
		def iManufacturer = ""
		def iProduct = "Dual RS232-HS"
		def config = 1
		def timeout = gsTimeout

		val interfaces = Set(Interface(0, Set(
			InputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 512, 1, 0),
			OutputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 512, 2, 0)
		)))

		override def usbDeviceActorProps(device: UsbDevice, workRef: ActorRef): Props = ???
	}

	case object GSD3 extends USBIdentity {
		def drv = GridSeed
		override def name = "GSD"
		def idVendor = 0x067b
		def idProduct = 0x2303
		def iManufacturer = ""
		def iProduct = "USB-Serial Controller"
		def config = 1
		def timeout = gsTimeout

		val interfaces = Set(Interface(0, Set(
			InputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 3, 0),
			OutputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 2, 0)
		)))

		def usbDeviceActorProps(device: UsbDevice, workRef: ActorRef): Props =
			Props(classOf[GSD3Device], device)
	}

	val identities: Set[USBIdentity] = Set(GSD, GSD1, GSD2, GSD3)

}

class GSD3Device(val device: UsbDevice) extends PL2303Device {
	def receive = ??? //initReceive
	def normal = ???
	def identity = GridSeed.GSD3

	val defaultReadSize: Int = 2000

	override def preStart = {
		super.preStart()

		initUART()
	}


}

trait PL2303Device extends USBDeviceActor {
	import PL2303._

	def identity: USBIdentity
	def normal: Actor.Receive

	lazy val interfaceDef = identity.interfaces.toSeq.sortBy(_.interface).head

	//set data control
	val ctrlIrp = device.createUsbControlIrp(
		CTRL_OUT,
		REQUEST_CTRL,
		VALUE_CTRL,
		interfaceDef.interface
	)
	ctrlIrp.setData(Array.empty)

	val lineCtrlIrp = device.createUsbControlIrp(
		CTRL_OUT,
		REQUEST_LINE,
		VALUE_LINE,
		interfaceDef.interface
	)
	lineCtrlIrp.setData(byteArrayFrom { x =>
		x.writeInt(VALUE_LINE0)
		x.writeInt(VALUE_LINE1)
	})

	val vendorIrp = device.createUsbControlIrp(
		VENDOR_OUT,
		REQUEST_VENDOR,
		VALUE_VENDOR,
		interfaceDef.interface
	)
	vendorIrp.setData(Array.empty)

	val initIrps = List(ctrlIrp, lineCtrlIrp, vendorIrp)

	lazy val interface = {
		val configuration = device.getActiveUsbConfiguration
		configuration.getUsbInterface(interfaceDef.interface.toByte)
	}

	lazy val outputEndpoint = {
		val op = interfaceDef.endpoints.filter(_.isOutput).head
	}

	def initUART() = runIrps(initIrps)(_ => context become normal)

}
