package com.tvflux.pro

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Guards against channel mixing and stale async stream responses when zapping rapidly.
 */
object PlaybackSessionGuard {
    private val sessionCounter = AtomicLong(0L)

    @Volatile
    var currentActiveChannelKey: String = ""
        private set

    fun beginSession(channelStableKey: String): Long {
        currentActiveChannelKey = channelStableKey
        return sessionCounter.incrementAndGet()
    }

    fun isSessionCurrent(sessionId: Long, channelStableKey: String): Boolean {
        return sessionCounter.get() == sessionId && currentActiveChannelKey == channelStableKey
    }
}

/**
 * Universal Stream Format Detector, Protocol Classifier & IBO-Style Codec Engine.
 * - Detects all video and audio formats in <1ms without network latency.
 * - Classifies protocols (HTTP/HTTPS, HLS, DASH, RTSP, RTMP, MMS, UDP/RTP).
 * - Identifies streams that natively require LibVLC software demuxing (e.g. RTMP, MMS, UDP, RMVB, WMV).
 * - Probes ambiguous live streams via lightweight magic-byte sniffing (0x47 TS / #EXTM3U) when needed.
 */
object StreamFormatDetectorAndRenewer {

    private val probeHttpClient by lazy {
        ChannelRepository.streamHttpClient.newBuilder()
            .connectTimeout(1800, TimeUnit.MILLISECONDS)
            .readTimeout(1800, TimeUnit.MILLISECONDS)
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Determines whether a stream exclusively requires the LibVLC engine upfront:
     * - Streaming protocols not supported by Media3: RTMP, RTMPS, RTMPT, MMS, MMSH, UDP, RTP.
     * - Legacy video containers: RMVB, RM, WMV, ASF, DivX.
     * - Audio/video codecs known to fail on Android MediaCodec: VC-1, RealVideo (RV30/RV40), WMA, Sorenson Spark.
     */
    fun isExclusivelyVlcProtocolOrCodec(
        url: String,
        videoCodecHint: String = "",
        audioCodecHint: String = "",
        containerExtension: String = "",
        name: String = ""
    ): Boolean {
        val lowUrl = url.trim().lowercase(Locale.ROOT)
        val cleanUrl = lowUrl.substringBefore("?")

        // 1. Protocols requiring LibVLC
        if (cleanUrl.startsWith("rtmp://") || cleanUrl.startsWith("rtmps://") || cleanUrl.startsWith("rtmpt://") ||
            cleanUrl.startsWith("mms://") || cleanUrl.startsWith("mmsh://") ||
            cleanUrl.startsWith("udp://") || cleanUrl.startsWith("rtp://")
        ) {
            return true
        }

        // 2. Containers requiring LibVLC
        val ext = containerExtension.trim().lowercase(Locale.ROOT).removePrefix(".")
        if (ext == "rmvb" || ext == "rm" || ext == "wmv" || ext == "asf" || ext == "divx" ||
            cleanUrl.endsWith(".rmvb") || cleanUrl.endsWith(".rm") || cleanUrl.endsWith(".wmv") ||
            cleanUrl.endsWith(".asf") || cleanUrl.endsWith(".divx")
        ) {
            return true
        }

        // 3. Codec hints requiring LibVLC
        val lowV = videoCodecHint.lowercase(Locale.ROOT)
        val lowA = audioCodecHint.lowercase(Locale.ROOT)
        val lowName = name.lowercase(Locale.ROOT)
        if (lowV.contains("vc1") || lowV.contains("vc-1") || lowV.contains("wmv") ||
            lowV.contains("rv30") || lowV.contains("rv40") || lowV.contains("realvideo") ||
            lowA.contains("wma") || lowA.contains("wmav2") || lowA.contains("wmapro") ||
            lowName.contains("rmvb") || lowName.contains(".wmv")
        ) {
            return true
        }

        return false
    }

    fun detectFastFromUrlAndMetadata(channel: TvChannel, targetUrl: String, userAgent: String): DetectedStreamSpec {
        val low = targetUrl.lowercase(Locale.ROOT).substringBefore("?")
        val fullLow = targetUrl.lowercase(Locale.ROOT)
        val lowNameAndGroup = (channel.name + " " + channel.group + " " + low).lowercase(Locale.ROOT)

        // Comprehensive Video Codec Detection
        val vCodec = channel.videoCodecHint.ifBlank {
            when {
                lowNameAndGroup.contains("hevc") || lowNameAndGroup.contains("h265") || lowNameAndGroup.contains("h.265") ||
                    lowNameAndGroup.contains("x265") || lowNameAndGroup.contains("4k") || lowNameAndGroup.contains("uhd") -> "HEVC/H.265"
                lowNameAndGroup.contains("av01") || lowNameAndGroup.contains("av1") -> "AV1"
                lowNameAndGroup.contains("vp9") -> "VP9"
                lowNameAndGroup.contains("vp8") -> "VP8"
                lowNameAndGroup.contains("mpeg2") || lowNameAndGroup.contains("mp2v") || lowNameAndGroup.contains("mpeg-2") -> "MPEG-2"
                lowNameAndGroup.contains("vc1") || lowNameAndGroup.contains("vc-1") || low.endsWith(".wmv") || low.endsWith(".asf") -> "VC-1/WMV"
                low.endsWith(".rmvb") || low.endsWith(".rm") || lowNameAndGroup.contains("rv40") || lowNameAndGroup.contains("rv30") -> "RealVideo"
                low.endsWith(".flv") || lowNameAndGroup.contains("flv1") || lowNameAndGroup.contains("spark") -> "FLV/Spark"
                low.endsWith(".avi") || lowNameAndGroup.contains("xvid") || lowNameAndGroup.contains("divx") || lowNameAndGroup.contains("mp4v") -> "MPEG-4/XviD"
                else -> "H.264/AVC"
            }
        }

        // Comprehensive Audio Codec Detection
        val aCodec = channel.audioCodecHint.ifBlank {
            when {
                lowNameAndGroup.contains("atmos") -> "Dolby Atmos"
                lowNameAndGroup.contains("eac3") || lowNameAndGroup.contains("dd+") || lowNameAndGroup.contains("ec-3") -> "E-AC-3"
                lowNameAndGroup.contains("ac3") || lowNameAndGroup.contains("dolby") || lowNameAndGroup.contains("5.1") -> "AC-3"
                lowNameAndGroup.contains("truehd") -> "Dolby TrueHD"
                lowNameAndGroup.contains("dts-hd") || lowNameAndGroup.contains("dtshd") -> "DTS-HD"
                lowNameAndGroup.contains("dts:x") || lowNameAndGroup.contains("dtsx") -> "DTS:X"
                lowNameAndGroup.contains("dts") -> "DTS"
                lowNameAndGroup.contains("mp2") || lowNameAndGroup.contains("mp2a") || lowNameAndGroup.contains("mpeg-l2") -> "MP2"
                lowNameAndGroup.contains("flac") -> "FLAC"
                lowNameAndGroup.contains("alac") -> "ALAC"
                lowNameAndGroup.contains("opus") || low.endsWith(".opus") -> "Opus"
                lowNameAndGroup.contains("vorbis") || low.endsWith(".ogg") -> "Vorbis"
                lowNameAndGroup.contains("wma") -> "WMA"
                lowNameAndGroup.contains("amr") -> "AMR"
                lowNameAndGroup.contains("mp3") || low.endsWith(".mp3") -> "MP3"
                else -> "AAC"
            }
        }

        // Comprehensive Container & Protocol Detection
        val (container, mime) = when {
            // Streaming Protocols
            low.startsWith("rtmp://") || low.startsWith("rtmps://") || low.startsWith("rtmpt://") ->
                "RTMP Stream" to "video/x-flv"
            low.startsWith("rtsp://") ->
                "RTSP Stream" to MimeTypes.APPLICATION_RTSP
            low.startsWith("mms://") || low.startsWith("mmsh://") ->
                "MMS Stream" to "video/x-ms-asf"
            low.startsWith("udp://") || low.startsWith("rtp://") ->
                "UDP/RTP Multicast" to MimeTypes.VIDEO_MP2T
            // Adaptive Manifest Formats
            low.endsWith(".mpd") || fullLow.contains("format=mpd") || fullLow.contains("type=mpd") ->
                "MPEG-DASH (MPD)" to MimeTypes.APPLICATION_MPD
            low.endsWith(".m3u8") || fullLow.contains("output=m3u8") || fullLow.contains("type=m3u8") || fullLow.contains("output=hls") ->
                "HLS (M3U8)" to MimeTypes.APPLICATION_M3U8
            low.contains(".ism") ->
                "SmoothStreaming" to MimeTypes.APPLICATION_SS
            // Progressive Transport & Containers
            low.endsWith(".ts") || fullLow.contains("output=ts") || fullLow.contains("type=ts") ->
                "MPEG-TS" to MimeTypes.VIDEO_MP2T
            low.endsWith(".mkv") ->
                "MKV (Matroska)" to MimeTypes.VIDEO_MATROSKA
            low.endsWith(".webm") ->
                "WebM" to MimeTypes.VIDEO_WEBM
            low.endsWith(".mp4") || low.endsWith(".m4v") || low.endsWith(".mov") || low.endsWith(".m4a") ->
                "MP4 / MOV" to MimeTypes.VIDEO_MP4
            low.endsWith(".avi") ->
                "AVI" to MimeTypes.VIDEO_AVI
            low.endsWith(".flv") ->
                "FLV" to MimeTypes.VIDEO_FLV
            low.endsWith(".wmv") || low.endsWith(".asf") ->
                "Windows Media (WMV)" to "video/x-ms-wmv"
            low.endsWith(".rmvb") || low.endsWith(".rm") ->
                "RealMedia (RMVB)" to "video/x-pn-realvideo"
            low.endsWith(".3gp") || low.endsWith(".3g2") ->
                "3GPP" to "video/3gpp"
            low.endsWith(".ogg") || low.endsWith(".ogv") ->
                "Ogg" to MimeTypes.VIDEO_OGG
            // Channel Context Defaults
            channel.contentType == StreamContentType.LIVE_TV ->
                "MPEG-TS / HLS Auto" to ""
            else ->
                "VOD Container Auto" to ""
        }

        val needsVlc = isExclusivelyVlcProtocolOrCodec(
            url = targetUrl,
            videoCodecHint = vCodec,
            audioCodecHint = aCodec,
            containerExtension = channel.containerExtension,
            name = channel.name
        )

        return DetectedStreamSpec(
            resolvedUrl = targetUrl,
            containerFormat = container,
            mimeType = mime,
            videoCodecHint = vCodec,
            audioCodecHint = aCodec,
            preferSoftwareDecoder = needsVlc
        )
    }

    suspend fun probeStreamEndpoint(
        channel: TvChannel,
        targetUrl: String,
        userAgent: String
    ): DetectedStreamSpec = withContext(Dispatchers.IO) {
        val baseSpec = detectFastFromUrlAndMetadata(channel, targetUrl, userAgent)
        if (targetUrl.startsWith("rtsp://", ignoreCase = true) ||
            targetUrl.startsWith("rtmp://", ignoreCase = true) ||
            targetUrl.startsWith("udp://", ignoreCase = true)
        ) {
            return@withContext baseSpec
        }

        val startMs = SystemClock.elapsedRealtime()
        try {
            val reqBuilder = IboPlayerProfileSpoofer.applyIboApiHeaders(
                builder = Request.Builder().url(targetUrl).header("Range", "bytes=0-511"),
                targetUrl = targetUrl,
                serverBaseUrl = channel.streamBaseUrl,
                userAgent = userAgent,
                useIdentityEncoding = false
            )
            if (!channel.referer.isNullOrBlank()) reqBuilder.header("Referer", channel.referer)
            if (!channel.origin.isNullOrBlank()) reqBuilder.header("Origin", channel.origin)
            if (!channel.cookie.isNullOrBlank()) reqBuilder.header("Cookie", channel.cookie)

            probeHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val contentTypeHeader = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                val setCookie = resp.headers("Set-Cookie").firstOrNull()?.substringBefore(";")?.trim()

                val magicBuf = ByteArray(512)
                var readLen = 0
                resp.body?.byteStream()?.use { ins ->
                    readLen = ins.read(magicBuf, 0, magicBuf.size).coerceAtLeast(0)
                }

                val magicAscii = if (readLen > 0) {
                    String(magicBuf, 0, readLen.coerceAtMost(128), Charsets.ISO_8859_1).trimStart()
                } else ""

                val (detectedContainer, detectedMime) = when {
                    contentTypeHeader.contains("dash+xml") || magicAscii.startsWith("<MPD", ignoreCase = true) || magicAscii.contains("<MPD", ignoreCase = true) ->
                        "MPEG-DASH (MPD)" to MimeTypes.APPLICATION_MPD
                    contentTypeHeader.contains("mpegurl") || contentTypeHeader.contains("x-mpegurl") || magicAscii.startsWith("#EXTM3U", ignoreCase = true) ->
                        "HLS (M3U8)" to MimeTypes.APPLICATION_M3U8
                    contentTypeHeader.contains("mp2t") || (readLen >= 189 && magicBuf[0] == 0x47.toByte() && magicBuf[188] == 0x47.toByte()) || (readLen >= 1 && magicBuf[0] == 0x47.toByte()) ->
                        "MPEG-TS" to MimeTypes.VIDEO_MP2T
                    contentTypeHeader.contains("matroska") || (readLen >= 4 && magicBuf[0] == 0x1A.toByte() && magicBuf[1] == 0x45.toByte() && magicBuf[2] == 0xDF.toByte() && magicBuf[3] == 0xA3.toByte()) -> {
                        if (contentTypeHeader.contains("webm") || magicAscii.lowercase(Locale.ROOT).contains("webm")) {
                            "WebM" to MimeTypes.VIDEO_WEBM
                        } else {
                            "MKV (Matroska)" to MimeTypes.VIDEO_MATROSKA
                        }
                    }
                    contentTypeHeader.contains("video/mp4") || contentTypeHeader.contains("quicktime") || (readLen >= 8 && String(magicBuf, 4, 4, Charsets.ISO_8859_1) == "ftyp") ->
                        "MP4 / MOV" to MimeTypes.VIDEO_MP4
                    contentTypeHeader.contains("msvideo") || contentTypeHeader.contains("x-msvideo") || (magicAscii.startsWith("RIFF") && magicAscii.contains("AVI")) ->
                        "AVI" to MimeTypes.VIDEO_AVI
                    contentTypeHeader.contains("x-flv") || magicAscii.startsWith("FLV") ->
                        "FLV" to MimeTypes.VIDEO_FLV
                    else -> baseSpec.containerFormat to baseSpec.mimeType
                }

                val elapsed = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
                TiviMateDiagnostics.logEvent(
                    "🔍 فحص الصيغة [${channel.name.take(22)}]: $detectedContainer • ${baseSpec.videoCodecHint}/${baseSpec.audioCodecHint} (${elapsed}ms)"
                )

                baseSpec.copy(
                    resolvedUrl = finalUrl.ifBlank { targetUrl },
                    containerFormat = detectedContainer,
                    mimeType = detectedMime,
                    freshCookie = setCookie ?: channel.cookie,
                    probeLatencyMs = elapsed,
                    diagnosticNote = "$detectedContainer • ${baseSpec.videoCodecHint}"
                )
            }
        } catch (_: Exception) {
            baseSpec
        }
    }
}

