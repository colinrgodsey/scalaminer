package com.colingodsey.scalaminer.drivers

import javax.usb.{UsbDevice, UsbConst}
import akka.actor._
import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer._
import scala.concurrent.duration._
import com.colingodsey.scalaminer.utils._
import com.colingodsey.scalaminer.usb.USBManager.{OutputEndpoint, InputEndpoint, Interface}
import com.colingodsey.scalaminer.network.Stratum
import akka.util.ByteString
import javax.usb.event.UsbPipeDataEvent
import com.colingodsey.scalaminer.metrics.{MetricsWorker, MinerMetrics}

object GridSeedMiner {
	sealed trait Command

	case object Start extends Command
}

trait GridSeedMiner extends USBDeviceActor with AbstractMiner with MetricsWorker {
	import GridSeedMiner._
	import GridSeed.Constants._

	def doInit()

	def freq = DEFAULT_FREQUENCY
	def baud = DEFAULT_BAUD
	def nChips = DEFAULT_CHIPS

	lazy val selectedFreq = getFreqFor(freq)

	override def commandDelay = 2.millis
	override def defaultTimeout = 10000.millis

	def nonceTimeout = 10.seconds

	def jobTimeout = 5.minutes
	def altVoltage = false //hacked miners only

	val defaultReadSize: Int = 0x2000 // ?

	val pollDelay = 10.millis
	val maxWorkQueue = 15

	lazy val intf = identity.interfaces.head

	var fwVersion = -1
	var readStarted = false
	var hasRead = false
	var currentJob: Option[Stratum.Job] = None

	def detect() {
		usbCommandInfo(intf, "detect") {
			sendDataCommand(intf, detectBytes)()
			readDataUntilLength(intf, READ_SIZE) { dat =>
				if(dat.take(READ_SIZE - 4) != detectRespBytes) {
					log.warning("Failed detect!")
					failDetect()
				} else {
					val bVersion = dat.drop(8).reverse
					fwVersion = BigInt(bVersion.toArray).toInt

					log.info("Grid seed detected! Version " + bVersion.toHex)

					sendDataCommand(intf, chipResetBytes)()
					sleepInf(intf, 200.millis)
					//flushRead(intf)
					sendDataCommands(intf, initBytes)()
					sendDataCommands(intf, ltcResetBytes)()
					sendDataCommand(intf, frequencyCommands(selectedFreq))()

					if(altVoltage && fwVersion == 0x01140113) {
						// Put GPIOA pin 5 into general function, 50 MHz output.
						readRegister(GPIOA_BASE + CRL_OFFSET) { dat =>
							val i = getInts(dat)(0)

							val value = (i & 0xff0fffff) | 0x00300000

							writeRegister(GPIOA_BASE + CRL_OFFSET, value)()

							// Set GPIOA pin 5 high.
							readRegister(GPIOA_BASE + ODR_OFFSET) { dat2 =>
								val i2 = getInts(dat2)(0)
								val value2 = i2 | 0x00000020
								writeRegister(GPIOA_BASE + ODR_OFFSET, value2)(detected())
							}
						}
					} else if(altVoltage) {
						log.error("Cannot set alt voltage for fw version " + fwVersion)
						failDetect()
					} else detected()
				}
			}
		}
	}

	override def onReadTimeout() {
		log.error("Read data timeout!")
		if(!hasRead) failDetect()
		else context stop self
	}

	def detected() {
		context become normal
		unstashAll()
	}

	def readRegister(addr: Int)(recv: Seq[Byte] => Unit) {
		require(fwVersion == 0x01140113, "Incompatible firmware " + fwVersion)

		val cmd = "55aac001".fromHex ++ intToBytes(addr) ++
				intToBytes(regSize) ++ intToBytes(regSize)

		require(cmd.length == 16)

		readDataUntilLength(intf, regSize)(recv)
	}

	def writeRegister(addr: Int, value: Int)(recv: => Unit) {
		require(fwVersion == 0x01140113, "Incompatible firmware " + fwVersion)

		val cmd = "55aac002".fromHex ++ intToBytes(addr) ++
				intToBytes(value) ++ intToBytes(regSize)

		require(cmd.length == 16)

		readDataUntilLength(intf, regSize)(x => recv)
	}

	def sendWork() {
		log.debug("Sending work...")

		val workOpt = getWork(true)

		if(workOpt.isDefined) {
			val job = workOpt.get
			val work @ Work(ht, data, midstate, target) = job.work
			val eninfo = extraNonceInfo.get

			currentJob = Some(job)

			val dat = ScalaMiner.BufferType.empty ++
					"55aa1f00".fromHex ++
					target ++ midstate ++ data.take(80) ++
					Seq[Byte](0xFF.toByte, 0xFF.toByte, 0xFF.toByte, 0xFF.toByte) ++
					"12345678".fromHex

			self ! MinerMetrics.WorkStarted

			usbCommandInfo(intf, "sendWork") {
				//flushRead(intf)

				sendDataCommands(intf, ltcResetBytes)()
				sendDataCommand(intf, dat)()
			}

			startWorkRead()
		}
	}

