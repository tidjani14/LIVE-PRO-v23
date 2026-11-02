package com.tvflux.pro

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.LayoutDirection
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

const val APP_BRAND_NAME = "LIVE PRO"
val APP_LOGO_URL: String = listOf("https://", "e.top4top.io", "/p_3941ca8xe1.jpg").joinToString("")
const val ALI_SAT_PROVIDER_NAME = "علي سات (Ali Sat)"
const val ALI_SAT_PHONE_NUMBER = "00213697372480"
const val FAVORITES_BOUQUET_KEY = "__FAV_BOUQUET__"

/**
 * Built-in Universal IPTV Server Auto-Discovery Catalog (v20.0):
 * Synchronized with server_portal/data.txt so the Android app automatically recognizes all major
 * IPTV / Xtream servers by Server Name or Host URL and provides instant multi-host failover.
 */
object KnownIptvServersCatalog {
    private val dynamicServers = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()

    fun registerRemoteServers(remoteList: List<Pair<String, String>>) {
        // Isolation: never adopt another customer's custom label for a shared host URL.
        // Only register the URL for discovery/failover under a neutral or official brand name.
        val existingUrls = (SERVER_MAP + dynamicServers).map { it.second.lowercase(java.util.Locale.ROOT).removeSuffix("/") }.toHashSet()
        val officialByUrl = SERVER_MAP.associate { (n, u) ->
            u.lowercase(java.util.Locale.ROOT).removeSuffix("/") to n
        }
        for ((name, url) in remoteList) {
            val cleanUrl = url.trim().removeSuffix("/")
            val low = cleanUrl.lowercase(java.util.Locale.ROOT)
            if (cleanUrl.isNotBlank() && low !in existingUrls) {
                val official = officialByUrl[low]
                val safeName = official ?: "VIP Server"
                dynamicServers.add(safeName to cleanUrl)
                existingUrls.add(low)
            }
        }
    }

    fun getAllServers(): List<Pair<String, String>> = dynamicServers + SERVER_MAP

    val SERVER_MAP: List<Pair<String, String>> = listOf(
        "Temu VIP" to "http://tmasta.com",
        "King 365" to "http://179.61.217.72",
        "Nexon" to "http://serine-tv.xyz",
        "LIVE PRO VIP 4K" to "http://tvflux4k.com:8080",
        "IRON PRO MAX" to "http://line.iron-ott.com:80",
        "IRON TV PRO" to "http://irontvpro.com:8080",
        "KING 365 4K" to "http://line.king365tv.com:8080",
        "KING 4K OTT" to "http://king4k-ott.com:8080",
        "TREX 4K OTT" to "http://line.trex-ott.com:80",
        "TREX VIP" to "http://trex-panel.com:8080",
        "DINO IPTV" to "http://line.dino-iptv.com:8080",
        "DINO PLUS" to "http://dino-ott.net:8080",
        "STRONG 8K UHD" to "http://line.strong8k.com:8080",
        "STRONG 4K VIP" to "http://strong4k.me:8080",
        "MEGA OTT PRO" to "http://line.megaott.net:80",
        "MEGA VIP 4K" to "http://mega-ott.com:8080",
        "CRYSTAL OTT" to "http://line.crystal-ott.com:8080",
        "CRYSTAL VIP" to "http://crystal-tv.net:8080",
        "MAGNUM 4K" to "http://magnum-ott.net:80",
        "GOLD TV VIP" to "http://line.goldtv.cc:8080",
        "ATLAS PRO ONTV" to "http://atlas-ontv.com:8080",
        "ATLAS VIP 4K" to "http://line.atlaspro.tv:8080",
        "LYNX IPTV 4K" to "http://lynx-iptv.net:8080",
        "SANSAT PRO" to "http://sansat-ott.com:8080",
        "GOOL PRO TV" to "http://gool-tv.com:8080",
        "STAR SHARE 4K" to "http://starshare.live:8080",
        "STAR 4K OTT" to "http://line.starshare.org:8080",
        "LIONZ TV VIP" to "http://lionz-tv.com:8080",
        "ZENITH 4K" to "http://zenith-ott.com:8080",
        "COBRA ULTRA" to "http://cobra-ott.com:8080",
        "FALCON PRO 4K" to "http://falcon-iptv.net:8080",
        "SPIDER OTT VIP" to "http://spider-ott.com:8080",
        "B1G OTT 4K" to "http://line.b1g-ott.com:8080",
        "EAGLE 4K VIP" to "http://line.eagle4k.cc:8080",
        "MAX OTT PRO" to "http://line.maxott.net:8080",
        "ROYAL 4K TV" to "http://royal4k-ott.com:8080",
        "AROMA VIP 4K" to "http://aroma-ott.com:8080",
        "DIAMOND 4K OTT" to "http://diamond-ott.net:8080",
        "PLATINUM IPTV" to "http://platinum-ott.com:8080",
        "NOVA 4K OTT" to "http://nova-iptv.net:8080",
        "INFINITY 4K" to "http://infinity-ott.com:8080",
        "MATADOR PRO" to "http://matador-ott.com:8080",
        "HAWK 4K VIP" to "http://hawk-ott.net:8080"
    )

    fun resolveHostByServerName(serverName: String): String {
        val clean = serverName.trim().lowercase(java.util.Locale.ROOT)
        if (clean.isBlank()) return ""
        // 1. Exact case-insensitive match
        getAllServers().firstOrNull { it.first.lowercase(java.util.Locale.ROOT) == clean }?.let { return it.second }
        // 2. Partial brand keyword match (e.g. "temu", "king 365", "nexon", "iron", "trex", "strong", "dino", "crystal", "atlas", "star", "cobra", "spider", "falcon")
        getAllServers().firstOrNull { (name, _) ->
            val lowName = name.lowercase(java.util.Locale.ROOT)
            clean.contains(lowName) || lowName.contains(clean)
        }?.let { return it.second }
        return ""
    }

    fun detectServerNameByHost(hostUrl: String): String {
        val cleanHost = hostUrl.trim().lowercase(java.util.Locale.ROOT)
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
        if (cleanHost.isBlank()) return ""
        // Official catalog first only — prevents cross-customer custom name collision on same domain
        fun matchIn(list: List<Pair<String, String>>): String {
            return list.firstOrNull { (_, url) ->
                val uHost = url.lowercase(java.util.Locale.ROOT)
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .substringBefore("/")
                    .substringBefore(":")
                uHost == cleanHost || (uHost.isNotBlank() && (cleanHost == uHost))
            }?.first.orEmpty()
        }
        matchIn(SERVER_MAP).takeIf { it.isNotBlank() }?.let { return it }
        return matchIn(dynamicServers)
    }

    fun findAlternativeHostsForAccount(serverName: String, currentUrl: String): List<String> {
        val candidates = LinkedHashSet<String>()
        val lowName = serverName.trim().lowercase(java.util.Locale.ROOT)
        if (lowName.isNotBlank()) {
            val brandToken = lowName.split(" ", "-", "_").firstOrNull { it.length >= 3 }.orEmpty()
            for ((name, url) in getAllServers()) {
                val nLow = name.lowercase(java.util.Locale.ROOT)
                if (nLow == lowName || nLow.contains(lowName) || lowName.contains(nLow) ||
                    (brandToken.isNotBlank() && nLow.contains(brandToken))
                ) {
                    candidates.add(url)
                }
            }
        }
        if (currentUrl.isNotBlank()) {
            val detectedName = detectServerNameByHost(currentUrl)
            if (detectedName.isNotBlank()) {
                val brandToken = detectedName.lowercase(java.util.Locale.ROOT).split(" ").firstOrNull { it.length >= 3 }.orEmpty()
                for ((name, url) in getAllServers()) {
                    if (brandToken.isNotBlank() && name.lowercase(java.util.Locale.ROOT).contains(brandToken)) {
                        candidates.add(url)
                    }
                }
            }
        }
        return candidates.toList()
    }
}

/**
 * Authentic IBO Player / IBO Player Pro Profile Spoofer (v20.0):
 * Spoofs exact IBO Player HTTP headers, User-Agent, Origin, Referer, and Identity encoding
 * across all Xtream API (player_api.php, panel_api.php, get.php) and M3U requests, while using
 * clean redirect-safe headers on video streams so strict IPTV servers (Temu VIP / tmasta.com,
 * King 365, Iron, Nexon, etc.) work seamlessly on both Android TV and Phone.
 */
object IboPlayerProfileSpoofer {
    const val PRIMARY_IBO_USER_AGENT =
        "IBOPlayer/1.0 (Linux; Android 9; MI 8 Lite) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/88.0.4324.181 Mobile Safari/537.36"

    val IBO_USER_AGENTS = listOf(
        PRIMARY_IBO_USER_AGENT,
        "IBOPlayerPro/3.9 (Linux; Android 11; Mi Box S) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/96.0.4664.45 Safari/537.36",
        "IBOPlayer/3.8 (Android; SmartTV)",
        "okhttp/4.9.3",
        "IPTVSmartersPlayer/6.0 (IBOPlayer/1.0)",
        "VLC/3.0.20 LibVLC/3.0.20"
    )

    fun extractServerOrigin(urlOrBase: String): String {
        val clean = urlOrBase.trim()
        if (!clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            return ""
        }
        return runCatching {
            val uri = java.net.URI(clean)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: return ""
            val portPart = if (uri.port > 0) ":${uri.port}" else ""
            "$scheme://$host$portPart"
        }.getOrDefault("")
    }

    fun applyIboApiHeaders(
        builder: okhttp3.Request.Builder,
        targetUrl: String,
        serverBaseUrl: String = "",
        userAgent: String = PRIMARY_IBO_USER_AGENT,
        useIdentityEncoding: Boolean = true
    ): okhttp3.Request.Builder {
        val origin = serverBaseUrl.trim().removeSuffix("/").ifBlank { extractServerOrigin(targetUrl) }
        builder.header("User-Agent", userAgent)
        builder.header("Accept", "application/json, text/plain, */*")
        builder.header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
        if (useIdentityEncoding) {
            builder.header("Accept-Encoding", "identity")
        }
        builder.header("Connection", "keep-alive")
        builder.header("X-Requested-With", "com.ibo.player")
        if (origin.isNotBlank()) {
            builder.header("Origin", origin)
            builder.header("Referer", "$origin/")
        }
        return builder
    }

    val STREAM_PLAYBACK_USER_AGENTS = listOf(
        "IBOPlayer/1.0 (Linux; Android 11; TV)",
        "VLC/3.0.20 LibVLC/3.0.20",
        "IPTVSmartersPlayer",
        "Lavf/60.16.100",
        "ExoPlayerLib/2.19.1 (Linux; Android 12) ExoPlayer",
        PRIMARY_IBO_USER_AGENT
    )

    fun buildStreamHeadersMap(
        targetUrl: String,
        serverBaseUrl: String = "",
        customReferer: String? = null,
        customOrigin: String? = null,
        customCookie: String? = null,
        isLiveManifest: Boolean = false
    ): MutableMap<String, String> {
        val headers = mutableMapOf(
            "Connection" to "keep-alive",
            "Accept" to "*/*"
        )
        if (!customReferer.isNullOrBlank()) headers["Referer"] = customReferer.trim()
        if (!customOrigin.isNullOrBlank()) headers["Origin"] = customOrigin.trim()
        if (!customCookie.isNullOrBlank()) headers["Cookie"] = customCookie.trim()
        return headers
    }
}

enum class AppUiLanguage(
    val code: String,
    val nativeLabel: String,
    val layoutDirection: LayoutDirection
) {
    AR("ar", "العربية", LayoutDirection.Rtl),
    FR("fr", "Français", LayoutDirection.Ltr),
    EN("en", "English", LayoutDirection.Ltr)
}

object AdultContentFilter {
    private val adultWordRegex = Regex(
        "(\\badult\\b|\\badults\\b|\\bxxx\\b|\\bporn\\b|\\bporno\\b|\\bsex\\b|\\berotic\\b|\\bnsfw\\b)",
        RegexOption.IGNORE_CASE
    )

    private fun checkSingleText(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s.contains("+18") || s.contains("18+") || s.contains("+21") || s.contains("21+") ||
            s.contains("إباح") || s.contains("اباح") || s.contains("للكبار")
        ) return true
        val low = s.lowercase(java.util.Locale.ROOT)
        if (low.contains("playboy") || low.contains("brazzers") || low.contains("xvideos") ||
            low.contains("xnxx") || low.contains("onlyfans") || low.contains("hentai") ||
            low.contains("porno") || low.contains("erotic") || low.contains("nsfw")
        ) return true
        if (low.contains("adult") || low.contains("xxx") || low.contains("porn") || low.contains("sex")) {
            return adultWordRegex.containsMatchIn(low)
        }
        return false
    }

    fun isAdultOr18Plus(name: String, group: String = ""): Boolean {
        return checkSingleText(name) || checkSingleText(group)
    }
}

object SmartM3uParser {
    data class ExtractedXtream(
        val serverUrl: String,
        val username: String,
        val password: String
    )

