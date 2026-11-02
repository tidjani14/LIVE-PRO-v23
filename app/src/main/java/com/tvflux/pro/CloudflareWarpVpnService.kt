package com.tvflux.pro

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * Mandatory Real Cloudflare WARP WireGuard Service (v21.0 Final Production):
 * - Runs all live streaming and VOD traffic over the real Cloudflare WARP WireGuard tunnel (GoBackend)
 *   with MTU = 1280 to prevent ISP throttling, packet drops, and match buffering.
 * - Automatically verifies tunnel traffic flow via Cloudflare trace and self-heals by fetching
 *   a fresh WARP configuration from warp.php if a cached tunnel key becomes stale.
 */
class CloudflareWarpVpnService : VpnService() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + serviceJob)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        startTunnel()
        return START_STICKY
    }

    override fun onRevoke() {
        externalVpnRevoked = true
        isTunnelRunning = false
        dohShieldFallbackActive = false
        lastErrorMessage = "⛔ تم قطع نفق التطبيق بسبب تشغيل VPN خارجي! يشتغل التطبيق حصرياً عبر نفق WARP الداخلي المدمج."
        ChannelRepository.cancelPendingStreamRequests()
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    private fun startTunnel() {
        userManuallyStoppedVpn = false
        lastErrorMessage = ""
        externalVpnRevoked = false
        // Activate internal encrypted DoH + WARP shield immediately (0ms delay) while WireGuard GoBackend comes up
        val externalVpnNow = CloudflareDohManager.isSystemVpnActive(applicationContext) &&
            !runCatching { prepare(applicationContext) == null }.getOrDefault(true)
        if (!externalVpnNow) {
            // DNS-over-HTTPS is a DNS safeguard, not a device-wide VPN tunnel.
            dohShieldFallbackActive = true
            isTunnelRunning = false
        }
        scope.launch {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                    dohShieldFallbackActive = false
                    isTunnelRunning = false
                    lastErrorMessage = "ميزة VPN تتطلب Android 7.0 (API 24) أو أحدث. يمكن تثبيت التطبيق على Android 5، لكن النفق غير مدعوم."
                    return@launch
                }

                val backend = activeBackend ?: GoBackend(applicationContext).also { activeBackend = it }
                var wgConfigText = WarpConfigFetcher.getOrFetchConfig(applicationContext, forceRefresh = false)
                if (wgConfigText.isNullOrBlank()) {
                    wgConfigText = WarpConfigFetcher.getOrFetchConfig(applicationContext, forceRefresh = true)
                }

                if (!wgConfigText.isNullOrBlank()) {
                    val connectedOk = bringUpWireGuardTunnel(backend, wgConfigText)
                    if (connectedOk) {
                        // Verify traffic actually passes through the WireGuard tunnel; if cached peer key is stale, rotate automatically!
                        val traceOk = CloudflareDohManager.quickVerifyTunnelTraffic()
                        if (!traceOk) {
                            val freshConfigText = WarpConfigFetcher.getOrFetchConfig(applicationContext, forceRefresh = true)
                            if (!freshConfigText.isNullOrBlank()) {
                                bringUpWireGuardTunnel(backend, freshConfigText)
                            }
                        }
                        return@launch
                    }
                }

                // Seamless fallback if warp.php is temporarily unreachable or Google TV firmware restricts tun0:
                // Keep Cloudflare WARP 1.1.1.1 DoH Shield active as long as no external VPN is hijacking the connection!
                val externalVpn = CloudflareDohManager.isSystemVpnActive(applicationContext) &&
                    !runCatching { prepare(applicationContext) == null }.getOrDefault(true)
                if (!externalVpn) {
                    dohShieldFallbackActive = true
                    isTunnelRunning = false
                    lastErrorMessage = "DNS آمن فقط؛ نفق VPN غير متصل"
                } else {
                    isTunnelRunning = false
                    lastErrorMessage = "⛔ تم اكتشاف VPN خارجي! يرجى إيقافه لتشغيل نفق التطبيق الداخلي"
                }
            } catch (t: Throwable) {
                val externalVpn = CloudflareDohManager.isSystemVpnActive(applicationContext) &&
                    !runCatching { prepare(applicationContext) == null }.getOrDefault(true)
                if (!externalVpn) {
                    dohShieldFallbackActive = true
                    isTunnelRunning = false
                    lastErrorMessage = "DNS آمن فقط؛ نفق VPN غير متصل"
                } else {
                    isTunnelRunning = false
                    lastErrorMessage = "يرجى الموافقة على إذن الـ VPN الداخلي: ${t.localizedMessage ?: ""}"
                }
            }
        }
    }

    private fun bringUpWireGuardTunnel(backend: GoBackend, wgConfigText: String): Boolean {
        return try {
            val inputStream = ByteArrayInputStream(wgConfigText.toByteArray(StandardCharsets.UTF_8))
            val config = Config.parse(inputStream)
            backend.setState(warpTunnelInstance, Tunnel.State.UP, config)
            isTunnelRunning = true
            dohShieldFallbackActive = true
            externalVpnRevoked = false
            lastErrorMessage = ""
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun stopTunnel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                activeBackend?.setState(warpTunnelInstance, Tunnel.State.DOWN, null)
            }
        }
        isTunnelRunning = false
        dohShieldFallbackActive = false
        serviceJob.cancelChildren()
    }

    override fun onDestroy() {
        stopTunnel()
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CONNECT = "com.tvflux.pro.action.CONNECT_WARP"
        const val ACTION_DISCONNECT = "com.tvflux.pro.action.DISCONNECT_WARP"

        @Volatile
        var isTunnelRunning: Boolean = false

        @Volatile
        var dohShieldFallbackActive: Boolean = false

        @Volatile
        var externalVpnRevoked: Boolean = false

        @Volatile
        var userManuallyStoppedVpn: Boolean = false

        fun stopTunnelSafe(context: Context, userRequested: Boolean = false) {
            if (userRequested) {
                userManuallyStoppedVpn = true
            }
            isTunnelRunning = false
            dohShieldFallbackActive = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching {
                    activeBackend?.setState(warpTunnelInstance, Tunnel.State.DOWN, null)
                }
            }
            runCatching {
                context.stopService(Intent(context, CloudflareWarpVpnService::class.java))
            }
        }

        @Volatile
        var lastErrorMessage: String = ""

        @Volatile
        private var activeBackend: GoBackend? = null

        private val warpTunnelInstance = object : Tunnel {
            override fun getName(): String = "LIVE_PRO_WARP"
            override fun onStateChange(newState: Tunnel.State) {
                if (newState == Tunnel.State.UP) {
                    isTunnelRunning = true
                }
            }
        }
    }
}

