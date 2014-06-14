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

import akka.actor._
import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer._
import scala.concurrent.duration._
import com.colingodsey.scalaminer.utils._
import com.colingodsey.scalaminer.network.Stratum
import akka.util.ByteString
import com.colingodsey.scalaminer.metrics.{MetricsWorker, MinerMetrics}
import com.colingodsey.io.usb.{BufferedReader, Usb}
import com.colingodsey.io.usb.Usb.DeviceId
import com.colingodsey.scalaminer.usb.UsbDeviceActor.NonTerminated
import com.typesafe.config.Config

object GridSeedMiner {
	sealed trait Command

	case object Start extends Command
}

trait GridSeedMiner extends UsbDeviceActor with AbstractMiner
		with MetricsWorker with BufferedReader {
	import GridSeedMiner._
	import GridSeed.Constants._

	def doInit()
	def identity: USBIdentity
	def config: Config

	//dual BTC/LTC or just LTC
	def isDual = false

	lazy val freq = config getInt "freq"
	lazy val baud = config getInt "baud"
	lazy val nChips = config getInt "chips"
	lazy val altVoltage = config getBoolean "voltage" //hacked miners only

	lazy val selectedFreq = getFreqFor(freq)

	//override def defaultTimeout = 10000.millis

	def nonceTimeout = if(isScrypt) GridSeed.scryptNonceReadTimeout
	else GridSeed.btcNonceReadTimeout
	def nonceDelay = if(isFTDI) 50.millis else 3.millis

	def jobTimeout = 5.minutes

	val pollDelay = 10.millis
	val maxWorkQueue = 15

	val detectId = -23
	val chipResetId = -24

	lazy val intf = identity.interfaces.head

	var fwVersion = -1
	var readStarted = false
	var hasRead = false
	var currentJob: Option[Stratum.Job] = None

	implicit def ec = context.system.dispatcher

	case object ResetPause

	def startRead() {
		//log.info("start read")
		bufferRead(intf)
	}

	def baseReceive: Receive = metricsReceive orElse usbBufferReceive orElse workReceive

	def detecting: Receive = baseReceive orElse {
		case Usb.BulkTransferResponse(`intf`, _, `chipResetId`) =>
			context.system.scheduler.scheduleOnce(200.millis, self, ResetPause)
		case ResetPause =>
			if(isDual) {
				send(intf, dualInitBytes: _*)
				send(intf, dualResetBytes: _*)
			} else {
				send(intf, singleInitBytes: _*)
				send(intf, singleResetBytes: _*)
			}

			send(intf, frequencyCommands(selectedFreq))

			if(!isDual && altVoltage && fwVersion == 0x01140113) {
				log.info("Setting alt voltage")
				readRegister(GPIOA_BASE + CRL_OFFSET) { dat =>
					val i = getInts(dat)(0)

					val value = (i & 0xff0fffff) | 0x00300000

					writeRegister(GPIOA_BASE + CRL_OFFSET, value)

					// Set GPIOA pin 5 high.
					readRegister(GPIOA_BASE + ODR_OFFSET) { dat2 =>
						val i2 = getInts(dat2)(0)
						val value2 = i2 | 0x00000020
						writeRegister(GPIOA_BASE + ODR_OFFSET, value2)

						detected()
					}
				}
			} else if(altVoltage) {
				log.error("Cannot set alt voltage when in dual or " +
						"for fw version " + fwVersion)
				failDetect()
			} else detected()
		case Usb.BulkTransferResponse(`intf`, _, `detectId`) =>
			log.info("starting buffer")
			bufferRead(intf)
		case BufferedReader.BufferUpdated(`intf`) if fwVersion == -1 =>
			val buf = interfaceReadBuffer(intf)

			if(buf.length >= READ_SIZE) {
				val dat = buf.take(READ_SIZE)
				dropBuffer(intf, READ_SIZE)

				if(dat.take(READ_SIZE - 4) != detectRespBytes) {
					log.warning("Failed detect!")
					failDetect()
				} else {
					val bVersion = dat.drop(8).reverse
					fwVersion = BigInt(bVersion.toArray).toInt

					log.info("Grid seed detected! Version " + bVersion.toHex)

					deviceRef ! Usb.SendBulkTransfer(intf, chipResetBytes, chipResetId)
				}
			}
		case NonTerminated(_) => stash()
	}

	def detect() {
		context become detecting
		send(intf, chipResetBytes)
		deviceRef ! Usb.SendBulkTransfer(intf, detectBytes, detectId)
	}

	def detected() {
		log.info("Detected!! ")

		sendWork()

		finishedInit = true
		context become normal
		unstashAll()
		bufferRead(intf)

		self ! AbstractMiner.CancelWork
	}

	def readRegister(addr: Int)(recv: Seq[Byte] => Unit) {
		require(fwVersion == 0x01140113, "Incompatible firmware " + fwVersion)

		val cmd = "55aac001".fromHex ++ intToBytes(addr) ++
				intToBytes(regSize) ++ intToBytes(regSize)

		require(cmd.length == 16)

		deviceRef ! Usb.SendBulkTransfer(intf, cmd)
		deviceRef ! Usb.ReceiveBulkTransfer(intf, regSize, rrId)

		context.become(baseReceive orElse {
			case Usb.BulkTransferResponse(`intf`, Right(dat), `rrId`) =>
				unstashAll()
				context.unbecome()
				recv(dat)
			case Usb.BulkTransferResponse(`intf`, _, `rrId`) =>
				sys.error("Failed read register (unknown)!")
			case NonTerminated(_) => stash()
		}, false)
	}

	def writeRegister(addr: Int, value: Int) {
		require(fwVersion == 0x01140113, "Incompatible firmware " + fwVersion)

		val cmd = "55aac002".fromHex ++ intToBytes(addr) ++
				intToBytes(value) ++ intToBytes(regSize)

		require(cmd.length == 16)

		deviceRef ! Usb.SendBulkTransfer(intf, cmd)
		deviceRef ! Usb.ReceiveBulkTransfer(intf, regSize, rrId)
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

			send(intf, singleResetBytes: _*)
			send(intf, dat)

			startRead()
		}
	}

	def normal: Receive = baseReceive orElse {
		case AbstractMiner.CancelWork => sendWork()
		case BufferedReader.BufferUpdated(`intf`) =>
			val buf = interfaceReadBuffer(intf)
			if(buf.length > 0) log.debug("Buffer updated with len " + buf.length)

			if(buf.length >= READ_SIZE) {
				dropBuffer(intf, READ_SIZE)

				val packet = buf take READ_SIZE

				if(packet(0) == 0x55.toByte ||
						packet(1) == 0x20.toByte) {
					val nonce = packet.slice(4, 8)
					val iNonce = BigInt((0.toByte +: nonce).toArray)
					val chip = (iNonce / BigInt(0xffffffffL) * nChips).toInt

					if(currentJob.isDefined) {
						val job = currentJob.get

						self ! Nonce(job.work, nonce, job.extranonce2)
					}

					sendWork()

					hasRead = true
				}
			}
	}

	def receive: Receive = {
		case Start => doInit()
		case NonTerminated(_) => stash()
	}

	abstract override def preStart() {
		super.preStart()

		self ! Start

		stratumSubscribe(stratumRef)

	}
}

