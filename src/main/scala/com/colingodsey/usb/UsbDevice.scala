package com.colingodsey.usb

import org.usb4java._
import akka.actor._
import scala.collection.mutable
import java.nio.ByteBuffer
import scala.concurrent.duration._
import com.colingodsey.scalaminer.utils._
import scala.util.Try

//must deref device after use
class UsbDevice(device: Device, deviceId: Usb.DeviceId)
		extends Actor with ActorLogging {
	import Usb._

	val handle: DeviceHandle = new DeviceHandle

	def maxUsbQueueSize = 500
	val irpTimeout = 100.millis

	var claimedInterfaces = Set.empty[Int]
	var endpointQueues = Map.empty[Endpoint, mutable.Queue[(IrpRequest, ActorRef)]]
	var busyEndpoints = Set.empty[Endpoint]

	def queueFor(ep: Endpoint) = endpointQueues get ep match {
		case None =>
			endpointQueues += ep -> mutable.Queue.empty
			endpointQueues(ep)
		case Some(x) =>
			require(x.length < maxUsbQueueSize, "Internal USB queue flooded!")
			x
	}

	def checkEndpointQueue(ep: Endpoint) = if(!busyEndpoints(ep) && !queueFor(ep).isEmpty) {
		queueFor(ep).head match {
			case (ReceiveBulkTransfer(endpoint, length, id), ref) =>
				rawBulkTransfer(endpoint, length.toShort, Nil, ref, id)
			case (SendBulkTransfer(endpoint, dat, id), ref) =>
				rawBulkTransfer(endpoint, dat.length.toShort, dat, ref, id)

			case (ReceiveControlIrp(irp, length), ref) =>
				rawIrp(irp, length.toShort, Nil, sender)
			case (SendControlIrp(irp, dat), ref) =>
				rawIrp(irp, dat.length.toShort, dat, sender)
		}
	}

	def rawIrp(irp: ControlIrp,
			length: Short, dat: Seq[Byte], respondTo: ActorRef) {
		val ControlIrp(requestType, request, value, index) = irp
		val buffer = ByteBuffer.allocateDirect(LibUsb.CONTROL_SETUP_SIZE + length)
		val transfer = LibUsb.allocTransfer

		busyEndpoints += ControlEndpoint

		val callback = new TransferCallback {
			override def processTransfer(transfer0: Transfer) {
				busyEndpoints -= ControlEndpoint

				if(transfer0.status != LibUsb.TRANSFER_COMPLETED) throw new LibUsbException(
					"Transfer failed", transfer0.status)

				require(transfer0 == transfer)

				val actualLen = transfer.actualLength

				if(dat.isEmpty) { //recv
					buffer.rewind()

					//we cannot modify the buffer after this point! effectively sealed.
					val resDat = (buffer: Seq[Byte]).view(
						LibUsb.CONTROL_SETUP_SIZE, LibUsb.CONTROL_SETUP_SIZE + actualLen)

					respondTo ! ControlIrpResponse(irp, Right(resDat))
				} else
					respondTo ! ControlIrpResponse(irp, Left(actualLen))


				LibUsb freeTransfer transfer

				checkEndpointQueue(ControlEndpoint)
			}
		}

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

	def rawBulkTransfer(interface: Interface, length: Short,
			dat: Seq[Byte], respondTo: ActorRef, id: Int) {
		val buffer = ByteBuffer.allocateDirect(length)
		val transfer = LibUsb.allocTransfer

		val endpoint = if(dat.isEmpty) //recv
			interface.input
		else interface.output

		val inf = interface.interface

		busyEndpoints += endpoint

		if(!claimedInterfaces(inf)) {
			val detach = LibUsb.hasCapability(LibUsb.CAP_SUPPORTS_DETACH_KERNEL_DRIVER) &&
					LibUsb.kernelDriverActive(handle, inf) == 1

			if(detach) {
				val result = LibUsb.detachKernelDriver(handle, inf)
				if(result != LibUsb.SUCCESS) throw new LibUsbException(
					"Unable to detach kernel driver", result)
			}

			LibUsb.claimInterface(handle, inf)
			claimedInterfaces += inf
		}

		val callback = new TransferCallback {
			override def processTransfer(transfer0: Transfer) {
				require(transfer0 == transfer)

				val actualLen = transfer.actualLength

				if(dat.isEmpty) { //recv
				val resDat = (buffer: Seq[Byte]).view(0, actualLen)
					respondTo ! BulkTransferResponse(interface, Right(resDat), id)
				} else
					respondTo ! BulkTransferResponse(interface, Left(actualLen), id)

				LibUsb freeTransfer transfer

				busyEndpoints -= endpoint
				checkEndpointQueue(endpoint)
			}
		}

		dat foreach buffer.put
		buffer.rewind()

		LibUsb.fillBulkTransfer(transfer, handle, endpoint.ep, buffer,
			callback, null, irpTimeout.toMillis)
	}

	def receive = {
		case x: ControlIrpRequest =>
			queueFor(ControlEndpoint) enqueue (x -> sender)

		//queue it up
		case x: BulkIrpRequest => // if !queueFor(x.endpoint).isEmpty =>
			queueFor(x.endpoint) enqueue (x -> sender)
	}

	override def preStart() {
		super.preStart()

		val r = LibUsb.open(device, handle)

		if(r != LibUsb.SUCCESS)
			throw new LibUsbException("Unable to open handle", r)
	}

	override def postStop() {
		for(x <- claimedInterfaces) try {
			val detach = LibUsb.hasCapability(LibUsb.CAP_SUPPORTS_DETACH_KERNEL_DRIVER) &&
					LibUsb.kernelDriverActive(handle, x) == 1

			LibUsb.releaseInterface(handle, x)

			if(detach) {
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
		Try(LibUsb unrefDevice device).failed.foreach(
			log.error(_, "Failed closing handle"))
	}
}

