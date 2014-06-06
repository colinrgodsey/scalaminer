package com.colingodsey.scalaminer.drivers

import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer.utils._
import com.colingodsey.scalaminer.{Work, Nonce, MinerIdentity, ScalaMiner}
import scala.concurrent.duration._
import com.colingodsey.io.usb.{BufferedReader, Usb}
import akka.actor.{Props, ActorRef}
import com.colingodsey.scalaminer.metrics.{MinerMetrics, MetricsWorker}
import com.colingodsey.scalaminer.ScalaMiner.HashType
import com.colingodsey.scalaminer.usb.UsbDeviceActor.NonTerminated
import akka.util.ByteString
import com.colingodsey.scalaminer.network.Stratum
import com.colingodsey.Sha256

class NanoFury(val deviceId: Usb.DeviceId,
		val workRefs: Map[ScalaMiner.HashType, ActorRef]) extends MCP2210Actor with BitFury
			with BufferedReader with AbstractMiner with MetricsWorker {
	import MCP2210._
	import NanoFury._

	val nfuBits = 50 // ?? also seen 30?
	val nChips = 1

	def readDelay = 2.millis
	def readSize = 64
	def nonceTimeout = 15.seconds
	def identity = BitFury.NFU

	var sckPinStage = 0

	implicit def ec = system.dispatcher

	def postInit() {
		log.info("DONNEE!!!")
		finishedInit = true
		context become normal
		unstashAll()
		self ! AbstractMiner.CancelWork
	}

	def reinit(after: => Unit) {
		def resetChip(n: Int) {
			if(n < 0) after
			else {
				val builder = new SPIDataBuilder
				builder.addBreak()
				builder.addFASync(n)
				builder.setFreq(nfuBits)
				builder.sendConf()
				builder.sendInit()

				val dat = builder.results

				spiReset {
					transfer(dat.view)(_ => resetChip(n - 1))
				}
			}
		}

		resetChip(nChips - 1)
	}

	def transfer(dat: Seq[Byte])(after: Seq[Byte] => Unit): Unit =
		transfer(dat, ByteString.empty)(after)

	def transfer(dat: Seq[Byte], buffer: ByteString = ByteString.empty)(after: Seq[Byte] => Unit) {
		//log.info("Sending transfer total " + dat.length)
		if(!dat.isEmpty) {
			val d = dat take TRANSFER_MAX

			spiSend(d)(out => {
				//log.info("transfer resp " + out.toHex)
				transfer(dat drop TRANSFER_MAX, buffer ++ out)(after)
			})
		} else after(buffer)
	}

	// Bit-banging reset... Each 3 reset cycles reset first chip in chain
	def spiReset(after: => Unit) {
		pinValue = pinValue.updated(PIN_SCK_OVR, GPIO_PIN_HIGH)
		pinDirection = pinDirection.updated(PIN_SCK_OVR, GPIO_OUTPUT)
		pinDesignation = pinDesignation.updated(PIN_SCK_OVR, PIN_GPIO)

		setPins()

		var posPinSet = false

		def sendB(n: Int) {
			if(n == 0) spiSend(Seq(0x81.toByte)) { _ =>
				// Deactivate override
				pinDirection = pinDirection.updated(PIN_SCK_OVR, GPIO_INPUT)

				setPins()
				posPinSet = true
			} else spiSend(Seq(0x81.toByte))(_ => sendB(n - 1))
		}

		context.become(mcpReceive orElse {
			case Command(SET_GPIO_SETTING, _) if !posPinSet =>
				sendB(16 - 1)
			case Command(SET_GPIO_SETTING, _) if posPinSet =>
				context.unbecome()
				unstashAll()
				after
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
			spiSend(Seq(0))(_ => getPinVals())
		case GotPins if sckPinStage == 0 => //from getPinVals
			log.info("Got pins stage " + sckPinStage)

			if(pinValue(PIN_SCK_OVR) != GPIO_PIN_LOW)
				sys.error("SCK_OVRRIDE should be 0! not " + pinValue(PIN_SCK_OVR))

			//start sck polarity check
			setSettings(spiSettings.copy(spiMode = 2)) //polarity
		case Command(SET_SPI_SETTING, dat) if sckPinStage == 0 =>
			require(dat(1) == 0, "Failed to set spi settings! " + dat)
			sckPinStage = 1
			spiSend(Seq(0))(_ => getPinVals())
		case GotPins if sckPinStage == 1 =>
			log.info("Got pins stage " + sckPinStage)

			if(pinValue(PIN_SCK_OVR) != GPIO_PIN_HIGH)
				sys.error("SCK_OVRRIDE should be 1! not " + pinValue(PIN_SCK_OVR))

			setSettings(spiSettings.copy(spiMode = 0)) //polarity
		case Command(SET_SPI_SETTING, dat) if sckPinStage == 1 =>
			require(dat(1) == 0, "Failed to set spi settings! " + dat)
			sckPinStage = 2
			spiSend(Seq(0))(_ => getPinVals())
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

	def receive = mcpReceive orElse metricsReceive orElse {
		case NonTerminated(_) => stash()
	}

	def normal: Receive = mcpReceive orElse metricsReceive orElse
			workReceive orElse bitFuryReceive

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

object BXMDevice {
	sealed trait Command

	//case class ReceiveLine(cmd: String) extends Command
}

class BXMDevice(val deviceId: Usb.DeviceId,
		val workRefs: Map[ScalaMiner.HashType, ActorRef]) extends UsbDeviceActor with BitFury
		with BufferedReader with AbstractMiner with MetricsWorker {

	import FTDI._
	import BitFury.Constants._
	import BXMDevice._

	def controlIndex = 1.toByte
	val latency = 2.millis
	val freq = 200000
	val identity = BitFury.BXM
	val nChips = 2
	val bxmBits = 54
	//TODO: im guessing this can be set by stratum
	//val rollLimit = 60.seconds
	val transferTimeout = 5.seconds

	val readDelay: FiniteDuration = 2.millis
	val isFTDI: Boolean = true
	val readSize: Int = 512
	val nonceTimeout: FiniteDuration = 15.seconds

	val TWELVE_MHZ = 12000000

	//bitmodes
	val bitmask = 0
	val resetMode = (bitmask | (BITMODE_RESET << 8)).toShort //Do a BITMODE_RESET
	val mpsseMode = (bitmask | (BITMODE_MPSSE << 8)).toShort //Now set to MPSSE mode

	val mpsseBitmodeRequest = Usb.ControlIrp(TYPE_OUT, SIO_SET_BITMODE_REQUEST,
		mpsseMode, controlIndex)

	lazy val intf = identity.interfaces.filter(_.interface == 0).head

	private implicit def ec = context.system.dispatcher

	def init() = getDevice {
		log.info("Sending init")

		deviceRef ! Usb.ControlIrp(TYPE_OUT, SIO_RESET_REQUEST, SIO_RESET_SIO, controlIndex).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, SIO_SET_LATENCY_TIMER_REQUEST,
			latency.toMillis.toShort, controlIndex).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, SIO_SET_EVENT_CHAR_REQUEST, 0, controlIndex).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, SIO_SET_BITMODE_REQUEST,
			resetMode, controlIndex).send
		deviceRef ! mpsseBitmodeRequest.send
	}

	def purgeBuffers() {
		deviceRef ! Usb.ControlIrp(TYPE_OUT, SIO_RESET_REQUEST, SIO_RESET_PURGE_RX, controlIndex).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, SIO_RESET_REQUEST, SIO_RESET_PURGE_TX, controlIndex).send
	}

	def setCSHigh() =
		send(intf, Seq(SET_OUT_ADBUS, DEFAULT_STATE, DEFAULT_DIR))

	def setCSLow() = {
		val dat = Seq[Byte](SET_OUT_ADBUS, (0 & (~DEFAULT_STATE)).toByte, DEFAULT_DIR)

		deviceRef ! Usb.SendBulkTransfer(intf, dat, csLowSet)
	}

	def reset() {
		val resetBuf = Seq(0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00, 0xFF, 0x00).map(_.toByte)

		val bytes = Seq[Byte](WRITE_BYTES_SPI0, (16 - 1).toByte, 0) ++ resetBuf ++ resetBuf

		setCSHigh()
		send(intf, bytes)
		setCSLow()
	}

	def transfer(dat0: Seq[Byte])(after: Seq[Byte] => Unit) {
		//log.info("Sending transfer total " + dat.length)
		if(!dat0.isEmpty) {
			//val d = dat take TRANSFER_MAX

			val ftdiLength = dat0.length - 1

			val dat = Seq[Byte](READ_WRITE_BYTES_SPI0, (ftdiLength & 0xFF).toByte,
				((ftdiLength >> 8) & 0xFF).toByte) ++ dat0

			//val id = (math.random * 5000).toInt

			object StartTransfer
			object TimedOut

			//send this as a message incase another become takes over
			self ! StartTransfer

			val timeoutTimer = context.system.scheduler.scheduleOnce(
				transferTimeout, self, TimedOut)

			context.become(baseReceive orElse {
				case StartTransfer =>
					//deviceRef ! Usb.SendBulkTransfer(intf, dat, id)
					send(intf, dat)
					bufferRead(intf)
					dropBuffer(intf)
				//case Usb.BulkTransferResponse(`intf`, Right(response), `id`) =>
				case BufferedReader.BufferUpdated(`intf`) =>
					val buf = interfaceReadBuffer(intf)

					if(buf.length >= dat0.length) {
						//TODO: drop all, or just what we took?
						//dropBuffer(intf, dat0.length)
						dropBuffer(intf)
						unstashAll()
						context.unbecome()
						timeoutTimer.cancel()
						after(buf take dat0.length)
					}
				//TODO: should probably be just one chip
				case BitFury.SendWork(_) =>
				case TimedOut =>
					log.warning("Transfer timed out!")
					context stop self
				case NonTerminated(_) => stash()
			}, false)
		} else after(Nil)
	}

	def reinit() {
		log.info("Reinit chips")

		context become normal

		var chipsDone = 0

		for(i <- 0 until nChips) {
			val builder = new SPIDataBuilder

			builder.addBreak()
			builder.addFASync(i)
			builder.setFreq(bxmBits)
			builder.sendConf()
			builder.sendInit()
			transfer(builder.results) { _ =>
				chipsDone += 1
				if(chipsDone == nChips) {
					//finishedInit = true
					dropBuffer(intf)
					//log.info("Detected!")
				}
			}
		}
	}

	//unique ids
	val highPinsSet = 20
	val csLowSet = 21

	def baseReceive: Receive = metricsReceive orElse workReceive orElse usbBufferReceive

	def receive: Receive = baseReceive orElse {
		case Usb.ControlIrpResponse(`mpsseBitmodeRequest`, _) =>
			log.info("MPSSE mode set")

			//Now set the clock divisor
			//First send just the 0x8B command to set the system clock to 12MHz
			send(intf, Seq(TCK_D5))

			val divisor = (TWELVE_MHZ / freq) / 2 - 1

			send(intf, Seq(TCK_DIVISOR, (divisor & 0xFF).toByte, ((divisor >> 8) & 0xFF).toByte))

			//Disable internal loopback
			send(intf, Seq(LOOPBACK_END))
			//Now set direction and idle (initial) states for the pins
			send(intf, Seq(SET_OUT_ADBUS, DEFAULT_STATE, DEFAULT_DIR))
			//Set the pin states for the HIGH_BITS port as all outputs, all low
			//send(intf, Seq(SET_OUT_ACBUS, 0/*Bitmask for HIGH_PORT*/, 0xFF.toByte))
			deviceRef ! Usb.SendBulkTransfer(intf,
				Seq[Byte](SET_OUT_ACBUS, 0/*Bitmask for HIGH_PORT*/, 0xFF.toByte), highPinsSet)

		case Usb.BulkTransferResponse(`intf`, _, `highPinsSet`) =>
			log.info("High pins set")
			purgeBuffers()

			reset()
		case Usb.BulkTransferResponse(`intf`, _, `csLowSet`) =>
			log.info("Low pins set")
			purgeBuffers()

			//start dummy read
			transfer(Seq.fill[Byte](80)(0))(_ => reinit())
		case NonTerminated(_) => stash()
	}

	def normal: Receive = bitFuryReceive orElse baseReceive

	override def preStart() {
		super.preStart()

		init()

		stratumSubscribe(stratumRef)
	}

	override def postStop() {
		super.postStop()

		deviceRef ! Usb.ControlIrp(TYPE_OUT, SIO_SET_BITMODE_REQUEST,
			resetMode, controlIndex).send
	}
}

