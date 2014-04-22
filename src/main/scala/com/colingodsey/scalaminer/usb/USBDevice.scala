package com.colingodsey.scalaminer.usb

import javax.usb._
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import javax.usb.event._
import java.io.{ByteArrayOutputStream, DataOutputStream}
import akka.actor._
import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer.{ScalaMiner}
import scala.util.Try
import com.colingodsey.scalaminer.usb.USBManager.Interface
import scala.collection.immutable.Queue

object USBDeviceActor {
	//Start func, then continue/finalization func
	type USBHandler = (() => Unit, PartialFunction[Any, Boolean])

	case object NoWait extends PartialFunction[Any, Boolean] {
		override def isDefinedAt(x: Any): Boolean = true
		def apply(x: Any): Boolean = sys.error("this should never fire!")
	}

	case object CloseDevice
}

trait USBDeviceActor extends Actor with ActorLogging with Stash {
	import USBDeviceActor._

	def device: UsbDevice
	def identity: USBIdentity
	val defaultReadSize: Int

	def hashType: ScalaMiner.HashType = identity.drv.hashType

	def isFTDI: Boolean = false

	def commandDelay = 2.millis
	def defaultTimeout = identity.timeout
	def maxUsbQueueSize = 500

	implicit def ec = context.system.dispatcher

	val devListener = new UsbDeviceListener {
		def dataEventOccurred(event: javax.usb.event.UsbDeviceDataEvent): Unit =
			if(!dead) self ! event

		def errorEventOccurred(event: UsbDeviceErrorEvent): Unit =
			if(!dead) self ! event

		def usbDeviceDetached(event: UsbDeviceEvent): Unit =
			if(!dead) self ! event

		var dead: Boolean = false
	}

	val pipeListener = new UsbPipeListener {
		override def dataEventOccurred(event: UsbPipeDataEvent): Unit = self ! event

		override def errorEventOccurred(event: UsbPipeErrorEvent): Unit = self ! event
	}

	lazy val configuration = device.getActiveUsbConfiguration

	lazy val defaultReadBuffer = Array.fill[Byte](defaultReadSize)(0)

	var openedPipes = Set[UsbPipe]()
	var openedIfaces = Set[UsbInterface]()

	var usbCommandQueue: Map[Interface, Queue[USBHandler]] = Map.empty
	var usbCommandTags: Map[Interface, String] = Map.empty
	var usbCommandTagStats: Map[(Interface, String), Int] = Map.empty
	var usbCommandExecuting: Set[Interface] = Set.empty

	def usbBaseReceive = usbCommandReceive orElse usbErrorReceive

	def onReadTimeout() {
		log.error("Read data timeout!")
		context stop self
	}

	def usbErrorReceive: Actor.Receive = {
		case x: UsbDeviceErrorEvent =>
			throw x.getUsbException
		case x: UsbPipeErrorEvent =>
			throw x.getUsbException
		/*case x: UsbDeviceEvent =>
			log.info("Device removed! " + x)
			//context stop self*/
	}

	//TODO: use buffers and just constantly read input
	def constantRead {}

	//tag commands for debugging
	def usbCommandInfo(interface: Interface, name: String)(f: => Unit) {
		val key = (interface, name)
		usbCommandTagStats += key -> (usbCommandTagStats.getOrElse(key, 0) + 1)
		addUsbCommandToQueue(interface, ({ () =>
			usbCommandTags += interface -> name
		}, NoWait))
		f
		addUsbCommandToQueue(interface, ({ () =>
			usbCommandTags -= interface
			usbCommandTagStats += key -> (usbCommandTagStats.getOrElse(key, 0) - 1)
		}, NoWait))
	}

	def addUsbCommandToQueue(interface: Interface, cmd: USBHandler) {
		val q = usbCommandQueue.getOrElse(interface, Queue.empty)

		usbCommandQueue += interface -> (q :+ cmd)

		if(q.length > maxUsbQueueSize)
			sys.error("Internal USB queue flooded!")

		maybeExecuteUsbCommand(interface)
	}

