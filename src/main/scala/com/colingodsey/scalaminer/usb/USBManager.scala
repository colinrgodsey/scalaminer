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

package com.colingodsey.scalaminer.usb

import javax.usb.{UsbHostManager, UsbHub, UsbDevice, UsbConst}
import scala.concurrent.duration._
import scala.concurrent.{Future, blocking}
import scala.collection.JavaConversions._
import akka.actor._
import akka.pattern._
import com.colingodsey.scalaminer.utils._
import com.colingodsey.scalaminer.{MinerIdentity, ScalaMiner, MinerDriver}
import com.colingodsey.scalaminer.metrics.MinerMetrics
import com.typesafe.config.Config
import com.colingodsey.io.usb.Usb
import akka.util.Timeout
import akka.io.IO
import akka.actor.SupervisorStrategy.Stop


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

	def interfaces: Set[Usb.Interface]

	def usbDeviceActorProps(device: Usb.DeviceId,
			workRefs: Map[ScalaMiner.HashType, ActorRef]): Props

	def matches(device: UsbDevice) = {
		val desc = device.getUsbDeviceDescriptor

		desc.idVendor == idVendor && desc.idProduct == idProduct
	}

	def matches(device: Usb.DeviceId) = {
		val desc = device.desc

		desc.vendor == idVendor && desc.product == idProduct
	}
}

object UsbDeviceManager {
	sealed trait Command

	case class AddDriver(driver: USBDeviceDriver) extends Command
	case class AddStratumRef(typ: ScalaMiner.HashType, stratumRef: ActorRef) extends Command

	case class FailedIdentify(ref: ActorRef, identity: USBIdentity) extends Command

	case object IdentityReset extends Command

	case object ScanDevices extends Command

	case object Start extends Command

	case class RemoveRef(ref: ActorRef) extends Command
}

class UsbDeviceManager(config: Config)
		extends Actor with ActorLogging with Stash {
	import UsbDeviceManager._

	implicit def to = Timeout(2.seconds)
	implicit def system = context.system
	private implicit def ec = context.system.dispatcher

	lazy val libUsbHost: ActorRef = IO(Usb)

	var usbDrivers: Set[USBDeviceDriver] = Set.empty
	var workerMap: Map[Usb.DeviceId, ActorRef] = Map.empty
	var stratumEndpoints: Map[ScalaMiner.HashType, ActorRef] = Map.empty
	var failedIdentityMap: Map[Usb.DeviceId, Set[USBIdentity]] = Map.empty
	var metricsSubs = Set[ActorRef]()
	var devices = Set.empty[Usb.DeviceId]
	var pollQueued = false

	val identityResetTimer = context.system.scheduler.schedule(
		1.minute, config getDur "identity-reset-time", self, IdentityReset)

	case class CreateDeviceIdentity(id: Usb.DeviceId, identity: USBIdentity)
	case object PollDevices

	override val supervisorStrategy =
		OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1.2.seconds) {
			//case _: org.usb4java.LibUsbException    => Stop
			case _: Exception                       => Stop
		}

	def maybePoll(): Unit = if(!pollQueued) {
		pollQueued = true
		self ! PollDevices
	}

	def receive = {
		case Start =>
			context watch libUsbHost

			libUsbHost ! Usb.Subscribe
		case MinerMetrics.Subscribe =>
			metricsSubs += sender
			workerMap.foreach(_._2.tell(MinerMetrics.Subscribe, sender))
		case MinerMetrics.UnSubscribe =>
			metricsSubs -= sender
			workerMap.foreach(_._2.tell(MinerMetrics.UnSubscribe, sender))
		case IdentityReset => failedIdentityMap = Map.empty
		case AddStratumRef(t, ref) => stratumEndpoints += t -> ref
		case AddDriver(drv) => usbDrivers += drv
		case FailedIdentify(ref, identity) =>
			workerMap.filter(_._2 == ref).map(_._1).headOption.foreach { dev =>
				val s = failedIdentityMap.getOrElse(dev, Set.empty)
				failedIdentityMap += dev -> (s + identity)
			}

			self ! PollDevices
		case Terminated(x) =>
			context.system.scheduler.scheduleOnce(500.millis, self, RemoveRef(x))
		case RemoveRef(x) =>
			val filtered = workerMap.filter(_._2 == x).keySet

			workerMap --= filtered

			log.warning(s"$x died! Removing ${filtered.size} devices.")

			self ! PollDevices
		//got an identity and a device ref
		case CreateDeviceIdentity(id, identity) if !workerMap.contains(id) =>
			val props = identity.usbDeviceActorProps(id, stratumEndpoints)
			val name = identity.name + "." + id.portId
			val ref = context.actorOf(props, name = name)

			log.info(s"Starting $ref for dev $id")

			context watch ref

			metricsSubs.foreach(ref.tell(MinerMetrics.Subscribe, _))

			workerMap += id -> ref
		case Usb.DeviceConnected(id) =>
			devices += id

			log.debug("Device connected " + id)

			self ! PollDevices
		case Usb.DeviceDisconnected(id) =>
			devices -= id

			log.debug("Device disconnected " + id)

			workerMap.get(id) foreach context.stop
		case PollDevices =>
			pollQueued = false

			val matches = for {
				id <- devices
				if !id.desc.isHub
				failedSet = failedIdentityMap.getOrElse(id, Set.empty)
				if !workerMap.contains(id)
				driver <- usbDrivers
				identity <- driver.identities
				if !failedSet(identity)
				if identity matches id
				if stratumEndpoints contains driver.hashType
			} yield id -> identity

			matches foreach { case (id, identity) =>
				self ! CreateDeviceIdentity(id, identity)
			}
	}

	override def preStart() {
		super.preStart()
	}
}