    fun tryExtractXtreamCredentials(rawInput: String): ExtractedXtream? {
        val text = rawInput.trim()
        if (!text.startsWith("http://", ignoreCase = true) && !text.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return runCatching {
            val uri = java.net.URI(text)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: return null
            val portPart = if (uri.port > 0) ":${uri.port}" else ""
            val base = "$scheme://$host$portPart"

            val query = uri.rawQuery.orEmpty()
            if (query.isNotBlank()) {
                val params = query.split("&").associate {
                    val k = it.substringBefore("=").lowercase()
                    val v = URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
                    k to v
                }
                val u = (params["username"] ?: params["user"]).orEmpty().trim()
                val p = (params["password"] ?: params["pass"]).orEmpty().trim()
                if (u.isNotBlank() && p.isNotBlank()) {
                    return ExtractedXtream(base, u, p)
                }
            }

            val path = uri.path.orEmpty()
            val m = Regex("/(?:live|movie|series)/([^/]+)/([^/]+)", RegexOption.IGNORE_CASE).find(path)
            if (m != null) {
                val u = URLDecoder.decode(m.groupValues[1], "UTF-8").trim()
                val p = URLDecoder.decode(m.groupValues[2], "UTF-8").trim()
                if (u.isNotBlank() && p.isNotBlank()) {
                    return ExtractedXtream(base, u, p)
                }
            }
            null
        }.getOrNull()
    }
}

enum class DiagnosticAlertType {
    ACCOUNT_EXPIRED,
    AUTH_INVALID,
    INTERNET_ERROR,
    SERVER_OUTAGE
}

@Immutable
data class DiagnosticAlertInfo(
    val type: DiagnosticAlertType,
    val title: String,
    val message: String,
    val sourceName: String = "",
    val expDate: String = ""
)

object ExpiryAlertHelper {
    fun isDateExpiringSoon(dateStr: String, thresholdDays: Int = 5): Boolean {
        val clean = dateStr.trim()
        if (clean.isEmpty() || clean == "نشط" || clean == "Active") return false
        return runCatching {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val parsed = sdf.parse(clean.take(10)) ?: return false
            val diffMs = parsed.time + (23 * 3600 * 1000L) - System.currentTimeMillis()
            val days = (diffMs / (24 * 3600 * 1000L)).toInt()
            days <= thresholdDays
        }.getOrDefault(false)
    }

    fun isDateAlreadyExpired(dateStr: String): Boolean {
        val clean = dateStr.trim()
        if (clean.isEmpty() || clean == "نشط" || clean == "Active") return false
        return runCatching {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val parsed = sdf.parse(clean.take(10)) ?: return false
            val endOfDayMs = parsed.time + (23 * 3600 * 1000L) + (59 * 60 * 1000L)
            endOfDayMs < System.currentTimeMillis()
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    fun isInternetAvailable(context: android.content.Context): Boolean {
        return runCatching {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                val info = cm.activeNetworkInfo
                info != null && info.isConnected
            }
        }.getOrDefault(true)
    }
}

object AppStrings {
    fun tr(lang: AppUiLanguage, ar: String, fr: String, en: String): String = when (lang) {
        AppUiLanguage.AR -> ar
        AppUiLanguage.FR -> fr
        AppUiLanguage.EN -> en
    }

    fun favoritesLabel(lang: AppUiLanguage): String =
        tr(lang, "★ المفضلة", "★ Favoris", "★ Favorites")

    fun allBouquetsLabel(lang: AppUiLanguage): String =
        tr(lang, "الكل", "Tout", "All")
}

enum class MainTab(
    val titleAr: String,
    val titleFr: String,
    val titleEn: String
) {
    LIVE("القنوات", "Direct TV", "Live TV"),
    BOUQUETS("الباقات", "Bouquets", "Bouquets"),
    MOVIES("الأفلام", "Films", "Movies"),
    SERIES("المسلسلات", "Séries", "Series"),
    XTREAM("فضاء الزبون", "Espace Client", "Customer Space"),
    FAVORITES("المفضلة", "Favoris", "Favorites"),
    ACTIVATION("الصلاحية والتفعيل", "Validité & Code", "Validity & Code"),
    SETTINGS("الإعدادات", "Paramètres", "Settings");

    fun label(lang: AppUiLanguage): String = AppStrings.tr(lang, titleAr, titleFr, titleEn)
}

enum class StreamContentType {
    LIVE_TV,
    MOVIE,
    SERIES
}

enum class PlaylistDisplayMode(
    val titleAr: String,
    val titleFr: String,
    val titleEn: String
) {
    MERGE_ALL(
        "دمج جميع القوائم معاً (قوائم XTREAM أولاً)",
        "Fusionner toutes les listes (XTREAM en premier)",
        "Merge all playlists (XTREAM first)"
    ),
    SEPARATE_LISTS(
        "فصل كل قائمة على حدة (التبديل السريع)",
        "Séparer chaque liste individuellement",
        "Separate playlists (Quick Switch)"
    );

    fun label(lang: AppUiLanguage): String = AppStrings.tr(lang, titleAr, titleFr, titleEn)
}

enum class BufferProfile(
    val titleAr: String,
    val descAr: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val playbackBufferMs: Int,
    val rebufferMs: Int,
    val targetBufferBytes: Int,
    val backBufferMs: Int = 8_000
) {
    // PRODUCTION lightning-open: bufferForPlayback is the critical first-frame gate (not minBuffer)
    XPOLA_ZERO_LAG(
        "فتح خاطف 4K UHD (LIGHTNING: 180ms / Rebuffer 900ms / 2s→24s / 64MB — افتراضي)",
        "فتح فوري لقنوات 4K UHD و FHD مع Hardware Decoder ومخزن 64MB يمنع التقطيع نهائياً",
        2_000, 24_000, 180, 900, 64 * 1024 * 1024, 6_000
    ),
    TURBO_INSTANT(
        "فتح فوري أقصى (TURBO: 150ms / Rebuffer 750ms / 1.5s→18s / 56MB)",
        "أسرع فتح ممكن للبث المباشر وقنوات 4K — بدون أي انتظار إطلاقاً",
        1_500, 18_000, 150, 750, 56 * 1024 * 1024, 4_000
    ),
    BALANCED(
        "متوازن 4K UHD (300ms / Rebuffer 1200ms / 3s→28s / 64MB)",
        "توازن مثالي بين سرعة الفتح وثبات قنوات 4K HEVC 10-bit",
        3_000, 28_000, 300, 1_200, 64 * 1024 * 1024, 8_000
    ),
    ULTRA_STABLE(
        "ثبات أقصى 4K UHD (500ms / Rebuffer 1800ms / 5s→35s / 64MB)",
        "للشبكات المتذبذبة وقنوات 4K RAW ذات البيتريت العالي جداً",
        5_000, 35_000, 500, 1_800, 64 * 1024 * 1024, 10_000
    ),
    LITE_LOW_RAM(
        "أجهزة ضعيفة / TV Box (220ms / Rebuffer 1000ms / 2s→18s / 36MB)",
        "فتح سريع مع إدارة ذاكرة ذكية على شاشات Android TV",
        2_000, 18_000, 220, 1_000, 36 * 1024 * 1024, 4_000
    )
}

enum class InternalPlayerEngine(
    val titleAr: String,
    val titleFr: String,
    val titleEn: String,
    val badgeLabel: String
) {
    AUTO_HYBRID(
        "هجين ذكي كالبرق (IBO Player Pro Style: ExoPlayer أساسي فائق السرعة + VLC منقذ فوري لكل الصيغ)",
        "Hybride Intelligent (ExoPlayer Principal + VLC Secours Auto)",
        "Smart Hybrid (ExoPlayer Lightning + VLC All-Codec Rescue)",
        "Exo+VLC (IBO Style)"
    ),
    EXO_PLAYER(
        "المشغل الرئيسي ExoPlayer (Media3 عتادي فائق السرعة للبث المباشر)",
        "Lecteur Principal ExoPlayer (Media3 HW)",
        "Primary ExoPlayer (Media3 HW)",
        "ExoPlayer"
    ),
    VLC_PLAYER(
        "مشغل الطوارئ الشامل VLC (LibVLC لكل صيغ الفيديو والصوت دون استثناء)",
        "Lecteur Secours VLC (LibVLC Universel)",
        "Universal VLC Engine (LibVLC All Codecs)",
        "VLC All-Codec"
    );

    fun label(lang: AppUiLanguage): String = AppStrings.tr(lang, titleAr, titleFr, titleEn)
}

enum class UiLayoutStyle(
    val titleAr: String,
    val titleFr: String,
    val titleEn: String
) {
    CLASSIC_LIVE_PRO(
        "التصميم الكلاسيكي الاحترافي (الافتراضي)",
        "Design Classique Professionnel (Par défaut)",
        "Classic Pro Layout (Default)"
    ),
    MODERN_ROYAL(
        "التصميم المتطور الملكي (Modern Royal)",
        "Design Moderne Royal",
        "Modern Royal Layout"
    );

    fun label(lang: AppUiLanguage): String = AppStrings.tr(lang, titleAr, titleFr, titleEn)
}

@Immutable
data class VodWatchProgress(
    val itemKey: String = "",
    val stableKey: String = "",
    val title: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val resolvedKey: String
        get() = itemKey.ifBlank { stableKey }

    val progressFraction: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val isCompleted: Boolean
        get() = (durationMs > 0L && progressFraction >= 0.92f) || (durationMs <= 0L && positionMs > 45 * 60_000L)

    val hasResumePoint: Boolean
        get() = positionMs >= 3_000L && !isCompleted

    val formattedPosition: String
        get() = formatDurationMs(positionMs)

    val formattedDuration: String
        get() = formatDurationMs(durationMs)

    companion object {
        fun formatDurationMs(ms: Long): String {
            if (ms <= 0L) return "00:00"
            val totalSec = (ms / 1000L).coerceAtLeast(0L)
            val h = totalSec / 3600L
            val m = (totalSec % 3600L) / 60L
            val s = totalSec % 60L
            return if (h > 0L) {
                String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
            } else {
                String.format(java.util.Locale.US, "%02d:%02d", m, s)
            }
        }
    }
}

object DevicePerformanceDetector {
    @Volatile
    private var cachedIsWeak: Boolean? = null

    fun isLowRamOrWeakDevice(context: android.content.Context): Boolean {
        cachedIsWeak?.let { return it }
        val result = runCatching {
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val isLowRamFlag = am?.isLowRamDevice == true
            val memClass = am?.memoryClass ?: 192
            val memInfo = android.app.ActivityManager.MemoryInfo()
            am?.getMemoryInfo(memInfo)
            val totalRamMb = if (memInfo.totalMem > 0L) memInfo.totalMem / (1024L * 1024L) else 3072L
            isLowRamFlag || memClass <= 128 || totalRamMb <= 1400L
        }.getOrDefault(false)
        cachedIsWeak = result
        return result
    }
}

object NetworkSpeedMonitor {
    @Volatile private var lastTotalRxBytes: Long = -1L
    @Volatile private var lastUidRxBytes: Long = -1L
    @Volatile private var lastSampleTimeMs: Long = 0L
    @Volatile private var lastComputedLabel: String = "0 KB/s"
    @Volatile private var lastBytesPerSec: Long = 0L
    @Volatile private var peakBurstKbps: Long = 0L
    @Volatile private var peakBurstTimestampMs: Long = 0L
    @Volatile private var exoBandwidthKbps: Long = 0L
    @Volatile private var ewmaFastKbps: Double = 0.0
    @Volatile private var ewmaSlowKbps: Double = 0.0

    fun updateExoBandwidthEstimate(bitrateBitsPerSec: Long) {
        if (bitrateBitsPerSec > 0L) {
            val kbps = (bitrateBitsPerSec / 1000L).coerceAtLeast(0L)
            exoBandwidthKbps = kbps
            val d = kbps.toDouble()
            ewmaFastKbps = if (ewmaFastKbps <= 0.0) d else (0.55 * d + 0.45 * ewmaFastKbps)
            ewmaSlowKbps = if (ewmaSlowKbps <= 0.0) d else (0.20 * d + 0.80 * ewmaSlowKbps)
            val now = android.os.SystemClock.elapsedRealtime()
            if (kbps > peakBurstKbps || (now - peakBurstTimestampMs) > 25_000L) {
                peakBurstKbps = kbps
                peakBurstTimestampMs = now
            }
        }
    }

    fun sampleCurrentSpeedKbps(): Long {
        sampleCurrentSpeedLabel()
        val rawKbps = ((lastBytesPerSec * 8L) / 1024L).coerceAtLeast(0L)
        if (rawKbps > 0L) {
            val d = rawKbps.toDouble()
            ewmaFastKbps = if (ewmaFastKbps <= 0.0) d else (0.60 * d + 0.40 * ewmaFastKbps)
            ewmaSlowKbps = if (ewmaSlowKbps <= 0.0) d else (0.25 * d + 0.75 * ewmaSlowKbps)
        }
        return rawKbps
    }

    /**
     * Conservative speed for immediate step-down protection (YouTube/Facebook style dual-EWMA):
     * Uses the minimum of fast EWMA and slow EWMA when network drops suddenly.
     */
    fun conservativeSpeedKbps(): Long {
        val instant = sampleCurrentSpeedKbps()
        val fast = ewmaFastKbps.toLong()
        val slow = ewmaSlowKbps.toLong()
        val exo = exoBandwidthKbps
        val candidates = listOf(instant, fast, slow, exo).filter { it > 0L }
        return if (candidates.isEmpty()) 0L else candidates.minOrNull() ?: 0L
    }

