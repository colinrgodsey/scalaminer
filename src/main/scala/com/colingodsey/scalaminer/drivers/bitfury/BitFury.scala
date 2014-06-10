
/*
 * ScalaMiner
 * ----------
 * https://github.com/colinrgodsey/scalaminer
 *
 * Copyright 2014 Colin R Godsey <colingodsey.com>
 * Copyright 2011-2014 Con Kolivas
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.  See COPYING for more details.
 */

package com.colingodsey.scalaminer.drivers.bitfury

import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer.utils._
import com.colingodsey.scalaminer.{Work, Nonce, ScalaMiner}
import scala.concurrent.duration._
import com.colingodsey.io.usb.{BufferedReader, Usb}
import akka.actor.{Props, ActorRef}
import com.colingodsey.scalaminer.metrics.{MinerMetrics, MetricsWorker}
import com.colingodsey.scalaminer.usb.UsbDeviceActor.NonTerminated
import akka.util.ByteString
import com.colingodsey.scalaminer.network.Stratum
import com.colingodsey.scalaminer.drivers.AbstractMiner


trait BitFury extends BufferedReader with AbstractMiner {
	private implicit def ec = context.system.dispatcher

	def transfer(dat: Seq[Byte])(after: Seq[Byte] => Unit)
	def nChips: Int

	def hashType = ScalaMiner.SHA256

	def jobDelay = 100.millis

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

		log.debug("Work sent to chip " + chip)

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
			log.debug("Work changed for chip " + chip)
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



