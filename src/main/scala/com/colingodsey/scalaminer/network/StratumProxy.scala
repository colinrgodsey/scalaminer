package com.colingodsey.scalaminer.network

import scala.concurrent.duration._
import akka.actor._
import akka.pattern._
import spray.json._
import akka.io.{ IO, Tcp }
import java.net.{InetAddress, InetSocketAddress}
import akka.util.{Timeout, ByteString}
import DefaultJsonProtocol._
import spray.routing.{RequestContext, StandardRoute, HttpService}
import spray.http.{BasicHttpCredentials, HttpHeaders, HttpRequest}
import spray.httpx.unmarshalling._
import spray.httpx.marshalling._
import spray.http._
import javax.xml.bind.DatatypeConverter
import com.colingodsey.scalaminer.hashing.Hashing
import Hashing._
import com.colingodsey.scalaminer.network.Stratum.MiningJob
import scala.concurrent._
import com.colingodsey.scalaminer.{ScalaMiner, MinerStats, Work}
import scala.collection.JavaConversions._
import spray.can.Http
import com.colingodsey.scalaminer.drivers.AbstractMiner
import com.colingodsey.scalaminer.utils._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._

object StratumProxy {

	case object CalcStats

	case object JobTimeouts

	case class GetWork(needMidstate: Boolean)

	case class SubmitResult(res: String)

}


class StratumProxy(stratumRef: ActorRef) extends Actor with AbstractMiner with HttpService {
	import StratumProxy._
	def actorRefFactory = context
	implicit def ec = context.system.dispatcher

	def hashType: ScalaMiner.HashType = ScalaMiner.SHA256

	def jobTimeout = 5.minutes

	val started = Deadline.now

	val calcTimer = context.system.scheduler.schedule(
		1.seconds, 3.seconds, self, CalcStats)
	val jTimeoutTimer = context.system.scheduler.schedule(
		1.seconds, 45.seconds, self, JobTimeouts)

	var merkleJobMap: Map[Seq[Byte], Stratum.Job] = Map.empty

	(IO(Http) ? Http.Bind(self, interface = "0.0.0.0", port = 8099)).pipeTo(self)

	stratumSubscribe(stratumRef)

	def proxyReceive: Receive = {
		case AbstractMiner.CancelWork =>

		case GetWork(needsMidstate) =>
			workStarted += 1
			getWorkJson(needsMidstate) pipeTo sender
		case job: Stratum.Job =>
			merkleJobMap += job.merkle_hash -> job
		case x: MiningJob =>
			if(!submitStale) {
				log.info("Clearing current work")
				merkleJobMap = Map.empty
			}
			workReceive(x)
		case Status.Failure(e) => throw e
		case CalcStats =>
			log.info(minerStats.toString)
		case JobTimeouts =>
			val curTime = (System.currentTimeMillis / 1000)
			val expired = merkleJobMap filter { case (hash, job) =>
				(curTime - job.started).seconds > jobTimeout
			}

			if(!expired.isEmpty) {
				log.warning(expired.size + " jobs timed out")
				merkleJobMap --= expired.keySet
				timedOut += expired.size
			}

		case SubmitResult(str) =>
			val header = str.fromHex.take(80)

			val rev = reverseEndian(header)

			val hashBin = doubleHash(rev) //numerically read as little-endian
			//add an extra 0 here so that we dont make a negative on accident
			val hashInt = BigInt(Array(0.toByte) ++ hashBin.reverse)
			val blockHash = hashBin

			val merkleHash = header.slice(36, 68)

			val job = merkleJobMap.get(merkleHash)

			if(hashInt > (difMask / difficulty)) {
				log.debug("Share is below expected target " +
						(hashBin.toHex, targetBytes.toHex))
				short += 1
			} else if(job == None) {
				log.warning("Cannot find job for merkle hash!")
				failed += 1
			} else {
				submitted += 1

				log.info("Submitting " + blockHash.toHex)

				val en = job.get.extranonce2
				val ntimepos = 17*4 // 17th integer in datastring
				val noncepos = 19*4 // 19th integer in datastring
				val ntime = header.slice(ntimepos, ntimepos + 4)
				val nonce = header.slice(noncepos, noncepos + 4)

				val params = Seq("colinrgodsey.testtt2".toJson,
					job.get.id.toJson,
					en.toHex.toJson,
					ntime.toHex.toJson,
					nonce.toHex.toJson)

				stratumRef ! Stratum.SubmitStratumJob(params)
				//log.info("submitting.... to " + stratumRef)
			}

		//val ints = getInts(header)
		//val hash
	}

