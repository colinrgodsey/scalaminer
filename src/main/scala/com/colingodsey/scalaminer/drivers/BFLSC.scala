/*
 * ScalaMiner
 * ----------
 * https://github.com/colinrgodsey/scalaminer
 *
 * Copyright 2014 Colin R Godsey <colingodsey.com>
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.  See COPYING for more details.
 */

package com.colingodsey.scalaminer.drivers

import scala.collection.JavaConversions._
import scala.concurrent.duration._
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
import scala.concurrent.Future
import com.colingodsey.scalaminer.metrics.{MetricsWorker, MinerMetrics}
import com.colingodsey.io.usb.{BufferedReader, Usb}
import com.colingodsey.scalaminer.ScalaMiner.HashType
import com.colingodsey.scalaminer.usb.UsbDeviceActor.NonTerminated

/**
 * Device actor for BFLSC devices. Instead of a request->response style interface,
 * the device continuously reads and parses ASCII lines. The actor may submit 2 concurrency
 * requests, as long as one of them has discrete lines that can be handled discretely.
 *
 * Ex. The flush request may be issued at the same time as getResults. The result
 * is that you may have a flush response intertwined with the getResults response.
 * Because the flush response looks unique, we can process the line before it gets
 * processed as part of the getResult response. So it can occur any time,
 * as long as its presented as a discrete line, which the BFL devices seem to do.
 * @param deviceId
 * @param workRefs
 * @param identity
 */
