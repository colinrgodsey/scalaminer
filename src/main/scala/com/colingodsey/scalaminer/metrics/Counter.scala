package com.colingodsey.scalaminer.metrics

import scala.concurrent.duration.{Deadline, FiniteDuration}
import scala.collection.immutable.Queue
import com.colingodsey.scalaminer.utils._

case class Sample(value: Metric.MetricValue, time: Long = curTime)

object Metric {
	type MetricValue = Float
}

case class Metric(maxTimeFrame: FiniteDuration,
		samples: Queue[Sample] = Queue.empty,
		started: Long = curTime, total: Double = 0) {
	def purged = {
		val minTime = curTime - maxTimeFrame.toSeconds
		copy(samples = samples.dropWhile(_.time < minTime))
	}

	def sum = samples.iterator.map(_.value).sum

	def forTimeFrame(timeFrame: FiniteDuration) = {
		require(timeFrame <= maxTimeFrame)

		copy(maxTimeFrame = timeFrame).purged
	}

	//in seconds
	def rate = sum / maxTimeFrame.toSeconds

	def add(value: Metric.MetricValue) =
		copy(samples = samples :+ Sample(value), total = total + value)


}