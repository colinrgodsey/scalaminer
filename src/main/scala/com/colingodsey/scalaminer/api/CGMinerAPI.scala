
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

package com.colingodsey.scalaminer.api

import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import akka.actor._
import akka.pattern._
import akka.io.{Tcp, IO}
import java.net.{InetAddress, InetSocketAddress}
import akka.util.ByteString
import com.colingodsey.ScalaMinerVersion
import com.colingodsey.scalaminer.metrics.{Counter, MinerMetrics}
import com.colingodsey.scalaminer.metrics.MinerMetrics.{Metric, Identity}
import com.colingodsey.scalaminer.ScalaMiner
import com.colingodsey.scalaminer.metrics.MinerMetrics._

object CGMinerAPI {
	sealed trait Command

	case class ReceiveCommand(cmd: String) extends Command

	sealed trait CGMinerCommand extends Command {
		def messageId: Int
		def messageName: String
	}

	sealed trait CGMinerResponse extends CGMinerCommand {
		def messageName: String
		def messageId: Int
		def singleData: Option[Map[String, String]]

		def dataRows: Seq[Map[String, String]] = Nil

		def responseStrings: Seq[String] = {
			if(singleData.isDefined) Seq(
				formatSuccess(messageId, messageName),
				responseTag + "," + singleData.get.map {
					case (key, value) =>
						s"$key=$value"
				}.mkString(",")
			) else Seq(formatSuccess(messageId, messageName))
		}

		def responseTag: String
	}

	sealed trait CGMinerError extends Exception with CGMinerResponse

	sealed abstract class ACGMinerError(val messageId: Int, val theMsg: String) extends Exception(theMsg) with CGMinerError {
		def messageName = theMsg
		def singleData = None
		def responseTag = toString
	}

	case object InvalidCommand extends ACGMinerError(14, "Invalid command")
	case object MissingId extends ACGMinerError(15, "Missing device id parameter")

	/*
	 * API Commands
	 */

	trait APICommand extends Command {
		def commandId: Int
		def commandMessage: String

		def requestTag: String = this.toString()

		abstract class AResponse extends CGMinerResponse {
			def messageName: String = commandMessage
			def messageId: Int = commandId
			def responseTag: String = requestTag.toUpperCase()
		}
	}

	case object Asc extends APICommand {
		def commandId: Int = 9
		def commandMessage: String = "Device Details"

		/*
		STATUS=S,When=1402862004,Code=9,Msg=10 ASC(s),Description=cgminer 4.3.3|ASC=0,Name=BXM,ID=0,
		Enabled=N,Status=Alive,Temperature=0.00,MHS av=0.00,MHS 5s=0.00,MHS 1m=0.00,MHS 5m=0.00,
		MHS 15m=0.00,Accepted=0,Rejected=0,Hardware Errors=0,Utility=0.00,Last Share Pool=-1,
		Last Share Time=0,Total MH=0.0000,Diff1 Work=0,Difficulty Accepted=0.00000000,
		Difficulty Rejected=0.00000000,Last Share Difficulty=0.00000000,No Device=true,
		Last Valid Work=1402861378,Device Hardware%=0.0000,Device Rejected%=0.0000,
		Device Elapsed=621|ASC=1,Name=BXM,ID=1,Enabled=N,Status=Alive,Temperature=0.00,
		MHS av=0.00,MHS 5s=0.00,MHS 1m=0.00,MHS 5m=0.00,MHS 15m=0.00,Accepted=0,
		Rejected=0,Hardware Errors=0,Utility=0.00,Last Share Pool=-1,Last Share Time=0,
		Total MH=0.0000,Diff1 Work=0,Difficulty Accepted=0.00000000,Difficulty Rejected=0.00000000,
		Last Share Difficulty=0.00000000,No Device=true,Last Valid Work=1402861446,
		Device Hardware%=0.0000,Device Rejected%=0.0000,Device Elapsed=558
		 */

		case class Response(idents: Map[Identity, Map[Metric, Counter.Snapshot]]) extends AResponse {
			def singleData = None
			override def dataRows = idents.toSeq map { case (ident, snapshot) =>
				def sumOf(metric: Metric) = snapshot.get(metric) match {
					case None => 0.0
					case Some(x) => x.sum
				}

				def rateOf(metric: Metric) = snapshot.get(metric) match {
					case None => 0.0
					case Some(x) => x.rate
				}

				Map(
					"ASC" -> (now - snapshot(Hashes).started),
					"MHS av" -> rateOf(Hashes) / 1000000,
					"Found Blocks" -> "0",
					"Getworks" -> sumOf(WorkStarted),
					"Accepted" -> sumOf(NonceAccepted),
					"Rejected" -> (sumOf(NonceStale) + sumOf(NonceStratumLow) + sumOf(NonceFail)),
					"Hardware Errors" -> "0",
					"Utility" -> "0",
					"Discarded" -> sumOf(NonceShort),
					"Stale" -> sumOf(NonceStale),
					"Get Failures" -> "0",
					"Local Work" -> "0",
					"Remote Failures" -> "0",
					"Network Blocks" -> "512",
					"Total MH" -> sumOf(Hashes) / 1000000,
					"Work Utility" -> "0",
					"Difficulty Accepted" -> sumOf(NonceAccepted),
					"Difficulty Rejected" -> sumOf(NonceShort),
					"Difficulty Stale" -> "0.00000000",
					"Best Share" -> "0"
				).map(x => x._1 -> x._2.toString)
			}
		}
	}

