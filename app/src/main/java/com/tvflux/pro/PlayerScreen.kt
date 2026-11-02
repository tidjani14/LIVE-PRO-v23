@file:Suppress("DEPRECATION")

package com.tvflux.pro

import android.app.Activity
import android.app.ActivityManager
import android.app.PictureInPictureParams
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale

data class TrackOption(
    val title: String,
    val subtitle: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val isSelected: Boolean,
    /** Special: -1 groupIndex means "disable this track type" (e.g. subtitles Off). */
    val isDisableOption: Boolean = false
)

/** Human-friendly language label for audio/subtitle tracks (ar/en/fr…). */
private fun friendlyTrackLanguageLabel(rawLang: String?, rawLabel: String? = null): String {
    val code = rawLang?.trim()?.lowercase(Locale.ROOT).orEmpty()
    val fromCode = when {
        code.isBlank() || code == "und" || code == "null" -> null
        code.startsWith("ar") || code == "ara" -> "العربية"
        code.startsWith("en") || code == "eng" -> "English"
        code.startsWith("fr") || code == "fra" || code == "fre" -> "Français"
        code.startsWith("es") || code == "spa" -> "Español"
        code.startsWith("de") || code == "deu" || code == "ger" -> "Deutsch"
        code.startsWith("it") || code == "ita" -> "Italiano"
        code.startsWith("tr") || code == "tur" -> "Türkçe"
        code.startsWith("pt") || code == "por" -> "Português"
        code.startsWith("ru") || code == "rus" -> "Русский"
        code.startsWith("hi") || code == "hin" -> "हिन्दी"
        code.startsWith("ur") || code == "urd" -> "اردو"
        code.startsWith("nl") || code == "dut" || code == "nld" -> "Nederlands"
        code.startsWith("pl") || code == "pol" -> "Polski"
        code.startsWith("ro") || code == "ron" || code == "rum" -> "Română"
        code.startsWith("zh") || code == "chi" || code == "zho" -> "中文"
        code.startsWith("ja") || code == "jpn" -> "日本語"
        code.startsWith("ko") || code == "kor" -> "한국어"
        else -> code.uppercase(Locale.ROOT)
    }
    val label = rawLabel?.trim()?.takeIf { it.isNotBlank() }
    return when {
        fromCode != null && label != null && !label.equals(fromCode, true) && !label.equals(code, true) ->
            "$fromCode ($label)"
        fromCode != null -> fromCode
        label != null -> label
        else -> "غير معروف"
    }
}

/**
 * Authentic IBO Player + VLC Hybrid User-Agent Rotation (v18.0):
 * Starts with official IBOPlayer/1.0 profile so IPTV servers that require IBO Player accept the stream
 * immediately on attempt #1, and rotates through IBOPlayerPro, OkHttp, and VLC on fallback retries.
 */
private val STREAM_USER_AGENTS = IboPlayerProfileSpoofer.STREAM_PLAYBACK_USER_AGENTS

/**
 * Custom Ultra-Fast MPD (DASH) Manifest Parser:
 * Ensures dynamic live .mpd manifests refresh every ~2.4 seconds even when servers roll segments
 * every 3 seconds or set an overly long/missing minimumUpdatePeriod attribute!
 */
@OptIn(UnstableApi::class)
private class FastRefreshDashManifestParser : DashManifestParser() {
    override fun parse(uri: Uri, inputStream: InputStream): DashManifest {
        val rawBytes = inputStream.readBytes()
        val xmlText = String(rawBytes, Charsets.UTF_8)
        val patchedXml = if (xmlText.contains("type=\"dynamic\"", ignoreCase = true)) {
            if (xmlText.contains("minimumUpdatePeriod=", ignoreCase = true)) {
                xmlText.replace(
                    Regex("minimumUpdatePeriod\\s*=\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE),
                    "minimumUpdatePeriod=\"PT2.4S\""
                )
            } else {
                xmlText.replaceFirst(
                    Regex("<MPD\\b", RegexOption.IGNORE_CASE),
                    "<MPD minimumUpdatePeriod=\"PT2.4S\""
                )
            }
        } else {
            xmlText
        }
        return super.parse(uri, ByteArrayInputStream(patchedXml.toByteArray(Charsets.UTF_8)))
    }
}

@OptIn(UnstableApi::class)
private fun buildClearKeyDrmManager(drmKeyString: String): DefaultDrmSessionManager? {
    val clean = drmKeyString.trim()
    if (clean.isEmpty()) return null
    return runCatching {
        val jwkJson = if (clean.startsWith("{")) {
            clean
        } else if (clean.contains(":")) {
            val kidHex = clean.substringBefore(":").trim()
            val keyHex = clean.substringAfter(":").trim()
            val kidB64 = hexToBase64Url(kidHex)
            val keyB64 = hexToBase64Url(keyHex)
            "{\"keys\":[{\"kty\":\"oct\",\"k\":\"$keyB64\",\"kid\":\"$kidB64\"}],\"type\":\"temporary\"}"
        } else {
            return null
        }
        val callback = LocalMediaDrmCallback(jwkJson.toByteArray(Charsets.UTF_8))
        DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(false)
            .build(callback)
    }.getOrNull()
}

private fun hexToBase64Url(hex: String): String {
    val clean = hex.replace("-", "").trim()
    val len = clean.length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len - 1) {
        data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
        i += 2
    }
    return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}

// Solution 4.2: Safe ExoPlayer Wrappers to prevent IllegalStateException after release or during state transitions
@OptIn(UnstableApi::class)
fun ExoPlayer.safePlay() {
    runCatching {
        if (playbackState == Player.STATE_IDLE) {
            prepare()
        }
        play()
    }
}

@OptIn(UnstableApi::class)
fun ExoPlayer.safePause() {
    runCatching { pause() }
}

@OptIn(UnstableApi::class)
fun ExoPlayer.safeStop() {
    runCatching {
        playWhenReady = false
        stop()
    }
}

