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
import spray.http.{HttpHeaders, HttpRequest}
import spray.httpx.marshalling.ToResponseMarshallable
import javax.xml.bind.DatatypeConverter
import com.colingodsey.scalaminer.hashing.Hashing
import Hashing._
import com.colingodsey.scalaminer.network.Stratum.MiningJob
import scala.concurrent._
import com.colingodsey.scalaminer.{ScalaMiner, MinerStats, Work}
import com.colingodsey.scalaminer.utils._

object Stratum {
	case class Subscribe(ref: ActorRef)
	case class UnSubscribe(ref: ActorRef)
	case object WorkAccepted
	case class SubmitStratumJob(params: Seq[JsValue])
	case class Difficulty(diff: Int)
	case class StratumError(eid: Int, msg: String)

	case class Job(work: Work, id: String,
			merkle_hash: Seq[Byte], extranonce2: Seq[Byte],
			started: Long = System.currentTimeMillis / 1000) {
		def runningFor = ((System.currentTimeMillis / 1000) - started).seconds
	}

	case class StratumConnection(host: String, port: Int,
			user: String, pass: String)

	case class MiningJob(hashType: ScalaMiner.HashType, id: String, prevHashStr: String,
			coinbase1Str: String, coinbase2Str: String, merkleBranchStrs: Seq[String],
			versionStr: String, nBitsStr: String, nTimeStr: String, cleanJobs: Boolean,
			received: Long = System.currentTimeMillis / 1000) {
		val time = BigInt("00" + nTimeStr, 16).toLong
		val dTime = time - received.toInt

		lazy val coinbase1 = coinbase1Str.fromHex
		lazy val coinbase2 = coinbase2Str.fromHex
		lazy val previousHash = prevHashStr.fromHex
		lazy val protoVersion = versionStr.fromHex
		lazy val nBits = nBitsStr.fromHex
		lazy val merkleBranches = merkleBranchStrs.map(_.fromHex).toStream


	}

	case class ExtraNonce(hashType: ScalaMiner.HashType, extranonce1: Seq[Byte], extranonce2Size: Int)

	object StratumProtocol extends ModifiedJsonProtocol {
		implicit val _a1 = jsonFormat4(StratumConnection)
	}

	case class JSONResponse(json: JsObject) {
		def isBroadcast = json.fields.get("id") == Some(JsNull)
	}
}