	case object Summary extends APICommand {
		def commandId: Int = 11
		def commandMessage: String = requestTag

/*
STATUS=S,When=1399685471,Code=11,Msg=Summary,Description=cgminer 3.3.1|SUMMARY,Elapsed=268191,MHS av=7475.41,
			Found Blocks=0,Getworks=6862,Accepted=28792,Rejected=83,Hardware Errors=8052,Utility=6.44,Discarded=13720,
			Stale=0,Get Failures=0,Local Work=502148,Remote Failures=0,Network Blocks=512,Total MH=2004834899.1967,Work Utility=104.57,
			Difficulty Accepted=460672.00000000,Difficulty Rejected=1328.00000000,Difficulty Stale=0.00000000,Best Share=1070416
 */

		case class Response(started: Long, snapshotTotal: Map[Metric, Counter.Snapshot]) extends AResponse {
			val singleData = Some(Map(
				"Elapsed" -> (now - started),
				"MHS av" -> rateOf(Hashes) / 1000000,
				"Found Blocks" -> "0",
				"Getworks" -> sumOf(WorkStarted),
				"Accepted" -> sumOf(NonceAccepted),
				"Rejected" -> (sumOf(NonceStale) + sumOf(NonceStratumLow) + sumOf(NonceFail)),
				"Hardware Errors" -> "0",
				"Utility" -> "0",
				"Discarded" -> sumOf(NonceShort),
				"Stale" -> sumOf(NonceStale),
				"Get Failures" -> "0",
				"Local Work" -> "0",
				"Remote Failures" -> "0",
				"Network Blocks" -> "512",
				"Total MH" -> sumOf(Hashes) / 1000000,
				"Work Utility" -> "0",
				"Difficulty Accepted" -> sumOf(NonceAccepted),
				"Difficulty Rejected" -> sumOf(NonceShort),
				"Difficulty Stale" -> "0.00000000",
				"Best Share" -> "0"
			).map(x => x._1 -> x._2.toString))

			def sumOf(metric: Metric) = snapshotTotal.get(metric) match {
				case None => 0.0
				case Some(x) => x.sum
			}

			def rateOf(metric: Metric) = snapshotTotal.get(metric) match {
				case None => 0.0
				case Some(x) => x.rate
			}
		}
	}

	case object Version extends APICommand {
		def commandId: Int = 22
		def commandMessage: String = "ScalaMiner versions"

		case class Response(version: String) extends AResponse {
			val singleData = Some(Map(
				"CGMiner" -> version,
				"API" -> apiVersion
			))
		}
	}

	case object DevDetails extends APICommand {
		def commandId: Int = 69
		def commandMessage: String = "Device Details"

		case class Response(version: String) extends AResponse {
			val singleData = Some(Map(
				"CGMiner" -> version,
				"API" -> apiVersion
			))
		}
	}

	/*
	STATUS=S,When=1402865408,Code=78,Msg=CGMiner coin,Description=cgminer 4.3.3|
	COIN,Hash Method=sha256,Current Block Time=1402864983.050908,
	Current Block Hash=00000000000000005c3cad291bfb86105e4b7c480f630a2a4752d3277d9624b7,
	LP=true,Network Difficulty=11756551916.90395164|
	 */
	case object Coin extends APICommand {
		def commandId: Int = 78
		def commandMessage: String = "ScalaMiner versions"

		case object Response extends AResponse {
			val singleData = Some(Map(
				//TODO: need 2 APIs... one for scrypt, one for sha256
				"Hash Method" -> "sha256",
				"Current Block Time" -> "1402860000",
				"Current Block Hash" -> "",
				"LP" -> "true",
				"Network Difficulty" -> "",
				"API" -> apiVersion
			))
		}
	}

