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

package com.colingodsey.scalaminer.drivers

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import akka.actor._
import com.colingodsey.scalaminer.usb._
import com.colingodsey.scalaminer._
import com.colingodsey.scalaminer.network.Stratum.{Job, MiningJob}
import com.colingodsey.scalaminer.utils._
import com.colingodsey.io.usb.Usb
import com.colingodsey.scalaminer.metrics.MinerMetrics
import com.colingodsey.scalaminer.usb.UsbDeviceActor.NonTerminated

class DualMinerScrypt(val deviceId: Usb.DeviceId,
		val workRefs: Map[ScalaMiner.HashType, ActorRef]) extends DualMinerFacet {
	def hashType = ScalaMiner.Scrypt

	def cts = false //dual mode

	def receive = {
		case NonTerminated(_) => stash()
	}

	override def preStart() {
		super.preStart()

		//getDevice(requestGolden())
		getDevice {
			goldNonceReceived = true

			postInit()
		}
	}
}

class DualMinerSHA256(val deviceId: Usb.DeviceId,
		val workRefs: Map[ScalaMiner.HashType, ActorRef]) extends DualMinerFacet {
	def hashType = ScalaMiner.SHA256

	def cts = false //dual mode

	finishedInit = true

	def receive = nonceReceive orElse {
		case Usb.BulkTransferResponse(_, _, `goldNonceRespId`) =>
			log.info("Gold nonce request sent")

			bufferRead(nonceInterface)
		case NonTerminated(_) => stash()
	}

	override def preStart() {
		super.preStart()

		getDevice(requestGolden())
	}
}

class DualMiner(val deviceId: Usb.DeviceId, val workRefs: Map[ScalaMiner.HashType, ActorRef])
		extends DualMinerFacet {
	import FTDI._
	import DualMiner._

	def hashType = ScalaMiner.Scrypt

	val ctsIrp = Usb.ControlIrp(TYPE_IN, SIO_POLL_MODEM_STATUS_REQUEST, 0, 1)
	val lowDtrIrp = Usb.ControlIrp(TYPE_OUT, SIO_SET_MODEM_CTRL_REQUEST, SIO_SET_DTR_LOW, 0)
	val highDtrIrp = Usb.ControlIrp(TYPE_OUT, SIO_SET_MODEM_CTRL_REQUEST, SIO_SET_DTR_HIGH, 0)

	var cts = false //true for LTC(T) only side of switch

	case object LowDtrPostDelay


	def initIrpsPre() {
		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_BAUD, 0xC068.toShort, 0x201).send
		deviceRef ! Usb.ReceiveControlIrp(ctsIrp, 2)
	}

	def postCTSInit() {
		if(isDualIface) {
			deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_BAUD, 0xC068.toShort, 0x202).send
			deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_PURGE_TX, INTERFACE_B).send
			deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_PURGE_RX, INTERFACE_B).send
		}

		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_PURGE_TX, INTERFACE_A).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, REQUEST_RESET, VALUE_PURGE_RX, INTERFACE_A).send
		deviceRef ! highDtrIrp.send
	}

	//TODO: init timeout
	def initReceive: Receive = metricsReceive orElse nonceReceive orElse {
		case Usb.ControlIrpResponse(`highDtrIrp`, _) =>
			context.system.scheduler.scheduleOnce(2.millis, deviceRef, lowDtrIrp.send)
		case Usb.ControlIrpResponse(`ctsIrp`, Right(data)) if data.length >= 2 =>
			log.info("Received ctsIrp " + data.toHex)
			//etiher 18,96 for dual, or 2,96 for ltc. 0x000x0002,96
			def status = (data(1) << 8) | (data(0) & 0xFF)
			def st = (status & 0x10) == 0
			cts = st //also sets isDualIface
			//println("cts", data.toList, buf.toList, st)

			postCTSInit()
		case Usb.ControlIrpResponse(`lowDtrIrp`, _) =>
			context.system.scheduler.scheduleOnce(2.millis, self, LowDtrPostDelay)
		case LowDtrPostDelay =>
			log.info("Received lowDtrIrp")
			val runCommand = if(isDualIface) {
				log.info("Setting 550M") //BTC / LTC
				Constants.pll_freq_550M_cmd
			} else {
				log.info("Setting 850M") //LTC only
				Constants.pll_freq_850M_cmd
			}

			if(isDualIface) {
				send(interfaceA, Constants.btc_gating: _*)
				send(interfaceA, Constants.btc_init: _*)
				send(interfaceB, Constants.ltc_init: _*)
				send(interfaceA, Constants.btc_open_nonce_unit: _*)
			} else {
				send(interfaceA, Constants.ltc_only_init: _*)
			}

			send(interfaceA, runCommand: _*)

			deviceRef ! Usb.ControlIrp(TYPE_OUT, SIO_SET_MODEM_CTRL_REQUEST,
				SIO_SET_RTS_HIGH, 2.toByte).send

			if(!isDualIface) {
				requestGolden()
			} else {
				finishedInit = true

				val scryptChild =  context.actorOf(Props(classOf[DualMinerScrypt], deviceId,
					workRefs), name = "scrypt")
				val shaChild = context watch context.actorOf(Props(classOf[DualMinerSHA256], deviceId,
					workRefs), name = "sha256")

				context watch scryptChild
				context watch shaChild

				metricSubscribers.foreach(x => scryptChild.tell(MinerMetrics.Subscribe, x))
				metricSubscribers.foreach(x => shaChild.tell(MinerMetrics.Subscribe, x))

				stratumUnSubscribe(stratumRef)
				context become {
					case x: MinerMetrics.Command =>
						scryptChild.tell(x, sender)
						shaChild.tell(x, sender)
					case x: UsbDeviceManager.FailedIdentify =>
						context.parent ! x
				}
			}

		case Usb.BulkTransferResponse(_, _, `goldNonceRespId`) =>
			log.info("Gold nonce request sent")

			startRead()
		case x @ Usb.ControlIrpResponse(`ctsIrp`, _) =>
			sys.error("Bad CTS IRP response " + x)
		case x @ Usb.ControlIrpResponse =>
			log.info("IRP Resp " + x)
		case NonTerminated(_) => stash()
	}

	def doInit() {
		log.info("Starting init")
		initIrpsPre()
	}

	def receive = initReceive

	override def preStart() {
		super.preStart()

		getDevice(doInit)
	}

	override def postStop() {
		super.postStop()

		deviceRef ! Usb.ControlIrp(TYPE_OUT, SIO_SET_MODEM_CTRL_REQUEST, SIO_SET_RTS_HIGH, 2).send
		deviceRef ! Usb.ControlIrp(TYPE_OUT, SIO_SET_MODEM_CTRL_REQUEST, SIO_SET_DTR_HIGH, 0).send
	}
}

