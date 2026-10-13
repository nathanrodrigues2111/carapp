package com.lazyneil.lazycar.rear

import com.lazyneil.lazycar.R

import android.hardware.usb.UsbDevice

object UsbDevices {

    /** UVC class 14 (video), or 239 (misc/IAD, how composite webcams with a
     *  built-in mic present themselves). Also checks the interfaces, because
     *  some cameras report class 0 at the device level. */
    fun isVideoDevice(device: UsbDevice?): Boolean {
        device ?: return false
        if (device.deviceClass == 14 || device.deviceClass == 239) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 14) return true
        }
        return false
    }
}