	def receive: Receive = proxyReceive orElse workReceive orElse runRoute(route)

	def getWorkJson(needsMidstate: Boolean): Future[Option[JsObject]] = {
		val fut = getWorkAsync(needsMidstate)

		val wFut = fut map { opt =>
			for {
				job <- opt
				work = job.work
				//_ = merkleJobMap += job.merkle_hash -> job
			} yield {
				val obj = JsObject(
					"data" -> work.data.toHex.toJson,
					"hash1" -> "00000000000000000000000000000000000000000000000000000000000000000000008000000000000000000000000000000000000000000000000000010000".toJson,
					"target" -> targetBytes.toHex.toJson
				)

				if(needsMidstate) JsObject(obj.fields + ("midstate" -> work.midstate.toHex.toJson))
				else obj
			}
		}

		fut foreach { opt =>
			opt.foreach(self ! _)
		}

		wFut
	}

	val respHeaders = List(
		//HttpHeaders.RawHeader("x-long-polling", "/lp"),
		HttpHeaders.RawHeader("x-roll-ntime", "1"),
		HttpHeaders.Connection("Keep-Alive")
	)

	//NOTE: the methods below may be run in another thread. Do not modify state!
	val route = path("favicon.ico")(complete(None: Option[String])) ~
	post(respondWithHeaders(respHeaders)(completeWithReq { req: HttpRequest =>
		val remIp = remIpFromReq(req)
		val reqJs = try req.entity.asString.asJson.asJsObject catch {
			case x: Throwable =>
				log.warning("iffy req")
				JsObject()
		}

		val miningExtensions = req.headers.filter(_.lowercaseName ==
			"x-mining-extensions").map(_.value.toLowerCase).toSet

		val auth = req.header[HttpHeaders.Authorization].flatMap { auth =>
			auth.credentials match {
				case BasicHttpCredentials(username, password) =>
					Some(username, password)
				case _ => None
			}
		}

		val id = reqJs.fields.get("id").getOrElse(JsNull)
		val method = reqJs.fields.get("method").map(_.convertTo[String]).getOrElse(
			req.uri.query.get("method").get)

		val needsMidstate = miningExtensions("midstate")

		val fut = Future(method match {
			case "getwork" =>
				val subs = reqJs.fields.get("params").map(
					_.convertTo[Seq[String]]).getOrElse(Nil)

				if(!subs.isEmpty) {
					subs.foreach(self ! SubmitResult(_))

					Future successful Some(JsObject(
						"error" -> JsNull,
						"id" -> id,
						"result" -> JsTrue
					))
				} else {
					log debug req.toString

					implicit def ec = context.system.dispatcher

					log.debug("New getwork request from " + remIp)

					(self ? GetWork(needsMidstate)).mapTo[Option[JsValue]].map(_.map { res =>
						JsObject(
							"error" -> JsNull,
							"id" -> id,
							"result" -> res
						)
					})
				}
			case x =>
				log.warning("Unknown method " + x)
				Future successful Some(JsObject(
					"error" -> JsArray(21.toJson, "Job not found".toJson, JsNull),
					"id" -> id,
					"result" -> JsNull
				))
		}).flatMap(x => x)

		fut.onFailure { case x: Throwable =>
			log.error(x, "Failed request " + req)
		}

		fut.map(_.map { x =>
			HttpEntity(ContentTypes.`application/json`, x.compactPrint)
		})
	})) ~ completeWithReq { req: HttpRequest =>
		log.info("Bad req " + req)
		"not found"
	}

	override def postStop() {
		super.postStop()
		calcTimer.cancel()
		jTimeoutTimer.cancel()

		IO(Http) ! Tcp.Unbind
	}

	def completeWithReq: (HttpRequest => ToResponseMarshallable) => StandardRoute =
		marshallable => new StandardRoute {
			def apply(ctx: RequestContext): Unit =
				ctx.complete(marshallable(ctx.request))
		}

	def remIpFromReq(req: HttpRequest) = for {
		addr <- req.header[HttpHeaders.`Remote-Address`]
		ip <- addr.address.toOption
	} yield ip.toString
}