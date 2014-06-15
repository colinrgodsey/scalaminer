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

import scala.concurrent.duration._
import scala.concurrent.{Future, blocking}
import scala.collection.JavaConversions._
import akka.actor._
import akka.pattern._
import com.colingodsey.scalaminer.utils._
import com.colingodsey.scalaminer.{MinerIdentity, ScalaMiner, MinerDriver}
import com.colingodsey.scalaminer.metrics.MinerMetrics
import com.typesafe.config.{ConfigFactory, Config}
import com.colingodsey.io.usb.Usb
import akka.util.Timeout
import akka.io.IO
import akka.actor.SupervisorStrategy.Stop


object USBUtils {
	//def epi(i: Byte) = (UsbConst.ENDPOINT_DIRECTION_IN | i).toByte
	//def epo(i: Byte) = (UsbConst.ENDPOINT_DIRECTION_OUT | i).toByte
}

//should be a case object
trait USBDeviceDriver extends MinerDriver {
	def identities: Set[USBIdentity]

	def hashType: ScalaMiner.HashType

	def name = toString.toLowerCase()
}

//should be a case object
trait USBIdentity extends MinerIdentity {
	def drv: USBDeviceDriver
	def idVendor: Short
	def idProduct: Short
	def iManufacturer: String
	def iProduct: String
	def config: Int
	def timeout: FiniteDuration

	//delay in irp queue
	def irpDelay = 2.millis
	def latency: FiniteDuration = 32.millis
	def name: String = toString

	def interfaces: Set[Usb.Interface]

	//TODO: implement tryAfter logic! super useful for AMU/ANU for example
	def tryAfter: Set[USBIdentity] = Set()

	def usbDeviceActorProps(device: Usb.DeviceId, config: Config,
			workRefs: Map[ScalaMiner.HashType, ActorRef]): Props

	/*def matches(device: UsbDevice) = {
		val desc = device.getUsbDeviceDescriptor

		desc.idVendor == idVendor && desc.idProduct == idProduct
	}*/

	def matches(device: Usb.DeviceId) = {
		val desc = device.desc

		desc.vendor == idVendor && desc.product == idProduct
	}
}

object UsbDeviceManager {
	sealed trait Command

	case class AddDriver(driver: USBDeviceDriver) extends Command
	case class AddStratumRef(typ: ScalaMiner.HashType, stratumRef: ActorRef) extends Command

	case class FailedIdentify(deviceId: Usb.DeviceId, identity: USBIdentity) extends Command

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

	def removeDelay = 1.2.seconds

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

	def revWorkerMap = workerMap.map(_.swap)

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

	def optConfigOrBlank(key: String) =
		if(config.hasPath(key)) config.getConfig(key)
		else ConfigFactory.empty

	def receive = {
		case Start =>
			context watch libUsbHost
			libUsbHost ! Usb.Subscribe
			log.info("Subscribing to host " + libUsbHost)
		case MinerMetrics.Subscribe =>
			metricsSubs += sender
			workerMap.foreach(_._2.tell(MinerMetrics.Subscribe, sender))
			context watch sender
		case MinerMetrics.UnSubscribe =>
			metricsSubs -= sender
			workerMap.foreach(_._2.tell(MinerMetrics.UnSubscribe, sender))
		case Terminated(ref) if metricsSubs(ref) =>
			metricsSubs -= ref
		case IdentityReset =>
			log.info("Resetting identities")
			failedIdentityMap = Map.empty
			self ! PollDevices
		case AddStratumRef(t, ref) => stratumEndpoints += t -> ref
		case AddDriver(drv) => usbDrivers += drv
		case FailedIdentify(id, identity) =>
			log.info(s"$id failed identity $identity")

			val s = failedIdentityMap.getOrElse(id, Set.empty)
			failedIdentityMap += id -> (s + identity)

			maybePoll()
		case Terminated(x) =>
			context.system.scheduler.scheduleOnce(removeDelay, self, RemoveRef(x))
		case RemoveRef(x) =>
			val filtered = workerMap.filter(_._2 == x).keySet

			workerMap --= filtered

			log.warning(s"$x died! Removing ${filtered.size} devices.")

			maybePoll()
		//got an identity and a device ref
		case CreateDeviceIdentity(id, identity) if !workerMap.contains(id) =>
			val name = identity.name + "-" + id.portId

			val devConfig = optConfigOrBlank(name) withFallback
					optConfigOrBlank(identity.name.toLowerCase) withFallback
					optConfigOrBlank(identity.drv.name.toLowerCase) withFallback
					optConfigOrBlank("device")

			val props = identity.usbDeviceActorProps(id, devConfig, stratumEndpoints)
			val ref = context.actorOf(props, name = name)

			log.info(s"Starting $ref for dev $id")

			context watch ref

			metricsSubs.foreach(ref.tell(MinerMetrics.Subscribe, _))

			workerMap += id -> ref
		case Usb.DeviceConnected(id) =>
			devices += id

			log.debug("Device connected " + id)

			maybePoll()
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
				_ = log.info(s"maybe $identity for $id")
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
