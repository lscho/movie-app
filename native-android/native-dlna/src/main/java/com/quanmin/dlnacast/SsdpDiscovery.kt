package com.quanmin.dlnacast

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSDP 设备发现（DLNA/UPnP M-SEARCH）。
 *
 * 设计要点，每一条都对应一个真实踩过的坑：
 *
 * 1. **主 socket 绑随机端口，而不是 1900**。
 *    真机上 1900 常被系统投屏服务或其他投屏 App 占用。UDP 的 SO_REUSEADDR 允许多个 socket
 *    绑同一端口，但**单播响应只会投递给其中一个**，抢不到的那个就永远搜不到设备
 *    —— 这正是「别的投屏软件能搜到、我们搜不到」的典型症状。
 *    绑随机端口后，M-SEARCH 的单播响应会回到我们自己的端口，稳。
 *
 * 2. **额外尽力再绑一个 1900 socket**。它能额外收到设备的 NOTIFY 广播与「回给组播地址」的
 *    响应（少数老机型这么干）。绑不上就算了，不影响主流程。
 *
 * 3. **显式指定组播出站网卡**。多网卡（Wi-Fi + 蜂窝 + VPN）时，系统默认路由可能把组播发到
 *    蜂窝或 VPN 上，设备自然收不到。必须按当前活动网络解析出网卡再 setNetworkInterface。
 *
 * 4. **必须持有 WifiManager.MulticastLock**。Wi-Fi 省电会丢弃组播/广播，症状是
 *    「第一次能搜到、之后再也搜不到」。
 *
 * 5. **先收包、后解析描述**。设备描述 XML 要单独发 HTTP 请求（可能耗时 1~2 秒），
 *    若在收包循环里同步拉取，会把后面的设备响应全堵在外面。这里先收集候选 LOCATION，
 *    收包结束后再统一解析。
 *
 * 6. **按 UDN 去重**。同一台电视可能回多个 LOCATION（实测创维电视同时回 :1206 与 :1838，
 *    其中 :1206 是坏的），必须允许某个地址解析失败后由另一个地址救回来，且最终只展示一台。
 */
internal class SsdpDiscovery(private val appContext: Context) {

    companion object {
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val MX = "2"

        /** 搜索目标：先精确的 MediaRenderer，再逐步放宽到全部设备 */
        private val SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "upnp:rootdevice",
            "ssdp:all"
        )