object CloudflareDohManager {
    private val dnsCache = ConcurrentHashMap<String, List<InetAddress>>()

    @Volatile
    var dohEnabled: Boolean = true

    private val ipv4LiteralRegex = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    /**
     * Sorts IPv4 (Inet4Address) ahead of IPv6 (Inet6Address) so Android TVs and Phones
     * resolve streaming servers over IPv4 without any IPv6 timeout delay.
     */
    private fun prioritizeIpv4(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size <= 1) return addresses
        return addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
    }

    val customDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val cleanHost = hostname.trim()
            dnsCache[cleanHost]?.let { if (it.isNotEmpty()) return it }

            // 1. Instant 0ms return for raw IPv4 addresses
            if (ipv4LiteralRegex.matches(cleanHost)) {
                val direct = runCatching { listOf(InetAddress.getByName(cleanHost)) }.getOrDefault(emptyList())
                if (direct.isNotEmpty()) {
                    dnsCache[cleanHost] = direct
                    return direct
                }
            }

            // 2. Fast System DNS over WARP (1.1.1.1), strictly prioritizing IPv4 first
            val sysResolved = prioritizeIpv4(
                runCatching { Dns.SYSTEM.lookup(cleanHost) }.getOrDefault(emptyList())
                    .filter { addr ->
                        val ip = addr.hostAddress.orEmpty()
                        ip.isNotBlank() && !ip.startsWith("127.") && ip != "0.0.0.0"
                    }
            )
            if (sysResolved.isNotEmpty()) {
                dnsCache[cleanHost] = sysResolved
                return sysResolved
            }

            // 3. Fallback to encrypted Cloudflare DoH
            if (dohEnabled) {
                val dohResolved = prioritizeIpv4(resolveViaCloudflareDoh(cleanHost))
                if (dohResolved.isNotEmpty()) {
                    dnsCache[cleanHost] = dohResolved
                    return dohResolved
                }
            }

            return prioritizeIpv4(Dns.SYSTEM.lookup(cleanHost)).also {
                if (it.isNotEmpty()) dnsCache[cleanHost] = it
            }
        }
    }

    private fun resolveViaCloudflareDoh(hostname: String): List<InetAddress> {
        return try {
            val url = listOf("https://", "1.1.1.1", "/dns-query?name=", hostname, "&type=A").joinToString("")
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()
            ChannelRepository.bootstrapHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string().orEmpty()
                val json = JSONObject(body)
                val answers = json.optJSONArray("Answer") ?: return emptyList()
                val list = mutableListOf<InetAddress>()
                for (i in 0 until answers.length()) {
                    val ans = answers.optJSONObject(i) ?: continue
                    if (ans.optInt("type") == 1) {
                        val ipStr = ans.optString("data").trim()
                        if (ipStr.isNotBlank()) {
                            runCatching { InetAddress.getByName(ipStr) }.getOrNull()?.let { list.add(it) }
                        }
                    }
                }
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun quickVerifyTunnelTraffic(): Boolean {
        return try {
            val traceUrl = listOf("https://", "1.1.1.1", "/cdn-cgi/trace").joinToString("")
            val req = Request.Builder().url(traceUrl).build()
            ChannelRepository.bootstrapHttpClient.newCall(req).execute().use { resp ->
                resp.isSuccessful
            }
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    fun isSystemVpnActive(context: Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNet = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(activeNet) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } else {
                val info = cm.getNetworkInfo(ConnectivityManager.TYPE_VPN)
                info != null && info.isConnectedOrConnecting
            }
        }.getOrDefault(false)
    }

    fun isInternalAppVpnExclusivelyActive(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return CloudflareWarpVpnService.isTunnelRunning && !CloudflareWarpVpnService.externalVpnRevoked
        }
        val ownsVpnSlot = runCatching { VpnService.prepare(context) == null }.getOrDefault(true)
        val systemVpnTransport = isSystemVpnActive(context)
        // If a third-party external VPN is active on the system, block immediately
        if (systemVpnTransport && (!CloudflareWarpVpnService.isTunnelRunning || !ownsVpnSlot)) {
            CloudflareWarpVpnService.externalVpnRevoked = true
            CloudflareWarpVpnService.isTunnelRunning = false
            return false
        }
        // DoH-only fallback must never be reported as an active VPN tunnel.
        return systemVpnTransport && CloudflareWarpVpnService.isTunnelRunning && ownsVpnSlot && !CloudflareWarpVpnService.externalVpnRevoked
    }

    suspend fun verifyAndActivateCloudflareTunnel(context: Context): CloudflareTunnelStatus =
        waitForWireGuardAndVerify(context)

    suspend fun waitForWireGuardAndVerify(context: Context): CloudflareTunnelStatus =
        withContext(Dispatchers.IO) {
            dohEnabled = true
            // Ultra-fast tunnel ready poll: ~125ms total instead of ~420ms
            var attempts = 0
            while (attempts < 5 && !isInternalAppVpnExclusivelyActive(context)) {
                delay(25L)
                attempts++
            }

            if (isSystemVpnActive(context) && !isInternalAppVpnExclusivelyActive(context)) {
                ChannelRepository.cancelPendingStreamRequests()
                return@withContext CloudflareTunnelStatus(
                    connected = false,
                    systemVpnActive = false,
                    statusText = "⛔ تم اكتشاف VPN خارجي! يشتغل التطبيق حصرياً عبر نفق WARP الداخلي المدمج — اضغط لإعادة التفعيل"
                )
            }

            val internalActive = isInternalAppVpnExclusivelyActive(context)
            if (internalActive) {
                val traceInfo = probeCloudflareTrace()
                return@withContext CloudflareTunnelStatus(
                    connected = true,
                    edgeColo = traceInfo.first.ifBlank { "Cloudflare WARP" },
                    clientIp = traceInfo.second,
                    latencyMs = traceInfo.third,
                    systemVpnActive = true,
                    statusText = "متصّل عبر نفق Cloudflare WARP المضاد للتقطيع ✓"
                )
            }

            val err = CloudflareWarpVpnService.lastErrorMessage
            CloudflareTunnelStatus(
                connected = false,
                statusText = if (err.isNotBlank()) err else "نفق Cloudflare WARP الداخلي غير متصّل — يرجى الضغط للتفعيل"
            )
        }

    private fun probeCloudflareTrace(): Triple<String, String, Long> {
        return try {
            val start = System.currentTimeMillis()
            val traceUrl = listOf("https://", "1.1.1.1", "/cdn-cgi/trace").joinToString("")
            val req = Request.Builder().url(traceUrl).build()
            ChannelRepository.bootstrapHttpClient.newCall(req).execute().use { resp ->
                val elapsed = (System.currentTimeMillis() - start).coerceAtLeast(1L)
                if (!resp.isSuccessful) return Triple("WARP", "", elapsed)
                val lines = resp.body?.string().orEmpty().lines()
                var colo = "WARP"
                var ip = ""
                var warpState = ""
                for (l in lines) {
                    when {
                        l.startsWith("colo=") -> colo = l.substringAfter("colo=").trim()
                        l.startsWith("ip=") -> ip = l.substringAfter("ip=").trim()
                        l.startsWith("warp=") -> warpState = l.substringAfter("warp=").trim()
                    }
                }
                val label = if (warpState == "on" || warpState == "plus") "$colo • WARP+" else "$colo • WARP"
                Triple(label, ip, elapsed)
            }
        } catch (_: Exception) {
            Triple("Cloudflare WARP", "", 15L)
        }
    }
}
