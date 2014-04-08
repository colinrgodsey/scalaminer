package com.colingodsey.scalaminer.drivers

import javax.usb._
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import javax.usb.event._
import akka.pattern._
import spray.json._
import java.io.{ByteArrayOutputStream, DataOutputStream}
import akka.actor._
import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer._
import javax.xml.bind.DatatypeConverter
import akka.util.{Timeout, ByteString}
import com.colingodsey.scalaminer.network.Stratum
import com.colingodsey.scalaminer.network.Stratum.MiningJob
import com.colingodsey.scalaminer.hashing.Hashing
import com.colingodsey.scalaminer.hashing.Hashing._
import com.colingodsey.scalaminer.Nonce
import com.colingodsey.scalaminer.network.Stratum.MiningJob
import scala.Some
import com.colingodsey.scalaminer.Work
import com.colingodsey.scalaminer.utils._
import spray.json.DefaultJsonProtocol._
import com.lambdaworks.crypto.SCrypt
import com.colingodsey.scalaminer.usb.USBManager.{InputEndpoint, OutputEndpoint, Interface}

class BFLSC(val device: UsbDevice, stratumRef: ActorRef, val identity: USBIdentity)
		extends AbstractMiner with USBDeviceActor {
	import FTDI._
	import BFLSC._
	import Constants._

	override def isFTDI = true

	override def commandDelay = 2.millis
	override def defaultTimeout = 100.seconds

	def jobTimeout = 5.minutes
	val defaultReadSize: Int = 0x2000 // ?

	val pollDelay = 10.millis
	val maxWorkQueue = 15

	val controlIndex = 0.toShort // ?
	//forgot which of these was 'device'
	lazy val miningInterface = identity.interfaces.filter(_.interface == 0).head
	val latencyVal = BAS_LATENCY

	val initIrps = List(
		device.createUsbControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_RESET, controlIndex),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_LATENCY, latencyVal, controlIndex),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_DATA, VALUE_DATA_BAS, controlIndex),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_BAUD, VALUE_BAUD_BAS,
			((INDEX_BAUD_BAS & 0xff00) | controlIndex).toShort),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_FLOW, VALUE_FLOW, controlIndex),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_MODEM, VALUE_MODEM, controlIndex),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_PURGE_TX, controlIndex),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_PURGE_RX, controlIndex)
	)

	val calcTimer = context.system.scheduler.schedule(1.seconds, 3.seconds, self, CalcStats)
	val jTimeoutTimer = context.system.scheduler.schedule(
		1.seconds, 45.seconds, self, JobTimeouts)

	var workSubmitted = 0
	//var inProcess = 0
	var midstateToJobMap: Map[Seq[Byte], Stratum.Job] = Map.empty

	stratumSubscribe(stratumRef)

	def startResultsTimer() = context.system.scheduler.scheduleOnce(
		pollDelay, self, GetResults)

	def getInfo(cb: => Unit) {
		usbCommandInfo(miningInterface, "getInfo") {
			flushRead(miningInterface)
			sendDataCommand(miningInterface, DETAILS.getBytes)()
			readLinesUntil(miningInterface, "OK") { lines =>
				val info = BFLInfo(lines)
				log.info(info.toString)
				cb ==()
			}
		}
	}

	def identify(cb: => Unit) {
		usbCommandInfo(miningInterface, "identity") {
			flushRead(miningInterface)
			sendDataCommand(miningInterface, IDENTIFY.getBytes)()
			readLine(miningInterface) { line =>
				log.info(line)
				cb ==()
			}
		}
	}

	def queueJob(job: BFLFullRangeJob)(after: => Unit) {
		usbCommandInfo(miningInterface, "queueJob") {
			sendDataCommand(miningInterface, QJOB.getBytes)()
			readLine(miningInterface) { line =>
				def bail() = {
					after ==()
					true
				}

				line match {
					case "OK" =>
						insertCommands(miningInterface) {
							sendDataCommand(miningInterface, job.payload.toArray)()
							readLine(miningInterface) { line =>
								log.debug("queueJob " + line)
								if(line == "OK:QUEUED") {
									workStarted += 1
									//workSubmitted += 1
								} else log.warning("bad queue resp " + line)
								after ==()
							}
						}
						true
					case "ERR:QUEUE FULL" =>
						log.info("queue full")
						//flushWork(after)
						bail()
					case x =>
						log.warning("Unknown queue resp " + x)
						bail()
				}
			}
		}
	}

	def startJob(job: BFLNonceJob) {
		usbCommandInfo(miningInterface, "startJob") {
			sendDataCommand(miningInterface, SENDRANGE.getBytes)()
			readLine(miningInterface) { lines1 =>
				log.info(lines1)
				true
			}
			sendDataCommand(miningInterface, job.payload.toArray)()
			readLine(miningInterface) { line =>
				log.info("startJob " + line)
				true
			}
		}
	}

	def flushWork(after: => Unit) = usbCommandInfo(miningInterface, "flushWork") {
		workSubmitted = 0
		//QFLUSH
		flushRead(miningInterface)
		sendDataCommand(miningInterface, QFLUSH.getBytes)()
		readLine(miningInterface) { line =>
			if(line.substring(0, 2) != "OK") log.warning("Flush failed with " + line)
			else {
				val flushed = line.split(" ")(1).toInt
				log.info("Flushed " + flushed)
			}
			after == ()
			true
		}

	}

	//might never fire after
	def addWork(after: => Unit) {
		val workOpt = getWork(true)

		if(workOpt.isDefined) {
			val sjob = workOpt.get
			val work = sjob.work
			val eninfo = extraNonceInfo.get

			val merkle = work.data.slice(64, 76)
			require(merkle.length == 12)

			val job2 = BFLFullRangeJob(work.midstate, merkle)
			val job = BFLNonceJob(work.midstate, merkle, eninfo.extranonce1,
				sjob.extranonce2)

			//startJob(job)
			midstateToJobMap += work.midstate -> sjob

			queueJob(job2)(after)

			log.debug("submitting work")
		}
	}

	//NOTE: this will call the timer again
	def getResults() = usbCommandInfo(miningInterface, "getResults") {
		if(workSubmitted < maxWorkQueue) {
			val n = maxWorkQueue - workSubmitted
			0.until(n).foreach(_ => {
				workSubmitted += 1
				self ! AddWork
			})
		}

		flushRead(miningInterface)
		sendDataCommand(miningInterface, QRES.getBytes)()
		readLinesUntil(miningInterface, "OK") { lines =>
			try {
				val inProcess = lines(0).split(":")(1).toInt
				val count = lines(1).split(":")(1).toInt

				//if(inProcess > 0) log.info("inProcess " + inProcess)

				workSubmitted -= count
				workSubmitted = math.max(workSubmitted, 0)
				//workSubmitted = inProcess

				//require(lines(2 + count) == "OK")

				val hashLines = 0.until(count).map(i => lines(2 + i))

				hashLines foreach { hl =>
					val parts = hl.split(",")

					val midstate = parts(0).fromHex.toSeq
					val blockData = parts(1).fromHex.toSeq
					val nNonces = parts(2).toInt
					val nonces = parts.slice(3, 3 + nNonces).map(_.fromHex).toSeq

					nonces.foreach(self ! BFLWorkResult(midstate, blockData, _))
				}
			} catch {
				case e: Throwable =>
					log.error(e, "failed parsing " + lines.toString)
					flushWork()
			}

			startResultsTimer()
		}
	}

	def doInit() {
		runIrps(initIrps) { _ =>
			identify(getInfo {
				flushWork()
				context become normal
				unstashAll()
				startResultsTimer()
			})
		}
	}

	def normal: Receive = usbBaseReceive orElse ({
		case x: MiningJob =>
			//flush old work here
			flushWork()
			workReceive(x)
	}: Receive) orElse workReceive orElse {
		case GetResults => getResults
		case CalcStats => log.info(minerStats.toString)
		case JobTimeouts =>
			val expired = midstateToJobMap.filter(_._2.runningFor > jobTimeout)

			if(!expired.isEmpty) {
				log.warning(expired.size + " jobs timed out")
				midstateToJobMap --= expired.keySet
				timedOut += expired.size
			}
		case AddWork => addWork()
		case BFLWorkResult(midstate, blockData, nonce0) =>
			val nonce = nonce0.reverse
			val jobOpt = midstateToJobMap.get(midstate)

			if (!jobOpt.isDefined) {
				log.warning("Cannot find job for midstate")
				failed += 1
			} else {
				val job = jobOpt.get
				val work = job.work
				val header = ScalaMiner.BufferType.empty ++
						work.data.take(76) ++ nonce //76 + nonce

				val rev = reverseEndian(header)
				val hashBin = doubleHash(rev)
				val hashInt = BigInt(Array(0.toByte) ++ hashBin.reverse)

				if(hashInt > (difMask / difficulty)) {
					log.debug("Share is below expected target " +
							(hashBin.toHex, targetBytes.toHex))
					short += 1
				} else {
					submitted += 1

					log.info("Submitting " + hashBin.toHex)

					val en = job.extranonce2
					val ntimepos = 17*4 // 17th integer in datastring
					val noncepos = 19*4 // 19th integer in datastring
					val ntime = header.slice(ntimepos, ntimepos + 4)
					val nonce = header.slice(noncepos, noncepos + 4)

					val params = Seq("colinrgodsey.testtt2d".toJson,
						job.id.toJson,
						en.toHex.toJson,
						ntime.toHex.toJson,
						nonce.toHex.toJson)

					stratumRef ! Stratum.SubmitStratumJob(params)
					//log.info("submitting.... to " + stratumRef)
				}
			}
	}

	def receive: Receive = usbBaseReceive orElse {
		case Start => doInit()
		case _ => stash()
	}

	override def preStart() {
		super.preStart()

		self ! Start
	}

	override def postStop() {
		super.postStop()
		calcTimer.cancel()

		context stop self
	}



}

