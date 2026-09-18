package com.tangren.xiaoairc.dlna

import com.tangren.xiaoairc.BuildConfig
import com.tangren.xiaoairc.LogBus
import com.tangren.xiaoairc.NetUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.MulticastSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * SSDP 组播应答器：把自己伪装成一个 UPnP MediaRenderer。
 *
 * 手机上的音乐 App 会向 239.255.255.250:1900 发 M-SEARCH，
 * 我们回复 description.xml 的地址，它就会把我们当成一个可投屏的音箱。
 *
 * 实现对齐 MiAir 的 dlna/ssdp.py（同一条组播路径，端口必须用 1900 作为源端口）。
 */
class SsdpResponder(
    private val udn: String,
    private val friendlyName: String,
    private val hostIp: String,
    private val httpPort: Int,
    private val scope: CoroutineScope
) {

    private var socket: MulticastSocket? = null
    private var receiveJob: Job? = null
    private var aliveJob: Job? = null
    private val running = AtomicBoolean(false)

    private val location: String
        get() = "http://$hostIp:$httpPort/device/$udn/description.xml"

    private fun targets(): List<Pair<String, String>> {
        val uuid = "uuid:$udn"
        return listOf(
            "upnp:rootdevice" to "$uuid::upnp:rootdevice",
            uuid to uuid,
            UpnpConst.DEVICE_TYPE to "$uuid::${UpnpConst.DEVICE_TYPE}",
            UpnpConst.AVTRANSPORT_URN to "$uuid::${UpnpConst.AVTRANSPORT_URN}",
            UpnpConst.RENDERING_CONTROL_URN to "$uuid::${UpnpConst.RENDERING_CONTROL_URN}",
            UpnpConst.CONNECTION_MANAGER_URN to "$uuid::${UpnpConst.CONNECTION_MANAGER_URN}"
        )
    }

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val sock = MulticastSocket(null as SocketAddress?)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(UpnpConst.SSDP_PORT))

            val nif = NetUtil.findInterfaceByIp(hostIp) ?: firstUsableInterface()
            if (nif == null) {
                LogBus.e("找不到可用的网络接口，SSDP 无法启动")
                sock.close()
                return false
            }
            // 出站组播走 Wi-Fi 网卡；有些 ROM 有多个网卡（蜂窝/VPN），不指定会发错
            try {
                sock.networkInterface = nif
            } catch (t: Throwable) {
                LogBus.w("设置组播出口网卡失败（忽略）: ${t.message}")
            }
            val group = InetAddress.getByName(UpnpConst.SSDP_ADDR)
            sock.joinGroup(InetSocketAddress(group, UpnpConst.SSDP_PORT), nif)
            sock.soTimeout = 1000

            socket = sock
            running.set(true)
            LogBus.i("SSDP 已启动：监听 ${UpnpConst.SSDP_ADDR}:${UpnpConst.SSDP_PORT}（接口 ${nif.name} / $hostIp）")

            receiveJob = scope.launch(Dispatchers.IO) { receiveLoop(sock) }
            aliveJob = scope.launch(Dispatchers.IO) { aliveLoop(sock) }

            // 立刻广播一次，让控制端马上能发现
            sendAlive(sock)
            true
        } catch (t: Throwable) {
            LogBus.e("SSDP 启动失败：${t.message}")
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        val sock = socket
        socket = null
        try {
            if (sock != null) {
                sendByebye(sock)
                sock.close() // 关闭后 receive 会抛异常，接收循环自然退出
            }
        } catch (_: Throwable) {
        }
        receiveJob?.cancel()
        aliveJob?.cancel()
        receiveJob = null
        aliveJob = null
        LogBus.i("SSDP 已停止")
    }

    private fun firstUsableInterface(): NetworkInterface? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .firstOrNull { it.isUp && !it.isLoopback && !it.isVirtual }
    } catch (_: Throwable) {
        null
    }

    private suspend fun receiveLoop(sock: MulticastSocket) {
        val buf = ByteArray(4096)
        while (scope.isActive && running.get()) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                handleMsearch(sock, text, packet.address, packet.port)
            } catch (_: SocketTimeoutException) {
                // 正常，用于检查退出标志
            } catch (t: Throwable) {
                if (running.get()) LogBus.w("SSDP 接收异常：${t.message}")
                break
            }
        }
    }

    private suspend fun aliveLoop(sock: MulticastSocket) {
        while (scope.isActive && running.get()) {
            delay(UpnpConst.SSDP_ALIVE_INTERVAL_MS)
            if (running.get()) sendAlive(sock)
        }
    }

    private fun handleMsearch(sock: MulticastSocket, message: String, addr: InetAddress, port: Int) {
        if (!message.startsWith("M-SEARCH", ignoreCase = true)) return

        var st = ""
        var mx = 3
        for (line in message.split("\r\n")) {
            val lower = line.lowercase()
            when {
                lower.startsWith("st:") -> st = line.substringAfter(':').trim()
                lower.startsWith("mx:") -> mx = line.substringAfter(':').trim().toIntOrNull() ?: 3
            }
        }
        if (st.isEmpty()) return

        val matched = targets().filter { st == "ssdp:all" || st == it.first }
        if (matched.isEmpty()) return

        LogBus.i("收到 M-SEARCH  st=$st  from=${addr.hostAddress}")
        for ((targetSt, usn) in matched) {
            val payload = buildMsearchResponse(targetSt, usn)
            val delayMs = Random.nextLong(0, (mx.coerceIn(1, 3) * 1000L))
            // 规范要求在 MX 秒内随机延迟响应，避免多设备同时回包
            scope.launch(Dispatchers.IO) {
                try {
                    delay(delayMs)
                    sock.send(DatagramPacket(payload, payload.size, addr, port))
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun buildMsearchResponse(st: String, usn: String): ByteArray = (
        "HTTP/1.1 200 OK\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "EXT:\r\n" +
            "LOCATION: $location\r\n" +
            "SERVER: XiaoaiDLNA/${BuildConfig.VERSION_NAME} UPnP/1.0 DLNADOC/1.50\r\n" +
            "ST: $st\r\n" +
            "USN: $usn\r\n" +
            "BOOTID.UPNP.ORG: 1\r\n" +
            "CONFIGID.UPNP.ORG: 1\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

    private fun buildNotifyAlive(nt: String, usn: String): ByteArray = (
        "NOTIFY * HTTP/1.1\r\n" +
            "HOST: ${UpnpConst.SSDP_ADDR}:${UpnpConst.SSDP_PORT}\r\n" +
            "CACHE-CONTROL: max-age=1800\r\n" +
            "LOCATION: $location\r\n" +
            "NT: $nt\r\n" +
            "NTS: ssdp:alive\r\n" +
            "SERVER: XiaoaiDLNA/${BuildConfig.VERSION_NAME} UPnP/1.0 DLNADOC/1.50\r\n" +
            "USN: $usn\r\n" +
            "BOOTID.UPNP.ORG: 1\r\n" +
            "CONFIGID.UPNP.ORG: 1\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

    private fun buildNotifyByebye(nt: String, usn: String): ByteArray = (
        "NOTIFY * HTTP/1.1\r\n" +
            "HOST: ${UpnpConst.SSDP_ADDR}:${UpnpConst.SSDP_PORT}\r\n" +
            "NT: $nt\r\n" +
            "NTS: ssdp:byebye\r\n" +
            "USN: $usn\r\n" +
            "BOOTID.UPNP.ORG: 1\r\n" +
            "CONFIGID.UPNP.ORG: 1\r\n" +
            "\r\n"
        ).toByteArray(Charsets.UTF_8)

    private fun sendAlive(sock: MulticastSocket) {
        val group = try {
            InetAddress.getByName(UpnpConst.SSDP_ADDR)
        } catch (_: Throwable) {
            return
        }
        for ((nt, usn) in targets()) {
            try {
                val data = buildNotifyAlive(nt, usn)
                sock.send(DatagramPacket(data, data.size, group, UpnpConst.SSDP_PORT))
            } catch (_: Throwable) {
            }
        }
        LogBus.i("已广播 ssdp:alive（$friendlyName）")
    }

    private fun sendByebye(sock: MulticastSocket) {
        val group = try {
            InetAddress.getByName(UpnpConst.SSDP_ADDR)
        } catch (_: Throwable) {
            return
        }
        for ((nt, usn) in targets()) {
            try {
                val data = buildNotifyByebye(nt, usn)
                sock.send(DatagramPacket(data, data.size, group, UpnpConst.SSDP_PORT))
            } catch (_: Throwable) {
            }
        }
    }
}
