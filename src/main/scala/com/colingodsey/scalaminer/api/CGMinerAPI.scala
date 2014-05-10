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

object CGMinerAPI {
	sealed trait Command

	case class ReceiveCommand(cmd: String) extends Command

	sealed trait CGMinerCommand extends Command

	case object Version extends CGMinerCommand
}

class CGMinerAPI extends Actor with Stash with ActorLogging {
	import CGMinerAPI._

	implicit val system = context.system

	def port = 4028
	def now = System.currentTimeMillis() / 1000
	def localAddr = new InetSocketAddress(port)

	var currentConnections = Set.empty[ActorRef]

	IO(Tcp) ! Tcp.Bind(self, localAddr)

	def version = ScalaMinerVersion.str

	def descriptionTag = "ScalaMiner " + version

	def formatSuccess(code: Int, msg: String) = {
		List(
			"STATS" -> "S",
			"When" -> now.toString,
			"Code" -> code,
			"Msg" -> msg,
			"Description" -> descriptionTag
		).map(x => x._1 + "=" + x._2).mkString(",") + "|"
	}

	def respondWith(str: String) =
		sender ! Tcp.Write(ByteString(str))

	def receive = {
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

			val msg = cmd.toLowerCase.trim match {
				case "version" => Some(Version)
				case x =>
					log.warning("Unknown command " + x)

					val err = s"STATUS=E,When=$now,Code=14,Msg=Invalid command," +
							s"Description=$descriptionTag|"

					sender ! Tcp.Write(ByteString(err))

					None
			}

			msg.foreach(self.tell(_, sender))
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

		case Version =>
			respondWith(formatSuccess(22, "CGMiner versions"))
			respondWith(s"VERSION,CGMiner=$version,API=1.26|")
	}

	override def postStop() {
		super.postStop()

		context stop self
	}
}
