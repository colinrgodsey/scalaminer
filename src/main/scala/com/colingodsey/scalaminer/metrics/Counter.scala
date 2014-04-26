package com.colingodsey.scalaminer.metrics

import scala.concurrent.duration._
import scala.collection.immutable.Queue
import com.colingodsey.scalaminer.utils._

case class Sample(value: Counter.MetricValue, time: Long = curTime)

object Counter {
	type MetricValue = Float
}

case class Counter(maxTimeFrame: FiniteDuration,
		samples: Queue[Sample] = Queue.empty,
		started: Long = curTime,
		total: Double = 0, sum: Double = 0) {
	//require(maxTimeFrame > 0.seconds)

	def curTimeFrame = {
		val earlyFrame = (curTime - started).seconds
		if(earlyFrame < maxTimeFrame) earlyFrame
		else maxTimeFrame
	}

	def purged = {
		val minTime = curTime - maxTimeFrame.toSeconds
		val after = samples.dropWhile(_.time < minTime)
		val removed = samples.take(samples.length - after.length)
		val removedSum = removed.foldLeft(0.0)(_ + _.value)
		copy(samples = after, sum = sum - removedSum)
	}

	//def sum = samples.iterator.map(_.value).sum

	def forTimeFrame(timeFrame: FiniteDuration) = {
		require(timeFrame <= maxTimeFrame)

		copy(maxTimeFrame = timeFrame).purged
	}

	//in seconds
	def rate = sum / curTimeFrame.toSeconds

	def add(value: Counter.MetricValue) =
		copy(samples = samples :+ Sample(value),
			total = total + value, sum = sum + value)

	override def toString = s"Counter(frame = ${maxTimeFrame.toMinutes}m, " +
			s"sum = $sum, rate = $rate)"
}
