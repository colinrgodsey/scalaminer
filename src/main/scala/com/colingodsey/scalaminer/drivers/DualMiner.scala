package com.colingodsey.scalaminer.drivers

import javax.usb._
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import javax.usb.event._
import akka.pattern._
import spray.json._
import java.io.{ByteArrayOutputStream, DataOutputStream}
import akka.actor._
import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer._
import javax.xml.bind.DatatypeConverter
import akka.util.{Timeout, ByteString}
import com.colingodsey.scalaminer.network.Stratum
import com.colingodsey.scalaminer.network.Stratum.MiningJob
import com.colingodsey.scalaminer.hashing.Hashing
import com.colingodsey.scalaminer.hashing.Hashing._
import com.colingodsey.scalaminer.Nonce
import com.colingodsey.scalaminer.network.Stratum.MiningJob
import scala.Some
import com.colingodsey.scalaminer.Work
import com.colingodsey.scalaminer.utils._
import spray.json.DefaultJsonProtocol._
import com.lambdaworks.crypto.SCrypt
import com.colingodsey.scalaminer.usb.USBManager.Interface

class DualMiner(val device: UsbDevice, stratumRef: ActorRef)
		extends USBDeviceActor with AbstractMiner {
	import FTDI._
	import DualMiner._

	//def isScrypt = true
	def isDualIface0 = false //false(1) for LTC only, otherwise true(0)
	def identity = DualMiner.DM

	def nonceTimeout = 35.seconds

	val defaultReadSize: Int = 512

	//override def isScrypt = true
	override def isFTDI = true

	val calcTimer = context.system.scheduler.schedule(1.seconds, 3.seconds, self, CalcStats)

	stratumSubscribe(stratumRef)

	//lazy val interfaceDef = identity.interfaces.toSeq.sortBy(_.interface).head

	lazy val interfaceA = identity.interfaces.filter(_.interface == 0).head
	lazy val interfaceB = identity.interfaces.filter(_.interface == 1).head

	def miningInterface = if(isScrypt && isDualIface0) interfaceB else interfaceA
	//def nonceInterface = if(isScrypt && !isDualIface0) interfaceB else interfaceA
	def nonceInterface = miningInterface

	val dualInitIrps = if(isDualIface0) List(
		device.createUsbControlIrp(TYPE_OUT, REQUEST_BAUD, 0xC068.toShort, 0x202),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_PURGE_TX, INTERFACE_B),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_PURGE_RX, INTERFACE_B)
	) else Nil

	val initIrps = List(
		device.createUsbControlIrp(TYPE_OUT, REQUEST_BAUD, 0xC068.toShort, 0x201)
	) ::: dualInitIrps ::: List(
		device.createUsbControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_PURGE_TX, INTERFACE_A),
		device.createUsbControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_PURGE_RX, INTERFACE_A),
		device.createUsbControlIrp(TYPE_OUT, SIO_SET_MODEM_CTRL_REQUEST, SIO_SET_DTR_HIGH, 0)
	)

	val initIrps2 = List(
		device.createUsbControlIrp(TYPE_OUT, SIO_SET_MODEM_CTRL_REQUEST, SIO_SET_DTR_LOW, 0)
	)

	def getNonce(work: Work, job: Stratum.Job, timeout: FiniteDuration) {
		//object CancelNonce extends ContextualCommand
		//object TryRead extends ContextualCommand

		//context.system.scheduler.scheduleOnce(timeout, self, CancelNonce)
		//context.system.scheduler.scheduleOnce(2.millis, self, TryRead)

		/*var buffer = Vector[Byte]()

		def tryRead() {
			readData(nonceInterface) { read =>
				buffer ++= read
				if(buffer.length >= 4) {
					log.debug("Results " + buffer.take(4).toList)
					self ! (Nonce(work, buffer.take(4)) -> job)
					context.unbecome()
					unstashAll()
				} else
					context.system.scheduler.scheduleOnce(2.millis, self, TryRead)
			}
		}

		context.become(({
			case TryRead => tryRead()
			case CancelNonce =>
				timedOut += 1
				self ! StartWork
				context.unbecome()
				unstashAll()
			case x: MiningJob =>
				workReceive(x)
				self ! StartWork
				context.unbecome()
				unstashAll()
		}: Receive) orElse workReceive orElse {
			case _ => stash()
		}, discardOld = false)*/

		readDataUntilLength(nonceInterface, 4, nonceTimeout * 2,
				softFailTimeout = Some(nonceTimeout)) { dat =>
			if(dat.isEmpty) {
				timedOut += 1
				self ! StartWork
			} else self ! (Nonce(work, dat) -> job)
		}
	}

	def normal: Actor.Receive = usbBaseReceive orElse workReceive orElse {
		case x: UsbPipeDataEvent =>
			log.warning("Unhandled pipe data " + x)
		case x: UsbDeviceDataEvent =>
			log.warning("Unhandled device data " + x)

		case CalcStats =>
			log.info(minerStats.toString)

		case _: ContextualCommand =>
			log.debug("Uncaught ContextualCommand")

		case StartWork =>
			log.debug("startwork")
			getWork(true) match {
				case x if miningJob == None || x == None =>
					log.info("No work yet")
					context.system.scheduler.scheduleOnce(1.second, self, StartWork)
				case Some(job: Stratum.Job) =>
					//(self ? work).mapTo[Nonce].map(x => x -> job) pipeTo self
					self ! job
			}

		case (Nonce(work, nonce), job: Stratum.Job) if nonce.length < 4 =>
			log.debug("Bad nonce!")
			self ! StartWork
		case (Nonce(work, nonce0), job: Stratum.Job) =>
			val nonce = nonce0//.reverse
			val mjob = miningJob.get

			val header = ScalaMiner.BufferType.empty ++
					work.data.take(76) ++ nonce

			val rev = reverseEndian(header).toArray

			val hashBin = SCrypt.scrypt(rev, rev, 1024, 1, 1, 32)
			val hashInt = BigInt(Array(0.toByte) ++ hashBin.reverse)

			if(getInts(nonce).head == -1) {
				log.error("Nonce error!")
				context stop self
			} else if(hashInt > (difMask / difficulty)) {
				log.debug("Share is below expected target " +
						(hashBin.toHex, targetBytes.toHex))
				short += 1
			} else {
				submitted += 1

				log.info("Submitting " + hashBin.toHex + " nonce " + nonce.toList)

				val en = job.extranonce2
				val ntimepos = 17*4 // 17th integer in datastring
				val noncepos = 19*4 // 19th integer in datastring
				val ntime = header.slice(ntimepos, ntimepos + 4)
				val nonceRead = header.slice(noncepos, noncepos + 4)

				val params = Seq("colinrgodsey.testtt2d".toJson,
					mjob.id.toJson,
					en.toHex.toJson,
					ntime.toHex.toJson,
					nonceRead.toHex.toJson)

				stratumRef ! Stratum.SubmitStratumJob(params)
				//log.info("submitting.... to " + stratumRef)
			}

			self ! StartWork

		case job: Stratum.Job =>
			val work @ Work(ht, data, midstate, target) = job.work
			val respondTo = sender

			workStarted += 1
			log.debug("getting work")

			val cmd = if(isScrypt) {
				require(target.length == 32)
				require(midstate.length == 32)
				//require(data.length == 80)

				val dat = ScalaMiner.BufferType.empty ++
						Seq[Byte](0x55.toByte, 0xaa.toByte, 0x1f.toByte, 0x00.toByte) ++
						target ++ midstate ++ data.take(80) ++
						Seq[Byte](0xFF.toByte, 0xFF.toByte, 0xFF.toByte, 0xFF.toByte) ++
						Seq.fill[Byte](8)(0)

				require(dat.length == 160, dat.length + " != 160")

				dat
			} else {
				val obDat = ScalaMiner.BufferType.empty ++ midstate ++ Seq.fill[Byte](20)(0) ++
						data.drop(64).take(12)

				val dat = ScalaMiner.BufferType.empty ++
						Seq[Byte](0x55.toByte, 0xaa.toByte, 0x0f.toByte, 0x00.toByte) ++
						Seq.fill[Byte](4)(0) ++ obDat.take(32) ++ Seq.fill[Byte](8)(0) ++
						obDat.drop(52)

				require(dat.length == 52, dat.length + " != 52")

				dat
			}

			val initCmds = if(isDualIface0) Constants.ltc_init
			else Constants.ltc_restart

			sendDataCommands(miningInterface, initCmds)()
			sendDataCommand(nonceInterface, cmd)()
			getNonce(work, job, nonceTimeout)

	}

	def getCTSSetFreq {
		val ctsIrp = device.createUsbControlIrp(TYPE_IN, SIO_POLL_MODEM_STATUS_REQUEST,
			0, 1)

		val buf = Array.fill[Byte](2)(0)
		ctsIrp.setData(buf)

		//etiher 18,96 for dual, or 2,96 for ltc. 0x000x0002,96
		runIrps(List(ctsIrp)) { data =>
			def st = ((data(1) << 8) | (data(0) & 0xFF) & 0x10) == 0
			//println("cts", data.toList, buf.toList, st)

			val runCommand = if( /*data.length == 2 && */ st)
				Constants.pll_freq_550M_cmd
			else Constants.pll_freq_850M_cmd

			sendDataCommands(interfaceA, runCommand) {
				if(isDualIface0)
					sendDataCommands(interfaceA, Constants.btc_open_nonce_unit) {
						detect()
					}
				else detect()
			}
		}


	}

	def detect() {
		//opt_scrypt ? hex2bin(scrypt_bin, ltc_golden, sizeof(scrypt_bin)) : hex2bin(ob_bin, btc_golden, sizeof(ob_bin));

		val randomness = "FFFFFFFFFFFFFFFF"

		val cmd = (if(isScrypt) Constants.ltc_golden.head
		else Constants.btc_golden.head) + randomness

		val goldNonce = DatatypeConverter.parseHexBinary(
			if(isScrypt) Constants.ltc_golden_nonce
			else Constants.btc_golden_nonce)

		val readSize = 4

		val deadline = Deadline.now + identity.timeout + 14.second

		sendCommands(nonceInterface, Seq(cmd))()
		readDataUntilLength(nonceInterface, readSize) { dat =>
			if(dat.length == 4) {
				val nonce = dat.take(4).reverse
				log.info(("golden nonce " + nonce.toList, dat.toList).toString)
				require(nonce.toList == goldNonce.toList,
					nonce.toList + " != " + goldNonce.toList)
				postInit
			} else {
				log.warning("nonceFail " + dat.toList)
				failDetect
			}
		}
	}

	def postInit {
		context become normal
		unstashAll()
		self ! StartWork
	}

	def receive = usbBaseReceive orElse {
		case Start =>
			log.info("Starting miner actor for " + (device -> identity))

			runIrps(initIrps) { _ =>
				sleep(2.millis) {
					runIrps(initIrps2) { _ =>
						sleep(2.millis) {
							val cmds = if(isDualIface0) {
								sendDataCommands(interfaceA,
										Constants.btc_gating ++ Constants.btc_init) {
									sendDataCommands(interfaceB,
										Constants.ltc_init)(getCTSSetFreq)
								}
							} else {
								sendDataCommands(interfaceA,
									Constants.ltc_only_init)(getCTSSetFreq)
							}
						}
					}
				}
			}
		case _ => stash()
	}

	def sendCommands(interface: Interface, cmds: Seq[String])(then: => Unit) {
		val dats = cmds.filter(_ != "") map { cmd =>
			cmd.fromHex
			//Array.fill[Byte](cmd.length / 2)(0xFF.toByte)
		}

		sendDataCommands(interface, dats)(then)
	}


	override def preStart() {
		super.preStart()

		self ! Start
	}

	override def postStop() {
		super.postStop()
		calcTimer.cancel()
	}

	/*
	if (state)
	usb_val = SIO_SET_DTR_LOW;
else
	usb_val = SIO_SET_DTR_HIGH;
if (libusb_control_transfer(dualminer->usbdev->handle, FTDI_TYPE_OUT, SIO_SET_MODEM_CTRL_REQUEST, usb_val, 0, NULL, 0, 200) < 0)
	 */

}

