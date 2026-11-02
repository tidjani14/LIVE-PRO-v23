package com.tvflux.pro

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.StringReader
import java.util.zip.GZIPInputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Customer Bouquet & Channel Ordering Helper (D-Pad friendly up/down/top/hide).
 * Preserves 100% exact native server order when customOrder is empty.
 */
object CustomOrderHelper {
    const val HIDE_FAV_BOUQUET_KEY = "__HIDE_FAV_BOUQUET__"
    const val HIDE_MQ_BOUQUET_KEY = "__HIDE_MQ_BOUQUET__"

    fun isFavoritesBouquetHidden(hiddenBouquets: Set<String>): Boolean {
        return HIDE_FAV_BOUQUET_KEY in hiddenBouquets ||
            "★ المفضلة" in hiddenBouquets ||
            "★ Favoris" in hiddenBouquets ||
            "★ Favorites" in hiddenBouquets
    }

    fun isMultiQualityBouquetHidden(hiddenBouquets: Set<String>): Boolean {
        return MultiQualityEngine.BOUQUET_NAME in hiddenBouquets ||
            HIDE_MQ_BOUQUET_KEY in hiddenBouquets
    }

    fun toggleFavoritesBouquetHidden(hiddenBouquets: Set<String>, favLabel: String = "★ المفضلة"): Set<String> {
        return if (isFavoritesBouquetHidden(hiddenBouquets)) {
            hiddenBouquets - setOf(HIDE_FAV_BOUQUET_KEY, favLabel, "★ المفضلة", "★ Favoris", "★ Favorites")
        } else {
            hiddenBouquets + setOf(HIDE_FAV_BOUQUET_KEY, favLabel, "★ المفضلة")
        }
    }

    fun toggleMultiQualityBouquetHidden(hiddenBouquets: Set<String>): Set<String> {
        return if (isMultiQualityBouquetHidden(hiddenBouquets)) {
            hiddenBouquets - setOf(MultiQualityEngine.BOUQUET_NAME, HIDE_MQ_BOUQUET_KEY)
        } else {
            hiddenBouquets + setOf(MultiQualityEngine.BOUQUET_NAME, HIDE_MQ_BOUQUET_KEY)
        }
    }

    fun sortBouquets(
        rawBouquets: List<BouquetSummary>,
        customOrder: List<String>,
        hiddenBouquets: Set<String>,
        includeHidden: Boolean = false
    ): List<BouquetSummary> {
        val safeBouquets = rawBouquets
        val favHidden = isFavoritesBouquetHidden(hiddenBouquets)
        val mqHidden = isMultiQualityBouquetHidden(hiddenBouquets)
        val visible = if (includeHidden) {
            safeBouquets
        } else {
            safeBouquets.filter { bq ->
                when {
                    bq.name == "الكل" || bq.name == "Tout" || bq.name == "All" -> true
                    bq.isFavoritesSpecial -> !favHidden && bq.name !in hiddenBouquets
                    bq.name == MultiQualityEngine.BOUQUET_NAME -> !mqHidden && bq.name !in hiddenBouquets
                    else -> bq.name !in hiddenBouquets
                }
            }
        }
        if (customOrder.isEmpty()) return visible
        val orderMap = customOrder.withIndex().associate { it.value to it.index }
        return visible.sortedWith(
            compareBy<BouquetSummary> { bq ->
                when {
                    bq.name == "الكل" || bq.name == "Tout" || bq.name == "All" -> -2
                    else -> orderMap[bq.name] ?: (if (bq.isFavoritesSpecial) -1 else (10_000 + rawBouquets.indexOf(bq)))
                }
            }
        )
    }

    fun sortChannels(
        rawChannels: List<TvChannel>,
        customChannelOrder: List<String>,
        hiddenBouquets: Set<String>
    ): List<TvChannel> {
        if (customChannelOrder.isEmpty() && hiddenBouquets.isEmpty()) return rawChannels
        val filtered = if (hiddenBouquets.isEmpty()) {
            rawChannels
        } else {
            rawChannels.filter { it.group !in hiddenBouquets }
        }
        if (customChannelOrder.isEmpty()) return filtered
        val orderMap = customChannelOrder.withIndex().associate { it.value to it.index }
        return filtered.sortedWith(
            compareBy<TvChannel> { ch ->
                orderMap[ch.stableKey] ?: (100_000 + ch.number)
            }
        )
    }

    fun <T> moveItem(list: List<T>, fromIndex: Int, toIndex: Int): List<T> {
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return list
        val mutable = list.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return mutable
    }
}

/**
 * Universal High-Capacity Streaming Xtream Codes, Panel API & M3U Engine (v12.0):
 * - Preserves exact native Xtream server category and stream order via LinkedHashMap.
 * - Uses streaming JsonReader & BufferedReader directly over GZIP network sockets
 *   so servers with 150,000+ channels/movies/series load quickly with zero OOM.
 * - Supports player_api.php, panel_api.php, get.php, M3U/M3U8/MPD/TS/VOD, #EXTVLCOPT & #KODIPROP DRM.
 */
object XtreamRepository {

    private val IPTV_USER_AGENTS = IboPlayerProfileSpoofer.IBO_USER_AGENTS

    /**
     * Auto-detects GZIP magic bytes (0x1f 0x8b) on any stream so whether the Xtream panel honors
     * Accept-Encoding: identity or forces GZIP compression, reading never fails on Android TV.
     */
    private fun wrapAutoDecompressStream(raw: InputStream): InputStream {
        val pb = PushbackInputStream(raw, 2)
        val b1 = pb.read()
        if (b1 == -1) return pb
        val b2 = pb.read()
        if (b2 != -1) pb.unread(b2)
        pb.unread(b1)
        return if (b1 == 0x1f && b2 == 0x8b) {
            GZIPInputStream(pb, 65536)
        } else {
            pb
        }
    }