case object DualMiner extends USBDeviceDriver {
	import USBUtils._

	sealed trait Command

	val dmTimeout = 100.millis

	def hashType = ScalaMiner.Scrypt

	lazy val identities: Set[USBIdentity] = Set(DM)

	case object Start extends Command
	case object StartWork extends Command

	trait ContextualCommand extends Command

	val btcNonceReadTimeout = 11152.millis
	val scryptNonceReadTimeout = btcNonceReadTimeout * 3

	case object DM extends USBIdentity {
		import UsbDeviceManager._

		def drv = DualMiner
		def idVendor = FTDI.vendor
		def idProduct = 0x6010
		def iManufacturer = ""
		def iProduct = "Dual RS232-HS"
		def config = 1
		def timeout = dmTimeout

		def isMultiCoin = true

		val interfaces = Set(
			Usb.Interface(0, Set(
				Usb.InputEndpoint(64, 1, 0),
				Usb.OutputEndpoint(64, 2, 0)
			)),
			Usb.Interface(1, Set(
				Usb.InputEndpoint(64, 3, 0),
				Usb.OutputEndpoint(64, 4, 0)
			))
		)

		override def usbDeviceActorProps(device: Usb.DeviceId,
				workRefs: Map[ScalaMiner.HashType, ActorRef]): Props =
			Props(classOf[DualMiner], device, workRefs)
	}
	
	object Constants {
		val DEFAULT_0_9V_PLL = 550
		val DEFAULT_0_9V_BTC = 60
		val DEFAULT_1_2V_PLL = 850
		val DEFAULT_1_2V_BTC = 0

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