case object DualMiner extends USBDeviceDriver {
	import USBUtils._

	val dmTimeout = 100.millis

	def hashType = ScalaMiner.Scrypt

	lazy val identities: Set[USBIdentity] = Set(DM)

	case object Start
	case object StartWork
	case object CalcStats

	trait ContextualCommand

	case object DM extends USBIdentity {
		import USBManager._

		def drv = DualMiner
		def idVendor = FTDI.vendor
		def idProduct = 0x6010
		def iManufacturer = ""
		def iProduct = "Dual RS232-HS"
		def config = 1
		def timeout = dmTimeout

		val interfaces = Set(
			Interface(0, Set(
				InputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 1, 0),
				OutputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 2, 0)
			)),
			Interface(1, Set(
				InputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 3, 0),
				OutputEndpoint(UsbConst.ENDPOINT_TYPE_BULK, 64, 4, 0)
			))
		)

		override def usbDeviceActorProps(device: UsbDevice, workRef: ActorRef): Props =
			Props(classOf[DualMiner], device, workRef)
	}
	
	object Constants {
		val pll_freq_1200M_cmd = Seq(
			"55AAEF000500E085",
			"55AA0FFFB02800C0"
		).map(_.fromHex)

		val pll_freq_1100M_cmd = Seq(
			"55AAEF0005006085",
			"55AA0FFF4C2500C0"
		).map(_.fromHex)

		val pll_freq_1000M_cmd = Seq(
			"55AAEF000500E084",
			"55AA0FFFE82100C0"
		).map(_.fromHex)

		val pll_freq_950M_cmd = Seq(
			"55AAEF000500A084",
			"55AA0FFF362000C0"
		).map(_.fromHex)

		val pll_freq_900M_cmd = Seq(
			"55AAEF0005006084",
			"55AA0FFF841E00C0"
		).map(_.fromHex)

		val pll_freq_850M_cmd = Seq(
			"55AAEF0005002084",
			"55AA0FFFD21C00C0"
		).map(_.fromHex)

		val pll_freq_800M_cmd = Seq(
			"55AAEF000500E083",
			"55AA0FFF201B00C0"
		).map(_.fromHex)

		val pll_freq_750M_cmd = Seq(
			"55AAEF000500A083",
			"55AA0FFF6E1900C0"
		).map(_.fromHex)

		val pll_freq_700M_cmd = Seq(
			"55AAEF0005006083",
			"55AA0FFFBC1700C0"
		).map(_.fromHex)

		val pll_freq_650M_cmd = Seq(
			"55AAEF0005002083",
			"55AA0FFF0A1600C0"
		).map(_.fromHex)

		val pll_freq_600M_cmd = Seq(
		
			"55AAEF000500E082",
			"55AA0FFF581400C0"
		).map(_.fromHex)

		val pll_freq_550M_cmd = Seq(
		
			"55AAEF000500A082",
			"55AA0FFFA61200C0"
		).map(_.fromHex)

		val pll_freq_500M_cmd = Seq(
		
			"55AAEF0005006082",
			"55AA0FFFF41000C0"
		).map(_.fromHex)

		val pll_freq_400M_cmd = Seq(
		
			"55AAEF000500E081",
			"55AA0FFF900D00C0"
		).map(_.fromHex)

		val btc_gating = Seq(
		
			"55AAEF0200000000",
			"55AAEF0300000000",
			"55AAEF0400000000",
			"55AAEF0500000000",
			"55AAEF0600000000"
		).map(_.fromHex)

		val btc_single_open = Seq(
		
			"55AAEF0200000001",
			"55AAEF0200000003",
			"55AAEF0200000007",
			"55AAEF020000000F",
			"55AAEF020000001F",
			"55AAEF020000003F",
			"55AAEF020000007F",
			"55AAEF02000000FF",
			"55AAEF02000001FF",
			"55AAEF02000003FF",
			"55AAEF02000007FF",
			"55AAEF0200000FFF",
			"55AAEF0200001FFF",
			"55AAEF0200003FFF",
			"55AAEF0200007FFF",
			"55AAEF020000FFFF",
			"55AAEF020001FFFF",
			"55AAEF020003FFFF",
			"55AAEF020007FFFF",
			"55AAEF02000FFFFF",
			"55AAEF02001FFFFF",
			"55AAEF02003FFFFF",
			"55AAEF02007FFFFF",
			"55AAEF0200FFFFFF",
			"55AAEF0201FFFFFF",
			"55AAEF0203FFFFFF",
			"55AAEF0207FFFFFF",
			"55AAEF020FFFFFFF",
			"55AAEF021FFFFFFF",
			"55AAEF023FFFFFFF",
			"55AAEF027FFFFFFF",
			"55AAEF02FFFFFFFF",
			"55AAEF0300000001",
			"55AAEF0300000003",
			"55AAEF0300000007",
			"55AAEF030000000F",
			"55AAEF030000001F",
			"55AAEF030000003F",
			"55AAEF030000007F",
			"55AAEF03000000FF",
			"55AAEF03000001FF",
			"55AAEF03000003FF",
			"55AAEF03000007FF",
			"55AAEF0300000FFF",
			"55AAEF0300001FFF",
			"55AAEF0300003FFF",
			"55AAEF0300007FFF",
			"55AAEF030000FFFF",
			"55AAEF030001FFFF",
			"55AAEF030003FFFF",
			"55AAEF030007FFFF",
			"55AAEF03000FFFFF",
			"55AAEF03001FFFFF",
			"55AAEF03003FFFFF",
			"55AAEF03007FFFFF",
			"55AAEF0300FFFFFF",
			"55AAEF0301FFFFFF",
			"55AAEF0303FFFFFF",
			"55AAEF0307FFFFFF",
			"55AAEF030FFFFFFF",
			"55AAEF031FFFFFFF",
			"55AAEF033FFFFFFF",
			"55AAEF037FFFFFFF",
			"55AAEF03FFFFFFFF",
			"55AAEF0400000001",
			"55AAEF0400000003",
			"55AAEF0400000007",
			"55AAEF040000000F",
			"55AAEF040000001F",
			"55AAEF040000003F",
			"55AAEF040000007F",
			"55AAEF04000000FF",
			"55AAEF04000001FF",
			"55AAEF04000003FF",
			"55AAEF04000007FF",
			"55AAEF0400000FFF",
			"55AAEF0400001FFF",
			"55AAEF0400003FFF",
			"55AAEF0400007FFF",
			"55AAEF040000FFFF",
			"55AAEF040001FFFF",
			"55AAEF040003FFFF",
			"55AAEF040007FFFF",
			"55AAEF04000FFFFF",
			"55AAEF04001FFFFF",
			"55AAEF04003FFFFF",
			"55AAEF04007FFFFF",
			"55AAEF0400FFFFFF",
			"55AAEF0401FFFFFF",
			"55AAEF0403FFFFFF",
			"55AAEF0407FFFFFF",
			"55AAEF040FFFFFFF",
			"55AAEF041FFFFFFF",
			"55AAEF043FFFFFFF",
			"55AAEF047FFFFFFF",
			"55AAEF04FFFFFFFF",
			"55AAEF0500000001",
			"55AAEF0500000003",
			"55AAEF0500000007",
			"55AAEF050000000F",
			"55AAEF050000001F",
			"55AAEF050000003F",
			"55AAEF050000007F",
			"55AAEF05000000FF",
			"55AAEF05000001FF",
			"55AAEF05000003FF",
			"55AAEF05000007FF",
			"55AAEF0500000FFF",
			"55AAEF0500001FFF",
			"55AAEF0500003FFF",
			"55AAEF0500007FFF",
			"55AAEF050000FFFF",
			"55AAEF050001FFFF",
			"55AAEF050003FFFF",
			"55AAEF050007FFFF",
			"55AAEF05000FFFFF",
			"55AAEF05001FFFFF",
			"55AAEF05003FFFFF",
			"55AAEF05007FFFFF",
			"55AAEF0500FFFFFF",
			"55AAEF0501FFFFFF",
			"55AAEF0503FFFFFF",
			"55AAEF0507FFFFFF",
			"55AAEF050FFFFFFF",
			"55AAEF051FFFFFFF",
			"55AAEF053FFFFFFF",
			"55AAEF057FFFFFFF",
			"55AAEF05FFFFFFFF",
			"55AAEF0600000001",
			"55AAEF0600000003",
			"55AAEF0600000007",
			"55AAEF060000000F",
			"55AAEF060000001F",
			"55AAEF060000003F",
			"55AAEF060000007F",
			"55AAEF06000000FF",
			"55AAEF06000001FF",
			"55AAEF06000003FF",
			"55AAEF06000007FF",
			"55AAEF0600000FFF",
			"55AAEF0600001FFF",
			"55AAEF0600003FFF",
			"55AAEF0600007FFF",
			"55AAEF060000FFFF",
			"55AAEF060001FFFF",
			"55AAEF060003FFFF",
			"55AAEF060007FFFF",
			"55AAEF06000FFFFF",
			"55AAEF06001FFFFF",
			"55AAEF06003FFFFF",
			"55AAEF06007FFFFF",
			"55AAEF0600FFFFFF",
			"55AAEF0601FFFFFF",
			"55AAEF0603FFFFFF",
			"55AAEF0607FFFFFF",
			"55AAEF060FFFFFFF",
			"55AAEF061FFFFFFF",
			"55AAEF063FFFFFFF",
			"55AAEF067FFFFFFF",
			"55AAEF06FFFFFFFF"
		).map(_.fromHex)

		val ltc_only_init = Seq(
		
			"55AAEF0200000000",
			"55AAEF0300000000",
			"55AAEF0400000000",
			"55AAEF0500000000",
			"55AAEF0600000000",
			"55AAEF3040000000",
			"55AA1F2810000000",
			"55AA1F2813000000",
			//850M
			"55AAEF0005002084",
			"55AA0FFFD21C00C0"
			//800M
			//"55AAEF000500E083",
			//"55AA0FFF201B00C0",
		).map(_.fromHex)

		val ltc_restart = Seq(
		
			"55AA1F2810000000",
			"55AA1F2813000000"
		).map(_.fromHex)

		val btc_init = Seq(
		
			"55AAEF3020000000",
			"55AA1F2817000000"
		).map(_.fromHex)

		val ltc_init = Seq(
		
			"55AA1F2814000000",
			"55AA1F2817000000"
		).map(_.fromHex)

		val btc_open_nonce_unit = Seq(
		
			"55AAEF0204000000"
		).map(_.fromHex)

		val btc_close_nonce_unit = Seq(
		
			"55AAEF0200000000"
		)

		//needs padding to 64?
		val btc_golden = Seq("55aa0f00a08701004a548fe471fa3a9a1371144556c3f64d2500b4826008fe4bbf7698c94eba7946ce22a72f4f6726141a0b3287")

		//needs to be badded to 160?
		val ltc_golden = Seq("55aa1f00000000000000000000000000000000000000000000000000aaaaaaaa711c0000603ebdb6e35b05223c54f8155ac33123006b4192e7aafafbeb9ef6544d2973d700000002069b9f9e3ce8a6778dea3d7a00926cd6eaa9585502c9b83a5601f198d7fbf09be9559d6335ebad363e4f147a8d9934006963030b4e54c408c837ebc2eeac129852a55fee1b1d88f6000c050000000600")

		val btc_golden_nonce = "000187a2"
		val ltc_golden_nonce = "00050cdd"
		val btc_golden_nonce_val = 0x000187a2
		val ltc_golden_nonce_val = 0x00050cdd

	}
}