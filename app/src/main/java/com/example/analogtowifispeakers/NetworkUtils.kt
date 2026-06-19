package com.example.analogtowifispeakers

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /**
     * Returns the first non-loopback IPv4 address found on the device.
     * Works for Wi-Fi/LAN. Returns null if none found.
     */
    fun getLocalIpv4Address(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            interfaces.toList()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress }
                .firstOrNull { ip ->
                    ip != "127.0.0.1" && !ip.startsWith("169.254.")
                }
        } catch (_: Exception) {
            null
        }
    }
}