
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

import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer.utils._
import com.colingodsey.io.usb.{BufferedReader, Usb}
import com.colingodsey.scalaminer.{Nonce, Work, ScalaMiner}
import akka.actor._
import scala.concurrent.duration._
import com.colingodsey.scalaminer.utils.CRC16
import akka.util.ByteString
import com.colingodsey.scalaminer.metrics.{MinerMetrics, MetricsWorker}
import com.colingodsey.scalaminer.ScalaMiner.HashType
import com.colingodsey.scalaminer.usb.UsbDeviceActor.NonTerminated
import com.colingodsey.scalaminer.network.Stratum

trait Icarus extends UsbDeviceActor with AbstractMiner
		with MetricsWorker with BufferedReader  {
	import Icarus._
	import Constants._

	implicit def ec = system.dispatcher

	object StartRead

	def workDivision: Int
	def chipCount: Int
	def identity: USBIdentity
	def normal: Receive

	def doInit()

	def baud = 115200
	def readDelay = 10.millis//75.millis
	def readSize = 64 //512 //NOTE: I feel like this really is always 512.. right?
	def nonceTimeout = identity.timeout //probably identity timeout
	def hashType = ScalaMiner.SHA256
	def isFTDI = false

	var lastJob: Option[Stratum.Job] = None

	//TODO: do something with this... different between devices?
	val irpTimeout = 10.millis

	lazy val intf = identity.interfaces.filter(_.interface == 0).head

	def detect() {
		log.info("Requesting golden nonce")
		send(intf, goldenOb)

		require(goldenOb.length == 64)

		context.system.scheduler.scheduleOnce(100.millis, self, StartRead)

		//context become normal
	}

	def baseReceive: Receive = usbBufferReceive orElse workReceive orElse
			metricsReceive orElse {
		case StartRead => bufferRead(intf)
		case AbstractMiner.CancelWork => self ! StartWork
		case StartWork if finishedInit =>
			log.debug("startwork")
			getWork(true) match {
				case x if miningJob == None || x == None =>
					log.info("No work yet")
					context.system.scheduler.scheduleOnce(1.second, self, StartWork)
				case Some(job: Stratum.Job) =>
					//(self ? work).mapTo[Nonce].map(x => x -> job) pipeTo self
					self ! job
			}
		case job: Stratum.Job => submitWork(job)
	}

	def submitWork(job: Stratum.Job) {
		self ! MinerMetrics.WorkStarted

		val Work(_, data, midstate, target) = job.work

		val buf = ByteString.empty ++ midstate.reverse ++
				Seq.fill(16 + 4)(0.toByte) ++ data.drop(64).take(12).reverse

		require(buf.length == 64)

		send(intf, buf)

		lastJob = Some(job)
	}

	def nonceReceive: Receive = baseReceive orElse {
		case BufferedReader.BufferUpdated(`intf`) =>
			val buf = interfaceReadBuffer(intf)

			if(buf.length >= 4) {
				val nonce = buf.take(4)
				dropBuffer(intf, buf.length)

				//log.info("nonce " + nonce)

				if(!finishedInit) {
					require(nonce == goldenNonce, s"$nonce == $goldenNonce")

					finishedInit = true

					self ! StartWork

					context become normal
					unstashAll()
				} else lastJob match {
					case Some(Stratum.Job(work, id, merk, en2, _)) =>
						self ! Nonce(work, nonce.reverse, en2)
						self ! StartWork
					case _ =>
						log.info("No job for nonce!")
				}
			}
	}

	abstract override def preStart() {
		super.preStart()

		stratumSubscribe(stratumRef)

		getDevice {
			doInit()
		}
	}
}

