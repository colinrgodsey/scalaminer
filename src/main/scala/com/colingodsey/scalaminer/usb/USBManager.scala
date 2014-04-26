package com.colingodsey.scalaminer.usb

import javax.usb.{UsbHostManager, UsbHub, UsbDevice, UsbConst}
import scala.concurrent.duration._
import scala.concurrent.blocking
import scala.collection.JavaConversions._
import akka.actor._
import com.colingodsey.scalaminer.utils._
import com.colingodsey.scalaminer.{MinerIdentity, ScalaMiner, MinerDriver}
import com.colingodsey.scalaminer.metrics.MinerMetrics
import com.typesafe.config.Config


object USBUtils {
	def epi(i: Byte) = (UsbConst.ENDPOINT_DIRECTION_IN | i).toByte
	def epo(i: Byte) = (UsbConst.ENDPOINT_DIRECTION_OUT | i).toByte
}

//should be a case object
trait USBDeviceDriver extends MinerDriver {
	def identities: Set[USBIdentity]

	def hashType: ScalaMiner.HashType
}

//should be a case object
trait USBIdentity extends MinerIdentity {
	def drv: USBDeviceDriver
	def idVendor: Short
	def idProduct: Short
	def iManufacturer: String
	def iProduct: String
	//def config = 1,
	def timeout: FiniteDuration

	def latency: FiniteDuration = 32.millis
	def name: String = toString

	def interfaces: Set[USBManager.Interface]

	def usbDeviceActorProps(device: UsbDevice,
			workRefs: Map[ScalaMiner.HashType, ActorRef]): Props

	def matches(device: UsbDevice) = {
		val desc = device.getUsbDeviceDescriptor

		desc.idVendor == idVendor && desc.idProduct == idProduct
	}
}

object USBManager {
	sealed trait Command

	case class AddDriver(driver: USBDeviceDriver) extends Command
	case class AddStratumRef(typ: ScalaMiner.HashType, stratumRef: ActorRef) extends Command

	case class FailedIdentify(ref: ActorRef, identity: USBIdentity) extends Command

	case object IdentityReset extends Command

	case object ScanDevices extends Command

	case class RemoveRef(ref: ActorRef) extends Command

	object Interface {
		def apply(interface: Short, endpoints: Set[Endpoint]): Interface =
			Interface(interface, endpoints, interface)
	}

	case class Interface(interface: Short, endpoints: Set[Endpoint], ctrlInterface: Int)

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

	trait Endpoint {
		def att: Byte
		def size: Short
		def ep: Byte
		def maxPacketSize: Short
		def isInput: Boolean
		def isOutput: Boolean
	}
}

class USBManager(config: Config) extends Actor with ActorLogging with Stash {
	import USBManager._

	implicit def ec = context.dispatcher

    var usbDrivers: Set[USBDeviceDriver] = Set.empty
	var workerMap: Map[UsbDevice, ActorRef] = Map.empty
	var stratumEndpoints: Map[ScalaMiner.HashType, ActorRef] = Map.empty
	var failedIdentityMap: Map[UsbDevice, Set[USBIdentity]] = Map.empty
	var metricsSubs = Set[ActorRef]()

	val scanTimer = context.system.scheduler.schedule(
		1.seconds, config getDur "poll-time", self, ScanDevices)
	val identityResetTimer = context.system.scheduler.schedule(
		1.minute, config getDur "identity-reset-time", self, IdentityReset)

	def rootHub = UsbHostManager.getUsbServices.getRootUsbHub

	def scanDevices() = blocking {
		val devices = getDevices(rootHub)

		val matches = (for {
			device <- devices
			failedSet = failedIdentityMap.getOrElse(device, Set.empty)
			if !workerMap.contains(device)
			driver <- usbDrivers
			identity <- driver.identities
			if !failedSet(identity)
			if identity matches device
			if stratumEndpoints contains driver.hashType
		} yield device -> identity).toSeq.toMap

		val refs = matches map { m =>
			val (device, identity) = m

			val props = identity.usbDeviceActorProps(device, stratumEndpoints)
			val name = identity.name + "." + device.hashCode()
			val ref = context.actorOf(props, name = name)

			context watch ref

			metricsSubs.foreach(ref.tell(MinerMetrics.Subscribe, _))

			device -> ref
		}

		workerMap ++= refs
	}
  
	def getDevices(hub: UsbHub): Stream[UsbDevice] = blocking {
		hub.getAttachedUsbDevices.toStream flatMap {
			case x: UsbHub if x.isUsbHub => getDevices(x)
			case x: UsbDevice => Some(x)
			case _ => None
		}
	}

	def findDevice(hub: UsbHub, vendorId: Short, productId: Short): Option[UsbDevice] =
		getDevices(hub).filter { device =>
			val desc = device.getUsbDeviceDescriptor
			desc.idVendor == vendorId && desc.idProduct == productId
		}.headOption


	def manualScan = UsbHostManager.getUsbServices match {
		case x: org.usb4java.javax.Services => x.scan()
		case _ => sys.error("No manual scan for javax-usb driver")
	}

	def receive = {
		case MinerMetrics.Subscribe =>
			metricsSubs += sender
			workerMap.foreach(_._2.tell(MinerMetrics.Subscribe, sender))
		case MinerMetrics.UnSubscribe =>
			metricsSubs -= sender
			workerMap.foreach(_._2.tell(MinerMetrics.UnSubscribe, sender))
		case IdentityReset => failedIdentityMap = Map.empty
		case AddStratumRef(t, ref) => stratumEndpoints += t -> ref
		case AddDriver(drv) => usbDrivers += drv
		case ScanDevices => scanDevices()
		case FailedIdentify(ref, identity) =>
			workerMap.filter(_._2 == ref).map(_._1).headOption.foreach { dev =>
				val s = failedIdentityMap.getOrElse(dev, Set.empty)
				failedIdentityMap += dev -> (s + identity)
			}

		case Terminated(x) =>
			context.system.scheduler.scheduleOnce(2.seconds, self, RemoveRef(x))
		case RemoveRef(x) =>
			val filtered = workerMap.filter(_._2 == x).keySet

			workerMap --= filtered

			log.warning(s"$x died! Removing ${filtered.size} devices.")
	}

	override def postStop() {
		super.postStop()
		scanTimer.cancel()
		identityResetTimer.cancel()
	}
}