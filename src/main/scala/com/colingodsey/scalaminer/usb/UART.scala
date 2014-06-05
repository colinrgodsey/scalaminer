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

package com.colingodsey.scalaminer.usb

import org.usb4java.LibUsb
import com.colingodsey.scalaminer.utils._
import akka.actor.Actor
import com.colingodsey.io.usb.{BufferedReader, Usb}
import akka.util.ByteString
import com.colingodsey.scalaminer.usb.UsbDeviceActor.NonTerminated

case object CP210X {
	val TYPE_OUT = 0x41.toByte

	val REQUEST_IFC_ENABLE = 0x00.toByte
	val REQUEST_DATA = 0x07.toByte
	val REQUEST_BAUD = 0x1e.toByte

	val VALUE_UART_ENABLE = 0x0001.toShort
	val VALUE_DATA = 0x0303.toShort
	val DATA_BAUD = "0001c200".fromHex
}

case object FTDI {
	//OSX: sudo kextunload -bundle com.apple.driver.AppleUSBFTDI

	val TYPE_OUT = (LibUsb.REQUEST_TYPE_VENDOR |
			LibUsb.RECIPIENT_DEVICE | LibUsb.ENDPOINT_OUT).toByte
	val TYPE_IN = (LibUsb.REQUEST_TYPE_VENDOR |
			LibUsb.RECIPIENT_DEVICE | LibUsb.ENDPOINT_IN).toByte

	val REQUEST_RESET: Byte = 0
	val REQUEST_MODEM: Byte = 1
	val REQUEST_FLOW: Byte = 2
	val REQUEST_BAUD: Byte = 3
	val REQUEST_DATA: Byte = 4
	val REQUEST_LATENCY: Byte = 9

	val VALUE_DATA_BFL = 0.toShort
	val VALUE_DATA_BAS = VALUE_DATA_BFL
	// LLT = BLT (same code)
	val VALUE_DATA_BLT = 8.toShort
	val VALUE_DATA_AVA = 8.toShort

	val VALUE_BAUD_BFL = 0xc068.toShort
	val INDEX_BAUD_BFL = 0x0200.toShort
	val VALUE_BAUD_BAS = VALUE_BAUD_BFL
	val INDEX_BAUD_BAS = INDEX_BAUD_BFL
	val VALUE_BAUD_AVA = 0x001A.toShort
	val INDEX_BAUD_AVA = 0x0000.toShort

	val VALUE_FLOW = 0.toShort
	val VALUE_MODEM = 0x0303.toShort

	val INDEX_BAUD_A = 0x0201.toShort
	val INDEX_BAUD_B = 0x0202.toShort
	val VALUE_BAUD =  0xC068.toShort
	val VALUE_MODEM_BITMODE = 0x00FF.toShort
	val INTERFACE_A = 0x0.toByte
	val INTERFACE_B = 0x1.toByte
	val INTERFACE_A_IN_EP =  0x02.toByte
	val INTERFACE_A_OUT_EP = 0x81.toByte
	val INTERFACE_B_IN_EP =  0x04.toByte
	val INTERFACE_B_OUT_EP = 0x83.toByte

	val VALUE_RESET = 0.toShort
	val VALUE_PURGE_RX = 1.toShort
	val VALUE_PURGE_TX = 2.toShort
	val VALUE_LATENCY = 1.toShort

	val SIO_SET_DTR_MASK = 0x01.toByte
	val SIO_SET_RTS_MASK = 0x02.toByte
	val SIO_SET_DTR_HIGH = ( 1 | ( SIO_SET_DTR_MASK << 8)).toShort
	val SIO_SET_DTR_LOW  = ( 0 | ( SIO_SET_DTR_MASK << 8)).toShort
	val SIO_SET_RTS_HIGH = ( 2 | ( SIO_SET_RTS_MASK << 8)).toShort
	val SIO_SET_RTS_LOW  = ( 0 | ( SIO_SET_RTS_MASK << 8)).toShort
	val SIO_RESET_REQUEST = 0.toByte
	val SIO_SET_LATENCY_TIMER_REQUEST = 0x09.toByte
	val SIO_SET_EVENT_CHAR_REQUEST    = 0x06.toByte
	val SIO_SET_ERROR_CHAR_REQUEST    = 0x07.toByte
	val SIO_SET_BITMODE_REQUEST       = 0x0B.toByte
	val SIO_RESET_PURGE_RX            = 1.toByte
	val SIO_RESET_PURGE_TX            = 2.toByte
	val SIO_RESET_SIO                 = 0.toByte
	val SIO_POLL_MODEM_STATUS_REQUEST = 0x05.toByte
	val SIO_SET_MODEM_CTRL_REQUEST    = 0x01.toByte

	val BITMODE_RESET = 0x00.toByte
	val BITMODE_MPSSE = 0x02.toByte

	val MODEM_CTS = (1 << 4)

	//MPSSE commands from FTDI AN_108
	val INVALID_COMMAND           = 0xAB.toByte
	val ENABLE_ADAPTIVE_CLOCK     = 0x96.toByte
	val DISABLE_ADAPTIVE_CLOCK    = 0x97.toByte
	val ENABLE_3_PHASE_CLOCK      = 0x8C.toByte
	val DISABLE_3_PHASE_CLOCK     = 0x8D.toByte
	val TCK_X5                    = 0x8A.toByte
	val TCK_D5                    = 0x8B.toByte
	val CLOCK_N_CYCLES            = 0x8E.toByte
	val CLOCK_N8_CYCLES           = 0x8F.toByte
	val PULSE_CLOCK_IO_HIGH       = 0x94.toByte
	val PULSE_CLOCK_IO_LOW        = 0x95.toByte
	val CLOCK_N8_CYCLES_IO_HIGH   = 0x9C.toByte
	val CLOCK_N8_CYCLES_IO_LOW    = 0x9D.toByte
	val TRISTATE_IO               = 0x9E.toByte
	val TCK_DIVISOR               = 0x86.toByte
	val LOOPBACK_END              = 0x85.toByte
	val SET_OUT_ADBUS             = 0x80.toByte
	val SET_OUT_ACBUS             = 0x82.toByte
	val WRITE_BYTES_SPI0          = 0x11.toByte
	val READ_WRITE_BYTES_SPI0     = 0x31.toByte

	val vendor = 0x0403.toShort
}

