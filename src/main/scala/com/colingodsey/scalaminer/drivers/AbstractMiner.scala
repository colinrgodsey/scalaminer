package com.colingodsey.scalaminer.drivers

import scala.concurrent.duration._
import akka.actor._
import com.colingodsey.scalaminer._
import javax.xml.bind.DatatypeConverter
import akka.util.{Timeout, ByteString}
import com.colingodsey.scalaminer.network.Stratum
import com.colingodsey.scalaminer.network.Stratum.MiningJob
import com.colingodsey.scalaminer.hashing.Hashing
import com.colingodsey.scalaminer.hashing.Hashing._
import com.colingodsey.scalaminer._
import com.colingodsey.scalaminer.network.Stratum.MiningJob
import com.colingodsey.scalaminer.utils._
import spray.json._
import scala.concurrent.Future
import com.lambdaworks.crypto.SCrypt
import spray.json.DefaultJsonProtocol._

object AbstractMiner {
	sealed trait Commands

	case object CancelWork extends Commands
}

trait AbstractMiner extends Actor with ActorLogging with Stash {
	implicit def system = context.system
	implicit def to = Timeout(10.seconds + (5000 * math.random).millis)

	private implicit def asmEc = context.system.dispatcher

	def hashType: ScalaMiner.HashType
	def workRefs: Map[ScalaMiner.HashType, ActorRef]

	def stratumRef = workRefs(if(isScrypt) ScalaMiner.Scrypt else ScalaMiner.SHA256)

	var targetBytes: Seq[Byte] = Nil
	var miningJob: Option[MiningJob] = None
	var difficulty = 1
	var extraNonceInfo: Option[Stratum.ExtraNonce] = None
	var extraNonceCounter = (0xFFFFF * math.random).toInt
	var subRef: ActorRef = context.system.deadLetters

	var short = 0
	var submitted = 0
	var workStarted = 0
	var failed = 0
	var accepted = 0
	var tooLow = 0
	var stale = 0
	var timedOut = 0

	def stratumUser = "colinrgodsey.testtt2d"

	final def isScrypt = hashType == ScalaMiner.Scrypt
	def submitStale = true

	def difMask = if(isScrypt) scryptDefaultTarget
	else bitcoinDefaultTarget

	def minerStats = MinerStats(started = workStarted, short = short,
		submitted = submitted, failed = failed, accepted = accepted,
		tooLow = tooLow, stale = stale, timeout = timedOut)

	def stratumSubscribe(ref: ActorRef) {
		if(subRef != context.system.deadLetters)
			stratumUnSubscribe(subRef)
		context watch ref
		ref ! Stratum.Subscribe(self)
		subRef = ref
	}

	def stratumUnSubscribe(ref: ActorRef) {
		require(subRef != context.system.deadLetters, "Not subscribed to " + ref)
		context unwatch ref
		ref ! Stratum.UnSubscribe(self)
		subRef = context.system.deadLetters
	}

	def getExtraNonce = {
		val x = extraNonceCounter
		extraNonceCounter += (math.random * 6 + 1).toInt
		x
	}

	def workReceive: Receive = {
		case Stratum.WorkAccepted =>
			log.debug("Share accepted!")
			accepted += 1
		case x @ Stratum.StratumError(21, msg) =>
			log.debug("stale share submitted")
			stale += 1
		case x @ Stratum.StratumError(23, msg) =>
			tooLow += 1
			log.warning(msg)
		case x @ Stratum.StratumError(_, msg) =>
			failed += 1
			log.warning(x.toString)
		case Stratum.Difficulty(d) =>
			difficulty = d
			targetBytes = ScalaMiner.BufferType.empty ++ bintToBytes(difMask / difficulty, 32).reverse

			log.info("New target " + targetBytes.map(
				"%02x" format _).mkString + " diff " + difficulty)
		case x: Stratum.ExtraNonce => extraNonceInfo = Some(x)
		case x: MiningJob =>
			miningJob = Some(x)
			self ! AbstractMiner.CancelWork
		case Nonce(work, nonce, extraNonce) =>
			val job = miningJob.get

			val header = ScalaMiner.BufferType.empty ++
					work.data.take(76) ++ nonce

			val rev = reverseEndian(header)
			lazy val revArr = reverseEndian(header).toArray

			val hashBin = if(isScrypt) SCrypt.scrypt(revArr, revArr, 1024, 1, 1, 32).toSeq
			else doubleHash(rev)
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

				val ntimepos = 17*4 // 17th integer in datastring
				val noncepos = 19*4 // 19th integer in datastring
				val ntime = header.slice(ntimepos, ntimepos + 4)
				val nonceRead = header.slice(noncepos, noncepos + 4)

				val params = Seq(stratumUser.toJson,
					job.id.toJson,
					extraNonce.toHex.toJson,
					ntime.toHex.toJson,
					nonceRead.toHex.toJson)

				stratumRef ! Stratum.SubmitStratumJob(params)
				//log.info("submitting.... to " + stratumRef)
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
}
