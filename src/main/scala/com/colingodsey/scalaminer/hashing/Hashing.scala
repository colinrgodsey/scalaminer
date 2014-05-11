/*
 * ScalaMiner
 * ----------
 * https://github.com/colinrgodsey/scalaminer
 *
 * Copyright 2014 Colin R Godsey <colingodsey.com>
 * Copyright 2011-2014 Con Kolivas
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.  See COPYING for more details.
 */

package com.colingodsey.scalaminer.hashing

import com.colingodsey.Sha256
import com.colingodsey.scalaminer.network.Stratum.{ExtraNonce, MiningJob}
import com.colingodsey.scalaminer.network.Stratum
import com.colingodsey.scalaminer.utils._
import scala.concurrent.duration._
import scala.concurrent.duration.{FiniteDuration, Deadline}
import javax.xml.bind.DatatypeConverter
import com.colingodsey.scalaminer.{ScalaMiner, Work}
import akka.util.ByteString

object Hashing {

	//TODO: need some kind of calc for hashRate to nonceTimeout
	//a full 2^32 nonce range equals 4.294967296 GH

	val hashesFor32b = 4.294967296// GH

	/** for 32bit nonce range */
	def hashRateToTimeout(ghs: Double) = if(ghs != 0) (hashesFor32b / ghs).seconds
	else 1.second

	//returns little-endian
	def calculateMidstate(header: Seq[Byte], state: Option[Seq[Byte]] = None,
			rounds: Option[Int] = None) = {
		require(state == None)
		require(rounds == None)

		val sha256 = new ScalaSha256

		require(header.length == 64)

		//val ints = getInts(reverseEndian(header))

		sha256.update(reverseEndian(header))//reverseEndian(header).toArray)
		val midState = sha256.getState.toStream.flatMap(intToBytes)

		reverseEndian(midState)
	}

	def getWork(hashType: ScalaMiner.HashType, extraNonceInt: Int, job: MiningJob,
			extraNonceInfo: ExtraNonce, targetBytes: Seq[Byte], needsMidstate: Boolean) = {
		val bytes = intToBytes(extraNonceInt)
		val enBytes = Seq.fill[Byte](extraNonceInfo.extranonce2Size -
				bytes.length)(0) ++ bytes

		val extraNonce = extraNonceInfo.extranonce1 ++ enBytes

		val sha256 = new ScalaSha256

		val coinbase = ScalaMiner.BufferType.empty ++
				job.coinbase1 ++ extraNonce ++ job.coinbase2

		val coinbaseHash = doubleHash(coinbase, sha256)

		val merkleRoot = reverseEndian(job.merkleBranches.foldLeft(coinbaseHash) { (a, b) =>
			doubleHash(a ++ b, sha256)
		})

		val ntime = (System.currentTimeMillis / 1000) + job.dTime

		val serializedHeader = ScalaMiner.BufferType.empty ++
				job.protoVersion ++ job.previousHash ++ merkleRoot ++
				intToBytes(ntime.toInt) ++ job.nBits ++ intToBytes(0) ++ //enBytes ++
				workBasePad

		//require(serializedHeader.length == 128, "bad length " + serializedHeader.length)

		//TODO: this is the 'old' target?
		val target = ScalaMiner.BufferType.empty ++ (if(targetBytes.isEmpty)
			"ffffffffffffffffffffffffffffffffffffffffffffffffffffffff00000000".fromHex.toSeq
		else targetBytes)

		val midstate = if(needsMidstate) calculateMidstate(serializedHeader.take(64))
		else Nil

		Stratum.Job(Work(hashType, serializedHeader, midstate, target),
			job.id, merkleRoot, enBytes)
	}

	def prettyHashRate(nHashes: Double, dur: FiniteDuration) = {
		val hpm = nHashes / dur.toMillis
		val hps = hpm * 1000

		def nicePrint(v: Double) = "%.2f".format(v)

		if(hps > 1000000000000L) nicePrint(hps / 1000000000000L) + " TH/s"
		else if(hps > 1000000000L) nicePrint(hps / 1000000000L) + " GH/s"
		else if(hps > 1000000) nicePrint(hps / 1000000) + " MH/s"
		else if(hps > 1000) nicePrint(hps / 1000) + " kH/s"
		else nicePrint(hps) + " kH/s"
	}

	val workBasePad = "000000800000000000000000000000000000000000000000000000000000000000000000000000000000000080020000".fromHex

	//hashes in a single dif 1 share
	val dif1Hashes = {
		//2^48/65535
		((BigInt(2) pow 48) / 65535).toLong
	}

	def hashesForDiffSHA256(diff: Int) = diff * dif1Hashes
	def hashesForDiffScrypt(diff: Int) = (diff * dif1Hashes) >> 16

	val bitcoinTarget1 = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffff00000000"
	val bitcoinDefaultTarget =  BigInt({
		val hex = "00000000ffff0000000000000000000000000000000000000000000000000000"

		hex.fromHex.toArray
	})

	val scryptDefaultTarget =  BigInt({
		val hex = "0000ffff00000000000000000000000000000000000000000000000000000000"

		hex.fromHex.toArray
	})
}
