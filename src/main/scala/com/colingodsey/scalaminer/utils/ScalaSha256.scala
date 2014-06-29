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

package com.colingodsey.scalaminer.utils

import com.colingodsey.Sha256

final class ScalaSha256 extends Sha256 {
	def update(seq: TraversableOnce[Byte]) {
		seq foreach update
	}

	def getState = Vector(h0, h1, h2, h3, h4, h5, h6, h7)

	private def getK = Sha256.k

	/*def getResultSeq = Vector((h0 >>> 24).toByte,
			(h0 >>> 16).toByte, 
			(h0 >>> 8).toByte, 
			h0.toByte, 
			(h1 >>> 24).toByte, 
			(h1 >>> 16).toByte, 
			(h1 >>> 8).toByte, 
			h1.toByte, 
			(h2 >>> 24).toByte, 
			(h2 >>> 16).toByte, 
			(h2 >>> 8).toByte, 
			h2.toByte, 
			(h3 >>> 24).toByte, 
			(h3 >>> 16).toByte, 
			(h3 >>> 8).toByte, h3.toByte, 
			(h4 >>> 24).toByte, (h4 >>> 16).toByte, 
			(h4 >>> 8).toByte, h4.toByte, 
			(h5 >>> 24).toByte, (h5 >>> 16).toByte, 
			(h5 >>> 8).toByte, h5.toByte, 
			(h6 >>> 24).toByte, (h6 >>> 16).toByte, 
			(h6 >>> 8).toByte, h6.toByte, (h7 >>> 24).toByte, 
			(h7 >>> 16).toByte, (h7 >>> 8).toByte, h7.toByte)*/

	def getResultSeq: IndexedSeq[Byte] = wrapByteArray(getResult())

	def digestSeq() = {
		val tail: Array[Byte] = padBuffer
		update(tail, 0, tail.length)
		val result = getResultSeq
		reset()
		result
	}

	def initInts(ints: Seq[Int]) {
		h0 = ints(0)
		h1 = ints(1)
		h2 = ints(2)
		h3 = ints(3)
		h4 = ints(4)
		h5 = ints(5)
		h6 = ints(6)
		h7 = ints(7)
	}
}

object ScalaSha256 {
	lazy val k = (new ScalaSha256).getK

	//pretty sure this is some sort of fancy midstate
	//copied heavily from cgminer
	def ms3Steps(ints: IndexedSeq[Int]) = {
		var a, b, c, d, e, f, g, h = 0

		val intBits = 4 << 3 //(sizeof(x) << 3)

		def CH(x: Int, y: Int, z: Int) = ((x & y) ^ (~x & z))
		def MAJ(x: Int, y: Int, z: Int) = ((x & y) ^ (x & z) ^ (y & z))
		def ROTR(x: Int, n: Int) = ((x >>> n) | (x << (intBits - n)))
		def SHFR(x: Int, n: Int) = (x >>> n)

		def SHA256_F1(x: Int) = (ROTR(x,  2) ^ ROTR(x, 13) ^ ROTR(x, 22))
		def SHA256_F2(x: Int) = (ROTR(x,  6) ^ ROTR(x, 11) ^ ROTR(x, 25))
		def SHA256_F3(x: Int) = (ROTR(x,  7) ^ ROTR(x, 18) ^ SHFR(x,  3))
		def SHA256_F4(x: Int) =  (ROTR(x, 17) ^ ROTR(x, 19) ^ SHFR(x, 10))

		a = ints(0)
		b = ints(1)
		c = ints(2)
		d = ints(3)
		e = ints(4)
		f = ints(5)
		g = ints(6)
		h = ints(7)

		for(i <- 0 until 3) {
			val new_e = ints(i+16) + ScalaSha256.k(i) + h + CH(e,f,g) + SHA256_F2(e) + d
			val new_a = ints(i+16) + ScalaSha256.k(i) + h + CH(e,f,g) + SHA256_F2(e) +
					SHA256_F1(a) + MAJ(a,b,c)
			d = c
			c = b
			b = a
			a = new_a
			h = g
			g = f
			f = e
			e = new_e
		}

		ints.take(8) ++ Seq(a, b, c, d, e, f, g, h).reverse ++ ints.drop(16)
	}
}