	def maybeExecuteUsbCommand(interface: Interface) {
		val q = usbCommandQueue.getOrElse(interface, Queue.empty)

		if(!usbCommandExecuting(interface) && !q.isEmpty) {
			//log.info("Running queue for " + interface)
			usbCommandExecuting += interface
			val head = q.head

			head._1()

			if(head._2 == NoWait) {
				usbCommandQueue += interface -> q.tail
				usbCommandExecuting -= interface
				maybeExecuteUsbCommand(interface)
			}
		}
	}

	def insertCommands(interface: Interface)(f: => Unit) = {
		val q = usbCommandQueue.getOrElse(interface, Queue.empty)
		f
		val q2 = usbCommandQueue.getOrElse(interface, Queue.empty)

		//get new commands, then prepend queue
		val n = q2.slice(q.length, q2.length)

		//in middle of req, so add to next
		val newQ = if(usbCommandExecuting(interface)) {
			(q.head +: n) ++ q.tail
		} else n ++ q

		usbCommandQueue += interface -> newQ
	}

	def dropNextUsbCommand(interface: Interface) {
		val q = usbCommandQueue.getOrElse(interface, Queue.empty)

		if(q.isEmpty)
			sys.error("Cannot drop next command! Doesnt exist")

		usbCommandQueue += interface -> q.tail
	}

	def dropCommands(interface: Interface, cmds: Set[USBHandler]) {
		val q = usbCommandQueue.getOrElse(interface, Queue.empty)

		usbCommandQueue += interface -> q.filter(!cmds(_))
	}

	def commandChunk(interface: Interface)(f: => Unit) = {
		val q = usbCommandQueue.getOrElse(interface, Queue.empty)
		f
		val q2 = usbCommandQueue.getOrElse(interface, Queue.empty)

		q2.slice(q.length, q2.length)
	}

	def usbCommandReceive: Receive = new PartialFunction[Any, Unit] {
		def opt = usbCommandQueue.map(pair => pair._1 -> pair._2.headOption)

		override def isDefinedAt(x: Any): Boolean = (for {
			(intf, optRecv) <- opt.toSeq
			if usbCommandExecuting(intf)
			(start, recv) <- optRecv
		} yield recv.isDefinedAt(x)).count(_ == true) > 0

		override def apply(v1: Any): Unit = {
			val recvs = for {
				(intf, optRecv) <- opt.toSeq
				if usbCommandExecuting(intf)
				(start, recv) <- optRecv
				if recv.isDefinedAt(v1)
			} yield intf -> recv

			if(recvs.length > 1)
				log.error("2 USB recv commands applied!")

			val (interface, recv) = recvs.head

			//log.info("Finishing " + interface)

			//finish if true
			if(recv(v1)) {
				val q = usbCommandQueue.getOrElse(interface, Queue.empty)
				usbCommandQueue += interface -> q.tail
				usbCommandExecuting -= interface
				maybeExecuteUsbCommand(interface)
			}
		}
	}

	lazy val endpointsForIface = (for {
		desc <- identity.interfaces.toSeq
		iface = configuration.getUsbInterface(desc.interface.toByte)
		endpoints = (for {
			endpointDesc <- desc.endpoints.toSeq
			endpoint = iface.getUsbEndpoint(endpointDesc.ep)
			pipe = endpoint.getUsbPipe
			_ = {
				val ifaceOpen = openedIfaces(iface)

				openedPipes += pipe
				openedIfaces += iface

 				if(!ifaceOpen) iface.claim(new UsbInterfacePolicy() {
				    override def forceClaim(usbInterface: UsbInterface): Boolean = true
			    })

				pipe.addUsbPipeListener(pipeListener)

				//TODO: really should find out how to guarantee these are closed
				if(pipe.isOpen) pipe.close()
				pipe.open()

			}
		} yield endpointDesc -> pipe).toMap
	} yield desc -> endpoints).toMap

