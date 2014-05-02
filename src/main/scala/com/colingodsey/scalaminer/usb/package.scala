/*
 * scalaminer
 * ----------
 * https://github.com/colinrgodsey/scalaminer
 *
 * Copyright (c) 2014 Colin R Godsey <colingodsey.com>
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.  See COPYING for more details.
 */

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