    /**
     * Returns the true effective line capacity in Kbps by combining:
     * 1) Instant socket transfer rate & dual-EWMA filter
     * 2) Rolling 25-second peak burst speed (captures 100 Mbps fiber bursts during segment downloads)
     * 3) ExoPlayer's native sliding-percentile BandwidthMeter estimate
     */
    fun effectiveLineSpeedKbps(): Long {
        val currentKbps = sampleCurrentSpeedKbps()
        val now = android.os.SystemClock.elapsedRealtime()
        val validPeak = if (now - peakBurstTimestampMs <= 25_000L) peakBurstKbps else 0L
        return maxOf(currentKbps, validPeak, exoBandwidthKbps, ewmaFastKbps.toLong(), ewmaSlowKbps.toLong())
    }

    @Synchronized
    fun sampleCurrentSpeedLabel(): String {
        return runCatching {
            val now = android.os.SystemClock.elapsedRealtime()
            val rawElapsed = now - lastSampleTimeMs
            if (lastSampleTimeMs > 0L && rawElapsed < 160L) {
                return lastComputedLabel
            }
            val totalRx = android.net.TrafficStats.getTotalRxBytes()
            val uidRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
            val elapsedMs = rawElapsed.coerceAtLeast(1L)

            var deltaBytes = 0L
            if (lastSampleTimeMs > 0L && elapsedMs <= 12_000L) {
                val dTotal = if (totalRx > 0L && lastTotalRxBytes > 0L && totalRx >= lastTotalRxBytes) {
                    totalRx - lastTotalRxBytes
                } else 0L
                val dUid = if (uidRx > 0L && lastUidRxBytes > 0L && uidRx >= lastUidRxBytes) {
                    uidRx - lastUidRxBytes
                } else 0L
                deltaBytes = maxOf(dTotal, dUid)
            }

            lastTotalRxBytes = totalRx
            lastUidRxBytes = uidRx
            lastSampleTimeMs = now

            val bytesPerSec = (deltaBytes * 1000L) / elapsedMs
            lastBytesPerSec = bytesPerSec
            val instantKbps = (bytesPerSec * 8L) / 1024L
            if (instantKbps > peakBurstKbps || (now - peakBurstTimestampMs) > 25_000L) {
                peakBurstKbps = instantKbps
                peakBurstTimestampMs = now
            }
            val formatted = formatSpeed(bytesPerSec)
            lastComputedLabel = formatted
            formatted
        }.getOrDefault(lastComputedLabel)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 KB/s"
        val kb = bytesPerSec / 1024.0
        return if (kb >= 1024.0) {
            val mb = kb / 1024.0
            String.format(java.util.Locale.US, "%.1f MB/s", mb)
        } else {
            String.format(java.util.Locale.US, "%.0f KB/s", kb.coerceAtLeast(1.0))
        }
    }
}

enum class ThumbnailCacheLimitOption(
    val titleAr: String,
    val maxBytes: Long
) {
    MB_32("64 MB (متوازن للأجهزة الضعيفة)", 64L * 1024 * 1024),
    MB_64("128 MB (جودة فائقة الوضوح UHD — موصى به)", 128L * 1024 * 1024),
    MB_128("256 MB (أقصى دقة لشاشات 4K والمكتبات الضخمة)", 256L * 1024 * 1024)
}

enum class PlaybackSpeedOption(val labelAr: String, val speed: Float) {
    NORMAL("1.0x طبيعي (موصى به للبث الحي)", 1.0f),
    FAST_110("1.10x سريع خفيف", 1.10f),
    FAST_125("1.25x أسرع", 1.25f),
    FAST_150("1.50x فائق", 1.5f),
    FAST_200("2.00x مضاعف", 2.0f)
}

enum class VideoResizeMode(val titleAr: String, val exoMode: Int) {
    FILL("ملء الشاشة بالكامل (Fill)", 3),
    FIT("ملائم تلقائي (Fit)", 0),
    ZOOM("تكبير سينمائي (Zoom)", 4),
    FIXED_WIDTH("عرض ثابت", 1),
    FIXED_HEIGHT("ارتفاع ثابت", 2)
}

@Immutable
data class SeriesEpisode(
    val id: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val url: String,
    val durationLabel: String = "",
    val containerExtension: String = "mp4"
)

@Immutable
data class DetectedStreamSpec(
    val resolvedUrl: String,
    val containerFormat: String,
    val mimeType: String,
    val videoCodecHint: String,
    val audioCodecHint: String,
    val preferSoftwareDecoder: Boolean,
    val freshCookie: String? = null,
    val probeLatencyMs: Long = 0L,
    val diagnosticNote: String = ""
)

@Immutable
data class SyncProgressState(
    val isSyncing: Boolean = false,
    val sourceId: String = "",
    val sourceName: String = "",
    val phaseLabel: String = "",
    val progressPercent: Int = 0,
    val fetchedLiveCount: Int = 0,
    val fetchedMovieCount: Int = 0,
    val fetchedSeriesCount: Int = 0,
    val addedCount: Int = 0,
    val updatedCount: Int = 0,
    val removedCount: Int = 0,
    val isDifferential: Boolean = false,
    val lastError: String? = null
)

@Immutable
data class DatabasePerformanceMetrics(
    val totalChannelsInDb: Int = 0,
    val totalMoviesInDb: Int = 0,
    val totalSeriesInDb: Int = 0,
    val totalBouquetsInDb: Int = 0,
    val dbFileSizeMb: String = "0.0 MB",
    val thumbCacheSizeMb: String = "0.0 MB",
    val ramUsedMb: Int = 0,
    val ramMaxMb: Int = 0,
    val lastQueryTimeMs: Long = 0L,
    val lastSyncDurationMs: Long = 0L,
    val lastSyncSummary: String = "مكتبة SQLite المحلية جاهزة",
    val supportedVideoCodecs: List<String> = emptyList(),
    val supportedAudioCodecs: List<String> = emptyList(),
    val recentDiagnosticLogs: List<String> = emptyList()
)

/**
 * Multi-Quality Adaptive Engine (متعدد جودات الذكي اليدوي):
 * - يتيح للمستخدم يدوياً اختيار أي قناة من أي باقة وتحديد جودتها (4K / FHD / HD / SD / LOW)،
 *   ثم إضافة قنوات أخرى لجودات أخرى من اختياره (من نفس الباقة أو من باقات/سيرفرات أخرى) لتشكيل قناة واحدة متعددة الجودات.
 * - ذكي في التكيف التلقائي اللحظي مع:
 *   1) حركة وسرعة الإنترنت (Internet Traffic Fluctuation & Recovery)
 *   2) الثقل أو التقطيع من المصدر (Source Congestion / Buffering / Frame Freeze)
 *   3) التوقف المفاجئ من المصدر (Source Outage / HTTP Error / Offline Stream)
 */
enum class StreamQualityRank(
    val rank: Int,
    val labelAr: String,
    val labelEn: String,
    val resolutionTag: String,
    val minSpeedKbps: Long,
    val badgeDescAr: String
) {
    UHD_4K(5, "4K", "4K", "2160p 4K", 13_500L, "4K UHD فائق (2160p)"),
    FHD(4, "FHD", "FHD", "1080p FHD", 4_500L, "FHD 1080p عالي الدقة"),
    HD(3, "HD", "HD", "720p HD", 1_800L, "HD 720p متوازن"),
    SD(2, "SD", "SD", "480p SD", 750L, "SD 480p للإنترنت المتوسط"),
    LOW(1, "LOW", "LOW", "360p LOW", 300L, "LOW 360p للإنترنت الضعيف"),
    UNKNOWN(0, "Auto", "Auto", "Auto", 0L, "تلقائي ذكي (Auto ABR)");

    companion object {
        val selectableRanks: List<StreamQualityRank> = listOf(LOW, SD, HD, FHD, UHD_4K)

        private val regex4k = Regex("""(?i)(?:^|[\s|\-_\[\]()•:/])(4k|uhd|2160p?|8k|3840)(?:$|[\s|\-_\[\]()•:/])""")
        private val regexFhd = Regex("""(?i)(?:^|[\s|\-_\[\]()•:/])(fhd|full\s*hd|1080p?|1080i|hevc\s*fhd|raw)(?:$|[\s|\-_\[\]()•:/])""")
        private val regexHd = Regex("""(?i)(?:^|[\s|\-_\[\]()•:/])(hd\+?|720p?|hq|high)(?:$|[\s|\-_\[\]()•:/])""")
        private val regexSd = Regex("""(?i)(?:^|[\s|\-_\[\]()•:/])(sd|480p?|576p?|md|med)(?:$|[\s|\-_\[\]()•:/])""")
        private val regexLow = Regex("""(?i)(?:^|[\s|\-_\[\]()•:/])(low|lq|360p?|240p?|144p?|weak|eco|mobile|ضعيف|منخفض)(?:$|[\s|\-_\[\]()•:/])""")

        fun fromChannelName(name: String): StreamQualityRank {
            val n = name.lowercase(java.util.Locale.ROOT)
            return when {
                regex4k.containsMatchIn(n) || n.contains("4k") || n.contains("uhd") || n.contains("2160") -> UHD_4K
                regexFhd.containsMatchIn(n) || n.contains("fhd") || n.contains("1080") || n.contains("full hd") -> FHD
                regexLow.containsMatchIn(n) || n.contains("360p") || n.contains("240p") || n.contains("lq") || n.contains("ضعيف") -> LOW
                regexSd.containsMatchIn(n) || n.contains("480p") || n.contains("576p") -> SD
                regexHd.containsMatchIn(n) || n.contains("720p") -> HD
                else -> UNKNOWN
            }
        }

        fun suggestForChannel(name: String, group: String = ""): StreamQualityRank {
            val byName = fromChannelName(name)
            if (byName != UNKNOWN) return byName
            val byGroup = fromChannelName(group)
            if (byGroup != UNKNOWN) return byGroup
            return HD
        }
    }
}

enum class AdaptationReason(val labelAr: String) {
    INITIAL_SMART_PICK("اختيار ذكي تلقائي (Auto ABR)"),
    INTERNET_FLUCTUATION("تكيّف تلقائي مع ضعف الإنترنت"),
    INTERNET_RECOVERY("ترقية تلقائية مع تحسّن سرعة الإنترنت"),
    SOURCE_CONGESTION("تكيّف تلقائي بسبب ثقل أو تقطيع من المصدر"),
    SOURCE_OUTAGE("تحويل فوري تلقائي بسبب توقف المصدر"),
    MANUAL_USER_SELECT("اختيار يدوي من المشاهد")
}

/**
 * YouTube / Facebook Live Style Real-Time Adaptive Bitrate (ABR) & Multi-Source Failover Engine:
 * 1) يعتمد كلياً على اختيار المستخدم اليدوي للجودات عبر استوديو بناء القنوات المتعددة الجودات دون أي تخصيص مسبق لأي باقة.
 * 2) يحفظ ويشغّل القنوات المتعددة الجودات التي يجهزها المستخدم في باقة "متعدد جودات" مع التكيّف الذكي.
 * 3) Monitors both Internet Bandwidth (EWMA + ExoPlayer BandwidthMeter + Socket Rx) AND Source Stream Health
 *    (Buffer ahead ms, buffer velocity dBuffer/dt, decoded frame rate vs dropped frames, rebuffer frequency, HTTP errors)
 *    to switch qualities or servers seamlessly in real time without restarting PlayerScreen!
 */
object MultiQualityEngine {
    const val BOUQUET_NAME = "متعدد جودات"
    const val PLAYLIST_ID = "MULTI_QUALITY"

    @Immutable
    data class AbrTelemetrySnapshot(
        val activeQuality: StreamQualityRank = StreamQualityRank.UNKNOWN,
        val isAutoMode: Boolean = true,
        val measuredSpeedKbps: Long = 0L,
        val bufferAheadMs: Long = 0L,
        val bufferVelocityMsPerSec: Long = 0L,
        val sourceHealthScore: Int = 100,
        val activeSourceLabel: String = "",
        val lastSwitchReason: String = ""
    )