class BFLSC(val deviceId: Usb.DeviceId,
		val workRefs: Map[ScalaMiner.HashType, ActorRef],
		val identity: USBIdentity) extends UsbDeviceActor with AbstractMiner
		with MetricsWorker with BufferedReader {
	import FTDI._
	import BFLSC._
	import Constants._

	/**
	 * With Firmware 1.0.0 and a result queue of 20
	 * With Firmware 1.2.* and a result queue of 40 but a limit of 15 replies
	 */
	val maxWorkQueue = 20
	def jobTimeout = 5.minutes
	def pollDelay = RES_TIME - latency
	def nonceTimeout = 11.seconds
	def readDelay = latency
	def readSize = BUFSIZ
	def isFTDI = true
	override def hashType: HashType = ScalaMiner.SHA256
	val controlIndex = 0.toShort

	var workSubmitted = 0
	var bflInfo: Option[BFLInfo] = None
	var midstateToJobMap: Map[Seq[Byte], Stratum.Job] = Map.empty
	var latency = BAS_LATENCY
	var temp1 = 1.0
	var temp2 = 1.0
	var overHeating = false

	implicit def ec = system.dispatcher

	def curMaxTemp = math.max(temp1, temp2)

	lazy val intf = identity.interfaces.filter(_.interface == 0).head

	case class ReceiveLine(line: String)

	val pollTimer = context.system.scheduler.schedule(3.seconds, pollDelay, self, GetResults)
	val jobTimeoutTimer = context.system.scheduler.schedule(
		1.seconds, 45.seconds, self, JobTimeouts)

	/**
	 * autoread from the interface, break lines up and send
	 * discrete ReceiveLine events. Devices seem to never mangle lines.
	 */
	def responseReceive: Receive = usbBufferReceive orElse {
		case BufferedReader.BufferUpdated(`intf`) =>
			val buf = interfaceReadBuffer(intf)

			if(buf.length > 0) {
				log.debug("Buffer updated with len " + buf.length)

				val idx = buf indexOf '\n'.toByte

				if(idx >= 0) {
					val left = buf slice (0, idx)
					dropBuffer(intf, idx + 1)

					val line = new String(left.toArray, "ASCII")

					log.debug("New line " + line)

					self ! ReceiveLine(line)
				}
			}
	}

	def receive = {
		case NonTerminated(_) => stash()
	}

	def flushWork() {
		send(intf, QFLUSH.getBytes)
		workSubmitted = 0
	}

	def getTemp() {
		send(intf, TEMPERATURE.getBytes)
	}

	def getResults() = if(!overHeating) {
		object GetResultsTimeout
		object StartRes

		val resultTimeout = context.system.scheduler.scheduleOnce(1.second,
			self, GetResultsTimeout)

		context.system.scheduler.scheduleOnce(30.millis,
			self, StartRes)

		var started = false

		context become (normalBaseReceive orElse {
			case StartRes =>
				send(intf, QRES.getBytes)
				started = true
			case ReceiveLine(line) if !started => stash()
			case GetResultsTimeout =>
				log.error("Get results timed out!")
				context stop self
			case _: BFLSCNonCriticalCommand => //discard
			case ReceiveLine(line) if line.startsWith(INPROCESS) =>
				val n = line.drop(INPROCESS.length).toInt

				log.debug("Inprocess " + n)
			case ReceiveLine(line) if line.startsWith(RESULT) =>
				val n = line.drop(RESULT.length).toInt

				log.debug("nResults " + n)
			case ReceiveLine("OK") =>
				finishedInit = true
				unstashAll()
				context become normal
				if(workSubmitted < maxWorkQueue) addWork()
				resultTimeout.cancel()
			case ReceiveLine(line) =>
				val parts = line.split(",")

				workSubmitted = math.max(workSubmitted - 1, 0)

				val midstate = parts(0).fromHex.toSeq
				val blockData = parts(1).fromHex.toSeq
				val nNonces = parts(2).toInt
				val nonces = parts.slice(3, 3 + nNonces).map(_.fromHex).toSeq

				nonces.foreach(self ! BFLWorkResult(midstate, blockData, _))
			case NonTerminated(_) => stash()
		})
	}

	def procTemperatureLine(line: String) {
		try {
			val ts = line.split(",").map(x => x.split(":")(1).trim)

			temp1 += ts(0).toInt * 0.63
			temp1 /= 1.63
			temp2 += ts(1).toInt * 0.63
			temp2 /= 1.63
		} catch {
			case x: Throwable =>
				//log.error(x, "failed parsing temp " + lines1)
				log.info("failed parsing temp " + line)
		}

		if(!overHeating && curMaxTemp > TEMP_OVERHEAT) {
			overHeating = true
			log.warning("Overheating! Pausing work")
			//TODO: add fan
			//setFan(true)
			flushWork()
		}

		if(overHeating && (curMaxTemp + TEMP_RECOVER) < TEMP_OVERHEAT) {
			log.warning("Cooled down. Resuming work")
			overHeating = false
			//setFan(false)
		}

		log.debug(s"temp1: $temp1 temp2: $temp2 overHeating: $overHeating")
	}

	def normalBaseReceive: Receive = responseReceive orElse metricsReceive orElse
			workReceive orElse {
		case ReceiveLine(line) if line.startsWith("Temp") =>
			procTemperatureLine(line)
		case ReceiveLine(line) if line.startsWith("OK:FLUSHED") =>
			val flushed = line.split(" ")(1).toInt

			log.info("Flushed " + flushed)
			workSubmitted = math.max(workSubmitted - flushed, 0)
		case BFLWorkResult(midstate, blockData, nonce0) =>
			val nonce = nonce0.reverse
			val jobOpt = midstateToJobMap.get(midstate)

			if (!jobOpt.isDefined) {
				log.info("Cannot find job for midstate")
				self ! MinerMetrics.NonceFail
			} else {
				self ! Nonce(jobOpt.get.work, nonce, jobOpt.get.extranonce2)
			}
		case ReceiveLine(INVALID) =>
			log.warning("Failed submit!")
			workSubmitted = math.max(workSubmitted - 1, 0)
	}

	def normal: Receive = normalBaseReceive orElse {
		case AbstractMiner.CancelWork =>
			flushWork()
			addWork()
		case GetResults => getResults()
		case ReceiveLine(line) =>
			log.warning("Unhandled line " + line)
		case ExpireJobs(set) =>
			getTemp()
			if(!set.isEmpty) {
				val preSize = midstateToJobMap.size
				midstateToJobMap --= set
				val n = preSize - midstateToJobMap.size
				log.warning(n + " jobs timed out")
				self ! MinerMetrics.MetricValue(MinerMetrics.WorkTimeout, n)
			}
		case JobTimeouts =>
			val jobMap = midstateToJobMap

			Future {
				val expired = jobMap.filter(_._2.runningFor > jobTimeout)

				ExpireJobs(expired.keySet)
			} pipeTo self
	}

	def postInit() {
		unstashAll()
		context become normal

		addWork()
	}

	def init() = getDevice {
		var lines = Vector.empty[String]
		var gotIdentity = false

		object PostIrp

		val lastPurgeIrp = Usb.ControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_PURGE_RX, controlIndex)

		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_RESET, controlIndex).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_LATENCY, latency.toMillis.toShort,
			controlIndex).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_DATA, VALUE_DATA_BAS, controlIndex).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_BAUD, VALUE_BAUD_BAS,
			((INDEX_BAUD_BAS & 0xff00) | controlIndex).toShort).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_FLOW, VALUE_FLOW, controlIndex).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_MODEM, VALUE_MODEM, controlIndex).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_PURGE_TX, controlIndex).send
		deviceRef ! lastPurgeIrp.send

		context become (responseReceive orElse {
			case GetResults =>
			case Usb.ControlIrpResponse(`lastPurgeIrp`, _) =>
				context.system.scheduler.scheduleOnce(100.millis, self, PostIrp)
				send(intf, IDENTIFY.getBytes)
			case PostIrp =>
				send(intf, DETAILS.getBytes)

				startRead()
			case ReceiveLine(line) if !gotIdentity =>
				gotIdentity = true
				log.info("Identity " + line)
			case ReceiveLine("OK") =>
				log.info(lines.toString)
				bflInfo = Some(BFLInfo(lines))
				log.info("details " + bflInfo.get.toString)

				val (scanTime0, workTime0, latency0) = if(bflInfo.get.nEngines < 34) { //JAL
					(BAJ_SCAN_TIME, BAJ_WORK_TIME, BAJ_LATENCY)
				} else if(bflInfo.get.nEngines < 130) { //little single
					(BAL_SCAN_TIME, BAL_WORK_TIME, BAL_LATENCY)
				} else (BAS_SCAN_TIME, BAS_WORK_TIME, BAS_LATENCY)

				latency = latency0

				deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_LATENCY,
					latency.toMillis.toShort, controlIndex).send

				postInit()
			case ReceiveLine(line) =>
				lines :+= line
			case NonTerminated(_) => stash()
		})
	}

	def addWork() = if(workSubmitted < maxWorkQueue && !overHeating) {
		val workOpt = getWork(true)

		if(workOpt.isDefined) {
			workSubmitted += 1

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

			submitWork(job2)

			log.debug("submitting work to device")
		} else log.info("No work to add yet!")
	}

	//2 phase... lift to different behavior
	def submitWork(job: BFLFullRangeJob) {
		var phase = 0

		object StartPhase1
		object SubmitTimeout

		context.system.scheduler.scheduleOnce(20.millis, self, StartPhase1)
		val timeout = context.system.scheduler.scheduleOnce(1.5.seconds, self, SubmitTimeout)

		def finish() {
			timeout.cancel()
			context become normal
			unstashAll()
		}

		context become (normalBaseReceive orElse {
			case SubmitTimeout =>
				log.warning("submitWork timeout!")
				finish()
			case StartPhase1 =>
				send(intf, QJOB.getBytes)
				phase = 1
			case ReceiveLine("OK") if phase == 1 =>
				send(intf, job.payload)
				phase = 2
			case ReceiveLine(QFULL) =>
				log.warning("Queue fulll!")
				finish()
			case ReceiveLine(line) if phase == 1 =>
				log.warning("submitWork failed with " + line)
				finish()
			case ReceiveLine(OKQ) if phase == 2 =>
				self ! MinerMetrics.WorkStarted
				log.debug("submitWork finished")
				finish()
			case ReceiveLine(line) if phase == 2 =>
				log.warning("submitWork failed with " + line)
				finish()
			case NonTerminated(_) => stash()
		})
	}

	def startRead() = bufferRead(intf)

	override def preStart() {
		super.preStart()

		init()

		stratumSubscribe(stratumRef)
	}

	override def postStop() {
		pollTimer.cancel()
		jobTimeoutTimer.cancel()
	}
}

