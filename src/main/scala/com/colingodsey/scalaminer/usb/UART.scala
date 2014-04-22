package com.colingodsey.scalaminer.usb

import javax.usb.UsbConst


case object CP210X {
	val TYPE_OUT = 0x41

	val REQUEST_IFC_ENABLE = 0x00
	val REQUEST_DATA = 0x07
	val REQUEST_BAUD = 0x1e

	val VALUE_UART_ENABLE = 0x0001
	val VALUE_DATA = 0x0303
	val DATA_BAUD = 0x0001c200
}

case object FTDI {
	//OSX: sudo kextunload -bundle com.apple.driver.AppleUSBFTDI

	val TYPE_OUT = (UsbConst.REQUESTTYPE_TYPE_VENDOR |
			UsbConst.REQUESTTYPE_RECIPIENT_DEVICE | UsbConst.ENDPOINT_DIRECTION_OUT).toByte
	val TYPE_IN = (UsbConst.REQUESTTYPE_TYPE_VENDOR |
			UsbConst.REQUESTTYPE_RECIPIENT_DEVICE | UsbConst.ENDPOINT_DIRECTION_IN).toByte

	val REQUEST_RESET: Byte = 0
	val REQUEST_MODEM: Byte = 1
	val REQUEST_FLOW: Byte = 2
	val REQUEST_BAUD: Byte = 3
	val REQUEST_DATA: Byte = 4
	val REQUEST_LATENCY: Byte = 9

	val VALUE_DATA_BFL = 0.toShort
	val VALUE_DATA_BAS = VALUE_DATA_BFL
	// LLT = BLT (same code)
	val VALUE_DATA_BLT = 8.toShort
	val VALUE_DATA_AVA = 8.toShort

	val VALUE_BAUD_BFL = 0xc068.toShort
	val INDEX_BAUD_BFL = 0x0200.toShort
	val VALUE_BAUD_BAS = VALUE_BAUD_BFL
	val INDEX_BAUD_BAS = INDEX_BAUD_BFL
	val VALUE_BAUD_AVA = 0x001A.toShort
	val INDEX_BAUD_AVA = 0x0000.toShort

	val VALUE_FLOW = 0.toShort
	val VALUE_MODEM = 0x0303.toShort

	val INDEX_BAUD_A = 0x0201.toShort
	val INDEX_BAUD_B = 0x0202.toShort
	val VALUE_BAUD =  0xC068.toShort
	val VALUE_MODEM_BITMODE = 0x00FF.toShort
	val INTERFACE_A = 0x0.toByte
	val INTERFACE_B = 0x1.toByte
	val SIO_SET_BITMODE_REQUEST = 0x0B.toByte
	val INTERFACE_A_IN_EP =  0x02.toByte
	val INTERFACE_A_OUT_EP = 0x81.toByte
	val INTERFACE_B_IN_EP =  0x04.toByte
	val INTERFACE_B_OUT_EP = 0x83.toByte

	val VALUE_RESET = 0.toShort
	val VALUE_PURGE_RX = 1.toShort
	val VALUE_PURGE_TX = 2.toShort
	val VALUE_LATENCY = 1.toShort

	val SIO_SET_DTR_MASK = 0x01.toByte
	val SIO_SET_RTS_MASK = 0x02.toByte
	val SIO_SET_DTR_HIGH = ( 1 | ( SIO_SET_DTR_MASK << 8)).toShort
	val SIO_SET_DTR_LOW  = ( 0 | ( SIO_SET_DTR_MASK << 8)).toShort
	val SIO_SET_RTS_HIGH = ( 2 | ( SIO_SET_RTS_MASK << 8)).toShort
	val SIO_SET_RTS_LOW  = ( 0 | ( SIO_SET_RTS_MASK << 8)).toShort

	val SIO_POLL_MODEM_STATUS_REQUEST = 0x05.toByte
	val SIO_SET_MODEM_CTRL_REQUEST = 0x01.toByte
	val MODEM_CTS = (1 << 4)

	val vendor = 0x0403.toShort
}

case object PL2303 {
	val CTRL_DTR: Byte = 0x01
	val CTRL_RTS: Byte = 0x02

	val CTRL_OUT: Byte = 0x21
	val VENDOR_OUT: Byte = 0x40

	val REQUEST_CTRL: Byte = 0x22
	val REQUEST_LINE: Byte = 0x20
	val REQUEST_VENDOR: Byte = 0x01

	val REPLY_CTRL: Byte = 0x21

	val VALUE_CTRL = (CTRL_DTR | CTRL_RTS).toShort
	val VALUE_LINE = 0.toShort
	val VALUE_LINE0 = 0x0001c200
	val VALUE_LINE1 = 0x080000
	val VALUE_LINE_SIZE = 7
	val VALUE_VENDOR = 0.toShort
}