object BFLSC extends USBDeviceDriver {
	sealed trait BFLSCCommand

	def hashType: ScalaMiner.HashType = ScalaMiner.SHA256

	case object Start extends BFLSCCommand
	case object CalcStats extends BFLSCCommand
	case object GetResults extends BFLSCCommand
	case object AddWork extends BFLSCCommand
	case object JobTimeouts extends BFLSCCommand

	val bflTimeout = 100.millis

	lazy val identities: Set[USBIdentity] = Set(BAS)

	case class BFLProc(nEngines: Int, frequency: String)

	case class BFLFullRangeJob(midState: Seq[Byte],
			blockData: Seq[Byte]) {
		def endOfBlock = Constants.EOB

		lazy val payload = {
			require(midState.length == 32)
			require(blockData.length == 12)

			ByteString((32 + 12 + 1).toByte) ++ midState ++ blockData :+ endOfBlock
		}
	}

	case class BFLWorkResult(midstate: Seq[Byte], blockData: Seq[Byte], nonce: Seq[Byte])

	case class BFLNonceJob(midState: Seq[Byte],
			blockData: Seq[Byte],
			nonceBegin: Seq[Byte], nonceEnd: Seq[Byte]) {
		def endOfBlock = Constants.EOB

		lazy val payload = {
			require(midState.length == 32)
			require(blockData.length == 12)
			require(nonceBegin.length == 4)
			require(nonceEnd.length == 4)

			ByteString((32 + 12 + 1).toByte) ++ midState ++
					blockData ++ nonceBegin ++ nonceEnd :+ endOfBlock
		}
	}

