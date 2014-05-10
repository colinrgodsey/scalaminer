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

package com.colingodsey.scalaminer.drivers

import scala.concurrent.duration._
import akka.actor._
import com.colingodsey.scalaminer._
import javax.xml.bind.DatatypeConverter
import akka.util.{Timeout, ByteString}
import akka.pattern._
import com.colingodsey.scalaminer.network.Stratum
import com.colingodsey.scalaminer.network.Stratum.MiningJob
import com.colingodsey.scalaminer.hashing.Hashing
import com.colingodsey.scalaminer.hashing.Hashing._
import com.colingodsey.scalaminer.network.Stratum.MiningJob
import com.colingodsey.scalaminer.utils._
import spray.json._
import scala.concurrent.Future
import com.lambdaworks.crypto.SCrypt
import spray.json.DefaultJsonProtocol._
import com.colingodsey.scalaminer.Nonce
import com.colingodsey.scalaminer.network.Stratum.MiningJob
import akka.event.LoggingAdapter
import com.colingodsey.scalaminer.metrics.MinerMetrics
import com.colingodsey.scalaminer.usb.UsbDeviceManager

object AbstractMiner {
	sealed trait Command

	case object CancelWork extends Command

	//responds with MinerIdentity
	case object Identify extends Command

	case object CheckInitTimeout extends Command

	case object StatSubscribe extends Command
	case object WorkTimeout extends Command

	def submitNonce(n: Nonce, job: Stratum.MiningJob, diff: Int,
			target: Seq[Byte], strat: ActorRef, isScrypt: Boolean,
			self: ActorRef, log: LoggingAdapter, difMask: BigInt) {
		val Nonce(work, nonce, extraNonce) = n

		val header = ScalaMiner.BufferType.empty ++
				work.data.take(76) ++ nonce

		val rev = reverseEndian(header)
		lazy val revArr = reverseEndian(header).toArray

		val hashBin = if(isScrypt) SCrypt.scrypt(revArr, revArr, 1024, 1, 1, 32).toSeq
		else doubleHash(rev)
		val hashInt = BigInt(Array(0.toByte) ++ hashBin.reverse)

		/*val hashes = if(isScrypt) hashesForDiffScrypt(1)
		else hashesForDiffSHA256(1)
		self ! MinerMetrics.MetricValue(MinerMetrics.Hashes, hashes)*/

		if(getInts(nonce).head == -1) {
			log.error("Nonce error!")
			self ! PoisonPill
			self ! MinerMetrics.NonceFail
		} else if(hashInt > (difMask / diff)) {
			log.debug("Share is below expected target " +
					(hashBin.toHex, target.toHex))
			self ! MinerMetrics.NonceShort
		} else {
			val hashes = if(isScrypt) hashesForDiffScrypt(diff)
			else hashesForDiffSHA256(diff)
			self ! MinerMetrics.MetricValue(MinerMetrics.Hashes, hashes)

			log.info("Submitting " + hashBin.toHex + " nonce " + nonce.toList)

			val ntimepos = 17 * 4 // 17th integer in datastring
			val noncepos = 19 * 4 // 19th integer in datastring
			val ntime = header.slice(ntimepos, ntimepos + 4)
			val nonceRead = header.slice(noncepos, noncepos + 4)

			val params = Seq(//stratumUser.toJson,
				job.id.toJson,
				extraNonce.toHex.toJson,
				ntime.toHex.toJson,
				nonceRead.toHex.toJson)

			self ! MinerMetrics.NonceSubmitted
			strat.tell(Stratum.SubmitStratumJob(params), self)
			//log.info("submitting.... to " + stratumRef)
		}

	}
}

object MetricsMiner {
	sealed trait Command

	sealed trait MinerMetric extends Command

	trait MetricCompanion[T] {
		def apply(x: Int): T
		lazy val one = apply(1)
	}

	object Started extends MetricCompanion[Started]
	case class Started(n: Int) extends MinerMetric

	object Failed extends MetricCompanion[Failed]
	case class Failed(n: Int) extends MinerMetric

	object Accepted extends MetricCompanion[Accepted]
	case class Accepted(n: Int) extends MinerMetric

	object Hashes extends MetricCompanion[Hashes]
	case class Hashes(n: Int) extends MinerMetric
}

trait MetricsMiner {

}

trait AbstractMiner extends Actor with ActorLogging with Stash {
	import AbstractMiner._
	
	implicit def system = context.system
	implicit def to = Timeout(10.seconds + (5000 * math.random).millis)

	private implicit def asmEc = context.system.dispatcher