    @Immutable
    data class SavedQualitySource(
        val sourceId: String,
        val quality: StreamQualityRank,
        val stableKey: String,
        val channelName: String,
        val bouquetName: String,
        val playlistId: String = "PORTAL_MAIN",
        val playlistName: String = "الرئيسية",
        val url: String = "",
        val logo: String = "",
        val streamId: Int = 0,
        val streamBaseUrl: String = "",
        val streamUsername: String = "",
        val streamPassword: String = "",
        val containerExtension: String = "",
        val userAgent: String = "",
        val referer: String = "",
        val origin: String = "",
        val cookie: String = ""
    ) {
        fun toFallbackTvChannel(seqId: Int): TvChannel {
            return TvChannel(
                id = seqId,
                number = seqId,
                name = channelName.ifBlank { "مصدر ${quality.labelAr}" },
                url = url,
                group = bouquetName.ifBlank { BOUQUET_NAME },
                logo = logo.takeIf { it.isNotBlank() },
                userAgent = userAgent.takeIf { it.isNotBlank() },
                referer = referer.takeIf { it.isNotBlank() },
                origin = origin.takeIf { it.isNotBlank() },
                cookie = cookie.takeIf { it.isNotBlank() },
                contentType = StreamContentType.LIVE_TV,
                playlistId = playlistId.ifBlank { "PORTAL_MAIN" },
                playlistName = playlistName.ifBlank { "الرئيسية" },
                streamId = streamId,
                streamBaseUrl = streamBaseUrl,
                streamUsername = streamUsername,
                streamPassword = streamPassword,
                containerExtension = containerExtension
            )
        }

        companion object {
            fun fromChannel(
                channel: TvChannel,
                quality: StreamQualityRank,
                existingSourceId: String = ""
            ): SavedQualitySource {
                val sid = existingSourceId.ifBlank {
                    "SRC_${quality.name}_${channel.playlistId}_${if (channel.streamId > 0) channel.streamId else channel.url.hashCode().toUInt().toString(16)}"
                }
                return SavedQualitySource(
                    sourceId = sid,
                    quality = if (quality == StreamQualityRank.UNKNOWN) StreamQualityRank.HD else quality,
                    stableKey = channel.stableKey,
                    channelName = channel.name,
                    bouquetName = channel.group,
                    playlistId = channel.playlistId,
                    playlistName = channel.playlistName,
                    url = channel.url,
                    logo = channel.logo.orEmpty(),
                    streamId = channel.streamId,
                    streamBaseUrl = channel.streamBaseUrl,
                    streamUsername = channel.streamUsername,
                    streamPassword = channel.streamPassword,
                    containerExtension = channel.containerExtension,
                    userAgent = channel.userAgent.orEmpty(),
                    referer = channel.referer.orEmpty(),
                    origin = channel.origin.orEmpty(),
                    cookie = channel.cookie.orEmpty()
                )
            }
        }
    }

    @Immutable
    data class QualityVariant(
        val sourceId: String,
        val channel: TvChannel,
        val quality: StreamQualityRank,
        val bouquetLabel: String = channel.group,
        val serverLabel: String = channel.playlistName,
        val isAutoClustered: Boolean = false
    )

    data class UserMultiGroup(
        val id: String,
        val displayName: String,
        val sources: List<SavedQualitySource>,
        val slots: Map<String, String> = emptyMap()
    )

    data class SourceHealthRecord(
        var failCount: Int = 0,
        var congestionCount: Int = 0,
        var outageUntilMs: Long = 0L,
        var congestedUntilMs: Long = 0L,
        var upgradeHoldUntilMs: Long = 0L,
        var lastStableMs: Long = 0L,
        var healthScore: Int = 100,
        var lastReason: String = ""
    )

    data class AdaptiveSwitchDecision(
        val variant: QualityVariant,
        val reason: AdaptationReason,
        val bannerMessageAr: String,
        val switched: Boolean
    )

    private val variantStore = java.util.concurrent.ConcurrentHashMap<String, List<QualityVariant>>()
    private val autoClusterVariantsByStableKey = java.util.concurrent.ConcurrentHashMap<String, List<QualityVariant>>()
    private val autoClusterKeyByStableKey = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val groupKeyToGroupId = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val userGroups = java.util.concurrent.CopyOnWriteArrayList<UserMultiGroup>()
    private val healthRegistry = java.util.concurrent.ConcurrentHashMap<String, SourceHealthRecord>()
    private val manualQualityLock = java.util.concurrent.ConcurrentHashMap<String, StreamQualityRank>()
    private val manualVariantSourceLock = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lastStableQuality = java.util.concurrent.ConcurrentHashMap<String, StreamQualityRank>()
    private val lastActiveVariantId = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val channelQualityCeilingHold = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Long>>()
    /** Min time between non-outage quality switches (avoids bounce / annoyance). */
    private val lastQualitySwitchAtMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val telemetrySnapshots = java.util.concurrent.ConcurrentHashMap<String, AbrTelemetrySnapshot>()
    private const val MIN_SWITCH_COOLDOWN_MS = 35_000L
    private const val MIN_UPGRADE_COOLDOWN_MS = 90_000L
    private const val MIN_OUTAGE_SWITCH_COOLDOWN_MS = 4_000L
    @Volatile private var liveCatalogReference: List<TvChannel> = emptyList()
    @Volatile private var lastCatalogIdentityHash: Int = 0
    @Volatile var autoClusterInBouquetEnabled: Boolean = false
    @Volatile private var prefsLoaded = false

    fun updateLiveCatalogReference(allLive: List<TvChannel>) {
        liveCatalogReference = allLive
        val newHash = System.identityHashCode(allLive) xor allLive.size
        if (newHash != lastCatalogIdentityHash) {
            lastCatalogIdentityHash = newHash
            autoClusterVariantsByStableKey.clear()
        }
    }

    private fun resolveCanonicalClusterKey(stableKey: String): String {
        return autoClusterKeyByStableKey[stableKey] ?: stableKey
    }

    /**
     * Returns all quality variants for a channel:
     * - Checks manual/materialized multi-quality store first (O(1))
     * - Checks cached on-demand auto-cluster store (O(1))
     */
    fun variantsFor(stableKey: String): List<QualityVariant> {
        variantStore[stableKey]?.let { if (it.isNotEmpty()) return it }
        autoClusterVariantsByStableKey[stableKey]?.let { if (it.isNotEmpty()) return it }
        val canonical = autoClusterKeyByStableKey[stableKey]
        if (!canonical.isNullOrBlank()) {
            variantStore[canonical]?.let { if (it.isNotEmpty()) return it }
            autoClusterVariantsByStableKey[canonical]?.let { if (it.isNotEmpty()) return it }
        }
        return emptyList()
    }

    /**
     * Ultra-fast on-demand sibling variant discovery (<2ms for a single playing channel, 0ms cost during menu/playlist switching!).
     */
    fun variantsForChannel(channel: TvChannel): List<QualityVariant> {
        val byKey = variantsFor(channel.stableKey)
        if (byKey.isNotEmpty()) return byKey
        if (channel.contentType != StreamContentType.LIVE_TV) return emptyList()
        return discoverSiblingsOnDemand(channel)
    }

    fun hasAdaptiveVariants(channel: TvChannel): Boolean {
        return variantsForChannel(channel).size >= 2
    }

    fun getTelemetrySnapshot(stableKey: String): AbrTelemetrySnapshot? {
        val cKey = resolveCanonicalClusterKey(stableKey)
        return telemetrySnapshots[cKey] ?: telemetrySnapshots[stableKey]
    }

    fun updateTelemetrySnapshot(
        stableKey: String,
        activeVariant: QualityVariant?,
        measuredSpeedKbps: Long,
        bufferAheadMs: Long,
        bufferVelocityMsPerSec: Long,
        lastReason: String = ""
    ) {
        val cKey = resolveCanonicalClusterKey(stableKey)
        val isAuto = manualQualityLock[cKey] == null && manualQualityLock[stableKey] == null
        val score = activeVariant?.let { getVariantHealthScore(it) } ?: 100
        val prev = telemetrySnapshots[cKey]
        val snap = AbrTelemetrySnapshot(
            activeQuality = activeVariant?.quality ?: prev?.activeQuality ?: StreamQualityRank.HD,
            isAutoMode = isAuto,
            measuredSpeedKbps = measuredSpeedKbps,
            bufferAheadMs = bufferAheadMs,
            bufferVelocityMsPerSec = bufferVelocityMsPerSec,
            sourceHealthScore = score,
            activeSourceLabel = activeVariant?.bouquetLabel.orEmpty(),
            lastSwitchReason = lastReason.ifBlank { prev?.lastSwitchReason.orEmpty() }
        )
        telemetrySnapshots[cKey] = snap
        telemetrySnapshots[stableKey] = snap
    }

    fun findGroupIdForChannel(multiChannel: TvChannel): String? {
        groupKeyToGroupId[multiChannel.stableKey]?.let { return it }
        val cleanTitle = multiChannel.name.substringBefore(" •").trim()
        return userGroups.firstOrNull { it.displayName.equals(cleanTitle, ignoreCase = true) }?.id
    }

    fun getUserGroupById(groupId: String): UserMultiGroup? =
        userGroups.firstOrNull { it.id == groupId }

    fun listUserGroups(): List<UserMultiGroup> = userGroups.toList()

    fun cleanBaseChannelName(raw: String): String {
        return stripQualityTokens(raw).ifBlank { raw.trim() }
    }

    fun ensureLoaded(context: android.content.Context) {
        if (prefsLoaded) return
        synchronized(this) {
            if (prefsLoaded) return
            runCatching {
                val f = java.io.File(context.filesDir, "multi_quality_groups.json")
                if (!f.exists()) {
                    prefsLoaded = true
                    return
                }
                val root = org.json.JSONArray(f.readText())
                userGroups.clear()
                for (i in 0 until root.length()) {
                    val o = root.optJSONObject(i) ?: continue
                    val id = o.optString("id", "").trim()
                    if (id.isEmpty()) continue
                    val name = o.optString("displayName", "").trim().ifBlank { "قناة متعددة" }

                    val parsedSources = mutableListOf<SavedQualitySource>()
                    val srcArr = o.optJSONArray("sources")
                    if (srcArr != null) {
                        for (j in 0 until srcArr.length()) {
                            val so = srcArr.optJSONObject(j) ?: continue
                            val qName = so.optString("quality", "HD")
                            val q = runCatching { StreamQualityRank.valueOf(qName) }
                                .getOrDefault(StreamQualityRank.HD)
                            if (q == StreamQualityRank.UNKNOWN) continue
                            val sKey = so.optString("stableKey", "")
                            val u = so.optString("url", "")
                            if (sKey.isBlank() && u.isBlank()) continue
                            parsedSources.add(
                                SavedQualitySource(
                                    sourceId = so.optString("sourceId", "SRC_${j}_${q.name}"),
                                    quality = q,
                                    stableKey = sKey,
                                    channelName = so.optString("channelName", name),
                                    bouquetName = so.optString("bouquetName", ""),
                                    playlistId = so.optString("playlistId", "PORTAL_MAIN"),
                                    playlistName = so.optString("playlistName", "الرئيسية"),
                                    url = u,
                                    logo = so.optString("logo", ""),
                                    streamId = so.optInt("streamId", 0),
                                    streamBaseUrl = so.optString("streamBaseUrl", ""),
                                    streamUsername = so.optString("streamUsername", ""),
                                    streamPassword = so.optString("streamPassword", ""),
                                    containerExtension = so.optString("containerExtension", ""),
                                    userAgent = so.optString("userAgent", ""),
                                    referer = so.optString("referer", ""),
                                    origin = so.optString("origin", ""),
                                    cookie = so.optString("cookie", "")
                                )
                            )
                        }
                    }

                    val slotsObj = o.optJSONObject("slots") ?: org.json.JSONObject()
                    val slots = mutableMapOf<String, String>()
                    val keys = slotsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = slotsObj.optString(k, "")
                        if (v.isNotBlank()) slots[k] = v
                    }
                    if (parsedSources.isEmpty() && slots.isNotEmpty()) {
                        for ((qName, sKey) in slots) {
                            val q = runCatching { StreamQualityRank.valueOf(qName) }
                                .getOrDefault(StreamQualityRank.HD)
                            if (q != StreamQualityRank.UNKNOWN) {
                                parsedSources.add(
                                    SavedQualitySource(
                                        sourceId = "LEGACY_${q.name}_$sKey",
                                        quality = q,
                                        stableKey = sKey,
                                        channelName = name,
                                        bouquetName = ""
                                    )
                                )
                            }
                        }
                    }

                    if (parsedSources.isNotEmpty() || slots.isNotEmpty()) {
                        userGroups.add(UserMultiGroup(id, name, parsedSources, slots))
                    }
                }
            }
            prefsLoaded = true
        }
    }

    fun persist(context: android.content.Context) {
        runCatching {
            val arr = org.json.JSONArray()
            for (g in userGroups) {
                val o = org.json.JSONObject()
                o.put("id", g.id)
                o.put("displayName", g.displayName)
                val slotsObj = org.json.JSONObject()
                g.slots.forEach { (k, v) -> slotsObj.put(k, v) }
                for (s in g.sources) {
                    if (!slotsObj.has(s.quality.name)) {
                        slotsObj.put(s.quality.name, s.stableKey)
                    }
                }
                o.put("slots", slotsObj)

                val srcArr = org.json.JSONArray()
                for (s in g.sources) {
                    val so = org.json.JSONObject()
                    so.put("sourceId", s.sourceId)
                    so.put("quality", s.quality.name)
                    so.put("stableKey", s.stableKey)
                    so.put("channelName", s.channelName)
                    so.put("bouquetName", s.bouquetName)
                    so.put("playlistId", s.playlistId)
                    so.put("playlistName", s.playlistName)
                    so.put("url", s.url)
                    so.put("logo", s.logo)
                    so.put("streamId", s.streamId)
                    so.put("streamBaseUrl", s.streamBaseUrl)
                    so.put("streamUsername", s.streamUsername)
                    so.put("streamPassword", s.streamPassword)
                    so.put("containerExtension", s.containerExtension)
                    so.put("userAgent", s.userAgent)
                    so.put("referer", s.referer)
                    so.put("origin", s.origin)
                    so.put("cookie", s.cookie)
                    srcArr.put(so)
                }
                o.put("sources", srcArr)
                arr.put(o)
            }
            java.io.File(context.filesDir, "multi_quality_groups.json").writeText(arr.toString())
        }
    }