case object PL2303 {
	val CTRL_DTR: Byte = 0x01
	val CTRL_RTS: Byte = 0x02

	val CTRL_OUT: Byte = 0x21
	val VENDOR_OUT: Byte = 0x40

	val REQUEST_CTRL: Byte = 0x22
	val REQUEST_LINE: Byte = 0x20
	val REQUEST_VENDOR: Byte = 0x01

	val REPLY_CTRL: Byte = 0x21

	val VALUE_CTRL = (CTRL_DTR | CTRL_RTS).toShort
	val VALUE_LINE = 0.toShort
	val VALUE_LINE0 = 0x0001c200
	val VALUE_LINE1 = 0x080000
	val VALUE_LINE_SIZE = 7
	val VALUE_VENDOR = 0.toShort
}

trait MCP2210Actor extends Actor with UsbDeviceActor with BufferedReader {
	import MCP2210._

	def isFTDI = false

	lazy val intf = identity.interfaces.filter(_.interface == 0).head

	var pinDesignation = IndexedSeq[Byte]()
	var pinValue = IndexedSeq[Byte]()
	var pinDirection = IndexedSeq[Byte]()
	var settingsBytes = IndexedSeq[Byte]()
	var spiSettings: Settings = Settings(0, 0, 0, 0, 0, 0, 0, 0)

	def newEmptyBuf = Array.fill(BUFFER_LENGTH)(0.toByte)
	def newEmptyBuf(cmd: Byte) = {
		val arr = Array.fill(BUFFER_LENGTH)(0.toByte)
		arr(0) = cmd
		arr
	}

	def sendCmd(cmd: Byte) {
		deviceRef ! Usb.SendBulkTransfer(intf,
			newEmptyBuf(cmd).toSeq, cmd)
		startRead()
	}

	def startRead() = bufferRead(intf)

	def getPins() = sendCmd(GET_GPIO_SETTING)

	def cancelSPI() = sendCmd(SPI_CANCEL)

	def getSPISettings() = sendCmd(GET_SPI_SETTING)

	def getPinVals() = sendCmd(GET_GPIO_PIN_VAL)

	def setPins() {
		//getPins()
		sendSetPins()

		startRead()
	}

	def sendSetPins() {
		require(settingsBytes.length == 64, "Havent received pin settings yet!")

		val dat = newEmptyBuf

		dat(0) = SET_GPIO_SETTING
		dat(17) = settingsBytes(17)
		for(i <- 0 until 8) {
			dat(4 + i) = pinDesignation(i)
			dat(13) = (dat(13) | (pinValue(i) << i)).toByte
			dat(15) = (dat(15) | (pinDirection(i) << i)).toByte
		}
		dat(12) = pinDesignation(8)
		dat(14) = pinValue(8)
		dat(16) = pinDirection(8)

		log.debug("Set pin values " + pinValue)
		//log.info("Sending " + dat.toHex)
		deviceRef ! Usb.SendBulkTransfer(intf, dat.toSeq, SET_GPIO_SETTING)

		startRead()
	}