    fun normalizeServerUrl(raw: String): String {
        var clean = raw.trim().removeSuffix("/")
        if (clean.isBlank()) return ""
        val catalogMatch = KnownIptvServersCatalog.resolveHostByServerName(clean)
        if (catalogMatch.isNotBlank()) {
            clean = catalogMatch.trim().removeSuffix("/")
        }
        if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            clean = "http://$clean"
        }
        for (suffix in listOf("/player_api.php", "/panel_api.php", "/get.php", "/c/", "/c", "/stalker_portal/c")) {
            if (clean.contains(suffix, ignoreCase = true)) {
                clean = clean.substringBefore(suffix)
            }
        }
        return clean.removeSuffix("/")
    }

    private fun swapScheme(url: String): String {
        return when {
            url.startsWith("https://", ignoreCase = true) -> "http://" + url.substring(8)
            url.startsWith("http://", ignoreCase = true) -> "https://" + url.substring(7)
            else -> url
        }
    }

    private const val XTREAM_EXPIRY_PROBE_TTL_MS = 30 * 60 * 1000L
    private data class CachedXtreamProbe(val expDate: String, val isActive: Boolean, val timestampMs: Long)
    private val probeCache = java.util.concurrent.ConcurrentHashMap<String, CachedXtreamProbe>()

    /**
     * Lightweight real-time Xtream account validity & expiry date check (< 1 KB).
     * Uses a 30-minute TTL cache for automatic resume checks (unless forceNetwork = true)
     * so neither the app nor the Xtream server is overloaded with redundant requests.
     */
    suspend fun probeAccountExpiryQuick(
        config: XtreamAccountConfig,
        forceNetwork: Boolean = false
    ): Pair<String, Boolean> =
        withContext(Dispatchers.IO) {
            val autoSplit = SmartM3uParser.tryExtractXtreamCredentials(config.serverUrl)
                ?: SmartM3uParser.tryExtractXtreamCredentials(config.m3uUrl)
            val baseUrl = normalizeServerUrl(autoSplit?.serverUrl ?: config.serverUrl)
            val user = (autoSplit?.username ?: config.username).trim()
            val pass = (autoSplit?.password ?: config.password).trim()
            if (baseUrl.isBlank() || user.isBlank() || pass.isBlank()) {
                return@withContext Pair(config.expDate, true)
            }
            val cacheKey = "$baseUrl|$user|$pass"
            val now = System.currentTimeMillis()
            val cached = probeCache[cacheKey]
            if (!forceNetwork && cached != null && (now - cached.timestampMs) < XTREAM_EXPIRY_PROBE_TTL_MS) {
                return@withContext Pair(cached.expDate, cached.isActive)
            }

            val uEnc = URLEncoder.encode(user, "UTF-8")
            val pEnc = URLEncoder.encode(pass, "UTF-8")
            val apiBase = "$baseUrl/player_api.php?username=$uEnc&password=$pEnc"
            val raw = fetchUrlTextFast(apiBase).ifBlank {
                fetchUrlTextFast("${swapScheme(baseUrl)}/player_api.php?username=$uEnc&password=$pEnc")
            }
            if (raw.startsWith("{")) {
                runCatching {
                    val root = JSONObject(raw)
                    val userInfo = root.optJSONObject("user_info")
                    if (userInfo != null) {
                        val auth = userInfo.optInt("auth", 1)
                        val status = userInfo.optString("status", "Active")
                        val isActive = auth != 0 && !status.equals("Expired", ignoreCase = true) && !status.equals("Banned", ignoreCase = true)
                        val expSec = userInfo.optString("exp_date", "").toLongOrNull()
                        val expDateStr = if (expSec != null && expSec > 0L) {
                            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(expSec * 1000L))
                        } else {
                            config.expDate
                        }
                        probeCache[cacheKey] = CachedXtreamProbe(expDateStr, isActive, now)
                        return@withContext Pair(expDateStr, isActive)
                    }
                }
            }
            Pair(config.expDate, true)
        }

    /**
     * Probes candidate IPTV servers in fast parallel batches directly from the Phone/TV device
     * to identify the matching server for (username, password).
     */
    suspend fun probeServersForAccountOnDevice(
        username: String,
        password: String,
        candidates: List<Pair<String, String>>
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        val uEnc = URLEncoder.encode(username.trim(), "UTF-8")
        val pEnc = URLEncoder.encode(password.trim(), "UTF-8")
        // FAST auto-discover: larger parallel batches + short timeout + first-hit wins
        for (batch in candidates.chunked(14)) {
            val deferred = coroutineScope {
                batch.map { (srvName, srvUrl) ->
                    async {
                        val normUrl = normalizeServerUrl(srvUrl)
                        if (normUrl.isBlank()) return@async null
                        val apiUrl = "$normUrl/player_api.php?username=$uEnc&password=$pEnc"
                        val raw = runCatching {
                            withTimeout(2_600L) {
                                fetchUrlTextFast(apiUrl)
                            }
                        }.getOrDefault("")
                        val cleanJson = extractJsonPayload(raw)
                        if (!cleanJson.startsWith("{")) return@async null
                        val valid = runCatching {
                            val root = JSONObject(cleanJson)
                            val ui = root.optJSONObject("user_info")
                                ?: root.optJSONObject("data")?.optJSONObject("user_info")
                            if (ui != null) {
                                val auth = ui.optString("auth", "0").trim()
                                val status = ui.optString("status", "Active").trim()
                                val authOk = auth == "1" || auth.equals("true", ignoreCase = true) ||
                                    (ui.has("username") && status.equals("Active", ignoreCase = true))
                                val notBlocked = !status.equals("Expired", ignoreCase = true) &&
                                    !status.equals("Banned", ignoreCase = true) &&
                                    !status.equals("Disabled", ignoreCase = true)
                                authOk && notBlocked
                            } else {
                                root.has("server_info")
                            }
                        }.getOrDefault(false)
                        if (!valid) return@async null
                        val resolvedName = srvName.ifBlank {
                            KnownIptvServersCatalog.detectServerNameByHost(normUrl).ifBlank { "XTREAM VIP" }
                        }
                        Pair(normUrl, resolvedName)
                    }
                }
            }
            // First successful match in this batch wins — cancel waiting siblings via scope end
            for (d in deferred) {
                val hit = runCatching { d.await() }.getOrNull()
                if (hit != null) return@withContext hit
            }
        }
        null
    }

    suspend fun syncXtreamAccount(
        config: XtreamAccountConfig,
        onLiveReady: (List<TvChannel>, String) -> Unit = { _, _ -> },
        onProgress: (SyncProgressState) -> Unit = {}
    ): XtreamFullSyncResult =
        withContext(Dispatchers.IO) {
            // Unlimited total download duration: NEVER cut off or stop while Live TV, Movies, or Series are downloading!
            syncXtreamAccountInternal(config, onLiveReady, onProgress)
        }

    private suspend fun syncXtreamAccountInternal(
        config: XtreamAccountConfig,
        onLiveReady: (List<TvChannel>, String) -> Unit = { _, _ -> },
        onProgress: (SyncProgressState) -> Unit = {}
    ): XtreamFullSyncResult =
        withContext(Dispatchers.IO) {
            val serverLabel = config.serverName.trim().ifBlank { "XTREAM" }
            val playlistId = config.id.ifBlank { "XTREAM_1" }

            val autoSplit = SmartM3uParser.tryExtractXtreamCredentials(config.serverUrl)
                ?: SmartM3uParser.tryExtractXtreamCredentials(config.m3uUrl)

            // Direct M3U URL mode (when no Xtream username/password can be extracted)
            if (autoSplit == null && config.m3uUrl.isNotBlank() && (config.serverUrl.isBlank() || config.username.isBlank())) {
                val parsed = fetchAndParseM3uStream(config.m3uUrl.trim(), serverLabel, playlistId, serverLabel)
                val live = parsed.filter { it.contentType == StreamContentType.LIVE_TV }
                val movies = parsed.filter { it.contentType == StreamContentType.MOVIE }
                val series = parsed.filter { it.contentType == StreamContentType.SERIES }
                if (live.isEmpty() && movies.isEmpty() && series.isEmpty()) {
                    error("تعذّر قراءة ملف M3U من الرابط المحدد")
                }
                return@withContext XtreamFullSyncResult(
                    account = config.copy(
                        enabled = true,
                        liveCount = live.size,
                        movieCount = movies.size,
                        seriesCount = series.size,
                        lastSyncTimestamp = System.currentTimeMillis(),
                        statusNote = "متصل ✓ (${live.size} قناة • ${movies.size} فيلم)"
                    ),
                    liveChannels = live,
                    movies = movies,
                    series = series
                )
            }

            var baseUrl = normalizeServerUrl(
                (autoSplit?.serverUrl ?: config.serverUrl).ifBlank {
                    KnownIptvServersCatalog.resolveHostByServerName(config.serverName)
                }
            )
            val user = (autoSplit?.username ?: config.username).trim()
            val pass = (autoSplit?.password ?: config.password).trim()

            // If user pasted a direct non-Xtream M3U URL into serverUrl without username/password
            if (baseUrl.isNotBlank() && (user.isBlank() || pass.isBlank()) &&
                (config.serverUrl.contains(".m3u", ignoreCase = true) || config.serverUrl.contains("playlist", ignoreCase = true))
            ) {
                val parsed = fetchAndParseM3uStream(config.serverUrl.trim(), serverLabel, playlistId, serverLabel)
                val live = parsed.filter { it.contentType == StreamContentType.LIVE_TV }
                val movies = parsed.filter { it.contentType == StreamContentType.MOVIE }
                val series = parsed.filter { it.contentType == StreamContentType.SERIES }
                if (live.isNotEmpty() || movies.isNotEmpty() || series.isNotEmpty()) {
                    return@withContext XtreamFullSyncResult(
                        account = config.copy(
                            enabled = true,
                            liveCount = live.size,
                            movieCount = movies.size,
                            seriesCount = series.size,
                            lastSyncTimestamp = System.currentTimeMillis(),
                            statusNote = "متصل ✓ (${live.size} قناة • ${movies.size} فيلم)"
                        ),
                        liveChannels = live,
                        movies = movies,
                        series = series
                    )
                }
            }

            if (baseUrl.isBlank() && user.isNotBlank() && pass.isNotBlank()) {
                val discovered = ChannelRepository.autoDiscoverXtreamHostViaPortal("", user, pass)
                if (discovered != null) {
                    baseUrl = normalizeServerUrl(discovered.first)
                }
            }

            require(baseUrl.isNotBlank() && user.isNotBlank() && pass.isNotBlank()) {
                "يرجى إدخال رابط السيرفر واسم المستخدم وكلمة المرور"
            }

            val uEnc = URLEncoder.encode(user, "UTF-8")
            val pEnc = URLEncoder.encode(pass, "UTF-8")

            // Probe player_api.php on primary scheme, swap http <-> https, or auto-discover alternative hosts from KnownIptvServersCatalog
            var apiBase = "$baseUrl/player_api.php?username=$uEnc&password=$pEnc"
            var authJsonStr = fetchUrlTextWithFallback(apiBase)
            if (authJsonStr.isBlank()) {
                val altBase = swapScheme(baseUrl)
                val altApiBase = "$altBase/player_api.php?username=$uEnc&password=$pEnc"
                val altAuth = fetchUrlTextWithFallback(altApiBase)
                if (altAuth.isNotBlank()) {
                    baseUrl = altBase
                    apiBase = altApiBase
                    authJsonStr = altAuth
                }
            }
            if (authJsonStr.isBlank()) {
                val fallbackHosts = KnownIptvServersCatalog.findAlternativeHostsForAccount(serverLabel, baseUrl)
                for (candidateHost in fallbackHosts) {
                    val normCand = normalizeServerUrl(candidateHost)
                    if (normCand.isBlank() || normCand.equals(baseUrl, ignoreCase = true)) continue
                    val candApi = "$normCand/player_api.php?username=$uEnc&password=$pEnc"
                    val candAuth = fetchUrlTextWithFallback(candApi)
                    if (candAuth.isNotBlank()) {
                        baseUrl = normCand
                        apiBase = candApi
                        authJsonStr = candAuth
                        break
                    }
                }
            }
            if (authJsonStr.isBlank()) {
                val discovered = ChannelRepository.autoDiscoverXtreamHostViaPortal("", user, pass)
                if (discovered != null) {
                    val normDisc = normalizeServerUrl(discovered.first)
                    if (normDisc.isNotBlank()) {
                        val discApi = "$normDisc/player_api.php?username=$uEnc&password=$pEnc"
                        val discAuth = fetchUrlTextWithFallback(discApi)
                        if (discAuth.isNotBlank()) {
                            baseUrl = normDisc
                            apiBase = discApi
                            authJsonStr = discAuth
                        }
                    }
                }
            }

            if (authJsonStr.isBlank()) {
                error("NETWORK_OR_HOST|⛔ تعذّر الوصول إلى السيرفر أو الرابط غير صالح.\nتحقق من الإنترنت ورابط الهوست ثم أعد المحاولة.")
            }
            var expLabel = ""
            val cleanAuthJson = extractJsonPayload(authJsonStr)
            if (cleanAuthJson.startsWith("{")) {
                runCatching {
                    val root = JSONObject(cleanAuthJson)
                    val userInfo = root.optJSONObject("user_info")
                        ?: root.optJSONObject("data")?.optJSONObject("user_info")
                        ?: root
                    val authVal = userInfo.optString("auth", "1").trim()
                    val status = userInfo.optString("status", "Active").trim()
                    val statusLower = status.lowercase()
                    // Wrong username/password: auth=0 without explicit expired/banned status
                    if ((authVal == "0" || authVal.equals("false", ignoreCase = true)) &&
                        statusLower !in setOf("expired", "banned", "disabled", "blocked", "suspended")
                    ) {
                        error("AUTH_INVALID|🔑 اسم المستخدم أو كلمة المرور غير صحيحة.\nتأكد من كتابة Username و Password بدقة ثم أعد المحاولة.")
                    }
                    if (statusLower in setOf("expired", "banned", "disabled", "blocked", "suspended") ||
                        (authVal == "0" && statusLower == "expired")
                    ) {
                        error("ACCOUNT_EXPIRED|⚠️ حساب XTREAM غير نشط أو منتهي الصلاحية ($status).\nيرجى تجديد الاشتراك لدى مزود الخدمة.")
                    }
                    val rawExp = userInfo.optString("exp_date", "")
                        .ifBlank { userInfo.optString("expire_date", "") }
                        .ifBlank { root.optString("exp_date", "") }
                        .trim()
                    val expSec = rawExp.toLongOrNull()
                    if (expSec != null && expSec > 0L) {
                        val ms = if (expSec > 10_000_000_000L) expSec else expSec * 1000L
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        expLabel = sdf.format(Date(ms))
                    } else if (rawExp.length >= 10 && rawExp[4] == '-') {
                        expLabel = rawExp.take(10)
                    }
                }.onFailure { e ->
                    if (e.message?.contains("حساب XTREAM") == true) throw e
                }
            }

            onProgress(
                SyncProgressState(
                    isSyncing = true,
                    sourceId = playlistId,
                    sourceName = serverLabel,
                    phaseLabel = "📡 جارٍ سحب باقات وقنوات البث المباشر من ($serverLabel)...",
                    progressPercent = 30
                )
            )
            coroutineScope {
                // STEP 1: Uninterrupted Sequential IBO Player Sync — Fetch Live TV Categories & Streams FIRST
                val liveCats = runCatching {
                    fetchCategoryMapStreaming("$apiBase&action=get_live_categories")
                }.getOrDefault(LinkedHashMap())

                val liveList = runCatching {
                    streamParseLiveChannels(
                        url = "$apiBase&action=get_live_streams",
                        liveCats = liveCats,
                        serverLabel = serverLabel,
                        playlistId = playlistId,
                        baseUrl = baseUrl,
                        uEnc = uEnc,
                        pEnc = pEnc
                    ).toMutableList()
                }.getOrDefault(mutableListOf())

                // Preserve exact server bouquet order from get_live_categories right away
                if (liveCats.isNotEmpty() && liveList.size > 1) {
                    val catRank = LinkedHashMap<String, Int>(liveCats.size * 2)
                    var r = 0
                    for (catName in liveCats.values) {
                        if (!catRank.containsKey(catName)) {
                            catRank[catName] = r++
                        }
                    }
                    val indexed = liveList.withIndex().toList()
                    liveList.clear()
                    indexed.sortedWith(
                        compareBy<IndexedValue<TvChannel>> { catRank[it.value.group] ?: (100_000 + it.index) }
                            .thenBy { it.index }
                    ).forEach { liveList.add(it.value) }
                }

                // Deliver Live TV immediately so the user can watch while VOD+Series load in parallel
                if (liveList.isNotEmpty()) {
                    runCatching { onLiveReady(liveList.toList(), expLabel) }
                }

                onProgress(
                    SyncProgressState(
                        isSyncing = true,
                        sourceId = playlistId,
                        sourceName = serverLabel,
                        phaseLabel = "🎬 ${liveList.size} قناة جاهزة ✓ • جارٍ تنزيل الأفلام والمسلسلات بالتوازي...",
                        progressPercent = 55,
                        fetchedLiveCount = liveList.size
                    )
                )

                // STEP 2+3 FAST: Movies & Series in parallel (no artificial delay) for large servers
                // Return category maps alongside lists so post-sort (vodCats/seriesCats) stays in scope.
                val vodDeferred = async {
                    val cats = runCatching {
                        fetchCategoryMapStreaming("$apiBase&action=get_vod_categories")
                    }.getOrDefault(LinkedHashMap())
                    val list = runCatching {
                        streamParseVodMovies(
                            url = "$apiBase&action=get_vod_streams",
                            vodCats = cats,
                            serverLabel = serverLabel,
                            playlistId = playlistId,
                            baseUrl = baseUrl,
                            uEnc = uEnc,
                            pEnc = pEnc
                        ).toMutableList()
                    }.getOrDefault(mutableListOf())
                    cats to list
                }
                val seriesDeferred = async {
                    val cats = runCatching {
                        fetchCategoryMapStreaming("$apiBase&action=get_series_categories")
                    }.getOrDefault(LinkedHashMap())
                    val list = runCatching {
                        streamParseSeriesItems(
                            url = "$apiBase&action=get_series",
                            seriesCats = cats,
                            serverLabel = serverLabel,
                            playlistId = playlistId,
                            apiBase = apiBase,
                            baseUrl = baseUrl,
                            uEnc = uEnc,
                            pEnc = pEnc
                        ).toMutableList()
                    }.getOrDefault(mutableListOf())
                    cats to list
                }
                val (vodCats, movieList) = vodDeferred.await()
                onProgress(
                    SyncProgressState(
                        isSyncing = true,
                        sourceId = playlistId,
                        sourceName = serverLabel,
                        phaseLabel = "📺 ${movieList.size} فيلم ✓ • جارٍ إنهاء المسلسلات...",
                        progressPercent = 78,
                        fetchedLiveCount = liveList.size,
                        fetchedMovieCount = movieList.size
                    )
                )
                val (seriesCats, seriesList) = seriesDeferred.await()

                // Stage 2 Fallback: Try panel_api.php if player_api.php returned no channels
                if (liveList.isEmpty() && movieList.isEmpty() && seriesList.isEmpty()) {
                    val panelUrl = "$baseUrl/panel_api.php?username=$uEnc&password=$pEnc"
                    val panelParsed = parsePanelApiFallback(panelUrl, serverLabel, playlistId, baseUrl, uEnc, pEnc)
                    liveList.addAll(panelParsed)
                }

                // Stage 3 Fallback: Stream M3U via get.php (ts first for IBO/VLC parity, then m3u8)
                if (liveList.isEmpty() && movieList.isEmpty() && seriesList.isEmpty()) {
                    val candidateM3uUrls = listOf(
                        "$baseUrl/get.php?username=$uEnc&password=$pEnc&type=m3u_plus&output=ts",
                        "$baseUrl/get.php?username=$uEnc&password=$pEnc&type=m3u_plus&output=m3u8",
                        "$baseUrl/get.php?username=$uEnc&password=$pEnc&type=m3u&output=ts",
                        "${swapScheme(baseUrl)}/get.php?username=$uEnc&password=$pEnc&type=m3u_plus&output=ts"
                    )
                    for (m3uUrl in candidateM3uUrls) {
                        val parsedM3u = fetchAndParseM3uStream(m3uUrl, serverLabel, playlistId, serverLabel)
                        if (parsedM3u.isNotEmpty()) {
                            liveList.addAll(parsedM3u.filter { it.contentType == StreamContentType.LIVE_TV })
                            movieList.addAll(parsedM3u.filter { it.contentType == StreamContentType.MOVIE })
                            seriesList.addAll(parsedM3u.filter { it.contentType == StreamContentType.SERIES })
                            break
                        }
                    }
                }

                if (liveList.isEmpty() && movieList.isEmpty() && seriesList.isEmpty()) {
                    error("تم الاتصال بالسيرفر ولكن لم يتم العثور على قوائم. تأكد من الرابط وبيانات الحساب.")
                }

                if (vodCats.isNotEmpty() && movieList.size > 1) {
                    val catRank = HashMap<String, Int>(vodCats.size * 2)
                    var r = 0
                    for (catName in vodCats.values) {
                        if (!catRank.containsKey(catName)) {
                            catRank[catName] = r++
                        }
                    }
                    // In-place TimSort is stable and preserves native index order with zero extra list/wrapper allocations on 200k+ movies!
                    movieList.sortBy { catRank[it.group] ?: 100_000 }
                }

                if (seriesCats.isNotEmpty() && seriesList.size > 1) {
                    val catRank = HashMap<String, Int>(seriesCats.size * 2)
                    var r = 0
                    for (catName in seriesCats.values) {
                        if (!catRank.containsKey(catName)) {
                            catRank[catName] = r++
                        }
                    }
                    seriesList.sortBy { catRank[it.group] ?: 100_000 }
                }

                onProgress(
                    SyncProgressState(
                        isSyncing = true,
                        sourceId = playlistId,
                        sourceName = serverLabel,
                        phaseLabel = "جارٍ الفهرسة التفاضلية السريعة في قاعدة بيانات SQLite...",
                        progressPercent = 85,
                        fetchedLiveCount = liveList.size,
                        fetchedMovieCount = movieList.size,
                        fetchedSeriesCount = seriesList.size
                    )
                )
                // Evict idle API keep-alive connections so max_connections=1 servers immediately allow video streaming!
                ChannelRepository.evictIdleApiConnections()
                val detectedBrand = KnownIptvServersCatalog.detectServerNameByHost(baseUrl)
                val finalServerName = if (serverLabel == "XTREAM" || serverLabel.startsWith("قائمة")) {
                    detectedBrand.ifBlank { serverLabel }
                } else serverLabel
                val updatedConfig = config.copy(
                    serverName = finalServerName,
                    serverUrl = baseUrl,
                    username = user,
                    password = pass,
                    enabled = true,
                    liveCount = liveList.size,
                    movieCount = movieList.size,
                    seriesCount = seriesList.size,
                    expDate = expLabel,
                    lastSyncTimestamp = System.currentTimeMillis(),
                    statusNote = "متصل ✓ (${liveList.size} قناة • ${movieList.size} فيلم • ${seriesList.size} مسلسل)"
                )

                XtreamFullSyncResult(
                    account = updatedConfig,
                    liveChannels = liveList,
                    movies = movieList,
                    series = seriesList
                )
            }
        }

    /**
     * Uses LinkedHashMap to preserve the exact category order sent by the Xtream server.
     */
    private val JSON_WRAPPER_KEYS = setOf(
        "data", "streams", "channels", "live", "live_streams",
        "movies", "vod", "vod_streams", "series", "categories",
        "live_categories", "vod_categories", "series_categories",
        "available_channels", "result", "results", "items", "list", "response", "output"
    )

    private fun extractJsonPayload(raw: String): String {
        val trimmed = raw.trim().removePrefix("\uFEFF")
        if (trimmed.isEmpty()) return ""
        val firstBrace = trimmed.indexOf('{')
        val firstBracket = trimmed.indexOf('[')
        val startIdx = when {
            firstBrace >= 0 && firstBracket >= 0 -> minOf(firstBrace, firstBracket)
            firstBrace >= 0 -> firstBrace
            firstBracket >= 0 -> firstBracket
            else -> return trimmed
        }
        return if (startIdx > 0) trimmed.substring(startIdx).trim() else trimmed
    }

    private fun skipToFirstJsonToken(br: BufferedReader): Boolean {
        return try {
            while (true) {
                br.mark(4)
                val ch = br.read()
                if (ch == -1) return false
                val c = ch.toChar()
                if (c == '[' || c == '{') {
                    br.reset()
                    return true
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun safeReadString(reader: JsonReader): String {
        return try {
            when (reader.peek()) {
                JsonToken.STRING -> reader.nextString().trim()
                JsonToken.NUMBER -> reader.nextString().trim()
                JsonToken.BOOLEAN -> {
                    reader.nextBoolean()
                    ""
                }
                JsonToken.NULL -> {
                    reader.nextNull()
                    ""
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    var firstVal = ""
                    while (reader.hasNext()) {
                        if (firstVal.isEmpty()) {
                            firstVal = safeReadString(reader)
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endArray()
                    firstVal
                }
                JsonToken.BEGIN_OBJECT -> {
                    reader.skipValue()
                    ""
                }
                else -> {
                    reader.skipValue()
                    ""
                }
            }
        } catch (_: Throwable) {
            runCatching { reader.skipValue() }
            ""
        }
    }

    private fun safeReadInt(reader: JsonReader, defaultVal: Int = 0): Int {
        return try {
            when (reader.peek()) {
                JsonToken.NUMBER -> {
                    runCatching { reader.nextInt() }.getOrElse {
                        runCatching { reader.nextLong().toInt() }.getOrElse {
                            runCatching { reader.nextDouble().toInt() }.getOrElse {
                                reader.nextString().filter { it.isDigit() }.take(9).toIntOrNull() ?: defaultVal
                            }
                        }
                    }
                }
                JsonToken.STRING -> {
                    val s = reader.nextString().trim()
                    s.toIntOrNull()
                        ?: s.substringBefore(".").filter { it.isDigit() }.take(9).toIntOrNull()
                        ?: defaultVal
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    var firstInt = defaultVal
                    var gotFirst = false
                    while (reader.hasNext()) {
                        if (!gotFirst) {
                            firstInt = safeReadInt(reader, defaultVal)
                            gotFirst = true
                        } else {
                            reader.skipValue()
                        }
                    }
                    reader.endArray()
                    firstInt
                }
                JsonToken.BOOLEAN -> {
                    reader.nextBoolean()
                    defaultVal
                }
                JsonToken.NULL -> {
                    reader.nextNull()
                    defaultVal
                }
                else -> {
                    reader.skipValue()
                    defaultVal
                }
            }
        } catch (_: Throwable) {
            runCatching { reader.skipValue() }
            defaultVal
        }
    }

    /**
     * Universal streaming collection reader:
     * Handles standard JSONArrays `[ {...}, {...} ]`, ID-keyed JSONObjects `{ "1": {...}, "2": {...} }`,
     * and nested wrapper JSONObjects `{ "status": true, "data": [ {...} ] }` or `{ "available_channels": { "1": {...} } }`.
     */
    private fun parseUniversalJsonCollection(
        reader: JsonReader,
        parseSingleObject: (fallbackKey: String) -> Unit
    ) {
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        parseSingleObject("")
                    } else if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                        parseUniversalJsonCollection(reader, parseSingleObject)
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    val propName = reader.nextName()
                    val propClean = propName.trim().lowercase()
                    when (reader.peek()) {
                        JsonToken.BEGIN_ARRAY -> {
                            parseUniversalJsonCollection(reader, parseSingleObject)
                        }
                        JsonToken.BEGIN_OBJECT -> {
                            if (propClean in JSON_WRAPPER_KEYS) {
                                parseUniversalJsonCollection(reader, parseSingleObject)
                            } else if (propClean == "user_info" || propClean == "server_info") {
                                reader.skipValue()
                            } else {
                                parseSingleObject(propName.trim())
                            }
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            else -> reader.skipValue()
        }
    }

    /**
     * Uses LinkedHashMap to preserve the exact category order sent by any Xtream server variant.
     */
    private fun fetchCategoryMapStreaming(url: String): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>(256)
        for (ua in IPTV_USER_AGENTS) {
            val ok = runCatching {
                val req = IboPlayerProfileSpoofer.applyIboApiHeaders(
                    builder = Request.Builder().url(url),
                    targetUrl = url,
                    userAgent = ua
                ).build()
                ChannelRepository.xtreamHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val body = resp.body ?: return@use false
                    BufferedReader(InputStreamReader(wrapAutoDecompressStream(body.byteStream()), Charsets.UTF_8), 131072).use { br ->
                        if (!skipToFirstJsonToken(br)) return@use false
                        val reader = JsonReader(br)
                        reader.isLenient = true
                        parseUniversalJsonCollection(reader) { fallbackKey ->
                            readSingleCategoryObject(reader, map, fallbackKey)
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (map.isNotEmpty()) break
            runCatching { Thread.sleep(300L) }
        }
        return map
    }

    private fun readSingleCategoryObject(
        reader: JsonReader,
        map: MutableMap<String, String>,
        fallbackKey: String = ""
    ) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        var id = fallbackKey
        var name = ""
        reader.beginObject()
        while (reader.hasNext()) {
            val k = reader.nextName().trim().lowercase()
            when (k) {
                "category_id", "id", "cat_id", "cid" -> {
                    val v = safeReadString(reader)
                    if (v.isNotBlank()) id = v
                }
                "category_name", "name", "title", "cat_name", "group", "group_title" -> {
                    val v = safeReadString(reader)
                    if (v.isNotBlank()) name = v
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (id.isNotBlank() && name.isNotBlank() && !AdultContentFilter.isAdultOr18Plus(name, name)) {
            map[id] = name
        }
    }

    private fun streamParseLiveChannels(
        url: String,
        liveCats: Map<String, String>,
        serverLabel: String,
        playlistId: String,
        baseUrl: String,
        uEnc: String,
        pEnc: String
    ): List<TvChannel> {
        val list = ArrayList<TvChannel>(16384)
        val groupPool = HashMap<String, String>(256)
        for (ua in IPTV_USER_AGENTS) {
            val ok = runCatching {
                val req = IboPlayerProfileSpoofer.applyIboApiHeaders(
                    builder = Request.Builder().url(url),
                    targetUrl = url,
                    serverBaseUrl = baseUrl,
                    userAgent = ua
                ).build()
                ChannelRepository.xtreamHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val body = resp.body ?: return@use false
                    BufferedReader(InputStreamReader(wrapAutoDecompressStream(body.byteStream()), Charsets.UTF_8), 262144).use { br ->
                        if (!skipToFirstJsonToken(br)) return@use false
                        val reader = JsonReader(br)
                        reader.isLenient = true
                        var index = 1
                        parseUniversalJsonCollection(reader) { fallbackKey ->
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                reader.beginObject()
                                var streamId = fallbackKey.toIntOrNull() ?: 0
                                var num = index
                                var name = ""
                                var catId = ""
                                var categoryName = ""
                                var icon = ""
                                var directSource = ""
                                var ext = "ts"
                                while (reader.hasNext()) {
                                    val k = reader.nextName().trim().lowercase()
                                    when (k) {
                                        "stream_id", "id", "channel_id", "cid" -> {
                                            val v = safeReadInt(reader, 0)
                                            if (v > 0) streamId = v
                                        }
                                        "num", "number", "ch_num" -> {
                                            val v = safeReadInt(reader, index)
                                            if (v > 0) num = v
                                        }
                                        "name", "title", "stream_display_name", "channel_name", "o_name" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank() && name.isBlank()) name = v
                                        }
                                        "category_id", "cat_id", "category_ids" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank() && catId.isBlank()) catId = v
                                        }
                                        "category_name", "category", "group", "group_title" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank()) categoryName = v
                                        }
                                        "stream_icon", "logo", "icon", "tvg_logo", "img" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank()) icon = v
                                        }
                                        "direct_source", "stream_url", "url", "play_url", "link", "file" -> {
                                            val v = safeReadString(reader)
                                            if (v.startsWith("http", ignoreCase = true) || v.startsWith("rtmp", ignoreCase = true) || v.startsWith("rtsp", ignoreCase = true)) {
                                                directSource = v
                                            }
                                        }
                                        "container_extension", "extension", "ext" -> {
                                            val v = safeReadString(reader).lowercase().removePrefix(".")
                                            if (v.isNotBlank()) ext = v
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                if (streamId > 0 || directSource.isNotBlank()) {
                                    val rawGroup = categoryName.ifBlank { liveCats[catId] ?: catId.ifBlank { "باقات $serverLabel" } }
                                    val groupName = groupPool.getOrPut(rawGroup) { rawGroup }
                                    val finalName = name.ifBlank { "قناة $index" }
                                    if (!AdultContentFilter.isAdultOr18Plus(finalName, groupName)) {
                                        val cleanExt = if (ext == "ts" || ext == "m3u8") ext else "ts"
                                        val streamUrl = directSource.takeIf { it.isNotBlank() }
                                            ?: "$baseUrl/live/$uEnc/$pEnc/$streamId.$cleanExt"
                                        list.add(
                                            TvChannel(
                                                id = 500_000 + index,
                                                number = if (num > 0) num else index,
                                                name = finalName,
                                                url = streamUrl,
                                                group = groupName,
                                                logo = icon.takeIf { it.startsWith("http") },
                                                contentType = StreamContentType.LIVE_TV,
                                                playlistId = playlistId,
                                                playlistName = serverLabel,
                                                streamId = streamId,
                                                streamBaseUrl = baseUrl,
                                                streamUsername = uEnc,
                                                streamPassword = pEnc,
                                                containerExtension = cleanExt
                                            )
                                        )
                                        index++
                                    }
                                }
                            } else {
                                reader.skipValue()
                            }
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (list.isNotEmpty()) break
            runCatching { Thread.sleep(350L) }
        }
        return list
    }

    private fun streamParseVodMovies(
        url: String,
        vodCats: Map<String, String>,
        serverLabel: String,
        playlistId: String,
        baseUrl: String,
        uEnc: String,
        pEnc: String
    ): List<TvChannel> {
        val list = ArrayList<TvChannel>(16384)
        val groupPool = HashMap<String, String>(256)
        for (ua in IPTV_USER_AGENTS) {
            val ok = runCatching {
                val req = IboPlayerProfileSpoofer.applyIboApiHeaders(
                    builder = Request.Builder().url(url),
                    targetUrl = url,
                    serverBaseUrl = baseUrl,
                    userAgent = ua
                ).build()
                ChannelRepository.xtreamHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val body = resp.body ?: return@use false
                    BufferedReader(InputStreamReader(wrapAutoDecompressStream(body.byteStream()), Charsets.UTF_8), 262144).use { br ->
                        if (!skipToFirstJsonToken(br)) return@use false
                        val reader = JsonReader(br)
                        reader.isLenient = true
                        var index = 1
                        parseUniversalJsonCollection(reader) { fallbackKey ->
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                reader.beginObject()
                                var streamId = fallbackKey.toIntOrNull() ?: 0
                                var name = ""
                                var catId = ""
                                var categoryName = ""
                                var icon = ""
                                var ext = "mp4"
                                var rating = ""
                                var plot = ""
                                var releaseYear = ""
                                var directSource = ""
                                while (reader.hasNext()) {
                                    val k = reader.nextName().trim().lowercase()
                                    when (k) {
                                        "stream_id", "id", "vod_id", "movie_id" -> {
                                            val v = safeReadInt(reader, 0)
                                            if (v > 0) streamId = v
                                        }
                                        "name", "title", "stream_display_name", "movie_name", "o_name" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank() && name.isBlank()) name = v
                                        }
                                        "category_id", "cat_id", "category_ids" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank() && catId.isBlank()) catId = v
                                        }
                                        "category_name", "category", "group", "group_title" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank()) categoryName = v
                                        }
                                        "cover_big", "movie_image", "poster" -> {
                                            val v = safeReadString(reader)
                                            if (v.startsWith("http", ignoreCase = true)) icon = FluxBitmapCache.upgradeImageUrlToHighRes(v)
                                        }
                                        "stream_icon", "cover", "logo", "icon", "img" -> {
                                            val v = safeReadString(reader)
                                            if (v.startsWith("http", ignoreCase = true) && icon.isBlank()) icon = FluxBitmapCache.upgradeImageUrlToHighRes(v)
                                        }
                                        "container_extension", "target_container", "extension", "ext" -> {
                                            val v = safeReadString(reader).lowercase().removePrefix(".")
                                            if (v.isNotBlank()) ext = v
                                        }
                                        "rating", "rating_5based", "tmdb_rating" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank() && v != "0" && rating.isBlank()) rating = v
                                        }
                                        "plot", "description", "overview" -> {
                                            reader.skipValue()
                                        }
                                        "releasedate", "release_date", "year" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank() && releaseYear.isBlank()) releaseYear = v
                                        }
                                        "direct_source", "stream_url", "url", "play_url", "link", "file" -> {
                                            val v = safeReadString(reader)
                                            if (v.startsWith("http", ignoreCase = true)) directSource = v
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                if (streamId > 0 || directSource.isNotBlank()) {
                                    val rawGroup = categoryName.ifBlank { vodCats[catId] ?: catId.ifBlank { "أفلام $serverLabel" } }
                                    val groupName = groupPool.getOrPut(rawGroup) { rawGroup }
                                    val finalName = name.ifBlank { "فيلم $index" }
                                    if (!AdultContentFilter.isAdultOr18Plus(finalName, groupName)) {
                                        val cleanExt = ext.ifBlank { "mp4" }
                                        val streamUrl = directSource.takeIf { it.isNotBlank() }
                                            ?: "$baseUrl/movie/$uEnc/$pEnc/$streamId.$cleanExt"
                                        list.add(
                                            TvChannel(
                                                id = 700_000 + index,
                                                number = index,
                                                name = finalName,
                                                url = streamUrl,
                                                group = groupName,
                                                logo = icon.takeIf { it.startsWith("http") },
                                                contentType = StreamContentType.MOVIE,
                                                rating = rating,
                                                releaseYear = releaseYear,
                                                plot = plot,
                                                playlistId = playlistId,
                                                playlistName = serverLabel,
                                                streamId = streamId,
                                                streamBaseUrl = baseUrl,
                                                streamUsername = uEnc,
                                                streamPassword = pEnc,
                                                containerExtension = cleanExt
                                            )
                                        )
                                        index++
                                    }
                                }
                            } else {
                                reader.skipValue()
                            }
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (list.isNotEmpty()) break
            runCatching { Thread.sleep(350L) }
        }
        return list
    }

    private fun streamParseSeriesItems(
        url: String,
        seriesCats: Map<String, String>,
        serverLabel: String,
        playlistId: String,
        apiBase: String,
        baseUrl: String,
        uEnc: String,
        pEnc: String
    ): List<TvChannel> {
        val list = ArrayList<TvChannel>(8192)
        val groupPool = HashMap<String, String>(256)
        for (ua in IPTV_USER_AGENTS) {
            val ok = runCatching {
                val req = IboPlayerProfileSpoofer.applyIboApiHeaders(
                    builder = Request.Builder().url(url),
                    targetUrl = url,
                    serverBaseUrl = baseUrl,
                    userAgent = ua
                ).build()
                ChannelRepository.xtreamHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val body = resp.body ?: return@use false
                    BufferedReader(InputStreamReader(wrapAutoDecompressStream(body.byteStream()), Charsets.UTF_8), 262144).use { br ->
                        if (!skipToFirstJsonToken(br)) return@use false
                        val reader = JsonReader(br)
                        reader.isLenient = true
                        var index = 1
                        parseUniversalJsonCollection(reader) { fallbackKey ->
                            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                reader.beginObject()
                                var seriesId = fallbackKey.toIntOrNull() ?: 0
                                var name = ""
                                var catId = ""
                                var categoryName = ""
                                var cover = ""
                                var rating = ""
                                var plot = ""
                                var releaseDate = ""
                                while (reader.hasNext()) {
                                    val k = reader.nextName().trim().lowercase()
                                    when (k) {
                                        "series_id", "id", "sid" -> {
                                            val v = safeReadInt(reader, 0)
                                            if (v > 0) seriesId = v
                                        }
                                        "name", "title", "series_name", "o_name" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank() && name.isBlank()) name = v
                                        }
                                        "category_id", "cat_id", "category_ids" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank() && catId.isBlank()) catId = v
                                        }
                                        "category_name", "category", "group", "group_title" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank()) categoryName = v
                                        }
                                        "cover_big", "poster" -> {
                                            val v = safeReadString(reader)
                                            if (v.startsWith("http", ignoreCase = true)) cover = FluxBitmapCache.upgradeImageUrlToHighRes(v)
                                        }
                                        "cover", "stream_icon", "logo", "img" -> {
                                            val v = safeReadString(reader)
                                            if (v.startsWith("http", ignoreCase = true) && cover.isBlank()) cover = FluxBitmapCache.upgradeImageUrlToHighRes(v)
                                        }
                                        "rating", "rating_5based" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank() && v != "0" && rating.isBlank()) rating = v
                                        }
                                        "plot", "description", "overview" -> {
                                            reader.skipValue()
                                        }
                                        "releasedate", "release_date", "year" -> {
                                            val v = safeReadString(reader)
                                            if (v.isNotBlank() && releaseDate.isBlank()) releaseDate = v
                                        }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                if (seriesId > 0) {
                                    val rawGroup = categoryName.ifBlank { seriesCats[catId] ?: catId.ifBlank { "مسلسلات $serverLabel" } }
                                    val groupName = groupPool.getOrPut(rawGroup) { rawGroup }
                                    val finalName = name.ifBlank { "مسلسل $index" }
                                    if (!AdultContentFilter.isAdultOr18Plus(finalName, groupName)) {
                                        val seriesInfoUrl = "$apiBase&action=get_series_info&series_id=$seriesId"
                                        list.add(
                                            TvChannel(
                                                id = 900_000 + index,
                                                number = index,
                                                name = finalName,
                                                url = seriesInfoUrl,
                                                group = groupName,
                                                logo = cover.takeIf { it.startsWith("http") },
                                                contentType = StreamContentType.SERIES,
                                                rating = rating,
                                                releaseYear = releaseDate,
                                                plot = plot,
                                                seriesId = seriesId,
                                                playlistId = playlistId,
                                                playlistName = serverLabel,
                                                streamBaseUrl = baseUrl,
                                                streamUsername = uEnc,
                                                streamPassword = pEnc
                                            )
                                        )
                                        index++
                                    }
                                }
                            } else {
                                reader.skipValue()
                            }
                        }
                    }
                    true
                }
            }.getOrDefault(false)
            if (list.isNotEmpty()) break
            runCatching { Thread.sleep(350L) }
        }
        return list
    }

    private fun parsePanelApiFallback(
        panelUrl: String,
        serverLabel: String,
        playlistId: String,
        baseUrl: String,
        uEnc: String,
        pEnc: String
    ): List<TvChannel> {
        val raw = extractJsonPayload(fetchUrlTextWithFallback(panelUrl))
        if (!raw.startsWith("{")) return emptyList()
        return runCatching {
            val root = JSONObject(raw)
            val avail = root.optJSONObject("available_channels")
                ?: root.optJSONObject("channels")
                ?: root.optJSONObject("streams")
                ?: return@runCatching emptyList()
            val list = ArrayList<TvChannel>(avail.length())
            val keys = avail.keys()
            var idx = 1
            while (keys.hasNext()) {
                val key = keys.next()
                val o = avail.optJSONObject(key) ?: continue
                val streamId = o.optInt("stream_id", o.optInt("id", key.toIntOrNull() ?: idx))
                val channelName = o.optString("name", o.optString("title", "قناة $idx")).trim().ifBlank { "قناة $idx" }
                val group = o.optString("category_name", o.optString("category", "باقات $serverLabel")).trim().ifBlank { "باقات $serverLabel" }
                if (AdultContentFilter.isAdultOr18Plus(channelName, group)) continue
                val icon = o.optString("stream_icon", o.optString("logo", "")).trim().takeIf { it.startsWith("http") }
                val direct = o.optString("direct_source", o.optString("url", "")).trim().takeIf { it.startsWith("http") }
                val panelStreamUrl = direct ?: "$baseUrl/live/$uEnc/$pEnc/$streamId.ts"
                list.add(
                    TvChannel(
                        id = 500_000 + idx,
                        number = o.optInt("num", idx).let { if (it > 0) it else idx },
                        name = channelName,
                        url = panelStreamUrl,
                        group = group,
                        logo = icon,
                        contentType = StreamContentType.LIVE_TV,
                        playlistId = playlistId,
                        playlistName = serverLabel,
                        streamId = streamId,
                        streamBaseUrl = baseUrl,
                        streamUsername = uEnc,
                        streamPassword = pEnc
                    )
                )
                idx++
            }
            list
        }.getOrDefault(emptyList())
    }


    private val vodResolvedUrlCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Resolves the exact VOD container_extension (e.g. mkv, mp4, avi, ts) or direct_source via get_vod_info
     * when an Xtream server omits or misreports container_extension in the bulk get_vod_streams list.
     */
    suspend fun resolveVodRealUrlIfNeeded(
        channel: TvChannel,
        matchingAccount: XtreamAccountConfig?
    ): String? = withContext(Dispatchers.IO) {
        if (channel.contentType != StreamContentType.MOVIE || channel.streamId <= 0) return@withContext null
        val cacheKey = "${channel.playlistId}_${channel.streamId}"
        vodResolvedUrlCache[cacheKey]?.let { return@withContext it }

        val rawBase = (matchingAccount?.serverUrl?.takeIf { it.isNotBlank() } ?: channel.streamBaseUrl).trim().removeSuffix("/")
        val baseUrl = normalizeServerUrl(rawBase)
        val rawUser = (matchingAccount?.username?.takeIf { it.isNotBlank() } ?: channel.streamUsername).trim()
        val rawPass = (matchingAccount?.password?.takeIf { it.isNotBlank() } ?: channel.streamPassword).trim()
        if (baseUrl.isBlank() || rawUser.isBlank() || rawPass.isBlank()) return@withContext null

        val uEnc = runCatching {
            URLEncoder.encode(java.net.URLDecoder.decode(rawUser, "UTF-8"), "UTF-8")
        }.getOrDefault(rawUser)
        val pEnc = runCatching {
            URLEncoder.encode(java.net.URLDecoder.decode(rawPass, "UTF-8"), "UTF-8")
        }.getOrDefault(rawPass)

        val infoUrl = "$baseUrl/player_api.php?username=$uEnc&password=$pEnc&action=get_vod_info&vod_id=${channel.streamId}"
        val rawJson = runCatching {
            withTimeout(2_400L) {
                fetchUrlTextFast(infoUrl)
            }
        }.getOrDefault("")
        val clean = extractJsonPayload(rawJson)
        if (!clean.startsWith("{")) return@withContext null

        return@withContext runCatching {
            val root = JSONObject(clean)
            val movieData = root.optJSONObject("movie_data")
                ?: root.optJSONObject("data")?.optJSONObject("movie_data")
                ?: root.optJSONObject("info")
                ?: root
            val direct = movieData.optString("direct_source", "").trim()
            if (direct.startsWith("http://", ignoreCase = true) || direct.startsWith("https://", ignoreCase = true)) {
                vodResolvedUrlCache[cacheKey] = direct
                return@runCatching direct
            }
            val realExt = movieData.optString("container_extension", "")
                .ifBlank { movieData.optString("target_container", "") }
                .ifBlank { root.optJSONObject("info")?.optString("container_extension", "").orEmpty() }
                .trim().lowercase().removePrefix(".")
            if (realExt.isNotBlank()) {
                val built = "$baseUrl/movie/$uEnc/$pEnc/${channel.streamId}.$realExt"
                vodResolvedUrlCache[cacheKey] = built
                built
            } else null
        }.getOrNull()
    }

    private val seriesEpisodesMemoryCache = java.util.concurrent.ConcurrentHashMap<String, List<SeriesEpisode>>()

    private val seriesInfoFastClient by lazy {
        ChannelRepository.bootstrapHttpClient.newBuilder()
            .connectTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(7, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(9, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun resolveEffectiveSeriesId(seriesChannel: TvChannel): Int {
        if (seriesChannel.seriesId > 0) return seriesChannel.seriesId
        val fromUrl = Regex("[?&]series_id=(\\d+)", RegexOption.IGNORE_CASE)
            .find(seriesChannel.url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        if (fromUrl > 0) return fromUrl
        if (seriesChannel.streamId > 0 && seriesChannel.url.contains("action=get_series_info", ignoreCase = true)) {
            return seriesChannel.streamId
        }
        return 0
    }

    fun getCachedSeriesEpisodesNow(seriesChannel: TvChannel): List<SeriesEpisode>? {
        if (seriesChannel.episodes.isNotEmpty()) return seriesChannel.episodes
        val effectiveSid = resolveEffectiveSeriesId(seriesChannel)
        if (effectiveSid <= 0) return null
        val cacheKey = "${seriesChannel.playlistId}_$effectiveSid"
        return seriesEpisodesMemoryCache[cacheKey]
    }

    private fun readSingleEpisodeStreaming(
        reader: JsonReader,
        defaultSeason: Int,
        defaultIndex: Int,
        baseUrl: String,
        user: String,
        pass: String,
        outList: MutableList<SeriesEpisode>
    ) {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return
        }
        var epId = ""
        var directUrl = ""
        var sNum = defaultSeason.coerceAtLeast(1)
        var epNum = defaultIndex.coerceAtLeast(1)
        var epTitle = ""
        var ext = "mp4"
        var dur = ""

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName().trim().lowercase()) {
                "id", "stream_id", "episode_id" -> {
                    val v = safeReadString(reader)
                    if (v.isNotBlank() && epId.isBlank()) epId = v
                }
                "direct_source", "url", "stream_url" -> {
                    val v = safeReadString(reader)
                    if (v.startsWith("http", ignoreCase = true)) directUrl = v
                }
                "season", "season_num" -> {
                    val v = safeReadInt(reader, defaultSeason)
                    if (v > 0) sNum = v
                }
                "episode_num", "num" -> {
                    val v = safeReadInt(reader, defaultIndex)
                    if (v > 0) epNum = v
                }
                "title", "name" -> {
                    val v = safeReadString(reader)
                    if (v.isNotBlank() && !v.equals("false", ignoreCase = true) && epTitle.isBlank()) epTitle = v
                }
                "container_extension", "ext" -> {
                    val v = safeReadString(reader).lowercase().removePrefix(".")
                    if (v.isNotBlank()) ext = v
                }
                "duration" -> {
                    val v = safeReadString(reader)
                    if (v.isNotBlank() && !v.equals("false", ignoreCase = true)) dur = v
                }
                "info" -> {
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val ik = reader.nextName().trim().lowercase()
                            if (ik == "duration") {
                                val dv = safeReadString(reader)
                                if (dv.isNotBlank() && !dv.equals("false", ignoreCase = true)) dur = dv
                            } else {
                                reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (epId.isBlank() && !directUrl.startsWith("http", ignoreCase = true)) return
        val playUrl = directUrl.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: "$baseUrl/series/$user/$pass/$epId.$ext"
        outList.add(
            SeriesEpisode(
                id = epId.ifBlank { "${sNum}_$epNum" },
                seasonNumber = sNum,
                episodeNumber = epNum,
                title = epTitle.ifBlank { "الموسم $sNum - الحلقة $epNum" },
                url = playUrl,
                durationLabel = dur,
                containerExtension = ext.ifBlank { "mp4" }
            )
        )
    }

    private fun parseEpisodesNodeStreaming(
        reader: JsonReader,
        seasonNumber: Int,
        baseUrl: String,
        user: String,
        pass: String,
        outList: MutableList<SeriesEpisode>
    ) {
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                var idx = 1
                while (reader.hasNext()) {
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> readSingleEpisodeStreaming(reader, seasonNumber, idx++, baseUrl, user, pass, outList)
                        JsonToken.BEGIN_ARRAY -> parseEpisodesNodeStreaming(reader, if (seasonNumber <= 1) idx++ else seasonNumber, baseUrl, user, pass, outList)
                        else -> reader.skipValue()
                    }
                }
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                var idx = 1
                while (reader.hasNext()) {
                    val key = reader.nextName().trim()
                    val inferredSeason = key.toIntOrNull() ?: seasonNumber
                    when (reader.peek()) {
                        JsonToken.BEGIN_ARRAY, JsonToken.BEGIN_OBJECT -> {
                            parseEpisodesNodeStreaming(reader, inferredSeason, baseUrl, user, pass, outList)
                        }
                        else -> reader.skipValue()
                    }
                    idx++
                }
                reader.endObject()
            }
            else -> reader.skipValue()
        }
    }

    private fun fetchAndParseSeriesInfoFast(
        infoUrl: String,
        baseUrl: String,
        user: String,
        pass: String
    ): List<SeriesEpisode> {
        for (ua in IPTV_USER_AGENTS.take(2)) {
            val req = IboPlayerProfileSpoofer.applyIboApiHeaders(
                builder = Request.Builder().url(infoUrl),
                targetUrl = infoUrl,
                serverBaseUrl = baseUrl,
                userAgent = ua,
                useIdentityEncoding = false
            ).build()
            for (client in listOf(seriesInfoFastClient, ChannelRepository.httpClient)) {
                try {
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val body = resp.body ?: return@use
                        val list = ArrayList<SeriesEpisode>(128)
                        BufferedReader(InputStreamReader(wrapAutoDecompressStream(body.byteStream()), Charsets.UTF_8), 65536).use { br ->
                            if (!skipToFirstJsonToken(br)) return@use
                            val reader = JsonReader(br)
                            reader.isLenient = true
                            when (reader.peek()) {
                                JsonToken.BEGIN_ARRAY -> {
                                    parseEpisodesNodeStreaming(reader, 1, baseUrl, user, pass, list)
                                }
                                JsonToken.BEGIN_OBJECT -> {
                                    reader.beginObject()
                                    while (reader.hasNext()) {
                                        when (reader.nextName().trim().lowercase()) {
                                            "episodes" -> parseEpisodesNodeStreaming(reader, 1, baseUrl, user, pass, list)
                                            "data" -> {
                                                if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                                    reader.beginObject()
                                                    while (reader.hasNext()) {
                                                        if (reader.nextName().trim().equals("episodes", ignoreCase = true)) {
                                                            parseEpisodesNodeStreaming(reader, 1, baseUrl, user, pass, list)
                                                        } else {
                                                            reader.skipValue()
                                                        }
                                                    }
                                                    reader.endObject()
                                                } else {
                                                    reader.skipValue()
                                                }
                                            }
                                            "seasons" -> {
                                                if (reader.peek() == JsonToken.BEGIN_ARRAY) {
                                                    reader.beginArray()
                                                    var sIdx = 1
                                                    while (reader.hasNext()) {
                                                        if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                                                            reader.beginObject()
                                                            var sNum = sIdx
                                                            while (reader.hasNext()) {
                                                                val sk = reader.nextName().trim().lowercase()
                                                                when (sk) {
                                                                    "season_number", "season" -> {
                                                                        val v = safeReadInt(reader, sIdx)
                                                                        if (v > 0) sNum = v
                                                                    }
                                                                    "episodes" -> parseEpisodesNodeStreaming(reader, sNum, baseUrl, user, pass, list)
                                                                    else -> reader.skipValue()
                                                                }
                                                            }
                                                            reader.endObject()
                                                        } else {
                                                            reader.skipValue()
                                                        }
                                                        sIdx++
                                                    }
                                                    reader.endArray()
                                                } else {
                                                    reader.skipValue()
                                                }
                                            }
                                            else -> reader.skipValue()
                                        }
                                    }
                                    reader.endObject()
                                }
                                else -> reader.skipValue()
                            }
                        }
                        if (list.isNotEmpty()) {
                            return list.distinctBy { "${it.seasonNumber}_${it.episodeNumber}_${it.id}" }
                                .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return emptyList()
    }

    suspend fun fetchSeriesEpisodes(
        seriesChannel: TvChannel,
        xtreamLists: List<XtreamAccountConfig>
    ): List<SeriesEpisode> = withContext(Dispatchers.IO) {
        if (seriesChannel.episodes.isNotEmpty()) return@withContext seriesChannel.episodes
        val effectiveSid = resolveEffectiveSeriesId(seriesChannel)
        val cacheKey = "${seriesChannel.playlistId}_$effectiveSid"
        if (effectiveSid > 0) {
            seriesEpisodesMemoryCache[cacheKey]?.let { if (it.isNotEmpty()) return@withContext it }
        }

        val matchingAccount = xtreamLists.find { it.id == seriesChannel.playlistId }
            ?: xtreamLists.firstOrNull { it.isConfigured }
        val seriesUrlMatch = Regex("^(https?://[^/]+)/series/([^/]+)/([^/]+)/", RegexOption.IGNORE_CASE)
            .find(seriesChannel.url.trim())
        val urlBaseFallback = when {
            seriesChannel.url.contains("/player_api.php", ignoreCase = true) ->
                seriesChannel.url.substringBefore("/player_api.php").trim().removeSuffix("/")
            seriesUrlMatch != null ->
                seriesUrlMatch.groupValues[1].trim().removeSuffix("/")
            else -> ""
        }
        val urlUserFallback = Regex("[?&]username=([^&]+)", RegexOption.IGNORE_CASE)
            .find(seriesChannel.url)?.groupValues?.getOrNull(1)?.trim()
            ?: seriesUrlMatch?.groupValues?.getOrNull(2)?.trim()
            ?: ""
        val urlPassFallback = Regex("[?&]password=([^&]+)", RegexOption.IGNORE_CASE)
            .find(seriesChannel.url)?.groupValues?.getOrNull(1)?.trim()
            ?: seriesUrlMatch?.groupValues?.getOrNull(3)?.trim()
            ?: ""

        val baseUrl = seriesChannel.streamBaseUrl
            .ifBlank { urlBaseFallback }
            .ifBlank { matchingAccount?.let { normalizeServerUrl(it.serverUrl) }.orEmpty() }
        val rawUser = seriesChannel.streamUsername
            .ifBlank { urlUserFallback }
            .ifBlank { matchingAccount?.username?.trim().orEmpty() }
        val rawPass = seriesChannel.streamPassword
            .ifBlank { urlPassFallback }
            .ifBlank { matchingAccount?.password?.trim().orEmpty() }
        val user = runCatching {
            URLEncoder.encode(java.net.URLDecoder.decode(rawUser, "UTF-8"), "UTF-8")
        }.getOrDefault(rawUser)
        val pass = runCatching {
            URLEncoder.encode(java.net.URLDecoder.decode(rawPass, "UTF-8"), "UTF-8")
        }.getOrDefault(rawPass)

        if (baseUrl.isBlank() || user.isBlank() || pass.isBlank() || effectiveSid <= 0) {
            return@withContext emptyList()
        }

        val infoUrl = "$baseUrl/player_api.php?username=$user&password=$pass&action=get_series_info&series_id=$effectiveSid"

        // 1. Fast Streaming JsonReader parser (skips heavy plot/backdrop/cast fields and parses episodes in <15ms!)
        val fastParsed = fetchAndParseSeriesInfoFast(infoUrl, baseUrl, user, pass)
        if (fastParsed.isNotEmpty()) {
            seriesEpisodesMemoryCache[cacheKey] = fastParsed
            return@withContext fastParsed
        }

        // 2. Fallback to DOM JSONObject parser for non-standard server responses
        try {
            val raw = extractJsonPayload(fetchUrlTextFast(infoUrl).ifBlank { fetchUrlTextWithFallback(infoUrl) })
            if (!raw.startsWith("{") && !raw.startsWith("[")) return@withContext emptyList()
            val result = mutableListOf<SeriesEpisode>()

            fun parseSingleEpisodeObj(ep: JSONObject, defaultSeason: Int, defaultIndex: Int) {
                val epId = ep.optString("id", "")
                    .ifBlank { ep.optString("stream_id", "") }
                    .ifBlank { ep.optString("episode_id", "") }
                    .trim()
                val directUrl = ep.optString("direct_source", "")
                    .ifBlank { ep.optString("url", "") }
                    .ifBlank { ep.optString("stream_url", "") }
                    .trim()
                if (epId.isBlank() && !directUrl.startsWith("http", ignoreCase = true)) return

                val sNum = ep.optInt("season", ep.optInt("season_num", defaultSeason)).coerceAtLeast(1)
                val epNum = ep.optInt("episode_num", ep.optInt("num", defaultIndex)).coerceAtLeast(1)
                val epTitle = ep.optString("title", ep.optString("name", "الحلقة $epNum")).trim()
                val ext = ep.optString("container_extension", ep.optString("ext", "mp4"))
                    .trim().lowercase().removePrefix(".").ifBlank { "mp4" }
                val dur = ep.optJSONObject("info")?.optString("duration", "")
                    ?: ep.optString("duration", "")
                val playUrl = directUrl.takeIf { it.startsWith("http", ignoreCase = true) }
                    ?: "$baseUrl/series/$user/$pass/$epId.$ext"
                result.add(
                    SeriesEpisode(
                        id = epId.ifBlank { "${sNum}_$epNum" },
                        seasonNumber = sNum,
                        episodeNumber = epNum,
                        title = if (epTitle.isNotBlank() && !epTitle.equals("false", ignoreCase = true)) epTitle else "الموسم $sNum - الحلقة $epNum",
                        url = playUrl,
                        durationLabel = if (dur.equals("false", ignoreCase = true)) "" else dur,
                        containerExtension = ext
                    )
                )
            }

            fun parseEpisodeContainer(container: Any?, seasonNumber: Int) {
                when (container) {
                    is JSONArray -> {
                        for (i in 0 until container.length()) {
                            val item = container.opt(i)
                            when (item) {
                                is JSONObject -> parseSingleEpisodeObj(item, seasonNumber, i + 1)
                                is JSONArray -> parseEpisodeContainer(item, if (seasonNumber <= 1) i + 1 else seasonNumber)
                            }
                        }
                    }
                    is JSONObject -> {
                        val keys = container.keys()
                        var idx = 1
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val child = container.opt(k)
                            val inferredSeason = k.toIntOrNull() ?: seasonNumber
                            if (child is JSONObject && (child.has("id") || child.has("stream_id") || child.has("episode_num") || child.has("container_extension") || child.has("direct_source"))) {
                                parseSingleEpisodeObj(child, seasonNumber, k.toIntOrNull() ?: idx)
                            } else {
                                parseEpisodeContainer(child, inferredSeason)
                            }
                            idx++
                        }
                    }
                }
            }

            if (raw.startsWith("[")) {
                parseEpisodeContainer(JSONArray(raw), 1)
            } else {
                val root = JSONObject(raw)
                val dataRoot = root.optJSONObject("data") ?: root
                val episodesNode = dataRoot.opt("episodes") ?: root.opt("episodes")
                if (episodesNode != null && episodesNode != JSONObject.NULL) {
                    parseEpisodeContainer(episodesNode, 1)
                }
                if (result.isEmpty()) {
                    val seasonsArr = dataRoot.optJSONArray("seasons") ?: root.optJSONArray("seasons")
                    if (seasonsArr != null) {
                        for (sIdx in 0 until seasonsArr.length()) {
                            val sObj = seasonsArr.optJSONObject(sIdx) ?: continue
                            val sNum = sObj.optInt("season_number", sObj.optInt("season", sIdx + 1))
                            parseEpisodeContainer(sObj.opt("episodes"), sNum)
                        }
                    }
                }
            }

            val finalSorted = result.distinctBy { "${it.seasonNumber}_${it.episodeNumber}_${it.id}" }
                .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber }))
            if (finalSorted.isNotEmpty()) {
                seriesEpisodesMemoryCache[cacheKey] = finalSorted
            }
            finalSorted
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readResponseBodyAutoDecompress(resp: okhttp3.Response): String {
        val body = resp.body ?: return ""
        return BufferedReader(InputStreamReader(wrapAutoDecompressStream(body.byteStream()), Charsets.UTF_8), 65536).use {
            it.readText().trim()
        }
    }

    private fun fetchUrlTextFast(url: String): String {
        val req = IboPlayerProfileSpoofer.applyIboApiHeaders(
            builder = Request.Builder().url(url).header("Cache-Control", "no-cache, no-store, max-age=0"),
            targetUrl = url,
            userAgent = IPTV_USER_AGENTS.first()
        ).build()
        val firstTry = runCatching {
            ChannelRepository.bootstrapHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) readResponseBodyAutoDecompress(resp) else ""
            }
        }.getOrDefault("")
        if (firstTry.isNotBlank()) return firstTry
        return runCatching {
            ChannelRepository.httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) readResponseBodyAutoDecompress(resp) else ""
            }
        }.getOrDefault("")
    }

    private fun fetchUrlTextWithFallback(url: String): String {
        for (ua in IPTV_USER_AGENTS.take(4)) {
            val req = IboPlayerProfileSpoofer.applyIboApiHeaders(
                builder = Request.Builder().url(url),
                targetUrl = url,
                userAgent = ua
            ).build()
            for (client in listOf(ChannelRepository.xtreamHttpClient, ChannelRepository.bootstrapHttpClient, ChannelRepository.httpClient)) {
                try {
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val txt = readResponseBodyAutoDecompress(resp)
                            if (txt.isNotBlank()) return txt
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        return ""
    }

    private fun fetchAndParseM3uStream(
        url: String,
        defaultGroup: String,
        playlistId: String,
        playlistName: String
    ): List<TvChannel> {
        for (ua in IPTV_USER_AGENTS.take(4)) {
            try {
                val req = IboPlayerProfileSpoofer.applyIboApiHeaders(
                    builder = Request.Builder().url(url),
                    targetUrl = url,
                    userAgent = ua
                ).build()
                ChannelRepository.xtreamHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body ?: return@use
                    BufferedReader(InputStreamReader(wrapAutoDecompressStream(body.byteStream()), Charsets.UTF_8), 262144).use { br ->
                        val parsed = parseM3uBufferedReader(br, defaultGroup, playlistId, playlistName)
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    fun parseM3uPlaylist(
        m3u: String,
        defaultGroup: String,
        playlistId: String,
        playlistName: String
    ): List<TvChannel> {
        if (!m3u.contains("#EXTINF", ignoreCase = true)) return emptyList()
        return BufferedReader(StringReader(m3u), 131072).use { br ->
            parseM3uBufferedReader(br, defaultGroup, playlistId, playlistName)
        }
    }

    private fun parseM3uBufferedReader(
        reader: BufferedReader,
        defaultGroup: String,
        playlistId: String,
        playlistName: String
    ): List<TvChannel> {
        val list = ArrayList<TvChannel>(16384)
        val groupPool = HashMap<String, String>(256)
        var currentName = ""
        var currentGroup = defaultGroup
        var currentLogo: String? = null
        var currentUa: String? = null
        var currentReferer: String? = null
        var currentOrigin: String? = null
        var currentCookie: String? = null
        var currentDrmScheme = ""
        var currentDrmKey = ""
        var counter = 1

        var rawLine: String?
        while (reader.readLine().also { rawLine = it } != null) {
            val line = rawLine!!.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                currentName = line.substringAfterLast(",", "قناة $counter").trim()
                currentGroup = extractAttr(line, "group-title").ifBlank { defaultGroup }
                currentLogo = extractAttr(line, "tvg-logo").takeIf { it.startsWith("http") }
                currentUa = extractAttr(line, "user-agent").ifBlank { extractAttr(line, "http-user-agent") }.takeIf { it.isNotBlank() }
                currentReferer = extractAttr(line, "referer").ifBlank { extractAttr(line, "http-referrer") }.takeIf { it.isNotBlank() }
                currentOrigin = null
                currentCookie = null
                currentDrmScheme = ""
                currentDrmKey = ""
            } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                val grp = line.substringAfter(":").trim()
                if (grp.isNotBlank()) currentGroup = grp
            } else if (line.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val opt = line.substringAfter(":").trim()
                when {
                    opt.startsWith("http-user-agent=", ignoreCase = true) ->
                        currentUa = opt.substringAfter("=").trim().takeIf { it.isNotBlank() }
                    opt.startsWith("http-referrer=", ignoreCase = true) || opt.startsWith("http-referer=", ignoreCase = true) ->
                        currentReferer = opt.substringAfter("=").trim().takeIf { it.isNotBlank() }
                    opt.startsWith("http-origin=", ignoreCase = true) ->
                        currentOrigin = opt.substringAfter("=").trim().takeIf { it.isNotBlank() }
                    opt.startsWith("http-cookie=", ignoreCase = true) ->
                        currentCookie = opt.substringAfter("=").trim().takeIf { it.isNotBlank() }
                }
            } else if (line.startsWith("#KODIPROP:", ignoreCase = true)) {
                val prop = line.substringAfter(":").trim()
                when {
                    prop.startsWith("inputstream.adaptive.license_type=", ignoreCase = true) ->
                        currentDrmScheme = prop.substringAfter("=").trim().lowercase()
                    prop.startsWith("inputstream.adaptive.license_key=", ignoreCase = true) ->
                        currentDrmKey = prop.substringAfter("=").trim()
                }
            } else if (!line.startsWith("#") &&
                (line.startsWith("http://", ignoreCase = true) ||
                    line.startsWith("https://", ignoreCase = true) ||
                    line.startsWith("rtsp://", ignoreCase = true) ||
                    line.startsWith("rtmp://", ignoreCase = true) ||
                    line.startsWith("rtmps://", ignoreCase = true) ||
                    line.startsWith("udp://", ignoreCase = true) ||
                    line.startsWith("rtp://", ignoreCase = true) ||
                    line.startsWith("mms://", ignoreCase = true))
            ) {
                if (AdultContentFilter.isAdultOr18Plus(currentName, currentGroup)) {
                    continue
                }
                val cleanUrl = line.substringBefore("|").trim()
                if (line.contains("|")) {
                    val pipePart = line.substringAfter("|")
                    pipePart.split("&", "|").forEach { kv ->
                        val k = kv.substringBefore("=").trim().lowercase()
                        val v = kv.substringAfter("=", "").trim()
                        if (v.isNotBlank()) {
                            when (k) {
                                "user-agent", "user_agent" -> currentUa = v
                                "referer", "referrer" -> currentReferer = v
                                "origin" -> currentOrigin = v
                                "cookie" -> currentCookie = v
                            }
                        }
                    }
                }
                val lowUrl = cleanUrl.lowercase().substringBefore("?")
                val lowGroup = currentGroup.lowercase()
                val lowName = currentName.lowercase()
                val isSeriesPattern = lowUrl.contains("/series/") ||
                    lowGroup.contains("مسلسل") || lowGroup.contains("series") || lowGroup.contains("série") ||
                    Regex("s\\d{1,2}\\s*[eex]\\s*\\d{1,3}", RegexOption.IGNORE_CASE).containsMatchIn(lowName) ||
                    lowName.contains("حلقة") || lowName.contains("موسم")
                val isMoviePattern = !isSeriesPattern && (
                    lowUrl.contains("/movie/") || lowUrl.contains("/vod/") ||
                        lowGroup.contains("افلام") || lowGroup.contains("أفلام") ||
                        lowGroup.contains("movie") || lowGroup.contains("film") || lowGroup.contains("vod") ||
                        lowUrl.endsWith(".mp4") || lowUrl.endsWith(".mkv") ||
                        lowUrl.endsWith(".avi") || lowUrl.endsWith(".mov") ||
                        lowUrl.endsWith(".webm") || lowUrl.endsWith(".flv")
                    )
                val type = when {
                    isSeriesPattern -> StreamContentType.SERIES
                    isMoviePattern -> StreamContentType.MOVIE
                    else -> StreamContentType.LIVE_TV
                }
                val resolvedName = currentName.ifBlank { "قناة $counter" }
                list.add(
                    TvChannel(
                        id = 600_000 + counter,
                        number = counter,
                        name = resolvedName,
                        url = cleanUrl,
                        group = currentGroup,
                        logo = currentLogo,
                        userAgent = currentUa,
                        referer = currentReferer,
                        origin = currentOrigin,
                        cookie = currentCookie,
                        drmScheme = currentDrmScheme,
                        drmKey = currentDrmKey,
                        contentType = type,
                        playlistId = playlistId,
                        playlistName = playlistName
                    )
                )
                counter++
            }
        }
        return list
    }

    private fun extractAttr(extInf: String, attr: String): String {
        val regex = Regex("$attr\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        return regex.find(extInf)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }
}