	val cmdSet = Set(Version, Summary, DevDetails, Coin, Asc)

	val cmdMap = cmdSet.map(x => x.toString.toLowerCase -> x).toMap

	def formatSuccess(code: Int, msg: String) = {
		List(
			"STATUS" -> "S",
			"When" -> now.toString,
			"Code" -> code,
			"Msg" -> msg,
			"Description" -> descriptionTag
		).map(x => x._1 + "=" + x._2).mkString(",")
	}

	def now = System.currentTimeMillis() / 1000

	def version = ScalaMinerVersion.str
	def apiVersion = "1.26"

	def descriptionTag = "ScalaMiner " + version
}

class CGMinerAPI(metricsRef: ActorRef, hashType: ScalaMiner.HashType)
		extends Actor with Stash with ActorLogging {
	import CGMinerAPI._

	private implicit def ec = context.system.dispatcher

	implicit val system = context.system

	def port = 4028
	def localAddr = new InetSocketAddress(port)

	def snapshotInterval = 3.seconds

	val started = now

	var currentConnections = Set.empty[ActorRef]
	var metricsSnapshot: Map[Identity,
			Map[Metric, Counter.Snapshot]] = Map.empty
	var snapshotTotal: Map[Metric, Counter.Snapshot] = Map.empty

	def metricsSet = metricsSnapshot.flatMap(_._2.keySet).toSet
	def snapshotSumOf(metric: Metric) = (for {
		(ident, metrics) <- metricsSnapshot.toSeq
		(aMetric, snapshot) <- metrics
		if aMetric == metric
	} yield snapshot).reduceLeft(_ + _)
	def calcTotal = metricsSet.toSeq.map(metric => metric -> snapshotSumOf(metric)).toMap

	def respondWith(resp: CGMinerResponse): Unit =
		resp.responseStrings foreach respondWith

	def respondWith(str: String): Unit =
		sender ! Tcp.Write(ByteString(str + "|"))

	def receive = {
		case MinerMetrics.SnapshotResponse(snapshot) =>
			metricsSnapshot = snapshot.filter(_._1.hashType == hashType)
			snapshotTotal = calcTotal
		case Tcp.Connected(remote, _) =>
			log.info("New connection!")
			val connection = sender
			connection ! Tcp.Register(self)
			currentConnections += connection
			context watch connection
		//TODO: this should probably realllly buffer..... long commands may die
		case Tcp.Received(dat) =>
			val cmd = new String(dat.toArray, "ASCII").trim

			self.tell(ReceiveCommand(cmd.substring(0, cmd.length - 1)), sender)
		case ReceiveCommand(cmd) =>
			log.info("Received command " + cmd)

			cmd.toLowerCase.trim match {
				case x if cmdMap contains x =>
					val msg = cmdMap get x
					msg.foreach(self.tell(_, sender))
				case x => respondWith(InvalidCommand)
			}


		case Tcp.Bound(_) =>
			log.info("Listening on " + localAddr)
		case Tcp.CommandFailed(_: Tcp.Bind) =>
			sys.error("Failed to bind!")
		case Tcp.CommandFailed(cmd) =>
			log.warning("TCP command failed " + cmd)
		case x: Tcp.Message =>
			log.warning("Unhandled tcp command " + x)

		case Terminated(ref) if currentConnections(ref) =>
			currentConnections -= ref
			log.info("Connection closed")

		case Version => respondWith(Version.Response(version))
		case Coin => respondWith(Coin.Response)
		case Summary => respondWith(Summary.Response(started, snapshotTotal))
		case DevDetails =>
			/*
			STATUS=S,When=1399691062,Code=69,Msg=Device Details,Description=cgminer 3.3.1|
			DEVDETAILS=0,Name=BAJ,ID=0,Driver=BitForceSC,Kernel=,Model=,Device Path=3:5|
			 */
			respondWith(formatSuccess(11, "Device Details"))

			//TODO: replace with a deviceId -> #id thing
			var i = 0
			metricsSnapshot foreach {
				case (Identity(typ, id, _), metrics) =>
					val str = s"DEVDETAILS=$i,Name=$typ,ID=$i,Driver=${typ.drv}}," +
							s"Kernel=,Model=,Device Path=$id"

					respondWith(str)

					i += 1
			}
	}

	override def preStart() {
		super.preStart()

		IO(Tcp) ! Tcp.Bind(self, localAddr)

		context.system.scheduler.schedule(3.seconds, snapshotInterval,
			metricsRef, MinerMetrics.Snapshot)
	}

	override def postStop() {
		super.postStop()

		context stop self
	}
}
