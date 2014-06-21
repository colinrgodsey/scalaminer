/*
 * io.Usb
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

package com.colingodsey.io.usb

import akka.actor._
import scala.collection.mutable
import java.nio.ByteBuffer
import scala.concurrent.duration._
import com.colingodsey.scalaminer.utils._
import akka.util.ByteString
import com.colingodsey.scalaminer.metrics.MinerMetrics
import scala.annotation.tailrec

object BufferedReader {
	sealed trait Command

	case class BufferUpdated(inf: Usb.Interface) extends Command
}

//either we will do a write -> read, or a write and read on the side
trait BufferedReader extends Actor with ActorLogging{
	import Usb._
	import BufferedReader._

	def deviceRef: ActorRef
	def readDelay: FiniteDuration
	/** this must be set correctly for FTDI or youll get status codes everywhere */
	def readSize: Int
	def isFTDI: Boolean

	def autoRead = true
	//TOD: find some static area for these i guess
	def autoReadBulkId: Int = -99999

	var readingInterface = Set.empty[Interface]

	var interfaceReadBuffers = Map[Interface, ByteString]()

	private implicit def ec = context.system.dispatcher

	def interfaceReadBuffer(x: Interface) =
		interfaceReadBuffers.getOrElse(x, ByteString.empty)

	def dropBuffer(interface: Interface): Int = {
		val preBuf = interfaceReadBuffer(interface)
		dropBuffer(interface, preBuf.length)
	}

	def dropBuffer(interface: Interface, len: Int): Int = if(len > 0) {
		val preBuf = interfaceReadBuffer(interface)
		val dropped = math.min(len, preBuf.length)
		interfaceReadBuffers += interface -> preBuf.drop(dropped)

		if(dropped > 0) self ! BufferUpdated(interface)

		dropped
	} else 0

	def bufferRead(interface: Interface): Unit = if(!readingInterface(interface)) {
		readingInterface += interface

		log.info("Starting read..")

		self ! MinerMetrics.DevicePoll

		context.system.scheduler.scheduleOnce(readDelay, deviceRef,
			ReceiveBulkTransfer(interface, readSize, autoReadBulkId))
	}

	@tailrec final def trimFTDIData(dat: Seq[Byte],
			acc: ByteString = ByteString.empty): ByteString = {
		if(dat.length < readSize) acc ++ dat.drop(2)
		else trimFTDIData(dat drop readSize, acc ++ dat.slice(2, readSize))
	}

	def usbBufferReceive: Receive = {
		//untagged transfers only
		case BulkTransferResponse(interface, Right(dat0), bulkId) if bulkId == autoReadBulkId =>
			//log.info("Received " + dat0.length)
			val buf = interfaceReadBuffer(interface)

			//val dat = if(isFTDI) trimFTDIData(dat0.view) else dat0
			val dat = if(isFTDI) dat0.view.drop(2) else dat0.seq

			if(!dat.isEmpty) log.debug("Buffering " + dat.length)
			log.info("Buffering " + dat.length)

			interfaceReadBuffers += interface -> (buf ++ dat)
			readingInterface -= interface

			if(!dat.isEmpty || !autoRead) self ! BufferUpdated(interface)

			if(autoRead) bufferRead(interface)
	}
}