	def startWorkRead(): Unit = if(!readStarted) usbCommandInfo(intf, "startWorkRead") {
		readStarted = true

		object TimedOut

		val eps = endpointsForIface(intf)

		val (ep, pipe) = eps.filter(_._1.isInput).head

		lazy val timeoutTime = context.system.scheduler.scheduleOnce(
			nonceTimeout, self, TimedOut)

		var buffer = ByteString.empty

		addUsbCommandToQueue(intf, ({ () =>
			timeoutTime.isCancelled
			pipe.asyncSubmit(defaultReadBuffer)
		}, {
			case AbstractMiner.CancelWork =>
				timeoutTime.cancel()
				readStarted = false
				sendWork()
				true
			case TimedOut =>
				//onReadTimeout()
				self ! MinerMetrics.WorkTimeout
				readStarted = false
				sendWork()
				true
			case x: UsbPipeDataEvent if x.getUsbPipe == pipe =>
				val dat = x.getData

				buffer ++= dat

				if(buffer.length >= READ_SIZE && (buffer(0) == 0x55.toByte ||
						buffer(1) == 0x20.toByte)) {
					val nonce = buffer.slice(4, 8)//.reverse
					val iNonce = BigInt((0.toByte +: nonce).toArray)
					val chip = (iNonce / BigInt(0xffffffffL) * nChips).toInt

					if(currentJob.isDefined) {
						val job = currentJob.get

						self ! Nonce(job.work, nonce, job.extranonce2)
					}

					hasRead = true

					readStarted = false
					sendWork()
					true
				} else {
					readStarted = false
					false
				}
		}))
	}

	def normal: Receive = metricsReceive orElse usbBaseReceive orElse workReceive orElse {
		case AbstractMiner.CancelWork => sendWork()
	}

	def receive: Receive = usbBaseReceive orElse {
		case Start => doInit()
		case _ => stash()
	}

	abstract override def preStart() {
		super.preStart()

		self ! Start

		stratumSubscribe(stratumRef)

	}
}

class GridSeedFTDIMiner(val device: UsbDevice,
		val workRefs: Map[ScalaMiner.HashType, ActorRef]) extends GridSeedMiner {
	import FTDI._
	import GridSeed._
	import Constants._

	def identity: USBIdentity = GSD2
	override def isFTDI = true

	def controlIndex = 0.toShort

	val initIrps = List(
		device.createUsbControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_RESET, controlIndex),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_LATENCY, LATENCY, controlIndex),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_DATA, VALUE_DATA_AVA, controlIndex),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_BAUD, VALUE_BAUD_AVA,
			((INDEX_BAUD_AVA & 0xff00) | controlIndex).toShort),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_MODEM, VALUE_MODEM, controlIndex),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_FLOW, VALUE_FLOW, controlIndex),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_MODEM, VALUE_MODEM, controlIndex),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_FLOW, VALUE_FLOW, controlIndex)
	)

	def doInit() {
		runIrps(initIrps) { _ =>
			detect()
		}
	}
}

class GridSeedSGSMiner(val device: UsbDevice,
		val workRefs: Map[ScalaMiner.HashType, ActorRef]) extends GridSeedMiner {
	import FTDI._
	import GridSeed._
	import Constants._

	def identity: USBIdentity = GSD
	override def isFTDI = false

	def doInit() {
		detect()
	}
}

class GridSeedPL2303Miner(val device: UsbDevice) extends PL2303Device {
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

case object GridSeed extends USBDeviceDriver {
	sealed trait Command

	case object CalcStats extends Command

	def hashType: ScalaMiner.HashType = ScalaMiner.Scrypt

	val gsTimeout = 100.millis

	case object GSD extends USBIdentity {
		def drv = GridSeed
		def idVendor = 0x0483
		def idProduct = 0x5740
		def iManufacturer = "STMicroelectronics"
		def iProduct = "STM32 Virtual COM Port"
		def config = 1
		def timeout = gsTimeout

		def isMultiCoin = true

		val interfaces = Set(Interface(1, Set(
			//Endpoint(UsbConst.ENDPOINT_TYPE_INTERRUPT, 8, epi(2), 0, false),
			InputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 1, 0),
			OutputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 3, 0)
		)))