	def failDetect {
		context stop self
		context.parent ! USBManager.FailedIdentify(self, identity)
	}

	def runIrps(irps: List[UsbControlIrp])(then: IndexedSeq[Byte] => Unit) {
		if(!irps.isEmpty) {
			device asyncSubmit irps.head

			log.info("Submitting irp")

			context.become(usbBaseReceive orElse {
				case x: UsbDeviceDataEvent if x.getUsbControlIrp == irps.head =>
					context.unbecome()

					val dat = x.getData

					//println(dat.toList)

					if(irps.tail.isEmpty) {
						unstashAll()
						then(dat)
					}
					else runIrps(irps.tail)(then)
				case _ => stash()
			}, false)
		}
	}

	def sleep(dur: FiniteDuration)(then: => Unit) = {
		object DoResume

		context.system.scheduler.scheduleOnce(dur, self, DoResume)

		if(dur != 0.millis) waitForOld {
			case DoResume =>
		}(then) else then
	}

	def sleepInf(interface: Interface, dur: FiniteDuration)  {
		object DoResume

		if(dur != 0.millis) waitForInterface(interface, {
			case DoResume =>
		})(
			context.system.scheduler.scheduleOnce(dur, self, DoResume)
		)(())
	}

	def readLinesUntil(interface: Interface, term: String,
			timeout: FiniteDuration = defaultTimeout)(recv: Seq[String] => Unit) {
		var strs = Seq[String]()

		readLine(interface, timeout) { str =>
			strs :+= str

			val r = str == term

			if(r) recv(strs)

			r
		}
	}

	//recv returns true if its done reading
	def readLine(interface: Interface,
			timeout: FiniteDuration = defaultTimeout,
			softFailTimeout: Option[FiniteDuration] = None)(recv: String => Boolean) = {
		readDataUntilByte(interface, '\n'.toByte, timeout, softFailTimeout) { bytes =>
			val str = new String(bytes.toArray, "ASCII")

			recv(str)
		}
	}

	def readDataUntilLength(interface: Interface, len: Int,
			timeout: FiniteDuration = defaultTimeout,
			softFailTimeout: Option[FiniteDuration] = None)(
			recv: IndexedSeq[Byte] => Unit) {
		var buffer = ScalaMiner.BufferType.empty

		val softDeadline = softFailTimeout.map(Deadline.now + _)

		readDataUntilCond(interface, timeout) { dat =>
			buffer ++= dat

			if(buffer.length >= len) {
				val (left, right) = buffer.splitAt(len)

				buffer = right

				recv(left)

				if(!buffer.isEmpty)
					log.info("Throwing out " + buffer.length)

				true
			} else if(softDeadline.map(_.isOverdue()).getOrElse(false)) {
				//send data length 0 here
				recv(IndexedSeq.empty)
				true
			} else false
		}
	}

	def readDataUntilByte(interface: Interface, byte: Byte,
			timeout: FiniteDuration = defaultTimeout,
			softFailTimeout: Option[FiniteDuration] = None)(
			recv: (IndexedSeq[Byte]) => Boolean) {
		val softDeadline = softFailTimeout.map(Deadline.now + _)
		var buffer = ScalaMiner.BufferType.empty

		def procSome(dat: Seq[Byte]): Boolean = {
			buffer ++= dat
			val idx = buffer indexOf byte
			if(softDeadline.map(_.isOverdue()).getOrElse(false)) {
				recv(IndexedSeq.empty)
				true
			} else if(idx != -1) {
				val (left, right0) = buffer splitAt idx
				val right = right0 drop 1

				buffer = right

				val r = recv(left)

				if(r && !buffer.isEmpty)
					log.info("Throwing out " + buffer.length)

				if(!r) procSome(Nil)
				else r
			} else false
		}

		readDataUntilCond(interface, timeout)(procSome)
	}

