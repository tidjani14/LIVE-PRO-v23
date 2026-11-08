package com.tvflux.pro

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class PersistedAppState(
    val deviceId: String,
    val deviceName: String,
    val activationCode: String,
    val xtreamPlaylists: List<XtreamAccountConfig>,
    val cachedChannels: List<TvChannel>,
    val cachedMovies: List<TvChannel>,
    val cachedSeries: List<TvChannel>,
    val favorites: List<TvChannel>,
    val history: List<TvChannel>,
    val subscription: SubscriptionInfo?,
    val preferences: PlayerPreferences,
    val vodWatchProgress: Map<String, VodWatchProgress> = emptyMap(),
    val storedPlaylistCounts: Map<String, Triple<Int, Int, Int>> = emptyMap()
)

object StorageManager {
    private const val FILE_NAME = "live_pro_enterprise_state_v60.json"
    private const val LAST_PLAYLIST_PREFS = "livepro_last_active_playlist_prefs"

    fun saveLastActivePlaylistId(context: Context, playlistId: String) {
        val clean = playlistId.trim()
        if (clean.isBlank() || clean == "ALL") return
        runCatching {
            context.getSharedPreferences(LAST_PLAYLIST_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("last_active_playlist_id", clean)
                .apply()
        }
    }

    fun getLastActivePlaylistId(context: Context): String {
        return runCatching {
            context.getSharedPreferences(LAST_PLAYLIST_PREFS, Context.MODE_PRIVATE)
                .getString("last_active_playlist_id", "")
                ?.trim()
                .orEmpty()
        }.getOrDefault("")
    }
    private const val CATALOG_BACKUP_FILE = "live_pro_catalog_backup_v60.json"
    private const val MAX_HISTORY = 80
    private val saveMutex = Mutex()
    private val DEVICE_ID_REGEX = Regex("^DEV-[A-Z0-9]{4}-[A-Z0-9]{4}$")

    @Volatile
    private var memoryCachedDeviceId: String = ""

    @SuppressLint("HardwareIds")
    fun resolveHardwareDeviceId(context: Context): String {
        if (memoryCachedDeviceId.isNotBlank() && DEVICE_ID_REGEX.matches(memoryCachedDeviceId)) {
            return memoryCachedDeviceId
        }
        try {
            val sp = context.getSharedPreferences("live_pro_hw_id_cache", Context.MODE_PRIVATE)
            val savedHwId = sp.getString("immutable_device_id", "")?.uppercase().orEmpty()
            if (DEVICE_ID_REGEX.matches(savedHwId)) {
                memoryCachedDeviceId = savedHwId
                return savedHwId
            }
        } catch (_: Throwable) {
        }

        val drmIdHex = try {
            val widevineUuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
            val drm = android.media.MediaDrm(widevineUuid)
            try {
                val bytes = drm.getPropertyByteArray(android.media.MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                bytes?.joinToString("") { "%02X".format(it) }.orEmpty()
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try { drm.close() } catch (_: Throwable) {}
                } else {
                    @Suppress("DEPRECATION")
                    try { drm.release() } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            ""
        }

        val cpuSerial = runCatching {
            File("/proc/cpuinfo").takeIf { it.canRead() }?.useLines { lines ->
                lines.filter {
                    it.startsWith("Serial", ignoreCase = true) ||
                        it.startsWith("Hardware", ignoreCase = true) ||
                        it.startsWith("Revision", ignoreCase = true)
                }.joinToString("|") { it.trim() }
            }.orEmpty()
        }.getOrDefault("")

        val hwMac = runCatching {
            listOf("/sys/class/net/eth0/address", "/sys/class/net/wlan0/address")
                .mapNotNull { path ->
                    val f = File(path)
                    if (f.canRead()) f.readText().trim().uppercase() else null
                }
                .firstOrNull { it.length >= 11 && it != "00:00:00:00:00:00" && it != "02:00:00:00:00:00" }
                .orEmpty()
        }.getOrDefault("")

        val boardSpec = listOf(
            Build.BOARD,
            Build.BRAND,
            Build.DEVICE,
            Build.HARDWARE,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.PRODUCT,
            Build.SUPPORTED_ABIS.joinToString(",")
        ).joinToString("|") { it.orEmpty().trim().uppercase() }

        val rawSeed = when {
            drmIdHex.length >= 12 -> "LIVEPRO-IMMUTABLE-DRM-$drmIdHex-$boardSpec"
            hwMac.isNotBlank() || cpuSerial.isNotBlank() -> "LIVEPRO-IMMUTABLE-SOC-$hwMac-$cpuSerial-$boardSpec"
            else -> {
                val fallbackAndroidId = runCatching {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                }.getOrNull().orEmpty()
                "LIVEPRO-FALLBACK-$boardSpec-$fallbackAndroidId"
            }
        }

        val digest = MessageDigest.getInstance("SHA-256").digest(rawSeed.toByteArray(Charsets.UTF_8))
        val chars = "0123456789ABCDEF"
        val part1 = buildString {
            for (i in 0 until 4) {
                append(chars[(digest[i].toInt() and 0xFF) % chars.length])
            }
        }
        val part2 = buildString {
            for (i in 4 until 8) {
                append(chars[(digest[i].toInt() and 0xFF) % chars.length])
            }
        }
        val computedId = "DEV-$part1-$part2"
        memoryCachedDeviceId = computedId
        try {
            context.getSharedPreferences("live_pro_hw_id_cache", Context.MODE_PRIVATE)
                .edit()
                .putString("immutable_device_id", computedId)
                .apply()
        } catch (_: Throwable) {
        }
        return computedId
    }

    fun resolveDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL?.trim().orEmpty()
        val combined = if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }
        return if (combined.isNotBlank()) combined.take(55) else "Android TV"
    }

    /**
     * Instant Startup State Loader:
     * - Reads playlist metadata and stored SQLite counts in <2ms.
     * - Loads ONLY the single active playlist from SQLite (`loadPlaylistCatalogSnapshot(activeId)`)
     *   instead of loading all playlists into RAM at once, making app startup instantaneous even with 10+ huge playlists!
     */
    suspend fun loadState(context: Context): PersistedAppState = withContext(Dispatchers.IO) {
        val hardwareId = resolveHardwareDeviceId(context)
        val hwName = resolveDeviceName()
        val file = File(context.filesDir, FILE_NAME)
        val sqliteDb = TiviMateLocalDatabase.getInstance(context)

        val storedCountsMap = runCatching { sqliteDb.getStoredPlaylistCountsMap() }
            .getOrDefault(emptyMap())
        val sqlPlaylists = runCatching { sqliteDb.loadDistinctPlaylistsFromSql() }
            .getOrDefault(emptyList())

        if (!file.exists()) {
            val initialActiveId = sqlPlaylists.firstOrNull()?.id
                ?: if (storedCountsMap.containsKey("PORTAL_MAIN")) "PORTAL_MAIN" else "PORTAL_MAIN"
            val (dbLive, dbMovies, dbSeries) = runCatching {
                sqliteDb.loadPlaylistCatalogSnapshot(initialActiveId)
            }.getOrDefault(Triple(emptyList(), emptyList(), emptyList()))

            return@withContext PersistedAppState(
                deviceId = hardwareId,
                deviceName = hwName,
                activationCode = "",
                xtreamPlaylists = sqlPlaylists,
                cachedChannels = dbLive,
                cachedMovies = dbMovies,
                cachedSeries = dbSeries,
                favorites = emptyList(),
                history = emptyList(),
                subscription = SubscriptionInfo(active = true, isExpired = false, daysLeft = 30, reason = "ok"),
                preferences = PlayerPreferences(
                    playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                    activePlaylistFilterId = initialActiveId,
                    lowEndDeviceMode = false,
                    preferSoftwareDecoder = false,
                    bufferProfile = BufferProfile.XPOLA_ZERO_LAG
                ),
                vodWatchProgress = emptyMap(),
                storedPlaylistCounts = storedCountsMap
            )
        }

        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val savedId = root.optString("deviceId").uppercase()
            val finalDeviceId = if (DEVICE_ID_REGEX.matches(savedId)) savedId else hardwareId
            val savedActivationCode = root.optString("activationCode", "")

            val xtreamPlaylists = mutableListOf<XtreamAccountConfig>()
            val xArr = root.optJSONArray("xtreamPlaylists")
            if (xArr != null) {
                for (i in 0 until xArr.length()) {
                    val xObj = xArr.optJSONObject(i) ?: continue
                    val cfg = parseXtreamConfig(xObj, i)
                    if (cfg.isConfigured) xtreamPlaylists.add(cfg)
                }
            }

            // Reconcile xtreamPlaylists with SQLite catalog counts so every locally cached playlist shows its exact counts
            for (sqlPl in sqlPlaylists) {
                val existingIdx = xtreamPlaylists.indexOfFirst { it.id == sqlPl.id }
                if (existingIdx < 0) {
                    xtreamPlaylists.add(sqlPl)
                } else {
                    val cur = xtreamPlaylists[existingIdx]
                    val (sLive, sMovie, sSeries) = storedCountsMap[sqlPl.id]
                        ?: Triple(sqlPl.liveCount, sqlPl.movieCount, sqlPl.seriesCount)
                    val realLive = if (sLive > 0) sLive else cur.liveCount
                    val realMovies = if (sMovie > 0) sMovie else cur.movieCount
                    val realSeries = if (sSeries > 0) sSeries else cur.seriesCount
                    val hasLocal = (sLive + sMovie + sSeries) > 0
                    xtreamPlaylists[existingIdx] = cur.copy(
                        liveCount = realLive,
                        movieCount = realMovies,
                        seriesCount = realSeries,
                        statusNote = if (hasLocal) {
                            "محفوظة محلياً ✓ ($realLive قناة • $realMovies فيلم • $realSeries مسلسل)"
                        } else {
                            cur.statusNote
                        }
                    )
                }
            }

            runCatching {
                val backupFile = File(context.filesDir, CATALOG_BACKUP_FILE)
                if (backupFile.exists()) backupFile.delete()
            }

            val favs = parseChannelList(root.optJSONArray("favorites"), StreamContentType.LIVE_TV)
            val hist = parseChannelList(root.optJSONArray("history"), StreamContentType.LIVE_TV)

            val subObj = root.optJSONObject("subscription")
            val sub = subObj?.let {
                val expDateStr = it.optString("expireDate")
                val reasonStr = it.optString("reason", "ok")
                val realExpired = it.optBoolean("isExpired", false) &&
                    (ExpiryAlertHelper.isDateAlreadyExpired(expDateStr) || reasonStr == "expired" || reasonStr == "disabled")
                SubscriptionInfo(
                    active = !realExpired,
                    isExpired = realExpired,
                    isTrial = it.optBoolean("isTrial", false),
                    reason = if (realExpired) "expired" else "ok",
                    expireDate = expDateStr,
                    daysLeft = if (realExpired) 0 else it.optInt("daysLeft", 30).coerceAtLeast(1),
                    deviceName = it.optString("deviceName", hwName),
                    message = it.optString("message"),
                    portalMainEnabled = it.optBoolean("portalMainEnabled", false)
                )
            } ?: SubscriptionInfo(active = true, isExpired = false, daysLeft = 30, reason = "ok")

            val pObj = root.optJSONObject("preferences") ?: JSONObject()
            val thumbLimit = runCatching {
                ThumbnailCacheLimitOption.valueOf(
                    pObj.optString("thumbnailCacheLimit", ThumbnailCacheLimitOption.MB_64.name)
                )
            }.getOrDefault(ThumbnailCacheLimitOption.MB_64)
            FluxBitmapCache.maxDiskCacheBytes = thumbLimit.maxBytes

            val spLastActiveId = getLastActivePlaylistId(context)
            val rawSavedActiveId = spLastActiveId.ifBlank { pObj.optString("activePlaylistFilterId", "") }
            val enabledLists = xtreamPlaylists.filter { it.enabled }
            // الدخول التلقائي المباشر دائماً لآخر قائمة كان يشتغل بها الزبون دون أي نافذة اختيار قوائم منبثقة
            val resolvedActiveId = when {
                rawSavedActiveId.isNotBlank() && rawSavedActiveId != "ALL" &&
                    (enabledLists.any { it.id == rawSavedActiveId } || (rawSavedActiveId == "PORTAL_MAIN" && sub.portalMainEnabled)) -> {
                    rawSavedActiveId
                }
                else -> {
                    enabledLists.firstOrNull()?.id
                        ?: (if (sub.portalMainEnabled) "PORTAL_MAIN" else "")
                }
            }
            saveLastActivePlaylistId(context, resolvedActiveId)

            // Load ONLY the resolved active playlist from SQLite (0ms multi-playlist memory bloat!)
            val (dbLive, dbMovies, dbSeries) = runCatching {
                sqliteDb.loadPlaylistCatalogSnapshot(resolvedActiveId)
            }.getOrDefault(Triple(emptyList(), emptyList(), emptyList()))

            val prefs = PlayerPreferences(
                appLanguage = runCatching {
                    AppUiLanguage.valueOf(pObj.optString("appLanguage", AppUiLanguage.AR.name))
                }.getOrDefault(AppUiLanguage.AR),
                playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                activePlaylistFilterId = resolvedActiveId,
                playlistOrderIds = parseStringList(pObj.optJSONArray("playlistOrderIds")),
                bufferProfile = runCatching {
                    BufferProfile.valueOf(pObj.optString("bufferProfile", BufferProfile.XPOLA_ZERO_LAG.name))
                }.getOrDefault(BufferProfile.XPOLA_ZERO_LAG),
                resizeMode = runCatching {
                    VideoResizeMode.valueOf(pObj.optString("resizeMode", VideoResizeMode.FILL.name))
                }.getOrDefault(VideoResizeMode.FILL),
                thumbnailCacheLimit = thumbLimit,
                autoReconnect = pObj.optBoolean("autoReconnect", true),
                maxReconnectRetries = pObj.optInt("maxReconnectRetries", 6).coerceIn(1, 15),
                preferSoftwareDecoder = pObj.optBoolean("preferSoftwareDecoder", false),
                useGridLayout = pObj.optBoolean("useGridLayout", true),
                showBouquetSidebar = pObj.optBoolean("showBouquetSidebar", true),
                showScreenCodeOverlay = pObj.optBoolean("showScreenCodeOverlay", true),
                customBouquetOrder = parseStringList(pObj.optJSONArray("customBouquetOrder")),
                hiddenBouquets = parseStringList(pObj.optJSONArray("hiddenBouquets")).toSet(),
                customChannelOrder = parseStringList(pObj.optJSONArray("customChannelOrder")),
                uiLayoutStyle = runCatching {
                    val rawStyle = pObj.optString("uiLayoutStyle", UiLayoutStyle.CLASSIC_LIVE_PRO.name)
                    UiLayoutStyle.valueOf(rawStyle)
                }.getOrDefault(UiLayoutStyle.CLASSIC_LIVE_PRO),
                lowEndDeviceMode = pObj.optBoolean("lowEndDeviceMode", false),
                showNetworkSpeed = pObj.optBoolean("showNetworkSpeed", true),
                playerEngine = runCatching {
                    InternalPlayerEngine.valueOf(pObj.optString("playerEngine", InternalPlayerEngine.AUTO_HYBRID.name))
                }.getOrDefault(InternalPlayerEngine.AUTO_HYBRID),
                vpnConnectionMode = VpnConnectionMode.WIREGUARD_FULL,
                vpnPermanentDisable = false,
                vpnAutoStartOnLaunch = true
            )
            val vodProgressMap = parseVodWatchProgressMap(root.optJSONArray("vodWatchProgress"))

            TiviMateDiagnostics.logEvent(
                "⚡ سحب محلي فوري للقائمة [$resolvedActiveId]: (${dbLive.size} قناة • ${dbMovies.size} فيلم • ${dbSeries.size} مسلسل)"
            )

            PersistedAppState(
                deviceId = finalDeviceId,
                deviceName = hwName,
                activationCode = savedActivationCode,
                xtreamPlaylists = xtreamPlaylists,
                cachedChannels = dbLive,
                cachedMovies = dbMovies,
                cachedSeries = dbSeries,
                favorites = favs,
                history = hist,
                subscription = sub,
                preferences = prefs,
                vodWatchProgress = vodProgressMap,
                storedPlaylistCounts = storedCountsMap
            )
        } catch (_: Throwable) {
            val fallbackId = sqlPlaylists.firstOrNull()?.id ?: "PORTAL_MAIN"
            val (dbLive, dbMovies, dbSeries) = runCatching {
                sqliteDb.loadPlaylistCatalogSnapshot(fallbackId)
            }.getOrDefault(Triple(emptyList(), emptyList(), emptyList()))
            PersistedAppState(
                deviceId = hardwareId,
                deviceName = hwName,
                activationCode = "",
                xtreamPlaylists = sqlPlaylists,
                cachedChannels = dbLive,
                cachedMovies = dbMovies,
                cachedSeries = dbSeries,
                favorites = emptyList(),
                history = emptyList(),
                subscription = SubscriptionInfo(active = true, isExpired = false, daysLeft = 30, reason = "ok"),
                preferences = PlayerPreferences(
                    playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                    activePlaylistFilterId = fallbackId,
                    lowEndDeviceMode = false,
                    preferSoftwareDecoder = false,
                    bufferProfile = BufferProfile.XPOLA_ZERO_LAG
                ),
                vodWatchProgress = emptyMap(),
                storedPlaylistCounts = storedCountsMap
            )
        }
    }

