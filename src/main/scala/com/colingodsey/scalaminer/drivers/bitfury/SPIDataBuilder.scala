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