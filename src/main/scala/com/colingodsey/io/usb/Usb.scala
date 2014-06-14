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
import com.colingodsey.scalaminer.utils._
import scala.collection.JavaConversions._
import org.usb4java.LibUsb
import com.typesafe.config.Config
import akka.io.SelectionHandlerSettings
import scala.concurrent.duration.FiniteDuration

object Usb extends ExtensionId[UsbExt] with ExtensionIdProvider {
	case class ControlIrp(requestType: Byte, request: Byte,
			value: Short, index: Short) {
		def send = SendControlIrp(this)
	}
	case class DeviceId(bus: Int, address: Int, port: Int,
			desc: DeviceDescriptor, hostRef: ActorRef) {
		lazy val idKey = Seq(bus, address, desc.vendor.toInt,
			desc.product.toInt).map(intToBytes(_).drop(2).toHex).mkString("-")

		lazy val portId = Seq(bus, address, port).map(intToBytes(_).drop(3).toHex).mkString("-")

		override def toString = s"DeviceId($idKey)"
	}

	case class DeviceDescriptor(usbVer: Double, deviceClass: Byte,
			deviceSubClass: Byte, deviceProtocol: Byte, maxPacketSize: Byte,
			vendor: Short, product: Short, deviceVer: Double, numConfigurations: Byte) {
		def isHub = deviceClass == LibUsb.CLASS_HUB
	}

	sealed trait Message
	sealed trait Response extends Message
	sealed trait Request extends Message

	//host commands
	sealed trait HostRequest extends Request
	sealed trait HostResponse extends Response

	case class DeviceDisconnected(deviceId: DeviceId) extends HostResponse
	case class DeviceConnected(deviceId: DeviceId) extends HostResponse

	//TODO: add GetDevices
	case object Subscribe extends HostRequest
	case object UnSubscribe extends HostRequest

	case object Close extends Request
	case class Connect(deviceId: Usb.DeviceId) extends HostRequest
	case class Connected(deviceId: Usb.DeviceId, ref: Option[ActorRef]) extends HostResponse

	//device commands
	sealed trait DeviceRequest extends Request
	sealed trait DeviceResponse extends Response
	sealed trait IrpRequest extends DeviceRequest {
		def interface: Interface
		def endpoint: Endpoint
		def isRead: Boolean
	}
	sealed trait BulkIrpRequest extends IrpRequest {
		def id: Int
	}
	sealed trait ControlIrpRequest extends IrpRequest {
		def irp: ControlIrp
	}

	case class SetConfiguration(config: Int) extends DeviceRequest
	case class SetTimeout(timeout: FiniteDuration) extends DeviceRequest

	case class ReceiveControlIrp(irp: ControlIrp, length: Int) extends ControlIrpRequest {
		def interface = ControlInterface
		def endpoint = ControlEndpoint
		def isRead = true
	}
	case class SendControlIrp(irp: ControlIrp, dat: SerializableByteSeq = Nil) extends ControlIrpRequest {
		def interface = ControlInterface
		def endpoint = ControlEndpoint
		def isRead = false
	}

	case class ControlIrpResponse(irp: ControlIrp,
			datOrLength: Either[Int, SerializableByteSeq]) extends DeviceResponse

	case class ReceiveBulkTransfer(interface: Interface,
			length: Int, id: Int = -1) extends BulkIrpRequest {
		def endpoint = interface.input
		def isRead = true
	}
	case class SendBulkTransfer(interface: Interface, dat: SerializableByteSeq,
			id: Int = -1) extends BulkIrpRequest {
		def endpoint = interface.output
		def isRead = false
	}

	case class BulkTransferResponse(interface: Interface,
			datOrLength: Either[Int, SerializableByteSeq], id: Int) extends DeviceResponse

	//exceptions
	sealed trait Error extends Exception {
		override def fillInStackTrace() = this
	}

	case class IrpTimeout(irp: IrpRequest)
			extends Exception("IRP timed out: " + irp) with Error
	case class IrpError(irp: IrpRequest)
			extends Exception("IRP failed " + irp) with Error
	case class IrpCancelled(irp: IrpRequest)
			extends Exception("IRP cancelled " + irp) with Error
	case class IrpStall(irp: IrpRequest)
			extends Exception("IRP stalled " + irp) with Error
	case object IrpNoDevice
			extends Exception("IRP no device found!") with Error
	case class IrpOverflow(irp: IrpRequest)
			extends Exception("IRP transfer overflowed " + irp) with Error

	//structures

	object Interface {
		def apply(interface: Short, endpoints: Set[Endpoint]): Interface =
			Interface(interface, endpoints, interface)
	}

	case class Interface(interface: Short, endpoints: Set[Endpoint], ctrlInterface: Int) {
		lazy val input = endpoints.filter(_.isInput).head
		lazy val output = endpoints.filter(_.isOutput).head
	}

	case object ControlEndpoint extends Endpoint {
		//def att: Byte = 0
		def size: Short = 0
		def ep: Byte = 0
		def maxPacketSize: Short = 0
		def isInput: Boolean = true
		def isOutput: Boolean = true
	}

	lazy val ControlInterface = Interface(-1, Set(ControlEndpoint), -1)

	case class InputEndpoint(/*att: Byte, */size: Short, inputNum: Byte,
			maxPacketSize: Short) extends Endpoint {
		val ep = (LibUsb.ENDPOINT_IN | inputNum).toByte
		def isInput: Boolean = true
		def isOutput: Boolean = false
	}

	case class OutputEndpoint(/*att: Byte, */size: Short, outputNum: Byte,
			maxPacketSize: Short) extends Endpoint {
		val ep = (LibUsb.ENDPOINT_OUT | outputNum).toByte
		def isInput: Boolean = false
		def isOutput: Boolean = true
	}

	trait Endpoint extends Equals {
		//def att: Byte
		def size: Short
		def ep: Byte
		def maxPacketSize: Short
		def isInput: Boolean
		def isOutput: Boolean

		override def hashCode = ep.hashCode()
		override def canEqual(that: Any) = that match {
			case x: Endpoint => true
			case _ => false
		}
		override def equals(that: Any) = that match {
			case x: Endpoint => x.ep == ep
			case _ => false
		}
	}

	def createExtension(system: ExtendedActorSystem): UsbExt = new UsbExt(system)

	def lookup(): ExtensionId[_ <: Extension] = Usb
}

class UsbExt(system: ExtendedActorSystem) extends akka.io.IO.Extension {
	val Settings = new Settings(system.settings.config.getConfig("com.colingodsey.usb"))
	class Settings private[UsbExt] (_config: Config) {
		//import akka.util.Helpers.ConfigOps
		import _config._

		val isVirtual = getBoolean("virtual-host")
		val virtualHosts = getStringList("virtual-hosts").toSeq

		val NrOfSelectors = 1

		def MaxChannelsPerSelector: Int = 1
	}

	val manager = if(!Settings.isVirtual)
		system.actorOf(Props(classOf[UsbHost], UsbExt.this), name = "IO-Usb")
	else system.actorOf(Props(classOf[VirtualUsbHost], UsbExt.this), name = "IO-Usb")
	def getManager: ActorRef = manager

}