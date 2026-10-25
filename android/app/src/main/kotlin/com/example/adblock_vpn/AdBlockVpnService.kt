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
 * Stealth DNS-filtering VPN service.
 *
 * Announces standard Google Public DNS (8.8.8.8 / 8.8.4.4) in LinkProperties,
 * forwards allowed queries to standard non-AdGuard upstream resolvers
 * (8.8.8.8 / 1.1.1.1), resolves blocked ad domains to deterministic stealth
 * sinkhole IPs (198.18.0.0/15 and 2001:db8:ad::/48) routed into the local TUN
 * interface, and emulates TCP/HTTP no-fill responses on the sinkhole subnet so
 * HDO Box, MovieBox, Playfy, and other APKs never detect custom DNS or ad
 * blocking.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.example.adblock_vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.example.adblock_vpn.DISCONNECT"
        const val NOTIF_CHANNEL_ID = "adblock_vpn_channel"
        const val NOTIF_ID = 1

        private const val IPV4_VPN_ADDRESS = "10.0.0.2"
        private const val IPV4_FAKE_DNS_ADDRESS = "10.0.0.1"
        private const val IPV4_PRIMARY_DNS = "8.8.8.8"
        private const val IPV4_SECONDARY_DNS = "8.8.4.4"
        private const val STEALTH_SINKHOLE_IPV4_ROUTE = "198.18.0.0"
        private const val STEALTH_SINKHOLE_IPV4_PREFIX = 15

        private const val IPV6_VPN_ADDRESS = "fd00:1:fd00:1::2"
        private const val IPV6_FAKE_DNS_ADDRESS = "fd00:1:fd00:1::1"
        private const val IPV6_PRIMARY_DNS = "2001:4860:4860::8888"
        private const val IPV6_SECONDARY_DNS = "2001:4860:4860::8844"
        private const val STEALTH_SINKHOLE_IPV6_ROUTE = "2001:db8:ad::"
        private const val STEALTH_SINKHOLE_IPV6_PREFIX = 48

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
            "94.140.14.15",
            "94.140.14.140",
            "76.76.2.0",
            "76.76.10.0",
            "185.228.168.9",
            "185.228.169.9",
            "45.90.28.0",
            "45.90.30.0",
            "77.88.8.8",
            "77.88.8.1",
            "4.2.2.1",
            "4.2.2.2",
            "64.6.64.6",
            "64.6.65.6"
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
            "2a10:50c0::ad2:ff",
            "2a07:a8c0::",
            "2a07:a8c1::"
        )

        private val BUILT_IN_BLOCKED_DOMAINS = arrayOf(
            // Google AdMob / DoubleClick / IMA / Ad Traffic Quality
            "doubleclick.net",
            "doubleclick.com",
            "doubleclickbygoogle.com",
            "googlesyndication.com",
            "googleadservices.com",
            "googleads.g.doubleclick.net",
            "pagead2.googlesyndication.com",
            "securepubads.g.doubleclick.net",
            "pubads.g.doubleclick.net",
            "adservice.google.com",
            "admob.com",
            "admob.biz",
            "mobileads.google.com",
            "imasdk.googleapis.com",
            "idad.google.com",
            "fundingchoicesmessages.google.com",
            "adtrafficquality.google",
            "ep1.adtrafficquality.google",
            "ep2.adtrafficquality.google",
            "app-measurement.com",
            "google-analytics.com",
            "googletagmanager.com",
            "googletagservices.com",
            "2mdn.net",
            "admeld.com",
            "firebaselogging-pa.googleapis.com",
            "firebaselogging.googleapis.com",
            "ogads-pa.googleapis.com",
            "telemetry.googleapis.com",
            // MovieBox / Hisavana / Transsion / Shalltry Ad & Telemetry SDKs
            "hisavana.com",
            "api.hisavana.com",
            "cc-api.hisavana.com",
            "cm-oss.hisavana.com",
            "ad.hisavana.com",
            "ads.hisavana.com",
            "cdn.hisavana.com",
            "ssp.hisavana.com",
            "mediation.hisavana.com",
            "shallspark.com",
            "ire-dsc.shallspark.com",
            "dsu-h5-mb.shallspark.com",
            "shalltry.com",
            "ire-oneid.shalltry.com",
            "datatower.ai",
            "roiquery.com",
            "eagllwin.com",
            "palmplay.com",
            "asv.transsion.com",
            "ad.transsion.com",
            "ads.transsion.com",
            "ad.transsnet.com",
            "ads.transsnet.com",
            // HDO Box & Playfy Ad SDKs (Wortise, Start.io, CleverAds/CAS, PubScale, GreedyGame)
            "wortise.com",
            "api.wortise.com",
            "ads.wortise.com",
            "cdn.wortise.com",
            "sdk.wortise.com",
            "startio.com",
            "startapp.com",
            "startappservice.com",
            "startappexchange.com",
            "startapptrack.com",
            "cleverads.com",
            "cleversolutions.com",
            "cas.ai",
            "psv.cas.ai",
            "pubscale.com",
            "api.pubscale.com",
            "sdk.pubscale.com",
            "greedygame.com",
            "api.greedygame.com",
            // BIGO Ads SSP & Anti-Ban C2 Infrastructure
            "bigossp.com",
            "api.bigossp.com",
            "sdk.bigossp.com",
            "static-web.bigossp.com",
            "bigoads.com",
            "bigoad.com",
            "ad.bigo.sg",
            "ads.bigo.sg",
            "acobt.tech",
            "tr.acobt.tech",
            "done.acobt.tech",
            "antibanads.com",
            "api.antibanads.com",
            "appleads.ru",
            "trk.appleads.ru",
            "zoly.tech",
            "edu.zoly.tech",
            "companinons.site",
            "api.companinons.site",
            "cpmmax.site",
            "api.cpmmax.site",
            "successwe.site",
            "api.successwe.site",
            "onegg.site",
            "api.onegg.site",
            "deepads.ru",
            "trk.deepads.ru",
            "pckqac.tech",
            "newjeans.pckqac.tech",
            "api.pckqac.tech",
            "jet.pckqac.tech",
            "cizaa.tech",
            "topmax.cizaa.tech",
            "topnotchs.tech",
            "api.topnotchs.tech",
            "okvmm.tech",
            "curb.okvmm.tech",
            "zxphiz.tech",
            "zoo.zxphiz.tech",
            "adeeply.ru",
            "api.adeeply.ru",
            "miskyc.tech",
            "pack.miskyc.tech",
            "d10sbd116h2zht.cloudfront.net",
            "d1mt01ixprehg5.cloudfront.net",
            "ad-host-backup-asia.oss-ap-southeast-1.aliyuncs.com",
            "ad-host-backup-europe.oss-eu-central-1.aliyuncs.com",
            "ad-host-backup-america.oss-us-west-1.aliyuncs.com",
            // AppLovin MAX / Array / Unity / LevelPlay / ironSource / Vungle / Liftoff
            "amazon-adsystem.com",
            "aaxads.com",
            "ads.facebook.com",
            "an.facebook.com",
            "audience_network.facebook.com",
            "applovin.com",
            "applovin.net",
            "applvn.com",
            "apphub.io",
            "unityads.unity3d.com",
            "ads.unity.com",
            "iads.unity3d.com",
            "iads.unitychina.cn",
            "ads-ctv.unity3d.com",
            "mediation.unity3d.com",
            "analytics.unity3d.com",
            "adunity.com",
            "vungle.com",
            "liftoff.io",
            "liftoff-creatives.io",
            "chartboost.com",
            "chartboosts.com",
            "adcolony.com",
            "mopub.com",
            "ironsrc.com",
            "ironsource.com",
            "ironsource.mobi",
            "supersonicads.com",
            "supersonic.com",
            "isprog.com",
            "tapjoy.com",
            "tapjoyads.com",
            "inmobi.com",
            "inmobi.cn",
            "inmobicdn.net",
            // Mintegral / Pangle / TopOn / AnyThink / TradPlus
            "mbridge.com",
            "mintegral.com",
            "rayjump.com",
            "mindworks-creative.com",
            "pangle.io",
            "pangle-ads.com",
            "pangolin-sdk-toutiao.com",
            "pangolin-sdk-toutiao-b.com",
            "pangolin-sdk-toutiao1.com",
            "tiktokpangle.us",
            "tiktokpangle-cdn-us.com",
            "ad.byteoversea.com",
            "ads.byteoversea.com",
            "log.byteoversea.com",
            "maliva-mcs.byteoversea.com",
            "mcs.byteoversea.com",
            "sgsnssdk.com",
            "isnssdk.com",
            "anythinktech.com",
            "toponad.com",
            "toponadss.com",
            "birdgesdk.com",
            "tradplusad.com",
            "tradplus.com",
            // Yandex / Yango / Appodeal / BidMachine / Fyber / Moloco / Other SDKs
            "ads.yandex.com",
            "an.yandex.ru",
            "adsdk.yandex.ru",
            "yandexadexchange.net",
            "mobile.yandexadexchange.net",
            "yango-ads.com",
            "adfox.yandex.ru",
            "adfstat.yandex.ru",
            "appmetrica.yandex.net",
            "appmetrica.yandex.com",
            "metrica-yandex.com",
            "mc.yandex.ru",
            "mc.yandex.com",
            "appodeal.com",
            "appodealx.com",
            "bidmachine.io",
            "admost.com",
            "fyber.com",
            "inner-active.mobi",
            "tpbid.com",
            "dtignite.com",
            "dtscout.com",
            "appnext.com",
            "mytarget.ru",
            "ad.mail.ru",
            "trk.mail.ru",
            "smaato.net",
            "smaato.com",
            "smartadserver.com",
            "ogury.io",
            "ogury.com",
            "adjoe.zone",
            "moloco.com",
            "adsmoloco.com",
            "taurusx.com",
            "flat-ads.com",
            "flatads.com",
            "columbusad.com",
            "kidoz.net",
            "superawesome.tv",
            "pollfish.com",
            "verve.com",
            "pubnative.net",
            "hybid.io",
            "mobilefuse.com",
            "adsbynimbus.com",
            "loopme.com",
            "kargo.com",
            "undertone.com",
            // SSPs / DSPs / WebView / Pop / Video Ad Networks
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
            "3lift.com",
            "media.net",
            "advertising.com",
            "quantserve.com",
            "revcontent.com",
            "mgid.com",
            "exoclick.com",
            "magsrv.com",
            "realsrv.com",
            "tsyndicate.com",
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "monetag.com",
            "alwingulla.com",
            "glizauvo.net",
            "dooloust.net",
            "vemtoutcheeg.com",
            "oupagono.com",
            "stawhoph.com",
            "adsterra.com",
            "highperformanceformat.com",
            "profitablecpmrate.com",
            "effectivegatecpm.com",
            "achcdn.com",
            "adcash.com",
            "hilltopads.net",
            "admaven.com",
            "ad-maven.com",
            "galaksion.com",
            "richads.com",
            "bidgear.com",
            "valueimpression.com",
            "vidazoo.com",
            "aniview.com",
            "connatix.com",
            "primis.tech",
            "freewheel.tv",
            "fwmrm.net",
            "spotxchange.com",
            "spotx.tv",
            "smartclip.net",
            "vidverto.io",
            "trafficstars.com",
            "trafficjunky.net",
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
            "flashtalking.com",
            "celtra.com",
            "moatads.com",
            "innovid.com",
            "rollerads.com",
            "pushground.com",
            "evadav.com",
            "daopush.com",
            "mondiad.com",
            "yeesshh.com",
            "zeropark.com",
            // Attribution & Trackers
            "adjust.com",
            "adjust.net.in",
            "adjust.world",
            "adjust.io",
            "appsflyer.com",
            "appsflyersdk.com",
            "singular.net",
            "kochava.com",
            "tenjin.io",
            "tenjin.com",
            "airbridge.io",
            "mytracker.com",
            "flurry.com",
            "amplitude.com",
            "mixpanel.com",
            "scorecardresearch.com",
            "comscore.com",
            "demdex.net",
            "2o7.net",
            "omtrdc.net",
            "adobedc.net",
            "mmstat.com",
            "dotomi.com",
            "tradedoubler.com",
            "adocean.pl",
            "cnzz.com",
            "statcounter.com",
            "hit.gemius.pl",
            "braze.com",
            "onesignal.com",
            "pushwoosh.com",
            "leanplum.com",
            "clevertap.com",
            "wzrkt.com",
            // DoH / DoT / HTTPDNS Bypass Endpoints
            "dns.google",
            "dns.google.com",
            "8888.google",
            "cloudflare-dns.com",
            "one.one.one.one",
            "1dot1dot1dot1.cloudflare-dns.com",
            "dns.quad9.net",
            "dns9.quad9.net",
            "dns10.quad9.net",
            "dns11.quad9.net",
            "doh.opendns.com",
            "dns.opendns.com",
            "familyshield.opendns.com",
            "dns.adguard-dns.com",
            "dns.adguard.com",
            "dns.nextdns.io",
            "doh.cleanbrowsing.org",
            "dot.cleanbrowsing.org",
            "doh.dns.sb",
            "dot.sb",
            "dnscry.pt",
            "dns.alidns.com",
            "alidns.com",
            "aliyunhttpdns.com",
            "doh.pub",
            "dot.pub",
            "sm2.doh.pub",
            "httpdns.alicdn.com",
            "httpdns.aliyuncs.com",
            "httpdns-api.aliyuncs.com",
            "httpdns-sc.aliyuncs.com",
            "resolvers-cn.httpdns.aliyuncs.com",
            "httpdns.volcengineapi.com",
            "dig.bdurl.net",
            "httpdns.baidubce.com",
            "httpdns.baidu.com",
            "httpdns.pro",
            "httpdns.qq.com",
            "httpdns.tencentcloudapi.com",
            "httpdns.ocloud.oppomobile.com",
            "httpdns.push.oppomobile.com",
            "httpdns.ocloud.heytapmobi.com",
            "httpdns.huaweicloud.com",
            "httpdns.c.cdnhwc2.com",
            "httpdns.meituan.com",
            "httpdns.yunxindns.com"
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
        16, 32, 30L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(512),
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
            .setContentText("Stealth DNS filtering active")
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
        // Sized for the bundled ~47k-domain list to avoid rehashing during load.
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
        if (LocalDnsPolicy.isHeuristicAdHost(current)) return true
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

        // Announce standard Google Public DNS (8.8.8.8, 8.8.4.4) instead of
        // 10.0.0.1 or 94.140.14.14 so LinkProperties.getDnsServers() looks
        // identical to default Android DNS and never triggers custom-DNS checks.
        val builder = Builder()
            .setSession("Network Service")
            .addAddress(IPV4_VPN_ADDRESS, 32)
            .addDnsServer(IPV4_PRIMARY_DNS)
            .addDnsServer(IPV4_SECONDARY_DNS)
            .addRoute(IPV4_FAKE_DNS_ADDRESS, 32)
            .addRoute(IPV4_PRIMARY_DNS, 32)
            .addRoute(IPV4_SECONDARY_DNS, 32)
            .addRoute(STEALTH_SINKHOLE_IPV4_ROUTE, STEALTH_SINKHOLE_IPV4_PREFIX)
            .setMtu(1500)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

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
                .addDnsServer(IPV6_PRIMARY_DNS)
                .addDnsServer(IPV6_SECONDARY_DNS)
                .addRoute(IPV6_FAKE_DNS_ADDRESS, 128)
                .addRoute(IPV6_PRIMARY_DNS, 128)
                .addRoute(IPV6_SECONDARY_DNS, 128)
                .addRoute(STEALTH_SINKHOLE_IPV6_ROUTE, STEALTH_SINKHOLE_IPV6_PREFIX)
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
        if (length < 20) return
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl) return

        val totalLength = min(length, readU16(packet, 2))
        if (totalLength < ihl) return

        val protocol = packet[9].toInt() and 0xFF
        val srcIp = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val dstIp = InetAddress.getByAddress(packet.copyOfRange(16, 20))

        when (protocol) {
            1 -> handleIcmpv4Packet(packet, totalLength, ihl, srcIp, dstIp, output)
            6 -> handleTcpPacket(packet, totalLength, ihl, srcIp, dstIp, isIpv6 = false, output = output)
            17 -> {
                if (totalLength >= ihl + 8) {
                    handleUdpDnsPacket(packet, totalLength, ihl, srcIp, dstIp, isIpv6 = false, output = output)
                }
            }
        }
    }

    private fun handleIpv6Packet(packet: ByteArray, length: Int, output: FileOutputStream) {
        if (length < 40) return
        val nextHeader = packet[6].toInt() and 0xFF
        val payloadLength = readU16(packet, 4)
        val packetEnd = min(length, 40 + payloadLength)
        if (packetEnd < 40) return

        val srcIp = InetAddress.getByAddress(packet.copyOfRange(8, 24))
        val dstIp = InetAddress.getByAddress(packet.copyOfRange(24, 40))

        when (nextHeader) {
            6 -> handleTcpPacket(packet, packetEnd, 40, srcIp, dstIp, isIpv6 = true, output = output)
            17 -> {
                if (packetEnd >= 48) {
                    handleUdpDnsPacket(packet, packetEnd, 40, srcIp, dstIp, isIpv6 = true, output = output)
                }
            }
            58 -> handleIcmpv6Packet(packet, packetEnd, srcIp, dstIp, output)
        }
    }

    private fun isStealthSinkholeAddress(address: InetAddress): Boolean {
        val raw = address.address
        return if (raw.size == 4) {
            (raw[0].toInt() and 0xFF) == 198 && ((raw[1].toInt() and 0xFE) == 18)
        } else if (raw.size == 16) {
            (raw[0].toInt() and 0xFF) == 0x20 &&
                (raw[1].toInt() and 0xFF) == 0x01 &&
                (raw[2].toInt() and 0xFF) == 0x0d &&
                (raw[3].toInt() and 0xFF) == 0xb8 &&
                (raw[4].toInt() and 0xFF) == 0x00 &&
                (raw[5].toInt() and 0xFF) == 0xad
        } else {
            false
        }
    }

    private fun handleIcmpv4Packet(
        packet: ByteArray,
        packetEnd: Int,
        ihl: Int,
        srcIp: InetAddress,
        dstIp: InetAddress,
        output: FileOutputStream
    ) {
        if (ihl + 8 > packetEnd) return
        val type = packet[ihl].toInt() and 0xFF
        val code = packet[ihl + 1].toInt() and 0xFF
        if (type != 8 || code != 0) return

        val icmpLen = packetEnd - ihl
        val totalLen = 20 + icmpLen
        val reply = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(reply)

        buf.put(0, 0x45.toByte())
        buf.put(1, 0)
        buf.putShort(2, totalLen.toShort())
        buf.putShort(4, 0)
        buf.putShort(6, 0)
        buf.put(8, 64)
        buf.put(9, 1)
        buf.putShort(10, 0)
        System.arraycopy(dstIp.address, 0, reply, 12, 4)
        System.arraycopy(srcIp.address, 0, reply, 16, 4)
        buf.putShort(10, checksum(reply, 0, 20).toShort())

        System.arraycopy(packet, ihl, reply, 20, icmpLen)
        reply[20] = 0 // Echo Reply
        reply[21] = 0
        writeU16(reply, 22, 0)
        writeU16(reply, 22, checksum(reply, 20, icmpLen))

        synchronized(output) {
            output.write(reply)
        }
    }

    private fun handleIcmpv6Packet(
        packet: ByteArray,
        packetEnd: Int,
        srcIp: InetAddress,
        dstIp: InetAddress,
        output: FileOutputStream
    ) {
        if (packetEnd < 48) return
        val type = packet[40].toInt() and 0xFF
        val code = packet[41].toInt() and 0xFF
        if (type != 128 || code != 0) return

        val icmpLen = packetEnd - 40
        val reply = packet.copyOf(packetEnd)
        System.arraycopy(dstIp.address, 0, reply, 8, 16)
        System.arraycopy(srcIp.address, 0, reply, 24, 16)
        reply[40] = 129.toByte() // ICMPv6 Echo Reply
        reply[41] = 0
        writeU16(reply, 42, 0)
        writeU16(reply, 42, ipv6TransportChecksum(reply, 40, icmpLen, 58))

        synchronized(output) {
            output.write(reply)
        }
    }

    /**
     * Handles TCP packets arriving on the TUN interface:
     * 1) For Stealth Sinkhole IPs (198.18.0.0/15 & 2001:db8:ad::/48):
     *    Completes the 3-way TCP handshake (SYN -> SYN+ACK) so Socket.connect()
     *    probes from anti-adblock checks succeed, serves valid empty HTTP 200 /
     *    VAST / JS payloads on port 80, and cleanly closes TLS streams with
     *    FIN+ACK (EOF) on port 443 so ad SDKs treat the result as a normal
     *    no-fill rather than a DNS/firewall block.
     * 2) For TCP port 53 on DNS IPs: completes handshake and filters DNS-over-TCP.
     * 3) For other TCP ports (DoT 853 / DoH 443) on DNS IPs: sends TCP RST so
     *    encrypted DNS bypasses immediately fall back to UDP port 53.
     */
    private fun handleTcpPacket(
        packet: ByteArray,
        packetEnd: Int,
        tcpStart: Int,
        srcIp: InetAddress,
        dstIp: InetAddress,
        isIpv6: Boolean,
        output: FileOutputStream
    ) {
        if (tcpStart + 20 > packetEnd) return
        val srcPort = readU16(packet, tcpStart)
        val dstPort = readU16(packet, tcpStart + 2)
        val seqNum = readU32(packet, tcpStart + 4)
        val ackNum = readU32(packet, tcpStart + 8)
        val dataOffset = ((packet[tcpStart + 12].toInt() shr 4) and 0x0F) * 4
        if (dataOffset < 20 || tcpStart + dataOffset > packetEnd) return

        val flags = packet[tcpStart + 13].toInt() and 0x3F
        if ((flags and 0x04) != 0) return // Ignore incoming RST

        val payloadStart = tcpStart + dataOffset
        val payloadLen = packetEnd - payloadStart

        if (isStealthSinkholeAddress(dstIp)) {
            handleStealthSinkholeTcp(
                packet, payloadStart, payloadLen, flags, seqNum, ackNum,
                srcIp, srcPort, dstIp, dstPort, isIpv6, output
            )
            return
        }

        if (dstPort != DNS_PORT) {
            val rstFlags = if ((flags and 0x10) != 0) 0x04 else 0x14
            val rstSeq = if ((flags and 0x10) != 0) ackNum else 0L
            val synInc = if ((flags and 0x02) != 0) 1L else 0L
            val rstAck = (seqNum + payloadLen + synInc) and 0xFFFFFFFFL
            sendTcpResponse(output, dstIp, dstPort, srcIp, srcPort, rstSeq, rstAck, rstFlags, ByteArray(0), isIpv6)
            return
        }

        // Port 53 TCP handling
        if ((flags and 0x02) != 0 && (flags and 0x10) == 0) {
            val serverSeq = 1000L
            val ack = (seqNum + 1L) and 0xFFFFFFFFL
            sendTcpResponse(output, dstIp, dstPort, srcIp, srcPort, serverSeq, ack, 0x12, ByteArray(0), isIpv6)
            return
        }

        if ((flags and 0x01) != 0 && payloadLen == 0) {
            val ack = (seqNum + 1L) and 0xFFFFFFFFL
            sendTcpResponse(output, dstIp, dstPort, srcIp, srcPort, ackNum, ack, 0x11, ByteArray(0), isIpv6)
            return
        }

        if (payloadLen >= 14) {
            val dnsLen = readU16(packet, payloadStart)
            if (dnsLen >= 12 && payloadStart + 2 + dnsLen <= packetEnd) {
                val dnsPayload = packet.copyOfRange(payloadStart + 2, payloadStart + 2 + dnsLen)
                val nextAck = (seqNum + payloadLen) and 0xFFFFFFFFL
                val question = parseDnsQuestion(dnsPayload)
                if (question != null) {
                    if (isBlocked(question.name)) {
                        totalBlocked = blockedCount.incrementAndGet()
                        recordBlocked(question.name)
                        reportStats()
                        val dnsReply = buildDnsStealthResponse(dnsPayload, question)
                        val tcpDnsReply = ByteArray(2 + dnsReply.size)
                        writeU16(tcpDnsReply, 0, dnsReply.size)
                        System.arraycopy(dnsReply, 0, tcpDnsReply, 2, dnsReply.size)
                        sendTcpResponse(output, dstIp, dstPort, srcIp, srcPort, ackNum, nextAck, 0x19, tcpDnsReply, isIpv6)
                    } else {
                        sendTcpResponse(output, dstIp, dstPort, srcIp, srcPort, ackNum, nextAck, 0x10, ByteArray(0), isIpv6)
                        forwardTcpDnsToUpstream(dnsPayload, question, srcIp, srcPort, dstIp, dstPort, ackNum, nextAck, isIpv6, output)
                    }
                    return
                }
            }
        }
    }

    private fun handleStealthSinkholeTcp(
        packet: ByteArray,
        payloadStart: Int,
        payloadLen: Int,
        flags: Int,
        seqNum: Long,
        ackNum: Long,
        srcIp: InetAddress,
        srcPort: Int,
        dstIp: InetAddress,
        dstPort: Int,
        isIpv6: Boolean,
        output: FileOutputStream
    ) {
        if ((flags and 0x02) != 0 && (flags and 0x10) == 0) {
            // SYN -> SYN+ACK so Socket.connect() reachability probes pass
            val ack = (seqNum + 1L) and 0xFFFFFFFFL
            sendTcpResponse(output, dstIp, dstPort, srcIp, srcPort, 2000L, ack, 0x12, ByteArray(0), isIpv6)
            return
        }

        if ((flags and 0x01) != 0 && payloadLen == 0) {
            // FIN -> FIN+ACK
            val ack = (seqNum + 1L) and 0xFFFFFFFFL
            sendTcpResponse(output, dstIp, dstPort, srcIp, srcPort, ackNum, ack, 0x11, ByteArray(0), isIpv6)
            return
        }

        if (payloadLen > 0) {
            val nextAck = (seqNum + payloadLen) and 0xFFFFFFFFL
            if (dstPort == 80) {
                val previewLen = min(payloadLen, 256)
                val reqHead = String(packet, payloadStart, previewLen, Charsets.US_ASCII).lowercase()
                val (contentType, body) = when {
                    reqHead.contains(".js") -> "application/javascript" to "/* */"
                    reqHead.contains("vast") || reqHead.contains(".xml") ->
                        "application/xml" to "<VAST version=\"3.0\"/>"
                    else -> "application/json" to "{}"
                }
                val httpReply = (
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${body.length}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n" +
                    body
                ).toByteArray(Charsets.US_ASCII)
                sendTcpResponse(output, dstIp, dstPort, srcIp, srcPort, ackNum, nextAck, 0x19, httpReply, isIpv6)
            } else {
                // For HTTPS (port 443) or other ports: ACK the ClientHello and
                // close cleanly with FIN+ACK (EOF) rather than RST.
                sendTcpResponse(output, dstIp, dstPort, srcIp, srcPort, ackNum, nextAck, 0x11, ByteArray(0), isIpv6)
            }
        }
    }

    private fun forwardTcpDnsToUpstream(
        dnsQuery: ByteArray,
        question: DnsQuestion,
        clientIp: InetAddress,
        clientPort: Int,
        requestedDnsIp: InetAddress,
        requestedDnsPort: Int,
        serverSeq: Long,
        clientAck: Long,
        isIpv6: Boolean,
        output: FileOutputStream
    ) {
        dnsWorkers.execute {
            val reply = queryUpstreamDns(dnsQuery, question) ?: buildDnsStealthResponse(dnsQuery, question)
            val tcpDnsReply = ByteArray(2 + reply.size)
            writeU16(tcpDnsReply, 0, reply.size)
            System.arraycopy(reply, 0, tcpDnsReply, 2, reply.size)
            sendTcpResponse(output, requestedDnsIp, requestedDnsPort, clientIp, clientPort, serverSeq, clientAck, 0x19, tcpDnsReply, isIpv6)
        }
    }

    private fun handleUdpDnsPacket(
        packet: ByteArray,
        packetEnd: Int,
        udpStart: Int,
        srcIp: InetAddress,
        dstIp: InetAddress,
        isIpv6: Boolean,
        output: FileOutputStream
    ) {
        if (udpStart + 8 > packetEnd) return

        val srcPort = readU16(packet, udpStart)
        val dstPort = readU16(packet, udpStart + 2)
        if (dstPort != DNS_PORT) {
            // Reject QUIC/DoQ (UDP 443/853) on routed resolver or sinkhole IPs immediately
            if (!isIpv6) sendIcmpv4PortUnreachable(packet, packetEnd, srcIp, dstIp, output)
            return
        }

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

    private fun sendIcmpv4PortUnreachable(
        originalPacket: ByteArray,
        packetEnd: Int,
        srcIp: InetAddress,
        dstIp: InetAddress,
        output: FileOutputStream
    ) {
        val ihl = (originalPacket[0].toInt() and 0x0F) * 4
        val quotedLen = min(packetEnd, ihl + 8)
        if (quotedLen < 28) return
        val icmpLen = 8 + quotedLen
        val totalLen = 20 + icmpLen
        val reply = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(reply)

        buf.put(0, 0x45.toByte())
        buf.put(1, 0)
        buf.putShort(2, totalLen.toShort())
        buf.putShort(4, 0)
        buf.putShort(6, 0)
        buf.put(8, 64)
        buf.put(9, 1)
        buf.putShort(10, 0)
        System.arraycopy(dstIp.address, 0, reply, 12, 4)
        System.arraycopy(srcIp.address, 0, reply, 16, 4)
        buf.putShort(10, checksum(reply, 0, 20).toShort())

        reply[20] = 3 // Destination Unreachable
        reply[21] = 3 // Port Unreachable
        writeU16(reply, 22, 0)
        System.arraycopy(originalPacket, 0, reply, 28, quotedLen)
        writeU16(reply, 22, checksum(reply, 20, icmpLen))

        synchronized(output) {
            output.write(reply)
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
        val response = buildDnsStealthResponse(dnsPayload, question)
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

    /**
     * Synthesizes a valid NOERROR DNS response pointing A (type 1) and AAAA
     * (type 28) queries for blocked ad domains to our stealth sinkhole subnet
     * (198.18.0.0/15 and 2001:db8:ad::/48) routed into the TUN interface.
     *
     * Because the response has RCODE=0 and ANCOUNT=1 with a non-zero,
     * non-loopback, non-private IP, InetAddress.getByName() and WebView never
     * throw UnknownHostException or ERR_NAME_NOT_RESOLVED / ERR_ADDRESS_INVALID,
     * completely bypassing custom-DNS / AdGuard-DNS detectors in HDO Box,
     * MovieBox, Playfy, and other APKs.
     */
    private fun buildDnsStealthResponse(query: ByteArray, question: DnsQuestion): ByteArray {
        if (!policy.compatibility && question.type == 1) {
            val ipv4 = LocalDnsPolicy.stealthIpv4ForHost(question.name)
            val response = ByteArray(question.endOffset + 16)
            System.arraycopy(query, 0, response, 0, question.endOffset)
            response[2] = (0x80 or (query[2].toInt() and 0x01)).toByte()
            response[3] = 0x80.toByte()
            writeU16(response, 4, 1)
            writeU16(response, 6, 1)
            writeU16(response, 8, 0)
            writeU16(response, 10, 0)
            val ans = question.endOffset
            writeU16(response, ans, 0xC00C)
            writeU16(response, ans + 2, 1)
            writeU16(response, ans + 4, 1)
            writeU16(response, ans + 6, 0)
            writeU16(response, ans + 8, 60)
            writeU16(response, ans + 10, 4)
            System.arraycopy(ipv4, 0, response, ans + 12, 4)
            return response
        }

        if (!policy.compatibility && question.type == 28) {
            val ipv6 = LocalDnsPolicy.stealthIpv6ForHost(question.name)
            val response = ByteArray(question.endOffset + 28)
            System.arraycopy(query, 0, response, 0, question.endOffset)
            response[2] = (0x80 or (query[2].toInt() and 0x01)).toByte()
            response[3] = 0x80.toByte()
            writeU16(response, 4, 1)
            writeU16(response, 6, 1)
            writeU16(response, 8, 0)
            writeU16(response, 10, 0)
            val ans = question.endOffset
            writeU16(response, ans, 0xC00C)
            writeU16(response, ans + 2, 28)
            writeU16(response, ans + 4, 1)
            writeU16(response, ans + 6, 0)
            writeU16(response, ans + 8, 60)
            writeU16(response, ans + 10, 16)
            System.arraycopy(ipv6, 0, response, ans + 12, 16)
            return response
        }

        val response = query.copyOf(question.endOffset)
        response[2] = (0x80 or (query[2].toInt() and 0x01)).toByte()
        response[3] = 0x80.toByte()
        writeU16(response, 4, 1)
        writeU16(response, 6, 0)
        writeU16(response, 8, 0)
        writeU16(response, 10, 0)
        return response
    }

    private fun queryUpstreamDns(dnsQuery: ByteArray, question: DnsQuestion): ByteArray? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            synchronized(activeSockets) {
                if (!running.get() || Thread.currentThread().isInterrupted) return null
                activeSockets.add(socket)
            }
            if (!protect(socket)) return null
            socket.soTimeout = 2500

            val candidateIps = policy.resolvers(question.name)

            for (upstreamHost in candidateIps) {
                if (socket.isClosed || Thread.currentThread().isInterrupted) return null
                try {
                    val upstream = InetAddress.getByName(upstreamHost)
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

                    if (!policy.bypassBlocklist(question.name) &&
                        (dnsResponseReferencesBlockedDomain(reply) || dnsResponseContainsSinkholeAddress(reply))) {
                        totalBlocked = blockedCount.incrementAndGet()
                        recordBlocked(question.name)
                        reportStats()
                        return buildDnsStealthResponse(dnsQuery, question)
                    }
                    return reply
                } catch (_: Exception) {
                    // Try the next resolver.
                }
            }
        } finally {
            synchronized(activeSockets) { activeSockets.remove(socket) }
            socket?.close()
        }
        return null
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
            val reply = queryUpstreamDns(dnsQuery, question) ?: return@execute
            sendUdpResponse(output, requestedDnsIp, requestedDnsPort, clientIp, clientPort, reply)
        }
    }

    private fun dnsResponseContainsSinkholeAddress(response: ByteArray): Boolean {
        if (response.size < 12) return false
        var pos = 12
        repeat(readU16(response, 4)) {
            val qName = readDnsName(response, pos) ?: return false
            pos = qName.nextOffset + 4
            if (pos > response.size) return false
        }
        val answerCount = readU16(response, 6)
        repeat(answerCount) {
            val ownerName = readDnsName(response, pos) ?: return false
            pos = ownerName.nextOffset
            if (pos + 10 > response.size) return false
            val type = readU16(response, pos)
            val dataLength = readU16(response, pos + 8)
            val dataStart = pos + 10
            val dataEnd = dataStart + dataLength
            if (dataEnd > response.size) return false
            if (type == 1 && dataLength == 4) {
                val b0 = response[dataStart].toInt() and 0xFF
                val b1 = response[dataStart + 1].toInt() and 0xFF
                val b2 = response[dataStart + 2].toInt() and 0xFF
                val b3 = response[dataStart + 3].toInt() and 0xFF
                if ((b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0) ||
                    (b0 == 127 && b1 == 0 && b2 == 0 && b3 == 1)) {
                    return true
                }
            } else if (type == 28 && dataLength == 16) {
                var allZero = true
                var loopback = true
                for (i in 0 until 16) {
                    val b = response[dataStart + i].toInt() and 0xFF
                    if (b != 0) allZero = false
                    if (i < 15 && b != 0) loopback = false
                    if (i == 15 && b != 1) loopback = false
                }
                if (allZero || loopback) return true
            }
            pos = dataEnd
        }
        return false
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
            if (ownerName.value.isNotEmpty() && isBlocked(ownerName.value)) return true
            pos = ownerName.nextOffset
            if (pos + 10 > response.size) return false

            val type = readU16(response, pos)
            val dataLength = readU16(response, pos + 8)
            val dataStart = pos + 10
            val dataEnd = dataStart + dataLength
            if (dataEnd > response.size) return false

            if (type == 5 || type == 2 || type == 12) {
                readDnsName(response, dataStart)?.let { target ->
                    if (target.value.isNotEmpty() && isBlocked(target.value)) return true
                }
            } else if ((type == 64 || type == 65) && dataLength > 2) {
                readDnsName(response, dataStart + 2)?.let { target ->
                    if (target.value.isNotEmpty() && isBlocked(target.value)) return true
                }
            }
            pos = dataEnd
        }

        return false
    }

    private fun sendTcpResponse(
        output: FileOutputStream,
        srcIp: InetAddress,
        srcPort: Int,
        dstIp: InetAddress,
        dstPort: Int,
        seqNum: Long,
        ackNum: Long,
        flags: Int,
        tcpPayload: ByteArray,
        isIpv6: Boolean
    ) {
        val tcpHeaderLen = 20
        val tcpLength = tcpHeaderLen + tcpPayload.size
        val ipHeaderLen = if (isIpv6) 40 else 20
        val totalLength = ipHeaderLen + tcpLength
        val packet = ByteArray(totalLength)
        val buf = ByteBuffer.wrap(packet)

        if (isIpv6) {
            val srcAddress = srcIp.address
            val dstAddress = dstIp.address
            if (srcAddress.size != 16 || dstAddress.size != 16) return
            buf.putInt(0, 0x60000000)
            buf.putShort(4, tcpLength.toShort())
            buf.put(6, 6)
            buf.put(7, 64)
            System.arraycopy(srcAddress, 0, packet, 8, 16)
            System.arraycopy(dstAddress, 0, packet, 24, 16)
        } else {
            buf.put(0, 0x45.toByte())
            buf.put(1, 0)
            buf.putShort(2, totalLength.toShort())
            buf.putShort(4, 0)
            buf.putShort(6, 0)
            buf.put(8, 64)
            buf.put(9, 6)
            buf.putShort(10, 0)
            System.arraycopy(srcIp.address, 0, packet, 12, 4)
            System.arraycopy(dstIp.address, 0, packet, 16, 4)
            buf.putShort(10, checksum(packet, 0, 20).toShort())
        }

        val tcpStart = ipHeaderLen
        buf.putShort(tcpStart, srcPort.toShort())
        buf.putShort(tcpStart + 2, dstPort.toShort())
        buf.putInt(tcpStart + 4, seqNum.toInt())
        buf.putInt(tcpStart + 8, ackNum.toInt())
        buf.put(tcpStart + 12, 0x50.toByte())
        buf.put(tcpStart + 13, (flags and 0x3F).toByte())
        buf.putShort(tcpStart + 14, 0xFFFF.toShort())
        buf.putShort(tcpStart + 16, 0)
        buf.putShort(tcpStart + 18, 0)
        if (tcpPayload.isNotEmpty()) {
            System.arraycopy(tcpPayload, 0, packet, tcpStart + tcpHeaderLen, tcpPayload.size)
        }

        val tcpChecksum = if (isIpv6) {
            ipv6TransportChecksum(packet, tcpStart, tcpLength, 6)
        } else {
            ipv4TransportChecksum(packet, tcpStart, tcpLength, 6)
        }
        buf.putShort(tcpStart + 16, tcpChecksum.toShort())

        synchronized(output) {
            output.write(packet)
        }
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
        buf.putShort(udpStart + 6, ipv6TransportChecksum(packet, udpStart, udpLength, 17).toShort())

        synchronized(output) {
            output.write(packet)
        }
    }

    private fun ipv4TransportChecksum(packet: ByteArray, transportStart: Int, transportLength: Int, protocol: Int): Int {
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

        addBytes(12, 4)
        addBytes(16, 4)
        addWord(protocol)
        addWord(transportLength)
        addBytes(transportStart, transportLength)

        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun ipv6TransportChecksum(packet: ByteArray, transportStart: Int, transportLength: Int, nextHeader: Int): Int {
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
        addWord(transportLength shr 16)
        addWord(transportLength)
        addWord(nextHeader)
        addBytes(transportStart, transportLength)

        while ((sum shr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = sum.inv().toInt() and 0xFFFF
        return if (nextHeader == 17 && checksum == 0) 0xFFFF else checksum
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
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
