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
import com.colingodsey.scalaminer._
import scala.collection.JavaConversions._
import spray.can.Http
import com.colingodsey.scalaminer.drivers.{AbstractMiner}
import com.colingodsey.scalaminer.utils._
import spray.httpx.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import com.colingodsey.scalaminer.ScalaMiner.HashType
import com.colingodsey.scalaminer.metrics.{MinerMetrics, MetricsWorker}
import com.typesafe.config.Config
import com.colingodsey.scalaminer.Nonce
import com.colingodsey.scalaminer.network.Stratum.MiningJob
import scala.Some
import spray.http.HttpRequest
import spray.routing.RequestContext

case object StratumProxy extends MinerIdentity with MinerDriver {

	case object JobTimeouts

	case class GetWork(needMidstate: Boolean)

	case class SubmitResult(res: String)

	override def drv: MinerDriver = this

	override def identities: Set[_ <: MinerIdentity] = Set.empty
}


class StratumProxy(override val stratumRef: ActorRef, config: Config)
		extends Actor with AbstractMiner with HttpService with MetricsWorker {
	import StratumProxy._
	def actorRefFactory = context
	implicit def ec = context.system.dispatcher

	def hashType: ScalaMiner.HashType = ScalaMiner.SHA256
	def workRefs: Map[HashType, ActorRef] = Map.empty

	def jobTimeout = config.getDur("job-timeout")
	def nonceTimeout = jobTimeout

	def identity: MinerIdentity = StratumProxy
	def deviceName = StratumProxy.toString

	val started = Deadline.now

	val jTimeoutTimer = context.system.scheduler.schedule(
		1.seconds, config.getDur("job-timeout-gc"), self, JobTimeouts)

	var merkleJobMap: Map[Seq[Byte], Stratum.Job] = Map.empty

	(IO(Http) ? Http.Bind(self, interface = config getString "host",
		port = config getInt "port")).pipeTo(self)

	stratumSubscribe(stratumRef)

	def proxyReceive: Receive = {
		case AbstractMiner.CancelWork =>

		case GetWork(needsMidstate) =>
			//workStarted += 1
			self ! MinerMetrics.WorkStarted
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
		case JobTimeouts =>
			val curTime = (System.currentTimeMillis / 1000)
			val expired = merkleJobMap filter { case (hash, job) =>
				(curTime - job.started).seconds > jobTimeout
			}

			if(!expired.isEmpty) {
				log.info(expired.size + " jobs timed out")
				merkleJobMap --= expired.keySet
				self ! MinerMetrics.MetricValue(MinerMetrics.WorkTimeout, expired.size)
			}

		case SubmitResult(str) =>
			val header = str.fromHex.take(80)
			val merkleHash = header.slice(36, 68)
			val noncepos = 19*4 // 19th integer in datastring
			val nonce = header.slice(noncepos, noncepos + 4)

			val job = merkleJobMap.get(merkleHash)

			if(job.isDefined) {
				val en = job.get.extranonce2
				self ! Nonce(job.get.work, nonce, en)
			} else {
				log.warning("Cannot find job for merkle hash!")
				//failed += 1
				self ! MinerMetrics.NonceFail
			}

		//val ints = getInts(header)
		//val hash
	}

	def receive: Receive = proxyReceive orElse workReceive orElse
			runRoute(route) orElse metricsReceive

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

		IO(Http) ! Tcp.Unbind
	}

	override def preStart() {
		super.preStart()

		finishedInit = true
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

	def failDetect() {
		sys.error("yea, this should never get called.")
	}
}