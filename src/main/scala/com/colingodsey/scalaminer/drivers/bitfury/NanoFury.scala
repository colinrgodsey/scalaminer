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

class NanoFury(val deviceId: Usb.DeviceId,
			val workRefs: Map[ScalaMiner.HashType, ActorRef]) extends MCP2210Actor with BitFury
			with BufferedReader with AbstractMiner with MetricsWorker {
	  import MCP2210._
	  import NanoFury._

	  val nfuBits = 50 // ?? also seen 30?
	  val nChips = 1

	  def readDelay = 20.millis
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