trait BitFury extends BufferedReader with AbstractMiner {
	private implicit def ec = context.system.dispatcher

	def transfer(dat: Seq[Byte])(after: Seq[Byte] => Unit)
	def nChips: Int

	def hashType = ScalaMiner.SHA256

	def jobDelay = 30.millis

	//var jobByChip: Map[Int, Stratum.Job] = Map.empty
	var workSendingToChip = Set.empty[Int]
	var lastResPerChip: Map[Int, Seq[Int]] = Map.empty
	var chipNextWork = Map[Int, Stratum.Job]()
	var chipCurrentWork = Map[Int, Stratum.Job]()

	def oldNonces(chip: Int) =
		lastResPerChip.getOrElse(chip, Nil)

	def bfSendJob(chip: Int, nextJob: Stratum.Job): Unit = if(!workSendingToChip(chip)) {
		workSendingToChip += chip

		val builder = new SPIDataBuilder

		val payload = BitFury.genPayload(nextJob.work)

		self ! MinerMetrics.WorkStarted

		builder.addBreak()
		builder.addFASync(chip)
		builder.addData(0x3000, payload.view.take(76))

		log.info("Work sent to chip " + chip)

		transfer(builder.results) { dat0 =>
			val dat = dat0.drop(4 + chip).take(19 * 4)
			//log.info("Work resp dat " + dat.toHex)

			decNonces(dat, chip, chipCurrentWork(chip))

			context.system.scheduler.scheduleOnce(
				jobDelay, self, BitFury.UnBusyChip(chip))
		}
	}

