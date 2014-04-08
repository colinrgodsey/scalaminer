package com.colingodsey.scalaminer

import java.io.{ByteArrayOutputStream, DataOutputStream}
import javax.usb.event.UsbDeviceDataEvent
import javax.usb.UsbDevice

package object usb {
	def byteArrayFrom(f: DataOutputStream => Unit) = {
		val bos = new ByteArrayOutputStream
		val dos = new DataOutputStream(bos)

		try {
			f(dos)
			dos.flush()
			bos.toByteArray
		} finally {
			bos.close()
			dos.close()
		}
	}

}
