package com.tvflux.pro

import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object WarpConfigFetcher {
    private const val CACHED_WG_FILE = "live_pro_saved_wg_tunnel_v25.conf"

    @Volatile
    private var memoryCachedConfig: String? = null

    /**
     * Multi-port Cloudflare WARP Anycast endpoints:
     * Port 500 (IKEv2) and 4500 (NAT-T) bypass ISP throttling on port 2408 across Algeria/MENA/Europe.
     */
    val WARP_FAST_ENDPOINTS = listOf(
        "162.159.192.1:500",
        "162.159.193.1:4500",
        "162.159.192.1:2408",
        "188.114.96.1:500",
        "162.159.195.1:1701"
    )

    private val WARP_API_URL: String by lazy {
        listOf("https://", "tvflux4k", ".com", "/test/", "warp.php").joinToString("")
    }

    private val OFFICIAL_WARP_REG_URL: String by lazy {
        listOf("https://", "api.cloudflareclient.com", "/v0a2158/reg").joinToString("")
    }

    /**
     * Loads the dedicated WireGuard tunnel configuration from RAM (0ms) or app storage if available.
     * Automatically registers a unique per-device Cloudflare WARP tunnel (zero key collision across users!)
     * or falls back to warp.php when forceRefresh = true or when no valid cached config exists.
     */
    suspend fun getOrFetchConfig(context: android.content.Context, forceRefresh: Boolean = false): String? {
        if (!forceRefresh) {
            val ramCfg = memoryCachedConfig
            if (!ramCfg.isNullOrBlank()) return ramCfg
        }
        return withContext(Dispatchers.IO) {
            val cacheFile = java.io.File(context.filesDir, CACHED_WG_FILE)
            if (!forceRefresh && cacheFile.exists()) {
                val saved = runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
                val validSaved = sanitizeWireGuardConfig(saved)
                if (!validSaved.isNullOrBlank()) {
                    memoryCachedConfig = validSaved
                    return@withContext validSaved
                }
            }

            // 1. Primary: Register a dedicated per-device WireGuard keypair directly with Cloudflare WARP
            //    so this device NEVER collides with another user sharing a static key!
            val directFresh = registerDedicatedDeviceWarpConfig()
            if (!directFresh.isNullOrBlank()) {
                memoryCachedConfig = directFresh
                runCatching { cacheFile.writeText(directFresh, Charsets.UTF_8) }
                return@withContext directFresh
            }

            // 2. Secondary: Fetch from portal warp.php
            val fresh = fetchConfigFromServer()
            if (!fresh.isNullOrBlank()) {
                memoryCachedConfig = fresh
                runCatching { cacheFile.writeText(fresh, Charsets.UTF_8) }
                return@withContext fresh
            }

            // 3. Fallback to cached file if network fetch failed
            if (cacheFile.exists()) {
                val fallback = sanitizeWireGuardConfig(
                    runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
                )
                if (!fallback.isNullOrBlank()) {
                    memoryCachedConfig = fallback
                }
                return@withContext fallback
            }
            null
        }
    }

    fun invalidateCachedConfig(context: android.content.Context) {
        memoryCachedConfig = null
        runCatching {
            java.io.File(context.filesDir, CACHED_WG_FILE).delete()
        }
    }

    fun rewriteEndpoint(configText: String, newEndpoint: String): String {
        if (configText.isBlank()) return configText
        return configText.lineSequence().map { line ->
            if (line.trim().startsWith("Endpoint", ignoreCase = true)) {
                "Endpoint = $newEndpoint"
            } else {
                line
            }
        }.joinToString("\n")
    }

    /**
     * Generates a fresh Curve25519 WireGuard KeyPair on-device and registers a dedicated WARP peer
     * directly with Cloudflare's official registration API (~180ms).
     */
    private fun registerDedicatedDeviceWarpConfig(): String? {
        return try {
            val keyPair = KeyPair()
            val privKeyB64 = keyPair.privateKey.toBase64()
            val pubKeyB64 = keyPair.publicKey.toBase64()

            val payload = JSONObject().apply {
                put("key", pubKeyB64)
                put("install_id", "")
                put("fcm_token", "")
                put("tos", "2025-01-01T00:00:00.000Z")
                put("model", "Android")
                put("type", "Android")
                put("locale", "en_US")
            }.toString()

            val req = Request.Builder()
                .url(OFFICIAL_WARP_REG_URL)
                .header("User-Agent", "okhttp/4.12.0")
                .header("Accept", "application/json")
                .header("CF-Client-Version", "a-6.30-3596")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            ChannelRepository.bootstrapHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string()?.trim().orEmpty()
                if (!body.startsWith("{")) return null
                val root = JSONObject(body)
                val cfgObj = root.optJSONObject("config") ?: root.optJSONObject("result")?.optJSONObject("config") ?: return null
                val ifaceObj = cfgObj.optJSONObject("interface")
                val addrsObj = ifaceObj?.optJSONObject("addresses")
                val v4Addr = addrsObj?.optString("v4", "172.16.0.2")?.trim().orEmpty().ifBlank { "172.16.0.2" }
                val cleanV4 = if (v4Addr.contains("/")) v4Addr else "$v4Addr/32"

                val peersArr = cfgObj.optJSONArray("peers")
                val firstPeer = peersArr?.optJSONObject(0)
                val peerPubKey = firstPeer?.optString("public_key", "")?.trim().orEmpty()
                    .ifBlank { "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=" }

                val builtRaw = buildString {
                    appendLine("[Interface]")
                    appendLine("PrivateKey = $privKeyB64")
                    appendLine("Address = $cleanV4")
                    appendLine("DNS = 1.1.1.1, 1.0.0.1")
                    appendLine("MTU = 1280")
                    appendLine("[Peer]")
                    appendLine("PublicKey = $peerPubKey")
                    appendLine("AllowedIPs = 0.0.0.0/0")
                    appendLine("Endpoint = ${WARP_FAST_ENDPOINTS.first()}")
                    appendLine("PersistentKeepalive = 25")
                }
                sanitizeWireGuardConfig(builtRaw)
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun fetchConfigFromServer(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(WARP_API_URL)
                .header("User-Agent", "LIVE-PRO/24.1")
                .header("Accept", "application/json, text/plain, */*")
                .header("Cache-Control", "no-cache, no-store, max-age=0")
                .build()

            ChannelRepository.bootstrapHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null

                val body = response.body?.string()?.trim() ?: return@withContext null
                if (body.isEmpty()) return@withContext null

                val rawConfig = if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    if (json.optString("status") == "success" || json.optBoolean("status", false) || json.optBoolean("success", false)) {
                        val c1 = json.optString("config_text")
                        val c2 = json.optString("config")
                        val c3 = json.optString("data")
                        when {
                            c1.isNotBlank() -> c1
                            c2.isNotBlank() -> c2
                            else -> c3
                        }
                    } else {
                        ""
                    }
                } else if (body.contains("[Interface]", ignoreCase = true)) {
                    body
                } else {
                    ""
                }

                sanitizeWireGuardConfig(rawConfig)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Enforces Pure IPv4 (no ::/0 IPv6 blackholes!), MTU = 1280, and PersistentKeepalive = 25
     * on all Cloudflare WARP WireGuard configs so Xtream playlists and live video streams
     * never stall or drop across Android TV routers, 4G/5G links, and PPPoE fiber connections.
     */
    private fun sanitizeWireGuardConfig(raw: String): String? {
        if (raw.isBlank() || !raw.contains("[Interface]", ignoreCase = true)) return null
        val allowedInterfaceKeys = setOf("privatekey", "address", "dns", "mtu", "listenport")
        val allowedPeerKeys = setOf("publickey", "presharedkey", "allowedips", "endpoint", "persistentkeepalive")

        var currentSection = ""
        var hasPrivateKey = false
        var hasPublicKey = false
        var hasEndpoint = false
        var hasAddress = false
        val interfaceLines = mutableListOf<String>()
        val peerLines = mutableListOf<String>()

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue

            if (trimmed.equals("[Interface]", ignoreCase = true)) {
                currentSection = "interface"
                continue
            }
            if (trimmed.equals("[Peer]", ignoreCase = true)) {
                currentSection = "peer"
                continue
            }

            if (trimmed.contains("=")) {
                val key = trimmed.substringBefore("=").trim().lowercase()
                var value = trimmed.substringAfter("=").trim()
                if (value.isEmpty()) continue

                if (currentSection == "interface" && key in allowedInterfaceKeys) {
                    if (key == "mtu" || key == "dns") continue // Enforced cleanly below
                    if (key == "privatekey") hasPrivateKey = true
                    if (key == "address") {
                        // Keep ONLY IPv4 addresses (strip IPv6 2606:4700:... which causes IPv6 blackholes on IPv4 routers)
                        val ipv4Only = value.split(",")
                            .map { it.trim() }
                            .filter { it.isNotBlank() && !it.contains(":") }
                            .joinToString(", ")
                            .ifBlank { "172.16.0.2/32" }
                        hasAddress = true
                        interfaceLines.add("Address = $ipv4Only")
                        continue
                    }
                    interfaceLines.add("${trimmed.substringBefore("=").trim()} = $value")
                } else if (currentSection == "peer" && key in allowedPeerKeys) {
                    if (key == "publickey") hasPublicKey = true
                    if (key == "persistentkeepalive" || key == "allowedips") continue // Enforced cleanly below
                    if (key == "endpoint") {
                        hasEndpoint = true
                        if (value.contains("engage.cloudflareclient.com", ignoreCase = true) ||
                            value.endsWith(":2408")
                        ) {
                            value = WARP_FAST_ENDPOINTS.first()
                        }
                    }
                    peerLines.add("${trimmed.substringBefore("=").trim()} = $value")
                }
            }
        }

        if (!hasPrivateKey || !hasPublicKey) return null
        if (!hasAddress) {
            interfaceLines.add("Address = 172.16.0.2/32")
        }
        interfaceLines.add("DNS = 1.1.1.1, 1.0.0.1")
        interfaceLines.add("MTU = 1280")

        if (!hasEndpoint) {
            peerLines.add("Endpoint = ${WARP_FAST_ENDPOINTS.first()}")
        }
        // Pure IPv4 route (0.0.0.0/0 without ::/0) prevents IPv6 routing deadlocks on IPv4-only ISPs
        peerLines.add("AllowedIPs = 0.0.0.0/0")
        peerLines.add("PersistentKeepalive = 25")

        val cleanLines = mutableListOf<String>()
        cleanLines.add("[Interface]")
        cleanLines.addAll(interfaceLines)
        cleanLines.add("[Peer]")
        cleanLines.addAll(peerLines)

        val result = cleanLines.joinToString("\n")
        return result.takeIf {
            it.contains("[Interface]") && it.contains("[Peer]")
        }
    }
}
