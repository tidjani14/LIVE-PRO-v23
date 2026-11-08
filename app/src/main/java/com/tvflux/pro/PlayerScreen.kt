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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextAlign
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
 * Hybrid User-Agent Rotation (v18.0):
 * Starts with a widely accepted Android player profile so IPTV servers accept the stream
 * immediately on attempt #1, and rotates through compatible profiles on fallback retries.
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
    vodWatchProgressMap: Map<String, VodWatchProgress> = emptyMap(),
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
    var tracksModalInitialTab by remember { mutableStateOf(0) }
    var resizeMode by remember { mutableStateOf(preferences.resizeMode) }
    var playbackSpeed by remember { mutableStateOf(PlaybackSpeedOption.NORMAL) }

    var isBuffering by remember(channel.stableKey) { mutableStateOf(true) }
    var showBufferingBadge by remember(channel.stableKey) { mutableStateOf(false) }
    var isPlaying by remember(channel.stableKey) { mutableStateOf(true) }
    var isSourceStopped by remember(channel.stableKey) { mutableStateOf(false) }
    var errorMessage by remember(channel.stableKey) { mutableStateOf<String?>(null) }
    var playerDiagnosticAlert by remember(channel.stableKey) { mutableStateOf<DiagnosticAlertInfo?>(null) }
    var lastErrorCodeName by remember(channel.stableKey) { mutableStateOf("") }
    var fastFallbackTriggered by remember(channel.stableKey) { mutableStateOf(false) }
    var activeSessionChannelKey by remember { mutableStateOf("") }
    var activeSessionStreamUrl by remember { mutableStateOf("") }
    // PRODUCTION: ExoPlayer absolute priority for ALL HTTP/HTTPS (Live / Movie / Series).
    // Local DB "requires VLC" only shortens failover timeout — NEVER forces VLC on open.
    // VLC starts immediately ONLY for exclusive non-HTTP protocols (RTMP/MMS/UDP/RTP) or exclusive codecs.
    val knownVlcFromLocalDb = remember(channel.stableKey, channel.url) {
        FastFormatPreferenceCache.doesChannelPreferVlc(channel.stableKey, channel.url)
    }
    val initialNeedsVlc = remember(channel.stableKey, channel.url) {
        StreamFormatDetectorAndRenewer.isSoftwareVideoPreferredCodec(
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
    val topBackBtnFocusRequester = remember { FocusRequester() }
    val topFavBtnFocusRequester = remember { FocusRequester() }
    val topPipBtnFocusRequester = remember { FocusRequester() }
    val topListBtnFocusRequester = remember { FocusRequester() }
    val centerPrevFocusRequester = remember { FocusRequester() }
    val centerRewindFocusRequester = remember { FocusRequester() }
    val centerPlayFocusRequester = remember { FocusRequester() }
    val centerFfwdFocusRequester = remember { FocusRequester() }
    val centerNextFocusRequester = remember { FocusRequester() }
    val bottomPrevChFocusRequester = remember { FocusRequester() }
    val bottomNextChFocusRequester = remember { FocusRequester() }
    val bottomFirstPillFocusRequester = remember { FocusRequester() }
    val bottomQualityPillFocusRequester = remember { FocusRequester() }
    val bottomAudioPillFocusRequester = remember { FocusRequester() }
    val bottomSubtitlesPillFocusRequester = remember { FocusRequester() }
    val bottomResizePillFocusRequester = remember { FocusRequester() }
    val bottomSettingsPillFocusRequester = remember { FocusRequester() }
    val bottomFavPillFocusRequester = remember { FocusRequester() }
    var lastControlFocusRequester by remember { mutableStateOf(centerPlayFocusRequester) }
    val vodSeekBarFocusRequester = remember { FocusRequester() }
    val activeBouquetFocusRequester = remember { FocusRequester() }
    val firstChannelInBouquetFocusRequester = remember { FocusRequester() }
    val activeChannelFocusRequester = remember { FocusRequester() }
    var attachedPlayerView by remember { mutableStateOf<PlayerView?>(null) }

    var networkSpeedLabel by remember { mutableStateOf(NetworkSpeedMonitor.sampleCurrentSpeedLabel()) }
    var vodPositionMs by remember(channel.stableKey) { mutableStateOf(initialResumePositionMs.coerceAtLeast(0L)) }
    var vodDurationMs by remember(channel.stableKey) { mutableStateOf(0L) }
    var pendingResumeTargetMs by remember(channel.stableKey) { mutableStateOf(initialResumePositionMs.coerceAtLeast(0L)) }

    LaunchedEffect(showQuickDrawer, showTracksDialog, showControls) {
        if (!showQuickDrawer && !showTracksDialog && !showControls) {
            for (attempt in 1..4) {
                delay(45L)
                if (runCatching { rootPlayerFocusRequester.requestFocus() }.isSuccess) break
            }
        } else if (showControls && !showQuickDrawer && !showTracksDialog) {
            for (attempt in 1..5) {
                delay(45L)
                if (runCatching { lastControlFocusRequester.requestFocus() }.isSuccess) break
                if (runCatching { centerPlayFocusRequester.requestFocus() }.isSuccess) break
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
    // 4K UHD & High-Bitrate MPEG-TS Extractor Suite (v24.1):
    // - Uses 188 * 3600 (676 KB) timestamp search window so 25–45 Mbps 4K HEVC raw MPEG-TS streams
    //   always locate their PCR timestamps without failing or stalling!
    // - Omits FLAG_DETECT_ACCESS_UNITS because synthetic H.264 AUD injection corrupts HEVC/H.265 4K NAL units.
    val universalDataSourceFactory = remember {
        ExoFfmpegUniversalEngine.buildUniversalDataSourceFactory(context, httpFactory)
    }

    val extractorsFactory = remember {
        ExoFfmpegUniversalEngine.buildUniversalExtractorsFactory(
            isLiveStream = (channel.contentType == StreamContentType.LIVE_TV)
        )
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
            .setDataSourceFactory(universalDataSourceFactory)
            .setLoadErrorHandlingPolicy(lightningProbeErrorPolicy)
    }

    val fastDashMediaSourceFactory = remember {
        DashMediaSource.Factory(universalDataSourceFactory)
            .setManifestParser(FastRefreshDashManifestParser())
            .setFallbackTargetLiveOffsetMs(3_500L)
            .setLoadErrorHandlingPolicy(lightningProbeErrorPolicy)
    }

    val fastHlsMediaSourceFactory = remember {
        HlsMediaSource.Factory(universalDataSourceFactory)
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
        // Ultra-fast sub-150ms first-frame zap + deep 24s-35s / 64MB anti-freeze cushion
        val minBufMs = bp.minBufferMs.coerceIn(1_500, 8_000)
        val maxBufMs = bp.maxBufferMs.coerceIn(18_000, 35_000).coerceAtLeast(minBufMs + 5_000)
        val startPlaybackBufMs = bp.playbackBufferMs.coerceIn(100, 500)
        val afterRebufferBufMs = bp.rebufferMs.coerceIn(500, 1_500)
        val backBufMs = bp.backBufferMs.coerceIn(1_500, 6_000)

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

        val renderersFactory = ExoFfmpegUniversalEngine.buildAllCodecRenderersFactory(
            context = context,
            forceSoftwareVideoProvider = { forceExoSoftwareCodec.get() },
            preferFfmpegExtension = true
        )

        val trackSelector = ExoFfmpegUniversalEngine.buildAllCodecTrackSelector(
            context = context,
            capHeightForWeakDevice = isWeakDevice
        )

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
    val vlcAvailable = true
    var vlcFallbackToExo by remember(channel.stableKey, activeEngine) { mutableStateOf(false) }
    val useFfmpegSwMode = !vlcFallbackToExo && (codecRequiresVlc || forceVlcSoftwareVideo || activeEngine == InternalPlayerEngine.VLC_PLAYER)
    val useVlcEngine = false

    LaunchedEffect(useFfmpegSwMode, preferences.preferSoftwareDecoder) {
        forceExoSoftwareCodec.set(useFfmpegSwMode || preferences.preferSoftwareDecoder)
    }

    LaunchedEffect(player, playbackSpeed) {
        player.playbackParameters = PlaybackParameters(playbackSpeed.speed)
    }

    LaunchedEffect(player, channel.stableKey, activeStreamChannel.url, effectiveUrl, mqSwitchGeneration, formatAttemptMode, retryCount, useFfmpegSwMode) {
        val sessionId = PlaybackSessionGuard.beginSession(channel.stableKey)
        val isChannelSwitch = activeSessionChannelKey.isNotEmpty() && activeSessionChannelKey != channel.stableKey
        val prevStreamUrl = activeSessionStreamUrl
        activeSessionChannelKey = channel.stableKey
        activeSessionStreamUrl = effectiveUrl

        if (isChannelSwitch) {
            isSourceStopped = false
            isSeamlessMqTransition = false
        }
        if (!isSeamlessMqTransition) {
            isBuffering = true
        }
        errorMessage = null

        // إيقاف القناة السابقة بشكل ذري نظيف دون فصل سطح الفيديو (Video Surface) لضمان عدم ظهور صوت بدون صورة أبداً!
        runCatching {
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            sharedAllocator.trim()
            if (prevStreamUrl.isNotBlank() && prevStreamUrl != effectiveUrl) {
                ChannelRepository.terminatePreviousStreamSessionAndFlushCache(prevStreamUrl)
                if (isChannelSwitch) {
                    StreamFormatDetectorAndRenewer.clearChannelCache(prevStreamUrl)
                }
            }
            // التأكد من بقاء PlayerView مرتبطاً بالمشغل وسطح الفيديو مرئياً دائماً عند التنقل بين القنوات
            attachedPlayerView?.let { pv ->
                if (pv.player !== player) {
                    pv.player = player
                }
                (pv.videoSurfaceView as? android.view.SurfaceView)?.visibility = android.view.View.VISIBLE
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

        val isMpdStream = channel.contentType != StreamContentType.LIVE_TV && (
            fastSpec.mimeType == MimeTypes.APPLICATION_MPD ||
            low.endsWith(".mpd") || fullLow.contains("format=mpd") || fullLow.contains("type=mpd")
        )
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

        forceExoSoftwareCodec.set(useFfmpegSwMode || preferences.preferSoftwareDecoder || fastSpec.preferSoftwareDecoder)
        val engineModeBadge = if (forceExoSoftwareCodec.get()) "Exo+FFmpeg SW" else "Exo+FFmpeg HW"
        detectedSpecLabel = "$mqBadgePrefix$engineModeBadge • ${fastSpec.containerFormat} • ${fastSpec.videoCodecHint}/${fastSpec.audioCodecHint}"


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

        // Broadcast-Grade Watchdog (~180ms/tick):
        // Gives TV hardware decoders full GOP keyframe alignment time (~5.0s–6.5s) so switching channels NEVER kills
        // a stream that is actively connecting or waiting for its first IDR video keyframe!
        val maxFrozenReadyTicks = when {
            is4kOrHevcHint -> 34
            else -> 28
        }
        val maxInitialStallTicks = when {
            is4kOrHevcHint -> 34
            else -> 26
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

        while (!useVlcEngine) {
            delay(180L) // Ultra-fast 180ms ABR telemetry loop for instant reaction
            if (useVlcEngine) break

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
                if (frameDelta > 0 || player.isPlaying) {
                    wasReadyInThisSession = true
                    continuousSmoothReadyTicks++
                    if (isSourceStopped || errorMessage != null || playerDiagnosticAlert != null) {
                        isSourceStopped = false
                        errorMessage = null
                        playerDiagnosticAlert = null
                    }
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
                        forceExoSoftwareCodec.set(true)
                        forceVlcSoftwareVideo = true
                        codecRequiresVlc = true
                        retryCount += 1
                        break
                    }
                } else if (renderedAudioBuffers > 0) {
                    silentAudioTicks = 0
                }

                // B) Frozen-Video Detector (Audio advancing while 0 video frames are rendered for >5s):
                // Only intervenes once if 0 frames have ever rendered after 5+ seconds, and NEVER forces software video on HD/4K!
                if (channel.contentType == StreamContentType.LIVE_TV) {
                    if (renderedFrames == 0 && frameDelta == 0 && posDelta > 100L) {
                        frozenPictureTicks++
                        val freezeLimit = maxFrozenReadyTicks
                        if (frozenPictureTicks >= freezeLimit) {
                            frozenPictureTicks = 0
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
                            } else if (retryCount == 0 && formatAttemptMode == 0) {
                                isSeamlessMqTransition = true
                                formatAttemptMode = if (effectiveUrl.contains(".m3u8", ignoreCase = true)) 2 else 1
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
                    // Allow ExoPlayer's native LoadControl buffer to absorb normal 1-4s network jitter without killing the stream!
                    // Only trigger a hard reconnect if the stream has made ZERO buffer progress (bufDelta <= 0L) for ~5.0 seconds (28 ticks).
                    if (bufDelta > 0L) {
                        midStreamBufferingTicks = 0
                    }
                    val hardResumeTicks = 28
                    if (midStreamBufferingTicks >= hardResumeTicks) {
                        isSeamlessMqTransition = true
                        midStreamBufferingTicks = 0
                        recentRebufferEvents = 0
                        errorMessage = null
                        if (formatCandidateUrls.size > 1 && candidateIndex < formatCandidateUrls.size - 1) {
                            candidateIndex += 1
                            formatAttemptMode = 0
                            break
                        }
                        if (retryCount >= 2) {
                            isSourceStopped = true
                        }
                        retryCount++
                        break
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
                        } else {
                            if (retryCount >= 2) {
                                isSourceStopped = true
                            }
                            if (vlcAvailable && !vlcFallbackToExo && channel.drmKey.isBlank() && !codecRequiresVlc) {
                                isSeamlessMqTransition = true
                                FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
                                forceVlcSoftwareVideo = false
                                codecRequiresVlc = true
                            }
                            candidateIndex = (candidateIndex + 1) % formatCandidateUrls.size.coerceAtLeast(1)
                            formatAttemptMode = 0
                            retryCount++
                            break
                        }
                    }
                } else {
                    bufferingStallTicks = 0
                }
            } else if ((state == Player.STATE_IDLE || state == Player.STATE_ENDED) && errorMessage == null) {
                bufferingStallTicks++
                if (bufferingStallTicks >= 18) {
                    bufferingStallTicks = 0
                    if (retryCount >= 2) {
                        isSourceStopped = true
                    }
                    isSeamlessMqTransition = true
                    candidateIndex = (candidateIndex + 1) % formatCandidateUrls.size.coerceAtLeast(1)
                    formatAttemptMode = 0
                    retryCount++
                    break
                }
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
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration.let { if (it == C.TIME_UNSET || it < 0L) 0L else it }
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

    DisposableEffect(player, channel.stableKey) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    isSourceStopped = false
                    showBufferingBadge = false
                    errorMessage = null
                    playerDiagnosticAlert = null
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
                    forceExoSoftwareCodec.set(true)
                    forceVlcSoftwareVideo = true
                    codecRequiresVlc = true
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
                if (isContainerMismatch && formatAttemptMode == 0) {
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
                    } else if (vlcAvailable && !useVlcEngine && !vlcFallbackToExo) {
                        isSeamlessMqTransition = true
                        FastFormatPreferenceCache.markChannelRequiresVlc(channel.stableKey, effectiveUrl)
                        forceExoSoftwareCodec.set(true)
                        forceVlcSoftwareVideo = true
                        codecRequiresVlc = true
                        errorMessage = null
                        retryCount += 1
                    } else {
                        fastFallbackTriggered = true
                        errorMessage = ""
                    }
                } else if (isCodecError && !forceExoSoftwareCodec.get()) {
                    // Instant automatic escalation to Software Video + FFmpeg 6.0 Audio inside ExoPlayer!
                    isSeamlessMqTransition = true
                    forceExoSoftwareCodec.set(true)
                    forceVlcSoftwareVideo = true
                    codecRequiresVlc = true
                    errorMessage = null
                    retryCount += 1
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
                    val rawPos = player.currentPosition.coerceAtLeast(0L)
                    val pos = maxOf(rawPos, vodPositionMs)
                    val rawDur = player.duration.let { if (it == C.TIME_UNSET || it < 0L) 0L else it }
                    val dur = maxOf(rawDur, vodDurationMs)
                    if (pos >= 2_000L) {
                        onSaveVodProgress(channel, pos, dur)
                    }
                }
                player.removeListener(listener)
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            runCatching {
                // قطع الجلسة نهائياً وإفراغ الكاش والـ Sockets فقط عند الخروج الكامل من المشغل
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
                player.clearVideoSurface()
                player.release()
                sharedAllocator.trim()
                ExoFfmpegUniversalEngine.closeAllActiveStreamDataSources()
                ChannelRepository.terminatePreviousStreamSessionAndFlushCache(activeSessionStreamUrl)
                StreamFormatDetectorAndRenewer.clearChannelCache(activeSessionStreamUrl)
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

    // نظام إعادة المحاولة التلقائي المستمر في الخلفية:
    // - عند الانقطاع السريع، يحاول فوراً وبصمت (خلال 150ms–300ms) دون إزعاج المشاهد بأي نافذة.
    // - إذا استمر توقف المصدر، يعرض فقط عجلة تحميل وعبارة "متوقف من المصدر" ويواصل المحاولة تلقائياً في الخلفية
    //   وعند عودة البث من المصدر يشتغل تلقائياً وتختفي شاشة التوقف فوراً!
    LaunchedEffect(channel.stableKey, errorMessage) {
        if (errorMessage != null) {
            if (retryCount >= 1 && vlcAvailable && !useVlcEngine && !vlcFallbackToExo && !codecRequiresVlc) {
                forceVlcSoftwareVideo = false
                codecRequiresVlc = true
            }

            if (retryCount >= 2) {
                // إظهار شاشة "متوقف من المصدر" المبسطة مع استمرار المحاولة في الخلفية دون توقف
                isSourceStopped = true
                playerDiagnosticAlert = null
            }

            val backoffMs = when {
                retryCount == 0 -> 150L
                retryCount == 1 -> 350L
                retryCount == 2 -> 900L
                else -> 1_400L
            }
            delay(backoffMs)

            // إذا عاد البث أثناء الانتظار، نلغي شاشة التوقف فوراً
            if (player.playbackState == Player.STATE_READY && player.isPlaying) {
                isSourceStopped = false
                errorMessage = null
                playerDiagnosticAlert = null
                return@LaunchedEffect
            }

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

            isSeamlessMqTransition = true
            if (
                fastFallbackTriggered &&
                formatAttemptMode == 0 &&
                effectiveUrl.contains(".m3u8", ignoreCase = true)
            ) {
                formatAttemptMode = 2
            } else if (
                fastFallbackTriggered &&
                formatAttemptMode == 0 &&
                !effectiveUrl.contains(".m3u8", ignoreCase = true) &&
                channel.contentType == StreamContentType.LIVE_TV
            ) {
                formatAttemptMode = 1
            } else if (formatCandidateUrls.size > 1) {
                formatAttemptMode = 0
                candidateIndex = (candidateIndex + 1) % formatCandidateUrls.size
            } else if (formatAttemptMode < 2 && retryCount < 3) {
                formatAttemptMode += 1
            } else {
                formatAttemptMode = 0
            }

            // زيادة عداد المحاولة لإعادة تشغيل LaunchedEffect الخاص بتحميل البث بعد تنظيف الجلسة السابقة
            errorMessage = null
            retryCount += 1
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
        return player.currentPosition.coerceAtLeast(0L)
    }

    fun seekVodTo(targetMs: Long) {
        val safeTarget = if (vodDurationMs > 0L) {
            targetMs.coerceIn(0L, vodDurationMs)
        } else {
            targetMs.coerceAtLeast(0L)
        }
        pendingResumeTargetMs = 0L
        vodPositionMs = safeTarget
        player.safeSeekTo(safeTarget)
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
                            if (player.isPlaying) player.pause() else player.play()
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

        if (true) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        attachedPlayerView = this
                        this.player = player
                        this.useController = false
                        this.resizeMode = resizeMode.exoMode
                        this.setKeepContentOnPlayerReset(false)
                        this.setShutterBackgroundColor(android.graphics.Color.BLACK)
                        this.keepScreenOn = true
                        this.isFocusable = false
                        this.isFocusableInTouchMode = false
                        this.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        (this.videoSurfaceView as? android.view.SurfaceView)?.visibility = android.view.View.VISIBLE
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
                    attachedPlayerView = view
                    if (view.player !== player) {
                        view.player = player
                    }
                    if (view.resizeMode != resizeMode.exoMode) {
                        view.resizeMode = resizeMode.exoMode
                    }
                    (view.videoSurfaceView as? android.view.SurfaceView)?.visibility = android.view.View.VISIBLE
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

        if (isSourceStopped) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xD90A0A10), RoundedCornerShape(18.dp))
                    .border(1.5.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 28.dp, vertical = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color = EmeraldLive,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(if (isPhoneLandscape) 30.dp else 36.dp)
                    )
                    Text(
                        text = "متوقف من المصدر",
                        color = Color.White,
                        fontSize = if (isPhoneLandscape) 15.sp else 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (showBufferingBadge) {
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
                    text = "⬇ $networkSpeedLabel",
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
                            sizeDp = if (isPhoneLandscape) 36.dp else 44.dp,
                            modifier = Modifier.focusRequester(topBackBtnFocusRequester),
                            downTarget = centerPlayFocusRequester,
                            leftTarget = topFavBtnFocusRequester
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

                    val hasPipBtn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FocusableCircleButton(
                            onClick = { onToggleFavorite(channel) },
                            icon = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            tint = if (isFavorite) Color(0xFFFF4D6D) else Color.White,
                            contentDescription = "المفضلة",
                            sizeDp = if (isPhoneLandscape) 36.dp else 44.dp,
                            modifier = Modifier.focusRequester(topFavBtnFocusRequester),
                            downTarget = centerPlayFocusRequester,
                            rightTarget = topBackBtnFocusRequester,
                            leftTarget = if (hasPipBtn) topPipBtnFocusRequester else topListBtnFocusRequester
                        )

                        if (hasPipBtn && activity != null) {
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
                                sizeDp = if (isPhoneLandscape) 36.dp else 44.dp,
                                modifier = Modifier.focusRequester(topPipBtnFocusRequester),
                                downTarget = centerPlayFocusRequester,
                                rightTarget = topFavBtnFocusRequester,
                                leftTarget = topListBtnFocusRequester
                            )
                        }

                        FocusableCircleButton(
                            onClick = {
                                showControls = false
                                drawerSelectedBouquet = channel.group
                                drawerOpenTimestampMs = android.os.SystemClock.elapsedRealtime()
                                showQuickDrawer = true
                                focusFirstChannelTrigger += 1
                            },
                            icon = Icons.Default.List,
                            contentDescription = if (channel.contentType == StreamContentType.SERIES) "الأجزاء والحلقات" else "الباقات والقنوات",
                            sizeDp = if (isPhoneLandscape) 36.dp else 44.dp,
                            modifier = Modifier.focusRequester(topListBtnFocusRequester),
                            downTarget = centerPlayFocusRequester,
                            rightTarget = if (hasPipBtn) topPipBtnFocusRequester else topFavBtnFocusRequester
                        )
                    }
                }

                val centerDownTarget = if (channel.contentType != StreamContentType.LIVE_TV) {
                    vodSeekBarFocusRequester
                } else if (siblingChannels.size > 1) {
                    bottomPrevChFocusRequester
                } else {
                    bottomFirstPillFocusRequester
                }
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(if (isPhoneLandscape) 10.dp else 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. زر السابق (القناة السابقة / الحلقة السابقة)
                    FocusableCircleButton(
                        onClick = { switchRelativeChannel(-1) },
                        icon = Icons.Default.SkipPrevious,
                        tint = Color.White,
                        contentDescription = "السابق",
                        sizeDp = if (isPhoneLandscape) 38.dp else 46.dp,
                        modifier = Modifier
                            .focusRequester(centerPrevFocusRequester)
                            .onFocusChanged { if (it.isFocused) lastControlFocusRequester = centerPrevFocusRequester },
                        upTarget = topBackBtnFocusRequester,
                        downTarget = centerDownTarget,
                        leftTarget = centerRewindFocusRequester
                    )

                    // 2. زر التأخير (-15 ثانية للأفلام والمسلسلات أو القناة السابقة للبث المباشر)
                    FocusableCircleButton(
                        onClick = {
                            if (channel.contentType != StreamContentType.LIVE_TV) {
                                seekVodByOffset(-15_000L)
                            } else {
                                switchRelativeChannel(-1)
                            }
                        },
                        icon = Icons.Default.FastRewind,
                        tint = Color.White,
                        contentDescription = "تأخير -15s",
                        sizeDp = if (isPhoneLandscape) 38.dp else 48.dp,
                        modifier = Modifier
                            .focusRequester(centerRewindFocusRequester)
                            .onFocusChanged { if (it.isFocused) lastControlFocusRequester = centerRewindFocusRequester },
                        upTarget = topBackBtnFocusRequester,
                        downTarget = centerDownTarget,
                        rightTarget = centerPrevFocusRequester,
                        leftTarget = centerPlayFocusRequester
                    )

                    // 3. زر التشغيل / الإيقاف المؤقت (التركيز الافتراضي الرئيسي)
                    var centerPlayFocused by remember { mutableStateOf(false) }
                    IconButton(
                        onClick = {
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
                        },
                        modifier = Modifier
                            .focusRequester(centerPlayFocusRequester)
                            .size(if (isPhoneLandscape) 56.dp else 68.dp)
                            .scale(if (centerPlayFocused) 1.14f else 1.0f)
                            .onFocusChanged {
                                centerPlayFocused = it.isFocused
                                if (it.isFocused) lastControlFocusRequester = centerPlayFocusRequester
                            }
                            .onKeyEvent { ev ->
                                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && ev.nativeKeyEvent.repeatCount == 0) {
                                    when (ev.nativeKeyEvent.keyCode) {
                                        KeyEvent.KEYCODE_DPAD_UP -> {
                                            runCatching { topBackBtnFocusRequester.requestFocus() }
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                                            runCatching { centerDownTarget.requestFocus() }
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                            runCatching { centerRewindFocusRequester.requestFocus() }
                                            true
                                        }
                                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                                            runCatching { centerFfwdFocusRequester.requestFocus() }
                                            true
                                        }
                                        else -> false
                                    }
                                } else false
                            }
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

                    // 4. زر التقديم (+15 ثانية للأفلام والمسلسلات أو القناة التالية للبث المباشر)
                    FocusableCircleButton(
                        onClick = {
                            if (channel.contentType != StreamContentType.LIVE_TV) {
                                seekVodByOffset(15_000L)
                            } else {
                                switchRelativeChannel(1)
                            }
                        },
                        icon = Icons.Default.FastForward,
                        tint = Color.White,
                        contentDescription = "تقديم +15s",
                        sizeDp = if (isPhoneLandscape) 38.dp else 48.dp,
                        modifier = Modifier
                            .focusRequester(centerFfwdFocusRequester)
                            .onFocusChanged { if (it.isFocused) lastControlFocusRequester = centerFfwdFocusRequester },
                        upTarget = topFavBtnFocusRequester,
                        downTarget = centerDownTarget,
                        rightTarget = centerPlayFocusRequester,
                        leftTarget = centerNextFocusRequester
                    )

                    // 5. زر التالي (القناة التالية / الحلقة التالية)
                    FocusableCircleButton(
                        onClick = { switchRelativeChannel(1) },
                        icon = Icons.Default.SkipNext,
                        tint = Color.White,
                        contentDescription = "التالي",
                        sizeDp = if (isPhoneLandscape) 38.dp else 46.dp,
                        modifier = Modifier
                            .focusRequester(centerNextFocusRequester)
                            .onFocusChanged { if (it.isFocused) lastControlFocusRequester = centerNextFocusRequester },
                        upTarget = topFavBtnFocusRequester,
                        downTarget = centerDownTarget,
                        rightTarget = centerFfwdFocusRequester
                    )
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
                    // Bottom Control Bar: Slim Interactive Progress Bar for Movies/Series + Quick Episode Switcher OR Live TV Quick Navigation Bar
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
                                    width = if (seekBarFocused) 2.5.dp else 1.dp,
                                    color = if (seekBarFocused) BeinFocusGlow else BeinHighlightPurple.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .onFocusChanged {
                                    seekBarFocused = it.isFocused
                                    if (it.isFocused) lastControlFocusRequester = vodSeekBarFocusRequester
                                }
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
                                                if (player.isPlaying) player.pause() else player.play()
                                                true
                                            }
                                            KeyEvent.KEYCODE_DPAD_UP -> {
                                                runCatching { centerPlayFocusRequester.requestFocus() }
                                                true
                                            }
                                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                val downDest = if (channel.contentType == StreamContentType.SERIES && siblingChannels.size > 1) {
                                                    bottomPrevChFocusRequester
                                                } else {
                                                    bottomFirstPillFocusRequester
                                                }
                                                runCatching { downDest.requestFocus() }
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

                        // Quick Next/Previous Episode Switcher Row for Series (3.3 إمكانية الانتقال إلى الحلقة التالية/السابقة مباشرة من المشغل)
                        if (channel.contentType == StreamContentType.SERIES && siblingChannels.size > 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xE6121216))
                                    .border(1.dp, BeinBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📺 ${channel.group} • ${channel.name}",
                                    color = EmeraldLive,
                                    fontSize = if (isPhoneLandscape) 11.5.sp else 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FocusableActionPill(
                                        text = "⏮ الحلقة السابقة",
                                        isPrimary = false,
                                        compact = isPhoneLandscape,
                                        modifier = Modifier
                                            .focusRequester(bottomPrevChFocusRequester)
                                            .onFocusChanged { if (it.isFocused) lastControlFocusRequester = bottomPrevChFocusRequester },
                                        upTarget = vodSeekBarFocusRequester,
                                        downTarget = bottomFirstPillFocusRequester,
                                        leftTarget = bottomNextChFocusRequester,
                                        onClick = { switchRelativeChannel(-1) }
                                    )
                                    FocusableActionPill(
                                        text = "⏭ الحلقة التالية",
                                        isPrimary = true,
                                        compact = isPhoneLandscape,
                                        modifier = Modifier
                                            .focusRequester(bottomNextChFocusRequester)
                                            .onFocusChanged { if (it.isFocused) lastControlFocusRequester = bottomNextChFocusRequester },
                                        upTarget = vodSeekBarFocusRequester,
                                        downTarget = bottomFirstPillFocusRequester,
                                        rightTarget = bottomPrevChFocusRequester,
                                        onClick = { switchRelativeChannel(1) }
                                    )
                                }
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
                                        modifier = Modifier
                                            .focusRequester(bottomPrevChFocusRequester)
                                            .onFocusChanged { if (it.isFocused) lastControlFocusRequester = bottomPrevChFocusRequester },
                                        upTarget = centerPlayFocusRequester,
                                        downTarget = bottomFirstPillFocusRequester,
                                        leftTarget = bottomNextChFocusRequester,
                                        onClick = { switchRelativeChannel(-1) }
                                    )
                                    FocusableActionPill(
                                        text = "⏭ التالية",
                                        isPrimary = false,
                                        compact = isPhoneLandscape,
                                        modifier = Modifier
                                            .focusRequester(bottomNextChFocusRequester)
                                            .onFocusChanged { if (it.isFocused) lastControlFocusRequester = bottomNextChFocusRequester },
                                        upTarget = centerPlayFocusRequester,
                                        downTarget = bottomFirstPillFocusRequester,
                                        rightTarget = bottomPrevChFocusRequester,
                                        onClick = { switchRelativeChannel(1) }
                                    )
                                }
                            }
                        }
                    }

                    // Complete Bottom Control Toolbar for Live TV, Movies, and Series:
                    // Every button (الأجزاء والحلقات/القنوات، الجودة، الصوت، الترجمة، ملء الشاشة، الإعدادات، المفضلة) is focusable & chained step-by-step!
                    run {
                        val pillUpTarget = when {
                            channel.contentType == StreamContentType.SERIES && siblingChannels.size > 1 -> bottomPrevChFocusRequester
                            channel.contentType != StreamContentType.LIVE_TV -> vodSeekBarFocusRequester
                            siblingChannels.size > 1 -> bottomPrevChFocusRequester
                            else -> centerPlayFocusRequester
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. قائمة الأجزاء والحلقات (للمسلسلات) / القنوات والباقات (للبث المباشر) / أفلام القسم (للأفلام)
                            FocusableActionPill(
                                text = when (channel.contentType) {
                                    StreamContentType.SERIES -> "📺 ${AppStrings.tr(lang, "الأجزاء والحلقات", "Saisons & Épisodes", "Seasons & Episodes")}"
                                    StreamContentType.MOVIE -> "🎬 ${AppStrings.tr(lang, "قائمة الأفلام", "Liste des Films", "Movies List")}"
                                    StreamContentType.LIVE_TV -> "📂 ${AppStrings.tr(lang, "القنوات والباقات", "Chaînes & Bouquets", "Channels & Bouquets")}"
                                },
                                isPrimary = true,
                                compact = isPhoneLandscape,
                                modifier = Modifier
                                    .focusRequester(bottomFirstPillFocusRequester)
                                    .onFocusChanged { if (it.isFocused) lastControlFocusRequester = bottomFirstPillFocusRequester },
                                upTarget = pillUpTarget,
                                leftTarget = bottomQualityPillFocusRequester,
                                onClick = {
                                    showControls = false
                                    drawerSelectedBouquet = channel.group
                                    drawerOpenTimestampMs = android.os.SystemClock.elapsedRealtime()
                                    showQuickDrawer = true
                                    focusFirstChannelTrigger += 1
                                }
                            )

                            // 2. زر الجودة (Quality)
                            FocusableActionPill(
                                text = "🎬 ${AppStrings.tr(lang, "الجودة", "Qualité", "Quality")}",
                                isPrimary = false,
                                compact = isPhoneLandscape,
                                modifier = Modifier
                                    .focusRequester(bottomQualityPillFocusRequester)
                                    .onFocusChanged { if (it.isFocused) lastControlFocusRequester = bottomQualityPillFocusRequester },
                                upTarget = pillUpTarget,
                                rightTarget = bottomFirstPillFocusRequester,
                                leftTarget = bottomAudioPillFocusRequester,
                                onClick = {
                                    lastControlFocusRequester = bottomQualityPillFocusRequester
                                    tracksModalInitialTab = 0
                                    showTracksDialog = true
                                }
                            )

                            // 3. زر الصوت (Audio)
                            FocusableActionPill(
                                text = "🔊 ${AppStrings.tr(lang, "الصوت", "Audio", "Audio")}",
                                isPrimary = false,
                                compact = isPhoneLandscape,
                                modifier = Modifier
                                    .focusRequester(bottomAudioPillFocusRequester)
                                    .onFocusChanged { if (it.isFocused) lastControlFocusRequester = bottomAudioPillFocusRequester },
                                upTarget = pillUpTarget,
                                rightTarget = bottomQualityPillFocusRequester,
                                leftTarget = bottomSubtitlesPillFocusRequester,
                                onClick = {
                                    lastControlFocusRequester = bottomAudioPillFocusRequester
                                    tracksModalInitialTab = 1
                                    showTracksDialog = true
                                }
                            )

                            // 4. زر الترجمة (Subtitles)
                            FocusableActionPill(
                                text = "💬 ${AppStrings.tr(lang, "الترجمة", "Sous-titres", "Subtitles")}",
                                isPrimary = false,
                                compact = isPhoneLandscape,
                                modifier = Modifier
                                    .focusRequester(bottomSubtitlesPillFocusRequester)
                                    .onFocusChanged { if (it.isFocused) lastControlFocusRequester = bottomSubtitlesPillFocusRequester },
                                upTarget = pillUpTarget,
                                rightTarget = bottomAudioPillFocusRequester,
                                leftTarget = bottomResizePillFocusRequester,
                                onClick = {
                                    lastControlFocusRequester = bottomSubtitlesPillFocusRequester
                                    tracksModalInitialTab = 2
                                    showTracksDialog = true
                                }
                            )

                            // 5. زر ملء الشاشة والأبعاد (Fullscreen / Aspect Ratio)
                            FocusableActionPill(
                                text = "📐 ${resizeMode.titleAr}",
                                isPrimary = false,
                                compact = isPhoneLandscape,
                                modifier = Modifier
                                    .focusRequester(bottomResizePillFocusRequester)
                                    .onFocusChanged { if (it.isFocused) lastControlFocusRequester = bottomResizePillFocusRequester },
                                upTarget = pillUpTarget,
                                rightTarget = bottomSubtitlesPillFocusRequester,
                                leftTarget = bottomSettingsPillFocusRequester,
                                onClick = {
                                    val modes = VideoResizeMode.values()
                                    resizeMode = modes[(resizeMode.ordinal + 1) % modes.size]
                                }
                            )

                            // 6. زر الإعدادات والسرعة (Settings & Speed)
                            FocusableActionPill(
                                text = "⚙️ ${AppStrings.tr(lang, "الإعدادات (${playbackSpeed.labelAr})", "Paramètres (${playbackSpeed.labelAr})", "Settings (${playbackSpeed.labelAr})")}",
                                isPrimary = false,
                                compact = isPhoneLandscape,
                                modifier = Modifier
                                    .focusRequester(bottomSettingsPillFocusRequester)
                                    .onFocusChanged { if (it.isFocused) lastControlFocusRequester = bottomSettingsPillFocusRequester },
                                upTarget = pillUpTarget,
                                rightTarget = bottomResizePillFocusRequester,
                                leftTarget = bottomFavPillFocusRequester,
                                onClick = {
                                    lastControlFocusRequester = bottomSettingsPillFocusRequester
                                    tracksModalInitialTab = 3
                                    showTracksDialog = true
                                }
                            )

                            // 7. زر المفضلة (Favorite)
                            FocusableActionPill(
                                text = if (isFavorite) "❤️ ${AppStrings.tr(lang, "في المفضلة", "Favori", "Favorited")}" else "🤍 ${AppStrings.tr(lang, "إضافة للمفضلة", "Ajouter Favori", "Add Favorite")}",
                                isPrimary = isFavorite,
                                compact = isPhoneLandscape,
                                modifier = Modifier
                                    .focusRequester(bottomFavPillFocusRequester)
                                    .onFocusChanged { if (it.isFocused) lastControlFocusRequester = bottomFavPillFocusRequester },
                                upTarget = pillUpTarget,
                                rightTarget = bottomSettingsPillFocusRequester,
                                onClick = { onToggleFavorite(channel) }
                            )
                        }
                    }
                }
            }
        }

        if (showQuickDrawer) {
            val isSeriesDrawer = channel.contentType == StreamContentType.SERIES
            val activeBouquetIdx = remember(allBouquets, drawerSelectedBouquet) {
                allBouquets.indexOfFirst { it.first == drawerSelectedBouquet }.coerceAtLeast(0)
            }
            val rawActiveChannelIdx = remember(drawerChannels, channel.stableKey, channel.url) {
                drawerChannels.indexOfFirst {
                    it.stableKey == channel.stableKey ||
                        (isSeriesDrawer && it.url.trim() == channel.url.trim())
                }
            }
            val targetChannelIdx = remember(rawActiveChannelIdx) {
                if (rawActiveChannelIdx >= 0) rawActiveChannelIdx else 0
            }

            val bouquetListState = rememberLazyListState(
                initialFirstVisibleItemIndex = (activeBouquetIdx - 2).coerceAtLeast(0)
            )
            val channelListState = rememberLazyListState(
                initialFirstVisibleItemIndex = (targetChannelIdx - 2).coerceAtLeast(0)
            )

            var drawerVisibleLimit by remember(drawerSelectedBouquet, drawerChannels.size) {
                mutableStateOf((targetChannelIdx + 120).coerceAtLeast(140).coerceAtMost(drawerChannels.size.coerceAtLeast(1)))
            }
            val windowedDrawerChannels = remember(drawerChannels, drawerVisibleLimit) {
                if (drawerChannels.size <= drawerVisibleLimit) drawerChannels
                else drawerChannels.subList(0, drawerVisibleLimit)
            }
            val favKeySet = remember(favorites) { favorites.map { it.stableKey }.toHashSet() }

            val drawerBouquetFocusMap = remember { mutableMapOf<Int, FocusRequester>() }
            val drawerChannelFocusMap = remember { mutableMapOf<Int, FocusRequester>() }
            val drawerQualityBtnFocus = remember { FocusRequester() }
            val drawerResizeBtnFocus = remember { FocusRequester() }
            val drawerFavBtnFocus = remember { FocusRequester() }
            val drawerCloseBtnFocus = remember { FocusRequester() }
            val drawerScope = rememberCoroutineScope()

            fun getDrawerBouquetReq(idx: Int): FocusRequester = drawerBouquetFocusMap.getOrPut(idx) { FocusRequester() }
            fun getDrawerChannelReq(idx: Int): FocusRequester = drawerChannelFocusMap.getOrPut(idx) { FocusRequester() }

            fun focusDrawerBouquetStep(targetIdx: Int) {
                if (allBouquets.isEmpty() || targetIdx !in allBouquets.indices) return
                drawerSelectedBouquet = allBouquets[targetIdx].first
                drawerScope.launch {
                    val isVis = bouquetListState.layoutInfo.visibleItemsInfo.any { it.index == targetIdx }
                    if (!isVis) {
                        runCatching { bouquetListState.scrollToItem((targetIdx - 1).coerceAtLeast(0)) }
                    }
                    for (attempt in 1..5) {
                        if (runCatching { getDrawerBouquetReq(targetIdx).requestFocus() }.isSuccess) break
                        delay(20L)
                    }
                }
            }

            fun focusDrawerChannelStep(targetIdx: Int) {
                if (windowedDrawerChannels.isEmpty() || targetIdx !in windowedDrawerChannels.indices) return
                if (targetIdx + 25 >= drawerVisibleLimit && drawerVisibleLimit < drawerChannels.size) {
                    drawerVisibleLimit = (drawerVisibleLimit + 140).coerceAtMost(drawerChannels.size)
                }
                drawerScope.launch {
                    val isVis = channelListState.layoutInfo.visibleItemsInfo.any { it.index == targetIdx }
                    if (!isVis) {
                        runCatching { channelListState.scrollToItem((targetIdx - 1).coerceAtLeast(0)) }
                    }
                    for (attempt in 1..6) {
                        if (runCatching { getDrawerChannelReq(targetIdx).requestFocus() }.isSuccess) break
                        delay(20L)
                    }
                }
            }

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

            LaunchedEffect(showQuickDrawer, focusFirstChannelTrigger, channel.stableKey, drawerSelectedBouquet) {
                if (!showQuickDrawer) return@LaunchedEffect
                if (targetChannelIdx + 40 > drawerVisibleLimit) {
                    drawerVisibleLimit = (targetChannelIdx + 160).coerceAtMost(drawerChannels.size.coerceAtLeast(1))
                }
                runCatching {
                    bouquetListState.scrollToItem((activeBouquetIdx - 1).coerceAtLeast(0))
                }
                runCatching {
                    channelListState.scrollToItem((targetChannelIdx - 2).coerceAtLeast(0))
                }
                for (attempt in 1..8) {
                    delay(if (attempt <= 2) 16L else 40L)
                    val focusedOk = if (windowedDrawerChannels.isNotEmpty() && targetChannelIdx < windowedDrawerChannels.size) {
                        runCatching { getDrawerChannelReq(targetChannelIdx).requestFocus() }.isSuccess
                    } else {
                        runCatching { getDrawerBouquetReq(activeBouquetIdx).requestFocus() }.isSuccess
                    }
                    if (focusedOk) break
                    if (attempt == 3 || attempt == 6) {
                        runCatching {
                            channelListState.scrollToItem((targetChannelIdx - 1).coerceAtLeast(0))
                        }
                    }
                }
            }

            LaunchedEffect(focusBouquetTrigger) {
                if (focusBouquetTrigger > 0 && allBouquets.isNotEmpty()) {
                    focusDrawerBouquetStep(activeBouquetIdx)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth(if (isPhoneLandscape) 0.62f else 0.48f)
                    .widthIn(min = if (isPhoneLandscape) 280.dp else 360.dp, max = if (isPhoneLandscape) 460.dp else 540.dp)
                    .fillMaxHeight(if (isPhoneLandscape) 0.88f else 0.84f)
                    .align(Alignment.CenterStart)
                    .padding(start = if (isPhoneLandscape) 6.dp else 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xF00A0A0E))
                    .border(1.8.dp, BeinHighlightPurple, RoundedCornerShape(14.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {}
                    .padding(if (isPhoneLandscape) 5.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                val firstDrawerListReq = if (windowedDrawerChannels.isNotEmpty()) {
                    getDrawerChannelReq(targetChannelIdx.coerceIn(0, windowedDrawerChannels.lastIndex))
                } else {
                    getDrawerBouquetReq(activeBouquetIdx)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF14141C))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isSeriesDrawer) "📺 الأجزاء والحلقات — ${channel.name}" else "📺 ${channel.name}",
                        color = EmeraldLive,
                        fontSize = if (isPhoneLandscape) 10.sp else 11.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FocusableActionPill(
                            text = "🎬 الجودة",
                            isPrimary = false,
                            compact = true,
                            dense = true,
                            modifier = Modifier.focusRequester(drawerQualityBtnFocus),
                            downTarget = firstDrawerListReq,
                            leftTarget = drawerResizeBtnFocus,
                            onClick = {
                                showQuickDrawer = false
                                tracksModalInitialTab = 0
                                showTracksDialog = true
                            }
                        )
                        FocusableActionPill(
                            text = "📐",
                            isPrimary = false,
                            compact = true,
                            dense = true,
                            modifier = Modifier.focusRequester(drawerResizeBtnFocus),
                            downTarget = firstDrawerListReq,
                            rightTarget = drawerQualityBtnFocus,
                            leftTarget = drawerFavBtnFocus,
                            onClick = {
                                val modes = VideoResizeMode.values()
                                resizeMode = modes[(resizeMode.ordinal + 1) % modes.size]
                            }
                        )
                        FocusableActionPill(
                            text = if (isFavorite) "❤️" else "🤍",
                            isPrimary = isFavorite,
                            compact = true,
                            dense = true,
                            modifier = Modifier.focusRequester(drawerFavBtnFocus),
                            downTarget = firstDrawerListReq,
                            rightTarget = drawerResizeBtnFocus,
                            leftTarget = drawerCloseBtnFocus,
                            onClick = { onToggleFavorite(channel) }
                        )
                        FocusableActionPill(
                            text = "✕",
                            isPrimary = false,
                            compact = true,
                            dense = true,
                            modifier = Modifier.focusRequester(drawerCloseBtnFocus),
                            downTarget = firstDrawerListReq,
                            rightTarget = drawerFavBtnFocus,
                            onClick = { showQuickDrawer = false }
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // RIGHT COLUMN (in RTL): Seasons / الأجزاء (for Series) OR Bouquets / الباقات (for Live TV)
                    Column(
                        modifier = Modifier
                            .weight(0.38f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF101015))
                            .padding(5.dp)
                    ) {
                        Text(
                            text = if (isSeriesDrawer) {
                                "📂 الأجزاء (${allBouquets.size})"
                            } else {
                                "📂 ${AppStrings.tr(lang, "الباقات", "Bouquets", "Bouquets")} (${allBouquets.size})"
                            },
                            color = Color.White,
                            fontSize = if (isPhoneLandscape) 10.sp else 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp)
                        )
                        LazyColumn(
                            state = bouquetListState,
                            verticalArrangement = Arrangement.spacedBy(3.5.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(allBouquets, key = { idx, pair -> "${idx}_${pair.first}" }) { idx, (bName, bList) ->
                                val selected = drawerSelectedBouquet == bName
                                var bFocused by remember { mutableStateOf(false) }
                                val bReq = getDrawerBouquetReq(idx)
                                Row(
                                    modifier = Modifier
                                        .focusRequester(bReq)
                                        .fillMaxWidth()
                                        .scale(if (bFocused) 1.02f else 1.0f)
                                        .clip(RoundedCornerShape(7.dp))
                                        .background(
                                            when {
                                                bFocused -> Color.White
                                                selected -> BeinPrimaryPurple
                                                else -> Color(0xFF16161D)
                                            }
                                        )
                                        .border(
                                            width = if (bFocused) 2.5.dp else if (selected) 1.4.dp else 0.dp,
                                            color = if (bFocused) BeinFocusGlow else if (selected) EmeraldLive else Color.Transparent,
                                            shape = RoundedCornerShape(7.dp)
                                        )
                                        .onFocusChanged {
                                            bFocused = it.isFocused
                                            if (it.isFocused && isSeriesDrawer && drawerSelectedBouquet != bName) {
                                                drawerSelectedBouquet = bName
                                            }
                                        }
                                        .onKeyEvent { ev ->
                                            if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                                when (ev.nativeKeyEvent.keyCode) {
                                                    KeyEvent.KEYCODE_BACK,
                                                    KeyEvent.KEYCODE_ESCAPE -> {
                                                        if (ev.nativeKeyEvent.repeatCount == 0) {
                                                            showQuickDrawer = false
                                                        }
                                                        true
                                                    }
                                                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                        if (idx + 1 < allBouquets.size) {
                                                            focusDrawerBouquetStep(idx + 1)
                                                        }
                                                        true
                                                    }
                                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                                        if (idx > 0) {
                                                            focusDrawerBouquetStep(idx - 1)
                                                        } else {
                                                            runCatching { drawerQualityBtnFocus.requestFocus() }
                                                        }
                                                        true
                                                    }
                                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                                    KeyEvent.KEYCODE_ENTER,
                                                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                        if (ev.nativeKeyEvent.repeatCount == 0 &&
                                                            (android.os.SystemClock.elapsedRealtime() - drawerOpenTimestampMs) > 160L
                                                        ) {
                                                            drawerSelectedBouquet = bName
                                                            focusFirstChannelTrigger += 1
                                                        }
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
                                        .padding(horizontal = 7.dp, vertical = if (isPhoneLandscape) 5.dp else 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = bName,
                                        color = if (bFocused) Color.Black else Color.White,
                                        fontSize = if (isPhoneLandscape) 9.5.sp else 11.sp,
                                        fontWeight = if (bFocused || selected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "${bList.size}",
                                        color = if (bFocused) BeinPrimaryPurple else if (selected) EmeraldLive else Color(0xFFE2E8F0),
                                        fontSize = if (isPhoneLandscape) 8.5.sp else 9.5.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }

                    // LEFT COLUMN (in RTL): Episodes / الحلقات (with الحالية / التالية / تمت المشاهدة) OR Channels / القنوات
                    Column(
                        modifier = Modifier
                            .weight(0.62f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF101015))
                            .padding(5.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 3.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isSeriesDrawer) "🎬 حلقات $drawerSelectedBouquet (${drawerChannels.size})" else "$drawerSelectedBouquet (${drawerChannels.size})",
                                color = Color.White,
                                fontSize = if (isPhoneLandscape) 10.sp else 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "OK للتشغيل",
                                color = Color(0xFF94A3B8),
                                fontSize = if (isPhoneLandscape) 8.5.sp else 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (windowedDrawerChannels.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = if (isSeriesDrawer) "لا توجد حلقات في هذا الجزء حالياً" else AppStrings.tr(
                                        lang,
                                        "لا توجد قنوات في هذه الباقة حالياً",
                                        "Aucune chaîne dans ce bouquet",
                                        "No channels in this bouquet"
                                    ),
                                    color = Color.LightGray,
                                    fontSize = 11.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                state = channelListState,
                                verticalArrangement = Arrangement.spacedBy(3.5.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(windowedDrawerChannels, key = { idx, it -> "${idx}_${it.stableKey}" }) { idx, item ->
                                    val active = item.stableKey == channel.stableKey ||
                                        (isSeriesDrawer && item.url.trim() == channel.url.trim())
                                    val isNextItemInDrawer = isSeriesDrawer &&
                                        rawActiveChannelIdx >= 0 &&
                                        idx == rawActiveChannelIdx + 1 &&
                                        drawerSelectedBouquet == channel.group
                                    val itemProg = if (isSeriesDrawer) {
                                        vodWatchProgressMap[item.stableKey]
                                            ?: vodWatchProgressMap[item.url.trim()]
                                            ?: vodWatchProgressMap.values.firstOrNull { it.resolvedKey == item.url.trim() }
                                    } else null
                                    val isWatchedEpisode = isSeriesDrawer && (
                                        (itemProg != null && itemProg.isCompleted) ||
                                            (rawActiveChannelIdx > idx && drawerSelectedBouquet == channel.group && itemProg != null && itemProg.positionMs >= 5_000L)
                                        )
                                    val isFavItem = favKeySet.contains(item.stableKey)
                                    var rowFocused by remember { mutableStateOf(false) }
                                    val chReq = getDrawerChannelReq(idx)

                                    val epStatusLabel = when {
                                        active -> "▶ الحالية"
                                        isNextItemInDrawer -> "⏭ التالية"
                                        isWatchedEpisode -> "✅ تمت المشاهدة"
                                        isSeriesDrawer && itemProg != null && itemProg.positionMs >= 5_000L -> "⏯ ${(itemProg.progressFraction * 100).toInt()}%"
                                        else -> ""
                                    }
                                    val epStatusColor = when {
                                        active -> EmeraldLive
                                        isNextItemInDrawer -> Color(0xFFF59E0B)
                                        isWatchedEpisode -> Color(0xFF10B981)
                                        else -> BeinHighlightPurple
                                    }

                                    Row(
                                        modifier = Modifier
                                            .focusRequester(chReq)
                                            .fillMaxWidth()
                                            .scale(if (rowFocused) 1.02f else 1.0f)
                                            .clip(RoundedCornerShape(7.dp))
                                            .background(
                                                when {
                                                    rowFocused -> Color.White
                                                    active -> Color(0xFF0E2920)
                                                    isNextItemInDrawer -> Color(0xFF1C1532)
                                                    else -> Color(0xFF16161D)
                                                }
                                            )
                                            .border(
                                                width = if (rowFocused) 2.5.dp else if (active || isNextItemInDrawer) 1.5.dp else 0.dp,
                                                color = when {
                                                    rowFocused -> BeinFocusGlow
                                                    active -> EmeraldLive
                                                    isNextItemInDrawer -> Color(0xFFF59E0B)
                                                    else -> Color.Transparent
                                                },
                                                shape = RoundedCornerShape(7.dp)
                                            )
                                            .onFocusChanged { rowFocused = it.isFocused }
                                            .onKeyEvent { ev ->
                                                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                                                    when (ev.nativeKeyEvent.keyCode) {
                                                        KeyEvent.KEYCODE_BACK,
                                                        KeyEvent.KEYCODE_ESCAPE -> {
                                                            if (ev.nativeKeyEvent.repeatCount == 0) {
                                                                showQuickDrawer = false
                                                            }
                                                            true
                                                        }
                                                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                            if (idx + 1 < windowedDrawerChannels.size) {
                                                                focusDrawerChannelStep(idx + 1)
                                                            }
                                                            true
                                                        }
                                                        KeyEvent.KEYCODE_DPAD_UP -> {
                                                            if (idx > 0) {
                                                                focusDrawerChannelStep(idx - 1)
                                                            } else {
                                                                runCatching { drawerQualityBtnFocus.requestFocus() }
                                                            }
                                                            true
                                                        }
                                                        KeyEvent.KEYCODE_DPAD_CENTER,
                                                        KeyEvent.KEYCODE_ENTER,
                                                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                                            if (ev.nativeKeyEvent.repeatCount == 0 &&
                                                                (android.os.SystemClock.elapsedRealtime() - drawerOpenTimestampMs) > 180L
                                                            ) {
                                                                showControls = false
                                                                showQuickDrawer = false
                                                                if (!active) {
                                                                    onSelectChannel(item)
                                                                }
                                                            }
                                                            true
                                                        }
                                                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                            if (ev.nativeKeyEvent.repeatCount == 0) {
                                                                focusBouquetTrigger += 1
                                                            }
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
                                                if (!active) {
                                                    onSelectChannel(item)
                                                }
                                            }
                                            .padding(horizontal = 6.dp, vertical = if (isPhoneLandscape) 4.5.dp else 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .widthIn(min = 22.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    when {
                                                        rowFocused -> Color(0xFF14141D)
                                                        active -> EmeraldLive
                                                        else -> Color(0xFF101016)
                                                    }
                                                )
                                                .padding(horizontal = 4.dp, vertical = 1.5.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${idx + 1}",
                                                color = if (active && !rowFocused) Color.Black else Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                        Spacer(Modifier.width(5.dp))
                                        if (!isSeriesDrawer) {
                                            ChannelAvatar(
                                                name = item.name,
                                                logoUrl = item.logo,
                                                modifier = Modifier.size(if (isPhoneLandscape) 22.dp else 26.dp),
                                                cornerRadius = 5.dp,
                                                innerPadding = 1.dp
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            text = if (isSeriesDrawer) {
                                                item.name.substringAfter(" - ").ifBlank { item.name }
                                            } else {
                                                item.name
                                            },
                                            color = if (rowFocused) Color.Black else Color.White,
                                            fontSize = if (isPhoneLandscape) 10.sp else 11.5.sp,
                                            fontWeight = if (active || rowFocused) FontWeight.ExtraBold else FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (epStatusLabel.isNotBlank()) {
                                            Spacer(Modifier.width(4.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(5.dp))
                                                    .background(if (rowFocused) Color(0xFF0F172A) else epStatusColor.copy(alpha = 0.22f))
                                                    .border(1.dp, if (rowFocused) Color.Black else epStatusColor, RoundedCornerShape(5.dp))
                                                    .padding(horizontal = 5.dp, vertical = 1.5.dp)
                                            ) {
                                                Text(
                                                    text = epStatusLabel,
                                                    color = if (rowFocused) Color.White else epStatusColor,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                            }
                                        }
                                        if (isFavItem) {
                                            Spacer(Modifier.width(3.dp))
                                            Text(
                                                text = "★",
                                                color = if (rowFocused) BeinPrimaryPurple else Color(0xFFFF4D6D),
                                                fontSize = 11.sp,
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
                initialSection = tracksModalInitialTab,
                preferSubtitlesTab = tracksModalInitialTab == 2,
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

        // لا يتم إظهار أي نافذة منبثقة تحجب المشاهدة أو توقف المحاولات؛ يستمر المشغل بالمحاولة تلقائياً في الخلفية
    }
}

@Composable
private fun FocusableCircleButton(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color = Color.White,
    sizeDp: androidx.compose.ui.unit.Dp = 44.dp,
    modifier: Modifier = Modifier,
    upTarget: FocusRequester? = null,
    downTarget: FocusRequester? = null,
    leftTarget: FocusRequester? = null,
    rightTarget: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    IconButton(
        onClick = { SafeClickGuard.runThrottled("PLAYER_CIRCLE_BTN", 200L) { onClick() } },
        modifier = modifier
            .size(sizeDp)
            .scale(if (focused) 1.14f else 1.0f)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && ev.nativeKeyEvent.repeatCount == 0) {
                    when (ev.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            SafeClickGuard.runThrottled("PLAYER_CIRCLE_BTN", 200L) { onClick() }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> if (upTarget != null) { runCatching { upTarget.requestFocus() }; true } else false
                        KeyEvent.KEYCODE_DPAD_DOWN -> if (downTarget != null) { runCatching { downTarget.requestFocus() }; true } else false
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (leftTarget != null) { runCatching { leftTarget.requestFocus() }; true } else false
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (rightTarget != null) { runCatching { rightTarget.requestFocus() }; true } else false
                        else -> false
                    }
                } else false
            }
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
    dense: Boolean = false,
    modifier: Modifier = Modifier,
    upTarget: FocusRequester? = null,
    downTarget: FocusRequester? = null,
    leftTarget: FocusRequester? = null,
    rightTarget: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .scale(if (focused) 1.06f else 1.0f)
            .clip(RoundedCornerShape(if (dense) 7.dp else 10.dp))
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && ev.nativeKeyEvent.repeatCount == 0) {
                    when (ev.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            SafeClickGuard.runThrottled("PLAYER_UI_CLICK", 200L) { onClick() }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> if (upTarget != null) { runCatching { upTarget.requestFocus() }; true } else false
                        KeyEvent.KEYCODE_DPAD_DOWN -> if (downTarget != null) { runCatching { downTarget.requestFocus() }; true } else false
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (leftTarget != null) { runCatching { leftTarget.requestFocus() }; true } else false
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (rightTarget != null) { runCatching { rightTarget.requestFocus() }; true } else false
                        else -> false
                    }
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
                width = if (focused) 2.2.dp else 1.dp,
                color = if (focused) BeinFocusGlow else BeinHighlightPurple.copy(alpha = 0.75f),
                shape = RoundedCornerShape(if (dense) 7.dp else 10.dp)
            )
            .clickable { onClick() }
            .padding(
                horizontal = when { dense -> 7.dp; compact -> 11.dp; else -> 14.dp },
                vertical = when { dense -> 3.5.dp; compact -> 6.dp; else -> 8.dp }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (focused) Color.Black else Color.White,
            fontSize = when { dense -> 10.sp; compact -> 11.sp; else -> 13.sp },
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
    initialSection: Int = 0,
    manualLockedQuality: StreamQualityRank? = null,
    onSelectAutoMq: () -> Unit = {},
    onSelectMqVariant: (MultiQualityEngine.QualityVariant) -> Unit = {},
    currentSpeed: PlaybackSpeedOption,
    onSelectSpeed: (PlaybackSpeedOption) -> Unit,
    compact: Boolean,
    preferSubtitlesTab: Boolean = false,
    onDismiss: () -> Unit
) {
    var selectedSection by remember(initialSection, preferSubtitlesTab) {
        mutableStateOf(if (preferSubtitlesTab) 2 else initialSection.coerceIn(0, 3))
    }
    val tab0Focus = remember { FocusRequester() }
    val tab1Focus = remember { FocusRequester() }
    val tab2Focus = remember { FocusRequester() }
    val tab3Focus = remember { FocusRequester() }
    val firstItemFocus = when (selectedSection) {
        1 -> tab1Focus
        2 -> tab2Focus
        3 -> tab3Focus
        else -> tab0Focus
    }

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
                            .focusRequester(tab0Focus),
                        onClick = { selectedSection = 0 }
                    )
                    InteractiveModalTabButton(
                        title = "🎙 صوت (${audioOptions.size.coerceAtLeast(1)})",
                        selected = selectedSection == 1,
                        compact = compact,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(tab1Focus),
                        onClick = { selectedSection = 1 }
                    )
                    InteractiveModalTabButton(
                        title = "📝 ترجمة (${(subtitleOptions.size - 1).coerceAtLeast(0)})",
                        selected = selectedSection == 2,
                        compact = compact,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(tab2Focus),
                        onClick = { selectedSection = 2 }
                    )
                    InteractiveModalTabButton(
                        title = "⚡ سرعة",
                        selected = selectedSection == 3,
                        compact = compact,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(tab3Focus),
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
 * - Runs ExoPlayer with instant 0ms failover & 900ms frozen-frame handover to ExoFfmpegUniversalEngine.
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
    val vlcAvailable = true
    val forceMiniSoftwareCodec = remember {
        java.util.concurrent.atomic.AtomicBoolean(preferences.preferSoftwareDecoder)
    }
    val initialNeedsSw = remember(channel.stableKey, channel.url) {
        StreamFormatDetectorAndRenewer.isSoftwareVideoPreferredCodec(
            url = channel.url,
            videoCodecHint = channel.videoCodecHint,
            audioCodecHint = channel.audioCodecHint,
            containerExtension = channel.containerExtension,
            name = channel.name
        )
    }
    var codecRequiresVlc by remember(channel.stableKey) {
        mutableStateOf(false)
    }
    var forceVlcSoftwareVideo by remember(channel.stableKey) { mutableStateOf(initialNeedsSw) }
    LaunchedEffect(channel.stableKey, forceVlcSoftwareVideo, preferences.preferSoftwareDecoder) {
        forceMiniSoftwareCodec.set(forceVlcSoftwareVideo || preferences.preferSoftwareDecoder)
    }
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
    var isMiniSourceStopped by remember(channel.stableKey) { mutableStateOf(false) }
    var candidateIndex by remember(channel.stableKey) { mutableStateOf(0) }
    var miniFormatAttemptMode by remember(channel.stableKey, candidateIndex) { mutableStateOf(0) }
    var retryCount by remember(channel.stableKey) { mutableStateOf(0) }
    var activeMiniSessionKey by remember { mutableStateOf("") }
    var activeMiniStreamUrl by remember { mutableStateOf("") }

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
    val miniUniversalDataSourceFactory = remember {
        ExoFfmpegUniversalEngine.buildUniversalDataSourceFactory(context, httpFactory)
    }
    val extractorsFactory = remember {
        ExoFfmpegUniversalEngine.buildUniversalExtractorsFactory(isLiveStream = true)
    }
    val failFastPolicy = remember {
        object : DefaultLoadErrorHandlingPolicy(2) {
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 2
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                if (loadErrorInfo.errorCount <= 2) 100L else C.TIME_UNSET
        }
    }
    val defaultMediaSourceFactory = remember {
        DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(miniUniversalDataSourceFactory)
            .setLoadErrorHandlingPolicy(failFastPolicy)
    }
    val fastHlsMediaSourceFactory = remember {
        HlsMediaSource.Factory(miniUniversalDataSourceFactory)
            .setAllowChunklessPreparation(true)
            .setLoadErrorHandlingPolicy(failFastPolicy)
    }

    val player = remember {
        val bp = preferences.bufferProfile
        // Mini preview: prioritize first frame speed over deep buffer (zapping feels instant)
        val minBufMs = bp.minBufferMs.coerceIn(1_200, 4_500)
        val maxBufMs = bp.maxBufferMs.coerceIn(12_000, 22_000).coerceAtLeast(minBufMs + 3_000)
        val startPlaybackBufMs = bp.playbackBufferMs.coerceIn(80, 350)
        val afterRebufferBufMs = bp.rebufferMs.coerceIn(400, 1_100)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBufMs, maxBufMs, startPlaybackBufMs, afterRebufferBufMs)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(1_500, true)
            .setAllocator(DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE))
            .build()

        val renderersFactory = ExoFfmpegUniversalEngine.buildAllCodecRenderersFactory(
            context = context,
            forceSoftwareVideoProvider = { forceMiniSoftwareCodec.get() },
            preferFfmpegExtension = true
        )
        val trackSelector = ExoFfmpegUniversalEngine.buildAllCodecTrackSelector(
            context = context,
            capHeightForWeakDevice = preferences.lowEndDeviceMode
        )
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

    DisposableEffect(player, channel.stableKey) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    isBuffering = false
                    showMiniSpinner = false
                    isMiniSourceStopped = false
                    FastFormatPreferenceCache.recordWorkingLiveUrl(channel.playlistId, effectiveUrl)
                } else if (playbackState == Player.STATE_ENDED) {
                    isBuffering = false
                    showMiniSpinner = false
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
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
                if (hasVideoTrack && !anyVideoSelected && firstVideoGroup != null) {
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
                if (videoUnsupported && !forceMiniSoftwareCodec.get()) {
                    // Escalate to FFmpeg/Software video decoder and restart playback (NEVER leave player stopped!)
                    forceMiniSoftwareCodec.set(true)
                    forceVlcSoftwareVideo = true
                    codecRequiresVlc = true
                    retryCount += 1
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.play()
                    return
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    ChannelRepository.clearHostSessionCookies(effectiveUrl)
                    ChannelRepository.clearStreamRedirectCache(effectiveUrl)
                }
                val isContainerMismatch =
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
                if (isContainerMismatch && miniFormatAttemptMode == 0) {
                    // Instant container swap (.ts <-> .m3u8 HLS) just like full-screen PlayerScreen!
                    miniFormatAttemptMode = if (effectiveUrl.contains(".m3u8", ignoreCase = true)) 2 else 1
                    return
                }
                val isCodecError =
                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                if (isCodecError && !forceMiniSoftwareCodec.get()) {
                    forceMiniSoftwareCodec.set(true)
                    forceVlcSoftwareVideo = true
                    codecRequiresVlc = true
                    retryCount += 1
                    return
                }
                if (isMultiQualityMini && miniMqVariants.size >= 2) {
                    val decision = MultiQualityEngine.reportAdaptiveEvent(
                        multiStableKey = channel.stableKey,
                        currentVariant = activeMiniVariant,
                        reason = AdaptationReason.SOURCE_OUTAGE
                    )
                    if (decision != null && decision.switched) {
                        activeMiniVariant = decision.variant
                        candidateIndex = 0
                        miniFormatAttemptMode = 0
                        miniMqGeneration += 1
                        return
                    }
                }
                if (retryCount >= 2) {
                    isMiniSourceStopped = true
                }
                if (formatCandidateUrls.size > 1 && candidateIndex < formatCandidateUrls.size - 1) {
                    candidateIndex += 1
                    miniFormatAttemptMode = 0
                } else {
                    forceMiniSoftwareCodec.set(true)
                    forceVlcSoftwareVideo = true
                    retryCount += 1
                    candidateIndex = (candidateIndex + 1) % formatCandidateUrls.size.coerceAtLeast(1)
                    miniFormatAttemptMode = 0
                }
            }
        }
        player.addListener(listener)
        onDispose {
            runCatching {
                player.removeListener(listener)
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            runCatching {
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
                player.release()
                ExoFfmpegUniversalEngine.closeAllActiveStreamDataSources()
                ChannelRepository.terminatePreviousStreamSessionAndFlushCache(activeMiniStreamUrl)
                StreamFormatDetectorAndRenewer.clearChannelCache(activeMiniStreamUrl)
            }
        }
    }

    LaunchedEffect(
        player,
        channel.stableKey,
        activeMiniChannel.url,
        effectiveUrl,
        miniFormatAttemptMode,
        miniMqGeneration,
        codecRequiresVlc,
        forceVlcSoftwareVideo,
        retryCount
    ) {
        val isMiniSwitch = activeMiniSessionKey.isNotEmpty() && activeMiniSessionKey != channel.stableKey
        val prevMiniUrl = activeMiniStreamUrl
        activeMiniSessionKey = channel.stableKey
        activeMiniStreamUrl = effectiveUrl
        if (isMiniSwitch) {
            isMiniSourceStopped = false
        }
        isBuffering = true
        showMiniSpinner = false
        runCatching {
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
            ExoFfmpegUniversalEngine.closeAllActiveStreamDataSources()
            ChannelRepository.terminatePreviousStreamSessionAndFlushCache(prevMiniUrl)
            if (isMiniSwitch) {
                StreamFormatDetectorAndRenewer.clearChannelCache(prevMiniUrl)
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

        val rotatedUa = activeMiniChannel.userAgent?.takeIf { it.isNotBlank() }
            ?: channel.userAgent?.takeIf { it.isNotBlank() }
            ?: STREAM_USER_AGENTS[retryCount % STREAM_USER_AGENTS.size]
        val rawPlayUrl = effectiveUrl.trim()
        val targetPlayUrl = rawPlayUrl.substringBefore("|").trim()
        val low = targetPlayUrl.lowercase(Locale.ROOT).substringBefore("?")
        val isExplicitHlsUrl = low.endsWith(".m3u8") ||
            targetPlayUrl.contains("output=m3u8", ignoreCase = true) ||
            targetPlayUrl.contains("type=m3u8", ignoreCase = true) ||
            targetPlayUrl.contains("output=hls", ignoreCase = true)

        forceMiniSoftwareCodec.set(codecRequiresVlc || forceVlcSoftwareVideo || preferences.preferSoftwareDecoder)

        val customHeaders = IboPlayerProfileSpoofer.buildStreamHeadersMap(
            targetUrl = targetPlayUrl,
            serverBaseUrl = activeMiniChannel.streamBaseUrl.ifBlank { channel.streamBaseUrl },
            customReferer = activeMiniChannel.referer ?: channel.referer,
            customOrigin = activeMiniChannel.origin ?: channel.origin,
            customCookie = activeMiniChannel.cookie ?: channel.cookie,
            isLiveManifest = (isExplicitHlsUrl && miniFormatAttemptMode == 0) || miniFormatAttemptMode == 1
        )
        httpFactory.setDefaultRequestProperties(customHeaders)
        httpFactory.setUserAgent(rotatedUa)

        val itemBuilder = MediaItem.Builder()
            .setUri(Uri.parse(targetPlayUrl))
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(2_200L)
                    .setMinOffsetMs(800L)
                    .setMaxOffsetMs(45_000L)
                    .setMinPlaybackSpeed(1.0f)
                    .setMaxPlaybackSpeed(1.0f)
                    .build()
            )
        if ((isExplicitHlsUrl && miniFormatAttemptMode == 0) || miniFormatAttemptMode == 1) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            player.setMediaSource(fastHlsMediaSourceFactory.createMediaSource(itemBuilder.build()))
        } else {
            player.setMediaSource(defaultMediaSourceFactory.createMediaSource(itemBuilder.build()))
        }
        player.playWhenReady = true
        player.prepare()
    }

    // 4K-Aware Mini Player Watchdog with Real-Time Multi-Quality Upgrade & Instant Recovery (Never stops player onto black screen!)
    LaunchedEffect(player, channel.stableKey, activeMiniChannel.url, miniMqGeneration, candidateIndex, miniFormatAttemptMode, effectiveUrl, retryCount) {
        var lastRenderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
        var lastPosMs = player.currentPosition
        var lastBufMs = player.bufferedPosition
        var wasReadyMini = false
        var smoothReadyTicksMini = 0
        var midRebufferTicksMini = 0
        var frozenTicks = 0
        var bufferingTicks = 0
        while (true) {
            delay(200L)
            NetworkSpeedMonitor.updateExoBandwidthEstimate(miniBandwidthMeter.bitrateEstimate)
            val state = player.playbackState
            val renderedFrames = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
            val frameDelta = (renderedFrames - lastRenderedFrames).coerceAtLeast(0)
            val currentPos = player.currentPosition
            val bufferedPos = player.bufferedPosition
            val bufferAheadMs = (bufferedPos - currentPos).coerceAtLeast(0L)
            val posDelta = currentPos - lastPosMs
            val bufDelta = bufferedPos - lastBufMs
            lastRenderedFrames = renderedFrames
            lastPosMs = currentPos
            lastBufMs = bufferedPos

            if (state == Player.STATE_READY) {
                bufferingTicks = 0
                midRebufferTicksMini = 0
                if (frameDelta > 0) {
                    wasReadyMini = true
                    smoothReadyTicksMini++
                    frozenTicks = 0
                    if (isMiniSourceStopped) isMiniSourceStopped = false
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
                            miniFormatAttemptMode = 0
                            miniMqGeneration += 1
                            break
                        }
                    }
                }
                if (renderedFrames == 0 && frameDelta == 0 && posDelta > 100L) {
                    frozenTicks++
                    val fLimit = 25
                    if (frozenTicks >= fLimit) {
                        frozenTicks = 0
                        if (isMultiQualityMini && miniMqVariants.size >= 2) {
                            val dec = MultiQualityEngine.reportAdaptiveEvent(
                                multiStableKey = channel.stableKey,
                                currentVariant = activeMiniVariant,
                                reason = AdaptationReason.SOURCE_CONGESTION
                            )
                            if (dec != null && dec.switched) {
                                activeMiniVariant = dec.variant
                                candidateIndex = 0
                                miniFormatAttemptMode = 0
                                miniMqGeneration += 1
                                break
                            }
                        }
                        if (formatCandidateUrls.size > 1 && candidateIndex < formatCandidateUrls.size - 1) {
                            candidateIndex++
                            miniFormatAttemptMode = 0
                            break
                        }
                    }
                }
            } else if (state == Player.STATE_BUFFERING) {
                frozenTicks = 0
                smoothReadyTicksMini = 0
                if (wasReadyMini && isMultiQualityMini && miniMqVariants.size >= 2) {
                    midRebufferTicksMini++
                    if (midRebufferTicksMini >= 3) {
                        val dec = MultiQualityEngine.reportAdaptiveEvent(
                            multiStableKey = channel.stableKey,
                            currentVariant = activeMiniVariant,
                            reason = AdaptationReason.INTERNET_FLUCTUATION
                        )
                        if (dec != null && dec.switched) {
                            activeMiniVariant = dec.variant
                            candidateIndex = 0
                            miniFormatAttemptMode = 0
                            midRebufferTicksMini = 0
                            miniMqGeneration += 1
                            break
                        }
                    }
                }
                if (renderedFrames == 0 && posDelta <= 0L && bufDelta <= 0L) {
                    bufferingTicks++
                    // Give live channel ~4.8s (24 * 200ms) to connect, follow HTTP 302 redirects & deliver first GOP
                    val stallLimit = 24
                    if (bufferingTicks >= stallLimit) {
                        bufferingTicks = 0
                        if (isMultiQualityMini && miniMqVariants.size >= 2) {
                            val decision = MultiQualityEngine.reportAdaptiveEvent(
                                multiStableKey = channel.stableKey,
                                currentVariant = activeMiniVariant,
                                reason = AdaptationReason.SOURCE_CONGESTION
                            )
                            if (decision != null && decision.switched) {
                                activeMiniVariant = decision.variant
                                candidateIndex = 0
                                miniFormatAttemptMode = 0
                                miniMqGeneration += 1
                                break
                            }
                        }
                        if (formatCandidateUrls.size > 1 && candidateIndex < formatCandidateUrls.size - 1) {
                            candidateIndex++
                            miniFormatAttemptMode = 0
                            break
                        } else if (miniFormatAttemptMode == 0) {
                            miniFormatAttemptMode = if (effectiveUrl.contains(".m3u8", ignoreCase = true)) 2 else 1
                            break
                        } else {
                            if (retryCount >= 2) {
                                isMiniSourceStopped = true
                            }
                            forceMiniSoftwareCodec.set(true)
                            forceVlcSoftwareVideo = true
                            codecRequiresVlc = true
                            retryCount++
                            break
                        }
                    }
                } else {
                    bufferingTicks = 0
                }
            } else if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                bufferingTicks++
                if (bufferingTicks >= 7) {
                    bufferingTicks = 0
                    if (retryCount >= 2) {
                        isMiniSourceStopped = true
                    }
                    candidateIndex = (candidateIndex + 1) % formatCandidateUrls.size.coerceAtLeast(1)
                    miniFormatAttemptMode = 0
                    retryCount++
                    break
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
        if (true) {
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
                        // Ensure embedded SurfaceView renders above the Compose dark card background (never occluded to black!)
                        (this.videoSurfaceView as? android.view.SurfaceView)?.setZOrderMediaOverlay(true)
                    }
                },
                update = { view ->
                    if (view.player !== player) {
                        view.player = player
                    }
                    (view.videoSurfaceView as? android.view.SurfaceView)?.setZOrderMediaOverlay(true)
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
        if (isMiniSourceStopped) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xD90A0A10), RoundedCornerShape(14.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        color = EmeraldLive,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(if (compact) 22.dp else 26.dp)
                    )
                    Text(
                        text = "متوقف من المصدر",
                        color = Color.White,
                        fontSize = if (compact) 12.sp else 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (showMiniSpinner && isBuffering) {
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
