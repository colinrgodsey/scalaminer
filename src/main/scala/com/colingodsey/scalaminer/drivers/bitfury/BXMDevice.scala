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
import com.typesafe.config.Config

object BXMDevice {
	 sealed trait Command

	 //case class ReceiveLine(cmd: String) extends Command
 }

class BXMDevice(val deviceId: Usb.DeviceId, val config: Config,
		val workRefs: Map[ScalaMiner.HashType, ActorRef]) extends UsbDeviceActor with BitFury
		with BufferedReader with AbstractMiner with MetricsWorker {
	import FTDI._
	import BitFury.Constants._

	def controlIndex = 1.toByte
	val latency = 2.millis
	val freq = 200000
	val identity = BitFury.BXM

	//TODO: im guessing this can be set by stratum
	//val rollLimit = 60.seconds
	val transferTimeout = 5.seconds

	val readDelay: FiniteDuration = 20.millis//latency * 2
	val isFTDI: Boolean = true
	def readSize = identity.interfaces.head.output.size //512
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

		bufferRead(intf)

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
		dropBuffer(intf)
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
			builder.setFreq(freqBits)
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

			dropBuffer(intf)

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

		for(i <- 0 until nChips) {
			val builder = new SPIDataBuilder

			builder.addBreak()
			builder.addFASync(i)
			builder.configReg(4, false)
			send(intf, builder.results)
		}

		purgeBuffers()
		deviceRef ! Usb.ControlIrp(TYPE_OUT, SIO_SET_BITMODE_REQUEST,
			resetMode, controlIndex).send
	}
}