	def bfSendWork(chip: Int): Unit = if(!workSendingToChip(chip)) {
		if(!chipNextWork.contains(chip)) {
			val opt = getWork(true)

			if(!opt.isDefined) log.info("No work yet")

			opt foreach { job =>
				chipNextWork += chip -> job
				if(!chipCurrentWork.contains(chip))
					chipCurrentWork += chip -> job
				bfSendJob(chip, job)
			}
		} else bfSendJob(chip, chipNextWork(chip))
	}

	def decNonces(encNonce: Seq[Byte], chip: Int, job: Stratum.Job) {
		val nonceInts = getInts(encNonce.reverseEndian)
		val old = oldNonces(chip)
		val oldNonceSet = old.toSet
		val newNonceInts = nonceInts.filter(!oldNonceSet(_))
		val nonces = BitFury.noncesFromResponse(newNonceInts)

		val workChanged = {
			old.length <= 16 || nonceInts.length <= 16 || old(16) != nonceInts(16)
		}

		if(workChanged) {
			log.info("Work changed for chip " + chip)
			chipCurrentWork += chip -> chipNextWork(chip)
			chipNextWork -= chip
		}

		for {
			//job <- jobByChip get chip
			nonce <- nonces
			nonceBytes = intToBytes(nonce.toInt).reverse
			//_ = println(nonceBytes)
		} self ! Nonce(job.work, nonceBytes, job.extranonce2)

		finishedInit = true

		lastResPerChip += chip -> nonceInts
	}

