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
import com.colingodsey.scalaminer.hashing.Hashing
import com.colingodsey.scalaminer.{ScalaMiner, Work}
import com.colingodsey.scalaminer.utils._
import com.colingodsey.scalaminer.usb.UsbDeviceActor.NonTerminated

object StratumPool {
	sealed trait Command

	case class AddConnection(con: Stratum.Connection)

	case object MaybeChangeConnection extends Command
}

class StratumPool(hashType: ScalaMiner.HashType)
		extends Actor with ActorLogging with Stash {
	import Stratum._
	import StratumPool._

	def conRetryInterval = 2.seconds

	override val supervisorStrategy =
		OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 2.seconds) {
			//case _: org.usb4java.LibUsbException    => Stop
			case _: Exception                       => SupervisorStrategy.Restart
		}

	var stratumConnections: Set[Connection] = Set.empty
	var stratumRefs: Map[Connection, ActorRef] = Map.empty
	var conLastFailed: Map[Connection, Deadline] = Map.empty
	//list of receivers to the connection they're using
	//var stratumAssoc: Map[ActorRef, Connection]

	var difficulties: Map[Connection, Difficulty] = Map.empty
	var jobs: Map[Connection, MiningJob] = Map.empty
	var extraNonces: Map[Connection, ExtraNonce] = Map.empty
	var subscribers = Set[ActorRef]()
	var currentConnection: Option[Connection] = None

	def stratumRefsRev = stratumRefs.map(_.swap)

	private implicit def ec = context.system.dispatcher

	def canRetry(con: Connection) = conLastFailed.getOrElse(con,
		Deadline.now - 10000.seconds).timeLeft < (-conRetryInterval)

	context.system.scheduler.schedule(10.seconds, 1.minute, self, MaybeChangeConnection)

	def getStratumRef(con: Connection) = stratumRefs get con match {
		//create connection if non-existent and retry allowed
		case None if canRetry(con) =>
			val ref = context actorOf Props(classOf[StratumActor], con, hashType)

			log.info(s"Creating ref $ref for con $con")

			ref ! Subscribe(self)

			stratumRefs += con -> ref
			Some(ref)
		case Some(x) => Some(x)
		case None => None
	}

	def activeConnections = for {
		con <- stratumConnections
		ref <- getStratumRef(con)
		if jobs contains con
	} yield con

	//find highest active prio, and round robin that prio group
	def pickConnection = if(activeConnections.isEmpty) None
	else {
		val highestPrio = activeConnections.toSeq.sortBy(_.priority).reverse

		val prio = highestPrio.head.priority

		//take same priority, random from that
		val ofPrio = highestPrio.filter(_.priority == prio)
		val totalWeight = ofPrio.map(_.weight).sum
		val sel = math.random * totalWeight

		def findSel(seq: Seq[Connection], x: Double): Connection =
			if(seq.length == 1) seq.head
			else if(x < seq.head.weight) seq.head
			else findSel(seq.tail, x - seq.head.weight)

		Some(findSel(ofPrio, sel))
	}

	def checkConnections() {
		if(currentConnection == None) {
			currentConnection = pickConnection
			subscribers foreach updateSubscriber
		}
	}

	def updateSubscriber(ref: ActorRef) = for {
		con <- currentConnection
		en <- extraNonces.get(con)
		dif <- difficulties.get(con)
	} {
		ref ! en
		ref ! dif
		jobs.get(con).foreach(ref ! _)
	}

	def updateWork() {
		checkConnections()
	}

	def receive = {
		case AddConnection(con) =>
			stratumConnections += con
			checkConnections()
		case UnSubscribe(ref) if subscribers(ref) =>
			context unwatch ref
			subscribers -= ref
		case Subscribe(ref) =>
			subscribers += ref
			context watch ref
			updateSubscriber(ref)
		case Terminated(ref) if subscribers(ref) =>
			subscribers -= ref

		case x: SubmitStratumJob => for {
			con <- currentConnection
			ref <- stratumRefs get con
		} ref.tell(x, sender)
		case x: ExtraNonce if stratumRefsRev contains sender =>
			val con = stratumRefsRev(sender)
			extraNonces += con -> x
			updateWork()
			if(currentConnection == Some(con))
				subscribers.foreach(_ ! x)
		case x: MiningJob =>
			jobs += x.connection -> x
			if(currentConnection == Some(x.connection))
				subscribers.foreach(_ ! x)
			updateWork()
		case x: Difficulty if stratumRefsRev contains sender =>
			val con = stratumRefsRev(sender)
			difficulties += con -> x
			if(currentConnection == Some(con))
				subscribers.foreach(_ ! x)
			updateWork()
		case Terminated(ref) if stratumRefsRev contains ref =>
			val con = stratumRefsRev(ref)
			stratumRefs -= con

			log.warning(s"Connection $con died at ref $ref")

			conLastFailed += con -> Deadline.now

			if(currentConnection == Some(ref))
				currentConnection = None

			checkConnections()

		case MaybeChangeConnection =>
			val maybeCon = pickConnection

			if(maybeCon != currentConnection && maybeCon.isDefined) {
				log.info("Changing server to " + maybeCon)
				for {
					con <- currentConnection
					ref <- stratumRefs get con
				} context stop ref

				currentConnection = maybeCon
				subscribers foreach updateSubscriber
			}

			checkConnections()
	}
}
