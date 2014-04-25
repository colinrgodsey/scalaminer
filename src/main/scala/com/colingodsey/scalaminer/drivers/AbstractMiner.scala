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
import scala.Some
import com.colingodsey.scalaminer.MinerStats
import akka.event.LoggingAdapter

object AbstractMiner {
	sealed trait Command

	case object CancelWork extends Command
	case object NonceFail extends Command
	case object NonceSubmitted extends Command
	case object NonceShort extends Command

	def submitNonce(n: Nonce, job: Stratum.MiningJob, diff: Int,
			target: Seq[Byte], strat: ActorRef, isScrypt: Boolean,
			self: ActorRef, log: LoggingAdapter, stratumUser: String,
			difMask: BigInt) {
		val Nonce(work, nonce, extraNonce) = n

		val header = ScalaMiner.BufferType.empty ++
				work.data.take(76) ++ nonce

		val rev = reverseEndian(header)
		lazy val revArr = reverseEndian(header).toArray

		val hashBin = if(isScrypt) SCrypt.scrypt(revArr, revArr, 1024, 1, 1, 32).toSeq
		else doubleHash(rev)
		val hashInt = BigInt(Array(0.toByte) ++ hashBin.reverse)

		if(getInts(nonce).head == -1) {
			log.error("Nonce error!")
			self ! PoisonPill
			self ! NonceFail
		} else if(hashInt > (difMask / diff)) {
			log.debug("Share is below expected target " +
					(hashBin.toHex, target.toHex))
			self ! NonceShort
		} else {
			self ! NonceSubmitted

			log.info("Submitting " + hashBin.toHex + " nonce " + nonce.toList)

			val ntimepos = 17 * 4 // 17th integer in datastring
			val noncepos = 19 * 4 // 19th integer in datastring
			val ntime = header.slice(ntimepos, ntimepos + 4)
			val nonceRead = header.slice(noncepos, noncepos + 4)

			val params = Seq(stratumUser.toJson,
				job.id.toJson,
				extraNonce.toHex.toJson,
				ntime.toHex.toJson,
				nonceRead.toHex.toJson)

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

	def isScrypt = hashType == ScalaMiner.Scrypt
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
		case NonceFail => failed += 1
		case NonceSubmitted => submitted += 1
		case NonceShort => short += 1
		case n: Nonce =>
			//break off into async
			Future(submitNonce(n, miningJob.get, difficulty, targetBytes, stratumRef,
					isScrypt, self, log, stratumUser, difMask)) onFailure { case x: Throwable =>
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
}
