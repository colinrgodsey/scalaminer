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

package com.colingodsey.scalaminer.metrics

import akka.actor.{ActorLogging, Terminated, ActorRef, Actor}
import scala.concurrent.duration._
import com.colingodsey.scalaminer.hashing.Hashing
import com.colingodsey.scalaminer.usb.USBIdentity
import com.colingodsey.scalaminer.{ScalaMiner, MinerIdentity}

object MinerMetrics {
	sealed trait Command
	sealed trait Metric extends Command

	case object WorkStarted extends Metric
	case object WorkSent extends Metric
	case object WorkCanceled extends Metric
	case object WorkTimeout extends Metric
	case object NonceFail extends Metric
	case object NonceSubmitted extends Metric
	case object NonceShort extends Metric
	case object NonceAccepted extends Metric
	case object NonceStratumLow extends Metric
	case object NonceStale extends Metric
	case object Hashes extends Metric
	case object DevicePoll extends Metric

	case class MetricValue(metric: Metric, value: Long) extends Command

	case object Subscribe extends Command
	case object UnSubscribe extends Command
	case class Identity(deviceType: String,
			deviceId: String, hashType: ScalaMiner.HashType) extends Command
	case object Identify extends Command

	val defaultTimeFrame = 15.minutes

	case class MetricsBlock(counters: Map[Metric, Counter] = Map.empty) {
		def updateCounter(metric: Metric)(f: Counter => Counter) = {
			val c = counters.getOrElse(metric, new MutableCounter(defaultTimeFrame))

			copy(counters = counters + (metric -> f(c)))
		}

		def purged = MetricsBlock(counters.map(pair => pair._1 -> pair._2.purged))

		def prettyMetric(metric: Metric) = {
			val c = counters.getOrElse(metric,
				new MutableCounter(defaultTimeFrame)).forTimeFrame(defaultTimeFrame)

			val rate = metric match {
				case Hashes => Hashing.prettyHashRate(c.rate, 1.second)
				case _ => "%.2f/s".format(c.rate)
			}

			val sum = "%.2f".format(c.sum)

			s"rate = $rate, sum = $sum"
		}

		def prettyPrintCounters =
			counters.map(pair => pair._1 -> prettyMetric(pair._1)).mkString("\t\n")


		override def toString = s"MetricsBlock(\n$prettyPrintCounters\n)"
	}
}

trait MetricsWorker extends Actor {
	import MinerMetrics._

	var metricSubscribers: Set[ActorRef] = Set.empty

	def identity: MinerIdentity
	def deviceName: String
	def hashType: ScalaMiner.HashType

	def metricIdentity = Identity(identity.toString,
		deviceName.toString, hashType)

	def metricsReceive: Receive = {
		case MinerMetrics.Identify =>
			sender ! metricIdentity
		case Subscribe =>
			metricSubscribers += sender
			context watch sender
			sender ! metricIdentity
		case UnSubscribe =>
			metricSubscribers -= sender
			context unwatch sender
		case x: Command => metricSubscribers.foreach(_ ! x)
		case Terminated(ref) if metricSubscribers(ref) =>
			metricSubscribers -= ref
	}
}

class MetricsActor extends AbstractMetricsActor {
	private implicit def ec = context.system.dispatcher

	context.system.scheduler.schedule(1.seconds, 1.minute, self, PurgeTimer)
	context.system.scheduler.schedule(1.seconds, 10.seconds, self, ReportTimer)

	def receive = metricsReceive orElse {
		case ReportTimer =>
			log.info(metricMap.toString)
		case PurgeTimer =>
			metricMap = metricMap map { case (ref, block) =>
				ref -> block.purged
			}
	}
}

trait AbstractMetricsActor extends Actor with ActorLogging {
	import MinerMetrics._

	private implicit def ec = context.system.dispatcher

	case object PurgeTimer
	case object ReportTimer

	var metricMap: Map[ActorRef, MetricsBlock] = Map.empty
	var watching = Set[ActorRef]()
	var identities: Map[ActorRef, Identity] = Map.empty

	def metricsReceive: Receive = {
		case x: Identity =>
			identities += sender -> x
		case MetricValue(metric, value) =>
			val block = metricMap.getOrElse(sender, MetricsBlock())

			metricMap += sender -> block.updateCounter(metric)(_.add(value))
		case x: Metric =>
			if(!watching(sender)) {
				watching += sender
				context watch sender
				sender ! MinerMetrics.Identify
			}

			val block = metricMap.getOrElse(sender, MetricsBlock())

			val timeFrame = x match {
				case DevicePoll => 3.minutes
				case _ => defaultTimeFrame
			}

			metricMap += sender -> block.updateCounter(x)(
				_.add(1).forTimeFrame(timeFrame))
		case Terminated(ref) if watching(ref) =>
			watching -= ref
			metricMap -= ref
	}
}

