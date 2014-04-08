package com.colingodsey.scalaminer.utils

import com.colingodsey.Sha256

/**
 * Created by crgodsey on 4/10/14.
 */
final class ScalaSha256 extends Sha256 {
	def update(seq: TraversableOnce[Byte]) {
		seq foreach update
	}

	def getState = Vector(h0, h1, h2, h3, h4, h5, h6, h7)

	def getResultSeq = Vector((h0 >>> 24).toByte,
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
			(h7 >>> 16).toByte, (h7 >>> 8).toByte, h7.toByte)

	def digestSeq() = {
		val tail: Array[Byte] = padBuffer
		update(tail, 0, tail.length)
		val result = getResultSeq
		reset()
		result
	}

}