/**
 * Real-time Performance, Memory, SQLite Storage & Sync Diagnostics Logger.
 */
object TiviMateDiagnostics {
    private val logs = CopyOnWriteArrayList<String>()
    private const val MAX_LOGS = 40

    @Volatile
    var lastQueryTimeMs: Long = 2L
        private set

    @Volatile
    var lastSyncDurationMs: Long = 0L
        private set

    @Volatile
    var lastSyncSummary: String = "المكتبة المحلية جاهزة"
        private set

    private val sensitiveParamRegex = Regex("(?i)(password|pass|token|auth|cookie|key|secret)=([^&\\s]+)")

    fun sanitizeLogMessage(raw: String): String {
        return sensitiveParamRegex.replace(raw) { matchResult ->
            "${matchResult.groupValues[1]}=***"
        }
    }

    fun logEvent(message: String) {
        val safeMsg = sanitizeLogMessage(message)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logs.add(0, "[$timeStr] $safeMsg")
        while (logs.size > MAX_LOGS) {
            logs.removeAt(logs.lastIndex)
        }
    }

    fun recordQueryLatency(ms: Long) {
        lastQueryTimeMs = ms
    }

    fun recordSyncStats(sourceName: String, stats: DifferentialSyncStats) {
        lastSyncDurationMs = stats.durationMs
        lastSyncSummary = "$sourceName: +${stats.addedCount} جديد • ~${stats.updatedCount} محدّث • -${stats.removedCount} محذوف • =${stats.unchangedCount} ثابت (${stats.durationMs}ms)"
        logEvent("✅ مزامنة تفاضلية [$lastSyncSummary]")
    }