	def hashType: ScalaMiner.HashType
	//TODO: replace this with IO(Stratum) !!!!!
	def workRefs: Map[ScalaMiner.HashType, ActorRef]
	def nonceTimeout: FiniteDuration

	def failDetect()

	def detectTimeout = 6.seconds

	def stratumRef = workRefs(if(isScrypt) ScalaMiner.Scrypt else ScalaMiner.SHA256)

	var targetBytes: Seq[Byte] = Nil
	var miningJob: Option[MiningJob] = None
	var difficulty = 1
	var extraNonceInfo: Option[Stratum.ExtraNonce] = None
	var extraNonceCounter = (0xFFFFF * math.random).toInt
	var subRef: ActorRef = context.system.deadLetters
	var finishedInit = false
	var cancelWorkTimer: Option[Cancellable] = None

	def isScrypt = hashType == ScalaMiner.Scrypt

	//TODO: ... should actually figure out stales...
	def submitStale = true

	def difMask = if(isScrypt) scryptDefaultTarget
	else bitcoinDefaultTarget

	def stratumSubscribe(ref: ActorRef) {
		if(subRef != context.system.deadLetters)
			stratumUnSubscribe(subRef)
		context watch ref
		ref ! Stratum.Subscribe(self)
		subRef = ref
	}

	def stratumUnSubscribe(ref: ActorRef) {
		if(subRef != context.system.deadLetters) {
			context unwatch ref
			ref ! Stratum.UnSubscribe(self)
			subRef = context.system.deadLetters
		}
	}

	def getExtraNonce = {
		val x = extraNonceCounter
		extraNonceCounter += (math.random * 6 + 1).toInt
		x
	}

	def resetWorkTimer() {
		cancelWorkTimer.foreach(_.cancel())
		cancelWorkTimer = Some apply context.system.scheduler.scheduleOnce(nonceTimeout,
			self, AbstractMiner.WorkTimeout)
	}

	def workReceive: Receive = {
		case AbstractMiner.WorkTimeout =>
			log.debug("Nonce timeout! Restarting work...")
			self ! AbstractMiner.CancelWork
			resetWorkTimer()
		case CheckInitTimeout =>
			if(!finishedInit) sys.error("Init timeout!")
		case Stratum.WorkAccepted =>
			log.debug("Share accepted!")
			self ! MinerMetrics.NonceAccepted
		case x @ Stratum.StratumError(21, msg) =>
			log.debug("stale share submitted")
			self ! MinerMetrics.NonceStale
		case x @ Stratum.StratumError(23, msg) =>
			self ! MinerMetrics.NonceStratumLow
			log.warning(msg)
		case x @ Stratum.StratumError(_, msg) =>
			self ! MinerMetrics.NonceFail
			log.warning(x.toString)
		case Stratum.Difficulty(d) =>
			difficulty = d
			targetBytes = ScalaMiner.BufferType.empty ++
					bintToBytes(difMask / difficulty, 32).reverse

			log.info("New target " + targetBytes.map(
				"%02x" format _).mkString + " diff " + difficulty)
		case x: Stratum.ExtraNonce => extraNonceInfo = Some(x)
		case x: MiningJob =>
			miningJob = Some(x)
			self ! AbstractMiner.CancelWork
			resetWorkTimer()
		case n: Nonce =>
			resetWorkTimer()
			//break off into async
			Future(submitNonce(n, miningJob.get, difficulty, targetBytes, stratumRef,
					isScrypt, self, log, difMask)) onFailure { case x: Throwable =>
				log.error(x, "Nonce submit failed!")
			}
	}

	def getWork(needsMidstate: Boolean) = for {
		mjInfo <- miningJob
		enInfo <- extraNonceInfo
		if !targetBytes.isEmpty
		en = getExtraNonce
	} yield Hashing.getWork(hashType, en, mjInfo, enInfo, targetBytes, needsMidstate)

	def getWorkAsync(needsMidstate: Boolean) = (for {
		mjInfo <- miningJob
		enInfo <- extraNonceInfo
		if !targetBytes.isEmpty
		en = getExtraNonce
	} yield Future(Hashing.getWork(hashType, en, mjInfo,
			enInfo, targetBytes, needsMidstate))) match {
		case None => Future.successful(None)
		case Some(x) => x.map(Some(_))
	}

	abstract override def preStart() {
		super.preStart()
		context.system.scheduler.scheduleOnce(
			detectTimeout, self, CheckInitTimeout)

		//send one hash right away so metrics wont be skewed for hashrate
		context.system.scheduler.scheduleOnce(
			100.millis, self,  MinerMetrics.Hashes)
	}

	abstract override def postStop() {
		super.postStop()

		if(!finishedInit) failDetect()
	}
}
