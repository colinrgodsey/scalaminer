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
import com.colingodsey.io.usb.Usb

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
	val SIO_SET_BITMODE_REQUEST = 0x0B.toByte
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

	val SIO_POLL_MODEM_STATUS_REQUEST = 0x05.toByte
	val SIO_SET_MODEM_CTRL_REQUEST = 0x01.toByte
	val MODEM_CTS = (1 << 4)

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

trait MCP2210Actor extends Actor with UsbDeviceActor {
	import MCP2210._

	def isFTDI = false

	lazy val intf = identity.interfaces.filter(_.interface == 0).head

	var pinDesignation = IndexedSeq[Byte]()
	var pinValue = IndexedSeq[Byte]()
	var pinDirection = IndexedSeq[Byte]()
	var settingsBytes = IndexedSeq[Byte]()

	def newEmptyBuf = Array.fill(BUFFER_LENGTH)(0.toByte)

	def getPins() {
		val sendBuf = newEmptyBuf
		sendBuf(0) = GET_GPIO_SETTING

		deviceRef ! Usb.SendBulkTransfer(intf, sendBuf.toSeq, GET_GPIO_SETTING)
	}

	def setPins() {
		require(settingsBytes.length == 64, "Havent received pin settings yet!")

		val newBytes = newEmptyBuf

		newBytes(0) = SET_GPIO_SETTING
		newBytes(17) = settingsBytes(17)
		for(i <- 0 until 8) {
			newBytes(4 + i) = pinDesignation(i)
			newBytes(13) = (newBytes(13) | (pinValue(i) << i)).toByte
			newBytes(15) = (newBytes(13) | (pinDirection(i) << i)).toByte
		}
		newBytes(12) = pinDesignation(8)
		newBytes(14) = pinValue(8)
		newBytes(16) = pinDirection(8)

		deviceRef ! Usb.SendBulkTransfer(intf, newBytes.toSeq, GET_GPIO_SETTING)
	}

	def mcpReceive: Receive = {
		case Usb.BulkTransferResponse(`intf`, Right(dat0), GET_GPIO_SETTING) =>
			val dat = dat0.toIndexedSeq

			require(dat.length == 64)

			settingsBytes = dat

			val designationB = for(i <- 0 until 8) yield dat(4 + i)
			val valueB = for(i <- 0 until 8)
				yield (if((dat(13) & (1 << i)) != 0) 1 else 0).toByte
			val directionB = for(i <- 0 until 8)
				yield (if((dat(15) & (1 << i)) != 0) 1 else 0).toByte

			pinDesignation = designationB :+ dat(12)
			pinValue = valueB :+ (dat(14) & 1).toByte
			pinDirection = directionB :+ (dat(16) & 1).toByte

			require(pinDesignation.length == 9)
	}
}

object MCP2210 {
	/*
	struct mcp_settings {
	struct gpio_pin designation;
	struct gpio_pin value;
	struct gpio_pin direction;
	unsigned int bitrate, icsv, acsv, cstdd, ldbtcsd, sdbd, bpst, spimode;
	};
	 */

	/*
	char buf[MCP2210_BUFFER_LENGTH];
	int i;

	memset(buf, 0, MCP2210_BUFFER_LENGTH);
	buf[0] = MCP2210_GET_GPIO_SETTING;
	if (!mcp2210_send_recv(cgpu, buf, C_MCP_GETGPIOSETTING))
		return false;

	for (i = 0; i < 8; i++) {
		mcp->designation.pin[i] = buf[4 + i];
		mcp->value.pin[i] = !!(buf[13] & (0x01u << i));
		mcp->direction.pin[i] = !!(buf[15] & (0x01u << i));
	}
	mcp->designation.pin[8] = buf[12];
	mcp->value.pin[8] = buf[14] & 0x01u;
	mcp->direction.pin[8] = buf[16] & 0x01u;

	return true;
	 */



	case class Pin(pin: Seq[Byte]) { //GPIO
		require(pin.length == 9)
	}
	case class Settings(designation: Pin, value: Pin, direction: Pin,
			bitrate: Int, icsv: Int, acsv: Int, cstdd: Int, ldbtscsd: Int,
			bpst: Int, spMode: Int)

	val BUFFER_LENGTH = 64
	val TRANSFER_MAX = 60

	val PIN_GPIO = 0x0
	val PIN_CS  = 0x1
	val PIN_DEDICATED = 0x2

	val GPIO_PIN_LOW = 0
	val GPIO_PIN_HIGH = 1

	val GPIO_OUTPUT = 0
	val GPIO_INPUT = 1

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