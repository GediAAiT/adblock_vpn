package com.example.adblock_vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import io.flutter.plugin.common.MethodChannel
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.min

/**
 * DNS-filtering VPN service.
 *
 * This service keeps the VPN lightweight by handling DNS traffic only. Normal
 * app traffic is not proxied. It blocks known ad/tracker hostnames, catches
 * common hardcoded DNS resolvers in strict mode. Compatibility mode uses empty
 * successful answers and narrower routes; it cannot hide Android's VPN state.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.example.adblock_vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.example.adblock_vpn.DISCONNECT"
        const val NOTIF_CHANNEL_ID = "adblock_vpn_channel"
        const val NOTIF_ID = 1

        private const val IPV4_VPN_ADDRESS = "10.0.0.2"
        private const val IPV4_FAKE_DNS_ADDRESS = "10.0.0.1"
        private const val IPV6_VPN_ADDRESS = "fd00:1:fd00:1::2"
        private const val IPV6_FAKE_DNS_ADDRESS = "fd00:1:fd00:1::1"
        private const val DNS_PORT = 53

        private val HARD_CODED_DNS_IPV4_ROUTES = arrayOf(
            "1.1.1.1",
            "1.0.0.1",
            "8.8.8.8",
            "8.8.4.4",
            "9.9.9.9",
            "149.112.112.112",
            "208.67.222.222",
            "208.67.220.220",
            "94.140.14.14",
            "94.140.15.15",
            "76.76.2.0",
            "76.76.10.0",
            "185.228.168.9",
            "185.228.169.9"
        )
        private val HARD_CODED_DNS_IPV6_ROUTES = arrayOf(
            "2606:4700:4700::1111",
            "2606:4700:4700::1001",
            "2001:4860:4860::8888",
            "2001:4860:4860::8844",
            "2620:fe::fe",
            "2620:fe::9",
            "2620:119:35::35",
            "2620:119:53::53",
            "2a10:50c0::ad1:ff",
            "2a10:50c0::ad2:ff"
        )

        private val BUILT_IN_BLOCKED_DOMAINS = arrayOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "googleads.g.doubleclick.net",
            "pagead2.googlesyndication.com",
            "securepubads.g.doubleclick.net",
            "pubads.g.doubleclick.net",
            "adservice.google.com",
            "admob.com",
            "mobileads.google.com",
            "imasdk.googleapis.com",
            "amazon-adsystem.com",
            "aaxads.com",
            "ads.facebook.com",
            "an.facebook.com",
            "audience_network.facebook.com",
            "applovin.com",
            "applovin.net",
            "unityads.unity3d.com",
            "ads.unity.com",
            "vungle.com",
            "chartboost.com",
            "adcolony.com",
            "mopub.com",
            "ironsrc.com",
            "ironsource.com",
            "supersonicads.com",
            "tapjoy.com",
            "inmobi.com",
            "startapp.com",
            "startappexchange.com",
            "mbridge.com",
            "mintegral.com",
            "pangle.io",
            "pangolin-sdk-toutiao.com",
            "liftoff.io",
            "appnext.com",
            "mytarget.ru",
            "smaato.net",
            "smartadserver.com",
            "ogury.io",
            "ogury.com",
            "bidmachine.io",
            "adjoe.zone",
            "adnxs.com",
            "appnexus.com",
            "adsrvr.org",
            "casalemedia.com",
            "bidswitch.net",
            "yieldmo.com",
            "sharethrough.com",
            "pubmatic.com",
            "rubiconproject.com",
            "openx.net",
            "openx.com",
            "adform.net",
            "criteo.com",
            "criteo.net",
            "outbrain.com",
            "taboola.com",
            "indexww.com",
            "triplelift.com",
            "media.net",
            "advertising.com",
            "quantserve.com",
            "revcontent.com",
            "mgid.com",
            "exoclick.com",
            "popads.net",
            "propellerads.com",
            "adsterra.com",
            "adcash.com",
            "hilltopads.net",
            "admaven.com",
            "popcash.net",
            "freewheel.tv",
            "fwmrm.net",
            "spotxchange.com",
            "spotx.tv",
            "smartclip.net",
            "vidverto.io",
            "trafficstars.com",
            "clickadu.com",
            "juicyads.com",
            "onclickads.net",
            "adkernel.com",
            "adnuntius.com",
            "teads.tv",
            "rtbhouse.com",
            "33across.com",
            "gumgum.com",
            "sovrn.com",
            "lijit.com",
            "bidvertiser.com",
            "coinis.com",
            "springserve.com",
            "vidoomy.com",
            "lkqd.net",
            "adsafeprotected.com",
            "serving-sys.com",
            "moatads.com",
            "innovid.com",
            "adjust.com",
            "appsflyer.com",
            "flurry.com",
            "amplitude.com",
            "mixpanel.com",
            "scorecardresearch.com",
            "mmstat.com",
            "dns.google",
            "cloudflare-dns.com",
            "one.one.one.one",
            "dns.quad9.net",
            "doh.opendns.com",
            "dns.adguard-dns.com",
            "dns.nextdns.io"
        )

        @Volatile var isRunning: Boolean = false
        @Volatile var state = "disconnected"
        @Volatile var lastError = ""
        @Volatile var totalBlocked = 0
        var statsChannel: MethodChannel? = null

        // Bounded, memory-only diagnostics. Never infer which app made a query.
        private val recentBlocked = LinkedHashSet<String>()
        fun recentBlockedDomains(): List<String> = synchronized(recentBlocked) {
            recentBlocked.toList().asReversed()
        }
        fun clearRecentBlockedDomains() = synchronized(recentBlocked) { recentBlocked.clear() }
        private fun recordBlocked(host: String) = synchronized(recentBlocked) {
            val normalized = LocalDnsPolicy.normalize(host)
            recentBlocked.remove(normalized)
            recentBlocked.add(normalized)
            while (recentBlocked.size > 30) recentBlocked.remove(recentBlocked.first())
        }

        private val WHITESPACE = Regex("\\s+")
    }

    private data class DnsName(val value: String, val nextOffset: Int)
    private data class DnsQuestion(val name: String, val type: Int, val endOffset: Int)

    @Volatile private var vpnInterface: ParcelFileDescriptor? = null
    private val running = AtomicBoolean(false)
    private var workerThread: Thread? = null
    private val blockedCount = AtomicInteger(0)
    private lateinit var blocklist: HashSet<String>
    private val cancelled = AtomicBoolean(false)
    private val mainHandler by lazy { Handler(mainLooper) }
    private var startupGeneration = 0
    private val activeSockets = HashSet<DatagramSocket>()
    private var dnsWorkers = newDnsWorkers()
    private var policy = LocalDnsPolicy()

    private fun newDnsWorkers() = java.util.concurrent.ThreadPoolExecutor(
        4, 4, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(128),
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
    )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_CONNECT -> {
                if (state == "connecting" || running.get()) return START_NOT_STICKY
                cancelled.set(false)
                state = "connecting"
                lastError = ""
                val generation = ++startupGeneration
                startForegroundNotification()
                // The bundled blocklist has tens of thousands of entries; load it
                // off the main thread so service startup can't trigger an ANR.
                thread(start = true, name = "AdBlockVpnStartup") {
                    try {
                        val loadedBlocklist = loadBlocklist()
                        val prefs = getSharedPreferences("local_dns", MODE_PRIVATE)
                        val loadedPolicy = LocalDnsPolicy(
                            prefs.getBoolean("compatibility", false),
                            prefs.getStringSet("allowedDomains", emptySet())!!.toSet()
                        )
                        mainHandler.post {
                            if (cancelled.get() || generation != startupGeneration) return@post
                            try {
                                blocklist = loadedBlocklist
                                policy = loadedPolicy
                                startVpn()
                            } catch (e: Exception) {
                                lastError = e.message ?: "Unable to start DNS filtering"
                                stopVpn()
                            }
                        }
                    } catch (e: Exception) {
                        mainHandler.post {
                            if (!cancelled.get() && generation == startupGeneration) {
                                lastError = e.message ?: "Unable to start DNS filtering"
                                stopVpn()
                            }
                        }
                    }
                }
                return START_NOT_STICKY
            }
            else -> {
                stopVpn()
                return START_NOT_STICKY
            }
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "Ad Blocking", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("AdBlock VPN")
            .setContentText("Filtering DNS for protected apps")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .addAction(0, "Stop protection", PendingIntent.getService(
                this, 0, Intent(this, AdBlockVpnService::class.java).apply {
                    action = ACTION_DISCONNECT
                }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))
            .build()
        startForeground(NOTIF_ID, notification)
    }

    private fun loadBlocklist(): HashSet<String> {
        // Sized for the bundled ~46k-domain list to avoid rehashing during load.
        val set = HashSet<String>(1 shl 17)
        BUILT_IN_BLOCKED_DOMAINS.forEach { addBlocklistEntry(it, set) }
        try {
            assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.forEach { addBlocklistEntry(it, set) }
            }
        } catch (_: Exception) {
            // Keep the VPN usable even if the optional asset is missing.
        }
        return set
    }

    private fun addBlocklistEntry(rawLine: String, set: MutableSet<String>) {
        var line = rawLine.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!") || line.startsWith("@@")) return

        // Fast path: the bundled list is plain lowercase domains, one per line.
        if (isPlainDomain(line)) {
            set.add(line)
            return
        }

        val commentIndex = line.indexOf('#')
        if (commentIndex >= 0) line = line.substring(0, commentIndex).trim()
        if (line.isEmpty()) return

        val tokens = line.split(WHITESPACE).filter { it.isNotBlank() }
        val domainTokens = if (tokens.size > 1 && isAddressToken(tokens.first())) {
            tokens.drop(1)
        } else {
            listOf(tokens.first())
        }

        domainTokens.forEach { token ->
            normalizeBlocklistHost(token)?.let { set.add(it) }
        }
    }

    private fun isPlainDomain(line: String): Boolean {
        var hasDot = false
        for (c in line) {
            when {
                c == '.' -> hasDot = true
                c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' -> {}
                else -> return false
            }
        }
        return hasDot && !line.startsWith(".") && !line.endsWith(".") &&
            !line.all { it.isDigit() || it == '.' }
    }

    private fun isAddressToken(token: String): Boolean {
        if (token == "0.0.0.0" || token == "127.0.0.1" || token == "::" || token == "::1") {
            return true
        }
        return token.contains(":") || token.count { it == '.' } == 3 && token.all { it.isDigit() || it == '.' }
    }

    private fun normalizeBlocklistHost(rawValue: String): String? {
        var host = rawValue.trim().lowercase()
        if (host.isEmpty() || host.startsWith("@@")) return null

        val optionIndex = host.indexOf('$')
        if (optionIndex >= 0) host = host.substring(0, optionIndex)
        if (host.startsWith("||")) host = host.substring(2)
        while (host.startsWith("|")) host = host.substring(1)

        val schemeIndex = host.indexOf("://")
        if (schemeIndex >= 0) host = host.substring(schemeIndex + 3)

        host = host.substringBefore("/")
            .substringBefore("^")
            .substringBefore(":")
            .trim()

        while (host.startsWith("*.")) host = host.substring(2)
        while (host.startsWith(".")) host = host.substring(1)
        while (host.endsWith(".")) host = host.dropLast(1)

        if (host.isEmpty() || !host.contains(".")) return null
        if (host.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_') }) {
            return null
        }
        return host
    }

    private fun isBlocked(host: String): Boolean {
        if (policy.bypassBlocklist(host)) return false
        var current = normalizeDnsHost(host) ?: return false
        while (true) {
            if (blocklist.contains(current)) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
    }

    private fun normalizeDnsHost(host: String): String? {
        var normalized = host.trim().lowercase()
        while (normalized.endsWith(".")) normalized = normalized.dropLast(1)
        return normalized.takeIf { it.isNotEmpty() }
    }

    private fun startVpn() {
        if (running.get()) return
        if (dnsWorkers.isShutdown) dnsWorkers = newDnsWorkers()

        val builder = Builder()
            .setSession("AdBlock VPN")
            .addAddress(IPV4_VPN_ADDRESS, 32)
            .addDnsServer(IPV4_FAKE_DNS_ADDRESS)
            .addRoute(IPV4_FAKE_DNS_ADDRESS, 32)
            .setMtu(1500)

        // Keep server discovery and its secure DNS fallback off our own DNS
        // routes, including while a previous local session is shutting down.
        AppCompatibility.excludedPackages(this).forEach { packageName ->
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                // An uninstalled app must not prevent protection from starting.
            }
        }

        if (!policy.compatibility) {
            HARD_CODED_DNS_IPV4_ROUTES.forEach { builder.addRoute(it, 32) }
        }

        try {
            builder
                .addAddress(IPV6_VPN_ADDRESS, 128)
                .addDnsServer(IPV6_FAKE_DNS_ADDRESS)
                .addRoute(IPV6_FAKE_DNS_ADDRESS, 128)
            if (!policy.compatibility) {
                HARD_CODED_DNS_IPV6_ROUTES.forEach { builder.addRoute(it, 128) }
            }
        } catch (_: Exception) {
            // IPv4 DNS filtering still works on devices/networks without IPv6 support.
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) throw IllegalStateException("VPN permission was revoked")

        running.set(true)
        isRunning = true
        state = "connected"
        totalBlocked = 0
        blockedCount.set(0)
        clearRecentBlockedDomains()

        val sessionInterface = vpnInterface!!
        workerThread = thread(start = true, name = "AdBlockVpnDnsLoop") {
            runPacketLoop(sessionInterface)
        }
    }

    private fun stopVpn() {
        startupGeneration++
        cancelled.set(true)
        running.set(false)
        isRunning = false
        state = "disconnecting"
        dnsWorkers.shutdownNow()
        synchronized(activeSockets) {
            activeSockets.forEach { it.close() }
            activeSockets.clear()
        }
        workerThread?.interrupt()
        workerThread = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        state = "disconnected"
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onRevoke() {
        mainHandler.post { stopVpn() }
    }

    private fun runPacketLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val packet = ByteArray(32767)

        while (running.get() && vpnInterface === fd) {
            val length = try {
                input.read(packet)
            } catch (_: Exception) {
                break
            }
            if (length <= 0) {
                // Android's TUN is nonblocking. Avoid spinning when it is idle.
                try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                continue
            }

            try {
                handleIpPacket(packet, length, output)
            } catch (_: Exception) {
                // Malformed or unsupported packet. Drop it and keep the VPN loop alive.
            }
        }
        mainHandler.post {
            if (running.get() && vpnInterface === fd) stopVpn()
        }
    }

    private fun handleIpPacket(packet: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 1) return
        when ((packet[0].toInt() shr 4) and 0xF) {
            4 -> handleIpv4Packet(packet, length, output)
            6 -> handleIpv6Packet(packet, length, output)
        }
    }

    private fun handleIpv4Packet(packet: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 28) return
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return
        if ((packet[9].toInt() and 0xFF) != 17) return

        val totalLength = min(length, readU16(packet, 2))
        if (totalLength < ihl + 8) return

        val srcIp = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val dstIp = InetAddress.getByAddress(packet.copyOfRange(16, 20))
        handleUdpDnsPacket(packet, totalLength, ihl, srcIp, dstIp, output)
    }

    private fun handleIpv6Packet(packet: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 48) return
        if ((packet[6].toInt() and 0xFF) != 17) return

        val payloadLength = readU16(packet, 4)
        val packetEnd = min(length, 40 + payloadLength)
        if (packetEnd < 48) return

        val srcIp = InetAddress.getByAddress(packet.copyOfRange(8, 24))
        val dstIp = InetAddress.getByAddress(packet.copyOfRange(24, 40))
        handleUdpDnsPacket(packet, packetEnd, 40, srcIp, dstIp, output)
    }

    private fun handleUdpDnsPacket(
        packet: ByteArray,
        packetEnd: Int,
        udpStart: Int,
        srcIp: InetAddress,
        dstIp: InetAddress,
        output: FileOutputStream
    ) {
        if (udpStart + 8 > packetEnd) return

        val srcPort = readU16(packet, udpStart)
        val dstPort = readU16(packet, udpStart + 2)
        if (dstPort != DNS_PORT) return

        val udpLength = readU16(packet, udpStart + 4)
        if (udpLength < 8) return

        val dnsStart = udpStart + 8
        val dnsEnd = min(packetEnd, udpStart + udpLength)
        if (dnsEnd <= dnsStart + 12) return

        val dnsPayload = packet.copyOfRange(dnsStart, dnsEnd)
        val question = parseDnsQuestion(dnsPayload) ?: return

        if (isBlocked(question.name)) {
            blockAndReply(dnsPayload, question, output, dstIp, dstPort, srcIp, srcPort)
        } else {
            forwardToUpstreamDns(dnsPayload, question, srcIp, srcPort, dstIp, dstPort, output)
        }
    }

    private fun blockAndReply(
        dnsPayload: ByteArray,
        question: DnsQuestion,
        output: FileOutputStream,
        srcIp: InetAddress,
        srcPort: Int,
        dstIp: InetAddress,
        dstPort: Int
    ) {
        totalBlocked = blockedCount.incrementAndGet()
        recordBlocked(question.name)
        reportStats()
        val response = buildDnsNoDataResponse(dnsPayload, question)
        sendUdpResponse(output, srcIp, srcPort, dstIp, dstPort, response)
    }

    private fun parseDnsQuestion(dns: ByteArray): DnsQuestion? {
        if (dns.size < 12 || readU16(dns, 4) == 0) return null
        val name = readDnsName(dns, 12) ?: return null
        if (name.nextOffset + 4 > dns.size) return null
        val type = readU16(dns, name.nextOffset)
        return DnsQuestion(name.value, type, name.nextOffset + 4)
    }

    private fun readDnsName(dns: ByteArray, start: Int): DnsName? {
        var pos = start
        var nextOffset = -1
        var jumped = false
        var depth = 0
        val labels = ArrayList<String>()
        val seenPointers = HashSet<Int>()

        while (pos < dns.size && depth < 64) {
            val len = dns[pos].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (!jumped) nextOffset = pos + 1
                    return DnsName(labels.joinToString("."), nextOffset)
                }
                (len and 0xC0) == 0xC0 -> {
                    if (pos + 1 >= dns.size) return null
                    val pointer = ((len and 0x3F) shl 8) or (dns[pos + 1].toInt() and 0xFF)
                    if (pointer >= dns.size || !seenPointers.add(pointer)) return null
                    if (!jumped) nextOffset = pos + 2
                    pos = pointer
                    jumped = true
                    depth++
                }
                (len and 0xC0) != 0 -> return null
                else -> {
                    val labelStart = pos + 1
                    val labelEnd = labelStart + len
                    if (labelEnd > dns.size) return null
                    labels.add(String(dns, labelStart, len, Charsets.US_ASCII))
                    pos = labelEnd
                    if (!jumped) nextOffset = pos
                }
            }
        }

        return null
    }

    private fun buildDnsNoDataResponse(query: ByteArray, question: DnsQuestion): ByteArray {
        val response = query.copyOf(question.endOffset)
        response[2] = (0x80 or (query[2].toInt() and 0x01)).toByte()
        // NOERROR/NODATA avoids an explicit nonexistent-domain error in
        // compatibility mode. Missing ad responses can still be detected.
        response[3] = (if (policy.compatibility) 0x80 else 0x83).toByte()
        writeU16(response, 4, 1)
        writeU16(response, 6, 0)
        writeU16(response, 8, 0)
        writeU16(response, 10, 0)
        return response
    }

    private fun forwardToUpstreamDns(
        dnsQuery: ByteArray,
        question: DnsQuestion,
        clientIp: InetAddress,
        clientPort: Int,
        requestedDnsIp: InetAddress,
        requestedDnsPort: Int,
        output: FileOutputStream
    ) {
        dnsWorkers.execute {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                synchronized(activeSockets) {
                    if (!running.get() || Thread.currentThread().isInterrupted) return@execute
                    activeSockets.add(socket)
                }
                if (!protect(socket)) return@execute
                socket.soTimeout = 3500

                for (upstream in policy.resolvers(question.name).map { InetAddress.getByName(it) }) {
                    if (socket.isClosed || Thread.currentThread().isInterrupted) return@execute
                    try {
                        socket.connect(upstream, DNS_PORT)
                        val outPacket = DatagramPacket(dnsQuery, dnsQuery.size, upstream, DNS_PORT)
                        socket.send(outPacket)

                        val replyBuf = ByteArray(4096)
                        val inPacket = DatagramPacket(replyBuf, replyBuf.size)
                        socket.receive(inPacket)

                        val reply = replyBuf.copyOfRange(0, inPacket.length)
                        if (reply.size < 12 || readU16(reply, 0) != readU16(dnsQuery, 0) ||
                            (reply[2].toInt() and 0x80) == 0) continue
                        val replyQuestion = parseDnsQuestion(reply) ?: continue
                        if (!replyQuestion.name.equals(question.name, ignoreCase = true) ||
                            replyQuestion.type != question.type) continue
                        // An explicit exception also permits its CNAME chain;
                        // otherwise a shared CDN alias could defeat the exception.
                        if (!policy.bypassBlocklist(question.name) && dnsResponseReferencesBlockedDomain(reply)) {
                            blockAndReply(
                                dnsQuery,
                                question,
                                output,
                                requestedDnsIp,
                                requestedDnsPort,
                                clientIp,
                                clientPort
                            )
                        } else {
                            sendUdpResponse(output, requestedDnsIp, requestedDnsPort, clientIp, clientPort, reply)
                        }
                        return@execute
                    } catch (_: Exception) {
                        // Try the next resolver. If all fail, the client DNS stack will retry.
                    }
                }
            } finally {
                synchronized(activeSockets) { activeSockets.remove(socket) }
                socket?.close()
            }
        }
    }

    private fun isFakeDnsAddress(address: InetAddress): Boolean {
        return try {
            address == InetAddress.getByName(IPV4_FAKE_DNS_ADDRESS) ||
                address == InetAddress.getByName(IPV6_FAKE_DNS_ADDRESS)
        } catch (_: Exception) {
            false
        }
    }

    private fun dnsResponseReferencesBlockedDomain(response: ByteArray): Boolean {
        if (response.size < 12) return false

        var pos = 12
        repeat(readU16(response, 4)) {
            val questionName = readDnsName(response, pos) ?: return false
            if (isBlocked(questionName.value)) return true
            pos = questionName.nextOffset + 4
            if (pos > response.size) return false
        }

        val recordCount = readU16(response, 6) + readU16(response, 8) + readU16(response, 10)
        repeat(recordCount) {
            val ownerName = readDnsName(response, pos) ?: return false
            if (isBlocked(ownerName.value)) return true
            pos = ownerName.nextOffset
            if (pos + 10 > response.size) return false

            val type = readU16(response, pos)
            val dataLength = readU16(response, pos + 8)
            val dataStart = pos + 10
            val dataEnd = dataStart + dataLength
            if (dataEnd > response.size) return false

            if (type == 5 || type == 2 || type == 12) {
                readDnsName(response, dataStart)?.let { target ->
                    if (isBlocked(target.value)) return true
                }
            }
            pos = dataEnd
        }

        return false
    }

    private fun sendUdpResponse(
        output: FileOutputStream,
        srcIp: InetAddress,
        srcPort: Int,
        dstIp: InetAddress,
        dstPort: Int,
        dnsPayload: ByteArray
    ) {
        if (srcIp.address.size == 16 || dstIp.address.size == 16) {
            sendUdpIpv6Response(output, srcIp, srcPort, dstIp, dstPort, dnsPayload)
        } else {
            sendUdpIpv4Response(output, srcIp, srcPort, dstIp, dstPort, dnsPayload)
        }
    }

    private fun sendUdpIpv4Response(
        output: FileOutputStream,
        srcIp: InetAddress,
        srcPort: Int,
        dstIp: InetAddress,
        dstPort: Int,
        dnsPayload: ByteArray
    ) {
        val udpLength = 8 + dnsPayload.size
        val totalLength = 20 + udpLength
        val packet = ByteArray(totalLength)
        val buf = ByteBuffer.wrap(packet)

        buf.put(0, 0x45.toByte())
        buf.put(1, 0)
        buf.putShort(2, totalLength.toShort())
        buf.putShort(4, 0)
        buf.putShort(6, 0)
        buf.put(8, 64)
        buf.put(9, 17)
        buf.putShort(10, 0)
        System.arraycopy(srcIp.address, 0, packet, 12, 4)
        System.arraycopy(dstIp.address, 0, packet, 16, 4)
        buf.putShort(10, checksum(packet, 0, 20).toShort())

        val udpStart = 20
        buf.putShort(udpStart, srcPort.toShort())
        buf.putShort(udpStart + 2, dstPort.toShort())
        buf.putShort(udpStart + 4, udpLength.toShort())
        buf.putShort(udpStart + 6, 0)
        System.arraycopy(dnsPayload, 0, packet, udpStart + 8, dnsPayload.size)

        synchronized(output) {
            output.write(packet)
        }
    }

    private fun sendUdpIpv6Response(
        output: FileOutputStream,
        srcIp: InetAddress,
        srcPort: Int,
        dstIp: InetAddress,
        dstPort: Int,
        dnsPayload: ByteArray
    ) {
        val srcAddress = srcIp.address
        val dstAddress = dstIp.address
        if (srcAddress.size != 16 || dstAddress.size != 16) return

        val udpLength = 8 + dnsPayload.size
        val totalLength = 40 + udpLength
        val packet = ByteArray(totalLength)
        val buf = ByteBuffer.wrap(packet)

        buf.putInt(0, 0x60000000)
        buf.putShort(4, udpLength.toShort())
        buf.put(6, 17)
        buf.put(7, 64)
        System.arraycopy(srcAddress, 0, packet, 8, 16)
        System.arraycopy(dstAddress, 0, packet, 24, 16)

        val udpStart = 40
        buf.putShort(udpStart, srcPort.toShort())
        buf.putShort(udpStart + 2, dstPort.toShort())
        buf.putShort(udpStart + 4, udpLength.toShort())
        buf.putShort(udpStart + 6, 0)
        System.arraycopy(dnsPayload, 0, packet, udpStart + 8, dnsPayload.size)
        buf.putShort(udpStart + 6, ipv6UdpChecksum(packet, udpStart, udpLength).toShort())

        synchronized(output) {
            output.write(packet)
        }
    }

    private fun ipv6UdpChecksum(packet: ByteArray, udpStart: Int, udpLength: Int): Int {
        var sum = 0L
        fun addWord(value: Int) {
            sum += (value and 0xFFFF).toLong()
        }
        fun addBytes(offset: Int, length: Int) {
            var i = offset
            while (i < offset + length - 1) {
                addWord(((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF))
                i += 2
            }
            if (length % 2 == 1) {
                addWord((packet[offset + length - 1].toInt() and 0xFF) shl 8)
            }
        }

        addBytes(8, 16)
        addBytes(24, 16)
        addWord(udpLength shr 16)
        addWord(udpLength)
        addWord(17)
        addBytes(udpStart, udpLength)

        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = sum.inv().toInt() and 0xFFFF
        return if (checksum == 0) 0xFFFF else checksum
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (length % 2 == 1) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv() and 0xFFFF
    }

    private fun reportStats() {
        val channel = statsChannel ?: return
        val args = HashMap<String, Any>()
        args["blockedCount"] = blockedCount.get()
        android.os.Handler(mainLooper).post {
            channel.invokeMethod("onStatsUpdate", args)
        }
    }
}
