package com.colingodsey.usb

import org.usb4java._
import akka.actor._
import scala.collection.mutable
import java.nio.ByteBuffer
import scala.concurrent.duration._
import scala.concurrent.blocking
import com.colingodsey.scalaminer.utils._
import scala.collection.JavaConversions._

object UsbHost {
	sealed trait Command

	case object UpdateDeviceList extends Command

	private[usb] class LibUsbSelector(usbContext: Context) extends Actor with ActorLogging {

		implicit def ec = context.system.dispatcher
		case object Poll

		val pollTimeout = 1.second
		val pollFreq = 2.millis

		def startPollTimer() {
			context.system.scheduler.scheduleOnce(pollFreq, self, Poll)
		}

		def receive = {
			case Poll => blocking {
				LibUsb.lockEvents(usbContext)

				LibUsb.handleEventsTimeout(usbContext, pollTimeout.toMicros)

				LibUsb.unlockEvents(usbContext)

				startPollTimer()
			}
		}

		override def preStart() {
			super.preStart()

			startPollTimer()
		}
	}
}

class UsbHost(usb: UsbExt) extends Actor with ActorLogging {
	import Usb._
	import UsbHost._

	val pinnedDispatcher = "akka.io.pinned-dispatcher"

	val deviceUpdateInterval = 1.second
	val callbackHandle = new HotplugCallbackHandle

	var usbContext: Context = null
	var detach = false
	var deviceActors = Map.empty[DeviceId, ActorRef]
	var subscribers = Set.empty[ActorRef]

	var deviceSet = Set.empty[DeviceId]

	def hasDeviceRef(ref: ActorRef) = !deviceActors.filter(_._2 == ref).isEmpty

	implicit def ec = context.system.dispatcher

	val hotplugCallback = new HotplugCallback {
		override def processEvent(context: Context, device: org.usb4java.Device,
				event: Int, userData: scala.Any): Int = {
			event match {
				case LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED =>
					self ! UpdateDeviceList
					0
				case LibUsb.HOTPLUG_EVENT_DEVICE_LEFT =>
					self ! UpdateDeviceList
					0
			}
		}
	}

	def descToDesc(descriptor: org.usb4java.DeviceDescriptor) =
		DeviceDescriptor(descriptor.bcdUSB / 100.0,
			descriptor.bDeviceClass,
			descriptor.bDeviceSubClass, descriptor.bDeviceProtocol,
			descriptor.bMaxPacketSize0, descriptor.idVendor, descriptor.idProduct,
			descriptor.bcdDevice / 100.0, descriptor.bNumConfigurations)

	def deviceToId(device: org.usb4java.Device) = {
		val descriptor = new org.usb4java.DeviceDescriptor
		//TODO: positive we dont have to free these?
		val result = LibUsb.getDeviceDescriptor(device, descriptor)

		if (result != LibUsb.SUCCESS) throw new LibUsbException(
			"Unable to read device descriptor", result)

		DeviceId(LibUsb.getBusNumber(device), LibUsb.getDeviceAddress(device),
			LibUsb.getPortNumber(device), descToDesc(descriptor))
	}

	def receive = {
		case Subscribe =>
			subscribers += sender
			context watch sender

			for(d <- deviceSet) sender ! DeviceConnected(d)
		case UnSubscribe =>
			subscribers -= sender
			context unwatch sender
		case RefFor(deviceId) => deviceActors get deviceId match {
			case Some(ref) => sender ! DeviceRef(deviceId, Some(ref))
			case None =>
				val list = new DeviceList
				val result = LibUsb.getDeviceList(usbContext, list)

				if (result < 0)
					throw new LibUsbException("Unable to get device list", result)

				val found = list.filter(deviceToId(_) == deviceId).headOption

				found match {
					case None => sender ! DeviceRef(deviceId, None)
					case Some(x) =>
						//will be freed later when the actor dies
						LibUsb.refDevice(x)

						val ref = context.actorOf(Props(classOf[UsbDevice],
							x, deviceId), name = deviceId.idKey)

						context watch ref

						deviceActors += deviceId -> ref

						sender ! DeviceRef(deviceId, Some(ref))
				}

				LibUsb.freeDeviceList(list, true)
		}
		case Terminated(ref) if subscribers(ref) =>
			subscribers -= ref
		case Terminated(ref) if hasDeviceRef(ref) =>
			log.info("Actor ref closed: " + ref)
			deviceActors = deviceActors.filter(_._2 != ref)
		case UpdateDeviceList =>
			val list = new DeviceList
			val result = LibUsb.getDeviceList(usbContext, list)

			if (result < 0)
				throw new LibUsbException("Unable to get device list", result)

			val oldDevices = deviceSet

			deviceSet ++= list.map(deviceToId).toSet

			val removedDevices = oldDevices -- deviceSet
			val addedDevices = deviceSet -- oldDevices

			for(id <- removedDevices) {
				if(deviceActors contains id) {
					log.info("Stopping active device actor")
					context stop deviceActors(id)
				}
			}

			for {
				ref <- subscribers
				device <- addedDevices
			} ref ! DeviceConnected(device)

			for {
				ref <- subscribers
				device <- removedDevices
			} ref ! DeviceDisconnected(device)

			LibUsb.freeDeviceList(list, true)


	}

	override def preStart() {
		super.preStart()

		usbContext = new Context
		val result = LibUsb.init(usbContext)
		if (result != LibUsb.SUCCESS)
			throw new LibUsbException("Unable to initialize libusb.", result)

		if(LibUsb.hasCapability(LibUsb.CAP_HAS_HOTPLUG)) {
			val result = LibUsb.hotplugRegisterCallback(usbContext,
				LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED
						| LibUsb.HOTPLUG_EVENT_DEVICE_LEFT,
				LibUsb.HOTPLUG_ENUMERATE,
				LibUsb.HOTPLUG_MATCH_ANY,
				LibUsb.HOTPLUG_MATCH_ANY,
				LibUsb.HOTPLUG_MATCH_ANY,
				hotplugCallback, null, callbackHandle)

			if (result != LibUsb.SUCCESS) throw new LibUsbException(
				"Unable to register hotplug callback", result)

		} else log.warning("No USB hotplug!")

		context.system.scheduler.schedule(1.seconds, deviceUpdateInterval,
			self, UpdateDeviceList)

		//create pinned selector
		context watch context.actorOf(Props(classOf[LibUsbSelector],
			usbContext).withDispatcher(pinnedDispatcher), name = "selector")
	}

	override def postStop() {
		super.postStop()

		if(LibUsb.hasCapability(LibUsb.CAP_HAS_HOTPLUG))
			LibUsb.hotplugDeregisterCallback(usbContext, callbackHandle)

		if(usbContext != null) LibUsb.exit(usbContext)

	}
}