class StratumActor(tcpManager: ActorRef, conn: Stratum.StratumConnection,
			hashType: ScalaMiner.HashType)
		extends Actor with ActorLogging with Stash {
	import Stratum._

	var messageId = 1
	var difficulty = 1
	var connectionActor = context.system.deadLetters
	var buffer = ""
	var responseMap = Map[Int, JsObject => Unit]() //TODO: add cleanup!
	var subscribers = Set[ActorRef]()
	var extranonce1: Seq[Byte] = Nil
	var extranonce2Size: Int = 0
	var lastJob: Option[MiningJob] = None

	def extraNonce = ExtraNonce(hashType, extranonce1, extranonce2Size)

	tcpManager ! Tcp.Connect(new InetSocketAddress(
		InetAddress.getByName(conn.host), conn.port), timeout = Some(5.seconds))

	def receive = waitingConnect

	def send(dat: TraversableOnce[Byte]) =
		connectionActor ! Tcp.Write(ScalaMiner.BufferType.empty ++ dat.toIndexedSeq)

	def sendJsonCommand(obj: JsObject)(response: JsObject => Unit) {
		if(messageId > 100000) messageId = 0

		val id = messageId
		messageId += 1

		val newObj = (obj.fields + ("id" -> id.toJson)).toJson

		val str = newObj.compactPrint + "\n"

		//log.info("Sending " + str)

		send(str.getBytes("UTF8"))

		responseMap += id -> response
	}

	def dataReceive: Receive = {
		case Tcp.CommandFailed(cmd) =>
			sys.error("TCP command failed " + cmd)
		case x: Tcp.ConnectionClosed =>
			sys.error("Connection closed! " + x)
		case Tcp.Received(data) =>
			val str = new String(data.toArray, "UTF8")

			buffer += str

			def breakSome {
				val idx = buffer.indexOf("\n")
				if(idx != -1) {
					val (a, b) = buffer.splitAt(idx + 1)
					self ! JSONResponse(a.asJson.asJsObject)
					buffer = b
					breakSome
				}
			}

			breakSome
		case x: Tcp.Command =>
			log.warning("Unhandled tcp command " + x)
	}

	def sendCommand(method: String, params: Seq[JsValue])(response: JsObject => Unit) {
		sendJsonCommand(JsObject(
			"method" -> method.toJson,
			"params" -> params.toJson
		))(response)
	}

	def beginSession {
		sendCommand("mining.subscribe", Nil) { resp =>
			val params = Seq(conn.user.toJson, conn.pass.toJson)

			val res = resp.fields("result").convertTo[Seq[JsValue]]

			extranonce1 = res(1).convertTo[String].fromHex
			extranonce2Size = res(2).convertTo[Int]

			subscribers.foreach(_ ! extraNonce)

			sendCommand("mining.authorize", params) { r =>
				log.info(r.toString)

			}
		}
	}

	def waitingConnect: Receive = dataReceive orElse {
		case _: Tcp.Connected =>
			log.info("Connected!")

			connectionActor = sender
			connectionActor ! Tcp.Register(self)
			context watch connectionActor

			context become normal
			unstashAll()

			beginSession
		case _ => stash()
	}

	def receiveResponses: Receive = {
		case x @ JSONResponse(js) if x.isBroadcast =>
			js.fields("method").convertTo[String] match {
				case "mining.set_difficulty" =>
					difficulty = js.fields("params").convertTo[Seq[Int]].head
					log.info("New difficulty " + difficulty)
					subscribers.foreach(_ ! Difficulty(difficulty))
				case "mining.notify" =>
					val vals = js.fields("params").convertTo[Seq[JsValue]]

					val job = MiningJob(hashType,
						vals(0).convertTo[String], vals(1).convertTo[String],
						vals(2).convertTo[String], vals(3).convertTo[String],
						vals(4).convertTo[Seq[String]], vals(5).convertTo[String],
						vals(6).convertTo[String], vals(7).convertTo[String],
						vals(8).convertTo[Boolean])

					lastJob = Some(job)

					log.info("New job - " + job)

					subscribers.foreach(_ ! job)
				case _ =>
					log.info("Broadcast " + js)
			}
		case JSONResponse(js) =>
			log.debug("Response " + js)

			val id = js.fields("id").convertTo[Int]

			/*if(js.fields.get("error") != Some(JsNull))
				log.warning("Js error! " + js.fields("error"))*/

			require(responseMap.get(id).isDefined, "No response callback for resp " + id)

			responseMap(id)(js)
			responseMap -= id

		case SubmitStratumJob(params) =>
			val respondTo = sender

			log.debug("Submitting share! " + params)
			sendCommand("mining.submit", params) { js =>
				//log.info("Submitted share! " + js)

				if(js.fields.get("error") != Some(JsNull)) {
					val fields = js.fields("error").convertTo[Seq[JsValue]]
					val eid = fields(0).convertTo[Int]
					val msg = fields(1).convertTo[String]
					respondTo ! StratumError(eid, msg)
				} else {
					log.debug("Share accepted!")
					respondTo ! WorkAccepted
				}
			}

		case UnSubscribe(ref) if subscribers(ref) =>
			context unwatch ref
			subscribers -= ref
		case Subscribe(ref) =>
			subscribers += ref
			context watch ref
			ref ! extraNonce
			ref ! Difficulty(difficulty)
			if(lastJob != None) ref ! lastJob.get
		case Terminated(ref) if subscribers(ref) =>
			subscribers -= ref
	}

	def normal = dataReceive orElse receiveResponses

	override def postStop() {
		super.postStop()
		context stop connectionActor
		context stop self
	}
}


trait ModifiedJsonProtocol extends DefaultJsonProtocol {
	override protected def extractFieldNames(
			classManifest: ClassManifest[_]): Array[String] = {
		val clazz = classManifest.erasure
		try {
			val copyDefaultMethods = clazz.getMethods.filter(_.getName.startsWith("copy$default$")).sortBy(
				_.getName.drop("copy$default$".length).takeWhile(_ != '(').toInt)
			val fields = clazz.getDeclaredFields.filterNot(_.getName.startsWith("$"))
			fields.slice(0, copyDefaultMethods.length).map(_.getName)
		} catch {
			case ex: Throwable => throw new RuntimeException("Cannot automatically determine case class field names and order " +
					"for '" + clazz.getName + "', please use the 'jsonFormat' overload with explicit field name specification", ex)
		}
	}
}