package com.colingodsey.scalaminer

import com.colingodsey.Sha256
import javax.xml.bind.DatatypeConverter
import akka.util.ByteString

/**
 * Created by crgodsey on 4/10/14.
 */
package object utils {
	def curTime = System.currentTimeMillis() / 1000

	def intToBytes(x: Int) = {
		//val bs = BigInt(x).toByteArray
		//Seq.fill[Byte](4 - bs.length)(0) ++ bs

		Vector(((x >> 24) & 0xFF).toByte,
			((x >> 16) & 0xFF).toByte,
			((x >> 8 ) & 0xFF).toByte,
			((x      ) & 0xFF).toByte)
	}

	def bintToBytes(x: BigInt, bytes: Int): Seq[Byte] = {
		val bs = x.toByteArray

		Vector.fill[Byte](bytes - bs.length)(0) ++ bs
	}

	def reverseInts(dat: Seq[Byte]) = getInts(dat).reverse.flatMap(intToBytes)

	def doubleHash(seq: Iterable[Byte], sha: ScalaSha256 = new ScalaSha256) = {
		//seq.toArray.sha256.bytes.sha256.bytes.toSeq
		sha.update(seq)
		val seq2 = sha.digest()
		sha.update(seq2)
		sha.digestSeq()
	}

	def getInts(dat: Seq[Byte]): Stream[Int] =
		if(dat.isEmpty) Stream.empty
		else Stream(BigInt(dat.take(4).toArray).toInt) append getInts(dat.drop(4))

	def reverseEndian(dat: Seq[Byte]): ScalaMiner.BufferType =
		ScalaMiner.BufferType.empty ++ dat.take(4).reverse ++ {
			val dropped = dat drop 4
			if(dropped.isEmpty) Stream.empty
			else reverseEndian(dropped)
		}

	val hexDigits = "0123456789abcdef".toIndexedSeq

	def bytesToHex(seq: Iterable[Byte]): String =
		bytesToHex(seq.iterator)

	def bytesToHex(itr: Iterator[Byte]): String = {
		val builder = new StringBuilder

		while(itr.hasNext) {
			val byte = itr.next
			val n1 = (byte >> 4) & 0xF
			val n2 = byte & 0xF

			builder += hexDigits(n1)
			builder += hexDigits(n2)
		}

		builder.result()
	}

	implicit class ByteArrayPimp(val seq: Array[Byte]) extends AnyVal {
		def toHex = utils.bytesToHex(seq.iterator)
	}

	implicit class ByteSeqPimp(val seq: Seq[Byte]) extends AnyVal {
		def reverseEndian = utils.reverseEndian(seq)
		def toHex = utils.bytesToHex(seq)
		def doubleHash = utils.doubleHash(seq)
		def reverseInts = utils.reverseInts(seq)
	}

	implicit class StringPimp(val str: String) extends AnyVal {
		def fromHex = ByteString.empty ++ DatatypeConverter.parseHexBinary(str)
	}
}
