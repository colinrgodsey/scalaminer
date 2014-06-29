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

package com.colingodsey.scalaminer.ui

import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import akka.actor._
import akka.pattern._
import spray.json._
import scala.concurrent.Await
import com.colingodsey.scalaminer.metrics.{MinerMetrics, AbstractMetricsActor}
import spray.routing.{RequestContext, StandardRoute, HttpService}
import spray.httpx.unmarshalling._
import spray.httpx.marshalling._
import spray.http._
import HttpCharsets._
import MediaTypes._
import spray.json.DefaultJsonProtocol._
import akka.io.IO
import spray.can.Http
import akka.util.Timeout
import spray.http.HttpHeaders.RawHeader

object MetricsUI extends App {
	val classLoader = getClass.getClassLoader
	val config = ConfigFactory.load(classLoader)
	lazy val smConfig = config.getConfig("com.colingodsey.scalaminer.ui")
	implicit val system = ActorSystem("scalaminer-ui", config, classLoader)

	private implicit def ec = system.dispatcher

	val remotePath = "akka.tcp://scalaminer@192.168.0.170:2552/user/"
	val usbPath = remotePath + "usb-manager"
	val proxyPath = remotePath + "stratum-proxy"

	val uiRef = system.actorOf(Props[MetricsUI])

}


class MetricsUI extends AbstractMetricsActor with HttpService {
	private implicit def ec = context.system.dispatcher
	implicit def system = context.system
	implicit def actorRefFactory: ActorRefFactory = context
	implicit def to = Timeout(10.seconds)

	context.system.scheduler.schedule(1.seconds, 1.minute, self, PurgeTimer)
	context.system.scheduler.schedule(1.seconds, 10.seconds, self, ReportTimer)

	val mngrSel = context.actorSelection(MetricsUI.usbPath)

	context.actorSelection(MetricsUI.proxyPath) ! MinerMetrics.Subscribe

	mngrSel ! Identify()

	(IO(Http) ? Http.Bind(self, interface = "localhost",
		port = 8077)).pipeTo(self)

	val route = path("metrics.json") {
		respondWithHeaders(RawHeader("Access-Control-Allow-Origin", "*"),
			RawHeader("Access-Control-Allow-Methods", "GET,HEAD,POST,OPTIONS,TRACE"),
			RawHeader("Access-Control-Allow-Headers", "*, " +
					"X-Requested-With, Content-Type, Accept"))(complete {
			val res = metricMap map { case (ref, block) =>
				val row = block.counters.map { case (metric, counter0) =>
					val counter = counter0.purged
					metric.toString.toLowerCase -> Map(
						"rate" -> counter.rate.toJson
					).toJson
				}

				val extras = identities.get(ref) match {
					case Some(MinerMetrics.Identity(typ, id, hash)) => Map(
						"type" -> typ.toString.toJson,
						"id" -> id.toJson,
						"hash" -> hash.toString.toJson
					)
					case _ => Map()
				}

				ref.toString -> (row ++ extras).toJson
			}

			HttpEntity(
				contentType = ContentType(`application/json`, `UTF-8`),
				string = res.toJson.compactPrint
			)
		})
	}

	def receive = metricsReceive orElse runRoute(route) orElse {
		case Status.Failure(e) => throw e
		case ActorIdentity(_, Some(ref)) =>
			log.info("Get remote ref " + ref)
			ref ! MinerMetrics.Subscribe
		case ReportTimer =>
			//log.info(metricMap.toString)
		case PurgeTimer =>
			metricMap = metricMap map { case (ref, block) =>
				ref -> block.purged
			}
	}
}