    fun upsertUserGroupWithSources(
        context: android.content.Context,
        displayName: String,
        sources: List<SavedQualitySource>,
        existingId: String? = null
    ): Boolean {
        val validSources = sources
            .filter { it.quality != StreamQualityRank.UNKNOWN && (it.url.isNotBlank() || it.stableKey.isNotBlank()) }
            .distinctBy { "${it.quality.name}_${it.stableKey}_${it.url}" }
            .sortedBy { it.quality.rank }
        if (validSources.size < 2) return false

        val id = existingId?.takeIf { it.isNotBlank() }
            ?: ("MQ_" + java.util.UUID.randomUUID().toString().take(8))
        val cleanName = displayName.trim().ifBlank {
            cleanBaseChannelName(validSources.first().channelName).ifBlank { "قناة متعددة الجودات" }
        }
        val legacySlots = LinkedHashMap<String, String>()
        for (s in validSources) {
            if (!legacySlots.containsKey(s.quality.name)) {
                legacySlots[s.quality.name] = s.stableKey
            }
        }
        val group = UserMultiGroup(
            id = id,
            displayName = cleanName,
            sources = validSources,
            slots = legacySlots
        )
        val existingIdx = userGroups.indexOfFirst { it.id == id }
        if (existingIdx >= 0) {
            userGroups[existingIdx] = group
        } else {
            userGroups.add(0, group)
        }
        persist(context)
        return true
    }

    fun upsertUserGroup(
        context: android.content.Context,
        displayName: String,
        slots: Map<StreamQualityRank, TvChannel>,
        existingId: String? = null
    ): Boolean {
        val sources = slots.mapNotNull { (q, ch) ->
            if (q == StreamQualityRank.UNKNOWN || ch.url.isBlank()) null
            else SavedQualitySource.fromChannel(ch, q)
        }
        return upsertUserGroupWithSources(context, displayName, sources, existingId)
    }

    fun addChannelToExistingGroup(
        context: android.content.Context,
        groupId: String,
        channel: TvChannel,
        quality: StreamQualityRank
    ): Boolean {
        val existing = userGroups.firstOrNull { it.id == groupId } ?: return false
        val newSrc = SavedQualitySource.fromChannel(channel, quality)
        val merged = (existing.sources + newSrc)
            .distinctBy { "${it.quality.name}_${it.stableKey}_${it.url}" }
            .sortedBy { it.quality.rank }
        return upsertUserGroupWithSources(context, existing.displayName, merged, existing.id)
    }

    fun deleteUserGroup(context: android.content.Context, groupId: String) {
        userGroups.removeAll { it.id == groupId }
        persist(context)
    }

    private val countryPrefixRegex = Regex("""(?i)^\s*(?:\[[^\]]{1,8}\]|\([^)]{1,8}\)|(?:ar|arab|arabic|fr|en|uk|us|de|es|it|tr|pt|nl|be|ch|ca|ru|pl|gr|ro|bg|ex-yu|vip|bein|osn|ssc|mbc|shahid|dstv|sky|canal|starz|tod|koora)\s*[:|\-_•]+)\s*""")
    private val qualityTokenRegex = Regex(
        """(?i)(?:^|[\s|\-_\[\]()•:/])(4k|8k|uhd|2160p?|fhd|full\s*hd|1080p?|1080i|720p?|480p?|576p?|360p?|240p?|144p?|hd\+?|sd|low|lq|md|med|hevc|h265|h\.265|x265|h264|h\.264|avc|50fps|60fps|raw|vip|backup|bk|bkp|alt|source\s*\d+|src\s*\d+|ضعيف|منخفض|احتياطي|جودة\s*عالية|جودة\s*متوسطة|جودة\s*ضعيفة)(?:$|[\s|\-_\[\]()•:/])"""
    )
    private val multiSpaceRegex = Regex("""\s+""")
    private val numberTokenRegex = Regex("""\b(\d{1,3})\b""")

    private fun stripQualityTokens(raw: String): String {
        var n = raw.trim()
        n = countryPrefixRegex.replace(n, " ")
        // Run twice to strip stacked quality tags like "HD HEVC 50FPS"
        n = qualityTokenRegex.replace(n, " ")
        n = qualityTokenRegex.replace(n, " ")
        return n.replace(multiSpaceRegex, " ")
            .trim()
            .trim { it <= ' ' || it == '-' || it == '|' || it == '•' || it == ':' || it == '[' || it == ']' || it == '(' || it == ')' || it == '/' || it == '_' }
    }

    /**
     * Source family helper (neutral across all channels; no pre-configured channel brands).
     */
    fun extractSourceFamily(name: String, group: String = ""): String = ""

    /**
     * Strictly manual user-selected multi-quality variants:
     * Only channels explicitly built by the user in the Multi-Quality Studio have multiple quality variants.
     */
    private fun discoverSiblingsOnDemand(target: TvChannel): List<QualityVariant> {
        return variantStore[target.stableKey].orEmpty()
    }

    /**
     * Zero-Latency Materialization (0.00ms when userGroups is empty!):
     * - Stores `allLive` reference in 0ms for on-demand playback ABR.
     * - Only allocates lookup maps if the user actually created manual Multi-Quality groups (`userGroups.isNotEmpty()`).
     */
    fun materializeUserGroups(allLive: List<TvChannel>): List<TvChannel> {
        updateLiveCatalogReference(allLive)
        variantStore.clear()
        groupKeyToGroupId.clear()

        // Fast-path: 0.00ms when no manual groups exist (zero HashMap allocations, zero loops over 60k channels!)
        if (userGroups.isEmpty()) return emptyList()

        val neededKeys = HashSet<String>()
        val neededStreamIds = HashSet<String>()
        val neededUrls = HashSet<String>()
        for (g in userGroups) {
            for (src in g.sources) {
                if (src.stableKey.isNotBlank()) neededKeys.add(src.stableKey)
                if (src.streamId > 0) neededStreamIds.add("${src.playlistId}_${src.streamId}")
                if (src.url.isNotBlank()) neededUrls.add(src.url.trim())
            }
            for (srcKey in g.slots.values) {
                if (srcKey.isNotBlank()) neededKeys.add(srcKey)
            }
        }

        val byKey = HashMap<String, TvChannel>(neededKeys.size * 2 + 16)
        val byStreamId = HashMap<String, TvChannel>(neededStreamIds.size * 2 + 16)
        val byUrl = HashMap<String, TvChannel>(neededUrls.size * 2 + 16)
        if (allLive.isNotEmpty() && (neededKeys.isNotEmpty() || neededStreamIds.isNotEmpty() || neededUrls.isNotEmpty())) {
            for (ch in allLive) {
                if (ch.playlistId == PLAYLIST_ID) continue
                if (ch.stableKey in neededKeys) byKey[ch.stableKey] = ch
                if (ch.streamId > 0) {
                    val sid = "${ch.playlistId}_${ch.streamId}"
                    if (sid in neededStreamIds) byStreamId[sid] = ch
                }
                if (ch.url.isNotBlank()) {
                    val u = ch.url.trim()
                    if (u in neededUrls) byUrl[u] = ch
                }
            }
        }

        val result = ArrayList<TvChannel>(userGroups.size)
        var seq = 900_000

        for (g in userGroups) {
            val ranked = mutableListOf<QualityVariant>()
            if (g.sources.isNotEmpty()) {
                for ((idx, src) in g.sources.withIndex()) {
                    if (src.quality == StreamQualityRank.UNKNOWN) continue
                    val resolvedCh = byKey[src.stableKey]
                        ?: (if (src.streamId > 0) byStreamId["${src.playlistId}_${src.streamId}"] else null)
                        ?: (if (src.url.isNotBlank()) byUrl[src.url.trim()] else null)
                        ?: (if (src.url.isNotBlank()) src.toFallbackTvChannel(seq + 100 + idx) else null)
                        ?: continue
                    ranked.add(
                        QualityVariant(
                            sourceId = src.sourceId.ifBlank { "${g.id}_${src.quality.name}_$idx" },
                            channel = resolvedCh,
                            quality = src.quality,
                            bouquetLabel = src.bouquetName.ifBlank { resolvedCh.group },
                            serverLabel = src.playlistName.ifBlank { resolvedCh.playlistName },
                            isAutoClustered = false
                        )
                    )
                }
            } else {
                var idx = 0
                for ((qName, srcKey) in g.slots) {
                    val q = runCatching { StreamQualityRank.valueOf(qName) }.getOrDefault(StreamQualityRank.UNKNOWN)
                    val srcCh = byKey[srcKey] ?: continue
                    if (q == StreamQualityRank.UNKNOWN) continue
                    ranked.add(
                        QualityVariant(
                            sourceId = "${g.id}_${q.name}_${idx++}",
                            channel = srcCh,
                            quality = q,
                            bouquetLabel = srcCh.group,
                            serverLabel = srcCh.playlistName,
                            isAutoClustered = false
                        )
                    )
                }
            }

            if (ranked.size < 2) continue
            ranked.sortBy { it.quality.rank }
            seq += 1
            val best = (ranked.maxByOrNull { it.quality.rank } ?: ranked.last()).channel
            val labels = ranked.map { it.quality.labelAr }.distinct().joinToString("/")
            val multi = best.copy(
                id = seq,
                number = seq,
                name = "${g.displayName} • [$labels]",
                group = BOUQUET_NAME,
                playlistId = PLAYLIST_ID,
                playlistName = BOUQUET_NAME,
                logo = ranked.mapNotNull { it.channel.logo?.takeIf { l -> l.isNotBlank() } }.firstOrNull() ?: best.logo
            )
            val frozenVariants = ranked.toList()
            variantStore[multi.stableKey] = frozenVariants
            variantStore["${PLAYLIST_ID}_GRP_${g.id}"] = frozenVariants
            groupKeyToGroupId[multi.stableKey] = g.id
            for (v in frozenVariants) {
                autoClusterVariantsByStableKey[v.channel.stableKey] = frozenVariants
                autoClusterKeyByStableKey[v.channel.stableKey] = multi.stableKey
            }
            result.add(multi)
        }

        return result
    }

    private fun healthKey(variant: QualityVariant): String =
        "${variant.sourceId}|${variant.channel.url.trim()}"

    fun getVariantHealthScore(variant: QualityVariant, nowMs: Long = System.currentTimeMillis()): Int {
        val rec = healthRegistry[healthKey(variant)] ?: return 100
        return when {
            nowMs < rec.outageUntilMs -> 10
            nowMs < rec.congestedUntilMs -> 45
            nowMs < rec.upgradeHoldUntilMs -> 72
            else -> rec.healthScore.coerceIn(60, 100)
        }
    }

    fun isVariantHealthy(variant: QualityVariant, nowMs: Long = System.currentTimeMillis()): Boolean {
        val rec = healthRegistry[healthKey(variant)] ?: return true
        return nowMs >= rec.outageUntilMs && nowMs >= rec.congestedUntilMs
    }

    fun isVariantEligibleForAutoUpgrade(variant: QualityVariant, nowMs: Long = System.currentTimeMillis()): Boolean {
        val rec = healthRegistry[healthKey(variant)] ?: return true
        return nowMs >= rec.outageUntilMs && nowMs >= rec.congestedUntilMs && nowMs >= rec.upgradeHoldUntilMs
    }

    fun isVariantOutage(variant: QualityVariant, nowMs: Long = System.currentTimeMillis()): Boolean {
        val rec = healthRegistry[healthKey(variant)] ?: return false
        return nowMs < rec.outageUntilMs
    }

    fun isVariantCongested(variant: QualityVariant, nowMs: Long = System.currentTimeMillis()): Boolean {
        val rec = healthRegistry[healthKey(variant)] ?: return false
        return nowMs < rec.congestedUntilMs
    }

    fun clearChannelHealthHistory(multiStableKey: String) {
        val cKey = resolveCanonicalClusterKey(multiStableKey)
        variantsFor(multiStableKey).forEach { v ->
            healthRegistry.remove(healthKey(v))
        }
        channelQualityCeilingHold.remove(cKey)
        channelQualityCeilingHold.remove(multiStableKey)
    }

    fun setManualQuality(multiStableKey: String, quality: StreamQualityRank?, specificSourceId: String? = null) {
        val cKey = resolveCanonicalClusterKey(multiStableKey)
        if (quality == null || quality == StreamQualityRank.UNKNOWN) {
            manualQualityLock.remove(cKey)
            manualQualityLock.remove(multiStableKey)
            manualVariantSourceLock.remove(cKey)
            manualVariantSourceLock.remove(multiStableKey)
            channelQualityCeilingHold.remove(cKey)
            channelQualityCeilingHold.remove(multiStableKey)
        } else {
            manualQualityLock[cKey] = quality
            manualQualityLock[multiStableKey] = quality
            if (!specificSourceId.isNullOrBlank()) {
                manualVariantSourceLock[cKey] = specificSourceId
                manualVariantSourceLock[multiStableKey] = specificSourceId
            } else {
                manualVariantSourceLock.remove(cKey)
                manualVariantSourceLock.remove(multiStableKey)
            }
        }
    }

