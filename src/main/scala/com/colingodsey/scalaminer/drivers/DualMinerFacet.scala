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
import com.colingodsey.scalaminer.metrics.{MetricsWorker, MinerMetrics}

trait DualMinerFacet extends USBDeviceActor with AbstractMiner with MetricsWorker  {
	import FTDI._
	import DualMiner._

	def nonceInterface: USBManager.Interface
	def cts: Boolean
	def isDualIface0: Boolean

	def identity = DualMiner.DM

	def device: UsbDevice

	def nonceTimeout = if(isScrypt) scryptNonceReadTimeout else btcNonceReadTimeout
	override def defaultTimeout = 1000.millis

	val nonceDelay = 75.millis

	val defaultReadSize: Int = 512

	override def isFTDI = true

	val calcTimer = context.system.scheduler.schedule(1.seconds, 3.seconds, self, CalcStats)

	lazy val interfaceA = identity.interfaces.filter(_.interface == 0).head
	lazy val interfaceB = identity.interfaces.filter(_.interface == 1).head

	def openBTCNonceUnits(units: Int)(after: => Unit) {
		val bin = Constants.btc_single_open

		sendDataCommands(nonceInterface, bin.take(units + 1))()
	}

	def sendCommands(interface: Interface, cmds: Seq[String])(then: => Unit) {
		val dats = cmds.filter(_ != "") map { cmd =>
			cmd.fromHex
		}

		sendDataCommands(interface, dats)(then)
	}

	def detect(after: => Unit)() {
		//opt_scrypt ? hex2bin(scrypt_bin, ltc_golden, sizeof(scrypt_bin)) : hex2bin(ob_bin, btc_golden, sizeof(ob_bin));

		val randomness = "FFFFFFFFFFFFFFFF"

		val cmd = (if(isScrypt) Constants.ltc_golden.head
		else Constants.btc_golden.head) + randomness

		val goldNonce = DatatypeConverter.parseHexBinary(
			if(isScrypt) Constants.ltc_golden_nonce
			else Constants.btc_golden_nonce)

		val readSize = 4

		val deadline = Deadline.now + identity.timeout + 14.second

		def postDetect() {
			if(!isScrypt) {
				sendCommands(nonceInterface, Constants.btc_close_nonce_unit) {
					if(!cts) openBTCNonceUnits(Constants.DEFAULT_0_9V_BTC)()
					else openBTCNonceUnits(Constants.DEFAULT_1_2V_BTC)()
				}
			}

			after
		}

		sendCommands(nonceInterface, Seq(cmd))()
		readDataUntilLength(nonceInterface, readSize) { dat =>
			if(dat.length == 4) {
				val nonce = dat.take(4).reverse
				log.info(("golden nonce " + nonce.toList).toString)
				require(nonce.toList == goldNonce.toList,
					nonce.toList + " != " + goldNonce.toList)
				runIrps(List(device.createUsbControlIrp(TYPE_OUT, SIO_SET_MODEM_CTRL_REQUEST,
					SIO_SET_RTS_HIGH, 2.toByte)))(_ => postDetect())
			} else {
				log.warning("nonceFail " + dat.toList)
				failDetect()
			}

		}
	}

	def getNonce(work: Work, job: Stratum.Job) {
		object TimedOut

		val eps = endpointsForIface(nonceInterface)

		val (ep, pipe) = eps.filter(_._1.isInput).head

		lazy val timeoutTime = context.system.scheduler.scheduleOnce(
			nonceTimeout, self, TimedOut)

		var buffer = ByteString.empty

		addUsbCommandToQueue(nonceInterface, ({ () =>
			//realize lazy timeoutTime val here
			timeoutTime.isCancelled
			pipe.asyncSubmit(defaultReadBuffer)
		}, {
			case AbstractMiner.CancelWork =>
				timeoutTime.cancel()
				self ! StartWork
				true
			case TimedOut =>
				//onReadTimeout()
				self ! MinerMetrics.WorkTimeout
				self ! StartWork
				true
			case x: UsbPipeDataEvent if x.getUsbPipe == pipe =>
				val dat = if(isFTDI) {
					x.getData.drop(2)
				} else x.getData

				buffer ++= dat

				if(buffer.length >= 4) {
					timeoutTime.cancel()
					self ! Nonce(work, buffer.take(4), job.extranonce2)
					self ! StartWork
					true
				} else {
					context.system.scheduler.scheduleOnce(nonceDelay) {
						//NOTE: this will execute outside of actor context
						//but the queue should still create seq exec for this context
						pipe.asyncSubmit(defaultReadBuffer)
					}
					false
				}
		}))
	}

	def normal: Actor.Receive = metricsReceive orElse usbBaseReceive orElse
			workReceive orElse {
		case x: UsbPipeDataEvent =>
			log.warning("Unhandled pipe data " + x)
		case x: UsbDeviceDataEvent =>
			log.warning("Unhandled device data " + x)

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

		case job: Stratum.Job =>
			val work @ Work(ht, data, midstate, target) = job.work
			val respondTo = sender

			self ! MinerMetrics.WorkStarted
			log.debug("getting work")

			val cmd = if(isScrypt) {
				require(target.length == 32)
				require(midstate.length == 32)
				//require(data.length == 80)

				val dat = ScalaMiner.BufferType.empty ++
						"55aa1f00".fromHex ++
						target ++ midstate ++ data.take(80) ++
						Seq[Byte](0xFF.toByte, 0xFF.toByte, 0xFF.toByte, 0xFF.toByte) ++
						Seq.fill[Byte](8)(0)

				require(dat.length == 160, dat.length + " != 160")

				dat
			} else {
				val obDat = ScalaMiner.BufferType.empty ++ midstate ++ Seq.fill[Byte](20)(0) ++
						data.drop(64).take(12)

				val dat = ScalaMiner.BufferType.empty ++
						"55aa0f00".fromHex ++
						Seq.fill[Byte](4)(0) ++ obDat.take(32) ++
						obDat.drop(52).take(12)

				require(dat.length == 52, dat.length + " != 52")

				dat
			}

			val initCmds = if(isDualIface0) Constants.ltc_init
			else Constants.ltc_restart

			if(isScrypt) sendDataCommands(nonceInterface, initCmds)()
			sendDataCommand(nonceInterface, cmd)()
			getNonce(work, job)
	}

	abstract override def preStart() {
		super.preStart()

		stratumSubscribe(stratumRef)
	}

	abstract override def postStop() {
		calcTimer.cancel()

		try scala.concurrent.blocking {
			device syncSubmit device.createUsbControlIrp(TYPE_OUT, SIO_SET_MODEM_CTRL_REQUEST,
				SIO_SET_RTS_HIGH, 2.toByte)
			device syncSubmit device.createUsbControlIrp(TYPE_OUT, SIO_SET_MODEM_CTRL_REQUEST,
				SIO_SET_DTR_HIGH, 0)
		} catch {
			case x: Throwable => log.error(x, "postStop failure")
		}

		super.postStop()
	}
}