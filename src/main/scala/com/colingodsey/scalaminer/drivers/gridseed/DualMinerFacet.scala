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

package com.colingodsey.scalaminer.drivers.gridseed

import scala.concurrent.duration._
import akka.actor._
import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer._
import com.colingodsey.scalaminer.network.Stratum
import com.colingodsey.scalaminer.utils._
import com.colingodsey.scalaminer.metrics.{MetricsWorker, MinerMetrics}
import com.colingodsey.io.usb.{BufferedReader, Usb}
import com.colingodsey.scalaminer.drivers.AbstractMiner

trait DualMinerFacet extends UsbDeviceActor with AbstractMiner
		with MetricsWorker with BufferedReader with GridSeedWork {
	import DualMiner._

	implicit def ec = system.dispatcher

	def cts: Boolean

	//TODO: think this needs this val irpDelay = 2.millis

	def nonceTimeout = if(isScrypt) 33.seconds else 11.seconds
	def readDelay = 75.millis
	def readSize = 512 // ?
	def isFTDI = true
	def identity = DualMiner.DM

	def scryptInit: Seq[Seq[Byte]] = Constants.ltc_init
	def scryptRestart: Seq[Seq[Byte]] = Constants.ltc_restart

	var goldNonceReceived = false

	val goldNonceRespId = -10

	lazy val interfaceA = identity.interfaces.filter(_.interface == 0).head
	lazy val interfaceB = identity.interfaces.filter(_.interface == 1).head

	def isDualIface = !cts

	lazy val nonceInterface = if(isScrypt && isDualIface) interfaceB
	else interfaceA

	def goldNonce = (if(isScrypt) Constants.ltc_golden_nonce.fromHex
	else Constants.btc_golden_nonce.fromHex).reverse

	def nonceReceive: Receive = usbBufferReceive orElse workReceive orElse {
		//keep reading forever
		case BufferedReader.BufferUpdated(`nonceInterface`) =>
			val buf = interfaceReadBuffer(nonceInterface)
			if(buf.length > 0) log.debug("Buffer updated with len " + buf.length)

			if(buf.length >= 4) {
				val nonce = buf.take(4)

				//TODO: maybe drop all, doesnt stream nonces?
				dropBuffer(nonceInterface, 4)

				log.debug("Nonce " + nonce.toList)

				if(!goldNonceReceived) {
					log.info("golden nonce " + nonce.toList)

					require(nonce.toList == goldNonce.toList,
						nonce.toList + " != " + goldNonce.toList)

					goldNonceReceived = true
					finishedInit = true

					postInit()
				} else lastJob match {
					case Some(Stratum.Job(work, id, merk, en2, _)) =>
						self ! Nonce(work, nonce, en2)
						self ! StartWork
					case _ =>
						log.info("No job for nonce!")
				}
			}
			self ! MinerMetrics.DevicePoll
			startRead()
	}

	def requestGolden() {
		val randomness = "FFFFFFFFFFFFFFFF"
		val cmd = (if(isScrypt) Constants.ltc_golden.head
		else Constants.btc_golden.head) + randomness

		deviceRef ! Usb.SendBulkTransfer(nonceInterface, cmd.fromHex, goldNonceRespId)
	}

	def openBTCNonceUnits(units: Int) {
		val bin = Constants.btc_single_open

		send(nonceInterface, bin.take(units + 1): _*)
	}

	def send(cmds: Seq[Byte]*): Unit =
		send(nonceInterface, cmds: _*)

	def normal: Receive = nonceReceive orElse metricsReceive orElse {
		case AbstractMiner.CancelWork => self ! StartWork
		case StartWork =>
			log.debug("startwork")
			getWork(true) match {
				case x if miningJob == None || x == None =>
					log.info("No work yet")
					context.system.scheduler.scheduleOnce(1.second, self, StartWork)
				case Some(job: Stratum.Job) =>
					//(self ? work).mapTo[Nonce].map(x => x -> job) pipeTo self
					self ! job
					startRead()
			}

		case job: Stratum.Job => sendWork(job)
	}

	def postInit() {
		context become normal
		unstashAll()
		finishedInit = true

		//open nonce units
		if(!isScrypt) {
			send(nonceInterface,
				Constants.btc_close_nonce_unit.map(_.fromHex): _*)
			if(!cts) openBTCNonceUnits(Constants.DEFAULT_0_9V_BTC)
			else openBTCNonceUnits(Constants.DEFAULT_1_2V_BTC)
		}
	}

	def startRead() {
		//log.info("start read")
		bufferRead(nonceInterface)
	}

	abstract override def preStart() {
		super.preStart()

		stratumSubscribe(stratumRef)
	}

	abstract override def postStop() {
		stratumUnSubscribe(stratumRef)

		super.postStop()
	}
}