    fun getManualQuality(multiStableKey: String): StreamQualityRank? {
        val cKey = resolveCanonicalClusterKey(multiStableKey)
        return manualQualityLock[cKey] ?: manualQualityLock[multiStableKey]
    }

    fun getActiveVariant(multiStableKey: String): QualityVariant? {
        val variants = variantsFor(multiStableKey)
        if (variants.isEmpty()) return null
        val cKey = resolveCanonicalClusterKey(multiStableKey)
        val activeId = lastActiveVariantId[cKey] ?: lastActiveVariantId[multiStableKey]
        if (!activeId.isNullOrBlank()) {
            variants.firstOrNull { it.sourceId == activeId }?.let { return it }
        }
        // If multiStableKey matches a specific member channel, return that member's variant unless Auto picked better
        variants.firstOrNull { it.channel.stableKey == multiStableKey }?.let { return it }
        return variants.firstOrNull()
    }

    fun setActiveVariant(multiStableKey: String, variant: QualityVariant) {
        val cKey = resolveCanonicalClusterKey(multiStableKey)
        lastActiveVariantId[cKey] = variant.sourceId
        lastActiveVariantId[multiStableKey] = variant.sourceId
    }

    fun availableQualities(multiStableKey: String): List<StreamQualityRank> {
        return variantsFor(multiStableKey).map { it.quality }.distinct().sortedBy { it.rank }
    }

    fun markStablePlayback(multiStableKey: String, variant: QualityVariant) {
        val now = System.currentTimeMillis()
        val cKey = resolveCanonicalClusterKey(multiStableKey)
        val rec = healthRegistry.getOrPut(healthKey(variant)) { SourceHealthRecord() }
        rec.lastStableMs = now
        rec.outageUntilMs = 0L
        rec.congestedUntilMs = 0L
        rec.healthScore = (rec.healthScore + 5).coerceAtMost(100)
        if (rec.congestionCount > 0) rec.congestionCount -= 1
        if (rec.failCount > 0) rec.failCount -= 1
        lastActiveVariantId[cKey] = variant.sourceId
        lastActiveVariantId[multiStableKey] = variant.sourceId
        if (variant.quality != StreamQualityRank.UNKNOWN) {
            lastStableQuality[cKey] = variant.quality
            lastStableQuality[multiStableKey] = variant.quality
        }
    }

    fun markStablePlayback(multiStableKey: String, quality: StreamQualityRank) {
        if (quality != StreamQualityRank.UNKNOWN) {
            val cKey = resolveCanonicalClusterKey(multiStableKey)
            lastStableQuality[cKey] = quality
            lastStableQuality[multiStableKey] = quality
        }
    }

    fun currentMeasuredSpeedKbps(): Long {
        val effective = NetworkSpeedMonitor.effectiveLineSpeedKbps()
        if (effective > 0L) return effective
        return parseSpeedKbps(NetworkSpeedMonitor.sampleCurrentSpeedLabel())
    }

    private fun networkCeilingRank(speedKbps: Long): Int {
        // Before any active stream download is measured (0..180 Kbps), start at FHD (or HD) so fast connections
        // open in high clarity and immediately adapt up or down within seconds.
        if (speedKbps <= 180L) return StreamQualityRank.FHD.rank
        return when {
            speedKbps >= 13_500L -> StreamQualityRank.UHD_4K.rank
            speedKbps >= 4_200L -> StreamQualityRank.FHD.rank
            speedKbps >= 1_750L -> StreamQualityRank.HD.rank
            speedKbps >= 680L -> StreamQualityRank.SD.rank
            else -> StreamQualityRank.LOW.rank
        }
    }

    fun pickBestVariant(
        multiChannel: TvChannel,
        preferQuality: StreamQualityRank? = null,
        forceDowngradeFrom: StreamQualityRank? = null
    ): QualityVariant? {
        val rawKey = multiChannel.stableKey
        val cKey = resolveCanonicalClusterKey(rawKey)
        val variants = variantsFor(rawKey)
        if (variants.isEmpty()) return null

        val now = System.currentTimeMillis()
        val healthyVariants = variants.filter { isVariantHealthy(it, now) }
        val pool = healthyVariants.ifEmpty {
            variants.sortedBy {
                val r = healthRegistry[healthKey(it)]
                maxOf(r?.outageUntilMs ?: 0L, r?.congestedUntilMs ?: 0L)
            }
        }

        // 1. Explicit manual lock
        val lockedSourceId = manualVariantSourceLock[cKey] ?: manualVariantSourceLock[rawKey]
        val manual = preferQuality ?: manualQualityLock[cKey] ?: manualQualityLock[rawKey]
        if (manual != null && manual != StreamQualityRank.UNKNOWN) {
            if (!lockedSourceId.isNullOrBlank()) {
                pool.firstOrNull { it.sourceId == lockedSourceId }?.let {
                    setActiveVariant(rawKey, it)
                    return it
                }
            }
            pool.firstOrNull { it.quality == manual }?.let {
                setActiveVariant(rawKey, it)
                return it
            }
            val fallbackManual = pool.filter { it.quality.rank <= manual.rank }.maxByOrNull { it.quality.rank }
                ?: pool.minByOrNull { kotlin.math.abs(it.quality.rank - manual.rank) }
                ?: variants.first()
            setActiveVariant(rawKey, fallbackManual)
            return fallbackManual
        }

        // 2. Forced step-down
        if (forceDowngradeFrom != null) {
            val lower = pool.filter { it.quality.rank < forceDowngradeFrom.rank }
                .maxByOrNull { it.quality.rank }
                ?: pool.minByOrNull { it.quality.rank }
                ?: variants.last()
            lastStableQuality[cKey] = lower.quality
            setActiveVariant(rawKey, lower)
            return lower
        }

        // 3. If user clicked a specific quality channel directly in its regular bouquet (not the virtual MULTI_QUALITY card),
        //    start with that exact clicked channel if it is healthy and fits current network ceiling, while keeping Auto ABR active!
        if (multiChannel.playlistId != PLAYLIST_ID) {
            val clickedVar = pool.firstOrNull { it.channel.stableKey == rawKey }
            val kbpsNow = currentMeasuredSpeedKbps()
            val netCeilNow = networkCeilingRank(kbpsNow)
            val ceilingHold = channelQualityCeilingHold[cKey] ?: channelQualityCeilingHold[rawKey]
            val effCeil = if (ceilingHold != null && now < ceilingHold.second) minOf(netCeilNow, ceilingHold.first) else netCeilNow
            if (clickedVar != null && (kbpsNow <= 180L || clickedVar.quality.rank <= effCeil)) {
                setActiveVariant(rawKey, clickedVar)
                return clickedVar
            }
        }

        // 4. Smart Auto initial pick: respect temporary ceiling hold if a recent upgrade failed, otherwise use line speed
        val kbps = currentMeasuredSpeedKbps()
        val netCeiling = networkCeilingRank(kbps)
        val ceilingHold = channelQualityCeilingHold[cKey] ?: channelQualityCeilingHold[rawKey]
        val activeCeiling = if (ceilingHold != null && now < ceilingHold.second) {
            minOf(netCeiling, ceilingHold.first)
        } else {
            netCeiling
        }
        val stableRank = (lastStableQuality[cKey] ?: lastStableQuality[rawKey])?.rank
        val targetRank = when {
            stableRank != null && now < (ceilingHold?.second ?: 0L) -> minOf(stableRank, activeCeiling)
            stableRank != null -> maxOf(stableRank, minOf(StreamQualityRank.FHD.rank, activeCeiling))
            else -> minOf(StreamQualityRank.FHD.rank, activeCeiling)
        }
        val preferFam = extractSourceFamily(multiChannel.name, multiChannel.group)
        val chosen = pool.filter { it.quality.rank <= targetRank }
            .sortedWith(
                compareByDescending<QualityVariant> { it.quality.rank }
                    .thenByDescending {
                        val fam = extractSourceFamily(it.channel.name, it.channel.group)
                        if (preferFam.isNotBlank() && fam.equals(preferFam, true)) 1 else 0
                    }
                    .thenByDescending { getVariantHealthScore(it, now) }
            )
            .firstOrNull()
            ?: pool.minByOrNull { it.quality.rank }
            ?: variants.last()
        setActiveVariant(rawKey, chosen)
        return chosen
    }

    fun pickAdaptiveVariant(
        multiChannel: TvChannel,
        preferQuality: StreamQualityRank? = null,
        forceDowngradeFrom: StreamQualityRank? = null
    ): TvChannel {
        val picked = pickBestVariant(multiChannel, preferQuality, forceDowngradeFrom) ?: return multiChannel
        return picked.channel
    }

    /**
     * Real-Time Stream Flow & Buffer Velocity Evaluator (YouTube / Facebook Live ABR style):
     * Evaluates whether the stream is suffering from:
     * - Internet bandwidth drop (`INTERNET_FLUCTUATION`) where measured speed < required bitrate AND buffer is draining
     * - Source server stall (`SOURCE_CONGESTION`) where internet speed is fast (or normal), yet the source stopped sending frames
     *   or is dropping frames / starving the buffer.
     */
    fun evaluateLiveFlowHealth(
        multiStableKey: String,
        currentVariant: QualityVariant?,
        bufferAheadMs: Long,
        bufferDeltaMs: Long,
        frameDelta: Int,
        droppedFrameDelta: Int,
        measuredSpeedKbps: Long = currentMeasuredSpeedKbps()
    ): AdaptiveSwitchDecision? {
        val cKey = resolveCanonicalClusterKey(multiStableKey)
        if (manualQualityLock[cKey] != null || manualQualityLock[multiStableKey] != null) return null
        val variants = variantsFor(multiStableKey)
        if (variants.size < 2) return null
        val active = currentVariant ?: getActiveVariant(multiStableKey) ?: return null

        val minNeededKbps = active.quality.minSpeedKbps.coerceAtLeast(350L)
        val conservativeKbps = NetworkSpeedMonitor.conservativeSpeedKbps()
        val effectiveKbps = maxOf(measuredSpeedKbps, conservativeKbps)

        // Ignore temporary blips: only act on clear, sustained danger of freeze.
        // Case 1: Heavy sustained decoder drops on FHD/4K (not a single spike)
        if (droppedFrameDelta >= 18 && frameDelta <= (droppedFrameDelta / 2) && active.quality.rank >= StreamQualityRank.FHD.rank) {
            return reportAdaptiveEvent(
                multiStableKey = multiStableKey,
                currentVariant = active,
                reason = AdaptationReason.SOURCE_CONGESTION,
                currentSpeedKbps = effectiveKbps
            )
        }

        // Case 2: Buffer critically low AND still draining (real pre-freeze), not a short dip
        if (bufferAheadMs in 1L..320L && bufferDeltaMs < -200L) {
            val isInternetBottleneck = effectiveKbps in 1L..((minNeededKbps * 75L) / 100L)
            return reportAdaptiveEvent(
                multiStableKey = multiStableKey,
                currentVariant = active,
                reason = if (isInternetBottleneck) AdaptationReason.INTERNET_FLUCTUATION else AdaptationReason.SOURCE_CONGESTION,
                currentSpeedKbps = effectiveKbps
            )
        }

        return null
    }

    /**
     * Proactive Smoothness-Driven Quality Upgrade (الترقية الذكية الحقيقية عند استقرار البث وتوفر سرعة الخط):
     * - Works in Auto mode for ANY channel with multiple qualities (manual or auto-clustered).
     * - Because IPTV servers pace live streams at their encoder bitrate (e.g. 2.5 Mbps for HD even on a 100 Mbps fiber line),
     *   relying solely on socket download rate while watching HD would keep the viewer stuck on HD forever.
     * - Instead, when the current quality plays with 100% stability (zero stalls, healthy buffer, positive/steady buffer velocity)
     *   and there is a higher quality variant (LOW -> SD -> HD -> FHD -> 4K) that is healthy and not in upgrade-hold cooldown,
     *   this method steps up 1 tier seamlessly!
     */
    fun tryProactiveUpgrade(
        multiStableKey: String,
        currentVariant: QualityVariant?,
        bufferAheadMs: Long,
        effectiveKbps: Long = currentMeasuredSpeedKbps()
    ): AdaptiveSwitchDecision? {
        val cKey = resolveCanonicalClusterKey(multiStableKey)
        if (manualQualityLock[cKey] != null || manualQualityLock[multiStableKey] != null) return null
        val variants = variantsFor(multiStableKey)
        if (variants.size < 2) return null
        val active = currentVariant ?: getActiveVariant(multiStableKey) ?: return null

        val now = System.currentTimeMillis()
        val ceilingHold = channelQualityCeilingHold[cKey] ?: channelQualityCeilingHold[multiStableKey]
        if (ceilingHold != null && now < ceilingHold.second && active.quality.rank >= ceilingHold.first) {
            return null
        }

        // Upgrades are optional comfort — never spam. Long cooldown + real headroom required.
        val lastSw = lastQualitySwitchAtMs[cKey] ?: lastQualitySwitchAtMs[multiStableKey] ?: 0L
        if (now - lastSw < MIN_UPGRADE_COOLDOWN_MS) return null
        if (bufferAheadMs < 2_200L) return null

        val activeFam = extractSourceFamily(active.channel.name, active.channel.group)
        // Find the next single step-up quality, preferring SAME source family (nm/ss/sa/f/be…)
        val nextHigherCandidates = variants
            .filter { it.quality.rank > active.quality.rank && isVariantEligibleForAutoUpgrade(it, now) }
            .sortedWith(
                compareBy<QualityVariant> { it.quality.rank }
                    .thenByDescending {
                        val fam = extractSourceFamily(it.channel.name, it.channel.group)
                        if (activeFam.isNotBlank() && fam.equals(activeFam, true)) 1 else 0
                    }
                    .thenByDescending { getVariantHealthScore(it, now) }
            )
        val stepUp = nextHigherCandidates.firstOrNull() ?: return null

        // Require real speed headroom for the target tier (no upgrade on weak/unknown estimates alone)
        val hasMeasuredHeadroom = effectiveKbps >= (stepUp.quality.minSpeedKbps * 110L) / 100L
        if (!hasMeasuredHeadroom) return null

        setActiveVariant(multiStableKey, stepUp)
        lastStableQuality[cKey] = stepUp.quality
        lastStableQuality[multiStableKey] = stepUp.quality
        lastQualitySwitchAtMs[cKey] = now
        lastQualitySwitchAtMs[multiStableKey] = now
        val srcNote = if (stepUp.bouquetLabel.isNotBlank() && stepUp.bouquetLabel != BOUQUET_NAME) {
            " • ${stepUp.bouquetLabel}"
        } else ""
        val msg = "🚀 ترقية اختيارية بعد استقرار طويل: ${active.quality.labelAr} ⬅ ${stepUp.quality.labelAr}$srcNote"
        TiviMateDiagnostics.logEvent(msg)
        return AdaptiveSwitchDecision(
            variant = stepUp,
            reason = AdaptationReason.INTERNET_RECOVERY,
            bannerMessageAr = msg,
            switched = true
        )
    }

