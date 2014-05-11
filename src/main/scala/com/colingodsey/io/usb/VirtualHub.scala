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

import scala.concurrent.duration._
import akka.actor._
import akka.pattern._
import com.colingodsey.scalaminer.utils._
import akka.io

object VirtualUsbHost {
	sealed trait Message

	case object GetHosts extends Message
	case class AddHost(sel: ActorSelection) extends Message
	object AddHost {
		def apply(ref: ActorRef): AddHost = AddHost(ActorSelection(ref, Nil))
	}
}

class VirtualUsbHost(usb: UsbExt) extends Actor with ActorLogging {
	import Usb._
	import VirtualUsbHost._

	//TODO: make this configurable later?
	def includeLocal = true

	var subscribers = Set.empty[ActorRef]
	var deviceSet = Set.empty[DeviceId]
	var hostRefs = Set.empty[ActorRef]
	var hosts = usb.Settings.virtualHosts.map(context.actorSelection).toSet

	implicit def ec = context.system.dispatcher
	implicit def system = context.system

	val hostPollTimer = system.scheduler.schedule(1.second, 3.seconds, self, GetHosts)
	lazy val hostRef = context.actorOf(Props(classOf[UsbHost],
		usb), name = "host")

	def subToHosts() {
		hosts.foreach(_ ! Subscribe)
		if(includeLocal) {
			hostRef ! Subscribe
			context watch hostRef
		}
	}

	def receive = {
		case x: HostRequest => x match {
			case Subscribe if !subscribers(sender) =>
				subscribers += sender
				context watch sender

				for(d <- deviceSet) sender ! DeviceConnected(d)
			case UnSubscribe if subscribers(sender) =>
				subscribers -= sender
				context unwatch sender
			case Subscribe =>
			case UnSubscribe =>
			case Connect(id) =>
				id.hostRef.tell(Connect(id), sender)
		}

		case x @ DeviceDisconnected(id) if deviceSet(id) =>
			subscribers.foreach(_ ! x)
			deviceSet -= id
		case DeviceDisconnected(_) =>
		case x @ DeviceConnected(id) =>
			if(!hostRefs(sender)) {
				hostRefs += sender
				context watch sender
				log.info("New remote host " + sender)
			}
			subscribers.foreach(_ ! x)
			deviceSet += id

		case GetHosts => subToHosts()
		case AddHost(sel) =>
			hosts += sel
			sel ! Subscribe

		case Terminated(ref) if hostRefs(ref) =>
			hostRefs -= ref
			val oldSet = deviceSet
			deviceSet = deviceSet.filter(x => hostRefs(x.hostRef))
			val removed = oldSet -- deviceSet

			for(id <- removed; sub <- subscribers)
				sub ! DeviceDisconnected(id)
		case Terminated(ref) if subscribers(ref) =>
			subscribers -= ref
	}

	override def preStart() {
		super.preStart()

		subToHosts()
	}

	override def postStop() {
		super.postStop()

		hostPollTimer.cancel()
	}
}
