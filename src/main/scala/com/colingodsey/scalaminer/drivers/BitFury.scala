package com.colingodsey.scalaminer.drivers

import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer.utils._
import com.colingodsey.scalaminer.{MinerIdentity, ScalaMiner}
import scala.concurrent.duration._
import com.colingodsey.io.usb.{BufferedReader, Usb}
import akka.actor.{Props, ActorRef}
import com.colingodsey.scalaminer.metrics.MetricsWorker
import com.colingodsey.scalaminer.ScalaMiner.HashType
import com.colingodsey.scalaminer.usb.UsbDeviceActor.NonTerminated
import akka.util.ByteString

class SPIDataBuilder {
	private var buffer = ByteString.empty

	def results = buffer

	def addData(addr: Short, dat: Seq[Byte]) {

		val len = dat.length

		if (len < 4 || len > 128) sys.error("Bad SPI data length " + len)

		val otmp = Seq[Byte](
			((len / 4 - 1) | 0xE0).toByte,
			((addr >> 8) & 0xFF).toByte,
			(addr & 0xFF).toByte
		)

		buffer ++= otmp

		addReverse(dat)
	}

	def addBreak() = buffer :+= 0x4.toByte
	def addFASync(n: Int) = buffer ++= Seq.fill(n)(5.toByte)

	def sendConf() {
		val FIRST_BASE = 61
		val SECOND_BASE = 4

		val nfuCounters = Seq(
			64, 64, SECOND_BASE, SECOND_BASE+4, SECOND_BASE+2,
			SECOND_BASE+2+16, SECOND_BASE, SECOND_BASE+1, (FIRST_BASE)%65, (FIRST_BASE+1)%65,
			(FIRST_BASE+3)%65, (FIRST_BASE+3+16)%65, (FIRST_BASE+4)%65, (FIRST_BASE+4+4)%65,
			(FIRST_BASE+3+3)%65, (FIRST_BASE+3+1+3)%65
		).map(_.toByte)

		for(i <- 7 to 11) configReg(i, false)

		configReg(6, true) //disable OUTSLK
		configReg(4, true) //enable slow oscillator
		for(i <- 1 to 3) configReg(i, false)

		require(nfuCounters.length == 16)

		addData(0x0100, nfuCounters)
	}

	def sendInit() {

		val testVecIntsPre = Seq(0xb0e72d8e, 0x1dc5b862, 0xe9e7c4a6,
			0x3050f1f5, 0x8a1a6b7e,
			0x7ec384e8, 0x42c1c3fc, 0x8ed158a1, /* MIDSTATE */
			0,0,0,0,0,0,0,0,
			/* WDATA: hashMerleRoot[7], nTime, nBits, nNonce */
			0x8a0bb7b7, 0x33af304f, 0x0b290c1a, 0xf0c4e61f
		)

		//I guess we're calculating the result here?
		val testVec = {
			val sha = new ScalaSha256

			sha.initInts(testVecIntsPre take 8)
			val ints = (testVecIntsPre drop 16).flatMap(intToBytes)
			sha.update(ints)
			val seq = sha.digestSeq()

			testVecIntsPre.take(8).flatMap(intToBytes) ++ seq ++
					testVecIntsPre.drop(16).flatMap(intToBytes)
		}

		val w = Seq.fill(16)(0)
			.updated(3, 0xffffffff)
			.updated(4, 0x80000000)
			.updated(15, 0x00000280)

		addData(0x1000, w.flatMap(intToBytes))
		addData(0x1400, w.take(8).flatMap(intToBytes))

		val w2 = Seq.fill(16)(0)
				.updated(0, 0x80000000)
				.updated(7, 0x100)

		//Prepare MS and W buffers!
		addData(0x1900, w.take(8).flatMap(intToBytes))
		addData(0x3000, testVec.take(19 * 4))
	}