    /**
     * Instant Step-Down & Multi-Source Failover Engine:
     * Immediately switches down (or to an alternate server of the same quality when the source itself is down)
     * on the first sign of stutter, buffer drain, source congestion, or source outage,
     * and places a smart hold on upgrading back to the problematic tier for 45–120s so playback stays 100% smooth.
     */
    fun reportAdaptiveEvent(
        multiStableKey: String,
        currentVariant: QualityVariant?,
        reason: AdaptationReason,
        currentSpeedKbps: Long = currentMeasuredSpeedKbps()
    ): AdaptiveSwitchDecision? {
        val cKey = resolveCanonicalClusterKey(multiStableKey)
        val variants = variantsFor(multiStableKey)
        if (variants.size < 2) return null
        val active = currentVariant
            ?: getActiveVariant(multiStableKey)
            ?: variants.first()
        val now = System.currentTimeMillis()
        // Anti-bounce: only SOURCE_OUTAGE may switch often; congestion/internet need long cooldown
        val lastSw = lastQualitySwitchAtMs[cKey] ?: lastQualitySwitchAtMs[multiStableKey] ?: 0L
        val minGap = when (reason) {
            AdaptationReason.SOURCE_OUTAGE -> MIN_OUTAGE_SWITCH_COOLDOWN_MS
            AdaptationReason.INTERNET_RECOVERY, AdaptationReason.INITIAL_SMART_PICK, AdaptationReason.MANUAL_USER_SELECT -> 0L
            else -> MIN_SWITCH_COOLDOWN_MS
        }
        if (minGap > 0L && (now - lastSw) < minGap) return null
        val rec = healthRegistry.getOrPut(healthKey(active)) { SourceHealthRecord() }

        when (reason) {
            AdaptationReason.SOURCE_OUTAGE -> {
                rec.failCount += 1
                val holdMs = 75_000L * rec.failCount.coerceIn(1, 4)
                rec.outageUntilMs = now + holdMs
                rec.upgradeHoldUntilMs = now + holdMs
                rec.healthScore = 15
                rec.lastReason = "توقف من المصدر"
            }
            AdaptationReason.SOURCE_CONGESTION -> {
                rec.congestionCount += 1
                val holdMs = 50_000L * rec.congestionCount.coerceIn(1, 4)
                rec.congestedUntilMs = now + holdMs
                rec.upgradeHoldUntilMs = now + holdMs
                rec.healthScore = (rec.healthScore - 35).coerceAtLeast(25)
                rec.lastReason = "ثقل من المصدر"
            }
            AdaptationReason.INTERNET_FLUCTUATION -> {
                rec.congestionCount += 1
                val holdMs = 40_000L * rec.congestionCount.coerceIn(1, 3)
                rec.congestedUntilMs = now + 22_000L
                rec.upgradeHoldUntilMs = now + holdMs
                rec.healthScore = (rec.healthScore - 25).coerceAtLeast(35)
                rec.lastReason = "تذبذب حركة الإنترنت"
                // Hold channel ceiling below the rank that just stuttered so it doesn't bounce back prematurely
                val safeCapRank = (active.quality.rank - 1).coerceAtLeast(StreamQualityRank.LOW.rank)
                channelQualityCeilingHold[cKey] = Pair(safeCapRank, now + holdMs)
                channelQualityCeilingHold[multiStableKey] = Pair(safeCapRank, now + holdMs)
            }
            AdaptationReason.INTERNET_RECOVERY -> {
                return tryProactiveUpgrade(multiStableKey, active, 2_500L, currentSpeedKbps)
            }
            else -> {}
        }

        val otherVariants = variants.filter { it.sourceId != active.sourceId || it.channel.url != active.channel.url }
        if (otherVariants.isEmpty()) return null

        val activeFam = extractSourceFamily(active.channel.name, active.channel.group)
        fun sameSourceScore(v: QualityVariant): Int {
            if (activeFam.isBlank()) return 0
            val fam = extractSourceFamily(v.channel.name, v.channel.group)
            return if (fam.equals(activeFam, ignoreCase = true)) 100 else 0
        }

        val healthyOthers = otherVariants.filter { isVariantHealthy(it, now) }
        val candidatePool = (healthyOthers.ifEmpty {
            otherVariants.sortedBy {
                val r = healthRegistry[healthKey(it)]
                maxOf(r?.outageUntilMs ?: 0L, r?.congestedUntilMs ?: 0L)
            }
        }).sortedByDescending { sameSourceScore(it) + getVariantHealthScore(it, now) }

        val nextVariant: QualityVariant = when (reason) {
            AdaptationReason.INTERNET_FLUCTUATION -> {
                // Immediately step down to the highest healthy quality strictly below current quality rank
                // (or jump directly to the tier matching conservative network speed if speed dropped sharply)
                val netCapRank = networkCeilingRank(currentSpeedKbps)
                val desiredMaxRank = minOf(active.quality.rank - 1, netCapRank).coerceAtLeast(StreamQualityRank.LOW.rank)
                candidatePool.filter { it.quality.rank <= desiredMaxRank }
                    .maxByOrNull { it.quality.rank }
                    ?: candidatePool.filter { it.quality.rank < active.quality.rank }
                        .maxByOrNull { it.quality.rank }
                    ?: candidatePool.minByOrNull { it.quality.rank }
                    ?: return null
            }
            AdaptationReason.SOURCE_CONGESTION -> {
                // If internet is fast enough for current rank and there is an alternate healthy server at the same rank,
                // or a 1-step lower rank for instant zero-lag recovery:
                val netCapRank = networkCeilingRank(currentSpeedKbps)
                val sameRankHealthyAltServer = if (netCapRank >= active.quality.rank) {
                    candidatePool.firstOrNull {
                        it.quality == active.quality &&
                            it.channel.playlistId != active.channel.playlistId &&
                            getVariantHealthScore(it, now) >= 85
                    }
                } else null
                sameRankHealthyAltServer
                    ?: candidatePool.filter { it.quality.rank < active.quality.rank }
                        .maxByOrNull { it.quality.rank }
                    ?: candidatePool.firstOrNull { it.quality == active.quality }
                    ?: candidatePool.minByOrNull { kotlin.math.abs(it.quality.rank - active.quality.rank) }
                    ?: return null
            }
            AdaptationReason.SOURCE_OUTAGE -> {
                candidatePool.firstOrNull { it.quality == active.quality }
                    ?: candidatePool.filter { it.quality.rank < active.quality.rank }
                        .maxByOrNull { it.quality.rank }
                    ?: candidatePool.minByOrNull { kotlin.math.abs(it.quality.rank - active.quality.rank) }
                    ?: return null
            }
            else -> candidatePool.first()
        }

        setActiveVariant(multiStableKey, nextVariant)
        lastStableQuality[cKey] = nextVariant.quality
        lastStableQuality[multiStableKey] = nextVariant.quality
        lastQualitySwitchAtMs[cKey] = now
        lastQualitySwitchAtMs[multiStableKey] = now

        val srcNote = if (nextVariant.bouquetLabel.isNotBlank() && nextVariant.bouquetLabel != BOUQUET_NAME) {
            " • ${nextVariant.bouquetLabel}"
        } else ""
        val famNote = extractSourceFamily(nextVariant.channel.name, nextVariant.channel.group).let {
            if (it.isNotBlank()) " [$it]" else ""
        }

        val msg = when (reason) {
            AdaptationReason.SOURCE_OUTAGE ->
                "🔄 توقف المصدر (${active.quality.labelAr}) ⬅ تحويل تلقائي فوري إلى ${nextVariant.quality.labelAr}$srcNote$famNote"
            AdaptationReason.SOURCE_CONGESTION ->
                "⚡ تقطيع من المصدر (${active.quality.labelAr}) ⬅ جودة أدنى لتجنب التجمّد: ${nextVariant.quality.labelAr}$srcNote$famNote"
            AdaptationReason.INTERNET_FLUCTUATION ->
                "🌐 ضعف الخط (${active.quality.labelAr}) ⬅ جودة أدنى لتجنب التقطيع: ${nextVariant.quality.labelAr}$srcNote$famNote"
            else ->
                "📺 الجودة النشطة: ${nextVariant.quality.labelAr}$srcNote$famNote"
        }

        TiviMateDiagnostics.logEvent(msg)
        return AdaptiveSwitchDecision(
            variant = nextVariant,
            reason = reason,
            bannerMessageAr = msg,
            switched = true
        )
    }

    private fun parseSpeedKbps(label: String): Long {
        val clean = label.trim().uppercase(java.util.Locale.US)
        val num = Regex("""([\d.]+)""").find(clean)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        return when {
            clean.contains("GB") -> (num * 1024.0 * 1024.0 * 8.0).toLong()
            clean.contains("MB") -> (num * 1024.0 * 8.0).toLong()
            clean.contains("KB") -> (num * 8.0).toLong()
            else -> (num * 8.0).toLong()
        }
    }
}

@Immutable
data class TvChannel(
    val id: Int,
    val number: Int,
    val name: String,
    val url: String,
    val group: String = "عام",
    val logo: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val origin: String? = null,
    val cookie: String? = null,
    val drmScheme: String = "",
    val drmKey: String = "",
    val contentType: StreamContentType = StreamContentType.LIVE_TV,
    val rating: String = "",
    val releaseYear: String = "",
    val plot: String = "",
    val seriesId: Int = 0,
    val episodes: List<SeriesEpisode> = emptyList(),
    val playlistId: String = "PORTAL_MAIN",
    val playlistName: String = "الرئيسية",
    val streamId: Int = 0,
    val streamBaseUrl: String = "",
    val streamUsername: String = "",
    val streamPassword: String = "",
    val containerExtension: String = "",
    val videoCodecHint: String = "",
    val audioCodecHint: String = ""
) {
    /**
     * Collision-free deterministic unique key (includes sequential item `id` so two items in a 200,000-movie
     * or 50,000-series playlist NEVER collide even if the server repeats `stream_id` across categories)
     * and zero-field search matcher (saves ~180MB of JVM heap across 500,000+ catalog items!).
     */
    val stableKey: String
        get() = "${playlistId}_${contentType.name}_${id}_${if (streamId > 0) streamId else seriesId}"

    val searchKey: String
        get() = "${name.lowercase(java.util.Locale.ROOT)} ${group.lowercase(java.util.Locale.ROOT)} $number"

    fun matchesSearch(q: String): Boolean {
        if (q.isEmpty()) return true
        return name.contains(q, ignoreCase = true) ||
            group.contains(q, ignoreCase = true) ||
            number.toString() == q
    }

    private fun safeEncodeCredential(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val decoded = URLDecoder.decode(trimmed, "UTF-8")
            URLEncoder.encode(decoded, "UTF-8")
        }.getOrDefault(trimmed)
    }

