package com.colingodsey.scalaminer.metrics

import akka.actor.{ActorLogging, Terminated, ActorRef, Actor}
import scala.concurrent.duration._
import com.colingodsey.scalaminer.hashing.Hashing

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

	case class MetricValue(metric: Metric, value: Long) extends Command

	case object Subscribe extends Command
	case object UnSubscribe extends Command

	val defaultTimeFrame = 15.minutes

	case class MetricsBlock(counters: Map[Metric, Counter] = Map.empty) {
		def updateCounter(metric: Metric)(f: Counter => Counter) = {
			val c = counters.getOrElse(metric, Counter(defaultTimeFrame))

			copy(counters = counters + (metric -> f(c)))
		}

		def purged = MetricsBlock(counters.map(pair => pair._1 -> pair._2.purged))

		def prettyMetric(metric: Metric) = {
			val c = counters.getOrElse(metric, Counter(defaultTimeFrame)).forTimeFrame(5.minutes)

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

	def metricsReceive: Receive = {
		case Subscribe => metricSubscribers += sender
		case UnSubscribe => metricSubscribers -= sender
		case x: Command => metricSubscribers.foreach(_ ! x)
	}
}

class MetricsActor extends Actor with ActorLogging {
	import MinerMetrics._

	implicit def ec = context.system.dispatcher

	case object PurgeTimer
	case object ReportTimer

	var metricMap: Map[ActorRef, MetricsBlock] = Map.empty
	var watching = Set[ActorRef]()

	context.system.scheduler.schedule(1.seconds, 1.minute, self, PurgeTimer)
	context.system.scheduler.schedule(1.seconds, 10.seconds, self, ReportTimer)

	def receive: Receive = {
		case ReportTimer =>
			log.info(metricMap.toString)
		case PurgeTimer =>
			metricMap = metricMap map { case (ref, block) =>
				ref -> block.purged
			}
		case MetricValue(metric, value) =>
			val block = metricMap.getOrElse(sender, MetricsBlock())

			metricMap += sender -> block.updateCounter(metric)(_.add(value))
		case x: Metric =>
			if(!watching(sender)) {
				watching += sender
				context watch sender
			}

			val block = metricMap.getOrElse(sender, MetricsBlock())

			metricMap += sender -> block.updateCounter(x)(_.add(1))
		case Terminated(ref) =>
			watching -= ref
			metricMap -= ref
	}
}