    private fun parseXtreamConfig(xObj: JSONObject, index: Int): XtreamAccountConfig {
        val rawName = xObj.optString("serverName", "قائمة ${index + 1}")
        val rawUrl = xObj.optString("serverUrl", "").trim()
        val resolvedUrl = if (rawUrl.isBlank() || (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true) && !rawUrl.contains("."))) {
            KnownIptvServersCatalog.resolveHostByServerName(rawUrl.ifBlank { rawName }).ifBlank { rawUrl }
        } else rawUrl
        val resolvedName = if (rawName.isBlank() || rawName.startsWith("قائمة")) {
            KnownIptvServersCatalog.detectServerNameByHost(resolvedUrl).ifBlank { rawName }
        } else rawName
        return XtreamAccountConfig(
            id = xObj.optString("id", UUID.randomUUID().toString().take(8)),
            serverName = resolvedName,
            serverUrl = resolvedUrl,
            username = xObj.optString("username", ""),
            password = xObj.optString("password", ""),
            m3uUrl = xObj.optString("m3uUrl", ""),
            enabled = xObj.optBoolean("enabled", true),
            isRemoteFromPortal = xObj.optBoolean("isRemoteFromPortal", false),
            orderIndex = xObj.optInt("orderIndex", index),
            statusNote = xObj.optString("statusNote", "جاهز للتحديث"),
            liveCount = xObj.optInt("liveCount", 0),
            movieCount = xObj.optInt("movieCount", 0),
            seriesCount = xObj.optInt("seriesCount", 0),
            expDate = xObj.optString("expDate", ""),
            lastSyncTimestamp = xObj.optLong("lastSyncTimestamp", 0L),
            lastCatalogEtag = xObj.optString("lastCatalogEtag", "")
        )
    }

    /**
     * Persists user preferences, favorites, history, and account configurations.
     */
    suspend fun saveState(
        context: Context,
        state: PersistedAppState,
        saveCatalog: Boolean = false
    ) = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            try {
                FluxBitmapCache.maxDiskCacheBytes = state.preferences.thumbnailCacheLimit.maxBytes
                val root = JSONObject()
                root.put("deviceId", state.deviceId)
                root.put("deviceName", state.deviceName)
                root.put("activationCode", state.activationCode)

                val xArr = JSONArray()
                state.xtreamPlaylists.forEachIndexed { idx, x ->
                    xArr.put(
                        JSONObject().apply {
                            put("id", x.id)
                            put("serverName", x.serverName)
                            put("serverUrl", x.serverUrl)
                            put("username", x.username)
                            put("password", x.password)
                            put("m3uUrl", x.m3uUrl)
                            put("enabled", x.enabled)
                            put("isRemoteFromPortal", x.isRemoteFromPortal)
                            put("orderIndex", idx)
                            put("statusNote", x.statusNote)
                            put("liveCount", x.liveCount)
                            put("movieCount", x.movieCount)
                            put("seriesCount", x.seriesCount)
                            put("expDate", x.expDate)
                            put("lastSyncTimestamp", x.lastSyncTimestamp)
                            put("lastCatalogEtag", x.lastCatalogEtag)
                        }
                    )
                }
                root.put("xtreamPlaylists", xArr)
                root.put("favorites", serializeChannelList(state.favorites.take(5000)))
                root.put("history", serializeChannelList(state.history.take(MAX_HISTORY)))

                state.subscription?.let { sub ->
                    root.put(
                        "subscription",
                        JSONObject().apply {
                            put("active", sub.active)
                            put("isExpired", sub.isExpired)
                            put("isTrial", sub.isTrial)
                            put("reason", sub.reason)
                            put("expireDate", sub.expireDate)
                            put("daysLeft", sub.daysLeft)
                            put("deviceName", sub.deviceName)
                            put("message", sub.message)
                            put("portalMainEnabled", sub.portalMainEnabled)
                        }
                    )
                }

                val p = state.preferences
                root.put(
                    "preferences",
                    JSONObject().apply {
                        put("appLanguage", p.appLanguage.name)
                        put("playlistDisplayMode", PlaylistDisplayMode.SEPARATE_LISTS.name)
                        put("activePlaylistFilterId", p.activePlaylistFilterId.takeIf { it != "ALL" && it.isNotBlank() } ?: "PORTAL_MAIN")
                        put("playlistOrderIds", JSONArray(p.playlistOrderIds))
                        put("bufferProfile", p.bufferProfile.name)
                        put("resizeMode", p.resizeMode.name)
                        put("thumbnailCacheLimit", p.thumbnailCacheLimit.name)
                        put("autoReconnect", p.autoReconnect)
                        put("maxReconnectRetries", p.maxReconnectRetries)
                        put("preferSoftwareDecoder", p.preferSoftwareDecoder)
                        put("useGridLayout", p.useGridLayout)
                        put("showBouquetSidebar", p.showBouquetSidebar)
                        put("showScreenCodeOverlay", p.showScreenCodeOverlay)
                        put("customBouquetOrder", JSONArray(p.customBouquetOrder))
                        put("hiddenBouquets", JSONArray(p.hiddenBouquets.toList()))
                        put("customChannelOrder", JSONArray(p.customChannelOrder.take(5000)))
                        put("uiLayoutStyle", p.uiLayoutStyle.name)
                        put("lowEndDeviceMode", p.lowEndDeviceMode)
                        put("showNetworkSpeed", p.showNetworkSpeed)
                        put("playerEngine", p.playerEngine.name)
                        put("playerEngineHybridPrimaryExoV29", true)
                        put("vpnConnectionMode", p.vpnConnectionMode.name)
                        put("vpnPermanentDisable", p.vpnPermanentDisable)
                        put("vpnAutoStartOnLaunch", p.vpnAutoStartOnLaunch)
                    }
                )
                root.put("vodWatchProgress", serializeVodWatchProgressMap(state.vodWatchProgress))

                val tmpFile = File(context.filesDir, "$FILE_NAME.tmp")
                val finalFile = File(context.filesDir, FILE_NAME)
                tmpFile.writeText(root.toString(), Charsets.UTF_8)
                if (!tmpFile.renameTo(finalFile)) {
                    tmpFile.copyTo(finalFile, overwrite = true)
                    tmpFile.delete()
                }

                if (saveCatalog) {
                    runCatching { TiviMateLocalDatabase.getInstance(context).checkpointWalNow() }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotBlank()) list.add(s)
        }
        return list
    }

    private fun parseChannelList(arr: JSONArray?, defaultType: StreamContentType): List<TvChannel> {
        if (arr == null) return emptyList()
        val list = ArrayList<TvChannel>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            val url = o.optString("url")
            if (name.isBlank() || url.isBlank()) continue
            val rawGroup = o.optString("group", "عام")
            val typeStr = o.optString("contentType", defaultType.name)
            val contentType = runCatching { StreamContentType.valueOf(typeStr) }.getOrDefault(defaultType)
            list.add(
                TvChannel(
                    id = o.optInt("id", i + 1),
                    number = o.optInt("number", i + 1),
                    name = name,
                    url = url,
                    group = if (rawGroup.isNotBlank()) rawGroup else "عام",
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
            )
        }
        return list
    }

    private fun serializeChannelList(list: List<TvChannel>): JSONArray {
        val arr = JSONArray()
        list.forEach { ch ->
            arr.put(
                JSONObject().apply {
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
                    put("plot", if (ch.plot.length > 180) ch.plot.take(180) else ch.plot)
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
                }
            )
        }
        return arr
    }

    private fun parseVodWatchProgressMap(arr: JSONArray?): Map<String, VodWatchProgress> {
        if (arr == null) return emptyMap()
        val map = LinkedHashMap<String, VodWatchProgress>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("itemKey", o.optString("stableKey", "")).trim()
            if (key.isBlank()) continue
            val pos = o.optLong("positionMs", 0L)
            val dur = o.optLong("durationMs", 0L)
            if (pos <= 0L) continue
            map[key] = VodWatchProgress(
                itemKey = key,
                title = o.optString("title", ""),
                positionMs = pos,
                durationMs = dur,
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }
        return map
    }

    private fun serializeVodWatchProgressMap(map: Map<String, VodWatchProgress>): JSONArray {
        val arr = JSONArray()
        map.values
            .sortedByDescending { it.updatedAt }
            .take(600)
            .forEach { prog ->
                arr.put(
                    JSONObject().apply {
                        put("itemKey", prog.resolvedKey)
                        put("stableKey", prog.resolvedKey)
                        put("title", prog.title)
                        put("positionMs", prog.positionMs)
                        put("durationMs", prog.durationMs)
                        put("updatedAt", prog.updatedAt)
                    }
                )
            }
        return arr
    }
}