@OptIn(UnstableApi::class)
fun ExoPlayer.safeSeekTo(pos: Long) {
    if (pos < 0L) return
    runCatching { seekTo(pos) }
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channel: TvChannel,
    siblingChannels: List<TvChannel>,
    allChannels: List<TvChannel>,
    precomputedBouquets: List<Pair<String, List<TvChannel>>> = emptyList(),
    favorites: List<TvChannel>,
    xtreamPlaylists: List<XtreamAccountConfig> = emptyList(),
    preferences: PlayerPreferences,
    isFavorite: Boolean,
    initialResumePositionMs: Long = 0L,
    onSaveVodProgress: (TvChannel, Long, Long) -> Unit = { _, _, _ -> },
    onToggleFavorite: (TvChannel) -> Unit,
    onUpdatePreferences: (PlayerPreferences) -> Unit,
    onSelectChannel: (TvChannel) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lang = preferences.appLanguage

    var showControls by remember(channel.stableKey) { mutableStateOf(false) }
    var showQuickDrawer by remember(channel.stableKey) { mutableStateOf(false) }
    var drawerSelectedBouquet by remember(channel.stableKey) { mutableStateOf(channel.group) }
    var drawerOpenTimestampMs by remember { mutableStateOf(0L) }
    var focusFirstChannelTrigger by remember { mutableStateOf(0) }
    var focusBouquetTrigger by remember { mutableStateOf(0) }

    var showTracksDialog by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(preferences.resizeMode) }
    var playbackSpeed by remember { mutableStateOf(PlaybackSpeedOption.NORMAL) }

    var isBuffering by remember { mutableStateOf(true) }
    var showBufferingBadge by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var errorMessage by remember(channel.stableKey) { mutableStateOf<String?>(null) }
    var lastErrorCodeName by remember(channel.stableKey) { mutableStateOf("") }
    var fastFallbackTriggered by remember(channel.stableKey) { mutableStateOf(false) }
    // PRODUCTION: ExoPlayer absolute priority for ALL HTTP/HTTPS (Live / Movie / Series).
    // Local DB "requires VLC" only shortens failover timeout — NEVER forces VLC on open.
    // VLC starts immediately ONLY for exclusive non-HTTP protocols (RTMP/MMS/UDP/RTP) or exclusive codecs.
    val knownVlcFromLocalDb = remember(channel.stableKey, channel.url) {
        FastFormatPreferenceCache.doesChannelPreferVlc(channel.stableKey, channel.url)
    }
    val initialNeedsVlc = remember(channel.stableKey, channel.url) {
        StreamFormatDetectorAndRenewer.isExclusivelyVlcProtocolOrCodec(
            url = channel.url,
            videoCodecHint = channel.videoCodecHint,
            audioCodecHint = channel.audioCodecHint,
            containerExtension = channel.containerExtension,
            name = channel.name
        )
    }
    var codecRequiresVlc by remember(channel.stableKey) { mutableStateOf(initialNeedsVlc) }
    var forceVlcSoftwareVideo by remember(channel.stableKey) { mutableStateOf(initialNeedsVlc) }
    val forceExoSoftwareCodec = remember {
        java.util.concurrent.atomic.AtomicBoolean(preferences.preferSoftwareDecoder)
    }
    // Reset the decoder preference for every new channel. A failure on one stream must
    // never leave the shared ExoPlayer preferring software decoding for all later channels.
    LaunchedEffect(channel.stableKey, preferences.preferSoftwareDecoder) {
        forceExoSoftwareCodec.set(preferences.preferSoftwareDecoder)
    }
    var retryCount by remember(channel.stableKey) { mutableStateOf(0) }
    var videoResolutionLabel by remember(channel.stableKey) { mutableStateOf("") }
    var currentTracksSnapshot by remember { mutableStateOf(Tracks.EMPTY) }

    val mqVariants = remember(channel.stableKey) {
        if (channel.contentType == StreamContentType.LIVE_TV) {
            MultiQualityEngine.variantsForChannel(channel)
        } else emptyList()
    }
    val isMultiQualityChannel = mqVariants.size >= 2 || channel.playlistId == MultiQualityEngine.PLAYLIST_ID
    var activeMqVariant by remember(channel.stableKey) {
        mutableStateOf(
            if (isMultiQualityChannel) {
                MultiQualityEngine.getActiveVariant(channel.stableKey)
                    ?: MultiQualityEngine.pickBestVariant(channel)
                    ?: mqVariants.firstOrNull()
            } else null
        )
    }
    var mqSwitchGeneration by remember(channel.stableKey) { mutableStateOf(0) }
    var mqSwitchBanner by remember(channel.stableKey) { mutableStateOf<String?>(null) }

    LaunchedEffect(mqSwitchBanner, mqSwitchGeneration) {
        if (!mqSwitchBanner.isNullOrBlank()) {
            delay(4200L)
            mqSwitchBanner = null
        }
    }

    val activeStreamChannel = remember(channel, activeMqVariant) {
        activeMqVariant?.channel ?: channel
    }

    val matchingAccount = remember(activeStreamChannel.playlistId, xtreamPlaylists) {
        xtreamPlaylists.find { it.id == activeStreamChannel.playlistId }
    }
    var resolvedVodUrl by remember(channel.stableKey, activeStreamChannel.stableKey) { mutableStateOf<String?>(null) }
    val formatCandidateUrls = remember(channel.stableKey, activeStreamChannel, matchingAccount, resolvedVodUrl, mqSwitchGeneration) {
        val baseCands = activeStreamChannel.buildFormatCandidateUrls(matchingAccount).toMutableList()
        if (baseCands.isEmpty() && activeStreamChannel.url.isNotBlank()) {
            baseCands.add(activeStreamChannel.url.trim())
        }
        val real = resolvedVodUrl?.trim().orEmpty()
        if (real.isNotBlank()) {
            (listOf(real) + baseCands).distinct()
        } else {
            baseCands.distinct()
        }
    }
    var detectedSpecLabel by remember(channel.stableKey) {
        mutableStateOf(
            listOfNotNull(
                channel.videoCodecHint.takeIf { it.isNotBlank() },
                channel.audioCodecHint.takeIf { it.isNotBlank() }
            ).joinToString(" • ")
        )
    }
    var candidateIndex by remember(channel.stableKey) { mutableStateOf(0) }
    var formatAttemptMode by remember(channel.stableKey) { mutableStateOf(0) }
    var numberInputBuffer by remember { mutableStateOf("") }

    val rootPlayerFocusRequester = remember { FocusRequester() }
    val centerPlayFocusRequester = remember { FocusRequester() }
    val vodSeekBarFocusRequester = remember { FocusRequester() }
    val activeBouquetFocusRequester = remember { FocusRequester() }
    val firstChannelInBouquetFocusRequester = remember { FocusRequester() }
    val activeChannelFocusRequester = remember { FocusRequester() }

    var networkSpeedLabel by remember { mutableStateOf(NetworkSpeedMonitor.sampleCurrentSpeedLabel()) }
    var vodPositionMs by remember(channel.stableKey) { mutableStateOf(initialResumePositionMs.coerceAtLeast(0L)) }
    var vodDurationMs by remember(channel.stableKey) { mutableStateOf(0L) }
    var pendingResumeTargetMs by remember(channel.stableKey) { mutableStateOf(initialResumePositionMs.coerceAtLeast(0L)) }

    LaunchedEffect(showQuickDrawer, showTracksDialog, showControls) {
        if (!showQuickDrawer && !showTracksDialog && !showControls) {
            for (attempt in 1..3) {
                delay(55L)
                if (runCatching { rootPlayerFocusRequester.requestFocus() }.isSuccess) break
            }
        } else if (showControls && !showQuickDrawer && !showTracksDialog) {
            for (attempt in 1..4) {
                delay(55L)
                val targetFocus = if (channel.contentType != StreamContentType.LIVE_TV) {
                    vodSeekBarFocusRequester
                } else {
                    centerPlayFocusRequester
                }
                if (runCatching { targetFocus.requestFocus() }.isSuccess) break
            }
        }
    }

    var isSeamlessMqTransition by remember(channel.stableKey) { mutableStateOf(false) }

    LaunchedEffect(isBuffering, errorMessage, isSeamlessMqTransition) {
        if (isBuffering && errorMessage == null) {
            // Silent background transition (between qualities or between ExoPlayer <-> VLC):
            // Keep spinner completely hidden during silent transition so the viewer experiences zero visual disturbance!
            delay(if (isSeamlessMqTransition) 3200L else 1000L)
            if (isBuffering && !isSeamlessMqTransition) showBufferingBadge = true
        } else {
            showBufferingBadge = false
            if (!isBuffering) isSeamlessMqTransition = false
        }
    }

    val favLabel = AppStrings.favoritesLabel(lang)
    val allLabel = AppStrings.allBouquetsLabel(lang)

    // Instant O(1) reuse of precomputed bouquets from CatalogViewSnapshot (zero re-grouping lag on Player open!)
    val allBouquets = remember(precomputedBouquets, favorites, favLabel, channel.group, siblingChannels) {
        if (precomputedBouquets.isNotEmpty()) {
            precomputedBouquets.map { pair ->
                if (pair.first == favLabel) favLabel to favorites else pair
            }
        } else {
            listOf(channel.group to siblingChannels)
        }
    }

    val drawerChannels = remember(allBouquets, drawerSelectedBouquet, siblingChannels) {
        allBouquets.find { it.first == drawerSelectedBouquet }?.second
            ?: siblingChannels.ifEmpty {
                allBouquets.firstOrNull { !it.first.startsWith("★") && it.second.isNotEmpty() }?.second.orEmpty()
            }
    }

    val effectiveUrl = remember(formatCandidateUrls, candidateIndex) {
        formatCandidateUrls.getOrElse(candidateIndex.coerceIn(0, (formatCandidateUrls.size - 1).coerceAtLeast(0))) {
            channel.url
        }
    }

    DisposableEffect(Unit) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }
        }
        val insetsController = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insetsController?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val httpFactory = remember {
        OkHttpDataSource.Factory(ChannelRepository.streamHttpClient)
            .setUserAgent(STREAM_USER_AGENTS.first())
    }

    // Ultra-Fast Extractor Suite (v22.0):
    // - MODE_SINGLE_PMT + 188*600 timestamp search bytes locks onto MPEG-TS audio/video codecs 5x faster (<100ms)!
    // - FLAG_DETECT_ACCESS_UNITS fixes H.264/HEVC live channels that omit AUD NAL units (prevents frame drops & black screen).
    // - FLAG_WORKAROUND_IGNORE_EDIT_LISTS fixes MP4 VOD movies with broken edit lists.
    // 4K UHD & High-Bitrate MPEG-TS Extractor Suite (v24.0):
    // - Uses 188 * 3600 (676 KB) timestamp search window so 25–45 Mbps 4K HEVC raw MPEG-TS streams
    //   always locate their PCR timestamps without failing or stalling!
    // - Omits FLAG_DETECT_ACCESS_UNITS because synthetic H.264 AUD injection corrupts HEVC/H.265 4K NAL units.
    val extractorsFactory = remember {
        DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            .setTsExtractorMode(TsExtractor.MODE_MULTI_PMT)
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                    DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
            )
            .setTsExtractorTimestampSearchBytes(188 * 3600)
    }

    // Solution 2.4: Ultra-Fast LoadErrorHandlingPolicy (100ms micro-retry for transient HTTP/range checks, instant failover thereafter)
    val lightningProbeErrorPolicy = remember {
        object : DefaultLoadErrorHandlingPolicy(1) {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 1

            override fun getRetryDelayMsFor(
                loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
            ): Long {
                return if (loadErrorInfo.errorCount <= 1) 100L else C.TIME_UNSET
            }

            override fun getFallbackSelectionFor(
                fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
                loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
            ): LoadErrorHandlingPolicy.FallbackSelection? {
                val code = (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                val exclusionMs = when (code) {
                    403, 404, 410 -> 30_000L
                    500, 502, 503, 504 -> 5_000L
                    else -> return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
                }
                return when {
                    fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK) ->
                        LoadErrorHandlingPolicy.FallbackSelection(
                            LoadErrorHandlingPolicy.FALLBACK_TYPE_TRACK,
                            exclusionMs
                        )
                    fallbackOptions.isFallbackAvailable(LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION) ->
                        LoadErrorHandlingPolicy.FallbackSelection(
                            LoadErrorHandlingPolicy.FALLBACK_TYPE_LOCATION,
                            exclusionMs
                        )
                    else -> null
                }
            }
        }
    }

    val defaultMediaSourceFactory = remember {
        DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(lightningProbeErrorPolicy)
    }

    val fastDashMediaSourceFactory = remember {
        DashMediaSource.Factory(httpFactory)
            .setManifestParser(FastRefreshDashManifestParser())
            .setFallbackTargetLiveOffsetMs(3_500L)
            .setLoadErrorHandlingPolicy(lightningProbeErrorPolicy)
    }

    val fastHlsMediaSourceFactory = remember {
        HlsMediaSource.Factory(httpFactory)
            .setAllowChunklessPreparation(true)
            .setLoadErrorHandlingPolicy(lightningProbeErrorPolicy)
    }

    val isWeakDevice = remember(preferences.lowEndDeviceMode) {
        preferences.lowEndDeviceMode
    }

    val sharedAllocator = remember {
        DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    }

    val player = remember {
        // 4K UHD Hardware-First LoadControl:
        // - Sub-second first-frame gate (150ms–450ms) for instant channel opening without unnecessary pre-buffering.
        // - Generous 36MB–64MB buffer ceiling so 25–45 Mbps 4K HEVC 60fps streams never hit buffer-size deadlocks.
        val bp = preferences.bufferProfile
        val isLowRam = DevicePerformanceDetector.isLowRamOrWeakDevice(context)
        val safeTargetBytes = if (isLowRam) {
            36 * 1024 * 1024
        } else {
            bp.targetBufferBytes.coerceIn(48 * 1024 * 1024, 64 * 1024 * 1024)
        }
        // v23.2: sub-second live zap — start playback as soon as first keyframe arrives
        val minBufMs = bp.minBufferMs.coerceIn(800, 6_000)
        val maxBufMs = bp.maxBufferMs.coerceIn(12_000, 28_000).coerceAtLeast(minBufMs + 3_000)
        val startPlaybackBufMs = bp.playbackBufferMs.coerceIn(80, 280)
        val afterRebufferBufMs = bp.rebufferMs.coerceIn(500, 1_200)
        val backBufMs = bp.backBufferMs.coerceIn(2_000, 6_000)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                minBufMs,
                maxBufMs,
                startPlaybackBufMs,
                afterRebufferBufMs
            )
            .setTargetBufferBytes(safeTargetBytes)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(backBufMs, true)
            .setAllocator(sharedAllocator)
            .build()

        // Strict Hardware-First MediaCodecSelector for 4K UHD / HEVC Main10 / H.264 / AV1 / VP9:
        // - NEVER forces Software Decoder on high-definition or 4K video tracks (c2.android.hevc.decoder
        //   cannot decode 4K HEVC in real time and causes frozen frames / audio-only).
        // - Prioritizes dedicated SoC hardware decoders (Amlogic, Qualcomm, MediaTek, Rockchip, Broadcom, Hisilicon, Realtek).
        val smartCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val allDecoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            if (allDecoders.size <= 1) {
                allDecoders
            } else {
                val isVideoMime = mimeType.startsWith("video/", ignoreCase = true)
                val isHighResCodec = mimeType.contains("hevc", ignoreCase = true) ||
                    mimeType.contains("dolby-vision", ignoreCase = true) ||
                    mimeType.contains("av01", ignoreCase = true) ||
                    mimeType.contains("vp9", ignoreCase = true) ||
                    mimeType.contains("avc", ignoreCase = true)
                val (swList, hwList) = allDecoders.partition { info ->
                    val lowName = info.name.lowercase(Locale.ROOT)
                    info.softwareOnly ||
                        lowName.startsWith("omx.google.") ||
                        lowName.startsWith("c2.android.") ||
                        lowName.contains(".sw.") ||
                        lowName.contains("ffmpeg")
                }
                val rankedHw = hwList.sortedBy { info ->
                    val n = info.name.lowercase(Locale.ROOT)
                    when {
                        !n.contains("secure") && (info.hardwareAccelerated || n.startsWith("c2.") || n.startsWith("omx.")) -> 0
                        else -> 1
                    }
                }
                val isAudioMime = mimeType.startsWith("audio/", ignoreCase = true)
                val isStandardPcmAudio = isAudioMime && (
                    mimeType.contains("mp4a", ignoreCase = true) ||
                        mimeType.contains("aac", ignoreCase = true) ||
                        mimeType.contains("mpeg", ignoreCase = true) ||
                        mimeType.contains("opus", ignoreCase = true) ||
                        mimeType.contains("vorbis", ignoreCase = true) ||
                        mimeType.contains("flac", ignoreCase = true) ||
                        mimeType.contains("raw", ignoreCase = true)
                    )
                // - For standard Audio codecs (AAC/MP3/Opus/Vorbis/FLAC): prefer Google's PCM software decoders first
                //   so vendor HDMI-bitstream passthrough decoders never mute sound on stereo TV/phone speakers!
                // - For Video codecs (4K HEVC/H.264/AV1/VP9): ALWAYS prioritize Hardware Decoders first!
                if (isStandardPcmAudio && swList.isNotEmpty()) {
                    swList + rankedHw
                } else if (preferences.preferSoftwareDecoder && forceExoSoftwareCodec.get() && (!isVideoMime || !isHighResCodec)) {
                    swList + rankedHw
                } else {
                    rankedHw + swList
                }
            }
        }

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setMediaCodecSelector(smartCodecSelector)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(true)
            setEnableAudioFloatOutput(false)
            setEnableAudioTrackPlaybackParams(true)
        }

        // 4K UHD Video + Strict Audio Capability TrackSelector:
        // - Keeps setExceedVideoConstraintsIfNecessary(true) for 4K UHD video.
        // - Keeps setExceedRendererCapabilitiesIfNecessary(false) so DefaultTrackSelector NEVER selects an
        //   unsupported 5.1/AC3/E-AC3 audio track when a supported Stereo AAC track is available!
        val trackSelector = DefaultTrackSelector(context).apply {
            val params = buildUponParameters()
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(true)
                .setAllowAudioMixedSampleRateAdaptiveness(true)
                .setAllowAudioMixedChannelCountAdaptiveness(true)
                .setMinVideoSize(320, 180)
                .setMaxVideoSize(7680, 4320)
                .setMinVideoBitrate(50_000)
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .setMinVideoFrameRate(1)
                .setExceedRendererCapabilitiesIfNecessary(false)
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedAudioConstraintsIfNecessary(true)
            setParameters(params)
        }

        val bwMeter = DefaultBandwidthMeter.getSingletonInstance(context)
        ExoPlayer.Builder(context, renderersFactory)
            .setBandwidthMeter(bwMeter)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(defaultMediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
    val bandwidthMeter = remember { DefaultBandwidthMeter.getSingletonInstance(context) }

    var activeEngine by remember(preferences.playerEngine) {
        mutableStateOf(preferences.playerEngine)
    }
    val vlcAvailable = remember { VlcEngineBridge.isLibVlcRuntimeAvailable() }
    var vlcFallbackToExo by remember(channel.stableKey, activeEngine) { mutableStateOf(false) }
    // PRODUCTION absolute priority: ExoPlayer first. VLC only after real Exo failure (codecRequiresVlc),
    // or exclusive non-HTTP protocols already captured in initialNeedsVlc, or user forced VLC_PLAYER in settings.
    val useVlcEngine = vlcAvailable && !vlcFallbackToExo &&
        (codecRequiresVlc || activeEngine == InternalPlayerEngine.VLC_PLAYER)
    val vlcController = remember {
        EmbeddedVlcController(context).apply {
            onBufferingChanged = { buffering -> isBuffering = buffering }
            onPlayingChanged = { playing ->
                isPlaying = playing
                if (playing) {
                    isBuffering = false
                    errorMessage = null
                }
            }
            onVoutStarted = {
                isBuffering = false
                errorMessage = null
            }
            onErrorOccurred = { reason ->
                if (reason == "VLC_INIT_FAILED") {
                    vlcFallbackToExo = true
                } else if (formatCandidateUrls.size > 1 && candidateIndex < formatCandidateUrls.size - 1 && candidateIndex < 3) {
                    // Try alternate URL format (.m3u8 / raw / .ts) silently inside VLC
                    isSeamlessMqTransition = true
                    candidateIndex += 1
                    errorMessage = null
                } else if (isMultiQualityChannel && mqVariants.size >= 2) {
                    val decision = MultiQualityEngine.reportAdaptiveEvent(
                        multiStableKey = channel.stableKey,
                        currentVariant = activeMqVariant,
                        reason = AdaptationReason.SOURCE_OUTAGE
                    )
                    if (decision != null && decision.switched) {
                        isSeamlessMqTransition = true
                        activeMqVariant = decision.variant
                        codecRequiresVlc = false
                        forceVlcSoftwareVideo = false
                        candidateIndex = 0
                        formatAttemptMode = 0
                        errorMessage = null
                        mqSwitchGeneration += 1
                    } else {
                        lastErrorCodeName = reason
                        fastFallbackTriggered = true
                        errorMessage = ""
                    }
                } else {
                    lastErrorCodeName = reason
                    fastFallbackTriggered = true
                    errorMessage = ""
                }
            }
        }
    }
    // Pre-warm LibVLC native singleton in background on Dispatchers.IO so if ExoPlayer needs silent instant handover to VLC,
    // LibVLC is already warm and switches in 0ms without any JNI loading pause!
    LaunchedEffect(vlcController, vlcAvailable) {
        if (vlcAvailable) {
            withContext(Dispatchers.IO) {
                VlcEngineBridge.getOrCreateSharedLibVlc(context)
            }
        }
    }
    LaunchedEffect(vlcController, useVlcEngine) {
        if (useVlcEngine && vlcAvailable) {
            FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
            vlcController.initializeSuspend()
        }
    }
    DisposableEffect(vlcController) {
        onDispose {
            vlcController.release()
        }
    }

    LaunchedEffect(player, vlcController, playbackSpeed, useVlcEngine) {
        player.playbackParameters = PlaybackParameters(playbackSpeed.speed)
        if (useVlcEngine) {
            vlcController.setRate(playbackSpeed.speed)
        }
    }

    LaunchedEffect(player, vlcController, channel.stableKey, activeStreamChannel.url, effectiveUrl, mqSwitchGeneration, formatAttemptMode, retryCount, useVlcEngine, forceVlcSoftwareVideo) {
        val sessionId = PlaybackSessionGuard.beginSession(channel.stableKey)
        if (!isSeamlessMqTransition) {
            isBuffering = true
        }
        errorMessage = null
        // For seamless Multi-Quality transitions inside ExoPlayer, do NOT stop/clear before setMediaSource
        // so PlayerView keeps the last video frame on screen with zero black flash!
        // v23.2 instant zap: free previous stream sockets before the new segment request
        runCatching {
            ChannelRepository.cancelPendingStreamRequests()
            ChannelRepository.evictIdleApiConnections()
        }
        runCatching {
            if (useVlcEngine || !isSeamlessMqTransition) {
                player.playWhenReady = false
                if (useVlcEngine) {
                    player.clearVideoSurface()
                }
                player.stop()
                player.clearMediaItems()
                sharedAllocator.trim()
            }
            player.volume = 1.0f
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .clearOverrides()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setSelectUndeterminedTextLanguage(true)
                .build()
        }

        val rotatedUa = activeStreamChannel.userAgent?.takeIf { it.isNotBlank() }
            ?: channel.userAgent?.takeIf { it.isNotBlank() }
            ?: STREAM_USER_AGENTS[retryCount % STREAM_USER_AGENTS.size]

        val fastSpec = StreamFormatDetectorAndRenewer.detectFastFromUrlAndMetadata(activeStreamChannel, effectiveUrl, rotatedUa)

        if (!PlaybackSessionGuard.isSessionCurrent(sessionId, channel.stableKey)) {
            return@LaunchedEffect
        }

        val mqBadgePrefix = activeMqVariant?.let { "جودة ${it.quality.labelAr} • " }.orEmpty()
        detectedSpecLabel = "$mqBadgePrefix${fastSpec.containerFormat} • ${fastSpec.videoCodecHint}"
        val rawPlayUrl = effectiveUrl.trim()
        val targetPlayUrl = rawPlayUrl.substringBefore("|").trim()
        val pipePart = if (rawPlayUrl.contains("|")) rawPlayUrl.substringAfter("|") else ""
        val low = targetPlayUrl.lowercase(Locale.ROOT).substringBefore("?")
        val fullLow = targetPlayUrl.lowercase(Locale.ROOT)

        val isMpdStream = fastSpec.mimeType == MimeTypes.APPLICATION_MPD ||
            low.endsWith(".mpd") || fullLow.contains("format=mpd") || fullLow.contains("type=mpd")
        val isExplicitHlsUrl = low.endsWith(".m3u8") ||
            fullLow.contains("output=m3u8") || fullLow.contains("type=m3u8") || fullLow.contains("output=hls")
        val isVodFile = channel.contentType != StreamContentType.LIVE_TV ||
            low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".avi") ||
            low.endsWith(".mov") || low.endsWith(".webm") || low.endsWith(".flv")

        // Direct, clean HTTP headers using activeStreamChannel so each variant uses its own server headers
        val customHeaders = IboPlayerProfileSpoofer.buildStreamHeadersMap(
            targetUrl = targetPlayUrl,
            serverBaseUrl = activeStreamChannel.streamBaseUrl.ifBlank { channel.streamBaseUrl },
            customReferer = activeStreamChannel.referer ?: channel.referer,
            customOrigin = activeStreamChannel.origin ?: channel.origin,
            customCookie = activeStreamChannel.cookie ?: channel.cookie,
            isLiveManifest = false
        )
        if (pipePart.isNotEmpty()) {
            pipePart.split("&", "|").forEach { kv ->
                val k = kv.substringBefore("=").trim().lowercase(Locale.ROOT)
                val v = kv.substringAfter("=", "").trim()
                if (v.isNotBlank()) {
                    when (k) {
                        "user-agent", "user_agent" -> customHeaders["User-Agent"] = v
                        "referer", "referrer" -> customHeaders["Referer"] = v
                        "origin" -> customHeaders["Origin"] = v
                        "cookie" -> customHeaders["Cookie"] = v
                    }
                }
            }
        }
        httpFactory.setDefaultRequestProperties(customHeaders)
        httpFactory.setUserAgent(rotatedUa)

        val itemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(targetPlayUrl))

        if (channel.drmKey.startsWith("http://", ignoreCase = true) || channel.drmKey.startsWith("https://", ignoreCase = true)) {
            itemBuilder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(channel.drmKey.trim())
                    .setMultiSession(false)
                    .build()
            )
        }

        if (!isVodFile) {
            // Lock live playback speed strictly at 1.0f (native broadcast frame rate) with a responsive 3.8s target offset
            // so HLS/TS channels start on the very first segment (<350ms) and NEVER speed-warp or stutter!
            val targetOffset = when {
                isMpdStream -> 3_200L
                retryCount > 0 -> 6_500L
                else -> 3_800L
            }
            val liveConfig = MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(targetOffset)
                .setMinOffsetMs(1_500L)
                .setMaxOffsetMs(60_000L)
                .setMinPlaybackSpeed(1.0f)
                .setMaxPlaybackSpeed(1.0f)
                .build()
            itemBuilder.setLiveConfiguration(liveConfig)
        }

        if (useVlcEngine && channel.drmKey.isBlank()) {
            val startPos = if (isVodFile) maxOf(pendingResumeTargetMs, vodPositionMs) else 0L
            val swMode = forceVlcSoftwareVideo || preferences.preferSoftwareDecoder
            detectedSpecLabel = "VLC All-Codec • ${fastSpec.containerFormat} • ${fastSpec.videoCodecHint}"
            vlcController.playStreamSuspend(
                url = targetPlayUrl,
                userAgent = rotatedUa,
                referer = activeStreamChannel.referer ?: channel.referer,
                cookie = activeStreamChannel.cookie ?: channel.cookie,
                isVod = isVodFile,
                forceSoftwareVideo = swMode,
                startPositionMs = startPos
            )
            return@LaunchedEffect
        } else {
            // Stop LibVLC before Exo prepares a new media item, avoiding dual audio/video sessions.
            vlcController.stop()
        }

        // Direct & flexible format adaptation (v23.0):
        // - Mode 0: Only set explicit MimeType for manifest protocols (.m3u8, .mpd, .ism, rtsp://).
        //   For ALL progressive files (.mp4, .mkv, .avi, .flv, .mov, .webm, .ts), leave mimeType null so
        //   DefaultExtractorsFactory sniffs the actual file magic bytes (ftyp, EBML, RIFF, 0x47) in <1ms!
        when (formatAttemptMode) {
            1 -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            2 -> itemBuilder.setMimeType(MimeTypes.VIDEO_MP2T)
            3 -> {
                // Pure byte-sniffing via DefaultExtractorsFactory without forced MimeType
            }
            else -> {
                when {
                    isMpdStream -> itemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
                    isExplicitHlsUrl -> itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
                    low.contains(".ism") -> itemBuilder.setMimeType(MimeTypes.APPLICATION_SS)
                    low.startsWith("rtsp://") -> itemBuilder.setMimeType(MimeTypes.APPLICATION_RTSP)
                }
            }
        }

        val mediaItem = itemBuilder.build()
        val clearKeyManager = if (channel.drmKey.isNotBlank() && !channel.drmKey.startsWith("http", ignoreCase = true)) {
            buildClearKeyDrmManager(channel.drmKey)
        } else null

        if (isMpdStream) {
            val dashFactory = if (clearKeyManager != null) {
                DashMediaSource.Factory(httpFactory)
                    .setManifestParser(FastRefreshDashManifestParser())
                    .setFallbackTargetLiveOffsetMs(2_800L)
                    .setDrmSessionManagerProvider { clearKeyManager }
                    .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
            } else {
                fastDashMediaSourceFactory
            }
            player.setMediaSource(dashFactory.createMediaSource(mediaItem))
        } else if (clearKeyManager != null) {
            val drmDefaultFactory = DefaultMediaSourceFactory(context, extractorsFactory)
                .setDataSourceFactory(httpFactory)
                .setDrmSessionManagerProvider { clearKeyManager }
                .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
            player.setMediaSource(drmDefaultFactory.createMediaSource(mediaItem))
        } else if ((isExplicitHlsUrl && formatAttemptMode == 0) || formatAttemptMode == 1) {
            // Chunkless HLS preparation opens .m3u8 live channels 2x faster!
            player.setMediaSource(fastHlsMediaSourceFactory.createMediaSource(mediaItem))
        } else {
            player.setMediaSource(defaultMediaSourceFactory.createMediaSource(mediaItem))
        }

        if (isVodFile && pendingResumeTargetMs > 2_000L) {
            player.seekTo(pendingResumeTargetMs)
        }
        player.playWhenReady = true
        player.prepare()
    }

    // 4K-Aware & Silent-Audio Recovery Watchdog (Works across Live TV, Movies, and Series!):
    // 1. Keeps ExoPlayer as the primary engine and gives 4K/HEVC streams full buffer time for their initial GOP.
    // 2. Monitors audio decoder counters while video is actively playing: if a channel, movie, or series plays
    //    video with ZERO decoded audio buffers (e.g. AC3/E-AC3/DTS/MP2/AAC-LATM or silent hardware audio decoder),
    //    automatically switches to an alternate audio track or hands over to LibVLC (FFmpeg Audio + HW Video) so sound is never lost!
    LaunchedEffect(player, channel.stableKey, activeStreamChannel.url, mqSwitchGeneration, candidateIndex, formatAttemptMode, effectiveUrl, retryCount, useVlcEngine) {
        if (useVlcEngine) return@LaunchedEffect

        val is4kOrHevcHint = activeMqVariant?.quality == StreamQualityRank.UHD_4K ||
            activeStreamChannel.name.contains("4k", ignoreCase = true) ||
            activeStreamChannel.name.contains("uhd", ignoreCase = true) ||
            activeStreamChannel.name.contains("hevc", ignoreCase = true) ||
            activeStreamChannel.name.contains("2160", ignoreCase = true) ||
            activeStreamChannel.group.contains("4k", ignoreCase = true) ||
            activeStreamChannel.group.contains("uhd", ignoreCase = true) ||
            detectedSpecLabel.contains("HEVC", ignoreCase = true) ||
            videoResolutionLabel.contains("4K", ignoreCase = true)

        // Buffering Watchdog (~180ms/tick): Exo gets real time before VLC.
        // Normal ~1.8s (10 ticks), 4K/HEVC ~3.2s (18 ticks). Known-VLC channels slightly shorter but still try Exo first.
        // v23.2 auto-resume: shorter freeze tolerance so a stuck live channel reconnects almost immediately
        val maxFrozenReadyTicks = when {
            knownVlcFromLocalDb && is4kOrHevcHint -> 6
            knownVlcFromLocalDb -> 4
            is4kOrHevcHint -> 9
            else -> 5
        }
        val maxInitialStallTicks = when {
            knownVlcFromLocalDb && is4kOrHevcHint -> 10
            knownVlcFromLocalDb -> 7
            is4kOrHevcHint -> 12
            else -> 6
        }

        var lastRenderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
        var lastDroppedFrames = player.videoDecoderCounters?.droppedBufferCount ?: 0
        var lastPosMs = player.currentPosition
        var lastBufferedPosMs = player.bufferedPosition
        var maxBufferSeenMs = 0L
        var bufferingStallTicks = 0
        var midStreamBufferingTicks = 0
        var recentRebufferEvents = 0
        var firstRebufferAtMs = 0L
        var continuousSmoothReadyTicks = 0
        var wasReadyInThisSession = false
        var frozenPictureTicks = 0
        var silentAudioTicks = 0
        var triedAlternateAudioTrack = false

        while (!useVlcEngine && errorMessage == null) {
            delay(180L) // Ultra-fast 180ms ABR telemetry loop for instant reaction
            if (useVlcEngine || errorMessage != null) break

            NetworkSpeedMonitor.updateExoBandwidthEstimate(bandwidthMeter.bitrateEstimate)

            if (!player.playWhenReady) {
                bufferingStallTicks = 0
                midStreamBufferingTicks = 0
                frozenPictureTicks = 0
                silentAudioTicks = 0
                lastRenderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                lastPosMs = player.currentPosition
                lastBufferedPosMs = player.bufferedPosition
                continue
            }

            val state = player.playbackState
            val renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
            val droppedFrames = player.videoDecoderCounters?.droppedBufferCount ?: 0
            val frameDelta = (renderedFrames - lastRenderedFrames).coerceAtLeast(0)
            val droppedDelta = (droppedFrames - lastDroppedFrames).coerceAtLeast(0)
            val currentPos = player.currentPosition
            val bufferedPos = player.bufferedPosition
            val bufferAheadMs = (bufferedPos - currentPos).coerceAtLeast(0L)
            if (bufferAheadMs > maxBufferSeenMs) maxBufferSeenMs = bufferAheadMs
            val posDelta = currentPos - lastPosMs
            val bufDelta = bufferedPos - lastBufferedPosMs
            val bufferVelocityMsPerSec = (bufDelta * 1000L) / 180L

            val audioCounters = player.audioDecoderCounters
            val renderedAudioBuffers = (audioCounters?.renderedOutputBufferCount ?: 0) +
                (audioCounters?.skippedOutputBufferCount ?: 0)

            lastRenderedFrames = renderedFrames
            lastDroppedFrames = droppedFrames
            lastPosMs = currentPos
            lastBufferedPosMs = bufferedPos

            if (isMultiQualityChannel) {
                MultiQualityEngine.updateTelemetrySnapshot(
                    stableKey = channel.stableKey,
                    activeVariant = activeMqVariant,
                    measuredSpeedKbps = MultiQualityEngine.currentMeasuredSpeedKbps(),
                    bufferAheadMs = bufferAheadMs,
                    bufferVelocityMsPerSec = bufferVelocityMsPerSec
                )
            }

            if (state == Player.STATE_READY) {
                bufferingStallTicks = 0
                midStreamBufferingTicks = 0
                if (frameDelta > 0) {
                    wasReadyInThisSession = true
                    continuousSmoothReadyTicks++
                }

                if (isMultiQualityChannel && mqVariants.size >= 2) {
                    val curVar = activeMqVariant
                    if (curVar != null && frameDelta > 0 && renderedFrames >= 20) {
                        MultiQualityEngine.markStablePlayback(channel.stableKey, curVar)
                    }

                    // 1) Real-Time Stream Flow & Pre-Freeze Buffer/Drop Guard (YouTube/Facebook ABR style):
                    //    Evaluates buffer drain velocity, dropped frames, and internet vs source bottleneck BEFORE freeze!
                    if (curVar != null && wasReadyInThisSession) {
                        val flowDecision = MultiQualityEngine.evaluateLiveFlowHealth(
                            multiStableKey = channel.stableKey,
                            currentVariant = curVar,
                            bufferAheadMs = if (maxBufferSeenMs >= 1_500L) bufferAheadMs else 2_000L,
                            bufferDeltaMs = bufDelta,
                            frameDelta = frameDelta,
                            droppedFrameDelta = droppedDelta,
                            measuredSpeedKbps = MultiQualityEngine.currentMeasuredSpeedKbps()
                        )
                        if (flowDecision != null && flowDecision.switched) {
                            isSeamlessMqTransition = true
                            activeMqVariant = flowDecision.variant
                            candidateIndex = 0
                            formatAttemptMode = 0
                            mqSwitchGeneration += 1
                            break
                        }
                    }

                    // 2) Proactive Real-Time Upgrade (LOW -> SD -> HD -> FHD -> 4K):
                    //    When current quality has been 100% smooth for ~4.5s (25 ticks, or 36 ticks ~6.5s for 4K step-up),
                    //    automatically upgrade to the next higher healthy quality!
                    // Upgrades only after long stable playback (~12–16s) — never mid-match noise
                    val upgradeThresholdTicks = if (curVar?.quality == StreamQualityRank.FHD) 90 else 70
                    if (curVar != null && continuousSmoothReadyTicks >= upgradeThresholdTicks) {
                        continuousSmoothReadyTicks = 0
                        val currentKbps = MultiQualityEngine.currentMeasuredSpeedKbps()
                        val upgradeDecision = MultiQualityEngine.tryProactiveUpgrade(
                            multiStableKey = channel.stableKey,
                            currentVariant = curVar,
                            bufferAheadMs = bufferAheadMs,
                            effectiveKbps = currentKbps
                        )
                        if (upgradeDecision != null && upgradeDecision.switched) {
                            isSeamlessMqTransition = true
                            activeMqVariant = upgradeDecision.variant
                            candidateIndex = 0
                            formatAttemptMode = 0
                            mqSwitchGeneration += 1
                            break
                        }
                    }
                }

                // A) Silent-Video Detector (Video frames advancing, but 0 audio buffers decoded!):
                if (renderedFrames >= 14 && (frameDelta > 0 || posDelta > 100L) && renderedAudioBuffers == 0) {
                    silentAudioTicks++
                    if (silentAudioTicks == 2 && !triedAlternateAudioTrack) {
                        val audioGroups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                        var switchedTrack = false
                        for (ag in audioGroups) {
                            for (idx in 0 until ag.length) {
                                if (!ag.isTrackSelected(idx) && ag.isTrackSupported(idx, false)) {
                                    triedAlternateAudioTrack = true
                                    switchedTrack = true
                                    player.trackSelectionParameters = player.trackSelectionParameters
                                        .buildUpon()
                                        .setOverrideForType(TrackSelectionOverride(ag.mediaTrackGroup, listOf(idx)))
                                        .build()
                                    break
                                }
                            }
                            if (switchedTrack) break
                        }
                    }
                    if (silentAudioTicks >= 3 && vlcAvailable && !vlcFallbackToExo && channel.drmKey.isBlank()) {
                        TiviMateDiagnostics.logEvent("🔊 تحويل صامت فوري لمحرك VLC لمعالجة الصوت لـ [${channel.name}]")
                        if (channel.contentType != StreamContentType.LIVE_TV && currentPos > 2_000L) {
                            pendingResumeTargetMs = maxOf(pendingResumeTargetMs, currentPos)
                            vodPositionMs = maxOf(vodPositionMs, currentPos)
                        }
                        isSeamlessMqTransition = true
                        FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
                        forceVlcSoftwareVideo = false
                        codecRequiresVlc = true
                        player.stop()
                        break
                    }
                } else if (renderedAudioBuffers > 0) {
                    silentAudioTicks = 0
                }

                // B) Frozen-Video Detector (Audio advancing while 0 video frames are rendered):
                if (channel.contentType == StreamContentType.LIVE_TV) {
                    if (frameDelta == 0 && posDelta > 100L) {
                        frozenPictureTicks++
                        val freezeLimit = if (isMultiQualityChannel && mqVariants.size >= 2) {
                            maxFrozenReadyTicks.coerceAtLeast(6)
                        } else maxFrozenReadyTicks
                        if (frozenPictureTicks >= freezeLimit) {
                            if (isMultiQualityChannel && mqVariants.size >= 2) {
                                val decision = MultiQualityEngine.reportAdaptiveEvent(
                                    multiStableKey = channel.stableKey,
                                    currentVariant = activeMqVariant,
                                    reason = AdaptationReason.SOURCE_CONGESTION
                                )
                                if (decision != null && decision.switched) {
                                    isSeamlessMqTransition = true
                                    activeMqVariant = decision.variant
                                    candidateIndex = 0
                                    formatAttemptMode = 0
                                    mqSwitchGeneration += 1
                                    break
                                }
                            }
                            if (formatCandidateUrls.size > 1 && candidateIndex < 1) {
                                isSeamlessMqTransition = true
                                candidateIndex++
                                formatAttemptMode = 0
                                break
                            } else if (vlcAvailable && !vlcFallbackToExo && channel.drmKey.isBlank()) {
                                isSeamlessMqTransition = true
                                FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
                                forceVlcSoftwareVideo = false
                                codecRequiresVlc = true
                                player.stop()
                                break
                            }
                        }
                    } else if (frameDelta > 0) {
                        frozenPictureTicks = 0
                    }
                }
            } else if (state == Player.STATE_BUFFERING && channel.contentType == StreamContentType.LIVE_TV) {
                frozenPictureTicks = 0
                continuousSmoothReadyTicks = 0

                if (wasReadyInThisSession) {
                    // Instant Mid-Stream Rebuffer Reaction:
                    // Do NOT wait! Within 1-2 fast ticks (180ms-360ms) of buffering after playback started,
                    // step down immediately so the viewer never experiences a long freeze!
                    if (midStreamBufferingTicks == 0) {
                        val nowMs = android.os.SystemClock.elapsedRealtime()
                        if (nowMs - firstRebufferAtMs > 18_000L) {
                            recentRebufferEvents = 1
                            firstRebufferAtMs = nowMs
                        } else {
                            recentRebufferEvents += 1
                        }
                    }
                    midStreamBufferingTicks++

                    val curVar = activeMqVariant
                    val currentKbps = MultiQualityEngine.currentMeasuredSpeedKbps()
                    val minNeeded = curVar?.quality?.minSpeedKbps ?: 2_000L
                    val isInternetWeak = currentKbps in 1L..((minNeeded * 85L) / 100L)
                    val fastTriggerTicks = if (recentRebufferEvents >= 2 || isInternetWeak) 1 else 2

                    if (isMultiQualityChannel && mqVariants.size >= 2 && midStreamBufferingTicks >= fastTriggerTicks) {
                        val reason = if (isInternetWeak) {
                            AdaptationReason.INTERNET_FLUCTUATION
                        } else {
                            AdaptationReason.SOURCE_CONGESTION
                        }
                        val decision = MultiQualityEngine.reportAdaptiveEvent(
                            multiStableKey = channel.stableKey,
                            currentVariant = curVar,
                            reason = reason,
                            currentSpeedKbps = currentKbps
                        )
                        if (decision != null && decision.switched) {
                            isSeamlessMqTransition = true
                            activeMqVariant = decision.variant
                            candidateIndex = 0
                            formatAttemptMode = 0
                            midStreamBufferingTicks = 0
                            recentRebufferEvents = 0
                            mqSwitchGeneration += 1
                            break
                        }
                    }
                    // CRITICAL: auto-resume stuck live channel — never leave the picture frozen
                    // ~540–900ms of mid-stream BUFFERING after it was already playing => hard reconnect
                    val hardResumeTicks = if (recentRebufferEvents >= 2 || isInternetWeak) 3 else 5
                    if (midStreamBufferingTicks >= hardResumeTicks) {
                        isSeamlessMqTransition = true
                        midStreamBufferingTicks = 0
                        recentRebufferEvents = 0
                        errorMessage = null
                        runCatching {
                            ChannelRepository.cancelPendingStreamRequests()
                            ChannelRepository.evictIdleApiConnections()
                        }
                        if (formatCandidateUrls.size > 1 && candidateIndex < formatCandidateUrls.size - 1) {
                            candidateIndex += 1
                            formatAttemptMode = 0
                            break
                        }
                        // Force full stream reload on same URL (re-triggers play LaunchedEffect)
                        if (retryCount < preferences.maxReconnectRetries.coerceAtLeast(12)) {
                            retryCount++
                            break
                        } else if (vlcAvailable && !vlcFallbackToExo && channel.drmKey.isBlank()) {
                            FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
                            forceVlcSoftwareVideo = false
                            codecRequiresVlc = true
                            player.stop()
                            break
                        } else {
                            retryCount = 0
                            break
                        }
                    }
                }

                if (renderedFrames == 0 && posDelta <= 0L && bufDelta <= 0L) {
                    bufferingStallTicks++
                    // Fast 900ms (5 ticks of 180ms) initial open timeout for Multi-Quality variants
                    // Give Exo enough time even on multi-quality before stepping down / VLC
                    val multiStallLimit = if (isMultiQualityChannel && mqVariants.size >= 2) {
                        maxInitialStallTicks.coerceAtLeast(8)
                    } else {
                        maxInitialStallTicks
                    }
                    if (bufferingStallTicks >= multiStallLimit) {
                        if (isMultiQualityChannel && mqVariants.size >= 2) {
                            val curVar = activeMqVariant
                            val currentKbps = MultiQualityEngine.currentMeasuredSpeedKbps()
                            val minNeeded = curVar?.quality?.minSpeedKbps ?: 2_000L
                            val isInternetWeak = currentKbps in 1L..((minNeeded * 75L) / 100L)
                            val decision = MultiQualityEngine.reportAdaptiveEvent(
                                multiStableKey = channel.stableKey,
                                currentVariant = curVar,
                                reason = if (isInternetWeak) AdaptationReason.INTERNET_FLUCTUATION else AdaptationReason.SOURCE_CONGESTION,
                                currentSpeedKbps = currentKbps
                            )
                            if (decision != null && decision.switched) {
                                isSeamlessMqTransition = true
                                activeMqVariant = decision.variant
                                candidateIndex = 0
                                formatAttemptMode = 0
                                bufferingStallTicks = 0
                                mqSwitchGeneration += 1
                                break
                            }
                        }
                        if (formatCandidateUrls.size > 1 && candidateIndex < 1) {
                            isSeamlessMqTransition = true
                            candidateIndex++
                            formatAttemptMode = 0
                            bufferingStallTicks = 0
                            break
                        } else if (vlcAvailable && !vlcFallbackToExo && channel.drmKey.isBlank()) {
                            isSeamlessMqTransition = true
                            FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
                            forceVlcSoftwareVideo = false
                            codecRequiresVlc = true
                            player.stop()
                            break
                        } else if (retryCount < preferences.maxReconnectRetries) {
                            retryCount++
                            candidateIndex = (candidateIndex + 1) % formatCandidateUrls.size
                            formatAttemptMode = 0
                            break
                        }
                    }
                } else {
                    bufferingStallTicks = 0
                }
            }
        }
    }

    // VLC Hardware-First Guard: keeps Hardware Decoding active for all 4K/FHD/HD channels;
    // only falls back to FFmpeg software for low-bitrate legacy SD streams if hardware Vout fails after 4.0s.
    LaunchedEffect(useVlcEngine, channel.stableKey, effectiveUrl, forceVlcSoftwareVideo) {
        if (!useVlcEngine || forceVlcSoftwareVideo) return@LaunchedEffect
        val isHighResOr4K = channel.name.contains("4k", ignoreCase = true) ||
            channel.name.contains("uhd", ignoreCase = true) ||
            channel.name.contains("hevc", ignoreCase = true) ||
            channel.name.contains("fhd", ignoreCase = true) ||
            channel.name.contains("1080", ignoreCase = true) ||
            detectedSpecLabel.contains("HEVC", ignoreCase = true)
        if (isHighResOr4K) return@LaunchedEffect

        var lastPos = -1L
        var stuckTime = 0L

        repeat(10) {
            delay(450L)
            if (!useVlcEngine) return@LaunchedEffect

            val currentPos = vlcController.getTimeMs()
            if ((vlcController.hasRenderedVout || vlcController.hasRenderedFrame()) && currentPos > lastPos && lastPos >= 0L) {
                return@LaunchedEffect
            }

            if (currentPos == lastPos || !vlcController.hasRenderedVout) {
                stuckTime += 450L
            } else {
                stuckTime = 0L
            }
            lastPos = currentPos

            if (stuckTime >= 4050L) {
                TiviMateDiagnostics.logEvent("⚡ تحويل VLC لـ FFmpeg software لقناة SD قديمة [${channel.name}]")
                forceVlcSoftwareVideo = true
                return@LaunchedEffect
            }
        }
    }

    // Smart Ticker: updates network speed ONLY when controls/buffering are visible (zero recomposition during fullscreen video!)
    LaunchedEffect(player, channel.stableKey) {
        var tickCount = 0
        while (true) {
            delay(1200L)
            if (showControls || isBuffering || errorMessage != null) {
                val sampled = NetworkSpeedMonitor.sampleCurrentSpeedLabel()
                if (sampled != networkSpeedLabel) networkSpeedLabel = sampled
            }
            if (channel.contentType != StreamContentType.LIVE_TV) {
                val pos = if (useVlcEngine) vlcController.getTimeMs() else player.currentPosition.coerceAtLeast(0L)
                val dur = if (useVlcEngine) vlcController.getLengthMs() else player.duration.let { if (it == C.TIME_UNSET || it < 0L) 0L else it }
                if (pos > 0L || pendingResumeTargetMs <= 0L) {
                    vodPositionMs = pos
                }
                if (dur > 0L) vodDurationMs = dur
                if (pendingResumeTargetMs > 2_000L && pos >= pendingResumeTargetMs - 2_000L) {
                    pendingResumeTargetMs = 0L
                }
                tickCount++
                if (tickCount % 2 == 0 && pos >= 2_000L) {
                    onSaveVodProgress(channel, pos, vodDurationMs)
                }
            }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    errorMessage = null
                    lastErrorCodeName = ""
                    fastFallbackTriggered = false
                    retryCount = 0
                    currentTracksSnapshot = player.currentTracks
                    if (channel.contentType != StreamContentType.LIVE_TV && pendingResumeTargetMs > 2_000L) {
                        if (player.currentPosition < pendingResumeTargetMs - 2_500L) {
                            player.seekTo(pendingResumeTargetMs)
                        }
                        pendingResumeTargetMs = 0L
                    }
                    if (channel.contentType == StreamContentType.LIVE_TV) {
                        FastFormatPreferenceCache.recordWorkingLiveUrl(channel.playlistId, effectiveUrl)
                    }
                } else if (playbackState == Player.STATE_ENDED && preferences.autoReconnect &&
                    channel.contentType == StreamContentType.LIVE_TV
                ) {
                    if (isMultiQualityChannel && mqVariants.size >= 2) {
                        val decision = MultiQualityEngine.reportAdaptiveEvent(
                            multiStableKey = channel.stableKey,
                            currentVariant = activeMqVariant,
                            reason = AdaptationReason.SOURCE_OUTAGE
                        )
                        if (decision != null && decision.switched) {
                            activeMqVariant = decision.variant
                            candidateIndex = 0
                            formatAttemptMode = 0
                            mqSwitchGeneration += 1
                            return
                        }
                    }
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentTracksSnapshot = tracks
                var vCodecLabel = ""
                var aCodecLabel = ""
                var hasVideoTrack = false
                var hasSupportedVideoTrack = false
                var hasAudioTrack = false
                var hasSupportedAudioTrack = false
                var fallbackSupportedAudioGroup: Tracks.Group? = null
                var fallbackSupportedAudioIndex = -1

                var firstVideoGroup: Tracks.Group? = null
                var anyVideoSelected = false
                var hasExceedsCapabilitiesVideo = false

                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_VIDEO) {
                        hasVideoTrack = true
                        if (firstVideoGroup == null && group.length > 0) {
                            firstVideoGroup = group
                        }
                        for (i in 0 until group.length) {
                            if (group.isTrackSupported(i, true)) {
                                hasSupportedVideoTrack = true
                            }
                            if (group.getTrackSupport(i) == C.FORMAT_EXCEEDS_CAPABILITIES) {
                                hasExceedsCapabilitiesVideo = true
                                hasSupportedVideoTrack = true
                            }
                            if (group.isTrackSelected(i)) {
                                anyVideoSelected = true
                                if (vCodecLabel.isEmpty()) {
                                    val fmt = group.getTrackFormat(i)
                                    val mime = fmt.sampleMimeType.orEmpty().lowercase()
                                    vCodecLabel = when {
                                        mime.contains("hevc") || mime.contains("h265") -> "HEVC/H.265"
                                        mime.contains("avc") || mime.contains("h264") -> "H.264/AVC"
                                        mime.contains("av01") || mime.contains("av1") -> "AV1"
                                        mime.contains("vp9") -> "VP9"
                                        mime.contains("mp2") || mime.contains("mpeg2") -> "MPEG-2"
                                        mime.contains("mp4v") || mime.contains("xvid") -> "MPEG-4/XviD"
                                        mime.isNotBlank() -> mime.substringAfter("/").uppercase()
                                        else -> ""
                                    }
                                }
                            }
                        }
                    } else if (group.type == C.TRACK_TYPE_AUDIO) {
                        hasAudioTrack = true
                        for (i in 0 until group.length) {
                            val fmt = group.getTrackFormat(i)
                            val mime = fmt.sampleMimeType.orEmpty().lowercase(Locale.ROOT)
                            // Check STRICT hardware/PCM support (allowExceedsCapabilities = false) so 5.1/AC3/E-AC3 tracks
                            // that exceed the device's stereo AudioTrack capabilities are never treated as supported!
                            val strictlySupported = group.isTrackSupported(i, false)
                            if (strictlySupported) {
                                hasSupportedAudioTrack = true
                                val isStereoOrMono = fmt.channelCount <= 2
                                val isStandardPcmCodec = mime.contains("mp4a") || mime.contains("aac") ||
                                    mime.contains("mpeg") || mime.contains("mp3") || mime.contains("opus") ||
                                    mime.contains("vorbis") || mime.contains("flac")
                                if (fallbackSupportedAudioGroup == null || (isStereoOrMono && isStandardPcmCodec)) {
                                    if (fallbackSupportedAudioGroup == null || fallbackSupportedAudioIndex < 0) {
                                        fallbackSupportedAudioGroup = group
                                        fallbackSupportedAudioIndex = i
                                    } else {
                                        val prevFmt = fallbackSupportedAudioGroup!!.getTrackFormat(fallbackSupportedAudioIndex)
                                        if (prevFmt.channelCount > 2 && isStereoOrMono) {
                                            fallbackSupportedAudioGroup = group
                                            fallbackSupportedAudioIndex = i
                                        }
                                    }
                                }
                            }
                            if (group.isTrackSelected(i) && aCodecLabel.isEmpty()) {
                                aCodecLabel = when {
                                    mime.contains("eac3") -> "E-AC3"
                                    mime.contains("ac3") -> "AC3"
                                    mime.contains("mp4a") || mime.contains("aac") -> "AAC"
                                    mime.contains("opus") -> "Opus"
                                    mime.contains("mpeg-l2") || mime.contains("mp2") -> "MP2"
                                    mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
                                    mime.contains("dts") -> "DTS"
                                    mime.contains("flac") -> "FLAC"
                                    mime.contains("vorbis") -> "Vorbis"
                                    mime.isNotBlank() -> mime.substringAfter("/").uppercase()
                                    else -> ""
                                }
                            }
                        }
                    }
                }

                // 1) Force-bind 4K UHD / HEVC 10-bit video track if TrackSelector left it unselected due to display/profile caps:
                if (hasVideoTrack && !anyVideoSelected && firstVideoGroup != null && (hasSupportedVideoTrack || hasExceedsCapabilitiesVideo)) {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(
                            TrackSelectionOverride(firstVideoGroup.mediaTrackGroup, listOf(0))
                        )
                        .build()
                }

                // 2) If a channel/movie/series has a strictly supported audio track, ensure a strictly supported audio track is selected!
                if (hasAudioTrack && hasSupportedAudioTrack && fallbackSupportedAudioGroup != null && fallbackSupportedAudioIndex >= 0) {
                    val anySelectedAudioStrictlySupported = tracks.groups.any { g ->
                        g.type == C.TRACK_TYPE_AUDIO && (0 until g.length).any { idx ->
                            g.isTrackSelected(idx) && g.isTrackSupported(idx, false)
                        }
                    }
                    if (!anySelectedAudioStrictlySupported) {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(
                                TrackSelectionOverride(fallbackSupportedAudioGroup.mediaTrackGroup, listOf(fallbackSupportedAudioIndex))
                            )
                            .build()
                    }
                }

                // 3) Instant Codec & Audio Rescue via LibVLC:
                //    - If the video codec is unsupported by ExoPlayer, OR
                //    - If the stream has an audio track (e.g. AC3, E-AC3, DTS, MP2, TrueHD, WMA, 5.1) that is NOT strictly
                //      supported by ExoPlayer's MediaCodec on this device (which would otherwise play Video with NO SOUND!):
                //    Immediately hand over to LibVLC (which has built-in FFmpeg software audio decoding + Hardware Video)!
                val emptyTracksInReady = tracks.groups.isNotEmpty() && !hasVideoTrack && !hasAudioTrack
                val videoUnsupported = hasVideoTrack && !hasSupportedVideoTrack && !hasExceedsCapabilitiesVideo
                val audioUnsupported = hasAudioTrack && !hasSupportedAudioTrack
                if ((emptyTracksInReady || videoUnsupported || audioUnsupported) && vlcAvailable && !useVlcEngine && !vlcFallbackToExo && channel.drmKey.isBlank()) {
                    if (channel.contentType != StreamContentType.LIVE_TV) {
                        val curPos = player.currentPosition
                        if (curPos > 2_000L) {
                            pendingResumeTargetMs = maxOf(pendingResumeTargetMs, curPos)
                            vodPositionMs = maxOf(vodPositionMs, curPos)
                        }
                    }
                    isSeamlessMqTransition = true
                    FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
                    forceVlcSoftwareVideo = false
                    codecRequiresVlc = true
                    player.stop()
                }

                val combinedCodec = listOfNotNull(
                    vCodecLabel.takeIf { it.isNotBlank() },
                    aCodecLabel.takeIf { it.isNotBlank() }
                ).joinToString(" • ")
                if (combinedCodec.isNotBlank()) {
                    detectedSpecLabel = combinedCodec
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing && channel.contentType != StreamContentType.LIVE_TV) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration.let { if (it == C.TIME_UNSET || it < 0L) vodDurationMs else it }
                    if (pos >= 5_000L) {
                        onSaveVodProgress(channel, pos, dur)
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val tag = when {
                        videoSize.height >= 2160 || videoSize.width >= 3840 -> "4K UHD"
                        videoSize.height >= 1080 -> "1080p FHD"
                        videoSize.height >= 720 -> "720p HD"
                        else -> "${videoSize.height}p SD"
                    }
                    videoResolutionLabel = "${videoSize.width}×${videoSize.height} • $tag"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
                    return
                }
                lastErrorCodeName = error.errorCodeName
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    ChannelRepository.clearHostSessionCookies(effectiveUrl)
                    ChannelRepository.clearStreamRedirectCache(effectiveUrl)
                }
                val isCodecError =
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
                val isContainerMismatch =
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED

                // ExoPlayer Primary + Instant Silent VLC Failover:
                // 1) If codec/decoder/container error or stream rejected by ExoPlayer, or after 1 quick format swap (.ts <-> .m3u8),
                //    hand over to LibVLC IMMEDIATELY and SILENTLY so single channels open fast without delay!
                if (isContainerMismatch && formatAttemptMode == 0 && !vlcAvailable) {
                    isSeamlessMqTransition = true
                    formatAttemptMode = if (effectiveUrl.contains(".m3u8", ignoreCase = true)) 2 else 1
                    errorMessage = null
                    return
                }
                val isHardIoFail =
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
                val canTryOneQuickAltFormatInExo = !isCodecError && !isContainerMismatch && !isHardIoFail &&
                    formatCandidateUrls.size > 1 &&
                    candidateIndex < 1
                if (canTryOneQuickAltFormatInExo) {
                    isSeamlessMqTransition = true
                    candidateIndex += 1
                    formatAttemptMode = 0
                    errorMessage = null
                } else if (isMultiQualityChannel && mqVariants.size >= 2) {
                    // Multi-Quality channel: switch silently to next healthy quality/source variant first!
                    val decision = MultiQualityEngine.reportAdaptiveEvent(
                        multiStableKey = channel.stableKey,
                        currentVariant = activeMqVariant,
                        reason = AdaptationReason.SOURCE_OUTAGE
                    )
                    if (decision != null && decision.switched) {
                        isSeamlessMqTransition = true
                        activeMqVariant = decision.variant
                        codecRequiresVlc = false
                        forceVlcSoftwareVideo = false
                        candidateIndex = 0
                        formatAttemptMode = 0
                        errorMessage = null
                        mqSwitchGeneration += 1
                        return
                    } else if (vlcAvailable && !useVlcEngine && !vlcFallbackToExo && activeEngine != InternalPlayerEngine.EXO_PLAYER) {
                        isSeamlessMqTransition = true
                        FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
                        forceVlcSoftwareVideo = false
                        codecRequiresVlc = true
                        errorMessage = null
                        player.stop()
                    } else {
                        fastFallbackTriggered = true
                        errorMessage = ""
                    }
                } else if (vlcAvailable && !useVlcEngine && !vlcFallbackToExo && activeEngine != InternalPlayerEngine.EXO_PLAYER) {
                    // Single (non-multi-quality) channel: instant silent switch to VLC on first ExoPlayer failure!
                    isSeamlessMqTransition = true
                    FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
                    forceVlcSoftwareVideo = false
                    codecRequiresVlc = true
                    errorMessage = null
                    player.stop()
                } else {
                    fastFallbackTriggered = true
                    errorMessage = ""
                }
            }
        }
        player.addListener(listener)
        onDispose {
            runCatching {
                if (channel.contentType != StreamContentType.LIVE_TV) {
                    val rawPos = if (useVlcEngine) vlcController.getTimeMs() else player.currentPosition.coerceAtLeast(0L)
                    val pos = maxOf(rawPos, vodPositionMs)
                    val rawDur = if (useVlcEngine) vlcController.getLengthMs() else player.duration.let { if (it == C.TIME_UNSET || it < 0L) 0L else it }
                    val dur = maxOf(rawDur, vodDurationMs)
                    if (pos >= 2_000L) {
                        onSaveVodProgress(channel, pos, dur)
                    }
                }
                ChannelRepository.cancelPendingStreamRequests()
                // Solution 4.2: Safe ordered teardown + allocator trim to prevent IllegalStateException & 4K buffer leaks
                player.playWhenReady = false
                player.clearVideoSurface()
                player.stop()
                player.clearMediaItems()
                player.removeListener(listener)
                player.release()
                sharedAllocator.trim()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        var wasPlayingBeforeBackground = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasPlayingBeforeBackground = player.isPlaying || player.playWhenReady
                    if (channel.contentType != StreamContentType.LIVE_TV) {
                        val pos = player.currentPosition.coerceAtLeast(0L)
                        val dur = player.duration.let { if (it == C.TIME_UNSET || it < 0L) vodDurationMs else it }
                        if (pos >= 5_000L) {
                            onSaveVodProgress(channel, pos, dur)
                        }
                    }
                    if (activity?.isInPictureInPictureMode != true) {
                        player.pause()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (wasPlayingBeforeBackground) {
                        if (channel.contentType == StreamContentType.LIVE_TV && player.playbackState == Player.STATE_IDLE) {
                            player.prepare()
                        }
                        player.play()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var playerDiagnosticAlert by remember(channel.stableKey) { mutableStateOf<DiagnosticAlertInfo?>(null) }

    LaunchedEffect(channel.stableKey, errorMessage, retryCount) {
        if (errorMessage != null && preferences.autoReconnect) {
            if (vlcAvailable && !useVlcEngine && !vlcFallbackToExo &&
                activeEngine != InternalPlayerEngine.EXO_PLAYER &&
                candidateIndex >= formatCandidateUrls.size - 1 &&
                retryCount >= 1
            ) {
                TiviMateDiagnostics.logEvent("⚡ انتقال احتياطي لمنقذ VLC الشامل بعد استنفاد محاولات ExoPlayer لـ [${channel.name}]")
                forceVlcSoftwareVideo = false
                codecRequiresVlc = true
                errorMessage = null
                return@LaunchedEffect
            }
            if (retryCount >= preferences.maxReconnectRetries) {
                val accName = matchingAccount?.serverName?.ifBlank { channel.playlistName } ?: channel.playlistName
                val accExp = matchingAccount?.expDate.orEmpty()
                playerDiagnosticAlert = when {
                    accExp.isNotBlank() && ExpiryAlertHelper.isDateAlreadyExpired(accExp) -> DiagnosticAlertInfo(
                        type = DiagnosticAlertType.ACCOUNT_EXPIRED,
                        title = "⚠️ انتهت صلاحية حساب القنوات ($accName)",
                        message = "انتهت فترة اشتراك القائمة ($accName) بتاريخ $accExp.\nيرجى تجديد اشتراك الحساب عبر التواصل مع مزود الخدمة ($ALI_SAT_PROVIDER_NAME : $ALI_SAT_PHONE_NUMBER) أو تحديث بيانات القائمة من فضاء الزبون.",
                        sourceName = accName,
                        expDate = accExp
                    )
                    !ExpiryAlertHelper.isInternetAvailable(context) -> DiagnosticAlertInfo(
                        type = DiagnosticAlertType.INTERNET_ERROR,
                        title = "📡 تنبيه: مشكلة في اتصال الإنترنت لديك",
                        message = "تعذّر الوصول إلى البث بسبب انقطاع أو ضعف في اتصال الإنترنت على جهازك.\nيرجى التحقق من شبكة الواي فاي (Wi-Fi) أو كابل الإنترنت والراوتر ثم المحاولة مجدداً.",
                        sourceName = accName
                    )
                    else -> DiagnosticAlertInfo(
                        type = DiagnosticAlertType.SERVER_OUTAGE,
                        title = "🛠️ تنبيه: توقف القناة من المصدر",
                        message = "القناة (${channel.name}) متوقفة حالياً من المصدر.",
                        sourceName = accName
                    )
                }
                errorMessage = AppStrings.tr(
                    lang,
                    "تعذّر فتح البث حالياً ($lastErrorCodeName)",
                    "Échec de lecture ($lastErrorCodeName)",
                    "Playback failed ($lastErrorCodeName)"
                )
                return@LaunchedEffect
            }
            // Lightning-fast 1ms switch while testing format candidates so fallback is instantaneous!
            val backoffMs = if (fastFallbackTriggered || retryCount < formatCandidateUrls.size) 1L else (80L * (retryCount - formatCandidateUrls.size + 1).coerceAtLeast(1)).coerceAtMost(400L)
            delay(backoffMs)
            if (channel.contentType == StreamContentType.MOVIE && channel.streamId > 0 && resolvedVodUrl == null) {
                val realUrl = XtreamRepository.resolveVodRealUrlIfNeeded(channel, matchingAccount)
                if (!realUrl.isNullOrBlank() && realUrl != effectiveUrl) {
                    resolvedVodUrl = realUrl
                    candidateIndex = 0
                    formatAttemptMode = 0
                    errorMessage = null
                    retryCount += 1
                    return@LaunchedEffect
                }
            }
            retryCount += 1

            if (
                fastFallbackTriggered &&
                formatAttemptMode == 0 &&
                effectiveUrl.contains(".m3u8", ignoreCase = true)
            ) {
                // Server returned raw MPEG-TS or MP4 bytes on a .m3u8 URL: immediately open with progressive extractor (mode 2)!
                formatAttemptMode = 2
            } else if (
                fastFallbackTriggered &&
                formatAttemptMode == 0 &&
                !effectiveUrl.contains(".m3u8", ignoreCase = true) &&
                channel.contentType == StreamContentType.LIVE_TV
            ) {
                // Server returned an #EXTM3U playlist on a .ts or extensionless URL: immediately open as HLS (mode 1)!
                formatAttemptMode = 1
            } else if (formatCandidateUrls.size > 1) {
                formatAttemptMode = 0
                candidateIndex = (candidateIndex + 1) % formatCandidateUrls.size
            } else if (formatAttemptMode < 2) {
                formatAttemptMode += 1
            } else {
                formatAttemptMode = 0
                errorMessage = null
                if (channel.contentType == StreamContentType.LIVE_TV) {
                    player.seekToDefaultPosition()
                }
                player.prepare()
                player.play()
            }
        }
    }

    LaunchedEffect(numberInputBuffer) {
        if (numberInputBuffer.isNotEmpty()) {
            delay(950L)
            val targetNum = numberInputBuffer.toIntOrNull()
            numberInputBuffer = ""
            if (targetNum != null) {
                val match = siblingChannels.find { it.number == targetNum }
                    ?: allChannels.find { it.number == targetNum }
                if (match != null) {
                    showControls = false
                    onSelectChannel(match)
                }
            }
        }
    }

    LaunchedEffect(showControls, isPlaying, showQuickDrawer, showTracksDialog, vodPositionMs) {
        if (showControls && isPlaying && !showQuickDrawer && !showTracksDialog && errorMessage == null) {
            delay(if (channel.contentType != StreamContentType.LIVE_TV) 6200L else 4000L)
            if (isPlaying) {
                showControls = false
            }
        }
    }

    // TV remote BACK: close overlays first and keep watching the SAME channel.
    // Never exit the player while the bouquet/channel drawer or controls are open.
    BackHandler(enabled = true) {
        when {
            showTracksDialog -> {
                showTracksDialog = false
            }
            showQuickDrawer -> {
                showQuickDrawer = false
                // Restore D-Pad focus to the video surface so the user continues watching immediately
            }
            showControls -> {
                showControls = false
            }
            else -> onBack()
        }
    }

    fun currentVodPositionMs(): Long {
        return if (useVlcEngine) {
            vlcController.getTimeMs().coerceAtLeast(0L)
        } else {
            player.currentPosition.coerceAtLeast(0L)
        }
    }

    fun seekVodTo(targetMs: Long) {
        val safeTarget = if (vodDurationMs > 0L) {
            targetMs.coerceIn(0L, vodDurationMs)
        } else {
            targetMs.coerceAtLeast(0L)
        }
        pendingResumeTargetMs = 0L
        vodPositionMs = safeTarget
        if (useVlcEngine) {
            vlcController.setTimeMs(safeTarget)
        } else {
            player.safeSeekTo(safeTarget)
        }
        if (channel.contentType != StreamContentType.LIVE_TV && safeTarget >= 2_000L) {
            onSaveVodProgress(channel, safeTarget, vodDurationMs)
        }
    }

    fun seekVodByOffset(deltaMs: Long) {
        val basePos = maxOf(currentVodPositionMs(), vodPositionMs)
        seekVodTo(basePos + deltaMs)
    }

    fun computeSmartSeekStepMs(repeatCount: Int): Long {
        return when {
            repeatCount <= 0 -> 15_000L
            repeatCount <= 4 -> 30_000L
            repeatCount <= 12 -> 60_000L
            else -> 180_000L
        }
    }

    fun switchRelativeChannel(offset: Int) {
        if (!SafeClickGuard.allowAction("PLAYER_ZAP", 130L)) return
        val list = siblingChannels.ifEmpty { allChannels }
        if (list.size <= 1) return
        val idx = list.indexOfFirst { it.stableKey == channel.stableKey }
        if (idx == -1) return
        val nextIdx = (idx + offset + list.size) % list.size
        showControls = false
        onSelectChannel(list[nextIdx])
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootPlayerFocusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val code = event.nativeKeyEvent.keyCode
                    val rep = event.nativeKeyEvent.repeatCount
                    // BACK while drawer/dialog/controls open => dismiss overlay, stay on same live channel
                    if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
                        when {
                            showTracksDialog -> {
                                showTracksDialog = false
                                return@onKeyEvent true
                            }
                            showQuickDrawer -> {
                                showQuickDrawer = false
                                return@onKeyEvent true
                            }
                            showControls -> {
                                showControls = false
                                return@onKeyEvent true
                            }
                        }
                    }
                    // While bouquet/channel drawer or tracks dialog has focus, let its own handlers process D-Pad
                    if (showQuickDrawer || showTracksDialog) {
                        return@onKeyEvent false
                    }
                    if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                        val digit = (code - KeyEvent.KEYCODE_0).toString()
                        if (numberInputBuffer.length < 4) {
                            numberInputBuffer += digit
                        }
                        return@onKeyEvent true
                    }
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (rep == 0) {
                                if (channel.contentType == StreamContentType.LIVE_TV) {
                                    if (!showQuickDrawer) {
                                        showControls = false
                                        // Open drawer on the CURRENT bouquet and focus the playing channel
                                        drawerSelectedBouquet = channel.group
                                        drawerOpenTimestampMs = android.os.SystemClock.elapsedRealtime()
                                        showQuickDrawer = true
                                        focusFirstChannelTrigger += 1
                                    } else {
                                        showQuickDrawer = false
                                    }
                                    true
                                } else if (!showControls) {
                                    showControls = true
                                    true
                                } else false
                            } else true
                        }
                        KeyEvent.KEYCODE_MENU,
                        KeyEvent.KEYCODE_INFO -> {
                            if (showQuickDrawer) {
                                showQuickDrawer = false
                            }
                            showControls = !showControls
                            true
                        }
                        KeyEvent.KEYCODE_BOOKMARK,
                        KeyEvent.KEYCODE_STAR,
                        KeyEvent.KEYCODE_CAPTIONS,
                        KeyEvent.KEYCODE_PROG_YELLOW -> {
                            onToggleFavorite(channel)
                            true
                        }
                        KeyEvent.KEYCODE_CHANNEL_UP -> {
                            switchRelativeChannel(-1)
                            true
                        }
                        KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                            switchRelativeChannel(1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (!showControls) {
                                if (channel.contentType != StreamContentType.LIVE_TV) {
                                    showControls = true
                                } else {
                                    switchRelativeChannel(-1)
                                }
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (!showControls) {
                                if (channel.contentType != StreamContentType.LIVE_TV) {
                                    showControls = true
                                } else {
                                    switchRelativeChannel(1)
                                }
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_MEDIA_REWIND,
                        KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                            if (channel.contentType != StreamContentType.LIVE_TV) {
                                showControls = true
                                seekVodByOffset(-computeSmartSeekStepMs(rep))
                                true
                            } else if (!showControls && !showQuickDrawer) {
                                drawerSelectedBouquet = channel.group
                                drawerOpenTimestampMs = android.os.SystemClock.elapsedRealtime()
                                showQuickDrawer = true
                                focusFirstChannelTrigger += 1
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                        KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                            if (channel.contentType != StreamContentType.LIVE_TV) {
                                showControls = true
                                seekVodByOffset(computeSmartSeekStepMs(rep))
                                true
                            } else if (!showControls && !showQuickDrawer) {
                                drawerSelectedBouquet = channel.group
                                drawerOpenTimestampMs = android.os.SystemClock.elapsedRealtime()
                                showQuickDrawer = true
                                focusFirstChannelTrigger += 1
                                true
                            } else false
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (useVlcEngine) {
                                if (vlcController.isPlaying()) vlcController.pause() else vlcController.play()
                            } else {
                                if (player.isPlaying) player.pause() else player.play()
                            }
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (showQuickDrawer) {
                    showQuickDrawer = false
                } else if (channel.contentType == StreamContentType.LIVE_TV && !showControls) {
                    drawerSelectedBouquet = channel.group
                    drawerOpenTimestampMs = android.os.SystemClock.elapsedRealtime()
                    showQuickDrawer = true
                    focusFirstChannelTrigger += 1
                } else {
                    showControls = !showControls
                }
            }
    ) {
        val isPhoneLandscape = maxHeight < 460.dp

        if (useVlcEngine && channel.drmKey.isBlank()) {
            VlcEmbeddedVideoSurface(
                controller = vlcController,
                resizeMode = resizeMode,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        this.useController = false
                        this.resizeMode = resizeMode.exoMode
                        this.setKeepContentOnPlayerReset(true)
                        this.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        this.keepScreenOn = true
                        this.isFocusable = false
                        this.isFocusableInTouchMode = false
                        this.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        // Multi-sub: show embedded text tracks (SRT/VTT/ASS inside MP4/MKV/HLS)
                        this.subtitleView?.apply {
                            setApplyEmbeddedStyles(true)
                            setApplyEmbeddedFontSizes(false)
                            setBottomPaddingFraction(0.08f)
                            setUserDefaultStyle()
                            setUserDefaultTextSize()
                            visibility = android.view.View.VISIBLE
                        }
                    }
                },
                update = { view ->
                    if (view.player !== player) {
                        view.player = player
                    }
                    if (view.resizeMode != resizeMode.exoMode) {
                        view.resizeMode = resizeMode.exoMode
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (numberInputBuffer.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .background(Color(0xEB101015), RoundedCornerShape(12.dp))
                    .border(2.dp, BeinHighlightPurple, RoundedCornerShape(12.dp))
                    .padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                Text(
                    text = numberInputBuffer,
                    color = Color.White,
                    fontSize = if (isPhoneLandscape) 20.sp else 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        if (preferences.showNetworkSpeed && (showControls || showBufferingBadge)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = if (showControls) 66.dp else 14.dp)
                    .background(Color(0xE6101015), RoundedCornerShape(10.dp))
                    .border(1.dp, EmeraldLive.copy(alpha = 0.7f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                val lockedQ = if (isMultiQualityChannel) MultiQualityEngine.getManualQuality(channel.stableKey) else null
                val activeQText = activeMqVariant?.let {
                    val modeTag = if (lockedQ == null) "Auto(${it.quality.labelAr})" else it.quality.labelAr
                    val healthPct = MultiQualityEngine.getVariantHealthScore(it)
                    " • 📺 $modeTag • صحة المصدر $healthPct%"
                }.orEmpty()
                Text(
                    text = "🌐 ⬇ $networkSpeedLabel$activeQText",
                    color = EmeraldLive,
                    fontSize = if (isPhoneLandscape) 10.sp else 12.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Silent Multi-Quality & Engine Switching: no popup banners over the video stream to avoid disturbing the viewer

        if (showBufferingBadge || !errorMessage.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC0D0D12), RoundedCornerShape(99.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(99.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    color = EmeraldLive,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(if (isPhoneLandscape) 18.dp else 20.dp)
                )
                Text(
                    text = errorMessage?.takeIf { it.isNotBlank() } ?: "⬇ $networkSpeedLabel",
                    color = Color.White,
                    fontSize = if (isPhoneLandscape) 11.sp else 13.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xE6030106),
                                Color(0x22030106),
                                Color(0x22030106),
                                Color(0xF0030106)
                            )
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(
                            horizontal = if (isPhoneLandscape) 12.dp else 20.dp,
                            vertical = if (isPhoneLandscape) 6.dp else 14.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        FocusableCircleButton(
                            onClick = onBack,
                            icon = Icons.Default.ArrowBack,
                            contentDescription = "رجوع",
                            sizeDp = if (isPhoneLandscape) 36.dp else 44.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        ChannelAvatar(
                            name = channel.name,
                            logoUrl = channel.logo,
                            modifier = Modifier.size(if (isPhoneLandscape) 42.dp else 54.dp),
                            cornerRadius = 11.dp,
                            innerPadding = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = channel.name,
                                color = Color.White,
                                fontSize = if (isPhoneLandscape) 15.sp else 19.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                modifier = Modifier.basicMarquee()
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = channel.group,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = if (isPhoneLandscape) 11.sp else 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (videoResolutionLabel.isNotBlank() || detectedSpecLabel.isNotBlank()) {
                                    Text(
                                        text = listOfNotNull(
                                            videoResolutionLabel.takeIf { it.isNotBlank() },
                                            detectedSpecLabel.takeIf { it.isNotBlank() }
                                        ).joinToString(" • "),
                                        color = EmeraldLive,
                                        fontSize = if (isPhoneLandscape) 10.sp else 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FocusableCircleButton(
                            onClick = { onToggleFavorite(channel) },
                            icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            tint = if (isFavorite) Color(0xFFFF4D6D) else Color.White,
                            contentDescription = "المفضلة",
                            sizeDp = if (isPhoneLandscape) 36.dp else 44.dp
                        )

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                            FocusableCircleButton(
                                onClick = {
                                    runCatching {
                                        val params = PictureInPictureParams.Builder()
                                            .setAspectRatio(Rational(16, 9))
                                            .build()
                                        activity.enterPictureInPictureMode(params)
                                    }
                                },
                                icon = Icons.Default.ExitToApp,
                                contentDescription = "PiP",
                                sizeDp = if (isPhoneLandscape) 36.dp else 44.dp
                            )
                        }

                        FocusableCircleButton(
                            onClick = {
                                showControls = false
                                drawerSelectedBouquet = channel.group
                                showQuickDrawer = true
                            },
                            icon = Icons.Default.List,
                            contentDescription = "الباقات والقنوات",
                            sizeDp = if (isPhoneLandscape) 36.dp else 44.dp
                        )
                    }
                }

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(if (isPhoneLandscape) 14.dp else 22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (channel.contentType != StreamContentType.LIVE_TV) {
                        FocusableCircleButton(
                            onClick = { seekVodByOffset(-15_000L) },
                            icon = Icons.Default.FastRewind,
                            tint = Color.White,
                            contentDescription = "-15s",
                            sizeDp = if (isPhoneLandscape) 38.dp else 48.dp
                        )
                    } else if (siblingChannels.size > 1) {
                        FocusableCircleButton(
                            onClick = { switchRelativeChannel(-1) },
                            icon = Icons.Default.KeyboardArrowRight,
                            tint = Color.White,
                            contentDescription = "السابقة",
                            sizeDp = if (isPhoneLandscape) 38.dp else 48.dp
                        )
                    }

                    var centerPlayFocused by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = {
                            if (useVlcEngine) {
                                if (vlcController.isPlaying()) {
                                    vlcController.pause()
                                    if (channel.contentType != StreamContentType.LIVE_TV) {
                                        onSaveVodProgress(channel, vlcController.getTimeMs(), vodDurationMs)
                                    }
                                } else {
                                    vlcController.play()
                                }
                            } else {
                                if (player.isPlaying) {
                                    player.pause()
                                    if (channel.contentType != StreamContentType.LIVE_TV) {
                                        onSaveVodProgress(channel, player.currentPosition.coerceAtLeast(0L), vodDurationMs)
                                    }
                                } else {
                                    if (channel.contentType == StreamContentType.LIVE_TV && player.playbackState == Player.STATE_IDLE) {
                                        player.prepare()
                                    }
                                    player.play()
                                }
                            }
                        },
                        modifier = Modifier
                            .focusRequester(centerPlayFocusRequester)
                            .size(if (isPhoneLandscape) 56.dp else 68.dp)
                            .scale(if (centerPlayFocused) 1.14f else 1.0f)
                            .onFocusChanged { centerPlayFocused = it.isFocused }
                            .focusable()
                            .background(if (centerPlayFocused) Color.White else BeinPrimaryPurple, CircleShape)
                            .border(3.dp, if (centerPlayFocused) BeinFocusGlow else Color.White, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "تشغيل/إيقاف",
                            tint = if (centerPlayFocused) Color.Black else Color.White,
                            modifier = Modifier.size(if (isPhoneLandscape) 28.dp else 36.dp)
                        )
                    }

                    if (channel.contentType != StreamContentType.LIVE_TV) {
                        FocusableCircleButton(
                            onClick = { seekVodByOffset(15_000L) },
                            icon = Icons.Default.FastForward,
                            tint = Color.White,
                            contentDescription = "+15s",
                            sizeDp = if (isPhoneLandscape) 42.dp else 52.dp
                        )
                    } else if (siblingChannels.size > 1) {
                        FocusableCircleButton(
                            onClick = { switchRelativeChannel(1) },
                            icon = Icons.Default.KeyboardArrowLeft,
                            tint = Color.White,
                            contentDescription = "التالية",
                            sizeDp = if (isPhoneLandscape) 42.dp else 52.dp
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xCC08080A), Color(0xF208080A))
                            )
                        )
                        .padding(
                            horizontal = if (isPhoneLandscape) 12.dp else 18.dp,
                            vertical = if (isPhoneLandscape) 6.dp else 10.dp
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Bottom Control Bar: Slim Interactive Progress Bar for Movies/Series OR Live TV Quick Navigation Bar
                    if (channel.contentType != StreamContentType.LIVE_TV) {
                        val progressFrac = if (vodDurationMs > 0L) {
                            (vodPositionMs.toFloat() / vodDurationMs.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        val remainingMs = (vodDurationMs - vodPositionMs).coerceAtLeast(0L)
                        var seekBarFocused by remember { mutableStateOf(false) }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(vodSeekBarFocusRequester)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xE6101016))
                                .border(
                                    width = if (seekBarFocused) 1.8.dp else 1.dp,
                                    color = if (seekBarFocused) EmeraldLive else BeinHighlightPurple.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .onFocusChanged { seekBarFocused = it.isFocused }
                                .onKeyEvent { ev ->
                                    if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                        val stepMs = computeSmartSeekStepMs(ev.nativeKeyEvent.repeatCount)
                                        when (ev.nativeKeyEvent.keyCode) {
                                            KeyEvent.KEYCODE_DPAD_LEFT,
                                            KeyEvent.KEYCODE_MEDIA_REWIND,
                                            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                                                seekVodByOffset(-stepMs)
                                                true
                                            }
                                            KeyEvent.KEYCODE_DPAD_RIGHT,
                                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                                            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                                                seekVodByOffset(stepMs)
                                                true
                                            }
                                            KeyEvent.KEYCODE_DPAD_CENTER,
                                            KeyEvent.KEYCODE_ENTER -> {
                                                if (useVlcEngine) {
                                                    if (vlcController.isPlaying()) vlcController.pause() else vlcController.play()
                                                } else {
                                                    if (player.isPlaying) player.pause() else player.play()
                                                }
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                                .focusable()
                                .padding(horizontal = 12.dp, vertical = if (isPhoneLandscape) 4.dp else 6.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⏱ ${VodWatchProgress.formatDurationMs(vodPositionMs)} / ${VodWatchProgress.formatDurationMs(vodDurationMs)} (${(progressFrac * 100).toInt()}%)",
                                    color = if (seekBarFocused) EmeraldLive else Color.White,
                                    fontSize = if (isPhoneLandscape) 11.sp else 12.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = if (vodDurationMs > 0L) {
                                        "المتبقي: -${VodWatchProgress.formatDurationMs(remainingMs)} • ◀/▶ للتقديم والتأخير"
                                    } else {
                                        "◀/▶ للتقديم والتأخير السريع"
                                    },
                                    color = Color(0xFFCBD5E1),
                                    fontSize = if (isPhoneLandscape) 10.sp else 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // Slim Interactive Touch + D-Pad Progress Track
                            BoxWithConstraints(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(16.dp)
                                    .pointerInput(vodDurationMs) {
                                        detectTapGestures { offset ->
                                            if (vodDurationMs > 0L && size.width > 0) {
                                                val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                                seekVodTo((frac * vodDurationMs).toLong())
                                            }
                                        }
                                    }
                                    .pointerInput(vodDurationMs) {
                                        detectHorizontalDragGestures { change, _ ->
                                            change.consume()
                                            if (vodDurationMs > 0L && size.width > 0) {
                                                val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                                seekVodTo((frac * vodDurationMs).toLong())
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.CenterStart
                            ) {
                                val thumbSize = if (seekBarFocused) 13.dp else 10.dp
                                val usableWidth = (maxWidth - thumbSize).coerceAtLeast(0.dp)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(if (seekBarFocused) 6.dp else 4.dp)
                                        .clip(RoundedCornerShape(99.dp))
                                        .background(Color(0xFF2C2C38))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(progressFrac)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(99.dp))
                                            .background(
                                                Brush.horizontalGradient(
                                                    colors = listOf(Color(0xFFE50914), EmeraldLive)
                                                )
                                            )
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .offset(x = usableWidth * progressFrac)
                                        .size(thumbSize)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .border(2.dp, if (seekBarFocused) EmeraldLive else Color(0xFFE50914), CircleShape)
                                )
                            }
                        }
                    } else {
                        // Live TV Bottom Info & Quick Channel Switch Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xE6121216))
                                .border(1.2.dp, BeinBorder, RoundedCornerShape(14.dp))
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .background(Color(0xFFE50914), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "● LIVE",
                                        color = Color.White,
                                        fontSize = if (isPhoneLandscape) 11.sp else 12.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                                Text(
                                    text = "${channel.name} • ${channel.group}",
                                    color = Color.White,
                                    fontSize = if (isPhoneLandscape) 13.sp else 15.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (siblingChannels.size > 1) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FocusableActionPill(
                                        text = "⏮ السابقة",
                                        isPrimary = false,
                                        compact = isPhoneLandscape,
                                        onClick = { switchRelativeChannel(-1) }
                                    )
                                    FocusableActionPill(
                                        text = "⏭ التالية",
                                        isPrimary = false,
                                        compact = isPhoneLandscape,
                                        onClick = { switchRelativeChannel(1) }
                                    )
                                }
                            }
                        }

                        // Live TV Only Bottom Control Toolbar (Hidden for Movies & Series to keep VOD view clean!)
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            item {
                                FocusableActionPill(
                                    text = "📂 ${AppStrings.tr(lang, "القنوات والباقات", "Chaînes & Bouquets", "Channels & Bouquets")}",
                                    isPrimary = true,
                                    compact = isPhoneLandscape,
                                    onClick = {
                                        showControls = false
                                        drawerSelectedBouquet = channel.group
                                        showQuickDrawer = true
                                    }
                                )
                            }
                            item {
                                FocusableActionPill(
                                    text = if (useVlcEngine) {
                                        "🛟 ${AppStrings.tr(lang, "المشغل: VLC (الاحتياطي المنقذ)", "Moteur: VLC Secours", "Engine: VLC Rescue")}"
                                    } else {
                                        "⚡ ${AppStrings.tr(lang, "المشغل: ExoPlayer (الأساسي السريع)", "Moteur: ExoPlayer Principal", "Engine: ExoPlayer Primary")}"
                                    },
                                    isPrimary = !useVlcEngine,
                                    compact = isPhoneLandscape,
                                    onClick = {
                                        if (useVlcEngine) {
                                            codecRequiresVlc = false
                                            vlcFallbackToExo = true
                                            activeEngine = InternalPlayerEngine.AUTO_HYBRID
                                            onUpdatePreferences(preferences.copy(playerEngine = InternalPlayerEngine.AUTO_HYBRID))
                                        } else {
                                            vlcFallbackToExo = false
                                            codecRequiresVlc = true
                                        }
                                    }
                                )
                            }
                            item {
                                FocusableActionPill(
                                    text = "⚙️ ${AppStrings.tr(lang, "الجودة والصوت", "Qualité & Audio", "Quality & Audio")}",
                                    isPrimary = false,
                                    compact = isPhoneLandscape,
                                    onClick = { showTracksDialog = true }
                                )
                            }
                            // Smart Auto + Manual Quality/Source pills for Multi-Quality channels
                            if (isMultiQualityChannel && mqVariants.isNotEmpty()) {
                                val locked = MultiQualityEngine.getManualQuality(channel.stableKey)
                                item {
                                    FocusableActionPill(
                                        text = if (locked == null) {
                                            "🤖 تلقائي ذكي (${activeMqVariant?.quality?.labelAr ?: "Auto"}) ✓"
                                        } else {
                                            "🤖 تفعيل التكيّف التلقائي (Auto)"
                                        },
                                        isPrimary = locked == null,
                                        compact = isPhoneLandscape,
                                        onClick = {
                                            MultiQualityEngine.setManualQuality(channel.stableKey, null)
                                            val bestVar = MultiQualityEngine.pickBestVariant(channel)
                                            if (bestVar != null) {
                                                activeMqVariant = bestVar
                                                mqSwitchBanner = "🤖 تم تفعيل التكيّف الذكي التلقائي — الجودة الحالية: ${bestVar.quality.labelAr} (${bestVar.bouquetLabel})"
                                                codecRequiresVlc = false
                                                forceVlcSoftwareVideo = false
                                                candidateIndex = 0
                                                formatAttemptMode = 0
                                                errorMessage = null
                                                mqSwitchGeneration += 1
                                            }
                                        }
                                    )
                                }
                                mqVariants.forEach { vItem ->
                                    val isCurrentVar = activeMqVariant?.sourceId == vItem.sourceId
                                    val healthIcon = when {
                                        MultiQualityEngine.isVariantOutage(vItem) -> "⛔"
                                        MultiQualityEngine.isVariantCongested(vItem) -> "⚠️"
                                        else -> "📺"
                                    }
                                    val hasSameRankSibling = mqVariants.count { it.quality == vItem.quality } > 1
                                    val bqShort = if (hasSameRankSibling && vItem.bouquetLabel.isNotBlank()) {
                                        " (${vItem.bouquetLabel.take(10)})"
                                    } else ""
                                    item(key = "mq_var_${vItem.sourceId}") {
                                        FocusableActionPill(
                                            text = "$healthIcon ${vItem.quality.labelAr}$bqShort" + if (isCurrentVar && locked == vItem.quality) " ✓" else "",
                                            isPrimary = isCurrentVar,
                                            compact = isPhoneLandscape,
                                            onClick = {
                                                MultiQualityEngine.setManualQuality(channel.stableKey, vItem.quality, vItem.sourceId)
                                                MultiQualityEngine.setActiveVariant(channel.stableKey, vItem)
                                                activeMqVariant = vItem
                                                mqSwitchBanner = "📺 تم اختيار جودة ${vItem.quality.labelAr} يدوياً (${vItem.bouquetLabel})"
                                                codecRequiresVlc = false
                                                forceVlcSoftwareVideo = false
                                                candidateIndex = 0
                                                formatAttemptMode = 0
                                                errorMessage = null
                                                mqSwitchGeneration += 1
                                            }
                                        )
                                    }
                                }
                            }
                            item {
                                FocusableActionPill(
                                    text = "📐 ${resizeMode.titleAr}",
                                    isPrimary = false,
                                    compact = isPhoneLandscape,
                                    onClick = {
                                        val modes = VideoResizeMode.values()
                                        resizeMode = modes[(resizeMode.ordinal + 1) % modes.size]
                                    }
                                )
                            }
                            item {
                                FocusableActionPill(
                                    text = if (isFavorite) "❤️ ${AppStrings.tr(lang, "في المفضلة", "Favori", "Favorited")}" else "🤍 ${AppStrings.tr(lang, "إضافة للمفضلة", "Ajouter Favori", "Add Favorite")}",
                                    isPrimary = isFavorite,
                                    compact = isPhoneLandscape,
                                    onClick = { onToggleFavorite(channel) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showQuickDrawer) {
            val activeBouquetIdx = remember(allBouquets, drawerSelectedBouquet) {
                allBouquets.indexOfFirst { it.first == drawerSelectedBouquet }.coerceAtLeast(0)
            }
            val rawActiveChannelIdx = remember(drawerChannels, channel.stableKey) {
                drawerChannels.indexOfFirst { it.stableKey == channel.stableKey }
            }
            val targetChannelIdx = remember(rawActiveChannelIdx) {
                if (rawActiveChannelIdx >= 0) rawActiveChannelIdx else 0
            }

            // Initialize LazyListState directly at the active bouquet and channel so Frame 1 renders in 0ms without scroll jumps!
            val bouquetListState = rememberLazyListState(
                initialFirstVisibleItemIndex = (activeBouquetIdx - 2).coerceAtLeast(0)
            )
            val channelListState = rememberLazyListState(
                initialFirstVisibleItemIndex = (targetChannelIdx - 2).coerceAtLeast(0)
            )

            // Windowed Lazy Paging inside Quick Drawer (prevents any UI lag on huge bouquets)
            var drawerVisibleLimit by remember(drawerSelectedBouquet, drawerChannels.size) {
                mutableStateOf((targetChannelIdx + 120).coerceAtLeast(140).coerceAtMost(drawerChannels.size.coerceAtLeast(1)))
            }
            val windowedDrawerChannels = remember(drawerChannels, drawerVisibleLimit) {
                if (drawerChannels.size <= drawerVisibleLimit) drawerChannels
                else drawerChannels.subList(0, drawerVisibleLimit)
            }
            val favKeySet = remember(favorites) { favorites.map { it.stableKey }.toHashSet() }

            val shouldLoadMoreDrawer by remember {
                derivedStateOf {
                    val lastVisible = channelListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    lastVisible >= windowedDrawerChannels.size - 24 && windowedDrawerChannels.size < drawerChannels.size
                }
            }
            LaunchedEffect(shouldLoadMoreDrawer) {
                if (shouldLoadMoreDrawer) {
                    drawerVisibleLimit = (drawerVisibleLimit + 140).coerceAtMost(drawerChannels.size)
                }
            }

            // On drawer open OR re-open: land focus on the CURRENTLY PLAYING channel in its bouquet
            // so the user can continue browsing neighbors or switch bouquet with D-Pad.
            LaunchedEffect(showQuickDrawer, focusFirstChannelTrigger, channel.stableKey, drawerSelectedBouquet) {
                if (!showQuickDrawer) return@LaunchedEffect
                // Expand window enough to include the playing channel index
                if (targetChannelIdx + 40 > drawerVisibleLimit) {
                    drawerVisibleLimit = (targetChannelIdx + 160).coerceAtMost(drawerChannels.size.coerceAtLeast(1))
                }
                runCatching {
                    bouquetListState.scrollToItem((activeBouquetIdx - 1).coerceAtLeast(0))
                }
                runCatching {
                    channelListState.scrollToItem((targetChannelIdx - 2).coerceAtLeast(0))
                }
                // Multiple retries: LazyColumn needs a frame after scroll before FocusRequester is attached
                for (attempt in 1..8) {
                    delay(if (attempt <= 2) 16L else 40L)
                    val focusedOk = if (windowedDrawerChannels.isNotEmpty() && targetChannelIdx < windowedDrawerChannels.size) {
                        runCatching { activeChannelFocusRequester.requestFocus() }.isSuccess
                    } else {
                        runCatching { activeBouquetFocusRequester.requestFocus() }.isSuccess
                    }
                    if (focusedOk) break
                    // Re-scroll mid-retries in case composition lagged
                    if (attempt == 3 || attempt == 6) {
                        runCatching {
                            channelListState.scrollToItem((targetChannelIdx - 1).coerceAtLeast(0))
                        }
                    }
                }
            }

            // When user presses DPAD_RIGHT from a channel: scroll active bouquet into view if needed and focus it (never gets stuck!)
            LaunchedEffect(focusBouquetTrigger) {
                if (focusBouquetTrigger > 0 && allBouquets.isNotEmpty()) {
                    val bVis = bouquetListState.layoutInfo.visibleItemsInfo.any { it.index == activeBouquetIdx }
                    if (!bVis) {
                        runCatching { bouquetListState.scrollToItem((activeBouquetIdx - 2).coerceAtLeast(0)) }
                    }
                    for (attempt in 1..5) {
                        delay(25L)
                        if (runCatching { activeBouquetFocusRequester.requestFocus() }.isSuccess) break
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth(if (isPhoneLandscape) 0.88f else 0.70f)
                    .widthIn(min = 380.dp, max = 680.dp)
                    .fillMaxHeight(if (isPhoneLandscape) 0.92f else 0.88f)
                    .align(Alignment.CenterStart)
                    .padding(start = if (isPhoneLandscape) 8.dp else 16.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xF50A0A0E))
                    .border(2.dp, BeinHighlightPurple, RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(if (isPhoneLandscape) 8.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Top Quick Controls Strip inside Drawer (Quality/Audio, Aspect Ratio, Favorite, Engine Status, Close)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF14141C))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📺 ${channel.name}",
                        color = EmeraldLive,
                        fontSize = if (isPhoneLandscape) 11.sp else 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FocusableActionPill(
                            text = if (useVlcEngine) "🛟 VLC" else "⚡ Exo",
                            isPrimary = !useVlcEngine,
                            compact = true,
                            onClick = {
                                if (useVlcEngine) {
                                    codecRequiresVlc = false
                                    vlcFallbackToExo = true
                                } else {
                                    vlcFallbackToExo = false
                                    codecRequiresVlc = true
                                }
                            }
                        )
                        FocusableActionPill(
                            text = "⚙️ الجودة",
                            isPrimary = false,
                            compact = true,
                            onClick = {
                                showQuickDrawer = false
                                showTracksDialog = true
                            }
                        )
                        FocusableActionPill(
                            text = "📐 ${resizeMode.titleAr}",
                            isPrimary = false,
                            compact = true,
                            onClick = {
                                val modes = VideoResizeMode.values()
                                resizeMode = modes[(resizeMode.ordinal + 1) % modes.size]
                            }
                        )
                        FocusableActionPill(
                            text = if (isFavorite) "❤️" else "🤍",
                            isPrimary = isFavorite,
                            compact = true,
                            onClick = { onToggleFavorite(channel) }
                        )
                        FocusableActionPill(
                            text = "✕ إغلاق",
                            isPrimary = false,
                            compact = true,
                            onClick = { showQuickDrawer = false }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // RIGHT COLUMN (in RTL): Bouquets List
                    Column(
                        modifier = Modifier
                            .weight(0.41f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF101015))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "📂 ${AppStrings.tr(lang, "الباقات", "Bouquets", "Bouquets")} (${allBouquets.size})",
                            color = Color.White,
                            fontSize = if (isPhoneLandscape) 11.sp else 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                        )
                        LazyColumn(
                            state = bouquetListState,
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(allBouquets, key = { idx, pair -> "${idx}_${pair.first}" }) { idx, (bName, bList) ->
                                val selected = drawerSelectedBouquet == bName
                                var bFocused by remember { mutableStateOf(false) }
                                val bModifier = if (idx == activeBouquetIdx) {
                                    Modifier.focusRequester(activeBouquetFocusRequester)
                                } else {
                                    Modifier
                                }
                                Row(
                                    modifier = bModifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                bFocused -> Color.White
                                                selected -> BeinPrimaryPurple
                                                else -> Color(0xFF16161D)
                                            }
                                        )
                                        .border(
                                            width = if (bFocused) 2.4.dp else if (selected) 1.5.dp else 0.dp,
                                            color = if (bFocused) BeinFocusGlow else if (selected) EmeraldLive else Color.Transparent,
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .onFocusChanged { bFocused = it.isFocused }
                                        .onKeyEvent { ev ->
                                            if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                                ev.nativeKeyEvent.repeatCount == 0 &&
                                                (android.os.SystemClock.elapsedRealtime() - drawerOpenTimestampMs) > 220L
                                            ) {
                                                when (ev.nativeKeyEvent.keyCode) {
                                                    KeyEvent.KEYCODE_BACK,
                                                    KeyEvent.KEYCODE_ESCAPE -> {
                                                        showQuickDrawer = false
                                                        true
                                                    }
                                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                                    KeyEvent.KEYCODE_ENTER,
                                                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                        drawerSelectedBouquet = bName
                                                        focusFirstChannelTrigger += 1
                                                        true
                                                    }
                                                    else -> false
                                                }
                                            } else false
                                        }
                                        .focusable()
                                        .clickable {
                                            drawerSelectedBouquet = bName
                                            focusFirstChannelTrigger += 1
                                        }
                                        .padding(horizontal = 10.dp, vertical = if (isPhoneLandscape) 7.dp else 9.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = bName,
                                        color = if (bFocused) Color.Black else Color.White,
                                        fontSize = if (isPhoneLandscape) 11.sp else 13.sp,
                                        fontWeight = if (bFocused || selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "${bList.size}",
                                        color = if (bFocused) BeinPrimaryPurple else if (selected) EmeraldLive else Color(0xFFE2E8F0),
                                        fontSize = if (isPhoneLandscape) 10.sp else 11.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }

                    // LEFT COLUMN (in RTL): Channels List in Selected Bouquet
                    Column(
                        modifier = Modifier
                            .weight(0.59f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF101015))
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "$drawerSelectedBouquet (${drawerChannels.size})",
                                color = Color.White,
                                fontSize = if (isPhoneLandscape) 11.sp else 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "OK للتشغيل • ◀ للباقات",
                                color = Color(0xFF94A3B8),
                                fontSize = if (isPhoneLandscape) 9.sp else 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (windowedDrawerChannels.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = AppStrings.tr(
                                        lang,
                                        "لا توجد قنوات في هذه الباقة حالياً",
                                        "Aucune chaîne dans ce bouquet",
                                        "No channels in this bouquet"
                                    ),
                                    color = Color.LightGray,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                state = channelListState,
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(windowedDrawerChannels, key = { idx, it -> "${idx}_${it.stableKey}" }) { idx, item ->
                                    val active = item.stableKey == channel.stableKey
                                    val isFavItem = favKeySet.contains(item.stableKey)
                                    var rowFocused by remember { mutableStateOf(false) }
                                    val chModifier = if (idx == targetChannelIdx) {
                                        Modifier.focusRequester(activeChannelFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                    Row(
                                        modifier = chModifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(
                                                when {
                                                    rowFocused -> Color.White
                                                    active -> Color(0xFF0E2920)
                                                    else -> Color(0xFF16161D)
                                                }
                                            )
                                            .border(
                                                width = if (rowFocused) 2.4.dp else if (active) 1.6.dp else 0.dp,
                                                color = if (rowFocused) BeinFocusGlow else if (active) EmeraldLive else Color.Transparent,
                                                shape = RoundedCornerShape(10.dp)
                                            )
                                            .onFocusChanged { rowFocused = it.isFocused }
                                            .onKeyEvent { ev ->
                                                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                                    ev.nativeKeyEvent.repeatCount == 0
                                                ) {
                                                    when (ev.nativeKeyEvent.keyCode) {
                                                        KeyEvent.KEYCODE_BACK,
                                                        KeyEvent.KEYCODE_ESCAPE -> {
                                                            // زر الرجوع: إغلاق القائمة والبقاء على نفس القناة قيد المشاهدة
                                                            showQuickDrawer = false
                                                            true
                                                        }
                                                        KeyEvent.KEYCODE_DPAD_CENTER,
                                                        KeyEvent.KEYCODE_ENTER,
                                                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                                            if ((android.os.SystemClock.elapsedRealtime() - drawerOpenTimestampMs) > 250L) {
                                                                showControls = false
                                                                showQuickDrawer = false
                                                                if (item.stableKey != channel.stableKey) {
                                                                    onSelectChannel(item)
                                                                }
                                                            }
                                                            true
                                                        }
                                                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                            focusBouquetTrigger += 1
                                                            true
                                                        }
                                                        else -> false
                                                    }
                                                } else false
                                            }
                                            .focusable()
                                            .clickable {
                                                showControls = false
                                                showQuickDrawer = false
                                                if (item.stableKey != channel.stableKey) {
                                                    onSelectChannel(item)
                                                }
                                            }
                                            .padding(horizontal = 8.dp, vertical = if (isPhoneLandscape) 6.dp else 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .widthIn(min = 24.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(
                                                    when {
                                                        rowFocused -> Color(0xFF14141D)
                                                        active -> EmeraldLive
                                                        else -> Color(0xFF101016)
                                                    }
                                                )
                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${idx + 1}",
                                                color = if (active && !rowFocused) Color.Black else Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                        Spacer(Modifier.width(7.dp))
                                        ChannelAvatar(
                                            name = item.name,
                                            logoUrl = item.logo,
                                            modifier = Modifier.size(if (isPhoneLandscape) 30.dp else 36.dp),
                                            cornerRadius = 7.dp,
                                            innerPadding = 2.dp
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = item.name,
                                            color = if (rowFocused) Color.Black else Color.White,
                                            fontSize = if (isPhoneLandscape) 11.sp else 13.sp,
                                            fontWeight = if (active || rowFocused) FontWeight.ExtraBold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (active) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "▶",
                                                color = if (rowFocused) Color(0xFF047857) else EmeraldLive,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                        if (isFavItem) {
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                text = "★",
                                                color = if (rowFocused) BeinPrimaryPurple else Color(0xFFFF4D6D),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showTracksDialog) {
            InteractiveTrackAndSpeedModal(
                player = player,
                tracks = currentTracksSnapshot,
                mqVariants = mqVariants,
                activeMqVariant = activeMqVariant,
                preferSubtitlesTab = channel.contentType == StreamContentType.MOVIE || channel.contentType == StreamContentType.SERIES,
                manualLockedQuality = if (isMultiQualityChannel) MultiQualityEngine.getManualQuality(channel.stableKey) else null,
                onSelectAutoMq = {
                    MultiQualityEngine.setManualQuality(channel.stableKey, null)
                    val bestVar = MultiQualityEngine.pickBestVariant(channel)
                    if (bestVar != null) {
                        isSeamlessMqTransition = true
                        activeMqVariant = bestVar
                        mqSwitchBanner = "🤖 تم تفعيل التكيّف التلقائي الذكي (Auto ABR: ${bestVar.quality.labelAr})"
                        codecRequiresVlc = false
                        forceVlcSoftwareVideo = false
                        candidateIndex = 0
                        formatAttemptMode = 0
                        errorMessage = null
                        mqSwitchGeneration += 1
                    }
                },
                onSelectMqVariant = { chosenVar ->
                    MultiQualityEngine.setManualQuality(channel.stableKey, chosenVar.quality, chosenVar.sourceId)
                    MultiQualityEngine.setActiveVariant(channel.stableKey, chosenVar)
                    isSeamlessMqTransition = true
                    activeMqVariant = chosenVar
                    mqSwitchBanner = "📺 تم تثبيت الجودة يدوياً: ${chosenVar.quality.labelAr} (${chosenVar.bouquetLabel})"
                    codecRequiresVlc = false
                    forceVlcSoftwareVideo = false
                    candidateIndex = 0
                    formatAttemptMode = 0
                    errorMessage = null
                    mqSwitchGeneration += 1
                },
                currentSpeed = playbackSpeed,
                onSelectSpeed = { playbackSpeed = it },
                compact = isPhoneLandscape,
                onDismiss = { showTracksDialog = false }
            )
        }

        val currentDiag = playerDiagnosticAlert
        if (currentDiag != null) {
            Dialog(onDismissRequest = { playerDiagnosticAlert = null }) {
                Column(
                    modifier = Modifier
                        .widthIn(min = 310.dp, max = 540.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF101015))
                        .border(
                            2.dp,
                            if (currentDiag.type == DiagnosticAlertType.ACCOUNT_EXPIRED) CrimsonAlert else BeinHighlightPurple,
                            RoundedCornerShape(20.dp)
                        )
                        .padding(if (isPhoneLandscape) 14.dp else 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = currentDiag.title,
                        color = Color.White,
                        fontSize = if (isPhoneLandscape) 14.sp else 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Text(
                        text = currentDiag.message,
                        color = Color(0xFFE2E8F0),
                        fontSize = if (isPhoneLandscape) 11.sp else 13.sp,
                        lineHeight = 20.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BeinPrimaryPurple)
                                .border(1.5.dp, Color.White, RoundedCornerShape(12.dp))
                                .clickable {
                                    playerDiagnosticAlert = null
                                    retryCount = 0
                                    candidateIndex = 0
                                    formatAttemptMode = 0
                                    errorMessage = null
                                    player.prepare()
                                    player.play()
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "🔄 إعادة المحاولة الآن",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF1C1C24))
                                .border(1.dp, BeinHighlightPurple, RoundedCornerShape(12.dp))
                                .clickable {
                                    playerDiagnosticAlert = null
                                    onBack()
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "العودة للقائمة",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusableCircleButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    sizeDp: androidx.compose.ui.unit.Dp = 44.dp
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = { SafeClickGuard.runThrottled("PLAYER_CIRCLE_BTN", 200L) { onClick() } },
        modifier = Modifier
            .size(sizeDp)
            .scale(if (focused) 1.14f else 1.0f)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused) Color.White else Color(0xE6181820), CircleShape)
            .border(
                width = if (focused) 2.8.dp else 1.5.dp,
                color = if (focused) BeinFocusGlow else BeinHighlightPurple.copy(alpha = 0.7f),
                shape = CircleShape
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (focused) Color.Black else tint,
            modifier = Modifier.size(sizeDp * 0.52f)
        )
    }
}

@Composable
private fun FocusableActionPill(
    text: String,
    isPrimary: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .scale(if (focused) 1.08f else 1.0f)
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    ev.nativeKeyEvent.repeatCount == 0 &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    SafeClickGuard.runThrottled("PLAYER_UI_CLICK", 200L) { onClick() }
                    true
                } else false
            }
            .focusable()
            .background(
                when {
                    focused -> Color.White
                    isPrimary -> BeinPrimaryPurple
                    else -> Color(0xE6181820)
                }
            )
            .border(
                width = if (focused) 2.8.dp else 1.2.dp,
                color = if (focused) BeinFocusGlow else BeinHighlightPurple.copy(alpha = 0.75f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(
                horizontal = if (compact) 13.dp else 16.dp,
                vertical = if (compact) 8.dp else 10.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (focused) Color.Black else Color.White,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun InteractiveTrackAndSpeedModal(
    player: ExoPlayer,
    tracks: Tracks,
    mqVariants: List<MultiQualityEngine.QualityVariant> = emptyList(),
    activeMqVariant: MultiQualityEngine.QualityVariant? = null,
    manualLockedQuality: StreamQualityRank? = null,
    onSelectAutoMq: () -> Unit = {},
    onSelectMqVariant: (MultiQualityEngine.QualityVariant) -> Unit = {},
    currentSpeed: PlaybackSpeedOption,
    onSelectSpeed: (PlaybackSpeedOption) -> Unit,
    compact: Boolean,
    preferSubtitlesTab: Boolean = false,
    onDismiss: () -> Unit
) {
    var selectedSection by remember { mutableStateOf(if (preferSubtitlesTab) 2 else 0) }
    val firstItemFocus = remember { FocusRequester() }

    val videoOptions = remember(tracks) {
        val list = mutableListOf<TrackOption>()
        tracks.groups.forEachIndexed { gIndex, group ->
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (tIndex in 0 until group.length) {
                    val fmt = group.getTrackFormat(tIndex)
                    if (fmt.height > 0) {
                        val badge = when {
                            fmt.height >= 2160 -> "4K UHD"
                            fmt.height >= 1080 -> "Full HD 1080p"
                            fmt.height >= 720 -> "HD 720p"
                            else -> "SD ${fmt.height}p"
                        }
                        val br = if (fmt.bitrate > 0) "${fmt.bitrate / 1000} kbps" else "Adaptive"
                        val fps = if (fmt.frameRate > 0) " • ${fmt.frameRate.toInt()}fps" else ""
                        list.add(
                            TrackOption(
                                title = "$badge (${fmt.width}×${fmt.height})",
                                subtitle = "معدل البث: $br$fps",
                                groupIndex = gIndex,
                                trackIndex = tIndex,
                                isSelected = group.isTrackSelected(tIndex)
                            )
                        )
                    }
                }
            }
        }
        list.sortedByDescending { it.title }
    }

    val audioOptions = remember(tracks) {
        val list = mutableListOf<TrackOption>()
        var commentatorNum = 1
        tracks.groups.forEachIndexed { gIndex, group ->
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (tIndex in 0 until group.length) {
                    val fmt = group.getTrackFormat(tIndex)
                    val rawLabel = fmt.label?.takeIf { it.isNotBlank() }
                    val rawLang = fmt.language?.takeIf { it.isNotBlank() && it != "und" }
                    val title = when {
                        rawLabel != null && rawLang != null -> "معلق / صوت $commentatorNum: $rawLabel ($rawLang)"
                        rawLabel != null -> "معلق / صوت $commentatorNum: $rawLabel"
                        rawLang != null -> "معلق / مسار صوتي $commentatorNum ($rawLang)"
                        else -> "المعلق الصوتي / المسار رقم $commentatorNum"
                    }
                    val channelsDesc = if (fmt.channelCount >= 6) "صوت محيطي 5.1" else "ستيريو واضح"
                    val br = if (fmt.bitrate > 0) " • ${fmt.bitrate / 1000} kbps" else ""
                    list.add(
                        TrackOption(
                            title = title,
                            subtitle = "$channelsDesc$br",
                            groupIndex = gIndex,
                            trackIndex = tIndex,
                            isSelected = group.isTrackSelected(tIndex)
                        )
                    )
                    commentatorNum++
                }
            }
        }
        list
    }

    // Multi-subtitles for Movies & Series (and any stream that embeds text tracks)
    val subtitleOptions = remember(tracks, player.trackSelectionParameters) {
        val list = mutableListOf<TrackOption>()
        var textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        var anySelected = false
        var subNum = 1
        tracks.groups.forEachIndexed { gIndex, group ->
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (tIndex in 0 until group.length) {
                    // Skip non-displayable image/cea608 if unsupported — still list if present
                    val fmt = group.getTrackFormat(tIndex)
                    val mime = (fmt.sampleMimeType ?: "").lowercase(Locale.ROOT)
                    val isForced = (fmt.selectionFlags and androidx.media3.common.C.SELECTION_FLAG_FORCED) != 0
                    val isDefault = (fmt.selectionFlags and androidx.media3.common.C.SELECTION_FLAG_DEFAULT) != 0
                    val langLabel = friendlyTrackLanguageLabel(fmt.language, fmt.label)
                    val kind = when {
                        mime.contains("ass") || mime.contains("ssa") -> "ASS/SSA"
                        mime.contains("vtt") -> "WebVTT"
                        mime.contains("subrip") || mime.contains("srt") -> "SRT"
                        mime.contains("ttml") || mime.contains("dfxp") -> "TTML"
                        mime.contains("cea") -> "CEA-608/708"
                        else -> "ترجمة مضمّنة"
                    }
                    val flags = buildList {
                        if (isDefault) add("افتراضية")
                        if (isForced) add("إجبارية")
                    }.joinToString(" • ")
                    val selected = group.isTrackSelected(tIndex) && !textDisabled
                    if (selected) anySelected = true
                    list.add(
                        TrackOption(
                            title = "📝 ترجمة $subNum: $langLabel",
                            subtitle = listOf(kind, flags).filter { it.isNotBlank() }.joinToString(" • ")
                                .ifBlank { "مسار ترجمة من السيرفر" },
                            groupIndex = gIndex,
                            trackIndex = tIndex,
                            isSelected = selected
                        )
                    )
                    subNum++
                }
            }
        }
        // Off option always first when there is at least one track
        if (list.isNotEmpty()) {
            val offSelected = textDisabled || !anySelected
            list.add(
                0,
                TrackOption(
                    title = "🚫 إيقاف الترجمة",
                    subtitle = "إخفاء كل مسارات الترجمة",
                    groupIndex = -1,
                    trackIndex = -1,
                    isSelected = offSelected,
                    isDisableOption = true
                )
            )
            // If Off is selected, clear other selections visually
            if (offSelected) {
                for (i in 1 until list.size) {
                    list[i] = list[i].copy(isSelected = false)
                }
            }
        }
        list
    }

    val hasVideoOverride = remember(player.trackSelectionParameters) {
        player.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
    }

    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { firstItemFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 320.dp, max = if (compact) 540.dp else 640.dp)
                    .fillMaxWidth(0.90f)
                    .fillMaxHeight(if (compact) 0.88f else 0.80f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF101015))
                    .border(2.dp, BeinHighlightPurple, RoundedCornerShape(18.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(if (compact) 12.dp else 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "الجودة • الصوت • الترجمة • السرعة ⚙️",
                            color = Color.White,
                            fontSize = if (compact) 13.sp else 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "تنقل بأسهم الريموت (▲▼◄►) واضغط (OK) للاختيار الفوري",
                            color = Color.LightGray,
                            fontSize = if (compact) 9.sp else 11.sp
                        )
                    }
                    FocusableCircleButton(
                        onClick = onDismiss,
                        icon = Icons.Default.Close,
                        contentDescription = "إغلاق",
                        sizeDp = if (compact) 32.dp else 38.dp
                    )
                }

                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    InteractiveModalTabButton(
                        title = "📺 جودة",
                        selected = selectedSection == 0,
                        compact = compact,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(firstItemFocus),
                        onClick = { selectedSection = 0 }
                    )
                    InteractiveModalTabButton(
                        title = "🎙 صوت (${audioOptions.size.coerceAtLeast(1)})",
                        selected = selectedSection == 1,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedSection = 1 }
                    )
                    InteractiveModalTabButton(
                        title = "📝 ترجمة (${(subtitleOptions.size - 1).coerceAtLeast(0)})",
                        selected = selectedSection == 2,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedSection = 2 }
                    )
                    InteractiveModalTabButton(
                        title = "⚡ سرعة",
                        selected = selectedSection == 3,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = { selectedSection = 3 }
                    )
                }

                Spacer(Modifier.height(if (compact) 6.dp else 10.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    when (selectedSection) {
                        0 -> {
                            if (mqVariants.size >= 2) {
                                item {
                                    val currentAutoLabel = activeMqVariant?.quality?.resolutionTag ?: "Auto"
                                    InteractiveTrackCard(
                                        title = "🤖 تلقائي ذكي مثل يوتيوب (Auto — يعمل الآن: $currentAutoLabel)",
                                        subtitle = "يتكيّف تلقائياً باللحظة حسب سرعة الإنترنت وحالة تدفق الفيديو من المصدر",
                                        isSelected = manualLockedQuality == null && !hasVideoOverride,
                                        compact = compact,
                                        onClick = {
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                                .build()
                                            onSelectAutoMq()
                                            onDismiss()
                                        }
                                    )
                                }

                                itemsIndexed(mqVariants) { _, vOpt ->
                                    val isSelectedVar = manualLockedQuality != null && activeMqVariant?.sourceId == vOpt.sourceId
                                    val healthScore = MultiQualityEngine.getVariantHealthScore(vOpt)
                                    val statusText = when {
                                        MultiQualityEngine.isVariantOutage(vOpt) -> "⛔ متوقف حالياً من المصدر"
                                        MultiQualityEngine.isVariantCongested(vOpt) -> "⚠️ ضغط/ثقل في المصدر ($healthScore%)"
                                        else -> "✅ مستقر ($healthScore%) • يحتاج ≥ ${vOpt.quality.minSpeedKbps / 1000.0} Mbps"
                                    }
                                    InteractiveTrackCard(
                                        title = "📺 ${vOpt.quality.resolutionTag} — ${vOpt.channel.name}",
                                        subtitle = "الباقة: ${vOpt.bouquetLabel} • $statusText",
                                        isSelected = isSelectedVar,
                                        compact = compact,
                                        onClick = {
                                            onSelectMqVariant(vOpt)
                                            onDismiss()
                                        }
                                    )
                                }
                            } else {
                                item {
                                    InteractiveTrackCard(
                                        title = "تلقائي متكيّف (Auto Adaptive HD) — موصى به",
                                        subtitle = "يختار أعلى جودة مستقرة حسب سرعة الاتصال وحالة البث",
                                        isSelected = !hasVideoOverride,
                                        compact = compact,
                                        onClick = {
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                                .build()
                                            onDismiss()
                                        }
                                    )
                                }
                            }

                            itemsIndexed(videoOptions) { _, opt ->
                                InteractiveTrackCard(
                                    title = opt.title,
                                    subtitle = opt.subtitle,
                                    isSelected = hasVideoOverride && opt.isSelected,
                                    compact = compact,
                                    onClick = {
                                        val group = tracks.groups[opt.groupIndex].mediaTrackGroup
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setOverrideForType(
                                                TrackSelectionOverride(group, opt.trackIndex)
                                            )
                                            .build()
                                        onDismiss()
                                    }
                                )
                            }
                        }
                        1 -> {
                            if (audioOptions.isEmpty()) {
                                item {
                                    InteractiveTrackCard(
                                        title = "المسار الصوتي الافتراضي للمصدر",
                                        subtitle = "هذه القناة تبث بمسار تعليق صوتي موحّد",
                                        isSelected = true,
                                        compact = compact,
                                        onClick = onDismiss
                                    )
                                }
                            } else {
                                itemsIndexed(audioOptions) { _, opt ->
                                    InteractiveTrackCard(
                                        title = opt.title,
                                        subtitle = opt.subtitle,
                                        isSelected = opt.isSelected,
                                        compact = compact,
                                        onClick = {
                                            val group = tracks.groups[opt.groupIndex].mediaTrackGroup
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .setOverrideForType(
                                                    TrackSelectionOverride(group, opt.trackIndex)
                                                )
                                                .build()
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
                        2 -> {
                            if (subtitleOptions.isEmpty()) {
                                item {
                                    InteractiveTrackCard(
                                        title = "لا توجد ترجمات في هذا البث",
                                        subtitle = "يظهر تبويب الترجمة عند توفر مسارات SRT/VTT/ASS من السيرفر داخل الفيلم أو المسلسل",
                                        isSelected = true,
                                        compact = compact,
                                        onClick = onDismiss
                                    )
                                }
                            } else {
                                itemsIndexed(subtitleOptions) { _, opt ->
                                    InteractiveTrackCard(
                                        title = opt.title,
                                        subtitle = opt.subtitle,
                                        isSelected = opt.isSelected,
                                        compact = compact,
                                        onClick = {
                                            if (opt.isDisableOption || opt.groupIndex < 0) {
                                                // Disable all text tracks
                                                player.trackSelectionParameters = player.trackSelectionParameters
                                                    .buildUpon()
                                                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                                    .build()
                                            } else {
                                                val group = tracks.groups[opt.groupIndex].mediaTrackGroup
                                                player.trackSelectionParameters = player.trackSelectionParameters
                                                    .buildUpon()
                                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                    .setOverrideForType(
                                                        TrackSelectionOverride(group, opt.trackIndex)
                                                    )
                                                    .build()
                                            }
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        }
                        else -> {
                            itemsIndexed(PlaybackSpeedOption.values().toList()) { _, spd ->
                                InteractiveTrackCard(
                                    title = "سرعة التشغيل: ${spd.labelAr}",
                                    subtitle = if (spd == PlaybackSpeedOption.NORMAL) {
                                        "السرعة المثالية لاستقرار القنوات المباشرة"
                                    } else {
                                        "تغيير سرعة عرض الفيديو والصوت"
                                    },
                                    isSelected = currentSpeed == spd,
                                    compact = compact,
                                    onClick = {
                                        onSelectSpeed(spd)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveModalTabButton(
    title: String,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .height(if (compact) 34.dp else 42.dp)
            .scale(if (focused) 1.05f else 1.0f)
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    ev.nativeKeyEvent.repeatCount == 0 &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    SafeClickGuard.runThrottled("PLAYER_UI_CLICK", 200L) { onClick() }
                    true
                } else false
            }
            .focusable()
            .background(
                when {
                    focused -> Color.White
                    selected -> BeinPrimaryPurple
                    else -> Color(0xFF16161D)
                }
            )
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) BeinFocusGlow else if (selected) Color.White else Color(0xFF2C2C38),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (focused) Color.Black else Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (compact) 9.sp else 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun InteractiveTrackCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (focused) 1.02f else 1.0f)
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    ev.nativeKeyEvent.repeatCount == 0 &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    SafeClickGuard.runThrottled("PLAYER_UI_CLICK", 200L) { onClick() }
                    true
                } else false
            }
            .focusable()
            .background(
                when {
                    focused -> Color.White
                    isSelected -> BeinPrimaryPurple.copy(alpha = 0.55f)
                    else -> Color(0xFF16161D)
                }
            )
            .border(
                width = if (focused) 2.8.dp else if (isSelected) 1.5.dp else 1.dp,
                color = when {
                    focused -> BeinFocusGlow
                    isSelected -> Color.White
                    else -> Color(0xFF2C2C38)
                },
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(
                horizontal = if (compact) 12.dp else 16.dp,
                vertical = if (compact) 8.dp else 11.dp
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (focused) Color.Black else Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = if (compact) 11.sp else 13.sp
            )
            Text(
                text = subtitle,
                color = if (focused) Color.DarkGray else Color.LightGray,
                fontSize = if (compact) 9.sp else 11.sp
            )
        }

        Box(
            modifier = Modifier
                .size(if (compact) 20.dp else 24.dp)
                .clip(CircleShape)
                .background(if (isSelected) EmeraldLive else Color(0xFF24242E))
                .border(1.dp, if (focused) Color.Black else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "محدد",
                    tint = Color.Black,
                    modifier = Modifier.size(if (compact) 13.dp else 15.dp)
                )
            }
        }
    }
}

/**
 * Embedded Live Mini Preview Video Player (Green Frame in 3-Column Live TV Browser):
 * - Runs ExoPlayer with instant 0ms failover & 900ms frozen-frame handover to EmbeddedVlcController.
 * - Automatically releases its connection the moment the user expands to FullScreen (PlayerScreen)
 *   so max_connections=1 IPTV accounts never experience connection conflicts!
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveMiniPreviewPlayer(
    channel: TvChannel,
    xtreamPlaylists: List<XtreamAccountConfig> = emptyList(),
    preferences: PlayerPreferences = PlayerPreferences(),
    networkSpeedLabel: String = "",
    compact: Boolean = false,
    onExpandFullScreen: () -> Unit
) {
    val context = LocalContext.current
    val vlcAvailable = remember { VlcEngineBridge.isLibVlcRuntimeAvailable() }

    // Always try ExoPlayer first — local DB VLC preference only speeds failover, never skips Exo.
    val knownVlcFromLocalDb = remember(channel.stableKey, channel.url) {
        FastFormatPreferenceCache.doesChannelPreferVlc(channel.stableKey, channel.url)
    }
    val initialNeedsVlc = remember(channel.stableKey, channel.url) {
        StreamFormatDetectorAndRenewer.isExclusivelyVlcProtocolOrCodec(
            url = channel.url,
            videoCodecHint = channel.videoCodecHint,
            audioCodecHint = channel.audioCodecHint,
            containerExtension = channel.containerExtension,
            name = channel.name
        )
    }
    var codecRequiresVlc by remember(channel.stableKey) {
        mutableStateOf(initialNeedsVlc)
    }
    var forceVlcSoftwareVideo by remember(channel.stableKey) { mutableStateOf(initialNeedsVlc) }
    val miniMqVariants = remember(channel.stableKey) {
        if (channel.contentType == StreamContentType.LIVE_TV) {
            MultiQualityEngine.variantsForChannel(channel)
        } else emptyList()
    }
    val isMultiQualityMini = miniMqVariants.size >= 2 || channel.playlistId == MultiQualityEngine.PLAYLIST_ID
    var activeMiniVariant by remember(channel.stableKey) {
        mutableStateOf(
            if (isMultiQualityMini) {
                MultiQualityEngine.getActiveVariant(channel.stableKey)
                    ?: MultiQualityEngine.pickBestVariant(channel)
                    ?: miniMqVariants.firstOrNull()
            } else null
        )
    }
    var miniMqGeneration by remember(channel.stableKey) { mutableStateOf(0) }
    val activeMiniChannel = remember(channel, activeMiniVariant) {
        activeMiniVariant?.channel ?: channel
    }

    // Mini: no green spinner on open — show video surface immediately; spinner only if truly stuck
    var isBuffering by remember(channel.stableKey) { mutableStateOf(false) }
    var showMiniSpinner by remember(channel.stableKey) { mutableStateOf(false) }
    var candidateIndex by remember(channel.stableKey) { mutableStateOf(0) }
    var retryCount by remember(channel.stableKey) { mutableStateOf(0) }

    val matchingAccount = remember(activeMiniChannel.playlistId, xtreamPlaylists) {
        xtreamPlaylists.find { it.id == activeMiniChannel.playlistId }
    }
    val formatCandidateUrls = remember(channel.stableKey, activeMiniChannel, matchingAccount, miniMqGeneration) {
        activeMiniChannel.buildFormatCandidateUrls(matchingAccount).ifEmpty {
            listOf(activeMiniChannel.url)
        }
    }
    val effectiveUrl = remember(formatCandidateUrls, candidateIndex) {
        formatCandidateUrls.getOrElse(candidateIndex.coerceIn(0, (formatCandidateUrls.size - 1).coerceAtLeast(0))) {
            activeMiniChannel.url
        }
    }

    val httpFactory = remember {
        OkHttpDataSource.Factory(ChannelRepository.streamHttpClient)
            .setUserAgent(STREAM_USER_AGENTS.first())
    }
    val extractorsFactory = remember {
        DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorMode(TsExtractor.MODE_MULTI_PMT)
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS or
                    DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
            )
            .setTsExtractorTimestampSearchBytes(188 * 3600)
    }
    val failFastPolicy = remember {
        object : DefaultLoadErrorHandlingPolicy(0) {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 0
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
        }
    }
    val defaultMediaSourceFactory = remember {
        DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(failFastPolicy)
    }
    val fastHlsMediaSourceFactory = remember {
        HlsMediaSource.Factory(httpFactory)
            .setAllowChunklessPreparation(true)
            .setLoadErrorHandlingPolicy(failFastPolicy)
    }

    val player = remember {
        val bp = preferences.bufferProfile
        // Mini preview: prioritize first frame speed over deep buffer (zapping feels instant)
        val minBufMs = bp.minBufferMs.coerceIn(500, 3_000)
        val maxBufMs = bp.maxBufferMs.coerceIn(6_000, 12_000).coerceAtLeast(minBufMs + 2_000)
        val startPlaybackBufMs = bp.playbackBufferMs.coerceIn(50, 180)
        val afterRebufferBufMs = bp.rebufferMs.coerceIn(350, 800)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufMs, maxBufMs, startPlaybackBufMs, afterRebufferBufMs)
            .setTargetBufferBytes(12 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(1_500, true)
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .build()

        val hwFirstCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val allDecoders = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
            if (allDecoders.size <= 1) {
                allDecoders
            } else {
                val isAudioMime = mimeType.startsWith("audio/", ignoreCase = true)
                val (swList, hwList) = allDecoders.partition { info ->
                    val lowName = info.name.lowercase(Locale.ROOT)
                    info.softwareOnly ||
                        lowName.startsWith("omx.google.") ||
                        lowName.startsWith("c2.android.") ||
                        lowName.contains(".sw.") ||
                        lowName.contains("ffmpeg")
                }
                if (isAudioMime && swList.isNotEmpty()) {
                    swList + hwList
                } else if (preferences.preferSoftwareDecoder) {
                    swList + hwList
                } else {
                    hwList + swList
                }
            }
        }

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setMediaCodecSelector(hwFirstCodecSelector)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            setEnableDecoderFallback(true)
            setEnableAudioFloatOutput(false)
            setEnableAudioTrackPlaybackParams(true)
        }
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedSampleRateAdaptiveness(true)
                    .setAllowAudioMixedChannelCountAdaptiveness(true)
                    .setMinVideoSize(320, 180)
                    .setMaxVideoSize(7680, 4320)
                    .setMinVideoBitrate(50_000)
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                    .setMinVideoFrameRate(1)
                    .setExceedRendererCapabilitiesIfNecessary(false)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true)
            )
        }
        ExoPlayer.Builder(context, renderersFactory)
            .setBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(context))
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(defaultMediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
    val miniBandwidthMeter = remember { DefaultBandwidthMeter.getSingletonInstance(context) }

    val vlcController = remember {
        EmbeddedVlcController(context).apply {
            onBufferingChanged = { buffering -> isBuffering = buffering }
            onPlayingChanged = { playing -> if (playing) isBuffering = false }
            onVoutStarted = { isBuffering = false }
            onErrorOccurred = {
                if (formatCandidateUrls.size > 1 && retryCount < 4) {
                    retryCount++
                    candidateIndex = (candidateIndex + 1) % formatCandidateUrls.size
                }
            }
        }
    }

    LaunchedEffect(vlcController, vlcAvailable) {
        if (vlcAvailable) {
            withContext(Dispatchers.IO) {
                VlcEngineBridge.getOrCreateSharedLibVlc(context)
            }
        }
    }
    LaunchedEffect(vlcController, codecRequiresVlc) {
        if (codecRequiresVlc && vlcAvailable) {
            FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
            vlcController.initializeSuspend()
        }
    }

    DisposableEffect(vlcController) {
        onDispose {
            vlcController.release()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                    isBuffering = false
                    showMiniSpinner = false
                    FastFormatPreferenceCache.recordWorkingLiveUrl(channel.playlistId, effectiveUrl)
                }
                if (playbackState == Player.STATE_BUFFERING) {
                    // Spinner deferred — avoids green wheel on every channel zap
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                var hasVideoTrack = false
                var hasSupportedVideoTrack = false
                var firstVideoGroup: Tracks.Group? = null
                var anyVideoSelected = false
                var hasAudioTrack = false
                var hasSupportedAudioTrack = false
                var fallbackAudioGroup: Tracks.Group? = null
                var fallbackAudioIndex = -1
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_VIDEO) {
                        hasVideoTrack = true
                        if (firstVideoGroup == null && group.length > 0) firstVideoGroup = group
                        for (i in 0 until group.length) {
                            if (group.isTrackSupported(i, true) || group.getTrackSupport(i) == C.FORMAT_EXCEEDS_CAPABILITIES) {
                                hasSupportedVideoTrack = true
                            }
                            if (group.isTrackSelected(i)) anyVideoSelected = true
                        }
                    } else if (group.type == C.TRACK_TYPE_AUDIO) {
                        hasAudioTrack = true
                        for (i in 0 until group.length) {
                            if (group.isTrackSupported(i, false)) {
                                hasSupportedAudioTrack = true
                                if (fallbackAudioGroup == null) {
                                    fallbackAudioGroup = group
                                    fallbackAudioIndex = i
                                }
                            }
                        }
                    }
                }
                if (hasVideoTrack && !anyVideoSelected && firstVideoGroup != null && hasSupportedVideoTrack) {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(firstVideoGroup.mediaTrackGroup, listOf(0)))
                        .build()
                }
                if (hasAudioTrack && hasSupportedAudioTrack && fallbackAudioGroup != null && fallbackAudioIndex >= 0) {
                    val anySelectedAudioOk = tracks.groups.any { g ->
                        g.type == C.TRACK_TYPE_AUDIO && (0 until g.length).any { idx -> g.isTrackSelected(idx) && g.isTrackSupported(idx, false) }
                    }
                    if (!anySelectedAudioOk) {
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setOverrideForType(TrackSelectionOverride(fallbackAudioGroup.mediaTrackGroup, listOf(fallbackAudioIndex)))
                            .build()
                    }
                }
                val videoUnsupported = hasVideoTrack && !hasSupportedVideoTrack
                val audioUnsupported = hasAudioTrack && !hasSupportedAudioTrack
                if ((videoUnsupported || audioUnsupported) && vlcAvailable && !codecRequiresVlc) {
                    forceVlcSoftwareVideo = false
                    codecRequiresVlc = true
                    player.stop()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
                    return
                }
                val isCodecError =
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                val isHardIoFail =
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
                if (!isCodecError && !isHardIoFail && formatCandidateUrls.size > 1 && candidateIndex < 1 && retryCount == 0) {
                    candidateIndex += 1
                } else if (isMultiQualityMini && miniMqVariants.size >= 2) {
                    val decision = MultiQualityEngine.reportAdaptiveEvent(
                        multiStableKey = channel.stableKey,
                        currentVariant = activeMiniVariant,
                        reason = AdaptationReason.SOURCE_OUTAGE
                    )
                    if (decision != null && decision.switched) {
                        activeMiniVariant = decision.variant
                        codecRequiresVlc = false
                        forceVlcSoftwareVideo = false
                        candidateIndex = 0
                        miniMqGeneration += 1
                        return
                    } else if (vlcAvailable && !codecRequiresVlc) {
                        FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
                        codecRequiresVlc = true
                        player.stop()
                    }
                } else if (vlcAvailable && !codecRequiresVlc) {
                    FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
                    codecRequiresVlc = true
                    player.stop()
                } else if (formatCandidateUrls.size > 1 && retryCount < 4) {
                    retryCount++
                    candidateIndex = (candidateIndex + 1) % formatCandidateUrls.size
                }
            }
        }
        player.addListener(listener)
        onDispose {
            runCatching {
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
                player.removeListener(listener)
                player.release()
            }
        }
    }

    LaunchedEffect(player, vlcController, channel.stableKey, activeMiniChannel.url, effectiveUrl, miniMqGeneration, codecRequiresVlc, forceVlcSoftwareVideo, retryCount) {
        // Instant zap: hide spinner, free previous sockets, start next stream immediately
        isBuffering = false
        showMiniSpinner = false
        runCatching {
            ChannelRepository.cancelPendingStreamRequests()
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            player.volume = 1.0f
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .clearOverrides()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setSelectUndeterminedTextLanguage(true)
                .build()
        }

        val rotatedUa = activeMiniChannel.userAgent?.takeIf { it.isNotBlank() }
            ?: channel.userAgent?.takeIf { it.isNotBlank() }
            ?: STREAM_USER_AGENTS[retryCount % STREAM_USER_AGENTS.size]
        val rawPlayUrl = effectiveUrl.trim()
        val targetPlayUrl = rawPlayUrl.substringBefore("|").trim()
        val low = targetPlayUrl.lowercase(Locale.ROOT).substringBefore("?")
        val isExplicitHlsUrl = low.endsWith(".m3u8") || targetPlayUrl.contains("output=m3u8", ignoreCase = true)

        if (codecRequiresVlc && vlcAvailable && channel.drmKey.isBlank()) {
            vlcController.playStreamSuspend(
                url = targetPlayUrl,
                userAgent = rotatedUa,
                referer = activeMiniChannel.referer ?: channel.referer,
                cookie = activeMiniChannel.cookie ?: channel.cookie,
                isVod = false,
                forceSoftwareVideo = forceVlcSoftwareVideo || preferences.preferSoftwareDecoder,
                startPositionMs = 0L
            )
            return@LaunchedEffect
        } else {
            vlcController.stop()
        }

        val customHeaders = IboPlayerProfileSpoofer.buildStreamHeadersMap(
            targetUrl = targetPlayUrl,
            serverBaseUrl = activeMiniChannel.streamBaseUrl.ifBlank { channel.streamBaseUrl },
            customReferer = activeMiniChannel.referer ?: channel.referer,
            customOrigin = activeMiniChannel.origin ?: channel.origin,
            customCookie = activeMiniChannel.cookie ?: channel.cookie,
            isLiveManifest = false
        )
        httpFactory.setDefaultRequestProperties(customHeaders)
        httpFactory.setUserAgent(rotatedUa)

        val itemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(targetPlayUrl))
            .setLiveConfiguration(
                // Lower live edge offset for mini preview => faster join on channel change
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(2_200L)
                    .setMinOffsetMs(800L)
                    .setMaxOffsetMs(45_000L)
                    .setMinPlaybackSpeed(1.0f)
                    .setMaxPlaybackSpeed(1.0f)
                    .build()
            )
        if (isExplicitHlsUrl) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            player.setMediaSource(fastHlsMediaSourceFactory.createMediaSource(itemBuilder.build()))
        } else {
            player.setMediaSource(defaultMediaSourceFactory.createMediaSource(itemBuilder.build()))
        }
        player.playWhenReady = true
        player.prepare()
    }

    // 4K-Aware Mini Player Watchdog with Real-Time Multi-Quality Upgrade & Instant Step-Down
    LaunchedEffect(player, channel.stableKey, activeMiniChannel.url, miniMqGeneration, effectiveUrl, codecRequiresVlc) {
        if (codecRequiresVlc || !vlcAvailable) return@LaunchedEffect
        var lastRenderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
        var lastPosMs = player.currentPosition
        var lastBufMs = player.bufferedPosition
        var wasReadyMini = false
        var smoothReadyTicksMini = 0
        var midRebufferTicksMini = 0
        var frozenTicks = 0
        var bufferingTicks = 0
        while (!codecRequiresVlc) {
            delay(180L)
            if (codecRequiresVlc) break
            NetworkSpeedMonitor.updateExoBandwidthEstimate(miniBandwidthMeter.bitrateEstimate)
            val state = player.playbackState
            val renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
            val frameDelta = renderedFrames - lastRenderedFrames
            val currentPos = player.currentPosition
            val bufferedPos = player.bufferedPosition
            val bufferAheadMs = (bufferedPos - currentPos).coerceAtLeast(0L)
            val posDelta = currentPos - lastPosMs
            val bufDelta = bufferedPos - lastBufMs
            lastRenderedFrames = renderedFrames
            lastPosMs = currentPos
            lastBufMs = bufferedPos
            val miniAudioOut = (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) +
                (player.audioDecoderCounters?.skippedOutputBufferCount ?: 0)
            if (state == Player.STATE_READY) {
                bufferingTicks = 0
                midRebufferTicksMini = 0
                if (frameDelta > 0) {
                    wasReadyMini = true
                    smoothReadyTicksMini++
                }
                if (isMultiQualityMini && miniMqVariants.size >= 2) {
                    val curVar = activeMiniVariant
                    if (curVar != null && frameDelta > 0 && renderedFrames >= 20) {
                        MultiQualityEngine.markStablePlayback(channel.stableKey, curVar)
                    }
                    val upTicks = if (curVar?.quality == StreamQualityRank.FHD) 36 else 25
                    if (curVar != null && smoothReadyTicksMini >= upTicks) {
                        smoothReadyTicksMini = 0
                        val upDec = MultiQualityEngine.tryProactiveUpgrade(
                            multiStableKey = channel.stableKey,
                            currentVariant = curVar,
                            bufferAheadMs = bufferAheadMs
                        )
                        if (upDec != null && upDec.switched) {
                            activeMiniVariant = upDec.variant
                            candidateIndex = 0
                            miniMqGeneration += 1
                            break
                        }
                    }
                }
                if (renderedFrames >= 12 && frameDelta > 0 && miniAudioOut == 0) {
                    frozenTicks++
                    if (frozenTicks >= 3) {
                        forceVlcSoftwareVideo = false
                        codecRequiresVlc = true
                        player.stop()
                        break
                    }
                } else if (frameDelta == 0 && posDelta > 100L) {
                    frozenTicks++
                    val fLimit = if (isMultiQualityMini && miniMqVariants.size >= 2) 3 else 7
                    if (frozenTicks >= fLimit) {
                        if (isMultiQualityMini && miniMqVariants.size >= 2) {
                            val dec = MultiQualityEngine.reportAdaptiveEvent(
                                multiStableKey = channel.stableKey,
                                currentVariant = activeMiniVariant,
                                reason = AdaptationReason.SOURCE_CONGESTION
                            )
                            if (dec != null && dec.switched) {
                                activeMiniVariant = dec.variant
                                candidateIndex = 0
                                miniMqGeneration += 1
                                break
                            }
                        }
                        if (formatCandidateUrls.size > 1 && candidateIndex < formatCandidateUrls.size - 1) {
                            candidateIndex++
                            break
                        } else {
                            forceVlcSoftwareVideo = false
                            codecRequiresVlc = true
                            player.stop()
                            break
                        }
                    }
                } else if (frameDelta > 0) {
                    frozenTicks = 0
                }
            } else if (state == Player.STATE_BUFFERING) {
                frozenTicks = 0
                smoothReadyTicksMini = 0
                if (wasReadyMini && isMultiQualityMini && miniMqVariants.size >= 2) {
                    midRebufferTicksMini++
                    if (midRebufferTicksMini >= 2) {
                        val dec = MultiQualityEngine.reportAdaptiveEvent(
                            multiStableKey = channel.stableKey,
                            currentVariant = activeMiniVariant,
                            reason = AdaptationReason.INTERNET_FLUCTUATION
                        )
                        if (dec != null && dec.switched) {
                            activeMiniVariant = dec.variant
                            candidateIndex = 0
                            midRebufferTicksMini = 0
                            miniMqGeneration += 1
                            break
                        }
                    }
                }
                if (renderedFrames == 0 && posDelta <= 0L && bufDelta <= 0L) {
                    bufferingTicks++
                    val stallLimit = 3 // ~540ms — never leave mini preview hanging
                    if (bufferingTicks >= stallLimit) {
                        if (isMultiQualityMini && miniMqVariants.size >= 2) {
                            val decision = MultiQualityEngine.reportAdaptiveEvent(
                                multiStableKey = channel.stableKey,
                                currentVariant = activeMiniVariant,
                                reason = AdaptationReason.SOURCE_CONGESTION
                            )
                            if (decision != null && decision.switched) {
                                activeMiniVariant = decision.variant
                                candidateIndex = 0
                                bufferingTicks = 0
                                miniMqGeneration += 1
                                break
                            }
                        }
                        if (formatCandidateUrls.size > 1 && candidateIndex < 1) {
                            candidateIndex++
                            break
                        } else {
                            FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
                            forceVlcSoftwareVideo = false
                            codecRequiresVlc = true
                            player.stop()
                            break
                        }
                    }
                } else {
                    bufferingTicks = 0
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { onExpandFullScreen() }
    ) {
        if (codecRequiresVlc && vlcAvailable && channel.drmKey.isBlank()) {
            VlcEmbeddedVideoSurface(
                controller = vlcController,
                resizeMode = VideoResizeMode.FILL,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        this.useController = false
                        this.resizeMode = VideoResizeMode.FILL.exoMode
                        this.setKeepContentOnPlayerReset(true)
                        this.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        this.keepScreenOn = true
                        this.isFocusable = false
                        this.isFocusableInTouchMode = false
                        this.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                },
                update = { view ->
                    view.player = player
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Top overlay badges on Mini Player
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xCC000000), Color.Transparent)
                    )
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "⛶ اضغط OK لتكبير الشاشة",
                color = Color.White,
                fontSize = if (compact) 10.sp else 11.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier
                    .background(Color(0xCC14141D), RoundedCornerShape(6.dp))
                    .border(1.dp, EmeraldLive.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
            if (networkSpeedLabel.isNotBlank()) {
                Text(
                    text = "⬇ $networkSpeedLabel",
                    color = EmeraldLive,
                    fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .background(Color(0xCC0D0D14), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
            }
        }

        // Delayed spinner only: if still buffering after 2.2s with no frames, show a discreet indicator.
        // Never keep a permanent green wheel on mini preview during normal channel zapping.
        LaunchedEffect(isBuffering, channel.stableKey, candidateIndex, miniMqGeneration) {
            if (!isBuffering) {
                showMiniSpinner = false
                return@LaunchedEffect
            }
            showMiniSpinner = false
            delay(2200L)
            if (isBuffering) showMiniSpinner = true
        }
        if (showMiniSpinner && isBuffering) {
            CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.75f),
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(if (compact) 22.dp else 26.dp)
                    .align(Alignment.Center)
            )
        }
    }
}