class ANUAMUDevice(val deviceId: Usb.DeviceId,
		val workRefs: Map[ScalaMiner.HashType, ActorRef],
		isANU: Boolean) extends Icarus {
	import Icarus._
	import Constants._
	import CP210X._

	def identity = AMU
	def workDivision: Int = 1
	def chipCount: Int = 1

	def freq = 200 //can change

	lazy val clampedFreq = getAntMinerFreq(freq).toInt

	val baudIrp = Usb.ControlIrp(TYPE_OUT, REQUEST_BAUD,
		0, intf.interface)

	object GetGolden

	def doInit() {
		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_IFC_ENABLE,
			VALUE_UART_ENABLE, intf.interface).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_DATA,
			VALUE_DATA, intf.interface).send
		deviceRef ! Usb.SendControlIrp(baudIrp, DATA_BAUD.reverse)
	}

	def sendANUFreq() {
		log.info("Setting frequency " + freq)

		val cmdBufPre = Vector[Int](2 | 0x80, (clampedFreq & 0xff00) >> 8,
			clampedFreq & 0x00ff).map(_.toByte)
		val cmdBuf = cmdBufPre :+ CRC5(cmdBufPre, 27)

		val rdRegBufPre = Vector[Int](4 | 0x80, 0, 4).map(_.toByte)
		val rdRegBuf = rdRegBufPre :+ CRC5(rdRegBufPre, 27)

		send(intf, cmdBuf)
		send(intf, rdRegBuf)

		//TODO: finish this....
	}

	def normal = nonceReceive orElse {
		case Usb.ControlIrpResponse(`baudIrp`, _) =>
			context.system.scheduler.scheduleOnce(50.millis, self, GetGolden)
			//context.system.scheduler.scheduleOnce(100.millis, self, GetGolden)

			bufferRead(intf)

			if(isANU) sendANUFreq()
		case GetGolden => detect()
	}

	def receive = normal
}
/*
class ANUDevice(val deviceId: Usb.DeviceId,
		val workRefs: Map[ScalaMiner.HashType, ActorRef],
		isANU: Boolean) extends Icarus {
	import Icarus._
	import Constants._
	import CP210X._

	def identity = AMU
	def workDivision: Int = 1
	def chipCount: Int = 1

	val baudIrp = Usb.ControlIrp(TYPE_OUT, REQUEST_BAUD,
		0, intf.interface)

	object GetGolden

	def doInit() {
		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_IFC_ENABLE,
			VALUE_UART_ENABLE, intf.interface).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_DATA,
			VALUE_DATA, intf.interface).send
		deviceRef ! Usb.SendControlIrp(baudIrp, DATA_BAUD)
	}

	def normal = nonceReceive orElse {
		case Usb.ControlIrpResponse(`baudIrp`, _) =>
			context.system.scheduler.scheduleOnce(50.millis, self, GetGolden)
		//context.system.scheduler.scheduleOnce(100.millis, self, GetGolden)
		case GetGolden => detect()
	}

	def receive = normal
}
*/
case object Icarus extends USBDeviceDriver {
	def hashType = ScalaMiner.SHA256

	lazy val identities: Set[USBIdentity] = Set(AMU, ANU)

	sealed trait Command

	case object StartWork extends Command

	case object ANU extends USBIdentity {
		import UsbDeviceManager._

		def drv = Icarus
		def idVendor = 0x10c4
		def idProduct = 0xea60.toShort
		def iManufacturer = ""
		def iProduct = ""
		def config = 1
		def timeout = 1.second //???? work timeout?

		val interfaces = Set(
			Usb.Interface(0, Set(
				Usb.InputEndpoint(64, 1, 0),
				Usb.OutputEndpoint(64, 1, 0)
			))
		)

		override def usbDeviceActorProps(device: Usb.DeviceId,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			Props(classOf[ANUAMUDevice], device, workRefs, true)
	}

	//u2?
	case object AMU extends USBIdentity {
		import UsbDeviceManager._

		def drv = Icarus
		def idVendor = 0x10c4
		def idProduct = 0xea60.toShort
		def iManufacturer = ""
		def iProduct = ""
		def config = 1
		def timeout = 1.second //???? work timeout?

		val interfaces = Set(
			Usb.Interface(0, Set(
				Usb.InputEndpoint(64, 1, 0),
				Usb.OutputEndpoint(64, 1, 0)
			))
		)

		override def usbDeviceActorProps(device: Usb.DeviceId,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			Props(classOf[ANUAMUDevice], device, workRefs, false)
	}

	object Constants {
		//should be 64
		val goldenOb = ByteString.empty ++ Seq(
			"4679ba4ec99876bf4bfe086082b40025",
			"4df6c356451471139a3afa71e48f544a",
			"00000000000000000000000000000000",
			"0000000087320b1a1426674f2fa722ce").map(_.fromHex).flatten

		val goldenNonce = "000187a2".fromHex
	}

	def getAntMinerFreq(freq: Double) = {
		var bestDiff = 1000.0
		var bestOut = freq
		var exactFound = false

		for {
			od <- 0 until 4
			no = 1 << od
			n <- 0 until 16
			nr = n + 1
			m <- 0 until 64
			nf = m + 1
			fout = 25.0 * nf / (nr * no)
			if math.abs(fout - freq) <= bestDiff
			if !exactFound
		} {
			val bs = if(500 <= (fout * no) && (fout * no) <= 1000) 1 else 0
			bestDiff = math.abs(fout - freq)
			bestOut = fout

			val freqVal = (bs << 14) | (m << 7) | (n << 2) | od;

			if(freqVal == fout) {
				exactFound = true
				bestOut = fout
			}
		}

		bestOut
	}
}