	object BFLInfo {
		def apply(strings: Seq[String]): BFLInfo = {
			val pairs = strings.map { x =>
				val spl = x.toLowerCase.split(":").map(_.trim)
				if(spl.length > 1) Some(spl(0) -> spl(1))
				else None
			}.flatten.toMap

			val procs = pairs.filter(_._1.startsWith("processor ")).map { case (k, v) =>
				val spl = k.split(" ")
				val n = spl(1).toInt

				val parts = v.split("@")

				val ne = parts(0).split(" ")(0).trim.toInt

				n -> BFLProc(ne, parts(1).trim)
			}

			BFLInfo(pairs("device"), pairs("firmware"), pairs("minig speed"),
				pairs("engines").toInt, pairs("frequency"),
				pairs("xlink mode"), pairs("critical temperature").toInt,
				pairs("xlink present") != "no", procs)
		}
	}

	case class BFLInfo(deviceName: String, firmwareV: String, speed: String, nEngines: Int,
			frequency: String, xLinkMode: String, critTemp: Int, xLinkPresent: Boolean,
			procs: Map[Int, BFLProc])

	case object BAS extends USBIdentity {
		def drv = BFLSC
		def idVendor = FTDI.vendor
		def idProduct = 0x6014
		//def iManufacturer = "Butterfly Labs"
		def iManufacturer = ""
		def iProduct = "BitFORCE SHA256 SC"
		def config = 1
		def timeout = bflTimeout

		val interfaces = Set(Interface(0, Set(
			InputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 1, 0),
			OutputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 2, 0)
		)))

		override def usbDeviceActorProps(device: UsbDevice, workRef: ActorRef): Props =
			Props(classOf[BFLSC], device, workRef, BAS)
	}

	/*
	.drv = DRIVER_bitforce,
		.name = "BFL",
		.ident = IDENT_BFL,
		.idVendor = IDVENDOR_FTDI,
		.idProduct = 0x6014,
		.iManufacturer = "Butterfly Labs Inc.",
		.iProduct = "BitFORCE SHA256",
		.config = 1,
		.timeout = BITFORCE_TIMEOUT_MS,
		.latency = LATENCY_STD,
		INTINFO(bfl_ints) },
	 */
	case object BFL extends USBIdentity {
		def drv = BFLSC
		def idVendor = FTDI.vendor
		def idProduct = 0x6014
		def iManufacturer = "Butterfly Labs Inc."
		def iProduct = "BitFORCE SHA256"
		def config = 1
		def timeout = bflTimeout

		val interfaces = Set(Interface(0, Set(
			InputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 1, 0),
			OutputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 2, 0)
		)))

		override def usbDeviceActorProps(device: UsbDevice, workRef: ActorRef): Props =
			Props(classOf[BFLSC], device, workRef, BFL)
	}

	object Constants {
		val QUE_RES_LINES_MIN = 3
		val QUE_MIDSTATE = 0
		val QUE_BLOCKDATA = 1

		val QUE_NONCECOUNT_V1 = 2
		val QUE_FLD_MIN_V1 = 3
		val QUE_FLD_MAX_V1 = (QUE_MAX_RESULTS + QUE_FLD_MIN_V1)

		val QUE_CHIP_V2 = 2
		val QUE_NONCECOUNT_V2 = 3
		val QUE_FLD_MIN_V2 = 4
		val QUE_FLD_MAX_V2 = (QUE_MAX_RESULTS + QUE_FLD_MIN_V2)

		val SIGNATURE = 0xc1
		val EOW = 0xfe

		val MIDSTATE_BYTES = 32
		val MERKLE_OFFSET = 64
		val MERKLE_BYTES = 12
		val QJOBSIZ = (MIDSTATE_BYTES + MERKLE_BYTES + 1)
		val EOB = 0xaa.toByte

		val XLINKHDR = '@'
		val MAXPAYLOAD = 255

		val QUE_MAX_RESULTS = 8

		val BUFSIZ = (0x1000)

		// = Should = be = big = enough
		val APPLOGSIZ = 8192

		val INFO_TIMEOUT = 999

		val DI_FIRMWARE = "FIRMWARE"
		val DI_ENGINES = "ENGINES"
		val DI_JOBSINQUE = "JOBS IN QUEUE"
		val DI_XLINKMODE = "XLINK MODE"
		val DI_XLINKPRESENT = "XLINK PRESENT"
		val DI_DEVICESINCHAIN = "DEVICES IN CHAIN"
		val DI_CHAINPRESENCE = "CHAIN PRESENCE MASK"
		val DI_CHIPS = "CHIP PARALLELIZATION"
		val DI_CHIPS_PARALLEL = "YES"


		//commands
		val IDENTIFY = "ZGX"
		val DETAILS = "ZCX"
		val FIRMWARE = "ZJX"
		val FLASH = "ZMX"
		val VOLTAGE = "ZTX"
		val TEMPERATURE = "ZLX"
		val QRES = "ZOX"
		val QFLUSH = "ZQX"
		val FANAUTO = "Z9X"
		val FAN0 = "Z0X"
		val FAN1 = "Z1X"
		val FAN2 = "Z2X"
		val FAN3 = "Z3X"
		val FAN4 = "Z4X"
		val LOADSTR = "ZUX"

		// = Commands = (Dual = Stage)
		val QJOB = "ZNX"
		val QJOBS = "ZWX"
		val SAVESTR = "ZSX"

		// = Replies
		val IDENTITY = "BitFORCE SC"
		val BFLSC = "SHA256 SC"

		val OK = "OK\n"
		val SUCCESS = "SUCCESS\n"

		val RESULT = "COUNT:"

		val ANERR = "ERR:"
		val TIMEOUT = ANERR + "TIMEOUT"
		// = x-link = timeout = has = a = space = (a = number = follows)
		val XTIMEOUT = ANERR + "TIMEOUT "
		val INVALID = ANERR + "INVALID DATA"
		val ERRSIG = ANERR + "SIGNATURE"
		val OKQ = "OK:QUEUED"
		val INPROCESS = "INPROCESS"
		// = Followed = by = N=1..5
		val OKQN = "OK:QUEUED "
		val QFULL = "QUEUE FULL"
		val HITEMP = "HIGH TEMPERATURE RECOVERY"
		val EMPTYSTR = "MEMORY EMPTY"

		// = Queued = and = non-queued = are = the = same
		//val FullNonceRangeJob = QueueJobStructure
		val JOBSIZ = QJOBSIZ

		// = Non = queued = commands = (not = used)
		val SENDWORK = "ZDX"
		val WORKSTATUS = "ZFX"
		val SENDRANGE = "ZPX"

		// = Non = queued = work = replies = (not = used)
		val NONCE = "NONCE-FOUND:"
		val NO_NONCE = "NO-NONCE"
		val IDLE = "IDLE"
		val BUSY = "BUSY"

		val MINIRIG = "BAM"
		val SINGLE = "BAS"
		val LITTLESINGLE = "BAL"
		val JALAPENO = "BAJ"

		// = Default = expected = time = for = a = nonce = range
		// = - = thus = no = need = to = check = until = this = + = last = time = work = was = found
		// = 60GH/s = MiniRig = (1 = board) = or = Single
		val BAM_WORK_TIME = 71.58
		val BAS_WORK_TIME = 71.58
		// = 30GH/s = Little = Single
		val BAL_WORK_TIME = 143.17
		// = 4.5GH/s = Jalapeno
		val BAJ_WORK_TIME = 954.44

		// = Defaults = (slightly = over = half = the = work = time) = but = ensure = none = are = above = 100
		// = SCAN_TIME = - = delay = after = sending = work
		// = RES_TIME = - = delay = between = checking = for = results
		val BAM_SCAN_TIME = 20
		val BAS_SCAN_TIME = 360
		val BAL_SCAN_TIME = 720
		val BAJ_SCAN_TIME = 1000
		val RES_TIME = 100
		val MAX_SLEEP = 2000

		val BAJ_LATENCY = 32.millis
		//LATENCY_STD
		val BAL_LATENCY = 12
		val BAS_LATENCY = 12.toShort
		// = For = now = a = BAM = doesn't = really = exist = - = it's = currently = 8 = independent = BASs
		val BAM_LATENCY = 2

		val TEMP_SLEEPMS = 5

		val QUE_SIZE_V1 = 20
		val QUE_FULL_ENOUGH_V1 = 13
		val QUE_WATERMARK_V1 = 6
		val QUE_LOW_V1 = 3

		// = TODO: = use = 5 = batch = jobs
		// = TODO: = base = these = numbers = on = the = chip = count?
		val QUE_SIZE_V2 = 40
		val QUE_FULL_ENOUGH_V2 = 36
		val QUE_WATERMARK_V2 = 32
		val QUE_LOW_V2 = 16

		val TEMP_OVERHEAT = 90
		// = Must = drop = this = far = below = cutoff = before = resuming = work
		val TEMP_RECOVER = 5

		// = If = initialisation = fails = the = first = time,
		// = sleep = this = amount = (ms) = and = try = again
		val REINIT_TIME_FIRST_MS = 100
		// = Max = ms = per = sleep
		val REINIT_TIME_MAX_MS = 800
		// = Keep = trying = up = to = this = many = us
		val REINIT_TIME_MAX = 3000000
	}
}
