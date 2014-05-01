package com.colingodsey.usb

import akka.actor._
import scala.collection.mutable
import java.nio.ByteBuffer
import scala.concurrent.duration._
import com.colingodsey.scalaminer.utils._
import akka.util.ByteString
import com.colingodsey.scalaminer.usb.NewUsbDeviceActor

object UsbBufferedDevice {
	sealed trait Command

	case class BufferUpdated(inf: Usb.Interface) extends Command
}

//either we will do a write -> read, or a write and read on the side
trait UsbBufferedDevice extends Actor with ActorLogging with Stash with NewUsbDeviceActor {
	import Usb._
	import UsbBufferedDevice._

	def readDelay: FiniteDuration
	def readSize: Int

	var readingInterface = Set.empty[Interface]

	var interfaceReadBuffers = Map[Interface, ByteString]()

	private implicit def ec = context.system.dispatcher

	def interfaceReadBuffer(x: Interface) =
		interfaceReadBuffers.getOrElse(x, ByteString.empty)

	def dropBuffer(interface: Interface, len: Int) = {
		val preBuf = interfaceReadBuffer(interface)
		val dropped = math.min(len, preBuf.length)
		interfaceReadBuffers += interface -> preBuf.drop(len)

		if(dropped > 0) self ! BufferUpdated(interface)

		dropped
	}

	def bufferRead(interface: Interface): Unit = if(!readingInterface(interface)) {
		readingInterface += interface

		context.system.scheduler.scheduleOnce(readDelay, deviceRef,
			ReceiveBulkTransfer(interface, readSize))
	}

	def usbBufferReceive: Receive = {
		case BulkTransferResponse(interface, Right(dat), _) =>
			val buf = interfaceReadBuffer(interface)

			interfaceReadBuffers += interface -> (buf ++ dat)
			readingInterface -= interface

			self ! BufferUpdated(interface)
	}
}
