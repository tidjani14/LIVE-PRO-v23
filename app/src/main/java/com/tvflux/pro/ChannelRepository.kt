package com.tvflux.pro

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object ChannelRepository {
    private val INTERNAL_ENDPOINT: String by lazy {
        listOf("https://", "tvflux4k", ".com", "/p2p/", "api.php").joinToString("")
    }

    val CUSTOMER_PORTAL_ENDPOINT: String by lazy {
        listOf("https://", "tvflux4k", ".com", "/p2p/", "customer_portal.php").joinToString("")
    }

    /**
     * Session Cookie & Token Manager (Item 16): preserves IPTV/Xtream session cookies
     * across HTTP 302 redirects and HLS segment requests without logging sensitive values.
     */
    private val sessionCookieStore = java.util.concurrent.ConcurrentHashMap<String, List<okhttp3.Cookie>>()

    private val streamCookieJar = object : okhttp3.CookieJar {
        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
            if (cookies.isNotEmpty()) {
                sessionCookieStore[url.host] = cookies
            }
        }

        override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
            val existing = sessionCookieStore[url.host] ?: return emptyList()
            val now = System.currentTimeMillis()
            return existing.filter { it.expiresAt >= now }
        }
    }

    fun clearHostSessionCookies(urlStr: String) {
        runCatching {
            val host = java.net.URI(urlStr).host
            if (!host.isNullOrBlank()) {
                sessionCookieStore.remove(host)
            }
        }
    }

    /**
     * IPTV TLS compatibility mode:
     * Accepts any non-empty server certificate chain and skips hostname checks
     * so legacy IPTV panels with self-signed, expired, or mismatched certificates
     * can connect. This intentionally weakens HTTPS peer authentication.
     * Keep this client scoped to IPTV traffic; never use it for sensitive services.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun OkHttpClient.Builder.applyUniversalSslCompatibility(): OkHttpClient.Builder {
        return runCatching {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) {
                        throw java.security.cert.CertificateException("Empty certificate chain")
                    }
                    // Deliberately accept all certificate issuers and validity dates for IPTV compatibility.
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            this.sslSocketFactory(sslContext.socketFactory, trustManager)
            this.hostnameVerifier { _, _ -> true }
            this.cookieJar(streamCookieJar)
            this.connectionSpecs(
                listOf(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT
                )
            )
            this
        }.getOrDefault(this)
    }

    val bootstrapHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyUniversalSslCompatibility()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyUniversalSslCompatibility()
            .dns(CloudflareDohManager.customDns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * High-capacity Android TV-safe client for syncing massive Xtream / M3U servers (supports up to 150,000+ items
     * even on 300-500 KB/s TV Wi-Fi connections without premature SocketTimeoutException).
     */
    val xtreamHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyUniversalSslCompatibility()
            .dns(CloudflareDohManager.customDns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // Unlimited total duration: never cut off while Live, Movies, or Series are downloading!
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Xtream VOD 302 Redirect Edge Token Cache (v22.0):
     * When an MP4 (with moov atom at EOF) or MKV (with Cues at EOF) redirects via HTTP 302 from the Xtream panel
     * to a storage CDN node, ExoPlayer makes a 2nd and 3rd Range: bytes=... request within milliseconds.
     * Sending those Range requests to the original panel URL triggers HTTP 403/429 (max_connections=1).
     * Caching the resolved CDN target URL per stream allows multi-range seeks to hit the storage node directly!
     */
    private data class CachedRedirectTarget(val resolvedUrl: String, val timestampMs: Long)
    private val vodRedirectCache = java.util.concurrent.ConcurrentHashMap<String, CachedRedirectTarget>()
    private const val VOD_REDIRECT_CACHE_TTL_MS = 20 * 60 * 1000L

    fun clearStreamRedirectCache(urlStr: String) {
        vodRedirectCache.remove(urlStr.trim())
    }

    val streamHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyUniversalSslCompatibility()
            .dns(CloudflareDohManager.customDns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS) // Never kill an active video stream!
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun cancelPendingStreamRequests() {
        // v23.2: aggressively cancel in-flight API/stream calls so channel zap never waits on old sockets
        runCatching {
            for (c in listOf(streamHttpClient, httpClient, bootstrapHttpClient, xtreamHttpClient)) {
                c.dispatcher.cancelAll()
            }
        }
    }

    fun evictIdleApiConnections() {
        runCatching {
            httpClient.connectionPool.evictAll()
            xtreamHttpClient.connectionPool.evictAll()
            streamHttpClient.connectionPool.evictAll()
            bootstrapHttpClient.connectionPool.evictAll()
        }
    }

    /**
     * Lightweight subscription & remote playlist metadata check (< 1 KB).
     * Used on app startup when the full channel catalog is already cached in local app data,
     * so opening the app is instant and does NOT re-download the heavy channel catalog unless requested!
     */
    private fun executeFastGet(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "LIVE-PRO/13.1 (Android; Universal)")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache, no-store, max-age=0")
            .build()
        val firstTry = runCatching {
            bootstrapHttpClient.newCall(req).execute().use { resp ->
                resp.body?.string()?.trim().orEmpty()
            }
        }.getOrDefault("")
        if (firstTry.startsWith("{")) return firstTry
        return runCatching {
            httpClient.newCall(req).execute().use { resp ->
                resp.body?.string()?.trim().orEmpty()
            }
        }.getOrDefault("")
    }

    private fun computeDaysFromExpireString(expDateStr: String): Int {
        if (expDateStr.isBlank()) return -1
        return runCatching {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val parsed = sdf.parse(expDateStr.take(10))
            if (parsed != null) {
                val diffMs = parsed.time + (23 * 3600 * 1000L) + (59 * 60 * 1000L) - System.currentTimeMillis()
                if (diffMs <= 0L) 0 else kotlin.math.ceil(diffMs.toDouble() / (24.0 * 3600.0 * 1000.0)).toInt().coerceAtLeast(0)
            } else -1
        }.getOrDefault(-1)
    }

    // Smart Year-Round API Load Governor:
    // - Caches quick subscription & remote playlist metadata for 45 minutes during automatic background resume checks
    //   so reopening/minimizing the app does NOT hammer api.php or customer_portal.php!
    // - Manual clicks on "تحديث الصلاحية" pass forceNetwork = true for instant real-time verification.
    private const val API_QUICK_CHECK_COOLDOWN_MS = 45 * 60 * 1000L
    @Volatile private var lastQuickCheckTimeMs: Long = 0L
    @Volatile private var lastQuickCheckDeviceId: String = ""
    @Volatile private var cachedQuickCheckResult: Pair<SubscriptionInfo, List<XtreamAccountConfig>>? = null
    private val lastPushedPortalFingerprints = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun invalidateQuickCheckCache() {
        lastQuickCheckTimeMs = 0L
        cachedQuickCheckResult = null
    }

    /**
     * Real-time subscription expiry & remote playlist metadata check (< 1 KB).
     * Uses 1 unified HTTP call (`api.php?action=get_xtream_lists`) which returns BOTH `subscription`
     * AND `xtream_lists` in a single lightweight JSON payload, falling back to `customer_portal.php`
     * or `check_subscription` only if needed — cutting server API traffic by >85% all year round!
     */
    suspend fun checkSubscriptionAndListsQuick(
        deviceId: String,
        deviceName: String,
        forceNetwork: Boolean = false
    ): Pair<SubscriptionInfo, List<XtreamAccountConfig>> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedQuickCheckResult
        if (!forceNetwork && cached != null && lastQuickCheckDeviceId == deviceId &&
            (now - lastQuickCheckTimeMs) < API_QUICK_CHECK_COOLDOWN_MS
        ) {
            return@withContext cached
        }

        val ts = now
        val urlApiLists = "$INTERNAL_ENDPOINT?action=get_xtream_lists&device_id=${enc(deviceId)}&device_name=${enc(deviceName)}&_t=$ts"
        val urlPortalLists = "$CUSTOMER_PORTAL_ENDPOINT?action=get_xtream_lists&device_id=${enc(deviceId)}&_t=$ts"
        val urlCheckSub = "$INTERNAL_ENDPOINT?action=check_subscription&device_id=${enc(deviceId)}&device_name=${enc(deviceName)}&_t=$ts"

        // Primary single-call query (returns both subscription + xtream_lists in <1 KB!)
        val apiListsBody = executeFastGet(urlApiLists)
        // PRODUCTION API governor: secondary endpoints only when primary failed (never duplicate traffic)
        val portalBody = if (!apiListsBody.startsWith("{")) {
            executeFastGet(urlPortalLists)
        } else ""
        val checkSubBody = if (!apiListsBody.contains("\"subscription\"") && !portalBody.contains("\"subscription\"")) {
            executeFastGet(urlCheckSub)
        } else ""

        if (!checkSubBody.startsWith("{") && !portalBody.startsWith("{") && !apiListsBody.startsWith("{")) {
            cached?.let { return@withContext it }
            error("تعذّر التحقق السريع من حالة الاشتراك")
        }

        // Parse primary subscription from api.php?action=check_subscription (or apiListsBody / portalBody)
        var resolvedSubObj: JSONObject? = null
        for (candidateBody in listOf(checkSubBody, apiListsBody, portalBody)) {
            if (!candidateBody.startsWith("{")) continue
            runCatching {
                val root = JSONObject(candidateBody)
                val dataNode = root.optJSONObject("data")
                val cand = dataNode?.optJSONObject("subscription")
                    ?: root.optJSONObject("subscription")
                    ?: dataNode
                    ?: root
                if (cand.has("active") || cand.has("expire") || cand.has("expire_date") || cand.has("days_left")) {
                    if (resolvedSubObj == null) {
                        resolvedSubObj = cand
                    } else {
                        val curExp = first(resolvedSubObj!!, "expire", "expire_date", "expires_at", "exp_date")
                        val newExp = first(cand, "expire", "expire_date", "expires_at", "exp_date")
                        if (curExp.isBlank() && newExp.isNotBlank()) {
                            resolvedSubObj = cand
                        }
                    }
                }
            }
        }

        val subObj = resolvedSubObj ?: JSONObject()
        val hasExplicitActiveField = subObj.has("active")
        val reasonStr = subObj.optString("reason", "ok").lowercase()
        val expDateStr = first(subObj, "expire", "expire_date", "expires_at", "exp_date")
        val msgStr = subObj.optString("message", "")
        val explicitlyExpired = (
            (hasExplicitActiveField && !subObj.optBoolean("active", true) &&
                (reasonStr == "expired" || reasonStr == "disabled" || ExpiryAlertHelper.isDateAlreadyExpired(expDateStr))) ||
                ExpiryAlertHelper.isDateAlreadyExpired(expDateStr)
            )
        val activeFlag = !explicitlyExpired

        val computedDaysFromDate = computeDaysFromExpireString(expDateStr)
        val rawDays = subObj.optInt("days_left", -1)
        val finalDays = when {
            explicitlyExpired -> 0
            computedDaysFromDate >= 0 -> computedDaysFromDate
            rawDays >= 0 -> rawDays
            else -> 30
        }

        val sub = SubscriptionInfo(
            active = activeFlag,
            isExpired = explicitlyExpired,
            isTrial = subObj.optBoolean("is_trial", reasonStr == "trial"),
            reason = if (explicitlyExpired) "expired" else reasonStr.ifBlank { "ok" },
            expireDate = expDateStr,
            daysLeft = finalDays,
            deviceName = subObj.optString("device_name", deviceName),
            message = msgStr
        )

        val combinedRemoteLists = mutableListOf<XtreamAccountConfig>()
        if (activeFlag) {
            for (bodyStr in listOf(apiListsBody, portalBody)) {
                if (!bodyStr.startsWith("{")) continue
                runCatching {
                    val j = JSONObject(bodyStr)
                    val arr = j.optJSONArray("xtream_lists")
                        ?: j.optJSONObject("data")?.optJSONArray("xtream_lists")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val xo = arr.optJSONObject(i) ?: continue
                            parseRemoteXtreamObject(xo, i)?.let { cfg ->
                                val existingIdx = combinedRemoteLists.indexOfFirst { it.id == cfg.id }
                                if (existingIdx >= 0) {
                                    combinedRemoteLists[existingIdx] = cfg
                                } else {
                                    combinedRemoteLists.add(cfg)
                                }
                            }
                        }
                    }
                }
            }
        }

        val finalPair = Pair(sub, combinedRemoteLists)
        lastQuickCheckDeviceId = deviceId
        lastQuickCheckTimeMs = System.currentTimeMillis()
        cachedQuickCheckResult = finalPair
        finalPair
    }

    /**
     * Pushes customer Xtream playlist to customer_portal.php and api.php ONLY if credentials/expiry changed.
     */
    suspend fun pushCustomerXtreamToPortal(
        deviceId: String,
        deviceName: String,
        account: XtreamAccountConfig
    ) = withContext(Dispatchers.IO) {
        val fingerprint = "${deviceId}|${account.id}|${account.serverName.trim()}|${account.serverUrl.trim()}|${account.username.trim()}|${account.password.trim()}|${account.m3uUrl.trim()}|${account.expDate.trim()}|${account.enabled}|${account.orderIndex}"
        if (lastPushedPortalFingerprints[account.id] == fingerprint) {
            return@withContext
        }
        lastPushedPortalFingerprints[account.id] = fingerprint
        invalidateQuickCheckCache()
        val form = FormBody.Builder()
            .add("action", "save_xtream")
            .add("device_id", deviceId)
            .add("device_name", deviceName)
            .add("playlist_id", account.id)
            .add("server_name", account.serverName)
            .add("server_url", account.serverUrl)
            .add("xtream_user", account.username)
            .add("xtream_pass", account.password)
            .add("m3u_url", account.m3uUrl)
            .add("exp_date", account.expDate)
            .add("enabled", if (account.enabled) "1" else "0")
            .add("order_index", account.orderIndex.toString())
            .build()

        runCatching {
            val reqPortal = Request.Builder()
                .url(CUSTOMER_PORTAL_ENDPOINT)
                .post(form)
                .header("User-Agent", "LIVE-PRO/10.0 (Android; TV)")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(reqPortal).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    if (!json.optBoolean("success", true)) {
                        val err = json.optString("error", "انتهت صلاحية المشغل. يرجى التجديد عبر علي سات: $ALI_SAT_PHONE_NUMBER")
                        error(err)
                    }
                }
            }
        }.onFailure { e ->
            if (e.message?.contains("صلاحية") == true || e.message?.contains(ALI_SAT_PHONE_NUMBER) == true) {
                throw e
            }
        }

        runCatching {
            val reqMain = Request.Builder()
                .url(INTERNAL_ENDPOINT)
                .post(form)
                .header("User-Agent", "LIVE-PRO/10.0 (Android; TV)")
                .build()
            httpClient.newCall(reqMain).execute().close()
        }
    }

    suspend fun deleteCustomerXtreamFromPortal(
        deviceId: String,
        playlistId: String
    ) = withContext(Dispatchers.IO) {
        lastPushedPortalFingerprints.remove(playlistId)
        invalidateQuickCheckCache()
        val form = FormBody.Builder()
            .add("action", "delete_xtream")
            .add("device_id", deviceId)
            .add("playlist_id", playlistId)
            .build()
        runCatching {
            httpClient.newCall(Request.Builder().url(CUSTOMER_PORTAL_ENDPOINT).post(form).build()).execute().close()
        }
        runCatching {
            httpClient.newCall(Request.Builder().url(INTERNAL_ENDPOINT).post(form).build()).execute().close()
        }
    }

    /** Server favorites backup bound to Screen Code (device_id). One list only — restore, never duplicate. */
    @Volatile private var lastFavoritesPushFingerprint: String = ""

    private fun favoritesFingerprint(list: List<TvChannel>): String {
        return list.joinToString("|") { ch ->
            "${ch.stableKey}\u0001${ch.name}\u0001${ch.url}\u0001${ch.contentType.name}\u0001${ch.playlistId}"
        }.hashCode().toString() + ":" + list.size
    }

    private fun serializeFavoritesForServer(list: List<TvChannel>): String {
        val arr = JSONArray()
        list.take(5000).forEach { ch ->
            arr.put(JSONObject().apply {
                put("id", ch.id)
                put("number", ch.number)
                put("name", ch.name)
                put("url", ch.url)
                put("group", ch.group)
                put("logo", ch.logo ?: "")
                put("userAgent", ch.userAgent ?: "")
                put("referer", ch.referer ?: "")
                put("origin", ch.origin ?: "")
                put("cookie", ch.cookie ?: "")
                put("drmScheme", ch.drmScheme)
                put("drmKey", ch.drmKey)
                put("contentType", ch.contentType.name)
                put("rating", ch.rating)
                put("releaseYear", ch.releaseYear)
                put("plot", ch.plot)
                put("seriesId", ch.seriesId)
                put("playlistId", ch.playlistId)
                put("playlistName", ch.playlistName)
                put("streamId", ch.streamId)
                put("streamBaseUrl", ch.streamBaseUrl)
                put("streamUsername", ch.streamUsername)
                put("streamPassword", ch.streamPassword)
                put("containerExtension", ch.containerExtension)
                put("videoCodecHint", ch.videoCodecHint)
                put("audioCodecHint", ch.audioCodecHint)
            })
        }
        return arr.toString()
    }

    private fun parseFavoritesFromServer(raw: String): List<TvChannel> {
        if (raw.isBlank()) return emptyList()
        val arr = runCatching {
            val trimmed = raw.trim()
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("favorites")
                        ?: root.optJSONObject("data")?.optJSONArray("favorites")
                        ?: JSONArray()
                }
                else -> JSONArray()
            }
        }.getOrDefault(JSONArray())
        val out = ArrayList<TvChannel>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val url = o.optString("url").trim()
            if (name.isBlank() || url.isBlank()) continue
            val typeStr = o.optString("contentType", StreamContentType.LIVE_TV.name)
            val contentType = runCatching { StreamContentType.valueOf(typeStr) }.getOrDefault(StreamContentType.LIVE_TV)
            val ch = TvChannel(
                id = o.optInt("id", i + 1),
                number = o.optInt("number", i + 1),
                name = name,
                url = url,
                group = o.optString("group", "عام").ifBlank { "عام" },
                logo = o.optString("logo").takeIf { it.isNotBlank() },
                userAgent = o.optString("userAgent").takeIf { it.isNotBlank() },
                referer = o.optString("referer").takeIf { it.isNotBlank() },
                origin = o.optString("origin").takeIf { it.isNotBlank() },
                cookie = o.optString("cookie").takeIf { it.isNotBlank() },
                drmScheme = o.optString("drmScheme", ""),
                drmKey = o.optString("drmKey", ""),
                contentType = contentType,
                rating = o.optString("rating", ""),
                releaseYear = o.optString("releaseYear", ""),
                plot = o.optString("plot", ""),
                seriesId = o.optInt("seriesId", 0),
                playlistId = o.optString("playlistId", "PORTAL_MAIN"),
                playlistName = o.optString("playlistName", "الرئيسية"),
                streamId = o.optInt("streamId", 0),
                streamBaseUrl = o.optString("streamBaseUrl", ""),
                streamUsername = o.optString("streamUsername", ""),
                streamPassword = o.optString("streamPassword", ""),
                containerExtension = o.optString("containerExtension", ""),
                videoCodecHint = o.optString("videoCodecHint", ""),
                audioCodecHint = o.optString("audioCodecHint", "")
            )
            val key = when {
                ch.contentType == StreamContentType.SERIES && ch.seriesId > 0 -> "S|${ch.playlistId}|${ch.seriesId}"
                else -> ch.stableKey
            }
            if (seen.add(key)) out.add(ch)
        }
        return out
    }

    suspend fun pushFavoritesToServer(deviceId: String, favorites: List<TvChannel>) = withContext(Dispatchers.IO) {
        val id = deviceId.trim().uppercase()
        if (id.isBlank() || !id.startsWith("DEV-")) return@withContext
        val fp = favoritesFingerprint(favorites)
        if (fp == lastFavoritesPushFingerprint) return@withContext
        val payload = serializeFavoritesForServer(favorites)
        val form = FormBody.Builder()
            .add("action", "save_favorites")
            .add("device_id", id)
            .add("favorites_json", payload)
            .add("count", favorites.size.toString())
            .build()
        var ok = false
        for (endpoint in listOf(CUSTOMER_PORTAL_ENDPOINT, INTERNAL_ENDPOINT)) {
            val success = runCatching {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(form)
                    .header("User-Agent", "LIVE-PRO/23.2 (Android; FavoritesBackup)")
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!body.startsWith("{")) return@use resp.isSuccessful
                    val json = JSONObject(body)
                    json.optBoolean("success", false) || json.optBoolean("ok", false) || resp.isSuccessful
                }
            }.getOrDefault(false)
            if (success) {
                ok = true
                break
            }
        }
        if (ok) lastFavoritesPushFingerprint = fp
    }

    /** null = network/no row; list (maybe empty) = explicit server backup */
    suspend fun pullFavoritesFromServer(deviceId: String): List<TvChannel>? = withContext(Dispatchers.IO) {
        val id = deviceId.trim().uppercase()
        if (id.isBlank() || !id.startsWith("DEV-")) return@withContext null
        val ts = System.currentTimeMillis()
        val urls = listOf(
            "$CUSTOMER_PORTAL_ENDPOINT?action=get_favorites&device_id=${enc(id)}&_t=$ts",
            "$INTERNAL_ENDPOINT?action=get_favorites&device_id=${enc(id)}&_t=$ts"
        )
        for (url in urls) {
            val body = runCatching {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "LIVE-PRO/23.2 (Android; FavoritesRestore)")
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache, no-store, max-age=0")
                    .build()
                bootstrapHttpClient.newCall(req).execute().use { resp ->
                    resp.body?.string()?.trim().orEmpty()
                }
            }.getOrDefault("")
            if (!body.startsWith("{")) continue
            val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
            val data = root.optJSONObject("data")
            val found = when {
                root.has("found") -> root.optBoolean("found")
                data != null && data.has("found") -> data.optBoolean("found")
                root.has("favorites") || (data != null && data.has("favorites")) -> true
                else -> false
            }
            if (!found) {
                // Explicit empty / no backup row
                return@withContext null
            }
            val arr = root.optJSONArray("favorites")
                ?: data?.optJSONArray("favorites")
            val list = when {
                arr != null -> parseFavoritesFromServer(arr.toString())
                else -> parseFavoritesFromServer(root.optString("favorites_json", data?.optString("favorites_json", "") ?: ""))
            }
            lastFavoritesPushFingerprint = favoritesFingerprint(list)
            return@withContext list
        }
        null
    }

    private val directDataTxtUrl: String by lazy {
        listOf("https://", "tvflux4k", ".com", "/p2p/", "data.txt").joinToString("")
    }

    /**
     * Fetches the live server catalog from the server (customer_portal.php / api.php / fallback)
     * and merges it into KnownIptvServersCatalog so the Phone or TV always checks the newest hosts.
     */
    suspend fun fetchServerDiscoveryCatalog(deviceId: String = ""): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val discovered = LinkedHashMap<String, String>()
        val endpoints = listOf(
            "$CUSTOMER_PORTAL_ENDPOINT?action=get_discovery_hosts&device_id=${enc(deviceId)}",
            "$INTERNAL_ENDPOINT?action=get_discovery_hosts&device_id=${enc(deviceId)}"
        )
        for (ep in endpoints) {
            runCatching {
                val req = Request.Builder()
                    .url(ep)
                    .header("User-Agent", "LIVE-PRO/21.0 (Android; Universal)")
                    .header("Accept", "application/json")
                    .build()
                bootstrapHttpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()?.trim().orEmpty()
                    if (body.startsWith("{")) {
                        val root = JSONObject(body)
                        val arr = root.optJSONArray("servers")
                            ?: root.optJSONObject("data")?.optJSONArray("servers")
                        if (arr != null) {
                            for (i in 0 until arr.length()) {
                                val item = arr.optJSONObject(i) ?: continue
                                val rawU = item.optString("url", "").trim().removeSuffix("/")
                                val rawN = item.optString("name", "VIP Server").trim().ifBlank { "VIP Server" }
                                if (rawU.isNotBlank()) {
                                    val normU = XtreamRepository.normalizeServerUrl(rawU)
                                    if (normU.isNotBlank()) discovered[normU.lowercase()] = "$rawN|$normU"
                                }
                            }
                        }
                    }
                }
            }
            if (discovered.isNotEmpty()) break
        }

        // Also check raw server list file if endpoints haven't been updated on the remote server yet
        if (discovered.isEmpty()) {
            runCatching {
                val req = Request.Builder()
                    .url(directDataTxtUrl)
                    .header("User-Agent", "LIVE-PRO/21.0 (Android; Universal)")
                    .build()
                bootstrapHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val lines = resp.body?.string().orEmpty().lines()
                        for (line in lines) {
                            val trimmed = line.trim()
                            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("<")) continue
                            val nm = if (trimmed.contains("|")) trimmed.substringBefore("|").trim() else "VIP Server"
                            val u = if (trimmed.contains("|")) trimmed.substringAfter("|").trim() else trimmed
                            val normU = XtreamRepository.normalizeServerUrl(u)
                            if (normU.isNotBlank()) {
                                discovered[normU.lowercase()] = "${nm.ifBlank { "VIP Server" }}|$normU"
                            }
                        }
                    }
                }
            }
        }

        val remotePairs = discovered.values.map { pairStr ->
            val nm = pairStr.substringBefore("|")
            val u = pairStr.substringAfter("|")
            nm to u
        }
        if (remotePairs.isNotEmpty()) {
            KnownIptvServersCatalog.registerRemoteServers(remotePairs)
        }
        KnownIptvServersCatalog.getAllServers()
    }

    /**
     * Auto-Discovery & Verification on Phone/TV:
     * 1. Fetches the latest server host list from the server and merges with local catalog.
     * 2. Probes candidate servers in fast parallel batches directly from the device to find the matching server.
     * 3. Falls back to portal server-side check if needed.
     */
    suspend fun autoDiscoverXtreamHostViaPortal(
        deviceId: String,
        username: String,
        password: String
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        val cleanUser = username.trim()
        val cleanPass = password.trim()
        if (cleanUser.isBlank() || cleanPass.isBlank()) return@withContext null

        // If user pasted a full M3U link into username or password field
        val m3uExtracted = SmartM3uParser.tryExtractXtreamCredentials(cleanUser)
            ?: SmartM3uParser.tryExtractXtreamCredentials(cleanPass)
        if (m3uExtracted != null) {
            val brand = KnownIptvServersCatalog.detectServerNameByHost(m3uExtracted.serverUrl).ifBlank { "XTREAM VIP" }
            return@withContext Pair(m3uExtracted.serverUrl, brand)
        }

        // Step 1: Fetch server catalog from server and probe directly on the device in parallel batches
        val allCandidates = fetchServerDiscoveryCatalog(deviceId)
        val deviceMatch = XtreamRepository.probeServersForAccountOnDevice(
            username = cleanUser,
            password = cleanPass,
            candidates = allCandidates
        )
        if (deviceMatch != null) {
            return@withContext deviceMatch
        }

        // Step 2: Fallback to server-side test_xtream_connection
        val form = FormBody.Builder()
            .add("action", "test_xtream_connection")
            .add("device_id", deviceId)
            .add("server_url", "")
            .add("username", cleanUser)
            .add("password", cleanPass)
            .build()
        return@withContext try {
            val req = Request.Builder()
                .url(CUSTOMER_PORTAL_ENDPOINT)
                .post(form)
                .header("User-Agent", "LIVE-PRO/21.0 (Android; TV)")
                .header("Accept", "application/json")
                .build()
            bootstrapHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string()?.trim().orEmpty()
                if (!body.startsWith("{")) return@use null
                val json = JSONObject(body)
                if (json.optBoolean("ok", false)) {
                    val url = json.optString("server_url", "").trim()
                    val name = json.optString("server_name", "XTREAM VIP").trim()
                    if (url.isNotBlank()) Pair(url, name) else null
                } else {
                    null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun transferDeviceToNewScreenCode(
        oldDeviceId: String,
        newDeviceId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        invalidateQuickCheckCache()
        val form = FormBody.Builder()
            .add("action", "transfer_device")
            .add("device_id", oldDeviceId.trim().uppercase())
            .add("new_device_id", newDeviceId.trim().uppercase())
            .build()
        return@withContext try {
            val req = Request.Builder()
                .url(CUSTOMER_PORTAL_ENDPOINT)
                .post(form)
                .header("User-Agent", "LIVE-PRO/16.2 (Android; TV)")
                .header("Accept", "application/json")
                .build()
            bootstrapHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string()?.trim().orEmpty()
                if (body.startsWith("{")) {
                    val json = JSONObject(body)
                    val ok = json.optBoolean("success", false) || json.optBoolean("ok", false)
                    val msg = json.optString("message", json.optString("error", "تمت معالجة طلب النقل"))
                    if (ok) Result.success(msg) else Result.failure(IllegalStateException(msg))
                } else {
                    Result.failure(IllegalStateException("تعذّر الاتصال بفضاء الزبون لنقل الشاشة"))
                }
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private fun fetchListsFromCustomerPortal(deviceId: String): List<XtreamAccountConfig> {
        return try {
            val url = "$CUSTOMER_PORTAL_ENDPOINT?action=get_xtream_lists&device_id=${enc(deviceId)}&_t=${System.currentTimeMillis()}"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "LIVE-PRO/13.0 (Android; TV)")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache, no-store, max-age=0")
                .build()
            bootstrapHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string()?.trim().orEmpty()
                if (!body.startsWith("{")) return emptyList()
                val json = JSONObject(body)
                val arr = json.optJSONArray("xtream_lists") ?: json.optJSONObject("data")?.optJSONArray("xtream_lists") ?: return emptyList()
                val list = mutableListOf<XtreamAccountConfig>()
                for (i in 0 until arr.length()) {
                    val xo = arr.optJSONObject(i) ?: continue
                    parseRemoteXtreamObject(xo, i)?.let { list.add(it) }
                }
                list
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun redeemActivationCode(
        deviceId: String,
        deviceName: String,
        rawCode: String
    ): ApiSyncResult = withContext(Dispatchers.IO) {
        val rawClean = rawCode.trim().uppercase().removePrefix("DEV-")
        val alnumOnly = rawClean.replace(Regex("[^A-Z0-9]"), "")
        val cleanCode = if (alnumOnly.length == 8) {
            "${alnumOnly.substring(0, 4)}-${alnumOnly.substring(4, 8)}"
        } else {
            rawClean
        }
        if (!Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}$").matches(cleanCode)) {
            error("CODE_INVALID|⚠️ صيغة رمز التفعيل غير صحيحة ($rawCode).\nيرجى إدخال رمز مكون من 8 أحرف وأرقام بالصيغة الصحيحة (مثال: A1B2-C3D4).")
        }

        val form = FormBody.Builder()
            .add("action", "activate_card")
            .add("device_id", deviceId)
            .add("device_name", deviceName)
            .add("card_code", cleanCode)
            .add("code", cleanCode)
            .build()

        val req = Request.Builder()
            .url(INTERNAL_ENDPOINT)
            .post(form)
            .header("User-Agent", "LIVE-PRO/13.2 (Android; Universal)")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-cache, no-store, max-age=0")
            .build()

        var respCode = 0
        var respBody = ""
        for (client in listOf(bootstrapHttpClient, httpClient)) {
            val okAttempt = runCatching {
                client.newCall(req).execute().use { resp ->
                    respCode = resp.code
                    respBody = resp.body?.string()?.trim().orEmpty()
                }
                respBody.startsWith("{")
            }.getOrDefault(false)
            if (okAttempt) break
        }

        if (!respBody.startsWith("{")) {
            error("NETWORK_ERROR|📡 تعذّر الاتصال بخادم التفعيل حالياً (رمز $respCode).\nيرجى التحقق من اتصال الإنترنت ثم المحاولة مجدداً.")
        }

        val json = JSONObject(respBody)
        val ok = json.optBoolean("success", false)
        if (!ok) {
            val errCode = json.optString("error_code", "").uppercase()
            val serverErr = json.optString("error", "تعذّر تفعيل القسيمة")
            val resolvedTag = when {
                errCode == "CARD_USED" || serverErr.contains("مستخدم") || serverErr.contains("مستعمل") || serverErr.contains("محروق") -> "CARD_USED"
                errCode == "CARD_EXPIRED" || serverErr.contains("انتهت صلاحية") -> "CARD_EXPIRED"
                errCode == "CARD_NOT_FOUND" || errCode == "CARD_UNAVAILABLE" || serverErr.contains("غير موجود") || serverErr.contains("غير متاح") -> "CARD_NOT_FOUND"
                errCode == "RATE_LIMITED" || respCode == 429 -> "RATE_LIMITED"
                errCode == "CODE_INVALID" -> "CODE_INVALID"
                else -> "CARD_NOT_FOUND"
            }
            val finalMsg = when (resolvedTag) {
                "CARD_USED" -> "⛔ رمز التفعيل ($cleanCode) مستخدم ومحروق مسبقاً!\nلا يمكن إعادة استخدام نفس القسيمة مرة أخرى نهائياً. يرجى إدخال رمز تفعيل جديد أو التواصل مع علي سات: $ALI_SAT_PHONE_NUMBER"
                "CARD_EXPIRED" -> "⛔ انتهت صلاحية بطاقة التفعيل ($cleanCode).\nيرجى التواصل مع علي سات ($ALI_SAT_PHONE_NUMBER) للحصول على قسيمة جديدة."
                "CARD_NOT_FOUND" -> "❌ رمز التفعيل ($cleanCode) غير صحيح أو غير موجود في النظام.\nيرجى التأكد من كتابة الأحرف والأرقام بدقة (مثال: A1B2-C3D4)."
                "RATE_LIMITED" -> "⏳ تم تجاوز عدد المحاولات المسموح به مؤقتاً.\nيرجى الانتظار قليلاً ثم المحاولة برمز تفعيل صحيح."
                else -> serverErr
            }
            error("$resolvedTag|$finalMsg")
        }

        val dataNode = json.optJSONObject("data") ?: json
        val subNode = dataNode.optJSONObject("subscription") ?: json.optJSONObject("subscription") ?: dataNode
        val newExp = first(dataNode, "new_expire", "expire", "expire_date").ifBlank {
            first(subNode, "expire", "expire_date", "expires_at", "exp_date")
        }
        val addedDays = dataNode.optInt("added_days", 0)
        val calcDays = computeDaysFromExpireString(newExp)
        val rawDaysLeft = subNode.optInt("days_left", if (calcDays >= 0) calcDays else addedDays.coerceAtLeast(30))
        val successMsg = dataNode.optString("message", "✅ تم تفعيل وتجديد الاشتراك بنجاح!")

        invalidateQuickCheckCache()
        val activatedSub = SubscriptionInfo(
            active = true,
            isExpired = false,
            isTrial = false,
            reason = "ok",
            expireDate = newExp,
            daysLeft = if (calcDays >= 0) calcDays else rawDaysLeft.coerceAtLeast(1),
            deviceName = deviceName,
            message = "$successMsg|ADDED:$addedDays|EXP:$newExp|CODE:$cleanCode"
        )

        val fullSync = runCatching { syncWithServer(deviceId, deviceName, "") }.getOrNull()
        if (fullSync != null && fullSync.subscription.active) {
            fullSync.copy(
                subscription = fullSync.subscription.copy(
                    active = true,
                    isExpired = false,
                    expireDate = newExp.ifBlank { fullSync.subscription.expireDate },
                    daysLeft = activatedSub.daysLeft,
                    message = activatedSub.message
                )
            )
        } else {
            ApiSyncResult(
                channels = emptyList(),
                subscription = activatedSub,
                remoteXtreamLists = emptyList()
            )
        }
    }

    suspend fun syncWithServer(
        deviceId: String,
        deviceName: String,
        activationCode: String = "",
        knownCatalogHash: String = ""
    ): ApiSyncResult = withContext(Dispatchers.IO) {
        val codeParam = if (activationCode.isNotBlank()) "&code=${enc(activationCode)}" else ""
        val hashParam = if (knownCatalogHash.isNotBlank()) "&client_hash=${enc(knownCatalogHash)}" else ""
        val url = "$INTERNAL_ENDPOINT?action=get_channels&device_id=${enc(deviceId)}&device_name=${enc(deviceName)}$codeParam$hashParam"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "LIVE-PRO/10.0 (Android; TV)")
            .header("Accept", "application/json")
            .build()

        val (statusCode, rawBody) = try {
            xtreamHttpClient.newCall(req).execute().use { resp ->
                Pair(resp.code, resp.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            error("تعذّر الاتصال بخادم البث. تحقّق من الإنترنت.")
        }

        if (rawBody.isBlank()) {
            error("استجابة غير مكتملة من الخادم (رمز $statusCode)")
        }

        val root = try {
            JSONObject(rawBody.trim())
        } catch (_: Exception) {
            error("تعذّر قراءة بيانات الخادم حالياً. حاول مجدداً.")
        }

        val success = root.optBoolean("success", false)
        val dataObj = root.optJSONObject("data")

        val subObj = dataObj?.optJSONObject("subscription") ?: root.optJSONObject("subscription")
        val rawErr = root.optString("error")
        val topError = if (rawErr.isNotBlank()) rawErr else subObj?.optString("message").orEmpty()

        val remoteXtreamLists = mutableListOf<XtreamAccountConfig>()

        val multiArrays = listOfNotNull(
            dataObj?.optJSONArray("xtream_lists"),
            dataObj?.optJSONArray("playlists"),
            dataObj?.optJSONArray("xtream_accounts"),
            root.optJSONArray("xtream_lists"),
            root.optJSONArray("playlists"),
            subObj?.optJSONArray("xtream_lists")
        )
        for (arr in multiArrays) {
            for (i in 0 until arr.length()) {
                val xo = arr.optJSONObject(i) ?: continue
                parseRemoteXtreamObject(xo, i)?.let { cfg ->
                    if (remoteXtreamLists.none { it.serverUrl == cfg.serverUrl && it.username == cfg.username && it.m3uUrl == cfg.m3uUrl }) {
                        remoteXtreamLists.add(cfg)
                    }
                }
            }
        }

        val singleXtreamObj = dataObj?.optJSONObject("xtream")
            ?: root.optJSONObject("xtream")
            ?: subObj?.optJSONObject("xtream")
        if (singleXtreamObj != null) {
            parseRemoteXtreamObject(singleXtreamObj, remoteXtreamLists.size)?.let { cfg ->
                if (remoteXtreamLists.none { it.serverUrl == cfg.serverUrl && it.username == cfg.username }) {
                    remoteXtreamLists.add(cfg)
                }
            }
        }

        val portalLists = fetchListsFromCustomerPortal(deviceId)
        for (pl in portalLists) {
            if (remoteXtreamLists.none { it.id == pl.id || (it.serverUrl == pl.serverUrl && it.username == pl.username) }) {
                remoteXtreamLists.add(pl)
            }
        }

        val subscription = if (subObj != null) {
            val reasonStr = subObj.optString("reason", if (success) "ok" else "").lowercase()
            val expDateStr = first(subObj, "expire", "expire_date", "expires_at", "exp_date")
            val msgStr = subObj.optString("message", topError)
            val explicitlyExpired = (
                (!subObj.optBoolean("active", true) &&
                    (reasonStr == "expired" || reasonStr == "disabled" || statusCode == 403 || ExpiryAlertHelper.isDateAlreadyExpired(expDateStr))) ||
                    ExpiryAlertHelper.isDateAlreadyExpired(expDateStr)
                )
            val activeFlag = !explicitlyExpired
            val days = subObj.optInt("days_left", if (activeFlag) 30 else 0)

            SubscriptionInfo(
                active = activeFlag,
                isExpired = explicitlyExpired,
                isTrial = subObj.optBoolean("is_trial", reasonStr == "trial"),
                reason = if (explicitlyExpired) "expired" else reasonStr.ifBlank { "ok" },
                expireDate = expDateStr,
                daysLeft = if (explicitlyExpired) 0 else days.coerceAtLeast(1),
                deviceName = subObj.optString("device_name", deviceName),
                message = msgStr
            )
        } else {
            val explicitlyExpired = (statusCode == 403 && (topError.contains("انته") || topError.contains("منته") || topError.contains("تعطيل")))
            SubscriptionInfo(
                active = !explicitlyExpired,
                isExpired = explicitlyExpired,
                reason = if (explicitlyExpired) "expired" else "ok",
                daysLeft = if (explicitlyExpired) 0 else 30,
                message = topError
            )
        }

        if (!success || !subscription.active) {
            val fallbackMsg = when {
                subscription.message.isNotBlank() -> subscription.message
                topError.isNotBlank() -> topError
                else -> "انتهت صلاحية المشغل للشاشة ($deviceId). يرجى التجديد عبر التواصل مع علي سات : $ALI_SAT_PHONE_NUMBER"
            }
            return@withContext ApiSyncResult(
                channels = emptyList(),
                subscription = subscription.copy(
                    active = false,
                    isExpired = true,
                    message = fallbackMsg
                ),
                remoteXtreamLists = emptyList()
            )
        }

        val channelsArr: JSONArray = when {
            dataObj?.opt("channels") is JSONArray -> dataObj.getJSONArray("channels")
            root.opt("data") is JSONArray -> root.getJSONArray("data")
            root.opt("channels") is JSONArray -> root.getJSONArray("channels")
            else -> JSONArray()
        }

        val parsedChannels = ArrayList<TvChannel>(channelsArr.length())
        val parsedMovies = ArrayList<TvChannel>()
        val parsedSeries = ArrayList<TvChannel>()

        for (i in 0 until channelsArr.length()) {
            val o = channelsArr.optJSONObject(i) ?: continue
            val rawStreamUrl = first(
                o,
                "original_url",
                "encrypted_url",
                "url",
                "stream_url",
                "play_url",
                "link"
            )
            if (rawStreamUrl.isBlank()) continue

            val pipe = splitPipeHeaders(rawStreamUrl)
            val id = o.optInt("id", i + 1)
            val number = o.optInt("number", i + 1)
            val rawName = first(o, "name", "title", "channel_name")
            val name = if (rawName.isNotBlank()) rawName else "قناة $number"
            val rawCat = first(o, "category", "group", "category_name")
            val category = if (rawCat.isNotBlank()) rawCat else "عام"
            if (AdultContentFilter.isAdultOr18Plus(name, category)) continue

            val logo = first(o, "logo_url", "logo", "stream_icon", "icon")
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }

            val rawType = first(o, "type", "stream_type", "content_type").lowercase()
            val lowCat = category.lowercase()
            val lowUrl = pipe.cleanUrl.lowercase()

            val lowName = name.lowercase()
            val cleanLowUrl = lowUrl.substringBefore("?")
            val isSeriesItem = rawType == "series" ||
                lowCat.contains("مسلسل") || lowCat.contains("series") || lowCat.contains("série") ||
                cleanLowUrl.contains("/series/") ||
                Regex("s\\d{1,2}\\s*[eex]\\s*\\d{1,3}", RegexOption.IGNORE_CASE).containsMatchIn(lowName) ||
                lowName.contains("حلقة")
            val isMovieItem = !isSeriesItem && (
                rawType == "movie" || rawType == "vod" ||
                    lowCat.contains("افلام") || lowCat.contains("أفلام") ||
                    lowCat.contains("movie") || lowCat.contains("film") || lowCat.contains("vod") ||
                    cleanLowUrl.contains("/movie/") ||
                    cleanLowUrl.endsWith(".mp4") || cleanLowUrl.endsWith(".mkv") ||
                    cleanLowUrl.endsWith(".avi") || cleanLowUrl.endsWith(".mov") ||
                    cleanLowUrl.endsWith(".webm") || cleanLowUrl.endsWith(".flv")
                )
            val contentType = when {
                isSeriesItem -> StreamContentType.SERIES
                isMovieItem -> StreamContentType.MOVIE
                else -> StreamContentType.LIVE_TV
            }

            val item = TvChannel(
                id = id,
                number = number,
                name = name,
                url = pipe.cleanUrl,
                group = category,
                logo = logo,
                userAgent = first(o, "user_agent", "userAgent").takeIf { it.isNotBlank() } ?: pipe.userAgent,
                referer = first(o, "referer", "referrer").takeIf { it.isNotBlank() } ?: pipe.referer,
                origin = first(o, "origin").takeIf { it.isNotBlank() } ?: pipe.origin,
                cookie = first(o, "cookie").takeIf { it.isNotBlank() } ?: pipe.cookie,
                contentType = contentType,
                playlistId = "PORTAL_MAIN",
                playlistName = "قائمة الخادم الرئيسية"
            )

            when (contentType) {
                StreamContentType.MOVIE -> parsedMovies.add(item)
                StreamContentType.SERIES -> parsedSeries.add(item)
                StreamContentType.LIVE_TV -> parsedChannels.add(item)
            }
        }

        parsedChannels.sortBy { it.number }

        val serverHash = dataObj?.optString("catalog_hash", "").orEmpty()
        val unchangedFlag = dataObj?.optBoolean("is_unchanged", false) == true
        ApiSyncResult(
            channels = parsedChannels,
            movies = parsedMovies,
            series = parsedSeries,
            subscription = subscription,
            remoteXtreamLists = remoteXtreamLists,
            serverCatalogHash = serverHash,
            isDifferentialUnchanged = unchangedFlag
        )
    }

    private fun parseRemoteXtreamObject(xo: JSONObject, index: Int): XtreamAccountConfig? {
        val rawUrl = first(xo, "server_url", "url", "host", "portal_url").trim()
        val sUser = first(xo, "username", "user", "xtream_user")
        val sPass = first(xo, "password", "pass", "xtream_pass")
        val m3u = first(xo, "m3u_url", "m3u", "playlist_url")
        val rawName = first(xo, "server_name", "name", "title")
        val sUrl = if (rawUrl.isBlank() || (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true) && !rawUrl.contains("."))) {
            KnownIptvServersCatalog.resolveHostByServerName(rawUrl.ifBlank { rawName }).ifBlank { rawUrl }
        } else rawUrl
        val sName = rawName.ifBlank {
            KnownIptvServersCatalog.detectServerNameByHost(sUrl).ifBlank { "قائمة XTREAM #${index + 1}" }
        }
        val id = first(xo, "id", "playlist_id").ifBlank { "REMOTE_${index + 1}_${sUrl.hashCode()}" }

        return if ((sUrl.isNotBlank() && sUser.isNotBlank() && sPass.isNotBlank()) || m3u.isNotBlank()) {
            XtreamAccountConfig(
                id = id,
                serverName = sName,
                serverUrl = sUrl,
                username = sUser,
                password = sPass,
                m3uUrl = m3u,
                enabled = xo.opt("enabled").let { v ->
                    when (v) {
                        is Boolean -> v
                        is Number -> v.toInt() != 0
                        is String -> v != "0" && !v.equals("false", ignoreCase = true)
                        else -> true
                    }
                },
                isRemoteFromPortal = true,
                orderIndex = index,
                expDate = first(xo, "exp_date", "expire_date", "expire")
            )
        } else null
    }

    private data class PipeHeaderResult(
        val cleanUrl: String,
        val userAgent: String? = null,
        val referer: String? = null,
        val origin: String? = null,
        val cookie: String? = null
    )

    private fun splitPipeHeaders(rawUrl: String): PipeHeaderResult {
        if (!rawUrl.contains('|')) return PipeHeaderResult(rawUrl.trim())
        val mainUrl = rawUrl.substringBefore('|').trim()
        val headerPart = rawUrl.substringAfter('|').trim()
        var ua: String? = null
        var ref: String? = null
        var orig: String? = null
        var cook: String? = null

        val pairs = headerPart.split('&', '|')
        for (pair in pairs) {
            val k = pair.substringBefore('=').trim().lowercase()
            val v = pair.substringAfter('=', "").trim()
            if (v.isBlank()) continue
            when (k) {
                "user-agent", "user_agent" -> ua = v
                "referer", "referrer" -> ref = v
                "origin" -> orig = v
                "cookie" -> cook = v
            }
        }
        return PipeHeaderResult(mainUrl, ua, ref, orig, cook)
    }

    private fun first(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.opt(k)
            if (v != null && v != JSONObject.NULL) {
                val s = v.toString().trim()
                if (s.isNotBlank() && s != "null") return s
            }
        }
        return ""
    }

    private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())
}
