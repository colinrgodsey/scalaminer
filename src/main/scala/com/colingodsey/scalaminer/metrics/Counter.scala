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

import scala.concurrent.duration._
import scala.collection.immutable.Queue
import com.colingodsey.scalaminer.utils._

case class Sample(value: Counter.MetricValue, time: Long = curTime)

object Counter {
	type MetricValue = Float
}

trait Counter {
	def started: Long
	def maxTimeFrame: FiniteDuration
	def sum: Double

	def purged: Counter
	def forTimeFrame(timeFrame: FiniteDuration): Counter
	def add(value: Counter.MetricValue): Counter

	def curTimeFrame = {
		val earlyFrame = (curTime - started).seconds
		if(earlyFrame < maxTimeFrame) earlyFrame
		else maxTimeFrame
	}

	//in seconds
	def rate = sum / curTimeFrame.toSeconds
}

case class ImmutableCounter(maxTimeFrame: FiniteDuration,
		samples: Queue[Sample] = Queue.empty,
		started: Long = curTime,
		total: Double = 0, sum: Double = 0) extends Counter {
	//require(maxTimeFrame > 0.seconds)

	def purged = {
		val minTime = curTime - maxTimeFrame.toSeconds
		val after = samples.dropWhile(_.time < minTime)
		val removed = samples.take(samples.length - after.length)
		val removedSum = removed.foldLeft(0.0)(_ + _.value)
		copy(samples = after, sum = sum - removedSum)
	}

	def forTimeFrame(timeFrame: FiniteDuration) = {
		//require(timeFrame <= maxTimeFrame)
		val tf = math.min(timeFrame.toSeconds, maxTimeFrame.toSeconds)

		copy(maxTimeFrame = tf.seconds).purged
	}

	def add(value: Counter.MetricValue) =
		copy(samples = samples :+ Sample(value),
			total = total + value, sum = sum + value)

	override def toString = s"Counter(frame = ${maxTimeFrame.toMinutes}m, " +
			s"sum = $sum, rate = $rate)"
}

/** Destructive version of counter */
class MutableCounter(var maxTimeFrame: FiniteDuration) extends Counter {
	val started: Long = curTime

	var total = 0.0
	var sum = 0.0
	val samples = scala.collection.mutable.Queue.empty[Sample]

	def add(value: Counter.MetricValue) = {
		samples enqueue Sample(value)
		total += value
		sum += value
		this
	}

	def purge() = {
		val minTime = curTime - maxTimeFrame.toSeconds
		//val after = samples.dropWhile(_.time < minTime)

		while(!samples.isEmpty && samples.front.time < minTime) {
			val head = samples.front

			sum -= head.value
			samples.dequeue()
		}
	}

	def forTimeFrame(timeFrame: FiniteDuration) = {
		//require(timeFrame <= maxTimeFrame)
		val tf = math.min(timeFrame.toSeconds, maxTimeFrame.toSeconds)

		maxTimeFrame = tf.seconds
		purged
	}

	def purged = { purge(); this }
}