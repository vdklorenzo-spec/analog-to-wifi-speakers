package com.example.analogtowifispeakers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log

class UsbReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {

            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

            if (device == null) {
                Log.d("USB_DEBUG", "USB attached but device is null")
                return
            }

            Log.d("USB_DEBUG", "Device Attached: ${device.deviceName}")
            Log.d("USB_DEBUG", "Vendor ID: ${device.vendorId}")
            Log.d("USB_DEBUG", "Product ID: ${device.productId}")
            Log.d("USB_DEBUG", "Interface count: ${device.interfaceCount}")

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                Log.d("USB_DEBUG", "Interface $i class: ${intf.interfaceClass}")

                if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                    Log.d("USB_DEBUG", "🎧 AUDIO DEVICE DETECTED")
                }
            }
        }
    }
}