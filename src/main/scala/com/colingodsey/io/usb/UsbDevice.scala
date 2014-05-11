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
import com.colingodsey.scalaminer.utils._
import scala.util.Try

//must deref device after use
class UsbDevice(handle: DeviceHandle, deviceId: Usb.DeviceId)
		extends Actor with ActorLogging {
	import Usb._

	def maxUsbQueueSize = 500
	//TODO: this needs to be configurable, dynamic or otherwise
	val irpTimeout = 2.minutes//100.millis
	val irpDelay = 2.millis

	var claimedInterfaces = Set.empty[Int]
	var endpointQueues = Map.empty[Endpoint, mutable.Queue[(IrpRequest, ActorRef)]]
	var busyEndpoints = Set.empty[Endpoint]

	case class UnbusyEndpoint(endpoint: Endpoint)

	private implicit def ec = context.dispatcher

	def queueFor(ep: Endpoint) = endpointQueues get ep match {
		case None =>
			endpointQueues += ep -> mutable.Queue.empty
			endpointQueues(ep)
		case Some(x) =>
			require(x.length < maxUsbQueueSize, "Internal USB queue flooded!")
			x
	}

	def checkEndpointQueue(ep: Endpoint) = if(!busyEndpoints(ep) && !queueFor(ep).isEmpty) {
		queueFor(ep).dequeue() match {
			case (x @ ReceiveBulkTransfer(_, length, id), ref) =>
				rawBulkTransfer(x, length.toShort, Nil, ref, id)
			case (x @ SendBulkTransfer(_, dat, id), ref) =>
				rawBulkTransfer(x, dat.length.toShort, dat, ref, id)

			case (x @ ReceiveControlIrp(irp, length), ref) =>
				rawIrp(x, length.toShort, Nil, ref)
			case (x @ SendControlIrp(irp, dat), ref) =>
				rawIrp(x, dat.length.toShort, dat, ref)
		}
	}

	//TODO: I feel like this is a fixed size or something...
	def rawIrp(irpReq: ControlIrpRequest,
			length: Short, dat: Seq[Byte], respondTo: ActorRef) {
		val irp = irpReq.irp
		val ControlIrp(requestType, request, value, index) = irp
		val buffer = ByteBuffer.allocateDirect(LibUsb.CONTROL_SETUP_SIZE + length)
		val transfer = LibUsb.allocTransfer

		busyEndpoints += ControlEndpoint

		log.debug("Starting raw irp")

		val callback = new IrpTransferCallback(irpReq, respondTo, buffer)

		LibUsb.fillControlSetup(buffer, requestType, request,
			value, index, length)

		buffer.position(LibUsb.CONTROL_SETUP_SIZE)
		dat foreach buffer.put
		buffer.rewind()

		LibUsb.fillControlTransfer(transfer, handle, buffer,
			callback, null, irpTimeout.toMillis)

		val result = LibUsb.submitTransfer(transfer)
		if (result != LibUsb.SUCCESS)
			throw new LibUsbException("Unable to submit transfer", result)
	}

	def rawBulkTransfer(irpReq: BulkIrpRequest, length: Short,
			dat: Seq[Byte], respondTo: ActorRef, id: Int) {
		val buffer = ByteBuffer.allocateDirect(length)
		val transfer = LibUsb.allocTransfer()

		val endpoint = if(irpReq.isRead) //recv
			irpReq.interface.input
		else irpReq.interface.output

		val inf = (irpReq.interface.interface & 0xFF).toByte

		busyEndpoints += endpoint

		//log.info("Starting bulk transfer " + irpReq)

		if(!claimedInterfaces(inf)) {
			log.debug("Claiming interface " + inf)

			log.debug("SUPPORTS DETACH " + LibUsb.hasCapability(LibUsb.CAP_SUPPORTS_DETACH_KERNEL_DRIVER))
			log.debug("DRIVER ACTIVE " + LibUsb.kernelDriverActive(handle, inf))

			val detach = LibUsb.hasCapability(LibUsb.CAP_SUPPORTS_DETACH_KERNEL_DRIVER) &&
					LibUsb.kernelDriverActive(handle, inf) == 1

			if(!detach && LibUsb.kernelDriverActive(handle, inf) == 1)
				log.warning("Cannot detach active kernel driver. Missing libusb capability.")

			if(detach) {
				log.info("Detaching kernel driver")
				val result = LibUsb.detachKernelDriver(handle, inf)
				if(result != LibUsb.SUCCESS) throw new LibUsbException(
					"Unable to detach kernel driver", result)
			}

			LibUsb.claimInterface(handle, inf)
			claimedInterfaces += inf
		}

		val callback = new IrpTransferCallback(irpReq, respondTo, buffer)

		dat foreach buffer.put
		buffer.rewind()

		LibUsb.fillBulkTransfer(transfer, handle, endpoint.ep, buffer,
			callback, null, irpTimeout.toMillis)

		val result = LibUsb.submitTransfer(transfer)
		if (result != LibUsb.SUCCESS)
			throw new LibUsbException("Unable to submit transfer ep: " + endpoint, result)
	}

	def receive = {
		case Close =>
			log.debug("Asked to close. Closing...")
			context stop self

		case x: Usb.ControlIrp =>
			log.warning("Received Usb.ControlIrp. Did you mean to use Usb.ControlIrp(...).send ?")

		case UnbusyEndpoint(ep) =>
			busyEndpoints -= ep
			checkEndpointQueue(ep)

		case x: ControlIrpRequest =>
			queueFor(ControlEndpoint) enqueue (x -> sender)
			checkEndpointQueue(ControlEndpoint)

		//queue it up
		case x: BulkIrpRequest => // if !queueFor(x.endpoint).isEmpty =>
			queueFor(x.endpoint) enqueue (x -> sender)
			checkEndpointQueue(x.endpoint)

		case SetConfiguration(config) =>
			LibUsb.setConfiguration(handle, config)
	}

	override def preStart() {
		super.preStart()

		LibUsb.setAutoDetachKernelDriver(handle, true)
	}

	override def postStop() {
		log.debug("Shutting down...")

		for(x <- claimedInterfaces) try {
			val detach = LibUsb.hasCapability(LibUsb.CAP_SUPPORTS_DETACH_KERNEL_DRIVER) &&
					LibUsb.kernelDriverActive(handle, x) == 1

			LibUsb.releaseInterface(handle, x)

			if(detach) {
				log.info("Attaching kernel driver")
				val result = LibUsb.attachKernelDriver(handle, x)
				if(result != LibUsb.SUCCESS) throw new LibUsbException(
					"Unable to re-attach kernel driver", result)
			}
		} catch {
			case x: Throwable =>
				log.error(x, "failed to release interface")
		}

		Try(LibUsb close handle).failed.foreach(
			log.error(_, "Failed closing handle"))

	}

	class IrpTransferCallback(irp: IrpRequest, respondTo: ActorRef,
			buffer: ByteBuffer) extends TransferCallback {
		override def processTransfer(transfer: Transfer) = try {
			//log.info("Irp transfer done " + irp)

			def bail(err: Usb.Error) {
				LibUsb freeTransfer transfer
				respondTo ! err
				log.error(err, "Transfer error. Closing device")
				context stop self
			}

			//TODO: look into not making this close on all error conditions
			transfer.status match {
				case LibUsb.TRANSFER_COMPLETED =>
					val actualLen = transfer.actualLength

					val r = if(irp.isRead) {
						buffer.rewind()

						//we cannot modify the buffer after this point! effectively sealed.
						irp match {
							case x: ControlIrpRequest =>
								val resDat = (buffer: Seq[Byte]).view(
									LibUsb.CONTROL_SETUP_SIZE, LibUsb.CONTROL_SETUP_SIZE + actualLen)
								ControlIrpResponse(x.irp, Right(resDat))
							case x: BulkIrpRequest =>
								val resDat = (buffer: Seq[Byte]).view(0, actualLen)
								BulkTransferResponse(x.interface, Right(resDat), x.id)
						}
					} else irp match {
						case x: ControlIrpRequest =>
							ControlIrpResponse(x.irp, Left(actualLen))
						case x: BulkIrpRequest =>
							BulkTransferResponse(x.interface, Left(actualLen), x.id)
					}

					LibUsb freeTransfer transfer

					respondTo ! r
					context.system.scheduler.scheduleOnce(
						irpDelay, self, UnbusyEndpoint(irp.endpoint))
				case LibUsb.TRANSFER_TIMED_OUT =>
					bail(IrpTimeout(irp))
				case LibUsb.TRANSFER_CANCELLED =>
					bail(IrpCancelled(irp))
				case LibUsb.TRANSFER_STALL =>
					bail(IrpStall(irp))
				case LibUsb.TRANSFER_NO_DEVICE =>
					bail(IrpNoDevice)
					context stop self
				case LibUsb.TRANSFER_OVERFLOW =>
					bail(IrpOverflow(irp))
				case LibUsb.TRANSFER_ERROR =>
					bail(IrpCancelled(irp))
				case _ =>
					bail(IrpError(irp))
			}
		} catch { case x: Throwable =>
			log.error(x, "transfer failed")

			context stop self
		}
	}
}