        /** 部分路由器禁组播但放行广播，作为兜底 */
        private const val BROADCAST_ADDR = "255.255.255.255"
    }

    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * 执行一次扫描。
     *
     * @param timeoutMs 总时长（建议 ≥ 4000，设备在 MX 秒内随机延迟响应）
     * @return 去重后的设备列表（按发现顺序）
     */
    fun scan(timeoutMs: Long): List<DlnaDevice> {
        cancelled.set(false)
        acquireMulticastLock()

        // location -> UDN 候选（收包阶段只记这个，不发 HTTP）
        val candidates = LinkedHashMap<String, String>()
        var sockets: Sockets? = null
        try {
            sockets = openSockets()
            val deadline = System.currentTimeMillis() + timeoutMs

            // 两轮「发 M-SEARCH + 收听」：UDP 丢包常见，重发显著提升发现率
            var round = 0
            while (!cancelled.get() && round < 2 && System.currentTimeMillis() < deadline) {
                sendSearch(sockets)
                val slice = minOf(1500L, deadline - System.currentTimeMillis())
                if (slice > 0) collect(sockets, slice, candidates)
                round++
            }
            // 剩余时间继续收（回包可能晚到）
            while (!cancelled.get() && System.currentTimeMillis() < deadline) {
                collect(sockets, deadline - System.currentTimeMillis(), candidates)
            }
        } finally {
            sockets?.closeAll()
            releaseMulticastLock()
        }

        if (cancelled.get()) return emptyList()

        diagCandidates = candidates.size

        // 收包结束后再逐个拉描述；按 UDN 去重，坏 LOCATION 不影响好 LOCATION
        val byUdn = LinkedHashMap<String, DlnaDevice>()
        for ((location, usnUdn) in candidates) {
            if (cancelled.get()) break
            val dev = describe(location, usnUdn) ?: continue
            if (!byUdn.containsKey(dev.udn)) byUdn[dev.udn] = dev
        }
        return byUdn.values.toList()
    }

    fun cancel() {
        cancelled.set(true)
        // 关掉 socket 让阻塞中的 receive 立刻返回
        activeSockets?.closeAll()
    }

    @Volatile
    private var activeSockets: Sockets? = null

    // ---- 诊断信息（搜不到设备时定位问题用） ----
    private var diagIface: String = "none"
    private var diagPackets: Int = 0
    private var diagCandidates: Int = 0
    private var diagSecondary: Boolean = false

    /** 返回本次扫描的可读诊断：网卡、收到的包数、候选数等 */
    fun diagnostics(): String =
        "iface=$diagIface, multicastSocket1900=$diagSecondary, " +
            "packets=$diagPackets, candidates=$diagCandidates"

    // ---------------------------------------------------------------- sockets

    /** 两个 socket：随机端口（主）+ 1900（可选增强） */
    private inner class Sockets(
        val primary: MulticastSocket,
        val secondary: MulticastSocket?,
        val ifaceAddress: InetAddress?
    ) {
        fun closeAll() {
            try {
                primary.close()
            } catch (_: Exception) {
            }
            try {
                secondary?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun openSockets(): Sockets {
        val ifaceAddress = resolveInterfaceAddress()

        // 主 socket：随机端口，必定能拿到单播响应
        val primary = MulticastSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
            soTimeout = 500
            try {
                timeToLive = 4
            } catch (_: Exception) {
            }
            bindInterface(this, ifaceAddress)
        }

        // 次 socket：1900，能多收到 NOTIFY 与组播回包；被占用就放弃
        val secondary = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SSDP_PORT))
                soTimeout = 500
                try {
                    timeToLive = 4
                } catch (_: Exception) {
                }
                bindInterface(this, ifaceAddress)
                try {
                    joinGroup(InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT), null)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            null
        }

        diagIface = ifaceAddress?.hostAddress ?: "none"
        diagSecondary = secondary != null
        return Sockets(primary, secondary, ifaceAddress).also { activeSockets = it }
    }

    /** 把 socket 的组播出站网卡固定到 Wi-Fi/有线，避免走蜂窝或 VPN */
    private fun bindInterface(socket: MulticastSocket, address: InetAddress?) {
        if (address == null) return
        try {
            val nif = NetworkInterface.getByInetAddress(address)
            if (nif != null) {
                socket.networkInterface = nif
            } else {
                socket.setInterface(address)
            }
        } catch (_: Exception) {
            // 设置失败不致命，退回系统默认路由
        }
    }

    /**
     * 解析当前活动网络的 IPv4 地址。
     * 优先 ConnectivityManager 的 LinkProperties（准确反映「系统正在用哪张网卡」），
     * 失败再退回枚举非回环接口。
     */
    private fun resolveInterfaceAddress(): InetAddress? {
        // 1) ConnectivityManager 路线（API 23+）
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            if (network != null) {
                val lp = cm.getLinkProperties(network)
                val name = lp?.interfaceName
                if (name != null) {
                    val nif = NetworkInterface.getByName(name)
                    val addr = nif?.inetAddresses?.toList()
                        ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                    if (addr != null) return addr
                }
            }
        } catch (_: Exception) {
        }

        // 2) 枚举网卡：优先 wlan / eth / ap，再退回任意可用 IPv4
        try {
            val candidates = mutableListOf<Pair<Int, InetAddress>>()
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                val name = nif.name.lowercase()
                val priority = when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("eth") -> 1
                    name.startsWith("ap") -> 2
                    name.startsWith("rmnet") || name.startsWith("ccmni") -> 9 // 蜂窝，最后考虑
                    else -> 5
                }
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                    candidates.add(priority to addr)
                }
            }
            return candidates.minByOrNull { it.first }?.second
        } catch (_: Exception) {
        }
        return null
    }

    // -------------------------------------------------------------- send/recv

    private fun sendSearch(sockets: Sockets) {
        val targets = SEARCH_TARGETS
        for (i in targets.indices) {
            val msg = buildSearchMessage(targets[i])
            sendTo(sockets.primary, msg)
            sockets.secondary?.let { sendTo(it, msg) }
            // 顺便发一份广播：少数路由器不放行组播但放行广播
            sendTo(sockets.primary, msg, BROADCAST_ADDR)
            if (i < targets.size - 1) {
                try {
                    Thread.sleep(120)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    private fun buildSearchMessage(target: String): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: ").append(SSDP_ADDR).append(':').append(SSDP_PORT).append("\r\n")
        // MAN 的引号必须保留，缺了设备会忽略整条请求
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: ").append(MX).append("\r\n")
        append("ST: ").append(target).append("\r\n")
        // 部分设备按 UA 决定是否响应，用通用 UPnP UA
        append("USER-AGENT: Android/10 UPnP/1.1 DLNADOC/1.50\r\n")
        append("\r\n")
    }.toByteArray(Charsets.UTF_8)

    private fun sendTo(
        socket: MulticastSocket,
        msg: ByteArray,
        address: String = SSDP_ADDR
    ) {
        try {
            socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName(address), SSDP_PORT))
        } catch (_: Exception) {
            // 单个发送失败（如广播被禁用）不影响其他方式
        }
    }

    /** 在给定时间内收听两个 socket，把候选 LOCATION 收集起来 */
    private fun collect(
        sockets: Sockets,
        windowMs: Long,
        out: MutableMap<String, String>
    ) {
        val end = System.currentTimeMillis() + windowMs
        val buf = ByteArray(8192)
        while (!cancelled.get() && System.currentTimeMillis() < end) {
            val slice = minOf(250L, end - System.currentTimeMillis())
            if (slice <= 0) break
            var received = false
            for (socket in listOfNotNull(sockets.primary, sockets.secondary)) {
                try {
                    socket.soTimeout = slice.toInt().coerceAtLeast(1)
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    received = true
                    diagPackets++
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    parseResponse(text)?.let { (location, udn) ->
                        if (!out.containsKey(location)) out[location] = udn
                    }
                } catch (_: SocketTimeoutException) {
                    // 正常
                } catch (_: Exception) {
                    // socket 已关闭（cancel）或坏包，跳出
                    if (cancelled.get()) return
                }
            }
            if (!received) {
                // 两个 socket 都没数据，交给下一轮（让出 CPU 并检查 cancel）
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /**
     * 解析 SSDP 响应头，返回 (LOCATION, USN 里的 UDN)。
     * 注意真实设备返回的是 `Location:`（首字母大写），必须大小写不敏感匹配。
     */
    private fun parseResponse(text: String): Pair<String, String>? {
        val firstLine = text.lineSequence().firstOrNull()?.trim() ?: return null
        val isSearchHit = firstLine.startsWith("HTTP/1.1 200", ignoreCase = true) ||
            firstLine.startsWith("HTTP/1.0 200", ignoreCase = true)
        // 少数设备只会定期广播 ssdp:alive，从不响应 M-SEARCH，这里一并收下
        val isNotify = firstLine.startsWith("NOTIFY * HTTP/1.1", ignoreCase = true)
        if (!isSearchHit && !isNotify) return null
        var location: String? = null
        var usn = ""
        for (rawLine in text.split("\r\n")) {
            val idx = rawLine.indexOf(':')
            if (idx <= 0) continue
            val key = rawLine.substring(0, idx).trim()
            val value = rawLine.substring(idx + 1).trim()
            when {
                key.equals("LOCATION", ignoreCase = true) -> location = value
                key.equals("USN", ignoreCase = true) -> usn = value
                key.equals("NTS", ignoreCase = true) && value.equals("ssdp:byebye", ignoreCase = true) -> return null
            }
        }
        val loc = location?.takeIf { it.isNotBlank() } ?: return null
        // USN 形如 "uuid:xxxx-HD-CAST-dmr::urn:schemas-upnp-org:device:MediaRenderer:1"
        // 只取 :: 前面的 uuid 部分
        val udn = usn.substringBefore("::").trim()
        return loc to udn
    }

    // ------------------------------------------------------------- describe

    /** 拉设备描述 XML 并解析；失败返回 null（该 LOCATION 作废，但不影响同设备其他地址） */
    private fun describe(location: String, fallbackUdn: String): DlnaDevice? {
        return try {
            val xml = SoapClient.fetchText(location, timeoutMs = 2500) ?: return null
            val profile = DeviceDescriptionParser.parse(xml, location) ?: return null
            // 没有 AVTransport 的设备无法投屏，直接过滤掉
            if (profile.avTransportControlUrl.isEmpty()) return null
            DlnaDevice(
                name = profile.name.ifBlank { "未知设备" },
                udn = profile.udn.ifBlank { fallbackUdn },
                location = location
            )
        } catch (_: Exception) {
            null
        }
    }

    // ----------------------------------------------------------- multicast

    private fun acquireMulticastLock() {
        try {
            val wifi = appContext.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = wifi.createMulticastLock("uni-dlna-cast")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (_: Exception) {
            // 以太网或无 Wi-Fi 时拿不到锁，扫描依然可能成功
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }
}
