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

package com.colingodsey.scalaminer.drivers

import org.scalatest._
import javax.xml.bind.DatatypeConverter
import com.colingodsey.scalaminer.utils._
import com.colingodsey.scalaminer.{ScalaMiner, Work}

class BitFurySuite extends FlatSpec {
	val testData = "000000026681dfaafb4259b0decafc6dcc6b53c83c5b4b525a9d3af30000000000000000ce633c8f69c2163a96df5d9c4c73b05380cf7bf6f56ab90b07987bcf2f86e98a538b5f5a1869284200000000".fromHex
	val midstate = "2e116b45fd89519c3950d5b411024b75abf49e8136b5cc426dfff2d7669596b9".fromHex
	val payload = "2e116b45fd89519c3950d5b411024b75abf49e8136b5cc426dfff2d7669596b9abf49e81702a4e47ffbdee816a850f8f2e116b45a28fc01afb74f9e295b659f02f86e98a538b5f5a1869284200000000".fromHex

	val res = "600d1388f1f119c845ade52fe012560045ade52fe0289100e000e900e010a500e01bed00e0034300e00dcb00e0310700e025cf00e0283f80e00fdb80879f8e4800000000".fromHex
	val preNonce = 803581253
	val postNonce = 1161636329

	"A payload" should "be generated from work" in {
		val testWork = Work(ScalaMiner.SHA256, testData, midstate, Nil)

		val genPayload = BitFury.genPayload(testWork)

		require(genPayload == payload, genPayload.toHex + " != " + payload.toHex)
	}

	it should "parse the pre nonce response" in {
		val nonceInts = getInts(res.reverseEndian).toSet

		require(nonceInts(preNonce), nonceInts + " doesnt contain " + preNonce)
	}

	it should "parse the post nonce response" in {
		val nonces = BitFury.noncesFromResponseBytes(res).map(_.toInt).toSet

		require(nonces(postNonce), nonces + " doesnt contain " + postNonce)
	}
}