	//TODO: log?
	def flushRead(interface: Interface) = {
		sleepInf(interface, commandDelay)
		readDataUntilCond(interface)(_.isEmpty)
	}

	def readDataUntilCond(interface: Interface,
			timeout: FiniteDuration = defaultTimeout)(recv: IndexedSeq[Byte] => Boolean) {
		object TimedOut

		val eps = endpointsForIface(interface)

		val (ep, pipe) = eps.filter(_._1.isInput).head

		lazy val timeoutTime = context.system.scheduler.scheduleOnce(
			timeout, self, TimedOut)

		addUsbCommandToQueue(interface, ({ () =>
			timeoutTime.isCancelled
			pipe.asyncSubmit(defaultReadBuffer)
		}, {
			case TimedOut =>
				onReadTimeout()
				true
			case x: UsbPipeDataEvent if x.getUsbPipe == pipe =>
				val dat = if(isFTDI) {
					x.getData.drop(2)
				} else x.getData

				val r = recv(dat)

				if(r) timeoutTime.cancel()
				else pipe.asyncSubmit(defaultReadBuffer)

				r
		}))
	}

	def waitForOld(cond: Actor.Receive)(then: => Unit) {
		context.become(usbBaseReceive orElse {
			case x if cond.isDefinedAt(x) =>
				context.unbecome()
				cond(x)
				unstashAll()
				then == ()
			case _ => stash()
		}, false)
	}

	def waitForInterface(interface: Interface, cond: Actor.Receive)(start: => Unit)(then: => Unit) {
		addUsbCommandToQueue(interface, (() => start, {
			case x if cond.isDefinedAt(x) =>
				then == ()
				true
		}))
	}

	def sendDataCommand(interface: Interface, dat: Seq[Byte],
			timeout: FiniteDuration = defaultTimeout)(then: => Unit) {
		val eps = endpointsForIface(interface)

		val (ep, pipe) = eps.filter(_._1.isOutput).head

		sleepInf(interface, commandDelay)
		addUsbCommandToQueue(interface, (() => pipe.asyncSubmit(dat.toArray), {
			case x: UsbPipeDataEvent if x.getUsbPipe == pipe =>
				val read = x.getData
				require(dat.length == read.length,
					s"did not send all of our data! ${read.length} of ${dat.length}")
				then == ()
				true
		}))
	}

	def sendDataCommands(interface: Interface, dat: Seq[Seq[Byte]],
			timeout: FiniteDuration = defaultTimeout)(then: => Unit) {
		if(dat.length > 0) {
			val first = dat.slice(0, dat.length - 1)
			val last = dat(dat.length - 1)

			first.foreach(x => sendDataCommand(interface, x)())

			sendDataCommand(interface, last)(then)
		} else then
	}

	abstract override def preStart() {
		super.preStart()

		device.addUsbDeviceListener(devListener)
	}

	abstract override def postStop() {
		super.postStop()

		devListener.dead = true

		log.warning("Cmd stats " + usbCommandTagStats)

		//TODO: .... not too happy about this
		scala.concurrent.blocking(Thread.sleep(5000))

		if(!usbCommandTags.isEmpty)
			log.warning("Died during usb commands " + usbCommandTags)

		openedPipes.foreach { x =>
			try {
				x.removeUsbPipeListener(pipeListener)
			} catch {
				case t: Throwable =>
					log.error(t, "Error while closing pipe")
			}
			try {
				x.close()
			} catch {
				case t: Throwable =>
					log.error(t, "Error while closing pipe")
			}

		}

		openedIfaces.foreach { x =>
			try x.release() catch {
				case t: Throwable =>
					log.error(t, "Error while closing interface")
			}
		}

		context stop self

		device.removeUsbDeviceListener(devListener)
	}
}