	def configReg(reg: Int, ena: Boolean) {
		/*
		static const uint8_t enaconf[4] = { 0xc1, 0x6a, 0x59, 0xe3 };
	static const uint8_t disconf[4] = { 0, 0, 0, 0 };

	if (ena)
		spi_add_data(info, 0x7000 + cfgreg * 32, enaconf, 4);
	else
		spi_add_data(info, 0x7000 + cfgreg * 32, disconf, 4);
		 */

		val enaConf = "c16a59e3".fromHex
		val disConf = Seq.fill(4)(0.toByte)

		if(ena) addData((0x7000 + reg * 32).toShort, enaConf)
		else addData((0x7000 + reg * 32).toShort, disConf)
	}

	/** reverse bits in each byte */
	def addReverse(dat: Seq[Byte]) {
		buffer ++= dat.map { byte =>
			var p = byte.toInt
			p = ((p & 0xaa) >> 1) | ((p & 0x55) << 1)
			p = ((p & 0xcc) >> 2) | ((p & 0x33) << 2)
			p = ((p & 0xf0) >> 4) | ((p & 0x0f) << 4)

			p.toByte
		}
	}

	def setFreq(bits: Int) {
		val freq = BigInt(1) << bits

		addData(0x6000, bintToBytes(freq, 8))
	}
}

