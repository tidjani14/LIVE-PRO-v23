package com.tvflux.pro

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    private val ADMIN_UPDATE_ENDPOINT: String by lazy {
        listOf("https://", "tvflux4k", ".com", "/p2p/", "admin.php").joinToString("")
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
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 128
                maxRequestsPerHost = 32
            })
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyUniversalSslCompatibility()
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 128
                maxRequestsPerHost = 32
            })
            .dns(CloudflareDohManager.customDns)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * High-capacity parallel client for syncing Live TV, Movies, and Series simultaneously
     * with maxRequestsPerHost = 32 (eliminates OkHttp's default 5-connection bottleneck!).
     */
    val xtreamHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyUniversalSslCompatibility()
            .dispatcher(okhttp3.Dispatcher().apply {
                maxRequests = 128
                maxRequestsPerHost = 32
            })
            .dns(CloudflareDohManager.customDns)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
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
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
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
        // قطع كافة اتصالات البث السابقة فوراً وإفراغ الـ Sockets المفتوحة لمنع ظهور اتصالين (2 Connections) في السيرفر
        runCatching {
            streamHttpClient.dispatcher.cancelAll()
            streamHttpClient.connectionPool.evictAll()
        }
    }

    fun terminatePreviousStreamSessionAndFlushCache(previousUrl: String = "") {
        runCatching {
            val cleanPrev = previousUrl.substringBefore("|").trim()
            if (cleanPrev.isNotBlank()) {
                clearStreamRedirectCache(cleanPrev)
                clearHostSessionCookies(cleanPrev)
                val prevNoQuery = cleanPrev.substringBefore("?")
                // Cancel ONLY HTTP calls belonging to the previous stream URL — NEVER kill the newly opened channel's HTTP call!
                for (call in streamHttpClient.dispatcher.queuedCalls()) {
                    val u = call.request().url.toString()
                    if (u == cleanPrev || u.substringBefore("?") == prevNoQuery) {
                        runCatching { call.cancel() }
                    }
                }
                for (call in streamHttpClient.dispatcher.runningCalls()) {
                    val u = call.request().url.toString()
                    if (u == cleanPrev || u.substringBefore("?") == prevNoQuery) {
                        runCatching { call.cancel() }
                    }
                }
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
                if (diffMs <= 0L) 0 else kotlin.math.ceil(diffMs.toDouble() / (24.1 * 3600.0 * 1000.0)).toInt().coerceAtLeast(0)
            } else -1
        }.getOrDefault(-1)
    }

    // Smart Year-Round API Load Governor:
    // - Caches quick subscription & remote playlist metadata for 45 minutes during automatic background resume checks
    //   so reopening/minimizing the app does NOT hammer api.php or customer_portal.php!
    // - Manual clicks on "تحديث الصلاحية" pass forceNetwork = true for instant real-time verification.
    private const val API_QUICK_CHECK_COOLDOWN_MS = 45 * 60 * 1000L
    @Volatile private var lastQuickCheckTimeMs: Long = 0L
    const val CURRENT_APP_VERSION_CODE: Int = 26
    const val MAX_API_OFFLINE_GRACE_MS: Long = 7L * 24L * 3600L * 1000L // 7 أيام (أسبوع واحد كحد أقصى لانقطاع الاتصال مع الـ API)
    @Volatile var latestKnownServers: List<Pair<String, String>> = emptyList()
    @Volatile var latestDomainMigrations: List<Triple<String, String, String>> = emptyList()
    @Volatile var latestAppUpdateInfo: RemoteAppUpdateInfo? = null
    @Volatile private var lastQuickCheckDeviceId: String = ""
    @Volatile var lastApiContactTimestampMs: Long = 0L

    fun recordSuccessfulApiContact(context: android.content.Context) {
        val now = System.currentTimeMillis()
        lastApiContactTimestampMs = now
        runCatching {
            context.getSharedPreferences("livepro_api_guard_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putLong("last_api_contact_ms", now)
                .apply()
        }
    }

    fun isApiOfflineOverOneWeek(context: android.content.Context): Boolean {
        return runCatching {
            val sp = context.getSharedPreferences("livepro_api_guard_prefs", android.content.Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            var lastContact = sp.getLong("last_api_contact_ms", 0L)
            if (lastContact <= 0L) {
                lastContact = now
                sp.edit().putLong("last_api_contact_ms", now).apply()
                return@runCatching false
            }
            if (lastApiContactTimestampMs > lastContact) {
                lastContact = lastApiContactTimestampMs
            }
            (now - lastContact) > MAX_API_OFFLINE_GRACE_MS
        }.getOrDefault(false)
    }

    private val apkDownloadHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .applyUniversalSslCompatibility()
            .dns(CloudflareDohManager.customDns)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun shouldPromptForAppUpdate(context: android.content.Context, upd: RemoteAppUpdateInfo?): Boolean {
        if (upd == null || !upd.enabled || upd.apkUrl.isBlank()) return false
        val sp = context.getSharedPreferences("livepro_ota_update_prefs", android.content.Context.MODE_PRIVATE)
        val sig = "${upd.versionCode}|${upd.versionName.trim()}|${upd.apkUrl.trim()}|${upd.updatedAt.trim()}"
        val lastInstalledSig = sp.getString("last_installed_update_sig", "") ?: ""
        // بمجرد أن يحفظ الأدمن التحديث ويفعّله في لوحة التحكم (أو يكون رقم الإصدار أكبر)، تنبثق نافذة التحديث فوراً!
        return sig != lastInstalledSig || upd.versionCode > CURRENT_APP_VERSION_CODE
    }

    fun markAppUpdateInstallPrompted(context: android.content.Context, upd: RemoteAppUpdateInfo) {
        runCatching {
            val sig = "${upd.versionCode}|${upd.versionName.trim()}|${upd.apkUrl.trim()}|${upd.updatedAt.trim()}"
            context.getSharedPreferences("livepro_ota_update_prefs", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("last_installed_update_sig", sig)
                .apply()
        }
    }

    /**
     * فحص فوري ومباشر لإشعار التحديث عند إقلاع التطبيق أو عند النقر على زر (البحث عن تحديثات) في الإعدادات:
     * يفحص admin.php مباشرةً + api.php + customer_portal.php + app_update_config.json بدون أي كاش!
     */
    suspend fun fetchRemoteAppUpdateDirect(deviceId: String): RemoteAppUpdateInfo? = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        val directJsonUrl = listOf("https://", "tvflux4k", ".com", "/p2p/", "app_update_config.json?_t=$ts").joinToString("")
        val urls = listOf(
            "$ADMIN_UPDATE_ENDPOINT?admin_direct_action=admin_get_app_update&_t=$ts",
            "$INTERNAL_ENDPOINT?action=get_app_update&device_id=${enc(deviceId)}&_t=$ts",
            "$CUSTOMER_PORTAL_ENDPOINT?action=get_app_update&device_id=${enc(deviceId)}&_t=$ts",
            directJsonUrl,
            "$INTERNAL_ENDPOINT?action=get_xtream_lists&device_id=${enc(deviceId)}&_t=$ts"
        )
        for (u in urls) {
            val body = executeFastGet(u)
            if (!body.startsWith("{")) continue
            val parsed = runCatching {
                val root = JSONObject(body)
                val dataObj = root.optJSONObject("data")
                val subObj = root.optJSONObject("subscription") ?: dataObj?.optJSONObject("subscription")
                val updObj = root.optJSONObject("app_update")
                    ?: dataObj?.optJSONObject("app_update")
                    ?: subObj?.optJSONObject("app_update")
                    ?: (if (root.has("apk_url") || root.has("version_code")) root else null)
                if (updObj != null) {
                    val uApkUrl = updObj.optString("apk_url", "").trim()
                    val uEnabled = (updObj.optBoolean("enabled", false) || updObj.optInt("enabled", 0) == 1 || updObj.optInt("update_enabled", 0) == 1) && uApkUrl.isNotBlank()
                    val uVerCode = updObj.optInt("version_code", CURRENT_APP_VERSION_CODE)
                    val uVerName = updObj.optString("version_name", "$uVerCode.0").trim()
                    val uTitle = updObj.optString("title", updObj.optString("update_title", "📲 تحديث جديد متوفر للتطبيق")).trim()
                    val uNotes = updObj.optString("notes", updObj.optString("update_notes", "")).trim()
                    val uMandatory = updObj.optBoolean("is_mandatory", updObj.optInt("is_mandatory", 1) == 1)
                    val uUpdatedAt = updObj.optString("updated_at", "").trim()
                    RemoteAppUpdateInfo(
                        enabled = uEnabled,
                        versionCode = uVerCode,
                        versionName = uVerName,
                        apkUrl = uApkUrl,
                        title = uTitle.ifBlank { "📲 تحديث جديد متوفر للتطبيق" },
                        notes = uNotes,
                        isMandatory = uMandatory,
                        updatedAt = uUpdatedAt
                    )
                } else null
            }.getOrNull()
            if (parsed != null && parsed.enabled && parsed.apkUrl.isNotBlank()) {
                latestAppUpdateInfo = parsed
                return@withContext parsed
            }
        }
        latestAppUpdateInfo
    }

    suspend fun downloadApkUpdateToFile(
        apkUrl: String,
        destFile: java.io.File,
        onProgress: (Int, Long, Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            destFile.parentFile?.mkdirs()
            var cleanUrl = apkUrl.trim()
            // دعم روابط Google Drive المباشرة في حال وضع الأدمن رابط مشاركة Drive
            if (cleanUrl.contains("drive.google.com/file/d/")) {
                val fileId = cleanUrl.substringAfter("drive.google.com/file/d/").substringBefore("/").substringBefore("?").trim()
                if (fileId.isNotBlank()) {
                    cleanUrl = "https://drive.google.com/uc?export=download&confirm=t&id=$fileId"
                }
            }
            val req = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; LIVE-PRO/26.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36")
                .header("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                .header("Cache-Control", "no-cache")
                .build()
            apkDownloadHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body ?: error("Empty APK body")
                val totalBytes = body.contentLength()
                val tmpFile = java.io.File(destFile.parentFile, destFile.name + ".part")
                if (tmpFile.exists()) tmpFile.delete()
                body.byteStream().use { input ->
                    java.io.FileOutputStream(tmpFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReportedPct = -1
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            val pct = if (totalBytes > 0L) ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100) else -1
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct, downloaded, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }
                if (destFile.exists()) destFile.delete()
                val renamed = tmpFile.renameTo(destFile)
                if (!renamed) {
                    tmpFile.copyTo(destFile, overwrite = true)
                    tmpFile.delete()
                }
                destFile.setReadable(true, false)
                onProgress(100, destFile.length(), destFile.length())
                destFile.exists() && destFile.length() > 30_000L
            }
        }.getOrDefault(false)
    }
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

        val portalMainEnabledFlag = subObj.optBoolean("portal_main_enabled", subObj.optInt("portal_main_enabled", 0) == 1)
        subObj.optJSONObject("app_update")?.let { uo ->
            val uUrl = uo.optString("apk_url", "").trim()
            val uEn = (uo.optBoolean("enabled", false) || uo.optInt("enabled", 0) == 1 || uo.optInt("update_enabled", 0) == 1) && uUrl.isNotBlank()
            if (uEn) {
                val uVc = uo.optInt("version_code", CURRENT_APP_VERSION_CODE)
                latestAppUpdateInfo = RemoteAppUpdateInfo(
                    enabled = true,
                    versionCode = uVc,
                    versionName = uo.optString("version_name", "$uVc.0").trim(),
                    apkUrl = uUrl,
                    title = uo.optString("title", "📲 تحديث جديد متوفر للتطبيق").trim(),
                    notes = uo.optString("notes", "").trim(),
                    isMandatory = uo.optBoolean("is_mandatory", true),
                    updatedAt = uo.optString("updated_at", "").trim()
                )
            }
        }
        val sub = SubscriptionInfo(
            active = activeFlag,
            isExpired = explicitlyExpired,
            isTrial = subObj.optBoolean("is_trial", reasonStr == "trial"),
            reason = if (explicitlyExpired) "expired" else reasonStr.ifBlank { "ok" },
            expireDate = expDateStr,
            daysLeft = finalDays,
            deviceName = subObj.optString("device_name", deviceName),
            message = msgStr,
            portalMainEnabled = portalMainEnabledFlag
        )

        val combinedRemoteLists = mutableListOf<XtreamAccountConfig>()
        val discoveredServers = mutableListOf<Pair<String, String>>()
        val discoveredMigrations = mutableListOf<Triple<String, String, String>>()
        if (activeFlag) {
            for (bodyStr in listOf(apiListsBody, portalBody, checkSubBody)) {
                if (!bodyStr.startsWith("{")) continue
                runCatching {
                    val j = JSONObject(bodyStr)
                    val dataObj = j.optJSONObject("data")
                    val arr = j.optJSONArray("xtream_lists") ?: dataObj?.optJSONArray("xtream_lists")
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
                    val srvArr = j.optJSONArray("known_servers") ?: dataObj?.optJSONArray("known_servers")
                        ?: j.optJSONArray("servers") ?: dataObj?.optJSONArray("servers")
                    if (srvArr != null) {
                        for (i in 0 until srvArr.length()) {
                            val so = srvArr.optJSONObject(i) ?: continue
                            val sName = so.optString("name", "").trim()
                            val sUrl = so.optString("url", "").trim().removeSuffix("/")
                            if (sName.isNotBlank() && sUrl.isNotBlank()) {
                                discoveredServers.add(sName to sUrl)
                            }
                        }
                    }
                    val migArr = j.optJSONArray("domain_migrations") ?: dataObj?.optJSONArray("domain_migrations")
                    if (migArr != null) {
                        for (i in 0 until migArr.length()) {
                            val mo = migArr.optJSONObject(i) ?: continue
                            val oldD = mo.optString("old_domain", "").trim()
                            val newD = mo.optString("new_domain", "").trim()
                            val sName = mo.optString("server_name", "").trim()
                            if (oldD.isNotBlank() && newD.isNotBlank()) {
                                discoveredMigrations.add(Triple(oldD, newD, sName))
                            }
                        }
                    }
                    val updObj = j.optJSONObject("app_update") ?: dataObj?.optJSONObject("app_update")
                    if (updObj != null) {
                        val uEnabled = updObj.optBoolean("enabled", false)
                        val uVerCode = updObj.optInt("version_code", CURRENT_APP_VERSION_CODE)
                        val uVerName = updObj.optString("version_name", "$uVerCode.0").trim()
                        val uApkUrl = updObj.optString("apk_url", "").trim()
                        val uTitle = updObj.optString("title", "📲 تحديث جديد متوفر للتطبيق").trim()
                        val uNotes = updObj.optString("notes", "").trim()
                        val uMandatory = updObj.optBoolean("is_mandatory", true)
                        val uUpdatedAt = updObj.optString("updated_at", "").trim()
                        latestAppUpdateInfo = RemoteAppUpdateInfo(
                            enabled = uEnabled && uApkUrl.isNotBlank(),
                            versionCode = uVerCode,
                            versionName = uVerName,
                            apkUrl = uApkUrl,
                            title = uTitle.ifBlank { "📲 تحديث جديد متوفر للتطبيق" },
                            notes = uNotes,
                            isMandatory = uMandatory,
                            updatedAt = uUpdatedAt
                        )
                    }
                    lastApiContactTimestampMs = System.currentTimeMillis()
                }
            }
            if (discoveredServers.isNotEmpty()) {
                latestKnownServers = discoveredServers
                KnownIptvServersCatalog.syncAdminDataTxtServers(discoveredServers)
            }
            if (discoveredMigrations.isNotEmpty()) {
                latestDomainMigrations = discoveredMigrations
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
                        val err = json.optString("error", "انتهت صلاحية المشغل. يرجى تجديد الاشتراك عبر قسيمة تفعيل صالحة.")
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

    /** Server favorites backup bound to Screen Code (device_id). Atomic dual-endpoint replace & item deletion. */
    @Volatile private var lastFavoritesPushFingerprint: String = ""
    private val favoritesSyncMutex = kotlinx.coroutines.sync.Mutex()
    private val deletedFavoriteKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun buildAllDeletionKeysForChannel(ch: TvChannel): Set<String> {
        val keys = LinkedHashSet<String>()
        keys.add(ch.favoriteIdentityKey)
        keys.add(ch.stableKey)
        if (ch.url.isNotBlank()) {
            keys.add(ch.url.trim().lowercase(java.util.Locale.ROOT))
        }
        if (ch.contentType == StreamContentType.SERIES && ch.seriesId > 0) {
            keys.add("S|${ch.playlistId}|${ch.seriesId}")
            keys.add("FAV_SERIES_${ch.playlistId}_${ch.seriesId}")
        }
        if (ch.streamId > 0) {
            keys.add("STM|${ch.contentType.name}|${ch.playlistId}|${ch.streamId}")
            keys.add("FAV_${ch.contentType.name}_${ch.playlistId}_${ch.streamId}")
        }
        return keys
    }

    fun markFavoriteDeleted(vararg channels: TvChannel) {
        for (ch in channels) {
            deletedFavoriteKeys.addAll(buildAllDeletionKeysForChannel(ch))
        }
        lastFavoritesPushFingerprint = ""
    }

    fun unmarkFavoriteDeleted(vararg channels: TvChannel) {
        for (ch in channels) {
            deletedFavoriteKeys.removeAll(buildAllDeletionKeysForChannel(ch))
        }
        lastFavoritesPushFingerprint = ""
    }

    fun clearAllFavoritesDeletedMarkers() {
        deletedFavoriteKeys.clear()
        lastFavoritesPushFingerprint = ""
    }

    private fun favoritesFingerprint(list: List<TvChannel>): String {
        return list.joinToString("|") { ch ->
            "${ch.favoriteIdentityKey}\u0001${ch.stableKey}\u0001${ch.name}\u0001${ch.url}\u0001${ch.contentType.name}\u0001${ch.playlistId}"
        }.hashCode().toString() + ":" + list.size
    }

    private fun serializeFavoritesForServer(list: List<TvChannel>): String {
        val arr = JSONArray()
        val seenKeys = HashSet<String>()
        list.take(5000).forEach { ch ->
            val idKey = ch.favoriteIdentityKey
            if (!seenKeys.add(idKey)) return@forEach
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
                put("favoriteIdentityKey", idKey)
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
            if (seen.add(ch.favoriteIdentityKey)) out.add(ch)
        }
        return out
    }

    suspend fun deleteSingleFavoriteFromServer(
        deviceId: String,
        removedChannel: TvChannel,
        remainingFavorites: List<TvChannel>
    ): Boolean = withContext(Dispatchers.IO) {
        markFavoriteDeleted(removedChannel)
        val id = deviceId.trim().uppercase()
        if (id.isBlank() || !id.startsWith("DEV-")) return@withContext false
        val delKeysArr = JSONArray()
        for (k in buildAllDeletionKeysForChannel(removedChannel)) {
            delKeysArr.put(k)
        }
        val delForm = FormBody.Builder()
            .add("action", "delete_favorite_item")
            .add("device_id", id)
            .add("favorite_key", removedChannel.favoriteIdentityKey)
            .add("item_url", removedChannel.url.trim())
            .add("deleted_keys_json", delKeysArr.toString())
            .build()
        for (endpoint in listOf(CUSTOMER_PORTAL_ENDPOINT, INTERNAL_ENDPOINT)) {
            runCatching {
                val req = Request.Builder()
                    .url(endpoint)
                    .post(delForm)
                    .header("User-Agent", "LIVE-PRO/23.2 (Android; FavoritesDelete)")
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache, no-store, max-age=0")
                    .build()
                httpClient.newCall(req).execute().close()
            }
        }
        return@withContext pushFavoritesToServer(id, remainingFavorites, force = true)
    }

    suspend fun pushFavoritesToServer(
        deviceId: String,
        favorites: List<TvChannel>,
        force: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val id = deviceId.trim().uppercase()
        if (id.isBlank() || !id.startsWith("DEV-")) return@withContext false
        favoritesSyncMutex.lock()
        try {
            val cleanList = favorites.filterNot { ch ->
                val chKeys = buildAllDeletionKeysForChannel(ch)
                chKeys.any { it in deletedFavoriteKeys }
            }
            val fp = favoritesFingerprint(cleanList)
            if (!force && fp == lastFavoritesPushFingerprint) return@withContext true
            val payload = serializeFavoritesForServer(cleanList)
            val deletedJsonArr = JSONArray()
            for (dk in deletedFavoriteKeys) {
                deletedJsonArr.put(dk)
            }
            val form = FormBody.Builder()
                .add("action", "save_favorites")
                .add("device_id", id)
                .add("favorites_json", payload)
                .add("deleted_keys_json", deletedJsonArr.toString())
                .add("replace_all", "1")
                .add("count", cleanList.size.toString())
                .build()
            var anySuccess = false
            // Push to BOTH endpoints (CUSTOMER_PORTAL_ENDPOINT and INTERNAL_ENDPOINT) without breaking early
            // so both customer_portal.php and api.php databases are 100% updated and old deleted items never return!
            for (endpoint in listOf(CUSTOMER_PORTAL_ENDPOINT, INTERNAL_ENDPOINT)) {
                val success = runCatching {
                    val req = Request.Builder()
                        .url(endpoint)
                        .post(form)
                        .header("User-Agent", "LIVE-PRO/23.2 (Android; FavoritesBackup)")
                        .header("Accept", "application/json")
                        .header("Cache-Control", "no-cache, no-store, max-age=0")
                        .build()
                    var callOk = false
                    for (client in listOf(httpClient, bootstrapHttpClient)) {
                        val attemptOk = runCatching {
                            client.newCall(req).execute().use { resp ->
                                val body = resp.body?.string()?.trim().orEmpty()
                                if (!body.startsWith("{")) return@use false
                                val json = JSONObject(body)
                                val dataObj = json.optJSONObject("data")
                                json.optBoolean("success", false) ||
                                    json.optBoolean("ok", false) ||
                                    (dataObj?.optBoolean("ok", false) == true) ||
                                    (dataObj?.optBoolean("replaced", false) == true)
                            }
                        }.getOrDefault(false)
                        if (attemptOk) {
                            callOk = true
                            break
                        }
                    }
                    callOk
                }.getOrDefault(false)
                if (success) {
                    anySuccess = true
                }
            }
            if (anySuccess) {
                lastFavoritesPushFingerprint = fp
            }
            return@withContext anySuccess
        } finally {
            favoritesSyncMutex.unlock()
        }
    }

    /** null = network/no row; list (maybe empty) = explicit server backup */
    suspend fun pullFavoritesFromServer(deviceId: String): List<TvChannel>? = withContext(Dispatchers.IO) {
        val id = deviceId.trim().uppercase()
        if (id.isBlank() || !id.startsWith("DEV-")) return@withContext null
        favoritesSyncMutex.lock()
        try {
            val ts = System.currentTimeMillis()
            val urls = listOf(
                "$CUSTOMER_PORTAL_ENDPOINT?action=get_favorites&device_id=${enc(id)}&_t=$ts",
                "$INTERNAL_ENDPOINT?action=get_favorites&device_id=${enc(id)}&_t=$ts"
            )
            for (url in urls) {
                var body = ""
                for (client in listOf(httpClient, bootstrapHttpClient)) {
                    body = runCatching {
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", "LIVE-PRO/23.2 (Android; FavoritesRestore)")
                            .header("Accept", "application/json")
                            .header("Cache-Control", "no-cache, no-store, max-age=0")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            resp.body?.string()?.trim().orEmpty()
                        }
                    }.getOrDefault("")
                    if (body.startsWith("{")) break
                }
                if (!body.startsWith("{")) continue
                val root = runCatching { JSONObject(body) }.getOrNull() ?: continue
                if (!root.optBoolean("success", true) && !root.optBoolean("ok", true)) continue
                val data = root.optJSONObject("data")
                val found = when {
                    root.has("found") -> root.optBoolean("found")
                    data != null && data.has("found") -> data.optBoolean("found")
                    root.has("favorites") || (data != null && data.has("favorites")) -> true
                    else -> false
                }
                if (!found) {
                    continue
                }
                val arr = root.optJSONArray("favorites")
                    ?: data?.optJSONArray("favorites")
                val parsedList = when {
                    arr != null -> parseFavoritesFromServer(arr.toString())
                    else -> parseFavoritesFromServer(root.optString("favorites_json", data?.optString("favorites_json", "") ?: ""))
                }
                val list = parsedList.filterNot { ch ->
                    val chKeys = buildAllDeletionKeysForChannel(ch)
                    chKeys.any { it in deletedFavoriteKeys }
                }
                lastFavoritesPushFingerprint = favoritesFingerprint(list)
                return@withContext list
            }
            return@withContext null
        } finally {
            favoritesSyncMutex.unlock()
        }
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
        val deviceMatches = XtreamRepository.probeServersForAccountOnDevice(
            username = cleanUser,
            password = cleanPass,
            candidates = allCandidates,
            minSuccessHosts = 1
        )
        if (deviceMatches.isNotEmpty()) {
            // Register all successful hosts for instant future failover (min 3 when available)
            KnownIptvServersCatalog.registerRemoteServers(deviceMatches.map { it.second to it.first })
            // Primary = first success (fastest batch completion order)
            return@withContext deviceMatches.first()
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

    fun extractCleanVoucherCode(rawCode: String): String {
        if (rawCode.isEmpty()) return ""
        val stripped = buildString(rawCode.length) {
            for (ch in rawCode) {
                if (ch in '\u200B'..'\u200F' || ch in '\u202A'..'\u202E' || ch in '\u2060'..'\u2069' || ch == '\uFEFF') continue
                when (ch) {
                    '٠', '۰' -> append('0'); '١', '۱' -> append('1'); '٢', '۲' -> append('2'); '٣', '۳' -> append('3'); '٤', '۴' -> append('4')
                    '٥', '۵' -> append('5'); '٦', '۶' -> append('6'); '٧', '۷' -> append('7'); '٨', '۸' -> append('8'); '٩', '۹' -> append('9')
                    else -> append(ch)
                }
            }
        }.uppercase()
        val noDev = stripped
            .replace(Regex("DEV-[A-Z0-9]{4}-[A-Z0-9]{4}"), " ")
            .replace(Regex("\\bDEV-?"), " ")
        val dashMatch = Regex("(?<![A-Z0-9])([A-Z0-9]{4})[-\\s_\\u2010-\\u2015\\u2212\\uFE63\\uFF0D]+([A-Z0-9]{4})(?![A-Z0-9])").find(noDev)
        if (dashMatch != null) {
            return "${dashMatch.groupValues[1]}-${dashMatch.groupValues[2]}"
        }
        val block8 = Regex("(?<![A-Z0-9])([A-Z0-9]{8})(?![A-Z0-9])").find(noDev)
        if (block8 != null) {
            val t = block8.groupValues[1]
            return "${t.substring(0, 4)}-${t.substring(4, 8)}"
        }
        val alnumOnly = noDev.filter { it in 'A'..'Z' || it in '0'..'9' }.take(8)
        return if (alnumOnly.length == 8) {
            "${alnumOnly.substring(0, 4)}-${alnumOnly.substring(4, 8)}"
        } else {
            alnumOnly
        }
    }

    suspend fun redeemActivationCode(
        deviceId: String,
        deviceName: String,
        rawCode: String
    ): ApiSyncResult = withContext(Dispatchers.IO) {
        val cleanCode = extractCleanVoucherCode(rawCode)
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

        var respCode = 0
        var respBody = ""
        for (endpoint in listOf(INTERNAL_ENDPOINT, CUSTOMER_PORTAL_ENDPOINT)) {
            val req = Request.Builder()
                .url(endpoint)
                .post(form)
                .header("User-Agent", "LIVE-PRO/13.2 (Android; Universal)")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache, no-store, max-age=0")
                .build()
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
            if (respBody.startsWith("{")) break
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
                "CARD_USED" -> "⛔ رمز التفعيل ($cleanCode) مستخدم ومحروق مسبقاً!\nلا يمكن إعادة استخدام نفس القسيمة مرة أخرى نهائياً. يرجى إدخال رمز قسيمة تفعيل جديد صالح."
                "CARD_EXPIRED" -> "⛔ انتهت صلاحية بطاقة التفعيل ($cleanCode).\nيرجى إدخال قسيمة تفعيل جديدة صالحة."
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
                message = msgStr,
                portalMainEnabled = subObj.optBoolean("portal_main_enabled", subObj.optInt("portal_main_enabled", 0) == 1)
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
                else -> "انتهت صلاحية المشغل للشاشة ($deviceId). يرجى تجديد الاشتراك عبر قسيمة تفعيل صالحة."
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
