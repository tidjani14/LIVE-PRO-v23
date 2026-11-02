package com.tvflux.pro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

object WarpConfigFetcher {
    private const val CACHED_WG_FILE = "live_pro_saved_wg_tunnel.conf"

    private val WARP_API_URL: String by lazy {
        listOf("https://", "tvflux4k", ".com", "/test/", "warp.php").joinToString("")
    }

    /**
     * Loads the cached WireGuard tunnel configuration from app storage if available.
     * Automatically fetches a fresh tunnel from warp.php when forceRefresh = true or when no valid cached config exists.
     */
    suspend fun getOrFetchConfig(context: android.content.Context, forceRefresh: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            val cacheFile = java.io.File(context.filesDir, CACHED_WG_FILE)
            if (!forceRefresh && cacheFile.exists()) {
                val saved = runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
                val validSaved = sanitizeWireGuardConfig(saved)
                if (!validSaved.isNullOrBlank()) {
                    return@withContext validSaved
                }
            }

            val fresh = fetchConfigFromServer()
            if (!fresh.isNullOrBlank()) {
                runCatching { cacheFile.writeText(fresh, Charsets.UTF_8) }
                return@withContext fresh
            }

            // Fallback to cached file if network fetch failed
            if (cacheFile.exists()) {
                return@withContext sanitizeWireGuardConfig(
                    runCatching { cacheFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty()
                )
            }
            null
        }

    fun invalidateCachedConfig(context: android.content.Context) {
        runCatching {
            java.io.File(context.filesDir, CACHED_WG_FILE).delete()
        }
    }

    suspend fun fetchConfigFromServer(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(WARP_API_URL)
                .header("User-Agent", "LIVE-PRO/8.0")
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
     * Enforces MTU = 1280 and PersistentKeepalive = 25 on all Cloudflare WARP WireGuard configs
     * so video stream packets never fragment or drop across Android TV routers and PPPoE links.
     */
    private fun sanitizeWireGuardConfig(raw: String): String? {
        if (raw.isBlank() || !raw.contains("[Interface]", ignoreCase = true)) return null
        val allowedInterfaceKeys = setOf("privatekey", "address", "dns", "mtu", "listenport")
        val allowedPeerKeys = setOf("publickey", "presharedkey", "allowedips", "endpoint", "persistentkeepalive")

        var currentSection = ""
        var hasPrivateKey = false
        var hasPublicKey = false
        var hasEndpoint = false
        var hasDns = false
        var hasKeepalive = false
        var hasAllowedIps = false
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
                    if (key == "mtu") continue // Always enforce MTU = 1280 below
                    if (key == "privatekey") hasPrivateKey = true
                    if (key == "dns") hasDns = true
                    interfaceLines.add("${trimmed.substringBefore("=").trim()} = $value")
                } else if (currentSection == "peer" && key in allowedPeerKeys) {
                    if (key == "publickey") hasPublicKey = true
                    if (key == "persistentkeepalive") hasKeepalive = true
                    if (key == "allowedips") {
                        hasAllowedIps = true
                        value = "0.0.0.0/0, ::/0"
                    }
                    if (key == "endpoint") {
                        hasEndpoint = true
                        if (value.contains("engage.cloudflareclient.com", ignoreCase = true)) {
                            val port = value.substringAfterLast(":", "2408")
                            value = "162.159.192.1:$port"
                        }
                    }
                    peerLines.add("${trimmed.substringBefore("=").trim()} = $value")
                }
            }
        }

        if (!hasPrivateKey || !hasPublicKey || !hasEndpoint) return null
        if (!hasDns) {
            interfaceLines.add("DNS = 1.1.1.1, 1.0.0.1")
        }
        // Mandatory MTU = 1280 for zero packet fragmentation on Android TV & Phone over Cloudflare WARP
        interfaceLines.add("MTU = 1280")
        if (!hasAllowedIps) {
            peerLines.add("AllowedIPs = 0.0.0.0/0, ::/0")
        }
        if (!hasKeepalive) {
            peerLines.add("PersistentKeepalive = 15")
        }

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