case object BFLSC extends USBDeviceDriver {
	sealed trait BFLSCCommand
	sealed trait BFLSCNonCriticalCommand extends BFLSCCommand

	def hashType: ScalaMiner.HashType = ScalaMiner.SHA256

	case object Start extends BFLSCCommand
	case object GetResults extends BFLSCNonCriticalCommand
	case object AddWork extends BFLSCNonCriticalCommand
	case object JobTimeouts extends BFLSCCommand
	case object CheckTemp extends BFLSCNonCriticalCommand
	case class ExpireJobs(set: Set[Seq[Byte]]) extends BFLSCCommand

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

			//for some reason, i used to get "DEVICE:"...
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

		def isMultiCoin = true

		val interfaces = Set(Usb.Interface(0, Set(
			Usb.InputEndpoint(64, 1, 0),
			Usb.OutputEndpoint(64, 2, 0)
		)))

		override def usbDeviceActorProps(device: Usb.DeviceId,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			Props(classOf[BFLSC], device, workRefs, BAS)
	}

	case object BFL extends USBIdentity {
		def drv = BFLSC
		def idVendor = FTDI.vendor
		def idProduct = 0x6014
		def iManufacturer = "Butterfly Labs Inc."
		def iProduct = "BitFORCE SHA256"
		def config = 1
		def timeout = bflTimeout

		def isMultiCoin = true

		val interfaces = Set(Usb.Interface(0, Set(
			Usb.InputEndpoint(64, 1, 0),
			Usb.OutputEndpoint(64, 2, 0)
		)))

		override def usbDeviceActorProps(device: Usb.DeviceId,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			Props(classOf[BFLSC], device, workRefs, BFL)
	}

	object Constants {
		/*val QUE_RES_LINES_MIN = 3
		val QUE_MIDSTATE = 0
		val QUE_BLOCKDATA = 1

		val QUE_NONCECOUNT_V1 = 2
		val QUE_FLD_MIN_V1 = 3
		val QUE_FLD_MAX_V1 = (QUE_MAX_RESULTS + QUE_FLD_MIN_V1)

		val QUE_CHIP_V2 = 2
		val QUE_NONCECOUNT_V2 = 3
		val QUE_FLD_MIN_V2 = 4
		val QUE_FLD_MAX_V2 = (QUE_MAX_RESULTS + QUE_FLD_MIN_V2)*/

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

		//TODO: figure out if this should really be 512 or 0x1000
		val BUFSIZ = 512 //0x1000

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

		val QJOB = "ZNX"
		val QJOBS = "ZWX"
		val SAVESTR = "ZSX"

		val IDENTITY = "BitFORCE SC"
		val BFLSC = "SHA256 SC"

		val OK = "OK\n"
		val SUCCESS = "SUCCESS\n"

		val RESULT = "COUNT:"

		val ANERR = "ERR:"
		val TIMEOUT = ANERR + "TIMEOUT"
		// x-link timeout has a space (a number follows)
		val XTIMEOUT = ANERR + "TIMEOUT "
		val INVALID = ANERR + "INVALID DATA"
		val ERRSIG = ANERR + "SIGNATURE"
		val OKQ = "OK:QUEUED"
		val INPROCESS = "INPROCESS:"
		// = Followed = by = N=1..5
		val OKQN = "OK:QUEUED "
		val QFULL = ANERR + "QUEUE FULL"
		val HITEMP = "HIGH TEMPERATURE RECOVERY"
		val EMPTYSTR = "MEMORY EMPTY"

		// Queued and non-queued are the same
		//val FullNonceRangeJob = QueueJobStructure
		val JOBSIZ = QJOBSIZ

		// Non queued commands (not used)
		val SENDWORK = "ZDX"
		val WORKSTATUS = "ZFX"
		val SENDRANGE = "ZPX"

		// Non queued work replies (not used)
		val NONCE = "NONCE-FOUND:"
		val NO_NONCE = "NO-NONCE"
		val IDLE = "IDLE"
		val BUSY = "BUSY"

		val MINIRIG = "BAM"
		val SINGLE = "BAS"
		val LITTLESINGLE = "BAL"
		val JALAPENO = "BAJ"

		// Default expected time for a nonce range
		// - thus no need to check until this + last time work was found
		// 60GH/s MiniRig (1 board) or Single
		val BAM_WORK_TIME = 71.58
		val BAS_WORK_TIME = 71.58
		// = 30GH/s = Little = Single
		val BAL_WORK_TIME = 143.17
		// = 4.5GH/s = Jalapeno
		val BAJ_WORK_TIME = 954.44

		// Defaults (slightly over half the work time) but ensure none are above 100
		// SCAN_TIME - delay after sending work
		// RES_TIME - delay between checking for results
		val BAM_SCAN_TIME = 20.millis
		val BAS_SCAN_TIME = 360.millis
		val BAL_SCAN_TIME = 720.millis
		val BAJ_SCAN_TIME = 1000.millis
		val RES_TIME = 100.millis
		val MAX_SLEEP = 2000

		val BAJ_LATENCY = 32.millis
		//LATENCY_STD
		val BAL_LATENCY = 12.millis
		val BAS_LATENCY = 12.millis
		// For now a BAM doesn't really exist - it's currently 8 independent BASs
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
		// Must drop this far below cutoff before resuming work
		val TEMP_RECOVER = 5

		// If initialisation fails the first time,
		// sleep this amount (ms) and try again
		val REINIT_TIME_FIRST_MS = 100
		// = Max = ms = per = sleep
		val REINIT_TIME_MAX_MS = 800
		// Keep trying up to this many us
		val REINIT_TIME_MAX = 3000000
	}
}