class GridSeedFTDIMiner(val deviceId: Usb.DeviceId, val config: Config,
		val workRefs: Map[ScalaMiner.HashType, ActorRef]) extends GridSeedMiner {
	import FTDI._
	import GridSeed._
	import Constants._

	def identity: USBIdentity = GSD2
	override def isFTDI = true
	def hashType = ScalaMiner.Scrypt
	def readDelay = 20.millis
	def readSize = 512 // ?

	def controlIndex = 0.toShort

	val lastFlow = Usb.ControlIrp(TYPE_OUT, REQUEST_FLOW, VALUE_FLOW, controlIndex)

	def doInit() {
		getDevice {
			deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_RESET, controlIndex).send
			deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_LATENCY, LATENCY, controlIndex).send
			deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_DATA, VALUE_DATA_AVA, controlIndex).send
			deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_BAUD, VALUE_BAUD_AVA,
				((INDEX_BAUD_AVA & 0xff00) | controlIndex).toShort).send
			deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_MODEM, VALUE_MODEM, controlIndex).send
			deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_FLOW, VALUE_FLOW, controlIndex).send
			deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_MODEM, VALUE_MODEM, controlIndex).send
			deviceRef ! lastFlow.send

			context become (baseReceive orElse {
				case Usb.ControlIrpResponse(`lastFlow`, _) =>
					detect()
					unstashAll()
				case NonTerminated(_) => stash()
			})
		}
	}

}

class GridSeedSGSMiner(val deviceId: Usb.DeviceId, val config: Config,
		val workRefs: Map[ScalaMiner.HashType, ActorRef]) extends GridSeedMiner {
	import FTDI._
	import GridSeed._
	import Constants._

	def identity: USBIdentity = GSD
	def hashType = ScalaMiner.Scrypt
	def readDelay = 20.millis
	def readSize = 0x2000 // ?

	override def isFTDI = false

	def doInit() {
		getDevice(detect())
	}
}

