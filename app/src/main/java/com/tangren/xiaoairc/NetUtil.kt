package com.tangren.xiaoairc

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest

/** 局域网地址探测与杂项工具。 */
object NetUtil {

    /** 取本机在 Wi-Fi 上的 IPv4 地址：SSDP 组播与代理 URL 都要它。 */
    @Suppress("DEPRECATION")
    fun wifiIpv4(context: Context): String? {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wm != null) {
            val ipInt = wm.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return "${ipInt and 0xFF}.${(ipInt shr 8) and 0xFF}." +
                    "${(ipInt shr 16) and 0xFF}.${(ipInt shr 24) and 0xFF}"
            }
        }
        return fallbackIpv4()
    }

    /** Wi-Fi 不可用时退化为枚举网卡（例如某些 ROM 关闭了 WifiInfo.ipAddress）。 */
    private fun fallbackIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .firstOrNull { it.isNotBlank() && !it.startsWith("127.") }
        } catch (_: Throwable) {
            null
        }
    }

    /** 按 IP 匹配到具体的 NetworkInterface，SSDP 组播必须绑定正确网卡。 */
    fun findInterfaceByIp(ip: String): NetworkInterface? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList().firstOrNull { nif ->
                nif.inetAddresses.toList().any { addr ->
                    addr is Inet4Address && addr.hostAddress == ip
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun sha1(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(input)

    fun randomId(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..length).map { chars.random() }.joinToString("")
    }

    fun localAddressOf(ip: String): InetAddress? =
        try {
            InetAddress.getByName(ip)
        } catch (_: Throwable) {
            null
        }
}
