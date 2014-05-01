package com.colingodsey.usb

import akka.actor._
import com.colingodsey.scalaminer.utils._
import org.usb4java.LibUsb
import javax.usb.UsbConst
import com.typesafe.config.Config
import akka.io.SelectionHandlerSettings

object Usb extends ExtensionId[UsbExt] with ExtensionIdProvider {
	case class ControlIrp(requestType: Byte, request: Byte,
			value: Short, index: Short) {
		def send = SendControlIrp(this)
	}
	case class DeviceId(bus: Int, address: Int, port: Int, desc: DeviceDescriptor) {
		lazy val idKey = Seq(bus, address, desc.vendor.toInt,
			desc.product.toInt).map(intToBytes(_).drop(2).toHex).mkString(".")

		override def toString = s"DeviceId($idKey)"
	}
	//case class Device(id: DeviceId, desc: Usb.DeviceDescriptor)
	case class DeviceDescriptor(usbVer: Double, deviceClass: Byte,
			deviceSubClass: Byte, deviceProtocol: Byte, maxPacketSize: Byte,
			vendor: Short, product: Short, deviceVer: Double, numConfigurations: Byte) {
		def isHub = deviceClass == LibUsb.CLASS_HUB
	}

	sealed trait Command
	sealed trait Response
	sealed trait IrpRequest extends Command {
		def interface: Interface
		def endpoint: Endpoint
	}
	sealed trait BulkIrpRequest extends IrpRequest
	sealed trait ControlIrpRequest extends IrpRequest

	//host commands
	sealed trait HostCommand extends Command
	sealed trait HostResponse extends Response

	case class DeviceDisconnected(deviceId: DeviceId) extends HostResponse
	case class DeviceConnected(deviceId: DeviceId) extends HostResponse

	case object Subscribe extends Command
	case object UnSubscribe extends Command

	case class RefFor(deviceId: Usb.DeviceId) extends HostCommand
	case class DeviceRef(deviceId: Usb.DeviceId, ref: Option[ActorRef]) extends HostResponse

	case class ReceiveControlIrp(irp: ControlIrp, length: Int) extends ControlIrpRequest {
		def interface = ControlInterface
		def endpoint = ControlEndpoint
	}
	case class SendControlIrp(irp: ControlIrp, dat: Seq[Byte] = Nil) extends ControlIrpRequest {
		def interface = ControlInterface
		def endpoint = ControlEndpoint
	}

	case class ControlIrpResponse(irp: ControlIrp,
			datOrLength: Either[Int, Seq[Byte]]) extends Response

	case class ReceiveBulkTransfer(interface: Interface, length: Int, id: Int = -1) extends BulkIrpRequest {
		def endpoint = interface.input
	}
	case class SendBulkTransfer(interface: Interface, dat: Seq[Byte], id: Int = -1) extends BulkIrpRequest {
		def endpoint = interface.output
	}

	case class BulkTransferResponse(interface: Interface,
			datOrLength: Either[Int, Seq[Byte]], id: Int) extends Response

	object Interface {
		def apply(interface: Short, endpoints: Set[Endpoint]): Interface =
			Interface(interface, endpoints, interface)
	}

	case class Interface(interface: Short, endpoints: Set[Endpoint], ctrlInterface: Int) {
		lazy val input = endpoints.filter(_.isInput).head
		lazy val output = endpoints.filter(_.isOutput).head
	}

	case object ControlEndpoint extends Endpoint {
		def att: Byte = 0
		def size: Short = 0
		def ep: Byte = 0
		def maxPacketSize: Short = 0
		def isInput: Boolean = true
		def isOutput: Boolean = true
	}

	lazy val ControlInterface = Interface(-1, Set(ControlEndpoint), -1)

	case class InputEndpoint(att: Byte, size: Short, inputNum: Byte,
			maxPacketSize: Short) extends Endpoint {
		val ep = (UsbConst.ENDPOINT_DIRECTION_IN | inputNum).toByte
		def isInput: Boolean = true
		def isOutput: Boolean = false
	}

	case class OutputEndpoint(att: Byte, size: Short, outputNum: Byte,
			maxPacketSize: Short) extends Endpoint {
		val ep = (UsbConst.ENDPOINT_DIRECTION_OUT | outputNum).toByte
		def isInput: Boolean = false
		def isOutput: Boolean = true
	}

	trait Endpoint extends Equals {
		def att: Byte
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
	class Settings private[UsbExt] (_config: Config) extends SelectionHandlerSettings(_config) {
		//import akka.util.Helpers.ConfigOps
		import _config._

		val NrOfSelectors = 1

		override def MaxChannelsPerSelector: Int = 1
	}

	val manager = system.actorOf(Props[UsbHost], name = "IO-Usb")
	def getManager: ActorRef = manager
}