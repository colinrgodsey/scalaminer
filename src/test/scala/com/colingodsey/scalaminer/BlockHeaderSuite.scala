package com.colingodsey.scalaminer

import org.scalatest._
import javax.xml.bind.DatatypeConverter
import com.colingodsey.scalaminer.utils._

/**
 * Created by crgodsey on 4/8/14.
 */
class BlockHeaderSuite extends FlatSpec {

	import com.colingodsey.scalaminer.hashing.Hashing._
/*
https://en.bitcoin.it/wiki/Genesis_block

GetHash()      = 0x000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f
hashMerkleRoot = 0x4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b
txNew.vin[0].scriptSig     = 486604799 4 0x736B6E616220726F662074756F6C69616220646E6F63657320666F206B6E697262206E6F20726F6C6C65636E61684320393030322F6E614A2F33302073656D695420656854
txNew.vout[0].nValue       = 5000000000
txNew.vout[0].scriptPubKey = 0x5F1DF16B2B704C8A578D0BBAF74D385CDE12C11EE50455F3C438EF4C3FBCF649B6DE611FEAE06279A60939E028A8D65C10B73071A6F16719274855FEB0FD8A6704 OP_CHECKSIG
block.nVersion = 1
block.nTime    = 1231006505
block.nBits    = 0x1d00ffff
block.nNonce   = 2083236893

CBlock(hash=000000000019d6, ver=1, hashPrevBlock=00000000000000, hashMerkleRoot=4a5e1e, nTime=1231006505, nBits=1d00ffff, nNonce=2083236893, vtx=1)
  CTransaction(hash=4a5e1e, ver=1, vin.size=1, vout.size=1, nLockTime=0)
    CTxIn(COutPoint(000000, -1), coinbase 04ffff001d0104455468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73)
    CTxOut(nValue=50.00000000, scriptPubKey=0x5F1DF16B2B704C8A578D0B)
  vMerkleTree: 4a5e1e

http://blockexplorer.com/block/000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f
 */
	"Sanity checks" should "not fail" in {
		val header = Array.fill[Byte](128)((math.random * 128 - 128).toByte)
		require(header.reverse.toList == reverseInts(reverseEndian(header)).toList)
		require(BigInt(Array[Byte](-1, -1, -1, -1)).toInt == -1)
		require(BigInt(Array[Byte](-1, -1, -1, -1)).toInt < 0)
		require(BigInt(Array[Byte](0, -1, -1, -1, -1)).toLong > 0)
		require(BigInt(Array[Byte](0, -1, -1, -1, -1)) > 0)
		require(BigInt(Array[Byte](-1, -1, -1, -1)) < 0)
		require(intToBytes(-1).toList == List[Byte](-1, -1, -1, -1))
		require(0xFF.toByte == -1.toByte)
		require(0xFFFF.toByte == -1.toByte)
	}

	//http://stackoverflow.com/questions/9245235/golang-midstate-sha-256-hash
	"A midpoint hash" should "calc midpoint" in {
		//256 chars, 128 bytes
		val headerHex = "00000001c570c4764aadb3f09895619f549000b8b51a789e7f58ea750000709700000000103ca064f8c76c390683f8203043e91466a7fcc40e6ebc428fbcc2d89b574a864db8345b1b00b5ac00000000000000800000000000000000000000000000000000000000000000000000000000000000000000000000000080020000"
		val header = headerHex.fromHex

		val halfHeader = header take 64

		val intSwapped = reverseEndian(halfHeader)

		val preSwapExpected = "69FC72E76DB0E764615A858F483E3566E42D56B2BC7A03ADCE9492887010EDA8"

		val midState = reverseEndian(calculateMidstate(halfHeader))

		require(preSwapExpected.toLowerCase == midState.toHex,
			preSwapExpected + "!=" + midState.toHex)

		//this is the way it should actually look after being encoded to little endian.
		val postSwappedExpected = "e772fc6964e7b06d8f855a6166353e48b2562de4ad037abc889294cea8ed1070"

		require(postSwappedExpected == reverseEndian(midState).map("%02x" format _).mkString)
	}
}