class NanoFury(val deviceId: Usb.DeviceId,
		val workRefs: Map[ScalaMiner.HashType, ActorRef]) extends MCP2210Actor
			with BufferedReader with AbstractMiner with MetricsWorker {
	import MCP2210._
	import NanoFury._

	val nfuBits = 50 // ??
	val nChips = 2

	def readDelay = 0.millis//2.millis
	def readSize = 64
	def nonceTimeout = 10.seconds
	def hashType = ScalaMiner.SHA256
	def identity = BitFury.NFU

	var sckPinStage = 0

	implicit def ec = system.dispatcher

	def postInit() {
		log.info("DONNEE!!!")
		finishedInit = true
	}

	def reinit(after: => Unit) {
		def resetChip(n: Int) {
			if(n < 0) postInit()
			else {
				val builder = new SPIDataBuilder
				builder.addBreak()
				builder.addFASync(n)
				builder.setFreq(nfuBits)
				builder.sendConf()
				builder.sendInit()

				val dat = builder.results

				spiReset {
					transfer(dat)(resetChip(n - 1))
				}
			}
		}

		resetChip(nChips - 1)
	}

	def transfer(dat: Seq[Byte])(after: => Unit) {
		if(!dat.isEmpty) {
			val d = dat take TRANSFER_MAX

			spiSend(d)(transfer(dat drop TRANSFER_MAX)(after))
		} else after
	}

	// Bit-banging reset... Each 3 reset cycles reset first chip in chain
	def spiReset(after: => Unit) {
		pinValue = pinValue.updated(PIN_SCK_OVR, GPIO_PIN_HIGH)
		pinDirection = pinValue.updated(PIN_SCK_OVR, GPIO_OUTPUT)
		pinDesignation = pinDesignation.updated(PIN_SCK_OVR, PIN_GPIO)

		setPins()

		context.become(mcpReceive orElse {
			case Command(SET_GPIO_SETTING, dat0) =>
				def sendB(n: Int) {
					if(n == 0) spiSend(Seq(0x81.toByte)) {
						// Deactivate override
						pinDirection = pinValue.updated(PIN_SCK_OVR, GPIO_INPUT)

						setPins()

						context.unbecome()
						unstashAll()
						after
					} else spiSend(Seq(0x81.toByte))(sendB(n - 1))
				}

				sendB(16 - 1)
			case NonTerminated(_) => stash()
		}, false)
	}

	def gettingSettings: Receive = mcpReceive orElse {
		case GotSettings =>
			setSettings(spiSettings.copy(
				bitrate = 200000,
				icsv = 0xFFFF,
				acsv = 0xFFEF,
				cstdd = 0,
				ldbtcsd = 0,
				dbsdb = 0,
				bpst = 1,
				spiMode = 0
			))

			sckPinStage = -1
			log.info("Got settings stage " + sckPinStage)

		case Command(SET_SPI_SETTING, dat) if sckPinStage == -1 =>
			log.info("set setings stage " + sckPinStage)
			require(dat(1) == 0, "Failed to set spi settings! " + dat)
			sckPinStage = 0
			spiSend(Seq(0))(getPinVals())
		case GotPins if sckPinStage == 0 => //from getPinVals
			log.info("Got pins stage " + sckPinStage)

			if(pinValue(PIN_SCK_OVR) != GPIO_PIN_LOW)
				sys.error("SCK_OVRRIDE should be 0! not " + pinValue(PIN_SCK_OVR))

			//start sck polarity check
			setSettings(spiSettings.copy(spiMode = 2)) //polarity
		case Command(SET_SPI_SETTING, dat) if sckPinStage == 0 =>
			require(dat(1) == 0, "Failed to set spi settings! " + dat)
			sckPinStage = 1
			spiSend(Seq(0))(getPinVals())
		case GotPins if sckPinStage == 1 =>
			log.info("Got pins stage " + sckPinStage)

			if(pinValue(PIN_SCK_OVR) != GPIO_PIN_HIGH)
				sys.error("SCK_OVRRIDE should be 1! not " + pinValue(PIN_SCK_OVR))

			setSettings(spiSettings.copy(spiMode = 0)) //polarity
		case Command(SET_SPI_SETTING, dat) if sckPinStage == 1 =>
			require(dat(1) == 0, "Failed to set spi settings! " + dat)
			sckPinStage = 2
			spiSend(Seq(0))(getPinVals())
		case GotPins if sckPinStage == 2 => //from getPinVals
			sckPinStage = 3

			log.info("Got pins stage " + sckPinStage)

			if(pinValue(PIN_SCK_OVR) != GPIO_PIN_LOW)
				sys.error("SCK_OVRRIDE should be 0!")

			context become receive
			unstashAll()
			reinit(postInit)
		case NonTerminated(_) => stash()
	}

	def gettingPins: Receive = mcpReceive orElse {
		case GotPins =>
			log.info("pre set des " + pinDesignation)
			/* Set all pins to GPIO mode */
			pinDesignation = IndexedSeq.fill(9)(PIN_GPIO)
			/* Set all pins to input mode */
			pinDirection = IndexedSeq.fill(9)(GPIO_INPUT)
			pinValue = IndexedSeq.fill(9)(GPIO_PIN_LOW)

			pinDirection = pinDirection.updated(PIN_LED, GPIO_OUTPUT)
				.updated(PIN_PWR_EN, GPIO_OUTPUT)
				.updated(PIN_PWR_EN0, GPIO_OUTPUT)
				.updated(4, GPIO_OUTPUT)
			pinValue = pinValue.updated(PIN_LED, GPIO_PIN_HIGH)
				.updated(PIN_PWR_EN, GPIO_PIN_HIGH)
				.updated(PIN_PWR_EN0, GPIO_PIN_LOW)

			//whats pin 4 ???
			pinDesignation = pinDesignation.updated(4, PIN_CS)

			log.info("post set des " + pinDesignation)

			log.info("Setting initial pins...")

			setPins()
		case Command(SET_GPIO_SETTING, dat) =>
			cancelSPI()
		case Command(SPI_CANCEL, dat) =>
			require(dat(1) == 0, "cancel failed!")
			getSPISettings()

			context become gettingSettings
			unstashAll()
		case NonTerminated(_) => stash()
	}

	def receive = mcpReceive orElse metricsReceive orElse workReceive orElse {
		case "blaaalalal" =>
		case NonTerminated(_) => stash()
	}

	def init() {
		getPins()

		context become gettingPins
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

object NanoFury {
	val PIN_LED = 0
	val PIN_SCK_OVR = 5
	val PIN_PWR_EN = 6
	val PIN_PWR_EN0 = 7
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