    suspend fun buildSnapshot(context: Context): DatabasePerformanceMetrics {
        val db = TiviMateLocalDatabase.getInstance(context)
        val (liveCnt, movieCnt, seriesCnt) = db.getCountsSummary()
        val rt = Runtime.getRuntime()
        val usedMb = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
        val maxMb = (rt.maxMemory() / (1024 * 1024)).toInt()

        return DatabasePerformanceMetrics(
            totalChannelsInDb = liveCnt,
            totalMoviesInDb = movieCnt,
            totalSeriesInDb = seriesCnt,
            totalBouquetsInDb = 0,
            dbFileSizeMb = db.getDatabaseSizeMb(),
            thumbCacheSizeMb = FluxBitmapCache.getCacheSizeMb(context),
            ramUsedMb = usedMb,
            ramMaxMb = maxMb,
            lastQueryTimeMs = lastQueryTimeMs,
            lastSyncDurationMs = lastSyncDurationMs,
            lastSyncSummary = lastSyncSummary,
            supportedVideoCodecs = listOf("H.264/AVC", "H.265/HEVC (8/10-bit)", "MPEG-2 (Broadcast TS)", "MPEG-4 / XviD / DivX", "AV1", "VP9/VP8", "VC-1 / WMV", "RealVideo (RMVB)", "FLV"),
            supportedAudioCodecs = listOf("AAC (LC/HE)", "AC-3 (Dolby)", "E-AC-3 (Dolby+ / Atmos)", "DTS / DTS-HD / DTS:X", "MP2 (Broadcast)", "MP3", "Opus", "FLAC", "TrueHD", "WMA", "PCM"),
            recentDiagnosticLogs = logs.toList()
        )
    }
}