		override def usbDeviceActorProps(device: UsbDevice,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			Props(classOf[GridSeedSGSMiner], device, workRefs)
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

		override def usbDeviceActorProps(device: UsbDevice,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props = ???
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

		override def usbDeviceActorProps(device: UsbDevice,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			Props(classOf[GridSeedFTDIMiner], device, workRefs)
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

		def usbDeviceActorProps(device: UsbDevice,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			??? //Props(classOf[GSD3Device], device)
	}

	val identities: Set[USBIdentity] = Set(GSD, GSD2) //Set(GSD, GSD1, GSD2, GSD3)

	lazy val Constants = GSConstants
}

object GSConstants {
	val MINER_THREADS = 1
	val LATENCY = 4.toShort

	val DEFAULT_BAUD = 115200
	val DEFAULT_FREQUENCY = 750
	val DEFAULT_CHIPS = 5
	val DEFAULT_USEFIFO = 0
	val DEFAULT_BTCORE = 16

	val COMMAND_DELAY = 20
	val READ_SIZE = 12
	val MCU_QUEUE_LEN = 0
	val SOFT_QUEUE_LEN = (MCU_QUEUE_LEN + 2)
	val READBUF_SIZE = 8192
	val HASH_SPEED = 0.0851128926.millis
	// in ms
	val F_IN = 25 // input frequency

	val PROXY_PORT = 3350

	val PERIPH_BASE = 0x40000000
	val APB2PERIPH_BASE = (PERIPH_BASE + 0x10000)
	val GPIOA_BASE = (APB2PERIPH_BASE + 0x0800)
	val CRL_OFFSET = 0x00
	val ODR_OFFSET = 0x0c

	val regSize = 4

	val detectBytes = "55aac000909090900000000001000000".fromHex
	val detectRespBytes = "55aac00090909090".fromHex
	val chipResetBytes = "55AAC000808080800000000001000000".fromHex
	val initBytes = Seq("55AAC000C0C0C0C00500000001000000",
		"55AAEF020000000000000000000000000000000000000000",
		"55AAEF3020000000").map(_.fromHex)
	val ltcResetBytes = Seq("55AA1F2816000000",
		"55AA1F2817000000").map(_.fromHex)

	val freqNumbers = Seq(
		700,  706,  713,  719,  725,  731,  738,  744,
		750,  756,  763,  769,  775,  781,  788,  794,
		800,  813,  825,  838,  850,  863,  875,  888,
		900,  913,  925,  938,  950,  963,  975,  988,
		1000, 1013, 1025, 1038, 1050, 1063, 1075, 1088,
		1100, 1113, 1125, 1138, 1150, 1163, 1175, 1188,
		1200, 1213, 1225, 1238, 1250, 1263, 1275, 1288,
		1300, 1313, 1325, 1338, 1350, 1363, 1375, 1388,
		1400)

	lazy val frequencyCommands = (freqNumbers zip binFrequency).toMap

	def getFreqFor(freq: Int) = {
		val closest = freqNumbers.map(x =>
			x -> math.abs(x - freq)).sortBy(_._2).head._1

		closest
	}

	val binFrequency = Seq(
		"55aaef0005006083",
		"55aaef000500038e",
		"55aaef0005000187",
		"55aaef000500438e",
		"55aaef0005008083",
		"55aaef000500838e",
		"55aaef0005004187",
		"55aaef000500c38e",

		"55aaef000500a083",
		"55aaef000500038f",
		"55aaef0005008187",
		"55aaef000500438f",
		"55aaef000500c083",
		"55aaef000500838f",
		"55aaef000500c187",
		"55aaef000500c38f",

		"55aaef000500e083",
		"55aaef0005000188",
		"55aaef0005000084",
		"55aaef0005004188",
		"55aaef0005002084",
		"55aaef0005008188",
		"55aaef0005004084",
		"55aaef000500c188",

		"55aaef0005006084",
		"55aaef0005000189",
		"55aaef0005008084",
		"55aaef0005004189",
		"55aaef000500a084",
		"55aaef0005008189",
		"55aaef000500c084",
		"55aaef000500c189",

		"55aaef000500e084",
		"55aaef000500018a",
		"55aaef0005000085",
		"55aaef000500418a",
		"55aaef0005002085",
		"55aaef000500818a",
		"55aaef0005004085",
		"55aaef000500c18a",

		"55aaef0005006085",
		"55aaef000500018b",
		"55aaef0005008085",
		"55aaef000500418b",
		"55aaef000500a085",
		"55aaef000500818b",
		"55aaef000500c085",
		"55aaef000500c18b",

		"55aaef000500e085",
		"55aaef000500018c",
		"55aaef0005000086",
		"55aaef000500418c",
		"55aaef0005002086",
		"55aaef000500818c",
		"55aaef0005004086",
		"55aaef000500c18c",

		"55aaef0005006086",
		"55aaef000500018d",
		"55aaef0005008086",
		"55aaef000500418d",
		"55aaef000500a086",
		"55aaef000500818d",
		"55aaef000500c086",
		"55aaef000500c18d",

		"55aaef000500e086"
	).map(_.fromHex)
}