	abstract override def preStart() {
		super.preStart()

		context.system.scheduler.schedule(1.second, nonceTimeout,
			self, BitFury.ClearWork)(context.system.dispatcher)
	}

	def bitFuryReceive: Receive = {
		/*case AbstractMiner.ValidShareProcessed =>
			chipNextWork = Map.empty
			for(i <- 0 until nChips) self ! BitFury.SendWork(i)*/
		case BitFury.ClearWork =>
			chipNextWork = Map.empty
			for(i <- 0 until nChips) self ! BitFury.SendWork(i)
		case BitFury.UnBusyChip(chip) =>
			workSendingToChip -= chip
			self ! BitFury.SendWork(chip)
		case AbstractMiner.CancelWork =>
			chipNextWork = Map.empty
			for(i <- 0 until nChips) self ! BitFury.SendWork(i)
		case BitFury.SendWork(chip) => bfSendWork(chip)
	}
}

case object BitFury extends USBDeviceDriver {
	sealed trait Command

	val defaultTimeout = 100.millis

	def hashType = ScalaMiner.SHA256

	lazy val identities: Set[USBIdentity] = Set(NFU, BXM)

	case class SendWork(chip: Int) extends Command
	case class UnBusyChip(chip: Int) extends Command

	case object ClearWork extends Command

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

	case object BXM extends USBIdentity {
		import UsbDeviceManager._

		def drv = BitFury
		def idVendor = FTDI.vendor
		def idProduct = 0x6014
		def iManufacturer = ""
		def iProduct = "FT232H Single HS"
		def config = 1
		def timeout = defaultTimeout

		val interfaces = Set(
			Usb.Interface(0, Set(
				Usb.InputEndpoint(512, 1, 0),
				Usb.OutputEndpoint(512, 2, 0)
			))
		)

		override def usbDeviceActorProps(device: Usb.DeviceId,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			Props(classOf[BXMDevice], device, workRefs)
	}

	def genPayload(work: Work) = {
		/*
		struct bitfury_payload {
	unsigned char midstate[32];
	unsigned int junk[8];
	unsigned m7;
	unsigned ntime;
	unsigned nbits;
	unsigned nnonce;
};
		 */

		//almost positive we just need to drop 64 bytes of header and replace with midstate
		//and zeros

		val dat = work.midstate ++ Array.fill(8 * 4)(0.toByte) ++
				work.data.view.drop(64).take(12) ++ Array.fill(4)(0.toByte)

		require(dat.length == 80, "len " + dat.length)

		(ScalaSha256 ms3Steps getInts(reverseEndian(dat)).toIndexedSeq).flatMap(intToBytes(_).reverse)
		//SPIDataBuilder.testVec
	}

	def noncesFromResponseBytes(encNonces: Seq[Byte]): Seq[Int] =
		noncesFromResponse(getInts(encNonces.reverseEndian))

	//a - b
	def uIntSub(a: Int, b: Int) = {
		val a1 = a & 0xFFFFFFFFL
		val b1 = b & 0xFFFFFFFFL

		(if(b == 0) a
		else if(a1 < b1) a1 - b1 + 0xFFFFFFFFL
		else a1 - b1).toInt
	}

	def noncesFromResponse(nonceInts: Seq[Int]): Seq[Int] = {

		nonceInts flatMap { nonceInt =>
			var in = nonceInt

			//require(in >= 0)

			var out = (in & 0xFF) << 24

			/* First part load */
			in >>>= 8

			/* Byte reversal */
			in = (((in & 0xaaaaaaaa) >>> 1) | ((in & 0x55555555) << 1))
			in = (((in & 0xcccccccc) >>> 2) | ((in & 0x33333333) << 2))
			in = (((in & 0xf0f0f0f0) >>> 4) | ((in & 0x0f0f0f0f) << 4))

			out |= (in >>> 2) & 0x3FFFFF

			/* Extraction */
			if ((in & 1) != 0)
				out |= (1 << 23)
			if ((in & 2) != 0)
				out |= (1 << 22)

			//intToBytes(out - 0x800004).reverse
			val nonce = out - 0x800004//uIntSub(out, 0x800004)

			if(nonceInt != -1)
				Seq(0x800000, 0, 0x400000).map(nonce - _)
				//Seq(0x800000, 0, 0x400000).map(uIntSub(nonce, _))
			else Nil
		}
	}

	object Constants {
		//Low port pins
		val SK      = 1.toByte
		val DO      = 2.toByte
		val DI      = 4.toByte
		val CS      = 8.toByte
		val GPIO0   = 16.toByte
		val GPIO1   = 32.toByte
		val GPIO2   = 64.toByte
		val GPIO3   = 128.toByte

		//GPIO pins
		val GPIOL0  = 0.toByte
		val GPIOL1  = 1.toByte
		val GPIOL2  = 2.toByte
		val GPIOL3  = 3.toByte
		val GPIOH   = 4.toByte
		val GPIOH1  = 5.toByte
		val GPIOH2  = 6.toByte
		val GPIOH3  = 7.toByte
		val GPIOH4  = 8.toByte
		val GPIOH5  = 9.toByte
		val GPIOH6  = 10.toByte
		val GPIOH7  = 11.toByte

		val DEFAULT_DIR = (SK | DO | CS | GPIO0 | GPIO1 | GPIO2 | GPIO3).toByte  /* Setup default input or output state per FTDI for SPI */
		val DEFAULT_STATE = (CS).toByte                                       /* CS idles high, CLK idles LOW for SPI0 */
	}
}

object SPIDataBuilder {
	val testVecIntsPre = Vector(0xb0e72d8e, 0x1dc5b862, 0xe9e7c4a6,
		0x3050f1f5, 0x8a1a6b7e,
		0x7ec384e8, 0x42c1c3fc, 0x8ed158a1, /* MIDSTATE */
		0,0,0,0,0,0,0,0,
		/* WDATA: hashMerleRoot[7], nTime, nBits, nNonce */
		0x8a0bb7b7, 0x33af304f, 0x0b290c1a, 0xf0c4e61f
	)

	//super midstate?
	val testVec = {
		ScalaSha256.ms3Steps(testVecIntsPre).flatMap(intToBytes(_).reverse)
	}
}

class SPIDataBuilder {
	import SPIDataBuilder._

	private var buffer = ByteString.empty

	def results = buffer

	def addData(addr: Short, dat: Seq[Byte]) {

		val len = dat.length

		if (len < 4 || len > 128) sys.error("Bad SPI data length " + len)

		buffer ++= Seq[Byte](
			((len / 4 - 1) | 0xE0).toByte,
			((addr >>> 8) & 0xFF).toByte,
			(addr & 0xFF).toByte
		)

		addReverse(dat)
	}

	def addBreak() = buffer :+= 4.toByte
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

		val w = Seq.fill(16)(0)
				.updated(3, 0xffffffff)
				.updated(4, 0x80000000)
				.updated(15, 0x00000280)

		val b1 = w.flatMap(intToBytes(_).reverse)
		val b2 = w.take(8).flatMap(intToBytes(_).reverse)

		require(b1.length == 16 * 4)
		require(b2.length == 8 * 4)

		addData(0x1000, b1)
		addData(0x1400, b2)

		val w2 = Seq.fill(16)(0)
				.updated(0, 0x80000000)
				.updated(7, 0x100)

		val b3 = w2.take(8).flatMap(intToBytes(_).reverse)
		val b4 = testVec.take(19 * 4)

		require(b3.length == 8 * 4)
		require(b4.length == 19 * 4)

		//Prepare MS and W buffers!
		addData(0x1900, b3)
		addData(0x3000, b4)
	}

	def configReg(reg: Int, ena: Boolean) {
		val enaConf = "c16a59e3".fromHex
		val disConf = Seq.fill(4)(0.toByte)

		if(ena) addData((0x7000 + reg * 32).toShort, enaConf)
		else addData((0x7000 + reg * 32).toShort, disConf)
	}

	/** reverse bits in each byte */
	def addReverse(dat: Seq[Byte]) {
		buffer ++= dat.map { byte =>
			var p = byte.toInt
			p = ((p & 0xaa) >>> 1) | ((p & 0x55) << 1)
			p = ((p & 0xcc) >>> 2) | ((p & 0x33) << 2)
			p = ((p & 0xf0) >>> 4) | ((p & 0x0f) << 4)

			p.toByte
		}
	}

	def setFreq(bits: Int) {
		val freq = BigInt(1) << bits

		val d = bintToBytes(freq - 1, 8).reverse

		require(d.length == 8)

		addData(0x6000, d)
	}
}