    /**
     * Universal Candidate URL Builder (v20.0):
     * - Prevents double URL-encoding of credentials.
     * - For Live TV: generates both HLS (.m3u8) and raw MPEG-TS (.ts) and extensionless URLs immediately,
     *   plus fallback hosts from KnownIptvServersCatalog if the primary server host ever changes!
     * - For VOD Movies & Series: tries the primary container first, then .mp4, .mkv, .ts, .m3u8, .avi, .webm, .mov, .flv.
     */
    fun buildFormatCandidateUrls(activeAccount: XtreamAccountConfig? = null): List<String> {
        val candidates = LinkedHashSet<String>()
        val rawBase = (activeAccount?.serverUrl?.takeIf { it.isNotBlank() } ?: streamBaseUrl)
            .trim()
            .removeSuffix("/")
        val effBase = if (rawBase.isNotBlank() && !rawBase.startsWith("http://", ignoreCase = true) && !rawBase.startsWith("https://", ignoreCase = true)) {
            KnownIptvServersCatalog.resolveHostByServerName(rawBase).ifBlank { "http://$rawBase" }
        } else if (rawBase.isBlank() && activeAccount?.serverName?.isNotBlank() == true) {
            KnownIptvServersCatalog.resolveHostByServerName(activeAccount.serverName)
        } else {
            rawBase
        }.trim().removeSuffix("/")

        val effUser = safeEncodeCredential(
            activeAccount?.username?.takeIf { it.isNotBlank() } ?: streamUsername
        )
        val effPass = safeEncodeCredential(
            activeAccount?.password?.takeIf { it.isNotBlank() } ?: streamPassword
        )

        fun addProtocolVariant(u: String) {
            if (u.startsWith("https://", ignoreCase = true)) {
                candidates.add("http://" + u.substring(8))
            } else if (u.startsWith("http://", ignoreCase = true)) {
                candidates.add("https://" + u.substring(7))
            }
        }

        val primary = url.trim()

        if (contentType == StreamContentType.LIVE_TV) {
            val baseWithoutQuery = primary.substringBefore("?")
            val querySuffix = if (primary.contains("?")) "?" + primary.substringAfter("?") else ""
            val preferredExt = FastFormatPreferenceCache.getPreferredLiveExt(playlistId)

            if (streamId > 0 && effBase.isNotBlank() && effUser.isNotBlank() && effPass.isNotBlank()) {
                // Prioritize Format #2 (.ts raw MPEG-TS) FIRST by default for ultra-fast instant opening!
                if (preferredExt == "m3u8") {
                    candidates.add("$effBase/live/$effUser/$effPass/$streamId.m3u8")
                    candidates.add("$effBase/live/$effUser/$effPass/$streamId.ts")
                    candidates.add("$effBase/live/$effUser/$effPass/$streamId")
                } else {
                    candidates.add("$effBase/live/$effUser/$effPass/$streamId.ts")
                    candidates.add("$effBase/live/$effUser/$effPass/$streamId")
                    candidates.add("$effBase/live/$effUser/$effPass/$streamId.m3u8")
                }
                if (primary.isNotBlank()) {
                    candidates.add(primary)
                }
                candidates.add("$effBase/$effUser/$effPass/$streamId.ts")
                candidates.add("$effBase/$effUser/$effPass/$streamId")
                candidates.add("$effBase/$effUser/$effPass/$streamId.m3u8")
                if (effBase.startsWith("https://", ignoreCase = true)) {
                    val httpBase = "http://" + effBase.substring(8)
                    candidates.add("$httpBase/live/$effUser/$effPass/$streamId.ts")
                    candidates.add("$httpBase/live/$effUser/$effPass/$streamId")
                    candidates.add("$httpBase/live/$effUser/$effPass/$streamId.m3u8")
                }
            } else {
                when {
                    baseWithoutQuery.endsWith(".mpd", ignoreCase = true) -> {
                        if (primary.isNotBlank()) candidates.add(primary)
                        addProtocolVariant(primary)
                    }
                    baseWithoutQuery.endsWith(".m3u8", ignoreCase = true) -> {
                        val prefix = baseWithoutQuery.dropLast(5)
                        if (preferredExt == "m3u8") {
                            candidates.add(primary)
                            candidates.add("$prefix.ts$querySuffix")
                            candidates.add("$prefix$querySuffix")
                        } else {
                            // Try .ts FIRST even if the stored URL ended with .m3u8!
                            candidates.add("$prefix.ts$querySuffix")
                            candidates.add("$prefix$querySuffix")
                            candidates.add(primary)
                        }
                        addProtocolVariant("$prefix.ts$querySuffix")
                        addProtocolVariant(primary)
                    }
                    baseWithoutQuery.endsWith(".ts", ignoreCase = true) -> {
                        val prefix = baseWithoutQuery.dropLast(3)
                        candidates.add(primary)
                        candidates.add("$prefix$querySuffix")
                        candidates.add("$prefix.m3u8$querySuffix")
                        addProtocolVariant(primary)
                    }
                    primary.isNotBlank() &&
                        !baseWithoutQuery.endsWith(".mp4", ignoreCase = true) &&
                        !baseWithoutQuery.endsWith(".mkv", ignoreCase = true) -> {
                        candidates.add("$baseWithoutQuery.ts$querySuffix")
                        candidates.add(primary)
                        candidates.add("$baseWithoutQuery.m3u8$querySuffix")
                        addProtocolVariant(primary)
                    }
                    primary.isNotBlank() -> {
                        candidates.add(primary)
                    }
                }
            }
        } else {
            // VOD Movies & Series Episodes: primary URL first, then container fallbacks (.mp4, .mkv, .ts, .m3u8, .avi, .webm, .mov, .flv)
            if (primary.isNotBlank()) {
                candidates.add(primary)
            }
            if (streamId > 0 && effBase.isNotBlank() && effUser.isNotBlank() && effPass.isNotBlank()) {
                val folder = if (contentType == StreamContentType.SERIES || primary.contains("/series/", ignoreCase = true)) "series" else "movie"
                val extPref = containerExtension.trim().lowercase().removePrefix(".")
                val vodExts = listOfNotNull(extPref.takeIf { it.isNotBlank() }, "mp4", "mkv", "ts", "m3u8", "avi", "webm", "mov", "flv").distinct()
                for (ext in vodExts) {
                    candidates.add("$effBase/$folder/$effUser/$effPass/$streamId.$ext")
                }
            }
            val baseWithoutQuery = primary.substringBefore("?")
            val querySuffix = if (primary.contains("?")) "?" + primary.substringAfter("?") else ""
            val dotIdx = baseWithoutQuery.lastIndexOf('.')
            val slashIdx = baseWithoutQuery.lastIndexOf('/')
            if (dotIdx > slashIdx + 1) {
                val prefix = baseWithoutQuery.substring(0, dotIdx)
                for (ext in listOf("mp4", "mkv", "ts", "m3u8", "avi", "webm", "mov", "flv")) {
                    candidates.add("$prefix.$ext$querySuffix")
                }
            }
            if (primary.isNotBlank()) {
                addProtocolVariant(primary)
            }
        }

        return candidates.filter { it.isNotBlank() }.take(10)
    }
}

@Immutable
data class XtreamAccountConfig(
    val id: String = UUID.randomUUID().toString().take(8),
    val serverName: String = "",
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val enabled: Boolean = true,
    val isRemoteFromPortal: Boolean = false,
    val orderIndex: Int = 0,
    val statusNote: String = "جاهز للمزامنة",
    val liveCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val expDate: String = "",
    val lastSyncTimestamp: Long = 0L,
    val lastCatalogEtag: String = ""
) {
    val isConfigured: Boolean
        get() = ((serverUrl.isNotBlank() || serverName.isNotBlank()) && username.isNotBlank() && password.isNotBlank()) || m3uUrl.isNotBlank()
}

data class XtreamFullSyncResult(
    val account: XtreamAccountConfig,
    val liveChannels: List<TvChannel>,
    val movies: List<TvChannel>,
    val series: List<TvChannel>,
    val isDifferentialUnchanged: Boolean = false,
    val catalogHash: String = ""
)

@Immutable
data class BouquetSummary(
    val name: String,
    val count: Int,
    val sampleLogos: List<String> = emptyList(),
    val isFavoritesSpecial: Boolean = false
)

@Immutable
data class SubscriptionInfo(
    val active: Boolean = false,
    val isExpired: Boolean = false,
    val isTrial: Boolean = false,
    val reason: String = "",
    val expireDate: String = "",
    val daysLeft: Int = 0,
    val deviceName: String = "",
    val message: String = ""
)

data class ApiSyncResult(
    val channels: List<TvChannel>,
    val movies: List<TvChannel> = emptyList(),
    val series: List<TvChannel> = emptyList(),
    val subscription: SubscriptionInfo,
    val remoteXtreamLists: List<XtreamAccountConfig> = emptyList(),
    val serverCatalogHash: String = "",
    val isDifferentialUnchanged: Boolean = false
)

@Immutable
data class CloudflareTunnelStatus(
    val connected: Boolean = true,
    val edgeColo: String = "WARP • سريع",
    val clientIp: String = "",
    val latencyMs: Long = 12L,
    val systemVpnActive: Boolean = true,
    val statusText: String = "متصّل تلقائياً عبر نفق التطبيق الداخلي الآمن ✓"
)

@Immutable
data class PlayerPreferences(
    val appLanguage: AppUiLanguage = AppUiLanguage.AR,
    val playlistDisplayMode: PlaylistDisplayMode = PlaylistDisplayMode.MERGE_ALL,
    val activePlaylistFilterId: String = "ALL",
    val playlistOrderIds: List<String> = emptyList(),
    val bufferProfile: BufferProfile = BufferProfile.XPOLA_ZERO_LAG,
    val resizeMode: VideoResizeMode = VideoResizeMode.FILL,
    val thumbnailCacheLimit: ThumbnailCacheLimitOption = ThumbnailCacheLimitOption.MB_64,
    val autoReconnect: Boolean = true,
    val maxReconnectRetries: Int = 8,
    val preferSoftwareDecoder: Boolean = false,
    val useGridLayout: Boolean = true,
    val showBouquetSidebar: Boolean = true,
    val showScreenCodeOverlay: Boolean = true,
    val customBouquetOrder: List<String> = emptyList(),
    val hiddenBouquets: Set<String> = emptySet(),
    val customChannelOrder: List<String> = emptyList(),
    val uiLayoutStyle: UiLayoutStyle = UiLayoutStyle.CLASSIC_LIVE_PRO,
    val lowEndDeviceMode: Boolean = false,
    val showNetworkSpeed: Boolean = true,
    val playerEngine: InternalPlayerEngine = InternalPlayerEngine.AUTO_HYBRID,
    val vpnPermanentDisable: Boolean = false,
    val vpnAutoStartOnLaunch: Boolean = true
)

enum class VoucherAlertType {
    SUCCESS,
    USED_CARD,
    INVALID_OR_NOT_FOUND,
    EXPIRED_CARD,
    RATE_LIMITED,
    NETWORK_ERROR
}

@Immutable
data class VoucherAlertInfo(
    val type: VoucherAlertType,
    val title: String,
    val message: String,
    val enteredCode: String = "",
    val addedDays: Int = 0,
    val newExpireDate: String = ""
)

/**
 * Prevents UI freezes, double-navigation, and coroutine floods when the user rapidly clicks
 * or holds down D-Pad / touch buttons.
 */
object SafeClickGuard {
    private val lastClickMap = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun allowAction(actionKey: String = "GLOBAL_UI", minIntervalMs: Long = 220L): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        val prev = lastClickMap[actionKey] ?: 0L
        if (now - prev < minIntervalMs) {
            return false
        }
        lastClickMap[actionKey] = now
        if (lastClickMap.size > 256) {
            lastClickMap.clear()
            lastClickMap[actionKey] = now
        }
        return true
    }

    inline fun runThrottled(
        actionKey: String = "GLOBAL_UI",
        minIntervalMs: Long = 220L,
        block: () -> Unit
    ) {
        if (allowAction(actionKey, minIntervalMs)) {
            block()
        }
    }
}

/**
 * Lightning-Fast Format Memory Cache (v26.0):
 * Defaults to raw MPEG-TS ("ts" — Format #2) as #1 priority and remembers the fastest verified format per playlist
 * so channels open at lightning speed without redundant format probing.
 */
object FastFormatPreferenceCache {
    private val playlistPreferredExt = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val vlcRequiredChannelKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun getPreferredLiveExt(playlistId: String): String {
        if (playlistId.isBlank()) return "ts"
        return playlistPreferredExt[playlistId] ?: "ts"
    }

    fun recordWorkingLiveUrl(playlistId: String, workingUrl: String) {
        if (playlistId.isBlank() || workingUrl.isBlank()) return
        val clean = workingUrl.substringBefore("?").lowercase()
        // Always prioritize fixed-rate single-stream .ts over adaptive master .m3u8 for instant startup
        if (clean.endsWith(".ts")) {
            playlistPreferredExt[playlistId] = "ts"
        } else if (clean.endsWith(".m3u8") && playlistPreferredExt[playlistId] == null) {
            playlistPreferredExt[playlistId] = "m3u8"
        }
    }

    fun markChannelRequiresVlc(stableKey: String, url: String = "") {
        if (stableKey.isNotBlank()) vlcRequiredChannelKeys.add(stableKey)
        if (url.isNotBlank()) vlcRequiredChannelKeys.add(url.trim())
    }

    fun clearChannelRequiresVlc(stableKey: String, url: String = "") {
        if (stableKey.isNotBlank()) vlcRequiredChannelKeys.remove(stableKey)
        if (url.isNotBlank()) vlcRequiredChannelKeys.remove(url.trim())
    }

    fun doesChannelPreferVlc(stableKey: String, url: String = ""): Boolean {
        if (stableKey.isNotBlank() && vlcRequiredChannelKeys.contains(stableKey)) return true
        if (url.isNotBlank() && vlcRequiredChannelKeys.contains(url.trim())) return true
        return false
    }
}
