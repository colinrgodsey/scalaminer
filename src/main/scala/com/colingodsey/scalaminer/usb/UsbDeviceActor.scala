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

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import akka.actor._
import com.colingodsey.io.usb.Usb
import akka.io.IO
import com.colingodsey.scalaminer.usb.UsbDeviceActor.{NonTerminated}
import com.colingodsey.scalaminer.MinerIdentity

object UsbDeviceActor {
	//Start func, then continue/finalization func
	type USBHandler = (() => Unit, PartialFunction[Any, Boolean])

	case object NoWait extends PartialFunction[Any, Boolean] {
		override def isDefinedAt(x: Any): Boolean = true
		def apply(x: Any): Boolean = sys.error("this should never fire!")
	}

	object NonTerminated {
		def unapply(x: Any) = x match {
			case _: Terminated => None
			case _ => Some(x)
		}
	}

	case object CloseDevice

}

trait UsbDeviceActor extends Actor with ActorLogging with Stash {
	val deviceId: Usb.DeviceId
	def identity: USBIdentity

	def isFTDI: Boolean
	def readDelay: FiniteDuration

	def defaultTimeout = identity.timeout
	def minerIdentity: MinerIdentity = identity
	def deviceName = deviceId.idKey.toString

	private implicit def ec = context.system.dispatcher
	private implicit def system = context.system

	var deviceRef: ActorRef = context.system.deadLetters

	def send(interface: Usb.Interface, data: Seq[Byte]*) =
		/*deviceRef ! Usb.SendBulkTransfer(interface,
			data.foldLeft(ByteString.empty)(_ ++ _))*/
		for(x <- data) deviceRef ! Usb.SendBulkTransfer(interface, x)

	def waitingOnDevice(after: => Unit): Receive = {
		case Usb.Connected(`deviceId`, Some(ref)) =>
			log.info("Received device ref " + ref)
			deviceRef = ref
			context watch ref
			context.unbecome()
			unstashAll()

			deviceRef ! Usb.SetConfiguration(identity.config)

			after
		case Usb.Connected(`deviceId`, None) =>
			sys.error("no DeviceRef found!")
		case x: Usb.Connected =>
			sys.error("Unexpected DeviceRef " + x)
		case NonTerminated(_) => stash()
	}

	def getDevice(after: => Unit) {
		log.info(s"Getting device ref")
		IO(Usb) ! Usb.Connect(deviceId)
		context.become(waitingOnDevice(after), false)
	}

	def failDetect() {
		log.info("Failed detection for " + identity)
		context stop self
		context.parent ! UsbDeviceManager.FailedIdentify(self, identity)
	}

	abstract override def preStart() {
		super.preStart()

		log.info(s"Starting $identity at $deviceId")
	}

	abstract override def postStop() {
		super.postStop()

		context stop deviceRef

		log.info(s"Stopping $identity at $deviceId")
	}
}