	//TODO: needs to watch for status 0x20
	def spiSend(sendData: Seq[Byte])(after: Seq[Byte] => Unit) {
		val isReset = if(sendData.length != spiSettings.bpst && !sendData.isEmpty) {
			//log.info("Setting new transfer length " + sendData.length)
			setSettings(spiSettings.copy(bpst = sendData.length))
			true
		} else false

		//log.info("Sending " + sendData.toHex)

		val out = ByteString(SPI_TRANSFER, sendData.length.toByte, 0, 0) ++
				sendData ++ Array.fill(BUFFER_LENGTH - sendData.length - 4)(0.toByte)

		require(out.length == BUFFER_LENGTH)

		//delay sending until transfer length set
		if(!isReset)
			deviceRef ! Usb.SendBulkTransfer(intf, out.toSeq, SPI_TRANSFER)

		var buffer: Seq[Byte] = Nil

		context.become(({
			case Command(SET_SPI_SETTING, _) if isReset =>
				deviceRef ! Usb.SendBulkTransfer(intf, out.toSeq, SPI_TRANSFER)
			case Command(SPI_TRANSFER, dat) if dat.length >= 2 &&
					dat(1) == SPI_TRANSFER_SUCCESS =>
				val length = dat(2)
				val status = dat(3)

				//log.info(s"Transfer completed with $status, length $length ")

				buffer ++= dat.slice(4, 4 + length)

				val realDat = buffer

				//log.info("transfer dat " + realDat.toHex)

				if(status == 0x30)
					sys.error("SPI expecting more data inappropriately")

				if(/*length != sendData.length*/status == 0x20) {
					//log.info("spi transfer waiting....")
					//context.unbecome()
					//unstashAll()
					//spiSend(Nil/*endData drop length*/, realDat)(after)
					sendCmd(SPI_TRANSFER)
				}

				if(status == 0x10) {
					//log.debug("spi transfer good.")
					context.unbecome()
					unstashAll()
					after(realDat)
					//TODO: fix this and add a sum
					//require(realDat.length == dat.length, "Lost some SPI data")
				}
		}: Receive) orElse mcpReceive orElse {
			case NonTerminated(_) => stash()
		}, false)
	}

	def setSettings(settings: Settings) {
		spiSettings = settings
		setSettings()
	}

	def setSettings() {
		val settings = spiSettings

		import settings._

		val buf = newEmptyBuf(SET_SPI_SETTING)

		implicit def toByte(x: Int) = x.toByte
		implicit def toByte2(x: Long) = x.toByte

		buf(4) = bitrate & 0xffL
		buf(5) = (bitrate & 0xff00L) >> 8
		buf(6) = (bitrate & 0xff0000L) >> 16
		buf(7) = (bitrate & 0xff000000L) >> 24

		buf(8) = icsv & 0xff
		buf(9) = (icsv & 0x100) >> 8

		buf(10) = acsv & 0xff
		buf(11) = (acsv & 0x100) >> 8

		buf(12) = cstdd & 0xff
		buf(13) = (cstdd & 0xff00) >> 8

		buf(14) = ldbtcsd & 0xff
		buf(15) = (ldbtcsd & 0xff00) >> 8

		buf(16) = dbsdb & 0xff
		buf(17) = (dbsdb & 0xff00) >> 8

		buf(18) = bpst & 0xff
		buf(19) = (bpst & 0xff00) >> 8

		buf(20) = spiMode

		deviceRef ! Usb.SendBulkTransfer(intf, buf.toSeq, SET_SPI_SETTING)

		//log.info("Set settings " + buf.toHex)
	}

