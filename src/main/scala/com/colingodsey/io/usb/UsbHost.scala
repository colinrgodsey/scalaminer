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

import org.usb4java._
import akka.actor._
import scala.collection.mutable
import java.nio.ByteBuffer
import scala.concurrent.duration._
import scala.concurrent.blocking
import com.colingodsey.scalaminer.utils._
import scala.collection.JavaConversions._
import akka.actor.SupervisorStrategy._

object UsbHost {
	sealed trait Command

	case object UpdateDeviceList extends Command

	private[usb] class LibUsbSelector(usbContext: Context) extends Actor with ActorLogging {
		implicit def ec = context.system.dispatcher
		case object Poll

		val pollTimeout = 1.second
		val pollFreq = 0.millis//2.millis

		def startPollTimer() {
			if(pollTimeout > 0.seconds)
				context.system.scheduler.scheduleOnce(pollFreq, self, Poll)
			else self ! Poll
			//log.info("Starting poll timer")
		}

		def receive = {
			case Poll => blocking {
				//LibUsb.lockEvents(usbContext)

				try {
					val st = LibUsb.handleEventsTimeout(usbContext, pollTimeout.toMicros)

					if(st != 0)
						throw new LibUsbException("handleEventsTimeout error", st)
				} catch { case x: Throwable =>
					log.error(x, "Uncaught selector error!")
				}

				//LibUsb.unlockEvents(usbContext)
				startPollTimer()
			}
		}

		override def preStart() {
			super.preStart()

			startPollTimer()

			log.info("Starting LibUsbSelector")
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

	override val supervisorStrategy =
		OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.2.seconds) {
			//case _: org.usb4java.LibUsbException    => Stop
			case _: Exception                       => Stop
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
		case Connect(deviceId) => deviceActors get deviceId match {
			case Some(ref) => sender ! Connected(deviceId, Some(ref))
			case None =>
				val list = new DeviceList
				val result = LibUsb.getDeviceList(usbContext, list)

				if (result < 0)
					throw new LibUsbException("Unable to get device list", result)

				val found = list.filter(deviceToId(_) == deviceId).headOption

				found match {
					case None => sender ! Connected(deviceId, None)
					case Some(device) =>
						val handle: DeviceHandle = new DeviceHandle

						val r = LibUsb.open(device, handle)

						if(r != LibUsb.SUCCESS) {
							log.error(new LibUsbException("Unable to open handle", r),
								"Failed to open device handle")
							Connected(deviceId, None)
						} else {
							val ref = context.actorOf(Props(classOf[UsbDevice],
								handle, deviceId), name = deviceId.idKey)

							log.info(s"Starting $ref for dev $deviceId")

							context watch ref

							deviceActors += deviceId -> ref

							sender ! Connected(deviceId, Some(ref))
						}
				}

				LibUsb.freeDeviceList(list, true)
		}
		case Terminated(ref) if hasDeviceRef(ref) =>
			log.info("Device ref closed: " + ref)
			val filtered = deviceActors.filter(_._2 != ref)

			//val removed = deviceActors.toSet -- filtered.toSet
			//for(rem <- removed) deviceSet -= rem._1

			deviceActors = filtered
			subscribers -= ref

			self ! UpdateDeviceList
		case Terminated(ref) if subscribers(ref) =>
			subscribers -= ref
		case UpdateDeviceList =>
			val list = new DeviceList
			val result = LibUsb.getDeviceList(usbContext, list)

			if (result < 0)
				throw new LibUsbException("Unable to get device list", result)

			val oldDevices = deviceSet

			deviceSet = list.map(deviceToId).toSet

			val removedDevices = oldDevices -- deviceSet
			val addedDevices = deviceSet -- oldDevices

			if(!removedDevices.isEmpty)
				log.info("Removing devices " + removedDevices)

			if(!addedDevices.isEmpty)
				log.info("Adding devices " + addedDevices)

			for(id <- removedDevices) {
				if(deviceActors contains id) {
					log.info("Stopping active device actor " + deviceActors(id))
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

		log.info("Starting UsbHost")
	}

	override def postStop() {
		super.postStop()

		if(LibUsb.hasCapability(LibUsb.CAP_HAS_HOTPLUG))
			LibUsb.hotplugDeregisterCallback(usbContext, callbackHandle)

		if(usbContext != null) LibUsb.exit(usbContext)

	}
}
