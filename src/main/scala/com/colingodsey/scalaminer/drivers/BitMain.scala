/*
 * scalaminer
 * ----------
 * https://github.com/colinrgodsey/scalaminer
 *
 * Copyright (c) 2014 Colin R Godsey <colingodsey.com>
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
import com.colingodsey.scalaminer.ScalaMiner
import akka.actor._
import scala.concurrent.duration._
import com.colingodsey.scalaminer.utils.CRC16
import akka.util.ByteString
import com.colingodsey.scalaminer.metrics.MetricsWorker
import com.colingodsey.scalaminer.ScalaMiner.HashType

class BitMain(val deviceId: Usb.DeviceId,
		val workRefs: Map[ScalaMiner.HashType, ActorRef])
		extends UsbDeviceActor with AbstractMiner
		with MetricsWorker with BufferedReader  {
	import BitMain._

	def readDelay = 75.millis
	def readSize = 8192
	def nonceTimeout: FiniteDuration = 4.seconds
	def hashType: HashType = ScalaMiner.SHA256
	def isFTDI: Boolean = false

	def identity = BitMain.ANTS1

	lazy val intf = identity.interfaces.filter(_.interface == 0).head

	def detect() {
		send(intf, setRxStatus(0, 1, 0, 0))
	}

	def baseReceive = usbBufferReceive orElse workReceive orElse metricsReceive

	def receive: Receive = baseReceive orElse {
		case BufferedReader.BufferUpdated(`intf`) =>
			val buf = interfaceReadBuffer(intf)
			if(buf.length > 0) log.debug("Buffer updated with len " + buf.length)

			if(buf.length > 0) log.info(buf.toString)
	}

	override def preStart() {
		super.preStart()

		getDevice {
			detect()
		}
	}
}

object BitMain extends USBDeviceDriver {
	def hashType = ScalaMiner.SHA256

	lazy val identities: Set[USBIdentity] = Set(ANTS1)

	case object ANTS1 extends USBIdentity {
		import UsbDeviceManager._

		def drv = BitMain
		def idVendor = 0x4254
		def idProduct = 0x4153
		def iManufacturer = ""
		def iProduct = ""
		def config = 1
		def timeout = 100.millis

		def isMultiCoin = true

		val interfaces = Set(
			Usb.Interface(0, Set(
				Usb.InputEndpoint(64, 1, 0),
				Usb.OutputEndpoint(64, 1, 0)
			))
		)

		override def usbDeviceActorProps(device: Usb.DeviceId,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			Props(classOf[BitMain], device, workRefs)
	}

	private val bitSwapTable = Array[Int](
		0x00, 0x80, 0x40, 0xc0, 0x20, 0xa0, 0x60, 0xe0,
		0x10, 0x90, 0x50, 0xd0, 0x30, 0xb0, 0x70, 0xf0,
		0x08, 0x88, 0x48, 0xc8, 0x28, 0xa8, 0x68, 0xe8,
		0x18, 0x98, 0x58, 0xd8, 0x38, 0xb8, 0x78, 0xf8,
		0x04, 0x84, 0x44, 0xc4, 0x24, 0xa4, 0x64, 0xe4,
		0x14, 0x94, 0x54, 0xd4, 0x34, 0xb4, 0x74, 0xf4,
		0x0c, 0x8c, 0x4c, 0xcc, 0x2c, 0xac, 0x6c, 0xec,
		0x1c, 0x9c, 0x5c, 0xdc, 0x3c, 0xbc, 0x7c, 0xfc,
		0x02, 0x82, 0x42, 0xc2, 0x22, 0xa2, 0x62, 0xe2,
		0x12, 0x92, 0x52, 0xd2, 0x32, 0xb2, 0x72, 0xf2,
		0x0a, 0x8a, 0x4a, 0xca, 0x2a, 0xaa, 0x6a, 0xea,
		0x1a, 0x9a, 0x5a, 0xda, 0x3a, 0xba, 0x7a, 0xfa,
		0x06, 0x86, 0x46, 0xc6, 0x26, 0xa6, 0x66, 0xe6,
		0x16, 0x96, 0x56, 0xd6, 0x36, 0xb6, 0x76, 0xf6,
		0x0e, 0x8e, 0x4e, 0xce, 0x2e, 0xae, 0x6e, 0xee,
		0x1e, 0x9e, 0x5e, 0xde, 0x3e, 0xbe, 0x7e, 0xfe,
		0x01, 0x81, 0x41, 0xc1, 0x21, 0xa1, 0x61, 0xe1,
		0x11, 0x91, 0x51, 0xd1, 0x31, 0xb1, 0x71, 0xf1,
		0x09, 0x89, 0x49, 0xc9, 0x29, 0xa9, 0x69, 0xe9,
		0x19, 0x99, 0x59, 0xd9, 0x39, 0xb9, 0x79, 0xf9,
		0x05, 0x85, 0x45, 0xc5, 0x25, 0xa5, 0x65, 0xe5,
		0x15, 0x95, 0x55, 0xd5, 0x35, 0xb5, 0x75, 0xf5,
		0x0d, 0x8d, 0x4d, 0xcd, 0x2d, 0xad, 0x6d, 0xed,
		0x1d, 0x9d, 0x5d, 0xdd, 0x3d, 0xbd, 0x7d, 0xfd,
		0x03, 0x83, 0x43, 0xc3, 0x23, 0xa3, 0x63, 0xe3,
		0x13, 0x93, 0x53, 0xd3, 0x33, 0xb3, 0x73, 0xf3,
		0x0b, 0x8b, 0x4b, 0xcb, 0x2b, 0xab, 0x6b, 0xeb,
		0x1b, 0x9b, 0x5b, 0xdb, 0x3b, 0xbb, 0x7b, 0xfb,
		0x07, 0x87, 0x47, 0xc7, 0x27, 0xa7, 0x67, 0xe7,
		0x17, 0x97, 0x57, 0xd7, 0x37, 0xb7, 0x77, 0xf7,
		0x0f, 0x8f, 0x4f, 0xcf, 0x2f, 0xaf, 0x6f, 0xef,
		0x1f, 0x9f, 0x5f, 0xdf, 0x3f, 0xbf, 0x7f, 0xff
	).map(_.toByte)

	def bitSwap(x: Byte): Byte = bitSwapTable(x)
	
	object Constants {
		val RESET_FAULT_DECISECONDS = 1
		val MINER_THREADS = 1

		val IO_SPEED = 115200
		val HASH_TIME_FACTOR = 1.67d / 0x32
		val RESET_PITCH = (300*1000*1000)

		val TOKEN_TYPE_TXCONFIG = 0x51.toByte
		val TOKEN_TYPE_TXTASK = 0x52.toByte
		val TOKEN_TYPE_RXSTATUS = 0x53.toByte

		val DATA_TYPE_RXSTATUS = 0xa1.toByte
		val DATA_TYPE_RXNONCE = 0xa2.toByte

		val FAN_FACTOR = 60
		val PWM_MAX = 0xA0.toByte
		val DEFAULT_FAN_MIN = 20
		val DEFAULT_FAN_MAX = 100
		val DEFAULT_FAN_MAX_PWM = 0xA0.toByte //100%
		val DEFAULT_FAN_MIN_PWM = 0x20.toByte //20%

		val TEMP_TARGET = 50
		val TEMP_HYSTERESIS = 3
		val TEMP_OVERHEAT = 60

		val DEFAULT_TIMEOUT = 0x2D.toByte
		val MIN_FREQUENCY = 10
		val MAX_FREQUENCY = 1000000
		val TIMEOUT_FACTOR = 12690
		val DEFAULT_FREQUENCY = 282
		val DEFAULT_VOLTAGE = 5
		val DEFAULT_CHAIN_NUM = 8
		val DEFAULT_ASIC_NUM = 32
		val DEFAULT_REG_DATA = 0

		val AUTO_CYCLE = 1024

		val FTDI_READSIZE = 510
		val USB_PACKETSIZE = 512
		val SENDBUF_SIZE = 8192
		val READBUF_SIZE = 8192
		val RESET_TIMEOUT = 100
		val READ_TIMEOUT = 18 //enough to half fill buffer?
		val LATENCY = 1

		val MAX_WORK_NUM = 8
		val MAX_WORK_QUEUE_NUM = 64
		val MAX_DEAL_QUEUE_NUM = 1
		val MAX_NONCE_NUM = 8
		val MAX_CHAIN_NUM = 8
		val MAX_TEMP_NUM = 32
		val MAX_FAN_NUM = 32

		val SEND_STATUS_TIME = 10 //s
		val SEND_FULL_SPACE = 128

		val OVERHEAT_SLEEP_MS_MAX = 10000
		val OVERHEAT_SLEEP_MS_MIN = 200
		val OVERHEAT_SLEEP_MS_DEF = 600
		val OVERHEAT_SLEEP_MS_STEP = 200
	}

	def setRxStatus(statusEft: Byte, detectGet: Byte, chipAddr: Byte, regAddr: Byte) = {
		import Constants._

		val preDat = ByteString(
			TOKEN_TYPE_RXSTATUS,
			8, //len - 2
			bitSwap(statusEft), //idx 2
			detectGet,
			0, //reserved1
			0, //reserved2
			chipAddr,
			regAddr
		)

		val crc = CRC16(preDat)

		//take low 2 bytes for short, then reverse for little-endian
		val crcBytes = intToBytes(crc).drop(2).reverse

		val finalBytes = preDat ++ crcBytes

		require(finalBytes.length == 10)

		finalBytes
	}
}