/*



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
	val ctrlIrp = deviceRef ! Usb.ControlIrp(
		CTRL_OUT,
		REQUEST_CTRL,
		VALUE_CTRL,
		interfaceDef.interface
	)
	ctrlIrp.setData(Array.empty)

	val lineCtrlIrp = deviceRef ! Usb.ControlIrp(
		CTRL_OUT,
		REQUEST_LINE,
		VALUE_LINE,
		interfaceDef.interface
	)
	lineCtrlIrp.setData(byteArrayFrom { x =>
		x.writeInt(VALUE_LINE0)
		x.writeInt(VALUE_LINE1)
	})

	val vendorIrp = deviceRef ! Usb.ControlIrp(
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

}*/

case object GridSeed extends USBDeviceDriver {
	sealed trait Command

	case object CalcStats extends Command

	def hashType: ScalaMiner.HashType = ScalaMiner.Scrypt

	override def submitsAtDifficulty = true

	val gsTimeout = 2.minutes
	val btcNonceReadTimeout = 11152.millis
	val scryptNonceReadTimeout = btcNonceReadTimeout * 3

	case object GSD extends USBIdentity {
		def drv = GridSeed
		def idVendor = 0x0483
		def idProduct = 0x5740
		def iManufacturer = "STMicroelectronics"
		def iProduct = "STM32 Virtual COM Port"
		def config = 1
		def timeout = gsTimeout

		def isMultiCoin = true

		val interfaces = Set(Usb.Interface(1, Set(
			//Endpoint(UsbConst.ENDPOINT_TYPE_INTERRUPT, 8, epi(2), 0, false),
			Usb.InputEndpoint(64, 1, 0),
			Usb.OutputEndpoint(64, 3, 0)
		)))

		override def usbDeviceActorProps(device: Usb.DeviceId, config: Config,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			Props(classOf[GridSeedSGSMiner], device, config, workRefs)
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

		val interfaces = Set(Usb.Interface(0, Set(
			Usb.InputEndpoint(64, 1, 0),
			Usb.OutputEndpoint(64, 1, 0)
		)))

		override def usbDeviceActorProps(device: Usb.DeviceId, config: Config,
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

		val interfaces = Set(Usb.Interface(0, Set(
			Usb.InputEndpoint(512, 1, 0),
			Usb.OutputEndpoint(512, 2, 0)
		)))

		override def usbDeviceActorProps(device: Usb.DeviceId, config: Config,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			Props(classOf[GridSeedFTDIMiner], device, config, workRefs)
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

		val interfaces = Set(Usb.Interface(0, Set(
			Usb.InputEndpoint(64, 3, 0),
			Usb.OutputEndpoint(64, 2, 0)
		)))

		def usbDeviceActorProps(device: Usb.DeviceId, config: Config,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			??? //Props(classOf[GSD3Device], device)
	}

	val identities: Set[USBIdentity] = Set(GSD, GSD2) //Set(GSD, GSD1, GSD2, GSD3)

	lazy val Constants = GSConstants
}

object GSConstants {
	val rrId = "RR".hashCode()

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
	//val chipResetBytes = "55AAC000808080800000000001000000".fromHex

	//from GC3355_USB_Protocol_V1.1_EN.pdf
	val chipResetBytes = "55aac000e0e0e0e00000000001000000".fromHex

	val commonInitBytes = Seq(
		"55aac000101010100000000001000000", //reset
		"55aac000c0c0c0c00500000001000000", //5chip
		"55aac000b0b0b0b000c2010001000000" //115200bps baud
	).map(_.fromHex)

	val singleInitBytes = commonInitBytes ++ Seq(
		//"55AAEF3020000000", // ?
		//"55aac000808080800000000001000000", // ??
		"55aaef020000000000000000000000000000000000000000", //power down btc units
		"55aaef3020000000", //not sure what these last 3 things do
		"55aa1f2814000000",
		"55aa1f2817000000"
	).map(_.fromHex)
	val singleResetBytes = Seq("55AA1F2816000000",
		"55AA1F2817000000").map(_.fromHex)

	val dualInitBytes = commonInitBytes ++ Seq("55AA1F2814000000",
		"55AA1F2817000000").map(_.fromHex)
	val dualResetBytes = Seq("55AA1F2814000000",
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