	def mcpReceive: Receive = usbBufferReceive orElse {
		case BufferedReader.BufferUpdated(`intf`) =>
			val buf = interfaceReadBuffer(intf)

			if(buf.length >= 64) {
				val dat = buf take 64
				dropBuffer(intf, 64)

				self ! Command(dat(0), dat)
			}

		case Command(GET_GPIO_PIN_VAL, dat0) =>
			//log.info("pin val " + dat0)
			val dat = dat0.toIndexedSeq

			for(i <- 0 until 8) {
				val v = if((dat(4) & (1 << i)) != 0) 1 else 0
				pinValue = pinValue.updated(i, v.toByte)
			}
			pinValue = pinValue.updated(8, (dat(5) & 1).toByte)

			log.debug("Pin values " + pinValue)

			self ! GotPins

		case Command(GET_SPI_SETTING, dat0) =>
			val buf = dat0.toIndexedSeq

			spiSettings = Settings(
				bitrate = buf(7) << 24 | buf(6) << 16 | buf(5) << 8 | buf(4),
				icsv = (buf(9) & 0x1) << 8 | buf(8),
				acsv = (buf(11) & 0x1) << 8 | buf(10),
				cstdd = buf(13) << 8 | buf(12),
				ldbtcsd = buf(15) << 8 | buf(14),
				dbsdb = buf(17) << 8 | buf(16),
				bpst = buf(19) << 8 | buf(18),
				spiMode = buf(20)
			)

			log.debug("SPI Settings " + spiSettings)
			self ! GotSettings

		case Command(SPI_TRANSFER, dat) if dat.length >= 2 &&
				dat(1) == SPI_TRANSFER_ERROR_IP =>
			//TODO: this should do a retry and not just a flat out fail
			sys.error("SPI transfer error in progress")
		case Command(SPI_TRANSFER, dat) if dat.length >= 2 &&
				dat(1) == SPI_TRANSFER_ERROR_NA =>
			sys.error("External owner error on mcp2210 spi transfer")

		case Command(SPI_TRANSFER, dat) =>
			log.warning("unhaled SPI transfer response " + dat)

		case Command(GET_GPIO_SETTING, dat0) =>
			log.debug("Got pins")
			val dat = dat0.toIndexedSeq

			require(dat.length == 64)

			settingsBytes = dat
			//log.info("buf17 = " + dat(17))

			val designationB = for(i <- 0 until 8) yield dat(4 + i)
			val valueB = for(i <- 0 until 8)
				yield (if((dat(13) & (1 << i)) != 0) 1 else 0).toByte
			val directionB = for(i <- 0 until 8)
				yield (if((dat(15) & (1 << i)) != 0) 1 else 0).toByte

			pinDesignation = designationB :+ dat(12)
			pinValue = valueB :+ (dat(14) & 1).toByte
			pinDirection = directionB :+ (dat(16) & 1).toByte

			require(pinDesignation.length == 9)

			//log.info("Pin values " + pinValue)

			self ! GotPins
	}
}

object MCP2210 {
	sealed trait Message

	case class Command(id: Byte, dat: Seq[Byte]) extends Message {
		require(dat.length == BUFFER_LENGTH)
	}

	case object GotPins extends Message
	case object GotSettings extends Message

	case class Pin(pin: Seq[Byte]) { //GPIO
		require(pin.length == 9)
	}
	case class Settings(bitrate: Int,
			/** Idle Chip Select Value – 16-bit value (low byte):
				• MSB – – – – – – LSB
				CS7 CS6 CS5 CS4 CS3 CS2 CS1 CS0 */
			icsv: Int,
			/** Active Chip Select Value */
			acsv: Int,
			/** Chip Select to Data Delay (quanta of 100 µs) – 16-bit value (low byte) */
			cstdd: Int,
			/** Last Data Byte to CS (de-asserted) Delay (quanta of 100 µs) – 16-bit value (low byte) */
			ldbtcsd: Int,
			/** Delay Between Subsequent Data Bytes */
			dbsdb: Int,
			/** Bytes to Transfer per SPI Transaction */
			bpst: Int,
			spiMode: Int)

	val BUFFER_LENGTH = 64
	val TRANSFER_MAX = 60

	val PIN_GPIO = 0x0.toByte
	val PIN_CS  = 0x1.toByte
	val PIN_DEDICATED = 0x2.toByte

	val GPIO_PIN_LOW = 0.toByte
	val GPIO_PIN_HIGH = 1.toByte

	val GPIO_OUTPUT = 0.toByte
	val GPIO_INPUT = 1.toByte

	val SPI_CANCEL = 0x11.toByte
	val GET_GPIO_SETTING = 0x20.toByte
	val SET_GPIO_SETTING = 0x21.toByte
	val SET_GPIO_PIN_VAL = 0x30.toByte
	val GET_GPIO_PIN_VAL = 0x31.toByte
	val SET_GPIO_PIN_DIR = 0x32.toByte
	val GET_GPIO_PIN_DIR = 0x33.toByte
	val SET_SPI_SETTING = 0x40.toByte
	val GET_SPI_SETTING = 0x41.toByte
	val SPI_TRANSFER = 0x42.toByte

	val SPI_TRANSFER_SUCCESS = 0x00
	val SPI_TRANSFER_ERROR_NA = 0xF7 // SPI not available due to external owner
	val SPI_TRANSFER_ERROR_IP = 0xF8 // SPI not available due to transfer in progress
}