package com.tvflux.pro

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.VpnService
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// ============================================================================
// Global Platform Ultra-Dark Eye-Comfort Royal Palette (v12.0 - Max Eye Comfort)
// ============================================================================
val BeinPrimaryPurple = Color(0xFF24242E)     // LIVE PRO Dark Slate Accent
val BeinHighlightPurple = Color(0xFF444454)   // Silver-Graphite Highlight
val BeinFocusGlow = Color(0xFFFFFFFF)         // Crisp Pure White Remote Focus Ring
val BeinDarkBg = Color(0xFF08080A)            // Pitch Obsidian Background
val BeinSurface = Color(0xFF101014)           // Deep Charcoal Surface
val BeinCard = Color(0xFF17171D)              // Matte Dark Card Surface
val BeinBorder = Color(0xFF282832)            // Subtle Gunmetal Hairline Border
val EmeraldLive = Color(0xFF10B981)           // Active Status Accent
val CrimsonAlert = Color(0xFFE50914)          // Alert / error accent only

@androidx.compose.runtime.Immutable
private data class CatalogViewSnapshot(
    val allChannels: List<TvChannel> = emptyList(),
    val channelsByBouquet: Map<String, List<TvChannel>> = emptyMap(),
    val rawBouquetSummaries: List<BouquetSummary> = emptyList(),
    val bouquetSummaries: List<BouquetSummary> = emptyList(),
    val playerDrawerBouquets: List<Pair<String, List<TvChannel>>> = emptyList(),
    val allMovies: List<TvChannel> = emptyList(),
    val moviesByCategory: Map<String, List<TvChannel>> = emptyMap(),
    val movieCategories: List<BouquetSummary> = emptyList(),
    val allSeries: List<TvChannel> = emptyList(),
    val seriesByCategory: Map<String, List<TvChannel>> = emptyMap(),
    val seriesCategories: List<BouquetSummary> = emptyList()
)

val Gold = BeinHighlightPurple
val ObsidianBg = BeinDarkBg
val SurfaceDark = BeinSurface
val CardDark = BeinCard

private val BeinTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.2.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 25.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp
    )
)

internal object LiveProGlobalActions {
    var vpnPausedTemporarily: Boolean by mutableStateOf(false)
    var vpnPermanentDisable: Boolean by mutableStateOf(false)
    var vpnAutoStartOnLaunch: Boolean by mutableStateOf(true)
    var isVpnActiveAndProtected: Boolean by mutableStateOf(true)
    var onOpenQuickAddAccount: () -> Unit = {}
    var onOpenVpnControl: () -> Unit = {}
    var onPauseVpnTemporarily: () -> Unit = {}
    var onDisableVpnPermanently: () -> Unit = {}
    var onDismissPlaylistSelectorAndOpenQuickAdd: () -> Unit = {}

    fun loadVpnPrefs(context: Context) {
        val sp = context.getSharedPreferences("livepro_vpn_shield_prefs", Context.MODE_PRIVATE)
        vpnPermanentDisable = sp.getBoolean("vpnPermanentDisable", false)
        vpnAutoStartOnLaunch = sp.getBoolean("vpnAutoStartOnLaunch", true)
    }

    fun updateVpnPrefs(
        context: Context,
        permanentDisable: Boolean = vpnPermanentDisable,
        autoStart: Boolean = vpnAutoStartOnLaunch
    ) {
        vpnPermanentDisable = permanentDisable
        vpnAutoStartOnLaunch = autoStart
        context.getSharedPreferences("livepro_vpn_shield_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("vpnPermanentDisable", permanentDisable)
            .putBoolean("vpnAutoStartOnLaunch", autoStart)
            .apply()
    }
}

class MainActivity : ComponentActivity() {
    companion object {
        var resumeCheckTrigger by mutableStateOf(0)
        val appScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, e ->
                    TiviMateDiagnostics.logEvent("⚠️ Scope crash: ${e.message}")
                }
        )
        val syncLock = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Solution 1.1: Safe Crash Logging & Clean Recovery (No Zombie Looper.loop())
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val logFile = java.io.File(filesDir, "crash_log.txt")
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                val entry = buildString {
                    appendLine("===== $timestamp =====")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Exception: ${throwable.javaClass.name}")
                    appendLine("Message: ${throwable.message}")
                    appendLine("Stack:")
                    appendLine(throwable.stackTraceToString())
                    appendLine()
                }
                logFile.appendText(entry)

                if (logFile.length() > 500_000L) {
                    val trimmed = logFile.readText().takeLast(400_000)
                    logFile.writeText(trimmed)
                }

                if (throwable is OutOfMemoryError) {
                    FluxBitmapCache.clear(applicationContext)
                    System.gc()
                }
            }

            if (thread.name == "main" || thread.name.contains("main", ignoreCase = true)) {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            } else {
                TiviMateDiagnostics.logEvent("⚠️ Background crash: ${throwable.message}")
            }
        }

        runCatching {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
                }
            }
            val insetsCtrl = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            insetsCtrl.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsCtrl.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }

        // Solution 1.3: Safe Compose Wrapper with CrashRecoveryScreen
        setContent {
            val crashState = remember { mutableStateOf<Throwable?>(null) }
            if (crashState.value != null) {
                CrashRecoveryScreen(
                    error = crashState.value!!,
                    onRetry = { crashState.value = null },
                    onExit = { finishAffinity() }
                )
            } else {
                CompositionLocalProvider(LocalContext provides this@MainActivity) {
                    LiveProEnterpriseApp()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCheckTrigger += 1
    }

    // Solution 1.2: Non-blocking Background WAL Checkpoint on onStop + Final Guarantee on onDestroy
    override fun onStop() {
        super.onStop()
        val appCtx = applicationContext
        Thread {
            runCatching {
                TiviMateLocalDatabase.getInstance(appCtx).checkpointWalNow()
            }
        }.apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val appCtx = applicationContext
        Thread {
            runCatching {
                TiviMateLocalDatabase.getInstance(appCtx).checkpointWalNow()
            }
        }.apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
    }
}

@Composable
fun CrashRecoveryScreen(
    error: Throwable,
    onRetry: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08080A))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("⚠️ حدث خطأ غير متوقع", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(
            error.message ?: error.javaClass.simpleName,
            color = Color(0xFFFCA5A5),
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetry) { Text("إعادة المحاولة") }
            OutlinedButton(onClick = onExit) { Text("خروج") }
        }
    }
}

@Composable
private fun LiveProEnterpriseApp() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var initialized by remember { mutableStateOf(false) }
    var deviceId by remember { mutableStateOf(StorageManager.resolveHardwareDeviceId(context)) }
    var deviceName by remember { mutableStateOf(StorageManager.resolveDeviceName()) }
    var savedActivationCode by remember { mutableStateOf("") }

    var xtreamPlaylists by remember { mutableStateOf<List<XtreamAccountConfig>>(emptyList()) }

    var vpnStatus by remember { mutableStateOf(CloudflareTunnelStatus()) }
    var isConnectingVpn by remember { mutableStateOf(false) }
    var isVpnPermissionDialogShowing by remember { mutableStateOf(false) }
    var vpnPermissionAskedInSession by remember { mutableStateOf(false) }
    var autoVpnPrompted by remember { mutableStateOf(false) }
    var liveNetworkSpeed by remember { mutableStateOf("0 KB/s") }
    var vodWatchProgressMap by remember { mutableStateOf<Map<String, VodWatchProgress>>(emptyMap()) }
    var pendingResumeChannel by remember { mutableStateOf<TvChannel?>(null) }
    var activeResumePositionMs by remember { mutableStateOf(0L) }
    var classicProShowDashboard by remember { mutableStateOf(true) }

    var portalChannels by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var portalMovies by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var portalSeries by remember { mutableStateOf<List<TvChannel>>(emptyList()) }

    var xtreamChannelsMap by remember { mutableStateOf<Map<String, List<TvChannel>>>(emptyMap()) }
    var xtreamMoviesMap by remember { mutableStateOf<Map<String, List<TvChannel>>>(emptyMap()) }
    var xtreamSeriesMap by remember { mutableStateOf<Map<String, List<TvChannel>>>(emptyMap()) }

    var favorites by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var history by remember { mutableStateOf<List<TvChannel>>(emptyList()) }
    var subscription by remember {
        mutableStateOf<SubscriptionInfo?>(
            SubscriptionInfo(active = true, isExpired = false, daysLeft = 30, reason = "ok")
        )
    }
    var preferences by remember { mutableStateOf(PlayerPreferences()) }
    var multiQualityEpoch by remember { mutableStateOf(0) }
    var diagnosticAlert by remember { mutableStateOf<DiagnosticAlertInfo?>(null) }
    var voucherAlert by remember { mutableStateOf<VoucherAlertInfo?>(null) }
    var isSortingLocalLibrary by remember { mutableStateOf(true) }
    var saveStateJob by remember { mutableStateOf<Job?>(null) }

    var activeTab by remember { mutableStateOf(MainTab.LIVE) }
    var selectedCategory by remember { mutableStateOf("الكل") }
    var selectedMovieCategory by remember { mutableStateOf("الكل") }
    var selectedSeriesCategory by remember { mutableStateOf("الكل") }
    var searchQuery by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("مكتبة SQLite المحلية جاهزة") }
    var isLoading by remember { mutableStateOf(false) }
    var isSyncingXtream by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf(SyncProgressState()) }
    var browseOfflineAllowed by remember { mutableStateOf(false) }
    val sqliteDb = remember { TiviMateLocalDatabase.getInstance(context) }
    var playingChannel by remember { mutableStateOf<TvChannel?>(null) }
    var previewLiveChannel by remember { mutableStateOf<TvChannel?>(null) }
    var selectedSeriesForEpisodes by remember { mutableStateOf<TvChannel?>(null) }

    // Track the exact last-watched item key & scroll states so returning from Player
    // keeps the D-Pad focus on the exact channel/movie/series the user was watching!
    var lastWatchedStableKey by remember { mutableStateOf<String?>(null) }
    var lastSelectedSeriesParent by remember { mutableStateOf<TvChannel?>(null) }
    var restoreFocusTrigger by remember { mutableStateOf(0) }
    val liveGridState = remember { LazyGridState() }
    val liveListState = remember { LazyListState() }
    val bouquetSidebarListState = remember { LazyListState() }
    val moviesGridState = remember { LazyGridState() }
    val moviesCategoryListState = remember { LazyListState() }
    val seriesGridState = remember { LazyGridState() }
    val seriesCategoryListState = remember { LazyListState() }

    var showExitConfirmation by remember { mutableStateOf(false) }
    var showUnifiedValidityModal by remember { mutableStateOf(false) }
    var showMultiPlaylistSelectorModal by remember { mutableStateOf(false) }
    var isFirstTimeSelectorMode by remember { mutableStateOf(false) }
    var isCheckingValidityOnly by remember { mutableStateOf(false) }
    var vpnPausedTemporarily by remember { mutableStateOf(false) }
    var showQuickAddAccountModal by remember { mutableStateOf(false) }
    var showVpnControlModal by remember { mutableStateOf(false) }

    // True ONLY when the encrypted VPN tunnel is actively running and not paused/disabled by the user.
    // Whenever this is false, PORTAL_MAIN ("القائمة الرئيسية") automatically hides everywhere to prevent stream sniffing!
    val isVpnActiveAndProtected = vpnStatus.connected && !vpnPausedTemporarily && !LiveProGlobalActions.vpnPermanentDisable
    LiveProGlobalActions.vpnPausedTemporarily = vpnPausedTemporarily
    LiveProGlobalActions.isVpnActiveAndProtected = isVpnActiveAndProtected
    LiveProGlobalActions.onOpenQuickAddAccount = { showQuickAddAccountModal = true }
    LiveProGlobalActions.onOpenVpnControl = { showVpnControlModal = true }
    LiveProGlobalActions.onDismissPlaylistSelectorAndOpenQuickAdd = {
        showMultiPlaylistSelectorModal = false
        showQuickAddAccountModal = true
    }

    val lang = preferences.appLanguage

    val orderedPlaylists = remember(xtreamPlaylists, preferences.playlistOrderIds) {
        if (preferences.playlistOrderIds.isEmpty()) {
            xtreamPlaylists
        } else {
            val map = preferences.playlistOrderIds.withIndex().associate { it.value to it.index }
            xtreamPlaylists.sortedBy { map[it.id] ?: (1000 + it.orderIndex) }
        }
    }

    val favBouquetTitle = AppStrings.favoritesLabel(lang)
    val allBouquetTitle = AppStrings.allBouquetsLabel(lang)

    // If user hid the currently selected special bouquet (متعدد جودات or ★ المفضلة), smoothly fall back to "الكل"
    LaunchedEffect(preferences.hiddenBouquets, favBouquetTitle, allBouquetTitle) {
        val mqHidden = CustomOrderHelper.isMultiQualityBouquetHidden(preferences.hiddenBouquets)
        val favHidden = CustomOrderHelper.isFavoritesBouquetHidden(preferences.hiddenBouquets)
        if ((mqHidden && selectedCategory == MultiQualityEngine.BOUQUET_NAME) ||
            (favHidden && selectedCategory == favBouquetTitle) ||
            (selectedCategory in preferences.hiddenBouquets && selectedCategory != allBouquetTitle)
        ) {
            selectedCategory = allBouquetTitle
        }
    }

    // In-memory O(1) Snapshot Cache per playlist filter & settings so switching between playlists is 0.0ms!
    val fullSnapshotCache = remember(
        portalChannels,
        portalMovies,
        portalSeries,
        isVpnActiveAndProtected,
        xtreamChannelsMap,
        xtreamMoviesMap,
        xtreamSeriesMap,
        orderedPlaylists
    ) {
        java.util.concurrent.ConcurrentHashMap<String, CatalogViewSnapshot>()
    }

    val vodSliceCache = remember(
        portalMovies,
        portalSeries,
        isVpnActiveAndProtected,
        xtreamMoviesMap,
        xtreamSeriesMap,
        orderedPlaylists
    ) {
        java.util.concurrent.ConcurrentHashMap<String, CatalogViewSnapshot>()
    }

    // Background-indexed catalog snapshot with 0ms cache hit & instant Live-First emission!
    val catalogSnapshot by produceState(
        initialValue = CatalogViewSnapshot(),
        portalChannels,
        portalMovies,
        portalSeries,
        isVpnActiveAndProtected,
        xtreamChannelsMap,
        xtreamMoviesMap,
        xtreamSeriesMap,
        orderedPlaylists,
        preferences.playlistDisplayMode,
        preferences.activePlaylistFilterId,
        preferences.customChannelOrder,
        preferences.customBouquetOrder,
        preferences.hiddenBouquets,
        lang,
        multiQualityEpoch
    ) {
        val filterId = preferences.activePlaylistFilterId
        val separateMode = preferences.playlistDisplayMode == PlaylistDisplayMode.SEPARATE_LISTS
        val vodCacheKey = "${separateMode}|$filterId"
        val fullCacheKey = "$vodCacheKey|${preferences.hiddenBouquets.hashCode()}|${preferences.customBouquetOrder.hashCode()}|${preferences.customChannelOrder.hashCode()}|${lang.name}|$multiQualityEpoch"

        // 0ms Instant Cache Hit when switching back & forth between playlists!
        fullSnapshotCache[fullCacheKey]?.let { cached ->
            value = cached
            if (initialized) isSortingLocalLibrary = false
            return@produceState
        }

        val totalRawItems = portalChannels.size + xtreamChannelsMap.values.sumOf { it.size }
        if (!initialized && totalRawItems > 500) {
            isSortingLocalLibrary = true
        }
        withContext(Dispatchers.Default) {
            val disabledIds = orderedPlaylists.filter { !it.enabled }.map { it.id }.toSet()
            val primaryEnabledIds = orderedPlaylists.filter { it.enabled }.map { it.id }
            val extraCachedIds = (xtreamChannelsMap.keys + xtreamMoviesMap.keys + xtreamSeriesMap.keys)
                .filter { it !in primaryEnabledIds && it !in disabledIds }
            val enabledXtreamIds = primaryEnabledIds + extraCachedIds

            // 1. Live TV Channels: Zero-copy fast path when a single Xtream playlist is selected!
            val rawLiveList: List<TvChannel> = if (separateMode && filterId != "ALL" && filterId != "PORTAL_MAIN") {
                xtreamChannelsMap[filterId].orEmpty()
            } else if (separateMode && filterId == "PORTAL_MAIN") {
                if (isVpnActiveAndProtected) portalChannels else emptyList()
            } else {
                val expectedLive = enabledXtreamIds.sumOf { xtreamChannelsMap[it]?.size ?: 0 } +
                    (if (isVpnActiveAndProtected) portalChannels.size else 0)
                val list = ArrayList<TvChannel>(expectedLive.coerceAtLeast(16))
                for (pid in enabledXtreamIds) {
                    list.addAll(xtreamChannelsMap[pid].orEmpty())
                }
                if (isVpnActiveAndProtected) {
                    list.addAll(portalChannels)
                }
                list
            }

            val sortedLiveBase = CustomOrderHelper.sortChannels(
                rawLiveList,
                preferences.customChannelOrder,
                preferences.hiddenBouquets
            )
            val mqHidden = CustomOrderHelper.isMultiQualityBouquetHidden(preferences.hiddenBouquets)
            val multiQualityAll = MultiQualityEngine.materializeUserGroups(sortedLiveBase)
            val multiQualityChannels = if (mqHidden) emptyList() else multiQualityAll
            val sortedLive = if (multiQualityChannels.isEmpty()) {
                sortedLiveBase
            } else {
                multiQualityChannels + sortedLiveBase
            }

            // Pre-group Live TV by bouquet in LinkedHashMap to preserve exact native server bouquet order
            val liveGrouped = LinkedHashMap<String, MutableList<TvChannel>>(128)
            for (ch in sortedLive) {
                liveGrouped.getOrPut(ch.group) { ArrayList() }.add(ch)
            }

            // Always include MultiQualityEngine.BOUQUET_NAME and favBouquetTitle in rawSummaries
            // so the user can hide/show or reorder them in Settings -> إخفاء / ترتيب الباقات at any time!
            val rawSummaries = ArrayList<BouquetSummary>(liveGrouped.size + 3)
            rawSummaries.add(
                BouquetSummary(
                    name = MultiQualityEngine.BOUQUET_NAME,
                    count = multiQualityAll.size
                )
            )
            rawSummaries.add(
                BouquetSummary(
                    name = allBouquetTitle,
                    count = sortedLiveBase.size
                )
            )
            rawSummaries.add(
                BouquetSummary(
                    name = favBouquetTitle,
                    count = favorites.size,
                    isFavoritesSpecial = true
                )
            )
            for ((bName, chs) in liveGrouped) {
                if (bName != allBouquetTitle && bName != favBouquetTitle && bName != MultiQualityEngine.BOUQUET_NAME) {
                    rawSummaries.add(
                        BouquetSummary(
                            name = bName,
                            count = chs.size
                        )
                    )
                }
            }
            val sortedSummaries = CustomOrderHelper.sortBouquets(
                rawSummaries,
                preferences.customBouquetOrder,
                preferences.hiddenBouquets
            ).filter { bq ->
                // In the Live TV sidebar & Bouquets tab, only show "متعدد جودات" if it is not hidden AND has channels
                bq.name != MultiQualityEngine.BOUQUET_NAME || (!mqHidden && multiQualityChannels.isNotEmpty())
            }
            val prebuiltDrawerBouquets = sortedSummaries.map { summary ->
                val listForBouquet = when {
                    summary.isFavoritesSpecial -> emptyList()
                    summary.name == allBouquetTitle -> sortedLive
                    else -> liveGrouped[summary.name] ?: emptyList()
                }
                summary.name to listForBouquet
            }

            // Check if VOD slice for this playlist filter is already cached (avoids re-grouping 200k movies & 50k series!)
            val cachedVod = vodSliceCache[vodCacheKey]
            if (cachedVod != null) {
                val combinedSnap = cachedVod.copy(
                    allChannels = sortedLive,
                    channelsByBouquet = liveGrouped,
                    rawBouquetSummaries = rawSummaries,
                    bouquetSummaries = sortedSummaries,
                    playerDrawerBouquets = prebuiltDrawerBouquets
                )
                fullSnapshotCache[fullCacheKey] = combinedSnap
                value = combinedSnap
                return@withContext
            }

            // Emit Live TV channels & bouquets IMMEDIATELY first so playlist switching updates the screen in <5ms!
            value = value.copy(
                allChannels = sortedLive,
                channelsByBouquet = liveGrouped,
                rawBouquetSummaries = rawSummaries,
                bouquetSummaries = sortedSummaries,
                playerDrawerBouquets = prebuiltDrawerBouquets
            )

            // 2. VOD Movies: Zero-copy fast path for single playlist
            val combinedMovies: List<TvChannel> = if (separateMode && filterId != "ALL" && filterId != "PORTAL_MAIN") {
                xtreamMoviesMap[filterId].orEmpty()
            } else if (separateMode && filterId == "PORTAL_MAIN") {
                if (isVpnActiveAndProtected) portalMovies else emptyList()
            } else {
                val expectedMoviesSize = enabledXtreamIds.sumOf { xtreamMoviesMap[it]?.size ?: 0 } +
                    (if (isVpnActiveAndProtected) portalMovies.size else 0)
                val list = ArrayList<TvChannel>(expectedMoviesSize.coerceAtLeast(16))
                for (pid in enabledXtreamIds) {
                    list.addAll(xtreamMoviesMap[pid].orEmpty())
                }
                if (isVpnActiveAndProtected) {
                    list.addAll(portalMovies)
                }
                list
            }
            val moviesGrouped = LinkedHashMap<String, MutableList<TvChannel>>(128)
            for (mv in combinedMovies) {
                moviesGrouped.getOrPut(mv.group) { ArrayList() }.add(mv)
            }
            val movieCats = ArrayList<BouquetSummary>(moviesGrouped.size + 1)
            movieCats.add(BouquetSummary("الكل", combinedMovies.size))
            for ((cName, list) in moviesGrouped) {
                if (cName != "الكل") movieCats.add(BouquetSummary(cName, list.size))
            }

            // 3. VOD Series: Zero-copy fast path for single playlist
            val combinedSeries: List<TvChannel> = if (separateMode && filterId != "ALL" && filterId != "PORTAL_MAIN") {
                xtreamSeriesMap[filterId].orEmpty()
            } else if (separateMode && filterId == "PORTAL_MAIN") {
                if (isVpnActiveAndProtected) portalSeries else emptyList()
            } else {
                val expectedSeriesSize = enabledXtreamIds.sumOf { xtreamSeriesMap[it]?.size ?: 0 } +
                    (if (isVpnActiveAndProtected) portalSeries.size else 0)
                val list = ArrayList<TvChannel>(expectedSeriesSize.coerceAtLeast(16))
                for (pid in enabledXtreamIds) {
                    list.addAll(xtreamSeriesMap[pid].orEmpty())
                }
                if (isVpnActiveAndProtected) {
                    list.addAll(portalSeries)
                }
                list
            }
            val seriesGrouped = LinkedHashMap<String, MutableList<TvChannel>>(64)
            for (sr in combinedSeries) {
                seriesGrouped.getOrPut(sr.group) { ArrayList() }.add(sr)
            }
            val seriesCats = ArrayList<BouquetSummary>(seriesGrouped.size + 1)
            seriesCats.add(BouquetSummary("الكل", combinedSeries.size))
            for ((cName, list) in seriesGrouped) {
                if (cName != "الكل") seriesCats.add(BouquetSummary(cName, list.size))
            }

            val finalSnap = CatalogViewSnapshot(
                allChannels = sortedLive,
                channelsByBouquet = liveGrouped,
                rawBouquetSummaries = rawSummaries,
                bouquetSummaries = sortedSummaries,
                playerDrawerBouquets = prebuiltDrawerBouquets,
                allMovies = combinedMovies,
                moviesByCategory = moviesGrouped,
                movieCategories = movieCats,
                allSeries = combinedSeries,
                seriesByCategory = seriesGrouped,
                seriesCategories = seriesCats
            )
            vodSliceCache[vodCacheKey] = finalSnap
            fullSnapshotCache[fullCacheKey] = finalSnap
            value = finalSnap
        }
        if (initialized) {
            isSortingLocalLibrary = false
        }
    }

    val allChannels = catalogSnapshot.allChannels
    val rawBouquetSummaries = catalogSnapshot.rawBouquetSummaries
    // Item 9: O(1) update of Favorites count badge without rebuilding the 50,000-channel catalog!
    val liveFavoritesCount = remember(favorites) {
        favorites.count { it.contentType == StreamContentType.LIVE_TV }
    }
    val bouquetSummaries = remember(catalogSnapshot.bouquetSummaries, liveFavoritesCount, favBouquetTitle) {
        catalogSnapshot.bouquetSummaries.map { bq ->
            if (bq.isFavoritesSpecial || bq.name == favBouquetTitle) bq.copy(count = liveFavoritesCount) else bq
        }
    }
    val allMovies = catalogSnapshot.allMovies
    val allSeries = catalogSnapshot.allSeries

    suspend fun persistCurrentStateSuspend(saveCatalog: Boolean = false) {
        if (!initialized) return
        val pCh = portalChannels
        val pMv = portalMovies
        val pSr = portalSeries
        val xChMap = xtreamChannelsMap
        val xMvMap = xtreamMoviesMap
        val xSrMap = xtreamSeriesMap
        val curDeviceId = deviceId
        val curDeviceName = deviceName
        val curActCode = savedActivationCode
        val curPlaylists = xtreamPlaylists
        val curFavs = favorites
        val curHist = history
        val curSub = subscription
        val curPrefs = preferences

        withContext(Dispatchers.IO) {
            val snapshot = PersistedAppState(
                deviceId = curDeviceId,
                deviceName = curDeviceName,
                activationCode = curActCode,
                xtreamPlaylists = curPlaylists,
                cachedChannels = emptyList(),
                cachedMovies = emptyList(),
                cachedSeries = emptyList(),
                favorites = curFavs,
                history = curHist,
                subscription = curSub,
                preferences = curPrefs
            )
            StorageManager.saveState(context, snapshot, saveCatalog = saveCatalog)
        }
    }

    fun persistCurrentState(saveCatalog: Boolean = false) {
        if (!initialized) return
        if (saveCatalog) {
            scope.launch {
                persistCurrentStateSuspend(saveCatalog = true)
            }
        } else {
            saveStateJob?.cancel()
            saveStateJob = scope.launch {
                delay(280L)
                persistCurrentStateSuspend(saveCatalog = false)
            }
        }
    }

    fun stopVpnImmediately() {
        CloudflareWarpVpnService.stopTunnelSafe(context)
        isConnectingVpn = false
        vpnStatus = CloudflareTunnelStatus(
            connected = false,
            systemVpnActive = false,
            statusText = "تم إيقاف الـ VPN لانتهاء صلاحية اشتراك المشغل"
        )
    }

    fun hidePortalMainOnVpnOff() {
        if (playingChannel?.playlistId == "PORTAL_MAIN") {
            playingChannel = null
            ChannelRepository.cancelPendingStreamRequests()
        }
        if (previewLiveChannel?.playlistId == "PORTAL_MAIN") {
            previewLiveChannel = null
        }
        if (preferences.activePlaylistFilterId == "PORTAL_MAIN") {
            val fallbackId = orderedPlaylists.firstOrNull { it.enabled }?.id ?: "ALL"
            preferences = preferences.copy(
                activePlaylistFilterId = fallbackId,
                playlistDisplayMode = if (fallbackId == "ALL") PlaylistDisplayMode.MERGE_ALL else PlaylistDisplayMode.SEPARATE_LISTS
            )
        }
    }

    fun pauseVpnTemporarilyNow() {
        vpnPausedTemporarily = true
        LiveProGlobalActions.updateVpnPrefs(context, permanentDisable = false, autoStart = true)
        browseOfflineAllowed = true
        CloudflareWarpVpnService.stopTunnelSafe(context)
        isConnectingVpn = false
        hidePortalMainOnVpnOff()
        vpnStatus = CloudflareTunnelStatus(
            connected = false,
            systemVpnActive = false,
            statusText = "⏸️ الـ VPN متوقف مؤقتاً لهذه الجلسة (تم إخفاء القائمة الرئيسية لحماية الروابط)"
        )
        persistCurrentState(saveCatalog = false)
        Toast.makeText(
            context,
            "⏸️ تم إيقاف الـ VPN مؤقتاً لهذه الجلسة وإخفاء القائمة الرئيسية لحماية الروابط (سيعمل تلقائياً عند فتح التطبيق مجدداً)",
            Toast.LENGTH_LONG
        ).show()
    }

    fun disableVpnPermanentlyNow() {
        vpnPausedTemporarily = false
        LiveProGlobalActions.updateVpnPrefs(context, permanentDisable = true)
        browseOfflineAllowed = true
        CloudflareWarpVpnService.stopTunnelSafe(context)
        isConnectingVpn = false
        hidePortalMainOnVpnOff()
        vpnStatus = CloudflareTunnelStatus(
            connected = false,
            systemVpnActive = false,
            statusText = "🛑 الـ VPN متوقف دائماً (القائمة الرئيسية مخفية تلقائياً لمنع سحب الروابط)"
        )
        persistCurrentState(saveCatalog = false)
        Toast.makeText(
            context,
            "🛑 تم إيقاف الـ VPN وإخفاء القائمة الرئيسية تلقائياً لمنع اعتراض الروابط",
            Toast.LENGTH_LONG
        ).show()
    }
    LiveProGlobalActions.onPauseVpnTemporarily = { pauseVpnTemporarilyNow() }
    LiveProGlobalActions.onDisableVpnPermanently = { disableVpnPermanentlyNow() }

    fun startRealWireGuardServiceAndVerify() {
        val currentSub = subscription
        if (currentSub == null || !currentSub.active || currentSub.isExpired) {
            stopVpnImmediately()
            return
        }
        if (isConnectingVpn) return
        scope.launch {
            isConnectingVpn = true
            try {
                CloudflareWarpVpnService.userManuallyStoppedVpn = false
                val intent = Intent(context, CloudflareWarpVpnService::class.java).apply {
                    action = CloudflareWarpVpnService.ACTION_CONNECT
                }
                runCatching { context.startService(intent) }.onFailure {
                    // Android 14 background start restriction fallback: activate encrypted DoH tunnel directly
                    CloudflareWarpVpnService.dohShieldFallbackActive = true
                    CloudflareWarpVpnService.isTunnelRunning = false
                }

                for (attempt in 0 until 5) {
                    if (CloudflareDohManager.isInternalAppVpnExclusivelyActive(context)) {
                        break
                    }
                    if (attempt < 4) delay(75L)
                }

                val status = CloudflareDohManager.verifyAndActivateCloudflareTunnel(context)
                vpnStatus = status
            } finally {
                isConnectingVpn = false
            }
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isVpnPermissionDialogShowing = false
        val currentSub = subscription
        if (currentSub == null || !currentSub.active || currentSub.isExpired) {
            stopVpnImmediately()
            return@rememberLauncherForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK) {
            startRealWireGuardServiceAndVerify()
        } else if (!CloudflareDohManager.isSystemVpnActive(context)) {
            // On Google TV / Android TV firmwares where VpnDialogs is disabled or auto-returns RESULT_CANCELED,
            // automatically activate the built-in Cloudflare WARP DoH 1.1.1.1 Shield so Google TV never gets stuck!
            CloudflareWarpVpnService.dohShieldFallbackActive = true
            CloudflareWarpVpnService.isTunnelRunning = false
            startRealWireGuardServiceAndVerify()
        } else {
            isConnectingVpn = false
            vpnStatus = CloudflareTunnelStatus(
                connected = false,
                statusText = "يرجى إيقاف أي VPN خارجي والضغط على زر (تفعيل الـ VPN) للدخول"
            )
        }
    }

    fun connectVpnAutomatically(manualTrigger: Boolean = false, forcePrompt: Boolean = manualTrigger) {
        val isManual = manualTrigger || forcePrompt
        val currentSub = subscription
        if (currentSub == null || !currentSub.active || currentSub.isExpired) {
            stopVpnImmediately()
            return
        }
        if (isManual) {
            vpnPausedTemporarily = false
            if (LiveProGlobalActions.vpnPermanentDisable) {
                LiveProGlobalActions.updateVpnPrefs(context, permanentDisable = false)
            }
        } else if (vpnPausedTemporarily || LiveProGlobalActions.vpnPermanentDisable) {
            browseOfflineAllowed = true
            hidePortalMainOnVpnOff()
            return
        }
        if (isVpnPermissionDialogShowing || isConnectingVpn) return
        val prepareIntent = try { VpnService.prepare(context) } catch (_: Throwable) { null }
        val canResolveVpnDialog = prepareIntent != null &&
            runCatching { prepareIntent.resolveActivity(context.packageManager) != null }.getOrDefault(false)
        if (prepareIntent != null && canResolveVpnDialog) {
            // Prevent infinite re-launch loop that freezes the first-time Android system VPN confirmation dialog!
            if (!isManual && vpnPermissionAskedInSession) {
                return
            }
            isVpnPermissionDialogShowing = true
            vpnPermissionAskedInSession = true
            try {
                vpnPermissionLauncher.launch(prepareIntent)
            } catch (_: Throwable) {
                isVpnPermissionDialogShowing = false
                CloudflareWarpVpnService.dohShieldFallbackActive = true
                CloudflareWarpVpnService.isTunnelRunning = true
                startRealWireGuardServiceAndVerify()
            }
        } else {
            if (prepareIntent != null) {
                // Google TV device without VpnDialogs system package: activate internal WARP DoH Shield immediately
                CloudflareWarpVpnService.dohShieldFallbackActive = true
                CloudflareWarpVpnService.isTunnelRunning = true
            }
            startRealWireGuardServiceAndVerify()
        }
    }


    /**
     * Polls ConnectivityManager until NET_CAPABILITY_INTERNET is present (or timeout).
     * Prevents incomplete Xtream/M3U catalog pulls right after VPN comes up but before routes work.
     */
    suspend fun waitUntilInternetReady(maxWaitMs: Long = 20_000L): Boolean {
        if (ExpiryAlertHelper.isInternetAvailable(context)) return true
        val deadline = System.currentTimeMillis() + maxWaitMs
        var attempt = 0
        while (System.currentTimeMillis() < deadline) {
            attempt++
            if (attempt == 1 || attempt % 6 == 0) {
                statusMessage = "🌐 انتظار استقرار الإنترنت قبل سحب القوائم بالكامل..."
                syncProgress = syncProgress.copy(
                    isSyncing = true,
                    sourceId = syncProgress.sourceId.ifBlank { "NET_WAIT" },
                    sourceName = syncProgress.sourceName.ifBlank { "الشبكة" },
                    phaseLabel = "🌐 جارٍ التحقق من الإنترنت قبل تنزيل المحتوى الكامل...",
                    progressPercent = (8 + (attempt * 2)).coerceAtMost(18)
                )
            }
            delay(160L)
            if (ExpiryAlertHelper.isInternetAvailable(context)) {
                return true
            }
        }
        return ExpiryAlertHelper.isInternetAvailable(context)
    }

    /**
     * Ultra-Fast VPN + Internet Barrier (0ms fast-path when already connected!):
     */
    suspend fun ensureVpnConnectedAndStableBeforeSync() {
        val currentSub = subscription
        if (currentSub != null && (!currentSub.active || currentSub.isExpired)) return
        if (vpnPausedTemporarily || LiveProGlobalActions.vpnPermanentDisable || !LiveProGlobalActions.vpnAutoStartOnLaunch) {
            waitUntilInternetReady(maxWaitMs = 15_000L)
            return
        }

        val alreadyReady = (vpnStatus.connected || CloudflareWarpVpnService.isTunnelRunning) &&
            !isConnectingVpn && !isVpnPermissionDialogShowing
        // 0ms Instant Fast-Path when VPN + Internet are already active!
        if (alreadyReady && ExpiryAlertHelper.isInternetAvailable(context)) {
            return
        }

        if (!alreadyReady) {
            if (!vpnStatus.connected && !CloudflareWarpVpnService.isTunnelRunning && !isConnectingVpn && !isVpnPermissionDialogShowing) {
                withContext(Dispatchers.Main) {
                    connectVpnAutomatically()
                }
                delay(35L)
            }

            var dialogWaitTicks = 0
            while (isVpnPermissionDialogShowing && dialogWaitTicks < 240) {
                delay(100L)
                dialogWaitTicks++
            }

            if (!vpnStatus.connected && !CloudflareWarpVpnService.isTunnelRunning) {
                delay(40L)
            }

            var tunnelWaitTicks = 0
            while ((isConnectingVpn || (!vpnStatus.connected && !CloudflareWarpVpnService.isTunnelRunning)) && tunnelWaitTicks < 45) {
                if (vpnPausedTemporarily || LiveProGlobalActions.vpnPermanentDisable) break
                delay(65L)
                tunnelWaitTicks++
            }

            if (vpnStatus.connected || CloudflareWarpVpnService.isTunnelRunning) {
                delay(80L)
                ChannelRepository.evictIdleApiConnections()
            }
        }

        waitUntilInternetReady(maxWaitMs = 20_000L)
    }

    /**
     * Hard gate used before any FULL catalog download (live+VOD+series).
     * Returns false if the network is not ready — caller must abort without writing partial catalog as "complete".
     */
    suspend fun ensureNetworkReadyForFullCatalogSync(requireVpn: Boolean = true): Boolean {
        if (requireVpn && !vpnPausedTemporarily && !LiveProGlobalActions.vpnPermanentDisable && LiveProGlobalActions.vpnAutoStartOnLaunch) {
            ensureVpnConnectedAndStableBeforeSync()
            val vpnOk = vpnStatus.connected || CloudflareWarpVpnService.isTunnelRunning
            if (!vpnOk && !vpnPausedTemporarily && !LiveProGlobalActions.vpnPermanentDisable) {
                statusMessage = "🛡️ تعذّر تأكيد اتصال نفق WireGuard — لن يبدأ سحب القوائم حتى يتوفر النفق والإنترنت"
                TiviMateDiagnostics.logEvent("⛔ Catalog sync blocked: WireGuard tunnel not ready")
                return false
            }
        } else {
            waitUntilInternetReady(maxWaitMs = 20_000L)
        }
        val netOk = ExpiryAlertHelper.isInternetAvailable(context)
        if (!netOk) {
            statusMessage = "📡 لا يوجد إنترنت مستقر — تأجيل سحب المحتوى الكامل لتجنب قوائم ناقصة"
            TiviMateDiagnostics.logEvent("⛔ Catalog sync blocked: no internet")
            return false
        }
        return true
    }

    suspend fun syncSingleXtreamPlaylistSuspend(configToSync: XtreamAccountConfig, showToast: Boolean = false) {
        if (!configToSync.isConfigured) return
        // Immediately acquire network-level lock BEFORE any network call so LaunchedEffect(Unit),
        // onResume(), and the 45s watcher can NEVER launch duplicate concurrent downloads on Android TV!
        if (!TiviMateLocalDatabase.tryAcquireNetworkSync(configToSync.id)) {
            if (showToast) {
                Toast.makeText(context, "⏳ جارٍ تحديث هذه القائمة بالفعل، يرجى الانتظار...", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val currentSub = subscription
        if (currentSub != null && (currentSub.isExpired || !currentSub.active)) {
            TiviMateLocalDatabase.releaseNetworkSync(configToSync.id)
            Toast.makeText(
                context,
                "⛔ انتهت صلاحية المشغل ($deviceId). يرجى تجديد الاشتراك أولاً عبر علي سات: $ALI_SAT_PHONE_NUMBER",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // PRODUCTION: never pull full catalog until WireGuard + Internet are confirmed
        // (prevents incomplete restored lists that force the user to refresh again)
        val networkReady = ensureNetworkReadyForFullCatalogSync(requireVpn = true)
        if (!networkReady) {
            TiviMateLocalDatabase.releaseNetworkSync(configToSync.id)
            if (showToast) {
                Toast.makeText(
                    context,
                    "🛡️ انتظر اتصال النفق والإنترنت أولاً، ثم أعد تحديث القائمة لسحب المحتوى كاملاً",
                    Toast.LENGTH_LONG
                ).show()
            }
            statusMessage = "🛡️ تأجيل سحب (${configToSync.serverName.ifBlank { "XTREAM" }}) حتى يستقر النفق والإنترنت"
            return
        }

        val sName = configToSync.serverName.ifBlank { "XTREAM" }
        var partialLiveDelivered = emptyList<TvChannel>()
        var partialExpDelivered = configToSync.expDate

        TiviMateLocalDatabase.globalXtreamSyncQueueMutex.lock()
        isSyncingXtream = true
        syncProgress = SyncProgressState(
            isSyncing = true,
            sourceId = configToSync.id,
            sourceName = sName,
            phaseLabel = "🚀 جارٍ سحب وتحديث قائمة ($sName)...",
            progressPercent = 10
        )
        try {
            var xResult: XtreamFullSyncResult? = null
            var lastSyncErr: Exception? = null

            for (attempt in 1..3) {
                try {
                    xResult = XtreamRepository.syncXtreamAccount(
                        config = configToSync,
                        onLiveReady = { liveChannelsNow, expNow ->
                            partialLiveDelivered = liveChannelsNow
                            if (expNow.isNotBlank()) partialExpDelivered = expNow
                            scope.launch(Dispatchers.Main) {
                                xtreamChannelsMap = xtreamChannelsMap + (configToSync.id to liveChannelsNow)
                                val partialAcc = configToSync.copy(
                                    enabled = true,
                                    liveCount = liveChannelsNow.size,
                                    expDate = expNow.ifBlank { configToSync.expDate },
                                    statusNote = "متصل ✓ (${liveChannelsNow.size} قناة مباشرة • جارٍ إكمال الأفلام...)"
                                )
                                xtreamPlaylists = xtreamPlaylists.map {
                                    if (it.id == configToSync.id) partialAcc else it
                                }.let { list ->
                                    if (list.none { it.id == configToSync.id }) list + partialAcc else list
                                }
                            }
                        },
                        onProgress = { prog ->
                            syncProgress = prog
                        }
                    )
                    break
                } catch (e: Exception) {
                    lastSyncErr = e
                    val msg = e.message.orEmpty()
                    val isPermanentAuthError = msg.contains("منتهي") || msg.contains("غير نشط") ||
                        msg.contains("Expired", ignoreCase = true) || msg.contains("Banned", ignoreCase = true)
                    if (isPermanentAuthError || partialLiveDelivered.isNotEmpty() || attempt == 2) {
                        break
                    }
                    // Wait 2.2s for Android TV Wi-Fi / WireGuard route to settle, then retry once automatically
                    syncProgress = syncProgress.copy(
                        phaseLabel = "🔄 إعادة المحاولة التلقائية لتحديث قائمة ($sName)...",
                        progressPercent = 20
                    )
                    delay(2200L)
                }
            }

            // If full sync threw an exception during VOD/Series AFTER Live TV channels were already downloaded,
            // construct a valid result from the downloaded Live TV channels so the TV succeeds 100%!
            val finalResult = xResult ?: if (partialLiveDelivered.isNotEmpty()) {
                XtreamFullSyncResult(
                    account = configToSync.copy(
                        enabled = true,
                        liveCount = partialLiveDelivered.size,
                        expDate = partialExpDelivered,
                        lastSyncTimestamp = System.currentTimeMillis(),
                        statusNote = "متصل ✓ (${partialLiveDelivered.size} قناة مباشرة)"
                    ),
                    liveChannels = partialLiveDelivered,
                    movies = xtreamMoviesMap[configToSync.id].orEmpty(),
                    series = xtreamSeriesMap[configToSync.id].orEmpty()
                )
            } else {
                throw (lastSyncErr ?: IllegalStateException("تعذّر الاتصال بسيرفر XTREAM"))
            }

            // 1. Update in-memory UI maps IMMEDIATELY so channels/movies/series appear on screen in 0ms!
            val immediateAccount = finalResult.account.copy(
                statusNote = "متصل ✓ (${finalResult.liveChannels.size} قناة • ${finalResult.movies.size} فيلم • ${finalResult.series.size} مسلسل)"
            )
            xtreamPlaylists = xtreamPlaylists.map {
                if (it.id == configToSync.id) immediateAccount else it
            }.let { list ->
                if (list.none { it.id == configToSync.id }) list + immediateAccount else list
            }
            val safeLive = if (finalResult.liveChannels.isNotEmpty()) finalResult.liveChannels else xtreamChannelsMap[finalResult.account.id].orEmpty()
            val safeMovies = if (finalResult.movies.isNotEmpty()) finalResult.movies else xtreamMoviesMap[finalResult.account.id].orEmpty()
            val safeSeries = if (finalResult.series.isNotEmpty()) finalResult.series else xtreamSeriesMap[finalResult.account.id].orEmpty()
            xtreamChannelsMap = xtreamChannelsMap + (finalResult.account.id to safeLive)
            xtreamMoviesMap = xtreamMoviesMap + (finalResult.account.id to safeMovies)
            xtreamSeriesMap = xtreamSeriesMap + (finalResult.account.id to safeSeries)

            // 2. Push verified working playlist to Customer Portal & api.php (2-way real-time sync with fingerprint deduplication)
            scope.launch(Dispatchers.IO) {
                runCatching {
                    ChannelRepository.pushCustomerXtreamToPortal(deviceId, deviceName, finalResult.account)
                }
            }

            // 3. Immediately unlock UI & mark sync 100% complete in RAM, while committing to SQLite on Dispatchers.IO!
            val diffNote = "متصل ✓ (${safeLive.size} قناة • ${safeMovies.size} فيلم • ${safeSeries.size} مسلسل)"
            val finalAccount = finalResult.account.copy(
                liveCount = safeLive.size,
                movieCount = safeMovies.size,
                seriesCount = safeSeries.size,
                statusNote = diffNote
            )
            xtreamPlaylists = xtreamPlaylists.map {
                if (it.id == configToSync.id) finalAccount else it
            }
            syncProgress = syncProgress.copy(
                isSyncing = false,
                progressPercent = 100,
                addedCount = safeLive.size + safeMovies.size + safeSeries.size,
                isDifferential = true
            )
            isSyncingXtream = false
            scope.launch(Dispatchers.IO) {
                runCatching {
                    sqliteDb.syncSourceCatalogAtomically(
                        sourceId = finalResult.account.id,
                        sourceName = finalResult.account.serverName,
                        liveChannels = safeLive,
                        movies = safeMovies,
                        series = safeSeries,
                        catalogEtag = finalResult.catalogHash
                    )
                    persistCurrentStateSuspend(saveCatalog = true)
                }
            }
            if (finalResult.account.expDate.isNotBlank() && ExpiryAlertHelper.isDateAlreadyExpired(finalResult.account.expDate)) {
                diagnosticAlert = DiagnosticAlertInfo(
                    type = DiagnosticAlertType.ACCOUNT_EXPIRED,
                    title = "⚠️ انتهت صلاحية حساب القنوات (${finalResult.account.serverName})",
                    message = "انتهت فترة اشتراك القائمة (${finalResult.account.serverName}) بتاريخ ${finalResult.account.expDate}.\nيرجى تجديد اشتراك الحساب عبر التواصل مع مزود الخدمة ($ALI_SAT_PROVIDER_NAME : $ALI_SAT_PHONE_NUMBER) وتقديم رمز الشاشة ($deviceId)، أو تحديث بيانات القائمة من فضاء الزبون.",
                    sourceName = finalResult.account.serverName,
                    expDate = finalResult.account.expDate
                )
            } else if (showToast) {
                Toast.makeText(
                    context,
                    "✅ تم تحديث قائمة (${finalResult.account.serverName}) بنجاح: ${finalResult.liveChannels.size} قناة • ${finalResult.movies.size} فيلم • ${finalResult.series.size} مسلسل",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            val msg = e.message ?: "تعذّر الاتصال بسيرفر XTREAM"
            val hadCachedContent = !xtreamChannelsMap[configToSync.id].isNullOrEmpty() ||
                !xtreamMoviesMap[configToSync.id].isNullOrEmpty() ||
                !xtreamSeriesMap[configToSync.id].isNullOrEmpty()
            // NEVER delete the playlist from the TV or from Customer Portal on a temporary sync error!
            TiviMateDiagnostics.logEvent("⚠️ تعذّر تحديث [$sName]: $msg (تم الاحتفاظ بالقائمة في الجهاز لإعادة المحاولة)")
            val keptAccount = configToSync.copy(
                statusNote = if (hadCachedContent) "⚠️ محفوظ محلياً ($msg)" else "⚠️ تعذّر السحب حالياً — اضغط 🔄 لإعادة المحاولة"
            )
            xtreamPlaylists = xtreamPlaylists.map {
                if (it.id == configToSync.id) keptAccount else it
            }.let { list ->
                if (list.none { it.id == configToSync.id }) list + keptAccount else list
            }
            persistCurrentStateSuspend(saveCatalog = false)

            syncProgress = syncProgress.copy(isSyncing = false, lastError = msg)
            val tagged = msg.substringBefore("|").trim().uppercase()
            val cleanMsg = if (msg.contains("|")) msg.substringAfter("|").trim() else msg
            val isAuthInvalid = tagged == "AUTH_INVALID" ||
                cleanMsg.contains("اسم المستخدم") || cleanMsg.contains("كلمة المرور") ||
                cleanMsg.contains("Username", ignoreCase = true) || cleanMsg.contains("Password", ignoreCase = true) ||
                cleanMsg.contains("auth", ignoreCase = true) && cleanMsg.contains("invalid", ignoreCase = true)
            val isExpiredAccountErr = tagged == "ACCOUNT_EXPIRED" ||
                cleanMsg.contains("منتهي") || cleanMsg.contains("غير نشط") ||
                cleanMsg.contains("Expired", ignoreCase = true) || cleanMsg.contains("Banned", ignoreCase = true) ||
                ExpiryAlertHelper.isDateAlreadyExpired(configToSync.expDate)
            val isNetworkErr = tagged == "NETWORK_OR_HOST" ||
                !ExpiryAlertHelper.isInternetAvailable(context) ||
                cleanMsg.contains("Timeout", ignoreCase = true) ||
                cleanMsg.contains("Unable to resolve", ignoreCase = true) ||
                cleanMsg.contains("Failed to connect", ignoreCase = true) ||
                cleanMsg.contains("Network is unreachable", ignoreCase = true)
            when {
                isAuthInvalid -> {
                    diagnosticAlert = DiagnosticAlertInfo(
                        type = DiagnosticAlertType.AUTH_INVALID,
                        title = "🔑 بيانات الدخول غير صحيحة ($sName)",
                        message = cleanMsg.ifBlank {
                            "اسم المستخدم أو كلمة المرور غير صحيحة على السيرفر ($sName).\nتأكد من كتابتهما بدقة، أو استخدم الوضع اليدوي مع رابط الهوست الصحيح."
                        },
                        sourceName = sName
                    )
                }
                isExpiredAccountErr -> {
                    diagnosticAlert = DiagnosticAlertInfo(
                        type = DiagnosticAlertType.ACCOUNT_EXPIRED,
                        title = "⚠️ انتهت صلاحية حساب القنوات ($sName)",
                        message = cleanMsg.ifBlank {
                            "انتهت صلاحية اشتراك الحساب ($sName). يرجى تجديد الاشتراك بالتواصل مع علي سات ($ALI_SAT_PHONE_NUMBER) وتقديم رمز الشاشة ($deviceId)، أو تحديث القائمة من فضاء الزبون."
                        },
                        sourceName = sName,
                        expDate = configToSync.expDate
                    )
                }
                isNetworkErr -> {
                    diagnosticAlert = DiagnosticAlertInfo(
                        type = DiagnosticAlertType.INTERNET_ERROR,
                        title = "📡 انقطاع أو ضعف في الشبكة",
                        message = cleanMsg.ifBlank {
                            "تعذّر الوصول إلى السيرفر ($sName) بسبب مشكلة في الإنترنت أو الهوست.\nتحقق من الواي فاي/الراوتر ثم أعد المحاولة."
                        },
                        sourceName = sName
                    )
                }
                !hadCachedContent && showToast -> {
                    diagnosticAlert = DiagnosticAlertInfo(
                        type = DiagnosticAlertType.SERVER_OUTAGE,
                        title = "🛠️ السيرفر لا يستجيب حالياً ($sName)",
                        message = cleanMsg.ifBlank {
                            "يتعذّر استجابة خادم القنوات ($sName) بعد محاولات التنزيل.\nاضغط 🔄 لإعادة المحاولة، أو أعد تشغيل الراوتر."
                        },
                        sourceName = sName
                    )
                }
            }
        } finally {
            isSyncingXtream = false
            TiviMateLocalDatabase.releaseNetworkSync(configToSync.id)
            runCatching { TiviMateLocalDatabase.globalXtreamSyncQueueMutex.unlock() }
        }
    }

    fun syncSingleXtreamPlaylist(configToSync: XtreamAccountConfig, showToast: Boolean = false) {
        scope.launch {
            syncSingleXtreamPlaylistSuspend(configToSync, showToast = showToast)
        }
    }



    fun syncAllEnabledXtreamPlaylists(forceRefresh: Boolean = false) {
        val currentSub = subscription
        if (currentSub != null && (currentSub.isExpired || !currentSub.active)) return
        scope.launch {
            for (cfg in orderedPlaylists.filter { it.enabled && it.isConfigured }) {
                if (forceRefresh || xtreamChannelsMap[cfg.id].isNullOrEmpty()) {
                    syncSingleXtreamPlaylistSuspend(cfg, showToast = false)
                }
            }
        }
    }

    fun syncServerNow(codeOverride: String = savedActivationCode, forceXtreamRefresh: Boolean = false) {
        if (TiviMateLocalDatabase.isSourceSyncInProgress("PORTAL_MAIN")) return
        if (!MainActivity.syncLock.compareAndSet(false, true)) {
            TiviMateDiagnostics.logEvent("⚠️ Sync already in progress")
            return
        }
        scope.launch(Dispatchers.Main) {
            isLoading = true
            // STEP 0: Always ensure VPN is 100% connected and stabilized BEFORE starting any playlist/channel download!
            if (!vpnStatus.connected && !CloudflareWarpVpnService.isTunnelRunning && !vpnPausedTemporarily && !LiveProGlobalActions.vpnPermanentDisable) {
                syncProgress = SyncProgressState(
                    isSyncing = true,
                    sourceId = "VPN_INIT",
                    sourceName = "نفق الحماية VPN",
                    phaseLabel = "🛡️ جارٍ تفعيل وتأمين الـ VPN التلقائي أولاً قبل تحميل الباقات والقنوات...",
                    progressPercent = 10
                )
                statusMessage = "🛡️ جارٍ تفعيل الـ VPN التلقائي أولاً لضمان تحميل القوائم دون انقطاع..."
            }
            ensureVpnConnectedAndStableBeforeSync()
            // Hard stop: do not begin full list restore/download without tunnel + internet
            if (!ensureNetworkReadyForFullCatalogSync(requireVpn = true)) {
                statusMessage = "🛡️ بانتظار نفق WireGuard والإنترنت قبل سحب القوائم بالكامل (لتجنب محتوى ناقص)"
                isLoading = false
                syncProgress = syncProgress.copy(isSyncing = false, lastError = "network_not_ready")
                MainActivity.syncLock.set(false)
                return@launch
            }

            syncProgress = SyncProgressState(
                isSyncing = true,
                sourceId = "PORTAL_MAIN",
                sourceName = "الخادم الرئيسي",
                phaseLabel = "جارٍ التحقق التفاضلي ومزامنة المكتبة...",
                progressPercent = 20
            )
            statusMessage = AppStrings.tr(
                lang,
                "جارٍ التحقق من صلاحية المشغل والمزامنة التفاضلية عبر رمز الشاشة...",
                "Vérification et synchronisation différentielle via Code Écran...",
                "Checking validity and differential sync via Screen Code..."
            )
            try {
                // PRIORITY STEP 1: Fast-restore Screen Code subscription & linked XTREAM lists FIRST (in <1s)
                // so restored XTREAM playlists are downloaded and stored BEFORE the main portal list!
                runCatching {
                    val (quickSub, quickRemoteLists) = ChannelRepository.checkSubscriptionAndListsQuick(deviceId, deviceName)
                    subscription = quickSub
                    if (quickSub.active && !quickSub.isExpired) {
                        if (quickRemoteLists.isNotEmpty()) {
                            val mergedFast = xtreamPlaylists.toMutableList()
                            for (rCfg in quickRemoteLists) {
                                val existingIdx = mergedFast.indexOfFirst {
                                    it.id == rCfg.id ||
                                        (it.serverUrl.equals(rCfg.serverUrl, ignoreCase = true) &&
                                            it.username.equals(rCfg.username, ignoreCase = true))
                                }
                                if (existingIdx >= 0) {
                                    mergedFast[existingIdx] = rCfg.copy(id = mergedFast[existingIdx].id)
                                } else {
                                    mergedFast.add(rCfg)
                                }
                            }
                            xtreamPlaylists = mergedFast
                        }
                        // Sync restored XTREAM playlists FIRST (each one individually at rocket speed into SQLite)!
                        val listsToSyncFirst = xtreamPlaylists.filter { it.enabled && it.isConfigured }
                        for (cfg in listsToSyncFirst) {
                            if (forceXtreamRefresh || xtreamChannelsMap[cfg.id].isNullOrEmpty()) {
                                syncSingleXtreamPlaylistSuspend(cfg, showToast = false)
                            }
                        }
                    }
                }

                // PRIORITY STEP 2: Now sync the Main Portal list ("PORTAL_MAIN") after XTREAM lists!
                val (_, lastEtag) = sqliteDb.getSourceLastSyncInfo("PORTAL_MAIN")
                val result = ChannelRepository.syncWithServer(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    activationCode = codeOverride,
                    knownCatalogHash = if (portalChannels.isNotEmpty() && !forceXtreamRefresh) lastEtag else ""
                )
                subscription = result.subscription

                if (result.subscription.active && !result.subscription.isExpired) {
                    if (result.remoteXtreamLists.isNotEmpty()) {
                        val mergedLists = xtreamPlaylists.toMutableList()
                        var newlyDiscovered = false
                        for (remoteCfg in result.remoteXtreamLists) {
                            val existingIdx = mergedLists.indexOfFirst {
                                it.id == remoteCfg.id ||
                                    (it.serverUrl.equals(remoteCfg.serverUrl, ignoreCase = true) &&
                                        it.username.equals(remoteCfg.username, ignoreCase = true))
                            }
                            if (existingIdx >= 0) {
                                mergedLists[existingIdx] = remoteCfg.copy(id = mergedLists[existingIdx].id)
                            } else {
                                mergedLists.add(remoteCfg)
                                newlyDiscovered = true
                            }
                        }
                        xtreamPlaylists = mergedLists
                        // If any additional Xtream list came from api.php, sync it BEFORE finalizing Portal Main
                        if (newlyDiscovered) {
                            for (cfg in xtreamPlaylists.filter { it.enabled && it.isConfigured }) {
                                if (xtreamChannelsMap[cfg.id].isNullOrEmpty()) {
                                    syncSingleXtreamPlaylistSuspend(cfg, showToast = false)
                                }
                            }
                        }
                    }

                    if (!result.isDifferentialUnchanged && (result.channels.isNotEmpty() || result.movies.isNotEmpty() || result.series.isNotEmpty())) {
                        portalChannels = result.channels
                        portalMovies = result.movies
                        portalSeries = result.series
                        val diffStats = sqliteDb.syncSourceCatalogAtomically(
                            sourceId = "PORTAL_MAIN",
                            sourceName = "قائمة الخادم الرئيسية",
                            liveChannels = result.channels,
                            movies = result.movies,
                            series = result.series,
                            catalogEtag = result.serverCatalogHash
                        )
                        syncProgress = syncProgress.copy(
                            progressPercent = 95,
                            fetchedLiveCount = result.channels.size,
                            fetchedMovieCount = result.movies.size,
                            fetchedSeriesCount = result.series.size,
                            addedCount = diffStats.addedCount,
                            updatedCount = diffStats.updatedCount,
                            removedCount = diffStats.removedCount,
                            isDifferential = true
                        )
                    } else {
                        TiviMateDiagnostics.logEvent("⚡ فحص تفاضلي سريع: مكتبة الخادم الرئيسي مطابقة للمحلية (لم يتغير شيء)")
                    }

                    val expText = if (result.subscription.expireDate.isNotBlank()) {
                        " • ينتهي: ${result.subscription.expireDate}"
                    } else ""
                    statusMessage = "متصل • الصلاحية المتبقية: ${result.subscription.daysLeft} يوم$expText"

                    if (!vpnStatus.connected) {
                        connectVpnAutomatically()
                    }
                    persistCurrentStateSuspend(saveCatalog = true)
                } else {
                    // Subscription expired or inactive -> IMMEDIATELY stop VPN, block entry & show Ali Sat expiry gate!
                    // Keep local SQLite catalog intact on disk so when the user renews, channels do not need to be re-downloaded!
                    stopVpnImmediately()
                    playingChannel = null
                    val subMsg = result.subscription.message
                    statusMessage = if (subMsg.isNotBlank()) subMsg else "انتهت صلاحية المشغل. تواصل مع علي سات : $ALI_SAT_PHONE_NUMBER"
                    persistCurrentStateSuspend(saveCatalog = false)
                }
            } catch (e: Exception) {
                // Preserve existing local SQLite library when network fails!
                val errTxt = e.message ?: "تعذّر الاتصال بالخادم"
                statusMessage = "$errTxt • يتم عرض المكتبة المخزنة محلياً"
                TiviMateDiagnostics.logEvent("⚠️ انقطاع الشبكة أثناء المزامنة: تم الاحتفاظ بالمكتبة المحلية دون فقدان أي قناة.")
                if (portalChannels.isNotEmpty() || xtreamChannelsMap.isNotEmpty()) {
                    browseOfflineAllowed = true
                }
                if (forceXtreamRefresh) {
                    diagnosticAlert = if (!ExpiryAlertHelper.isInternetAvailable(context)) {
                        DiagnosticAlertInfo(
                            type = DiagnosticAlertType.INTERNET_ERROR,
                            title = "📡 تنبيه: مشكلة في اتصال الإنترنت لديك",
                            message = "تعذّر تحديث القوائم بسبب انقطاع الإنترنت على جهازك.\nيرجى التحقق من اتصال الواي فاي (Wi-Fi) أو كابل الشبكة والراوتر ثم المحاولة مجدداً. (تم الاحتفاظ بجميع القنوات المخزنة محلياً)."
                        )
                    } else {
                        DiagnosticAlertInfo(
                            type = DiagnosticAlertType.SERVER_OUTAGE,
                            title = "🛠️ تنبيه حالة الخادم: انقطاع مؤقت من المصدر",
                            message = "يتعذّر استجابة الخادم حالياً أثناء تحديث المحتوى.\n\nيرجى التريث لمدة ساعة، ثم غلق جهاز الراوتر (المودم) وإعادة فتحه مجدداً.\nإذا لم يعد السيرفر للعمل تلقائياً، يرجى التواصل مع مزود الخدمة ($ALI_SAT_PROVIDER_NAME : $ALI_SAT_PHONE_NUMBER) وتقديم رمز الشاشة ($deviceId)."
                        )
                    }
                }
                if (subscription?.isExpired != true && !vpnStatus.connected) {
                    connectVpnAutomatically()
                }
            } finally {
                MainActivity.syncLock.set(false)
                isLoading = false
                syncProgress = syncProgress.copy(isSyncing = false)
            }
        }
    }

    /**
     * Updates ONLY the subscription expiry dates for the Player (api.php) and Xtream accounts (< 1 KB)
     * WITHOUT downloading or re-syncing any channels, movies, or series!
     */
    fun refreshValidityDatesOnly(showToast: Boolean = true) {
        if (!SafeClickGuard.allowAction("REFRESH_VALIDITY_ONLY", 500L)) return
        if (isCheckingValidityOnly) return
        scope.launch {
            isCheckingValidityOnly = true
            statusMessage = "⏳ جارٍ تحديث تواريخ الصلاحية فقط (دون سحب القنوات)..."
            try {
                val (quickSub, remoteLists) = ChannelRepository.checkSubscriptionAndListsQuick(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    forceNetwork = true
                )
                subscription = quickSub
                if (quickSub.isExpired && !quickSub.active) {
                    stopVpnImmediately()
                    playingChannel = null
                    statusMessage = quickSub.message.ifBlank { "انتهت صلاحية المشغل. تواصل مع علي سات : $ALI_SAT_PHONE_NUMBER" }
                    if (showToast) {
                        Toast.makeText(context, "⛔ انتهت صلاحية اشتراك المشغل (${deviceId})", Toast.LENGTH_LONG).show()
                    }
                } else {
                    if (!vpnStatus.connected) {
                        connectVpnAutomatically()
                    }
                    val merged = xtreamPlaylists.toMutableList()
                    for (rCfg in remoteLists) {
                        val idx = merged.indexOfFirst {
                            it.id == rCfg.id ||
                                (it.serverUrl.equals(rCfg.serverUrl, ignoreCase = true) &&
                                    it.username.equals(rCfg.username, ignoreCase = true))
                        }
                        if (idx >= 0) {
                            merged[idx] = merged[idx].copy(
                                expDate = rCfg.expDate.ifBlank { merged[idx].expDate }
                            )
                        } else {
                            merged.add(rCfg)
                        }
                    }
                    // Probe live Xtream expiry dates in parallel (< 1 KB each, zero channel download!)
                    val updatedWithProbes = withContext(Dispatchers.IO) {
                        merged.map { pl ->
                            if (pl.enabled && pl.isConfigured) {
                                val (liveExp, _) = XtreamRepository.probeAccountExpiryQuick(pl, forceNetwork = true)
                                if (liveExp.isNotBlank()) pl.copy(expDate = liveExp) else pl
                            } else pl
                        }
                    }
                    xtreamPlaylists = updatedWithProbes
                    val expTxt = if (quickSub.expireDate.isNotBlank()) " • ينتهي: ${quickSub.expireDate}" else ""
                    statusMessage = "✅ تم تحديث الصلاحية • المتبقي: ${quickSub.daysLeft} يوم$expTxt"
                    persistCurrentStateSuspend(saveCatalog = false)
                    if (showToast) {
                        Toast.makeText(
                            context,
                            "✅ تم تحديث تاريخ الصلاحية فقط بنجاح: ${quickSub.daysLeft} يوم متبقٍ$expTxt",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                if (showToast) {
                    Toast.makeText(
                        context,
                        "⚠️ تعذّر فحص الصلاحية حالياً — تم الاحتفاظ بالبيانات المحلية",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } finally {
                isCheckingValidityOnly = false
            }
        }
    }

    /**
     * Syncs ONLY the Main Portal list ("PORTAL_MAIN") when the customer is in Separate Playlist Mode
     * and has selected "PORTAL_MAIN".
     */
    fun syncPortalMainOnlyNow(showToast: Boolean = true) {
        if (TiviMateLocalDatabase.isSourceSyncInProgress("PORTAL_MAIN")) return
        scope.launch {
            isLoading = true
            ensureVpnConnectedAndStableBeforeSync()
            syncProgress = SyncProgressState(
                isSyncing = true,
                sourceId = "PORTAL_MAIN",
                sourceName = "القائمة الرئيسية",
                phaseLabel = "🚀 جارٍ تحديث القائمة الرئيسية فقط...",
                progressPercent = 25
            )
            try {
                val result = ChannelRepository.syncWithServer(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    activationCode = savedActivationCode,
                    knownCatalogHash = ""
                )
                subscription = result.subscription
                if (result.subscription.active && !result.subscription.isExpired) {
                    if (result.channels.isNotEmpty() || result.movies.isNotEmpty() || result.series.isNotEmpty()) {
                        portalChannels = result.channels
                        portalMovies = result.movies
                        portalSeries = result.series
                        sqliteDb.syncSourceCatalogAtomically(
                            sourceId = "PORTAL_MAIN",
                            sourceName = "قائمة الخادم الرئيسية",
                            liveChannels = result.channels,
                            movies = result.movies,
                            series = result.series,
                            catalogEtag = result.serverCatalogHash
                        )
                    }
                    persistCurrentStateSuspend(saveCatalog = true)
                    if (showToast) {
                        Toast.makeText(
                            context,
                            "✅ تم تحديث القائمة الرئيسية فقط بنجاح (${result.channels.size} قناة)",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                if (showToast) {
                    Toast.makeText(context, "⚠️ تعذّر تحديث القائمة الرئيسية — تم الاحتفاظ بالمكتبة المحلية", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLoading = false
                syncProgress = syncProgress.copy(isSyncing = false)
            }
        }
    }

    /**
     * Syncs a user-selected subset of Xtream playlists (1 playlist, multiple playlists, or all playlists).
     */
    fun syncSelectedXtreamPlaylists(
        selectedIds: Set<String>,
        includePortalMain: Boolean = false
    ) {
        scope.launch {
            ensureVpnConnectedAndStableBeforeSync()
            val targets = orderedPlaylists.filter { it.id in selectedIds && it.isConfigured }
            if (targets.size == 1 && !includePortalMain) {
                val onlyPl = targets.first()
                preferences = preferences.copy(
                    playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                    activePlaylistFilterId = onlyPl.id
                )
                syncSingleXtreamPlaylistSuspend(onlyPl, showToast = true)
                return@launch
            }
            for ((idx, cfg) in targets.withIndex()) {
                val isLast = (idx == targets.lastIndex) && !includePortalMain
                syncSingleXtreamPlaylistSuspend(cfg, showToast = isLast)
            }
            if (includePortalMain) {
                syncPortalMainOnlyNow(showToast = true)
            } else if (targets.size > 1) {
                Toast.makeText(
                    context,
                    "✅ تم تحديث القوائم المختارة (${targets.size} قوائم) وحفظها في ذاكرة التطبيق بنجاح!",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Context-Aware Smart Content Refresh ("تحديث المحتوى"):
     * - If in SEPARATE_LISTS mode and a specific Xtream playlist is active -> refreshes ONLY that single Xtream playlist!
     * - If in SEPARATE_LISTS mode and PORTAL_MAIN is active -> refreshes ONLY the Main Portal playlist!
     * - If in MERGE_ALL mode (or "ALL" selected) -> refreshes all playlists!
     */
    fun triggerSmartContentRefresh() {
        if (!SafeClickGuard.allowAction("SMART_REFRESH_BTN", 450L)) return
        val activeFilter = preferences.activePlaylistFilterId
        if (preferences.playlistDisplayMode == PlaylistDisplayMode.SEPARATE_LISTS && activeFilter != "ALL") {
            if (activeFilter == "PORTAL_MAIN") {
                syncPortalMainOnlyNow(showToast = true)
                return
            }
            val targetXtream = orderedPlaylists.find { it.id == activeFilter }
            if (targetXtream != null) {
                syncSingleXtreamPlaylist(targetXtream, showToast = true)
                return
            }
        }
        // Merged mode ("MERGE_ALL" or "ALL"): refresh all playlists
        syncServerNow(forceXtreamRefresh = true)
    }

    fun redeemDirectCode(inputCode: String) {
        if (!SafeClickGuard.allowAction("REDEEM_VOUCHER", 600L)) return
        val trimmed = inputCode.trim().uppercase()
        if (trimmed.length < 4) {
            voucherAlert = VoucherAlertInfo(
                type = VoucherAlertType.INVALID_OR_NOT_FOUND,
                title = "⚠️ يرجى إدخال رمز التفعيل أولاً",
                message = "الرجاء كتابة رمز القسيمة المكون من 8 أحرف وأرقام في الخانة المخصصة (مثال: A1B2-C3D4) ثم الضغط على زر التفعيل.",
                enteredCode = trimmed
            )
            return
        }
        scope.launch {
            isLoading = true
            syncProgress = SyncProgressState(
                isSyncing = true,
                sourceId = "VOUCHER",
                sourceName = "تفعيل الاشتراك",
                phaseLabel = "⏳ جارٍ التحقق من رمز التفعيل ($trimmed) وتنشيط الاشتراك...",
                progressPercent = 50
            )
            statusMessage = "جارٍ التحقق من رمز التنشيط..."
            try {
                val result = ChannelRepository.redeemActivationCode(deviceId, deviceName, trimmed)
                val rawSubMsg = result.subscription.message
                val cleanSubMsg = rawSubMsg.substringBefore("|")
                val addedDays = rawSubMsg.substringAfter("ADDED:", "0").substringBefore("|").toIntOrNull() ?: 0
                val newExp = rawSubMsg.substringAfter("EXP:", "").substringBefore("|").ifBlank { result.subscription.expireDate }
                val cleanFormattedCode = rawSubMsg.substringAfter("CODE:", trimmed).substringBefore("|")

                subscription = result.subscription.copy(message = cleanSubMsg)
                if (result.subscription.active && !result.subscription.isExpired) {
                    savedActivationCode = cleanFormattedCode
                    if (result.channels.isNotEmpty()) portalChannels = result.channels
                    if (result.movies.isNotEmpty()) portalMovies = result.movies
                    if (result.series.isNotEmpty()) portalSeries = result.series
                    showUnifiedValidityModal = false
                    voucherAlert = VoucherAlertInfo(
                        type = VoucherAlertType.SUCCESS,
                        title = "🎉 تم تفعيل وتجديد الاشتراك بنجاح!",
                        message = "$cleanSubMsg\nتم تفعيل شاشتك ($deviceId) بنجاح ويمكنك الآن الاستمتاع بجميع القنوات والأفلام والمسلسلات.",
                        enteredCode = cleanFormattedCode,
                        addedDays = addedDays,
                        newExpireDate = newExp
                    )
                    if (!vpnStatus.connected) {
                        connectVpnAutomatically()
                    }
                    persistCurrentStateSuspend(saveCatalog = result.channels.isNotEmpty())
                } else {
                    stopVpnImmediately()
                    voucherAlert = VoucherAlertInfo(
                        type = VoucherAlertType.INVALID_OR_NOT_FOUND,
                        title = "❌ تعذّر تفعيل رمز القسيمة",
                        message = if (cleanSubMsg.isNotBlank()) cleanSubMsg else "رمز التنشيط غير صالح أو منتهي الصلاحية.",
                        enteredCode = cleanFormattedCode
                    )
                }
            } catch (e: Exception) {
                val rawErr = e.message ?: "CARD_NOT_FOUND|تعذّر التحقق من رمز التنشيط"
                val tag = if (rawErr.contains("|")) rawErr.substringBefore("|") else "CARD_NOT_FOUND"
                val errBody = if (rawErr.contains("|")) rawErr.substringAfter("|") else rawErr
                statusMessage = errBody
                val alertType = when (tag) {
                    "CARD_USED" -> VoucherAlertType.USED_CARD
                    "CARD_EXPIRED" -> VoucherAlertType.EXPIRED_CARD
                    "RATE_LIMITED" -> VoucherAlertType.RATE_LIMITED
                    "NETWORK_ERROR" -> VoucherAlertType.NETWORK_ERROR
                    else -> VoucherAlertType.INVALID_OR_NOT_FOUND
                }
                val alertTitle = when (alertType) {
                    VoucherAlertType.USED_CARD -> "⛔ هذه القسيمة مستخدمة ومحروقة مسبقاً!"
                    VoucherAlertType.EXPIRED_CARD -> "⛔ انتهت صلاحية بطاقة التفعيل"
                    VoucherAlertType.RATE_LIMITED -> "⏳ تنبيه الحماية: محاولات متكررة"
                    VoucherAlertType.NETWORK_ERROR -> "📡 تعذّر الاتصال بخادم التفعيل"
                    else -> "❌ رمز التفعيل غير صحيح أو غير موجود"
                }
                voucherAlert = VoucherAlertInfo(
                    type = alertType,
                    title = alertTitle,
                    message = errBody,
                    enteredCode = trimmed
                )
            } finally {
                isLoading = false
                syncProgress = syncProgress.copy(isSyncing = false, phaseLabel = "")
            }
        }
    }

    LaunchedEffect(Unit) {
        isSortingLocalLibrary = true
        val startLoadMs = android.os.SystemClock.elapsedRealtime()
        val loaded = StorageManager.loadState(context)
        deviceId = loaded.deviceId
        deviceName = loaded.deviceName
        savedActivationCode = loaded.activationCode
        xtreamPlaylists = loaded.xtreamPlaylists
        favorites = loaded.favorites
        history = loaded.history
        subscription = loaded.subscription ?: SubscriptionInfo(active = true, isExpired = false, daysLeft = 30, reason = "ok")
        preferences = loaded.preferences
        vodWatchProgressMap = loaded.vodWatchProgress
        MultiQualityEngine.ensureLoaded(context)

        // Server favorites restore for this Screen Code only — never creates a second list
        runCatching {
            val remoteFavs = ChannelRepository.pullFavoritesFromServer(loaded.deviceId)
            if (remoteFavs != null) {
                val merged = if (loaded.favorites.isEmpty()) {
                    remoteFavs
                } else {
                    val seen = LinkedHashSet<String>()
                    val out = ArrayList<TvChannel>(loaded.favorites.size + remoteFavs.size)
                    for (ch in loaded.favorites + remoteFavs) {
                        val key = when {
                            ch.contentType == StreamContentType.SERIES && ch.seriesId > 0 ->
                                "S|${ch.playlistId}|${ch.seriesId}"
                            else -> ch.stableKey
                        }
                        if (seen.add(key)) out.add(ch)
                    }
                    out
                }
                if (merged.size != favorites.size || merged.map { it.stableKey } != favorites.map { it.stableKey }) {
                    favorites = merged
                    // persist after initialized flag — may be early; safe write via StorageManager path later
                }
                if (merged.isNotEmpty()) {
                    runCatching { ChannelRepository.pushFavoritesToServer(loaded.deviceId, merged) }
                }
            } else if (loaded.favorites.isNotEmpty()) {
                runCatching { ChannelRepository.pushFavoritesToServer(loaded.deviceId, loaded.favorites) }
            }
        }

        // StorageManager.loadState already loaded SQLite catalog + JSON fallback in one fast pass!
        var hasCachedCatalog = false
        val effLive = loaded.cachedChannels
        val effMovies = loaded.cachedMovies
        val effSeries = loaded.cachedSeries
        hasCachedCatalog = effLive.isNotEmpty() || effMovies.isNotEmpty() || effSeries.isNotEmpty()

        // Stage 1: Partition Live TV first and open UI IMMEDIATELY (<100ms)
        if (effLive.isNotEmpty()) {
            withContext(Dispatchers.Default) {
                val pCh = ArrayList<TvChannel>()
                val gX = LinkedHashMap<String, MutableList<TvChannel>>()
                for (item in effLive) {
                    if (item.playlistId == "PORTAL_MAIN") {
                        pCh.add(item)
                    } else {
                        gX.getOrPut(item.playlistId) { ArrayList() }.add(item)
                    }
                }
                withContext(Dispatchers.Main) {
                    portalChannels = pCh
                    xtreamChannelsMap = gX
                }
            }
        }
        initialized = true
        isSortingLocalLibrary = false

        // Stage 2: Hydrate VOD Movies & Series in parallel in the background without delaying app open!
        if (effMovies.isNotEmpty() || effSeries.isNotEmpty()) {
            scope.launch(Dispatchers.Default) {
                if (effMovies.isNotEmpty()) {
                    val pMv = ArrayList<TvChannel>()
                    val gMv = LinkedHashMap<String, MutableList<TvChannel>>()
                    for (item in effMovies) {
                        if (item.playlistId == "PORTAL_MAIN") {
                            pMv.add(item)
                        } else {
                            gMv.getOrPut(item.playlistId) { ArrayList() }.add(item)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        portalMovies = pMv
                        xtreamMoviesMap = gMv
                    }
                }
                if (effSeries.isNotEmpty()) {
                    val pSr = ArrayList<TvChannel>()
                    val gSr = LinkedHashMap<String, MutableList<TvChannel>>()
                    for (item in effSeries) {
                        if (item.playlistId == "PORTAL_MAIN") {
                            pSr.add(item)
                        } else {
                            gSr.getOrPut(item.playlistId) { ArrayList() }.add(item)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        portalSeries = pSr
                        xtreamSeriesMap = gSr
                    }
                }
            }
        }

        // Check if any saved Xtream account has an expired date to notify customer gracefully
        val expiredSavedPlaylist = loaded.xtreamPlaylists.firstOrNull {
            it.enabled && it.expDate.isNotBlank() && ExpiryAlertHelper.isDateAlreadyExpired(it.expDate)
        }
        if (expiredSavedPlaylist != null) {
            diagnosticAlert = DiagnosticAlertInfo(
                type = DiagnosticAlertType.ACCOUNT_EXPIRED,
                title = "⚠️ انتهت صلاحية حساب القنوات (${expiredSavedPlaylist.serverName})",
                message = "انتهت فترة اشتراك القائمة (${expiredSavedPlaylist.serverName}) بتاريخ ${expiredSavedPlaylist.expDate}.\nيرجى تجديد اشتراك الحساب عبر التواصل مع مزود الخدمة ($ALI_SAT_PROVIDER_NAME : $ALI_SAT_PHONE_NUMBER) وتقديم رمز الشاشة ($deviceId)، أو تحديث بيانات القائمة من فضاء الزبون.",
                sourceName = expiredSavedPlaylist.serverName,
                expDate = expiredSavedPlaylist.expDate
            )
        }

        // Always reset session-only temporary pause on fresh app launch so VPN starts automatically!
        vpnPausedTemporarily = false
        LiveProGlobalActions.loadVpnPrefs(context)
        if (LiveProGlobalActions.vpnPermanentDisable) {
            browseOfflineAllowed = true
            CloudflareWarpVpnService.stopTunnelSafe(context)
            vpnStatus = CloudflareTunnelStatus(
                connected = false,
                systemVpnActive = false,
                statusText = "🛑 الـ VPN متوقف دائماً (القائمة الرئيسية مخفية تلقائياً لمنع سحب الروابط)"
            )
        }

        if (hasCachedCatalog) {
            // SUBSEQUENT LAUNCH: Open INSTANTLY from local SQLite/storage WITHOUT re-downloading channels, movies, or series!
            statusMessage = "جاهز للتشغيل الفوري من ذاكرة التطبيق • للتحديث اضغط (تحديث المحتوى)"
            if (LiveProGlobalActions.vpnAutoStartOnLaunch && !LiveProGlobalActions.vpnPermanentDisable && subscription?.isExpired != true) {
                ensureVpnConnectedAndStableBeforeSync()
            }
        } else {
            // FIRST-TIME LAUNCH (Local library is empty):
            // STRICT ORDER:
            // Phase 1: Activate & verify automatic VPN FIRST (wait for permission dialog + WireGuard tun0 stabilization)
            if (LiveProGlobalActions.vpnAutoStartOnLaunch && !LiveProGlobalActions.vpnPermanentDisable && subscription?.isExpired != true) {
                statusMessage = "🛡️ الخطوة 1/2: جارٍ تشغيل وتأمين الـ VPN التلقائي أولاً..."
                syncProgress = SyncProgressState(
                    isSyncing = true,
                    sourceId = "VPN_FIRST",
                    sourceName = "تأمين الاتصال VPN",
                    phaseLabel = "🛡️ جارٍ تشغيل الـ VPN التلقائي وتثبيت الاتصال أولاً قبل سحب القوائم...",
                    progressPercent = 12
                )
                ensureVpnConnectedAndStableBeforeSync()
            }

            // Phase 2: ONLY AFTER VPN is 100% connected and stable, restore & download playlists, bouquets, and channels!
            statusMessage = "🚀 الخطوة 2/2: تم تأمين الاتصال — جارٍ تحميل وتحديث الباقات والقنوات..."
            val restoredOk = runCatching {
                val (quickSub, remoteLists) = ChannelRepository.checkSubscriptionAndListsQuick(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    forceNetwork = true
                )
                subscription = quickSub
                if (quickSub.isExpired && !quickSub.active) {
                    stopVpnImmediately()
                    syncProgress = syncProgress.copy(isSyncing = false)
                    return@runCatching true
                }
                if (remoteLists.isNotEmpty()) {
                    val merged = xtreamPlaylists.toMutableList()
                    for (rCfg in remoteLists) {
                        val existingIdx = merged.indexOfFirst {
                            it.id == rCfg.id ||
                                (it.serverUrl.equals(rCfg.serverUrl, ignoreCase = true) &&
                                    it.username.equals(rCfg.username, ignoreCase = true))
                        }
                        if (existingIdx >= 0) {
                            merged[existingIdx] = rCfg.copy(id = merged[existingIdx].id)
                        } else {
                            merged.add(rCfg)
                        }
                    }
                    xtreamPlaylists = merged
                    persistCurrentStateSuspend(saveCatalog = false)
                }
                val configuredLists = xtreamPlaylists.filter { it.enabled && it.isConfigured }
                if (configuredLists.size > 1) {
                    syncProgress = syncProgress.copy(isSyncing = false)
                    isFirstTimeSelectorMode = true
                    showMultiPlaylistSelectorModal = true
                    statusMessage = "اختر القائمة أو القوائم المراد تحميلها وتحديثها..."
                    true
                } else {
                    false
                }
            }.getOrDefault(false)

            if (!restoredOk) {
                syncServerNow(forceXtreamRefresh = true)
            }
        }
    }

    // Real-time API Validity & Domain-Change Sync on App Resume (ONLY after VPN is stable, and never colliding with first-time sync!)
    val currentResumeTrigger = MainActivity.resumeCheckTrigger
    LaunchedEffect(initialized, currentResumeTrigger) {
        if (!initialized) return@LaunchedEffect
        // Wait for VPN to finish connecting first so resume checks never run mid-VPN-handshake
        ensureVpnConnectedAndStableBeforeSync()
        // If first-time initial sync is already running, do not launch a competing sync
        if (isLoading || isSyncingXtream || (allChannels.isEmpty() && currentResumeTrigger <= 1)) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            // Retry up to 3 times (every 1.5s) in case the automatic VPN is still establishing its route on app open
            var checkedOk = false
            for (attempt in 1..3) {
                if (checkedOk) break
                runCatching {
                    val (quickSub, remoteLists) = ChannelRepository.checkSubscriptionAndListsQuick(deviceId, deviceName)
                    checkedOk = true
                    withContext(Dispatchers.Main) {
                        subscription = quickSub
                        if (quickSub.isExpired && !quickSub.active) {
                            stopVpnImmediately()
                            playingChannel = null
                            statusMessage = quickSub.message.ifBlank { "انتهت صلاحية المشغل. تواصل مع علي سات : $ALI_SAT_PHONE_NUMBER" }
                        } else {
                            val expTxt = if (quickSub.expireDate.isNotBlank()) " • ينتهي: ${quickSub.expireDate}" else ""
                            statusMessage = "متصل • الصلاحية المتبقية: ${quickSub.daysLeft} يوم$expTxt"

                            if (remoteLists.isNotEmpty()) {
                                val merged = xtreamPlaylists.toMutableList()

                                val brandNewLists = mutableListOf<XtreamAccountConfig>()
                                val domainChangedLists = mutableListOf<Pair<XtreamAccountConfig, String>>()

                                for (rCfg in remoteLists) {
                                    // First match by exact playlist ID (so if user changed domain/host in customer_portal.php, we detect the domain change!)
                                    val idxById = merged.indexOfFirst { it.id == rCfg.id }
                                    val idx = if (idxById >= 0) {
                                        idxById
                                    } else {
                                        merged.indexOfFirst {
                                            it.serverUrl.equals(rCfg.serverUrl, ignoreCase = true) &&
                                                it.username.equals(rCfg.username, ignoreCase = true)
                                        }
                                    }

                                    if (idx >= 0) {
                                        val oldCfg = merged[idx]
                                        val oldNormUrl = XtreamRepository.normalizeServerUrl(oldCfg.serverUrl)
                                        val newNormUrl = XtreamRepository.normalizeServerUrl(rCfg.serverUrl)
                                        val domainOrCredsChanged = !oldNormUrl.equals(newNormUrl, ignoreCase = true) ||
                                            oldCfg.username.trim() != rCfg.username.trim() ||
                                            (rCfg.password.isNotBlank() && oldCfg.password.trim() != rCfg.password.trim())

                                        val updatedCfg = rCfg.copy(
                                            id = oldCfg.id,
                                            serverUrl = newNormUrl.ifBlank { rCfg.serverUrl },
                                            liveCount = oldCfg.liveCount,
                                            movieCount = oldCfg.movieCount,
                                            seriesCount = oldCfg.seriesCount,
                                            expDate = rCfg.expDate.ifBlank { oldCfg.expDate },
                                            statusNote = if (domainOrCredsChanged) "⚡ تم تحديث الدومين تلقائياً ✓" else oldCfg.statusNote
                                        )
                                        merged[idx] = updatedCfg
                                        if (domainOrCredsChanged) {
                                            domainChangedLists.add(updatedCfg to oldNormUrl)
                                        }
                                    } else {
                                        merged.add(rCfg)
                                        brandNewLists.add(rCfg)
                                    }
                                }

                                xtreamPlaylists = merged

                                // 1. If the customer changed the domain/host or credentials of an existing playlist in customer_portal.php:
                                //    Instantly update all cached channels/movies/series in SQLite & RAM with the new domain (<15ms),
                                //    AND if the playlist had 0 channels cached, sync it automatically!
                                for ((updatedCfg, oldBaseUrl) in domainChangedLists) {
                                    val newBase = XtreamRepository.normalizeServerUrl(updatedCfg.serverUrl)
                                    val uEnc = java.net.URLEncoder.encode(updatedCfg.username.trim(), "UTF-8")
                                    val pEnc = java.net.URLEncoder.encode(updatedCfg.password.trim(), "UTF-8")
                                    sqliteDb.updatePlaylistCredentialsInPlace(
                                        playlistId = updatedCfg.id,
                                        newServerName = updatedCfg.serverName,
                                        newBaseUrl = newBase,
                                        newUsernameEnc = uEnc,
                                        newPasswordEnc = pEnc,
                                        oldBaseUrl = oldBaseUrl
                                    )
                                    // Update in-memory maps immediately so playing channels use the new domain right away without re-downloading!
                                    xtreamChannelsMap[updatedCfg.id]?.let { chList ->
                                        xtreamChannelsMap = xtreamChannelsMap + (updatedCfg.id to chList.map { ch ->
                                            ch.copy(
                                                playlistName = updatedCfg.serverName,
                                                streamBaseUrl = newBase,
                                                streamUsername = uEnc,
                                                streamPassword = pEnc,
                                                url = if (oldBaseUrl.isNotBlank()) ch.url.replace(oldBaseUrl, newBase, ignoreCase = true) else ch.url
                                            )
                                        })
                                    }
                                    xtreamMoviesMap[updatedCfg.id]?.let { mvList ->
                                        xtreamMoviesMap = xtreamMoviesMap + (updatedCfg.id to mvList.map { mv ->
                                            mv.copy(
                                                playlistName = updatedCfg.serverName,
                                                streamBaseUrl = newBase,
                                                streamUsername = uEnc,
                                                streamPassword = pEnc,
                                                url = if (oldBaseUrl.isNotBlank()) mv.url.replace(oldBaseUrl, newBase, ignoreCase = true) else mv.url
                                            )
                                        })
                                    }
                                    xtreamSeriesMap[updatedCfg.id]?.let { srList ->
                                        xtreamSeriesMap = xtreamSeriesMap + (updatedCfg.id to srList.map { sr ->
                                            sr.copy(
                                                playlistName = updatedCfg.serverName,
                                                streamBaseUrl = newBase,
                                                streamUsername = uEnc,
                                                streamPassword = pEnc,
                                                url = if (oldBaseUrl.isNotBlank()) sr.url.replace(oldBaseUrl, newBase, ignoreCase = true) else sr.url
                                            )
                                        })
                                    }
                                    if (xtreamChannelsMap[updatedCfg.id].isNullOrEmpty()) {
                                        syncSingleXtreamPlaylist(updatedCfg, showToast = true)
                                    }
                                }

                                // 2. If a brand-new playlist was added in customer_portal.php (and we're not in the first-time selector modal), sync only that new playlist!
                                if (!showMultiPlaylistSelectorModal) {
                                    if (brandNewLists.size > 1 && allChannels.isEmpty()) {
                                        isFirstTimeSelectorMode = true
                                        showMultiPlaylistSelectorModal = true
                                    } else {
                                        for (newCfg in brandNewLists) {
                                            if (newCfg.enabled && newCfg.isConfigured) {
                                                syncSingleXtreamPlaylist(newCfg, showToast = true)
                                            }
                                        }
                                    }
                                }
                            }

                            // 3. Also probe real-time Xtream expiry dates in the background (<1 KB per list)
                            //    so the customer always sees the live Xtream account expiry date without re-downloading channels!
                            scope.launch(Dispatchers.IO) {
                                var updatedAnyExp = false
                                val refreshedLists = xtreamPlaylists.map { pl ->
                                    if (pl.enabled && pl.isConfigured) {
                                        val (realExp, _) = XtreamRepository.probeAccountExpiryQuick(pl)
                                        if (realExp.isNotBlank() && realExp != pl.expDate) {
                                            updatedAnyExp = true
                                            pl.copy(expDate = realExp)
                                        } else pl
                                    } else pl
                                }
                                if (updatedAnyExp) {
                                    withContext(Dispatchers.Main) {
                                        xtreamPlaylists = refreshedLists
                                        persistCurrentState(saveCatalog = false)
                                    }
                                }
                            }
                        }
                        persistCurrentState(saveCatalog = false)
                    }
                }.onFailure {
                    delay(1500L)
                }
            }
        }
    }

    LaunchedEffect(initialized, preferences.showNetworkSpeed, playingChannel) {
        if (!initialized || !preferences.showNetworkSpeed || playingChannel != null) return@LaunchedEffect
        while (true) {
            val nextSpeed = NetworkSpeedMonitor.sampleCurrentSpeedLabel()
            if (nextSpeed != liveNetworkSpeed) liveNetworkSpeed = nextSpeed
            delay(4500L)
        }
    }

    // Automatic Real-Time Customer Portal Watcher (Two-Way Live Sync):
    // Detects any new/edited/deleted playlist uploaded via customer_portal.php while the app is open,
    // shows an immediate notification, and downloads ONLY the new playlist into local SQLite!
    LaunchedEffect(initialized, deviceId) {
        if (!initialized || deviceId.isBlank()) return@LaunchedEffect
        while (true) {
            delay(120_000L)
            if (isLoading || isSyncingXtream || playingChannel != null) continue
            try {
                val (quickSub, remoteLists) = ChannelRepository.checkSubscriptionAndListsQuick(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    forceNetwork = false
                )
                subscription = quickSub
                if (quickSub.active && !quickSub.isExpired) {
                    val currentLists = xtreamPlaylists.toMutableList()
                    var stateChanged = false

                    val newlyDetectedToSync = mutableListOf<XtreamAccountConfig>()
                    for (rCfg in remoteLists) {
                        val idx = currentLists.indexOfFirst {
                            it.id == rCfg.id ||
                                (it.serverUrl.equals(rCfg.serverUrl, ignoreCase = true) &&
                                    it.username.equals(rCfg.username, ignoreCase = true))
                        }
                        if (idx < 0) {
                            currentLists.add(rCfg)
                            newlyDetectedToSync.add(rCfg)
                            stateChanged = true
                        } else {
                            val existing = currentLists[idx]
                            if (existing.enabled != rCfg.enabled || existing.serverName != rCfg.serverName || existing.orderIndex != rCfg.orderIndex) {
                                currentLists[idx] = existing.copy(
                                    serverName = rCfg.serverName.ifBlank { existing.serverName },
                                    enabled = rCfg.enabled,
                                    orderIndex = rCfg.orderIndex,
                                    expDate = rCfg.expDate.ifBlank { existing.expDate }
                                )
                                stateChanged = true
                            }
                        }
                    }

                    if (stateChanged) {
                        xtreamPlaylists = currentLists.sortedBy { it.orderIndex }
                        persistCurrentStateSuspend(saveCatalog = false)
                    }

                    for (newPl in newlyDetectedToSync) {
                        if (newPl.enabled && newPl.isConfigured) {
                            Toast.makeText(
                                context,
                                "📲 تم رصد قائمة جديدة من فضاء الزبون (${newPl.serverName}) — جارٍ التحميل والحفظ...",
                                Toast.LENGTH_LONG
                            ).show()
                            syncSingleXtreamPlaylistSuspend(newPl, showToast = true)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    // Immediately hide PORTAL_MAIN from active playback and filter whenever VPN is off!
    LaunchedEffect(isVpnActiveAndProtected, orderedPlaylists) {
        if (!initialized) return@LaunchedEffect
        if (!isVpnActiveAndProtected) {
            hidePortalMainOnVpnOff()
        }
    }

    LaunchedEffect(initialized) {
        if (!initialized) return@LaunchedEffect
        while (true) {
            delay(4500L)
            if (isVpnPermissionDialogShowing) continue
            if (vpnPausedTemporarily || LiveProGlobalActions.vpnPermanentDisable) {
                hidePortalMainOnVpnOff()
                continue
            }
            val internalExclusive = CloudflareDohManager.isInternalAppVpnExclusivelyActive(context)
            val externalHijack = CloudflareDohManager.isSystemVpnActive(context) && !internalExclusive
            if (externalHijack) {
                playingChannel = null
                browseOfflineAllowed = false
                ChannelRepository.cancelPendingStreamRequests()
                vpnStatus = CloudflareTunnelStatus(
                    connected = false,
                    systemVpnActive = false,
                    statusText = "⛔ تم رصد VPN خارجي! يشتغل التطبيق حصرياً عبر نفق الـ VPN الداخلي المدمج — اضغط لإعادة تفعيل نفق التطبيق"
                )
            } else if (!internalExclusive && vpnStatus.connected) {
                hidePortalMainOnVpnOff()
                vpnStatus = vpnStatus.copy(
                    connected = false,
                    statusText = "تم قطع اتصال نفق الـ VPN الداخلي للتطبيق — يرجى إعادة الاتصال للمتابعة"
                )
            } else if (internalExclusive && !vpnStatus.connected) {
                vpnStatus = CloudflareDohManager.verifyAndActivateCloudflareTunnel(context)
            }
        }
    }

    BackHandler(enabled = playingChannel == null) {
        when {
            pendingResumeChannel != null -> pendingResumeChannel = null
            selectedSeriesForEpisodes != null -> selectedSeriesForEpisodes = null
            preferences.uiLayoutStyle == UiLayoutStyle.CLASSIC_LIVE_PRO && !classicProShowDashboard -> {
                classicProShowDashboard = true
            }
            else -> showExitConfirmation = !showExitConfirmation
        }
    }

    /**
     * Resolves any Series item (whether from Favorites, Recently Watched / History, Continue Watching,
     * an active episode in PlayerScreen, or an M3U/Xtream catalog) back to its full parent Series
     * TvChannel so opening it ALWAYS displays all Seasons (الأجزاء/المواسم) and Episodes (الحلقات).
     */
    fun resolveParentSeriesChannel(ch: TvChannel): TvChannel {
        if (ch.contentType != StreamContentType.SERIES) return ch

        val urlSid = Regex("[?&]series_id=(\\d+)", RegexOption.IGNORE_CASE)
            .find(ch.url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val effectiveSid = if (ch.seriesId > 0) ch.seriesId else urlSid

        if (effectiveSid > 0) {
            val fromCatalog = allSeries.firstOrNull { it.seriesId == effectiveSid && it.playlistId == ch.playlistId }
                ?: allSeries.firstOrNull { it.seriesId == effectiveSid }
            if (fromCatalog != null) return fromCatalog
            val matchingPl = orderedPlaylists.firstOrNull { it.id == ch.playlistId }
                ?: orderedPlaylists.firstOrNull { it.isConfigured }
            return ch.copy(
                seriesId = effectiveSid,
                streamBaseUrl = ch.streamBaseUrl.ifBlank { matchingPl?.serverUrl?.trim()?.removeSuffix("/").orEmpty() },
                streamUsername = ch.streamUsername.ifBlank { matchingPl?.username?.trim().orEmpty() },
                streamPassword = ch.streamPassword.ifBlank { matchingPl?.password?.trim().orEmpty() }
            )
        }

        // If ch is an episode channel (seriesId == 0), check lastSelectedSeriesParent first:
        val activeParent = lastSelectedSeriesParent
        if (activeParent != null && (activeParent.seriesId > 0 || activeParent.episodes.isNotEmpty())) {
            if (ch.name.equals(activeParent.name, ignoreCase = true) ||
                ch.name.startsWith(activeParent.name + " - ", ignoreCase = true)
            ) {
                return activeParent
            }
        }

        // Strip episode suffixes like " - الموسم 1 - الحلقة 2" or "S01E02" to find the parent Series in allSeries:
        val prefixBeforeDash = ch.name.substringBefore(" - ").trim()
        val strippedSeriesTitle = prefixBeforeDash.replace(
            Regex("\\s*(?:[sS]\\d{1,2}\\s*[eExX]\\d{1,3}|الجزء\\s*\\d+|الموسم\\s*\\d+|حلقة\\s*\\d+|الحلقة\\s*\\d+).*$"),
            ""
        ).trim().ifBlank { prefixBeforeDash }

        val matchedParent = allSeries.firstOrNull {
            it.seriesId > 0 && it.playlistId == ch.playlistId &&
                (it.name.equals(ch.name, ignoreCase = true) ||
                    it.name.equals(prefixBeforeDash, ignoreCase = true) ||
                    it.name.equals(strippedSeriesTitle, ignoreCase = true) ||
                    ch.name.startsWith(it.name + " - ", ignoreCase = true))
        } ?: allSeries.firstOrNull {
            it.seriesId > 0 &&
                (it.name.equals(ch.name, ignoreCase = true) ||
                    it.name.equals(prefixBeforeDash, ignoreCase = true) ||
                    it.name.equals(strippedSeriesTitle, ignoreCase = true) ||
                    ch.name.startsWith(it.name + " - ", ignoreCase = true))
        }
        if (matchedParent != null) return matchedParent

        // Fallback for M3U / Portal Series where episodes are listed individually in allSeries with seriesId == 0:
        if (strippedSeriesTitle.length >= 2) {
            val siblingRawEpisodes = allSeries.filter { item ->
                item.seriesId == 0 && item.playlistId == ch.playlistId && (
                    item.name.equals(strippedSeriesTitle, ignoreCase = true) ||
                        item.name.startsWith(strippedSeriesTitle, ignoreCase = true)
                    )
            }
            if (siblingRawEpisodes.isNotEmpty()) {
                val builtEpisodes = siblingRawEpisodes.mapIndexed { idx, epItem ->
                    val sMatch = Regex("(?:[sS]|الموسم\\s*|الجزء\\s*)(\\d{1,2})", RegexOption.IGNORE_CASE).find(epItem.name)
                    val eMatch = Regex("(?:[eExX]|الحلقة\\s*|حلقة\\s*)(\\d{1,3})", RegexOption.IGNORE_CASE).find(epItem.name)
                    val sNum = sMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                    val eNum = eMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: (idx + 1)
                    SeriesEpisode(
                        id = epItem.streamId.takeIf { it > 0 }?.toString() ?: "${sNum}_$eNum",
                        seasonNumber = sNum,
                        episodeNumber = eNum,
                        title = epItem.name,
                        url = epItem.url,
                        containerExtension = epItem.containerExtension.ifBlank { "mp4" }
                    )
                }
                val rep = siblingRawEpisodes.first()
                return rep.copy(
                    name = strippedSeriesTitle,
                    episodes = builtEpisodes
                )
            }
        }

        return ch
    }

    // Automatically heal any legacy single-episode series entries in Favorites or Recently Watched (history)
    // into their full parent Series as soon as allSeries is available!
    LaunchedEffect(allSeries.size, initialized) {
        if (!initialized || allSeries.isEmpty()) return@LaunchedEffect
        val needsHeal = favorites.any { it.contentType == StreamContentType.SERIES && it.seriesId <= 0 } ||
            history.any { it.contentType == StreamContentType.SERIES && it.seriesId <= 0 }
        if (!needsHeal) return@LaunchedEffect
        var favChanged = false
        val healedFavs = favorites.map { item ->
            if (item.contentType == StreamContentType.SERIES && item.seriesId <= 0) {
                val resolved = resolveParentSeriesChannel(item)
                if (resolved.seriesId > 0 || resolved.episodes.isNotEmpty()) {
                    favChanged = true
                    resolved
                } else item
            } else item
        }.distinctBy {
            if (it.contentType == StreamContentType.SERIES && it.seriesId > 0) "SERIES_${it.playlistId}_${it.seriesId}" else it.stableKey
        }
        if (favChanged || healedFavs.size != favorites.size) {
            favorites = healedFavs
        }

        var histChanged = false
        val healedHist = history.map { item ->
            if (item.contentType == StreamContentType.SERIES && item.seriesId <= 0) {
                val resolved = resolveParentSeriesChannel(item)
                if (resolved.seriesId > 0 || resolved.episodes.isNotEmpty()) {
                    histChanged = true
                    resolved
                } else item
            } else item
        }.distinctBy {
            if (it.contentType == StreamContentType.SERIES && it.seriesId > 0) "SERIES_${it.playlistId}_${it.seriesId}" else it.stableKey
        }
        if (histChanged || healedHist.size != history.size) {
            history = healedHist
        }
        if (favChanged || histChanged) {
            persistCurrentState(saveCatalog = false)
        }
    }

    fun saveVodProgress(ch: TvChannel, posMs: Long, durMs: Long) {
        if (ch.contentType == StreamContentType.LIVE_TV) return
        if (posMs < 2_000L) return
        val nowMs = System.currentTimeMillis()
        val cleanUrl = ch.url.trim()
        val entry = VodWatchProgress(
            itemKey = cleanUrl.ifBlank { ch.stableKey },
            stableKey = ch.stableKey,
            title = ch.name,
            positionMs = posMs.coerceAtLeast(0L),
            durationMs = durMs.coerceAtLeast(0L),
            updatedAt = nowMs
        )
        val updated = LinkedHashMap(vodWatchProgressMap)
        updated[ch.stableKey] = entry
        if (cleanUrl.isNotBlank()) {
            updated[cleanUrl] = entry.copy(itemKey = cleanUrl, stableKey = ch.stableKey)
        }
        val parentSeries = if (ch.contentType == StreamContentType.SERIES) {
            lastSelectedSeriesParent ?: resolveParentSeriesChannel(ch)
        } else null
        if (ch.contentType == StreamContentType.SERIES && parentSeries != null) {
            val seriesEntry = entry.copy(
                itemKey = cleanUrl.ifBlank { ch.stableKey },
                stableKey = parentSeries.stableKey,
                title = ch.name
            )
            updated[parentSeries.stableKey] = seriesEntry
            if (parentSeries.seriesId > 0) {
                updated["SERIES_ID_${parentSeries.playlistId}_${parentSeries.seriesId}"] = seriesEntry
            }
        }
        vodWatchProgressMap = updated
        persistCurrentState(saveCatalog = false)
    }

    fun startPlayback(
        ch: TvChannel,
        forceResumePosMs: Long? = null,
        isDirectEpisodeFromModal: Boolean = false
    ) {
        if (!SafeClickGuard.allowAction("START_PLAYBACK_${ch.contentType.name}_${ch.seriesId}_${isDirectEpisodeFromModal}", 90L)) return
        if (ch.playlistId == "PORTAL_MAIN" && !isVpnActiveAndProtected) {
            Toast.makeText(
                context,
                "🔒 القائمة الرئيسية تتطلب تفعيل الـ VPN لحماية الروابط — اضغط على زر (🔒 VPN) لتفعيله",
                Toast.LENGTH_LONG
            ).show()
            showVpnControlModal = true
            return
        }
        if (!vpnStatus.connected && !vpnPausedTemporarily && !LiveProGlobalActions.vpnPermanentDisable) {
            if (subscription?.isExpired == true && subscription?.active == false) {
                connectVpnAutomatically(forcePrompt = true)
                return
            }
            CloudflareWarpVpnService.dohShieldFallbackActive = true
            CloudflareWarpVpnService.isTunnelRunning = true
            vpnStatus = vpnStatus.copy(connected = true)
            connectVpnAutomatically(forcePrompt = false)
        }
        // Whenever the user clicks a Series from Favorites (المفضلة), Recently Watched (المشاهدة أخيراً),
        // Continue Watching (متابعة المشاهدة), or the Series Catalog, ALWAYS resolve the parent Series
        // and open the full Series Seasons & Episodes page (SeriesEpisodesModal)!
        if (ch.contentType == StreamContentType.SERIES && !isDirectEpisodeFromModal) {
            val resolvedParent = resolveParentSeriesChannel(ch)
            if (resolvedParent.seriesId > 0 ||
                resolvedParent.episodes.isNotEmpty() ||
                resolvedParent.url.contains("action=get_series_info", ignoreCase = true)
            ) {
                lastWatchedStableKey = resolvedParent.stableKey
                lastSelectedSeriesParent = resolvedParent
                selectedSeriesForEpisodes = resolvedParent
                return
            }
        }
        if (ch.contentType != StreamContentType.SERIES || lastSelectedSeriesParent == null) {
            lastWatchedStableKey = ch.stableKey
            if (ch.contentType != StreamContentType.SERIES) {
                lastSelectedSeriesParent = null
            }
        }
        // Check if this Movie or selected Series Episode has a saved watch progress to offer Resume vs Start Over
        if (ch.contentType != StreamContentType.LIVE_TV && forceResumePosMs == null) {
            val savedProg = vodWatchProgressMap[ch.stableKey]
                ?: vodWatchProgressMap[ch.url.trim()]
            if (savedProg != null && savedProg.hasResumePoint) {
                pendingResumeChannel = ch
                return
            }
        }
        // Release mini-preview session before opening fullscreen PlayerScreen so max_connections=1
        // servers and hardware 4K decoders never experience dual-session contention or audio overlap!
        if (previewLiveChannel != null) {
            previewLiveChannel = null
        }
        activeResumePositionMs = forceResumePosMs ?: 0L
        // Always store the parent Series (with all Seasons & Episodes) in Recently Watched (history),
        // never a stripped single episode!
        val historyEntry = if (ch.contentType == StreamContentType.SERIES) {
            lastSelectedSeriesParent ?: resolveParentSeriesChannel(ch)
        } else {
            ch
        }
        history = listOf(historyEntry) + history.filterNot {
            it.stableKey == historyEntry.stableKey ||
                (historyEntry.contentType == StreamContentType.SERIES && historyEntry.seriesId > 0 &&
                    it.contentType == StreamContentType.SERIES &&
                    it.seriesId == historyEntry.seriesId &&
                    it.playlistId == historyEntry.playlistId)
        }.take(49)
        persistCurrentState()
        // Adaptive multi-quality (works for both virtual MULTI_QUALITY channels and auto-clustered regular channels):
        // Keep parent/channel identity key (`id`, `streamId`, `seriesId`, `playlistId`) intact so `stableKey` never changes,
        // and let PlayerScreen's `activeMqVariant` drive the real-time stream URL, streamId, and credentials!
        playingChannel = if (ch.contentType == StreamContentType.LIVE_TV && MultiQualityEngine.hasAdaptiveVariants(ch)) {
            val bestVar = MultiQualityEngine.pickBestVariant(ch)
            if (bestVar != null) {
                MultiQualityEngine.setActiveVariant(ch.stableKey, bestVar)
            }
            ch
        } else {
            ch
        }
    }

    fun pushFavoritesBackupNow() {
        val id = deviceId
        val snapshot = favorites
        if (id.isBlank()) return
        scope.launch(Dispatchers.IO) {
            runCatching { ChannelRepository.pushFavoritesToServer(id, snapshot) }
        }
    }

    fun toggleFavorite(ch: TvChannel) {
        val targetCh = if (ch.contentType == StreamContentType.SERIES) {
            resolveParentSeriesChannel(ch) ?: ch
        } else {
            ch
        }
        val exists = favorites.any {
            it.stableKey == targetCh.stableKey ||
                (targetCh.contentType == StreamContentType.SERIES && targetCh.seriesId > 0 &&
                    it.contentType == StreamContentType.SERIES &&
                    it.seriesId == targetCh.seriesId &&
                    it.playlistId == targetCh.playlistId)
        }
        favorites = if (exists) {
            favorites.filterNot {
                it.stableKey == targetCh.stableKey ||
                    (targetCh.contentType == StreamContentType.SERIES && targetCh.seriesId > 0 &&
                        it.contentType == StreamContentType.SERIES &&
                        it.seriesId == targetCh.seriesId &&
                        it.playlistId == targetCh.playlistId)
            }
        } else {
            listOf(targetCh) + favorites
        }
        persistCurrentState(saveCatalog = false)
        pushFavoritesBackupNow()
        val typeLabel = when (targetCh.contentType) {
            StreamContentType.LIVE_TV -> "مفضلة القنوات المباشرة 📺"
            StreamContentType.MOVIE -> "مفضلة الأفلام 🎬"
            StreamContentType.SERIES -> "مفضلة المسلسلات 🎞️"
        }
        val msg = if (exists) {
            "🗑️ تمت إزالة (${targetCh.name}) من $typeLabel"
        } else {
            "⭐ تمت إضافة (${targetCh.name}) إلى $typeLabel"
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun clearFavoritesByType(targetType: StreamContentType?) {
        favorites = if (targetType == null) {
            emptyList()
        } else {
            favorites.filterNot { it.contentType == targetType }
        }
        persistCurrentState(saveCatalog = false)
        pushFavoritesBackupNow()
        Toast.makeText(context, "✅ تم مسح عناصر المفضلة المحددة بنجاح", Toast.LENGTH_SHORT).show()
    }

    fun registerAndSyncNewXtreamAccount(cfg: XtreamAccountConfig) {
        val immediateCfg = cfg.copy(
            enabled = true,
            statusNote = cfg.statusNote.ifBlank { "مسجل في قاعدة البيانات ✓" }
        )
        xtreamPlaylists = xtreamPlaylists.map {
            if (it.id == immediateCfg.id) immediateCfg else it
        }.let { list ->
            if (list.none { it.id == immediateCfg.id }) list + immediateCfg else list
        }
        persistCurrentState(saveCatalog = false)
        scope.launch(Dispatchers.IO) {
            runCatching {
                ChannelRepository.pushCustomerXtreamToPortal(deviceId, deviceName, immediateCfg)
            }
        }
        Toast.makeText(
            context,
            "✅ تم تسجيل الحساب (${immediateCfg.serverName}) في السيرفر والجهاز — جارٍ سحب القنوات...",
            Toast.LENGTH_SHORT
        ).show()
        syncSingleXtreamPlaylist(immediateCfg, showToast = true)
    }

    val colorScheme = darkColorScheme(
        primary = BeinPrimaryPurple,
        onPrimary = Color.White,
        secondary = BeinHighlightPurple,
        background = BeinDarkBg,
        surface = BeinSurface,
        onBackground = Color.White,
        onSurface = Color.White
    )

    CompositionLocalProvider(LocalLayoutDirection provides lang.layoutDirection) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = BeinTypography
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BeinDarkBg)
            ) {
                val isPhoneLandscape = maxHeight < 480.dp || maxWidth < 720.dp
                val currentPlaying = playingChannel
                val sub = subscription

                // 1. Show Subscription Expired Gate ONLY when subscription is genuinely confirmed expired!
                //    Never show by default on startup!
                if (initialized && sub != null && sub.isExpired && !sub.active) {
                    AliSatSubscriptionExpiredGate(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        subscription = sub,
                        lang = lang,
                        isLoading = isLoading || isSyncingXtream,
                        compact = isPhoneLandscape,
                        onVerifyNow = { syncServerNow(forceXtreamRefresh = true) },
                        onRedeemCode = { redeemDirectCode(it) },
                        onRequestExit = { showExitConfirmation = true }
                    )
                }
                // 3. Mandatory VPN Gate (Shown if VPN is not connected AND user hasn't paused/disabled VPN or chosen offline browse)
                else if (!vpnStatus.connected && !browseOfflineAllowed && !vpnPausedTemporarily && !LiveProGlobalActions.vpnPermanentDisable) {
                    MandatoryCloudflareVpnGate(
                        vpnStatus = vpnStatus,
                        isConnecting = isConnectingVpn,
                        isPermissionDialogShowing = isVpnPermissionDialogShowing,
                        hasLocalLibrary = allChannels.isNotEmpty() || allMovies.isNotEmpty() || allSeries.isNotEmpty(),
                        lang = lang,
                        compact = isPhoneLandscape,
                        onConnectCloudflareVpn = { forcePrompt ->
                            connectVpnAutomatically(forcePrompt = forcePrompt)
                        },
                        onBrowseLocalOffline = {
                            browseOfflineAllowed = true
                            Toast.makeText(
                                context,
                                "📂 وضع تصفح المكتبة المحلية: يتطلب تشغيل القنوات اتصالاً صالحاً وVPN نشطاً",
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        onRequestExit = { showExitConfirmation = true }
                    )
                }
                // 3. ExoPlayer Fullscreen View (Instant O(1) bouquet slice lookup — zero lag on 100k+ servers!)
                else if (currentPlaying != null) {
                    val siblings = remember(catalogSnapshot, currentPlaying, lastSelectedSeriesParent) {
                        when (currentPlaying.contentType) {
                            StreamContentType.MOVIE ->
                                catalogSnapshot.moviesByCategory[currentPlaying.group].orEmpty().ifEmpty { listOf(currentPlaying) }
                            StreamContentType.SERIES -> {
                                val parentSr = lastSelectedSeriesParent
                                val cachedEps = if (parentSr != null) XtreamRepository.getCachedSeriesEpisodesNow(parentSr) else null
                                if (parentSr != null && !cachedEps.isNullOrEmpty()) {
                                    cachedEps.map { ep ->
                                        parentSr.copy(
                                            name = "${parentSr.name} - م${ep.seasonNumber} ح${ep.episodeNumber} — ${ep.title}",
                                            url = ep.url,
                                            contentType = StreamContentType.SERIES,
                                            seriesId = 0,
                                            streamId = ep.id.toIntOrNull() ?: 0,
                                            containerExtension = ep.containerExtension.ifBlank { "mp4" }
                                        )
                                    }
                                } else {
                                    listOf(currentPlaying)
                                }
                            }
                            StreamContentType.LIVE_TV ->
                                catalogSnapshot.channelsByBouquet[currentPlaying.group].orEmpty().ifEmpty { listOf(currentPlaying) }
                        }
                    }
                    val favTargetForPlayer = if (currentPlaying.contentType == StreamContentType.SERIES) {
                        lastSelectedSeriesParent ?: resolveParentSeriesChannel(currentPlaying)
                    } else {
                        currentPlaying
                    }
                    PlayerScreen(
                        channel = currentPlaying,
                        siblingChannels = siblings,
                        allChannels = when (currentPlaying.contentType) {
                            StreamContentType.MOVIE -> siblings
                            StreamContentType.SERIES -> siblings
                            StreamContentType.LIVE_TV -> allChannels
                        },
                        precomputedBouquets = if (currentPlaying.contentType == StreamContentType.LIVE_TV) {
                            catalogSnapshot.playerDrawerBouquets
                        } else emptyList(),
                        favorites = favorites,
                        xtreamPlaylists = orderedPlaylists,
                        preferences = preferences,
                        isFavorite = favorites.any {
                            it.stableKey == favTargetForPlayer.stableKey ||
                                (favTargetForPlayer.contentType == StreamContentType.SERIES && favTargetForPlayer.seriesId > 0 &&
                                    it.contentType == StreamContentType.SERIES &&
                                    it.seriesId == favTargetForPlayer.seriesId &&
                                    it.playlistId == favTargetForPlayer.playlistId)
                        },
                        initialResumePositionMs = activeResumePositionMs,
                        onSaveVodProgress = { ch, posMs, durMs ->
                            saveVodProgress(ch, posMs, durMs)
                        },
                        onToggleFavorite = { toggleFavorite(favTargetForPlayer) },
                        onUpdatePreferences = {
                            preferences = it
                            persistCurrentState()
                        },
                        onSelectChannel = { nextCh ->
                            if (nextCh.contentType == StreamContentType.LIVE_TV) {
                                lastWatchedStableKey = nextCh.stableKey
                                if (selectedCategory != "الكل" &&
                                    selectedCategory != favBouquetTitle &&
                                    selectedCategory != allBouquetTitle &&
                                    selectedCategory != nextCh.group
                                ) {
                                    selectedCategory = nextCh.group
                                }
                            } else if (nextCh.contentType == StreamContentType.MOVIE) {
                                lastWatchedStableKey = nextCh.stableKey
                            }
                            startPlayback(
                                nextCh,
                                isDirectEpisodeFromModal = (nextCh.contentType == StreamContentType.SERIES)
                            )
                        },
                        onBack = {
                            val parentSeries = lastSelectedSeriesParent ?: if (currentPlaying.contentType == StreamContentType.SERIES) {
                                resolveParentSeriesChannel(currentPlaying)
                            } else null
                            if (currentPlaying.contentType == StreamContentType.SERIES && parentSeries != null) {
                                lastWatchedStableKey = parentSeries.stableKey
                                selectedSeriesForEpisodes = parentSeries
                                if (selectedSeriesCategory != "الكل" &&
                                    selectedSeriesCategory != "__FAVORITES_VOD__" &&
                                    selectedSeriesCategory != "__CONTINUE_WATCHING__" &&
                                    selectedSeriesCategory != parentSeries.group
                                ) {
                                    selectedSeriesCategory = parentSeries.group
                                }
                            } else if (currentPlaying.contentType == StreamContentType.MOVIE) {
                                lastWatchedStableKey = currentPlaying.stableKey
                                if (selectedMovieCategory != "الكل" &&
                                    selectedMovieCategory != "__FAVORITES_VOD__" &&
                                    selectedMovieCategory != "__CONTINUE_WATCHING__" &&
                                    selectedMovieCategory != currentPlaying.group
                                ) {
                                    selectedMovieCategory = currentPlaying.group
                                }
                            } else {
                                previewLiveChannel = currentPlaying
                                lastWatchedStableKey = currentPlaying.stableKey
                                if (selectedCategory != "الكل" &&
                                    selectedCategory != favBouquetTitle &&
                                    selectedCategory != allBouquetTitle &&
                                    selectedCategory != currentPlaying.group
                                ) {
                                    selectedCategory = currentPlaying.group
                                }
                            }
                            restoreFocusTrigger += 1
                            playingChannel = null
                        }
                    )
                }
                // 4.A Classic LIVE PRO Classic Dashboard Mode (when user selects CLASSIC_LIVE_PRO layout style)
                else if (preferences.uiLayoutStyle == UiLayoutStyle.CLASSIC_LIVE_PRO && classicProShowDashboard) {
                    ClassicLiveProDashboard(
                        deviceId = deviceId,
                        subscription = subscription,
                        vpnStatus = vpnStatus,
                        networkSpeedLabel = if (preferences.showNetworkSpeed) liveNetworkSpeed else "",
                        showScreenCode = preferences.showScreenCodeOverlay,
                        onToggleScreenCodeVisibility = {
                            preferences = preferences.copy(
                                showScreenCodeOverlay = !preferences.showScreenCodeOverlay
                            )
                            persistCurrentState()
                        },
                        liveCount = allChannels.size,
                        moviesCount = allMovies.size,
                        seriesCount = allSeries.size,
                        playlistsCount = orderedPlaylists.size,
                        favoritesCount = favorites.size,
                        continueWatchingCount = vodWatchProgressMap.values.count { it.hasResumePoint },
                        lang = lang,
                        compact = isPhoneLandscape,
                        onOpenTab = { targetTab ->
                            activeTab = targetTab
                            classicProShowDashboard = false
                        },
                        onSwitchToModernUi = {
                            preferences = preferences.copy(uiLayoutStyle = UiLayoutStyle.MODERN_ROYAL)
                            persistCurrentState()
                        },
                        onRefreshAll = { triggerSmartContentRefresh() },
                        onOpenActivation = { showUnifiedValidityModal = true },
                        onExit = { showExitConfirmation = true }
                    )
                }
                // 4. Main App Shell (Dark Royal Purple)
                else {
                    Row(modifier = Modifier.fillMaxSize()) {
                        LandscapeSideNavigationRail(
                            activeTab = activeTab,
                            lang = lang,
                            compact = isPhoneLandscape,
                            onSelectTab = { tab -> activeTab = tab },
                            onExitClick = { showExitConfirmation = true }
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            TopBeinHeader(
                                deviceId = deviceId,
                                subscription = subscription,
                                vpnStatus = vpnStatus,
                                totalCount = allChannels.size,
                                moviesCount = allMovies.size,
                                seriesCount = allSeries.size,
                                bouquetCount = (bouquetSummaries.size - 1).coerceAtLeast(1),
                                isLoading = isLoading || isSyncingXtream,
                                useGrid = preferences.useGridLayout,
                                lang = lang,
                                playlistMode = preferences.playlistDisplayMode,
                                playlists = orderedPlaylists,
                                activePlaylistFilterId = preferences.activePlaylistFilterId,
                                showScreenCode = preferences.showScreenCodeOverlay,
                                compact = isPhoneLandscape,
                                onSelectLanguage = { newLang ->
                                    preferences = preferences.copy(appLanguage = newLang)
                                    persistCurrentState()
                                },
                                onCyclePlaylistSource = {
                                    // Instant Multi-Click Smart Cycle (XTREAM Playlists ALWAYS FIRST in exact order!):
                                    // Click 1..N: Each enabled XTREAM playlist individually (in exact order)
                                    // Next Click: PORTAL_MAIN (if available)
                                    // Final Click: MERGE_ALL (XTREAM first + Portal)
                                    val enabledLists = orderedPlaylists.filter { it.enabled }
                                    if (enabledLists.isEmpty()) {
                                        preferences = preferences.copy(
                                            playlistDisplayMode = PlaylistDisplayMode.MERGE_ALL,
                                            activePlaylistFilterId = "ALL"
                                        )
                                    } else {
                                        val cycleIds = buildList {
                                            addAll(enabledLists.map { it.id })
                                            if (isVpnActiveAndProtected && (portalChannels.isNotEmpty() || portalMovies.isNotEmpty() || portalSeries.isNotEmpty())) {
                                                add("PORTAL_MAIN")
                                            }
                                        }
                                        if (preferences.playlistDisplayMode == PlaylistDisplayMode.MERGE_ALL) {
                                            preferences = preferences.copy(
                                                playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                                                activePlaylistFilterId = cycleIds.first()
                                            )
                                        } else {
                                            val currIdx = cycleIds.indexOf(preferences.activePlaylistFilterId)
                                            if (currIdx == -1 || currIdx >= cycleIds.lastIndex) {
                                                preferences = preferences.copy(
                                                    playlistDisplayMode = PlaylistDisplayMode.MERGE_ALL,
                                                    activePlaylistFilterId = "ALL"
                                                )
                                            } else {
                                                preferences = preferences.copy(
                                                    playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                                                    activePlaylistFilterId = cycleIds[currIdx + 1]
                                                )
                                            }
                                        }
                                    }
                                    selectedCategory = "الكل"
                                    selectedMovieCategory = "الكل"
                                    selectedSeriesCategory = "الكل"
                                    persistCurrentState(saveCatalog = false)
                                },
                                onToggleScreenCodeVisibility = {
                                    preferences = preferences.copy(
                                        showScreenCodeOverlay = !preferences.showScreenCodeOverlay
                                    )
                                    persistCurrentState()
                                },
                                isCheckingValidityOnly = isCheckingValidityOnly,
                                networkSpeedLabel = if (preferences.showNetworkSpeed) liveNetworkSpeed else "",
                                uiLayoutStyle = preferences.uiLayoutStyle,
                                showClassicHomeButton = preferences.uiLayoutStyle == UiLayoutStyle.CLASSIC_LIVE_PRO && !classicProShowDashboard,
                                onReturnToClassicDashboard = { classicProShowDashboard = true },
                                onToggleUiLayoutStyle = {
                                    val nextStyle = if (preferences.uiLayoutStyle == UiLayoutStyle.MODERN_ROYAL) {
                                        UiLayoutStyle.CLASSIC_LIVE_PRO
                                    } else {
                                        UiLayoutStyle.MODERN_ROYAL
                                    }
                                    preferences = preferences.copy(uiLayoutStyle = nextStyle)
                                    classicProShowDashboard = (nextStyle == UiLayoutStyle.CLASSIC_LIVE_PRO)
                                    persistCurrentState()
                                },
                                onOpenBouquets = { activeTab = MainTab.BOUQUETS },
                                onOpenXtream = { activeTab = MainTab.XTREAM },
                                onOpenPlaylistSelectorModal = {
                                    if (orderedPlaylists.isNotEmpty()) {
                                        isFirstTimeSelectorMode = false
                                        showMultiPlaylistSelectorModal = true
                                    } else {
                                        activeTab = MainTab.XTREAM
                                    }
                                },
                                onOpenQuickAddAccount = { showQuickAddAccountModal = true },
                                onOpenVpnControl = { showVpnControlModal = true },
                                onRefreshValidityOnly = { refreshValidityDatesOnly(showToast = true) },
                                onOpenActivation = { showUnifiedValidityModal = true },
                                onRefresh = { triggerSmartContentRefresh() }
                            )

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                when (activeTab) {
                                    MainTab.LIVE -> BeinCatalogBrowserView(
                                        items = allChannels,
                                        channelsByBouquet = catalogSnapshot.channelsByBouquet,
                                        bouquetSummaries = bouquetSummaries,
                                        favorites = favorites,
                                        selectedCategory = selectedCategory,
                                        onSelectCategory = { selectedCategory = it },
                                        searchQuery = searchQuery,
                                        onSearchChange = { searchQuery = it },
                                        statusMessage = statusMessage,
                                        isLoading = isLoading || isSyncingXtream,
                                        useGrid = preferences.useGridLayout,
                                        showBouquetSidebar = preferences.showBouquetSidebar,
                                        onToggleBouquetSidebar = {
                                            preferences = preferences.copy(
                                                showBouquetSidebar = !preferences.showBouquetSidebar
                                            )
                                            persistCurrentState()
                                        },
                                        gridState = liveGridState,
                                        listState = liveListState,
                                        bouquetListState = bouquetSidebarListState,
                                        lastWatchedKey = lastWatchedStableKey,
                                        restoreFocusTrigger = restoreFocusTrigger,
                                        previewChannel = previewLiveChannel,
                                        onSelectPreviewChannel = { ch ->
                                            if (ch.playlistId == "PORTAL_MAIN" && !isVpnActiveAndProtected) {
                                                Toast.makeText(
                                                    context,
                                                    "🔒 القائمة الرئيسية تتطلب تفعيل الـ VPN لحماية الروابط — اضغط على زر (🔒 VPN) لتفعيله",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                showVpnControlModal = true
                                            } else {
                                                previewLiveChannel = ch
                                                lastWatchedStableKey = ch.stableKey
                                                history = listOf(ch) + history.filterNot { it.stableKey == ch.stableKey }.take(49)
                                                persistCurrentState(saveCatalog = false)
                                            }
                                        },
                                        xtreamPlaylists = orderedPlaylists,
                                        preferences = preferences,
                                        networkSpeedLabel = if (preferences.showNetworkSpeed) liveNetworkSpeed else "",
                                        lang = lang,
                                        compact = isPhoneLandscape,
                                        deviceId = deviceId,
                                        onPlay = { ch ->
                                            previewLiveChannel = null
                                            startPlayback(ch)
                                        },
                                        onToggleFavorite = { toggleFavorite(it) },
                                        onRefresh = { triggerSmartContentRefresh() },
                                        onMultiQualityChanged = { multiQualityEpoch++ }
                                    )

                                    MainTab.BOUQUETS -> BouquetsGridDirectoryView(
                                        bouquetSummaries = bouquetSummaries,
                                        selectedCategory = selectedCategory,
                                        lang = lang,
                                        compact = isPhoneLandscape,
                                        onSelectBouquet = { chosenBouquet ->
                                            selectedCategory = chosenBouquet
                                            activeTab = MainTab.LIVE
                                        }
                                    )

                                    MainTab.MOVIES -> VodMoviesAndSeriesCatalogView(
                                        titleAr = "🎬 ${AppStrings.tr(lang, "مكتبة الأفلام", "Catalogue Films", "Movies Library")} (${allMovies.size})",
                                        emptyHintAr = AppStrings.tr(
                                            lang,
                                            "لا توجد أفلام حالياً. يمكنك إدارة قوائمك من تبويب فضاء الزبون عبر رمز الشاشة.",
                                            "Aucun film disponible. Gérez vos listes via Espace Client.",
                                            "No movies available yet. Manage your lists via Customer Space."
                                        ),
                                        items = allMovies,
                                        itemsByCategory = catalogSnapshot.moviesByCategory,
                                        categories = catalogSnapshot.movieCategories,
                                        favorites = favorites,
                                        vodWatchProgressMap = vodWatchProgressMap,
                                        selectedCategory = selectedMovieCategory,
                                        onSelectCategory = { selectedMovieCategory = it },
                                        gridState = moviesGridState,
                                        categoryListState = moviesCategoryListState,
                                        lastWatchedKey = lastWatchedStableKey,
                                        restoreFocusTrigger = restoreFocusTrigger,
                                        compact = isPhoneLandscape,
                                        onPlay = { startPlayback(it) },
                                        onToggleFavorite = { toggleFavorite(it) },
                                        onOpenXtream = { activeTab = MainTab.XTREAM },
                                        onRefresh = { triggerSmartContentRefresh() }
                                    )

                                    MainTab.SERIES -> VodMoviesAndSeriesCatalogView(
                                        titleAr = "📺 ${AppStrings.tr(lang, "مكتبة المسلسلات", "Catalogue Séries", "Series Library")} (${allSeries.size})",
                                        emptyHintAr = AppStrings.tr(
                                            lang,
                                            "لا توجد مسلسلات حالياً. يمكنك إدارة قوائمك من تبويب فضاء الزبون عبر رمز الشاشة.",
                                            "Aucune série disponible. Gérez vos listes via Espace Client.",
                                            "No series available yet. Manage your lists via Customer Space."
                                        ),
                                        items = allSeries,
                                        itemsByCategory = catalogSnapshot.seriesByCategory,
                                        categories = catalogSnapshot.seriesCategories,
                                        favorites = favorites,
                                        vodWatchProgressMap = vodWatchProgressMap,
                                        selectedCategory = selectedSeriesCategory,
                                        onSelectCategory = { selectedSeriesCategory = it },
                                        gridState = seriesGridState,
                                        categoryListState = seriesCategoryListState,
                                        lastWatchedKey = lastWatchedStableKey,
                                        restoreFocusTrigger = restoreFocusTrigger,
                                        compact = isPhoneLandscape,
                                        onPlay = { startPlayback(it) },
                                        onToggleFavorite = { toggleFavorite(it) },
                                        onOpenXtream = { activeTab = MainTab.XTREAM },
                                        onRefresh = { triggerSmartContentRefresh() }
                                    )

                                    MainTab.XTREAM -> MultiPlaylistXtreamManagerView(
                                        deviceId = deviceId,
                                        subscription = subscription,
                                        playlists = orderedPlaylists,
                                        showPortalMain = isVpnActiveAndProtected && (portalChannels.isNotEmpty() || portalMovies.isNotEmpty() || portalSeries.isNotEmpty()),
                                        portalStatsLabel = "${portalChannels.size} قناة • ${portalMovies.size} فيلم • ${portalSeries.size} مسلسل",
                                        playlistMode = preferences.playlistDisplayMode,
                                        activePlaylistFilterId = preferences.activePlaylistFilterId,
                                        lang = lang,
                                        isSyncing = isSyncingXtream || isLoading,
                                        compact = isPhoneLandscape,
                                        onChangePlaylistMode = { mode ->
                                            preferences = preferences.copy(playlistDisplayMode = mode)
                                            persistCurrentState()
                                        },
                                        onSelectActivePlaylist = { pid ->
                                            preferences = preferences.copy(activePlaylistFilterId = pid)
                                            persistCurrentState()
                                        },
                                        onAddOrUpdatePlaylist = { cfg ->
                                            val immediateCfg = cfg.copy(
                                                enabled = true,
                                                statusNote = cfg.statusNote.ifBlank { "مسجل في قاعدة البيانات ✓" }
                                            )
                                            xtreamPlaylists = xtreamPlaylists.map {
                                                if (it.id == immediateCfg.id) immediateCfg else it
                                            }.let { list ->
                                                if (list.none { it.id == immediateCfg.id }) list + immediateCfg else list
                                            }
                                            persistCurrentState(saveCatalog = false)
                                            scope.launch(Dispatchers.IO) {
                                                runCatching {
                                                    ChannelRepository.pushCustomerXtreamToPortal(deviceId, deviceName, immediateCfg)
                                                }
                                            }
                                            Toast.makeText(
                                                context,
                                                "✅ تم تسجيل القائمة في قاعدة بيانات السيرفر والجهاز مباشرة!",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            syncSingleXtreamPlaylist(immediateCfg, showToast = true)
                                        },
                                        onMovePlaylist = { fromIdx, toIdx ->
                                            val reordered = CustomOrderHelper.moveItem(orderedPlaylists, fromIdx, toIdx)
                                                .mapIndexed { idx, item -> item.copy(orderIndex = idx) }
                                            xtreamPlaylists = reordered
                                            preferences = preferences.copy(playlistOrderIds = reordered.map { it.id })
                                            persistCurrentState()
                                            scope.launch(Dispatchers.IO) {
                                                reordered.forEach { item ->
                                                    runCatching { ChannelRepository.pushCustomerXtreamToPortal(deviceId, deviceName, item) }
                                                }
                                            }
                                        },
                                        onTogglePlaylistEnabled = { cfg ->
                                            val toggled = cfg.copy(enabled = !cfg.enabled)
                                            xtreamPlaylists = xtreamPlaylists.map {
                                                if (it.id == cfg.id) toggled else it
                                            }
                                            persistCurrentState()
                                            scope.launch(Dispatchers.IO) {
                                                runCatching { ChannelRepository.pushCustomerXtreamToPortal(deviceId, deviceName, toggled) }
                                            }
                                        },
                                        onDeletePlaylist = { cfg ->
                                            xtreamPlaylists = xtreamPlaylists.filterNot { it.id == cfg.id }
                                            xtreamChannelsMap = xtreamChannelsMap - cfg.id
                                            xtreamMoviesMap = xtreamMoviesMap - cfg.id
                                            xtreamSeriesMap = xtreamSeriesMap - cfg.id
                                            scope.launch(Dispatchers.IO) {
                                                runCatching { sqliteDb.deleteSourceCatalog(cfg.id) }
                                                runCatching { ChannelRepository.deleteCustomerXtreamFromPortal(deviceId, cfg.id) }
                                            }
                                            persistCurrentState(saveCatalog = true)
                                        },
                                        onPullRemoteListsByScreenCode = {
                                            // Differential Restore: ONLY download NEW or uncached playlists from Customer Portal!
                                            // PRODUCTION: wait for WireGuard + Internet so restored catalogs are complete (not partial).
                                            scope.launch {
                                                runCatching {
                                                    statusMessage = "🛡️ تأمين النفق والإنترنت قبل استعادة القوائم..."
                                                    if (!ensureNetworkReadyForFullCatalogSync(requireVpn = true)) {
                                                        Toast.makeText(
                                                            context,
                                                            "🛡️ انتظر اتصال WireGuard والإنترنت ثم أعد المحاولة لسحب المحتوى كاملاً",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                        return@launch
                                                    }
                                                    val (quickSub, remoteLists) = ChannelRepository.checkSubscriptionAndListsQuick(
                                                        deviceId = deviceId,
                                                        deviceName = deviceName,
                                                        forceNetwork = true
                                                    )
                                                    subscription = quickSub
                                                    if (remoteLists.isEmpty()) {
                                                        Toast.makeText(context, "ℹ️ لا توجد قوائم مرفوعة على فضاء الزبون لهذا الرمز حالياً", Toast.LENGTH_SHORT).show()
                                                        return@launch
                                                    }
                                                    val merged = xtreamPlaylists.toMutableList()
                                                    val newOrUncachedToSync = mutableListOf<XtreamAccountConfig>()
                                                    for (rCfg in remoteLists) {
                                                        val idx = merged.indexOfFirst {
                                                            it.id == rCfg.id ||
                                                                (it.serverUrl.equals(rCfg.serverUrl, ignoreCase = true) &&
                                                                    it.username.equals(rCfg.username, ignoreCase = true))
                                                        }
                                                        if (idx >= 0) {
                                                            val existing = merged[idx]
                                                            val credsChanged = !XtreamRepository.normalizeServerUrl(existing.serverUrl)
                                                                .equals(XtreamRepository.normalizeServerUrl(rCfg.serverUrl), ignoreCase = true) ||
                                                                existing.username.trim() != rCfg.username.trim() ||
                                                                (rCfg.password.isNotBlank() && existing.password.trim() != rCfg.password.trim())
                                                            val updated = existing.copy(
                                                                serverName = rCfg.serverName.ifBlank { existing.serverName },
                                                                serverUrl = rCfg.serverUrl.ifBlank { existing.serverUrl },
                                                                username = rCfg.username.ifBlank { existing.username },
                                                                password = rCfg.password.ifBlank { existing.password },
                                                                enabled = rCfg.enabled,
                                                                orderIndex = rCfg.orderIndex,
                                                                expDate = rCfg.expDate.ifBlank { existing.expDate }
                                                            )
                                                            merged[idx] = updated
                                                            val hasLocalData = !xtreamChannelsMap[existing.id].isNullOrEmpty() ||
                                                                !xtreamMoviesMap[existing.id].isNullOrEmpty() ||
                                                                !xtreamSeriesMap[existing.id].isNullOrEmpty()
                                                            if (!hasLocalData || credsChanged) {
                                                                newOrUncachedToSync.add(updated)
                                                            }
                                                        } else {
                                                            merged.add(rCfg)
                                                            newOrUncachedToSync.add(rCfg)
                                                        }
                                                    }
                                                    xtreamPlaylists = merged.sortedBy { it.orderIndex }
                                                    persistCurrentStateSuspend(saveCatalog = false)

                                                    if (newOrUncachedToSync.isEmpty()) {
                                                        Toast.makeText(
                                                            context,
                                                            "✅ جميع القوائم (${merged.size}) محدثة ومحفوظة مسبقاً في ذاكرة التطبيق (دون إعادة تحميل)!",
                                                            Toast.LENGTH_LONG
                                                        ).show()
                                                    } else {
                                                        Toast.makeText(
                                                            context,
                                                            "📲 تم رصد ${newOrUncachedToSync.size} قائمة جديدة — بعد تأكيد النفق والإنترنت جارٍ سحب المحتوى الكامل...",
                                                            Toast.LENGTH_SHORT
                                                        ).show()
                                                        for ((i, targetCfg) in newOrUncachedToSync.withIndex()) {
                                                            if (targetCfg.enabled && targetCfg.isConfigured) {
                                                                // Re-validate path before each list so a mid-batch drop does not write incomplete data as final
                                                                if (!ensureNetworkReadyForFullCatalogSync(requireVpn = true)) {
                                                                    Toast.makeText(
                                                                        context,
                                                                        "⚠️ انقطع النفق/الإنترنت — توقف السحب لتجنب قوائم ناقصة. أعد التحديث لاحقاً",
                                                                        Toast.LENGTH_LONG
                                                                    ).show()
                                                                    break
                                                                }
                                                                syncSingleXtreamPlaylistSuspend(targetCfg, showToast = (i == newOrUncachedToSync.lastIndex))
                                                            }
                                                        }
                                                    }
                                                }.onFailure {
                                                    Toast.makeText(context, "⚠️ تعذّر الاتصال بفضاء الزبون حالياً", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    )

                                    MainTab.FAVORITES -> FavoritesAndHistoryView(
                                        favorites = favorites,
                                        history = history,
                                        vodWatchProgressMap = vodWatchProgressMap,
                                        preferences = preferences,
                                        onUpdatePreferences = {
                                            preferences = it
                                            persistCurrentState()
                                        },
                                        lang = lang,
                                        compact = isPhoneLandscape,
                                        onPlay = { startPlayback(it) },
                                        onToggleFavorite = { toggleFavorite(it) },
                                        onClearFavoritesByType = { cType -> clearFavoritesByType(cType) },
                                        onBackupFavoritesToServer = {
                                            if (deviceId.isBlank()) {
                                                Toast.makeText(context, "رمز الشاشة غير جاهز بعد", Toast.LENGTH_SHORT).show()
                                            } else if (favorites.isEmpty()) {
                                                Toast.makeText(context, "قائمة المفضلة فارغة — أضف عناصر ثم انسخ احتياطياً", Toast.LENGTH_SHORT).show()
                                            } else {
                                                pushFavoritesBackupNow()
                                                Toast.makeText(context, "☁️ جارٍ رفع نسخة المفضلة للسيرفر (${favorites.size})...", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onRestoreFavoritesFromServer = {
                                            if (deviceId.isBlank()) {
                                                Toast.makeText(context, "رمز الشاشة غير جاهز بعد", Toast.LENGTH_SHORT).show()
                                            } else {
                                                scope.launch {
                                                    Toast.makeText(context, "📥 جارٍ استعادة المفضلة من السيرفر...", Toast.LENGTH_SHORT).show()
                                                    val remote = withContext(Dispatchers.IO) {
                                                        runCatching { ChannelRepository.pullFavoritesFromServer(deviceId) }.getOrNull()
                                                    }
                                                    if (remote == null) {
                                                        Toast.makeText(context, "⚠️ لا توجد نسخة محفوظة لهذا الرمز أو تعذر الاتصال", Toast.LENGTH_LONG).show()
                                                    } else if (remote.isEmpty()) {
                                                        Toast.makeText(context, "النسخة على السيرفر فارغة", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        favorites = remote
                                                        persistCurrentState(saveCatalog = false)
                                                        pushFavoritesBackupNow()
                                                        Toast.makeText(context, "✅ تم استعادة ${remote.size} عنصر مفضلة من السيرفر", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        },
                                        onClearHistory = {
                                            history = emptyList()
                                            persistCurrentState(saveCatalog = false)
                                        }
                                    )

                                    MainTab.ACTIVATION -> ScreenActivationStatusView(
                                        deviceId = deviceId,
                                        deviceName = deviceName,
                                        subscription = subscription,
                                        lang = lang,
                                        isLoading = isLoading || isCheckingValidityOnly,
                                        compact = isPhoneLandscape,
                                        onVerifyNow = { refreshValidityDatesOnly(showToast = true) },
                                        onRedeemCode = { redeemDirectCode(it) }
                                    )

                                    MainTab.SETTINGS -> SettingsAndVpnView(
                                        preferences = preferences,
                                        rawBouquets = rawBouquetSummaries.filter { it.name != allBouquetTitle },
                                        allChannels = allChannels,
                                        vpnStatus = vpnStatus,
                                        compact = isPhoneLandscape,
                                        onReconnectVpn = { connectVpnAutomatically(manualTrigger = true, forcePrompt = true) },
                                        onUpdatePreferences = {
                                            preferences = it
                                            persistCurrentState()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                val seriesItem = selectedSeriesForEpisodes
                if (seriesItem != null) {
                    val resolvedModalSeries = remember(seriesItem, allSeries.size) {
                        resolveParentSeriesChannel(seriesItem)
                    }
                    val isSeriesFav = favorites.any {
                        it.stableKey == resolvedModalSeries.stableKey ||
                            (resolvedModalSeries.seriesId > 0 &&
                                it.contentType == StreamContentType.SERIES &&
                                it.seriesId == resolvedModalSeries.seriesId &&
                                it.playlistId == resolvedModalSeries.playlistId)
                    }
                    SeriesEpisodesModal(
                        series = resolvedModalSeries,
                        isFavorite = isSeriesFav,
                        onToggleSeriesFavorite = { toggleFavorite(resolvedModalSeries) },
                        xtreamLists = orderedPlaylists,
                        vodWatchProgressMap = vodWatchProgressMap,
                        compact = isPhoneLandscape,
                        onPlayEpisode = { ep ->
                            lastWatchedStableKey = resolvedModalSeries.stableKey
                            lastSelectedSeriesParent = resolvedModalSeries
                            selectedSeriesForEpisodes = null
                            val epChannel = resolvedModalSeries.copy(
                                name = "${resolvedModalSeries.name} - م${ep.seasonNumber} ح${ep.episodeNumber} — ${ep.title}",
                                url = ep.url,
                                contentType = StreamContentType.SERIES,
                                seriesId = 0,
                                streamId = ep.id.toIntOrNull() ?: 0,
                                containerExtension = ep.containerExtension.ifBlank { "mp4" }
                            )
                            startPlayback(epChannel, isDirectEpisodeFromModal = true)
                        },
                        onDismiss = {
                            lastWatchedStableKey = resolvedModalSeries.stableKey
                            selectedSeriesForEpisodes = null
                            restoreFocusTrigger += 1
                        }
                    )
                }

                val resumeCandidate = pendingResumeChannel
                if (resumeCandidate != null) {
                    val savedProg = vodWatchProgressMap[resumeCandidate.stableKey]
                        ?: vodWatchProgressMap[resumeCandidate.url.trim()]
                    if (savedProg != null) {
                        VodResumePromptModal(
                            channel = resumeCandidate,
                            progress = savedProg,
                            lang = lang,
                            compact = isPhoneLandscape,
                            onResume = {
                                val target = resumeCandidate
                                pendingResumeChannel = null
                                startPlayback(
                                    target,
                                    forceResumePosMs = savedProg.positionMs,
                                    isDirectEpisodeFromModal = (target.contentType == StreamContentType.SERIES)
                                )
                            },
                            onStartOver = {
                                val target = resumeCandidate
                                pendingResumeChannel = null
                                vodWatchProgressMap = vodWatchProgressMap - setOf(target.stableKey, target.url.trim())
                                persistCurrentState(saveCatalog = false)
                                startPlayback(
                                    target,
                                    forceResumePosMs = 0L,
                                    isDirectEpisodeFromModal = (target.contentType == StreamContentType.SERIES)
                                )
                            },
                            onDismiss = { pendingResumeChannel = null }
                        )
                    } else {
                        pendingResumeChannel = null
                    }
                }

                if (showQuickAddAccountModal) {
                    QuickAddXtreamAccountModal(
                        deviceId = deviceId,
                        existingPlaylistsCount = xtreamPlaylists.size,
                        lang = lang,
                        compact = isPhoneLandscape,
                        onAddAccount = { newCfg ->
                            registerAndSyncNewXtreamAccount(newCfg)
                        },
                        onDismiss = { showQuickAddAccountModal = false }
                    )
                }

                if (showVpnControlModal) {
                    VpnControlModal(
                        vpnStatus = vpnStatus,
                        vpnPausedTemporarily = vpnPausedTemporarily,
                        vpnPermanentDisable = LiveProGlobalActions.vpnPermanentDisable,
                        vpnAutoStartOnLaunch = LiveProGlobalActions.vpnAutoStartOnLaunch,
                        lang = lang,
                        compact = isPhoneLandscape,
                        onEnableVpnNow = {
                            connectVpnAutomatically(manualTrigger = true, forcePrompt = true)
                        },
                        onPauseVpnTemporarily = {
                            pauseVpnTemporarilyNow()
                        },
                        onDisableVpnPermanently = {
                            disableVpnPermanentlyNow()
                        },
                        onToggleAutoStartOnLaunch = { enabledAuto ->
                            LiveProGlobalActions.updateVpnPrefs(
                                context = context,
                                permanentDisable = if (enabledAuto) false else LiveProGlobalActions.vpnPermanentDisable,
                                autoStart = enabledAuto
                            )
                            if (enabledAuto && !vpnStatus.connected) {
                                connectVpnAutomatically(manualTrigger = true, forcePrompt = true)
                            }
                        },
                        onDismiss = { showVpnControlModal = false }
                    )
                }

                if (showUnifiedValidityModal) {
                    UnifiedValidityAndScreenCodeModal(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        subscription = subscription,
                        playlists = orderedPlaylists,
                        showScreenCode = preferences.showScreenCodeOverlay,
                        lang = lang,
                        compact = isPhoneLandscape,
                        onToggleScreenCode = {
                            preferences = preferences.copy(showScreenCodeOverlay = !preferences.showScreenCodeOverlay)
                            persistCurrentState()
                        },
                        onOpenQuickAddAccount = {
                            showUnifiedValidityModal = false
                            showQuickAddAccountModal = true
                        },
                        onOpenCustomerSpace = {
                            showUnifiedValidityModal = false
                            activeTab = MainTab.XTREAM
                        },
                        onOpenFullActivation = {
                            showUnifiedValidityModal = false
                            activeTab = MainTab.ACTIVATION
                        },
                        onRedeemCode = { codeStr ->
                            redeemDirectCode(codeStr)
                        },
                        onRefresh = {
                            refreshValidityDatesOnly(showToast = true)
                        },
                        onDismiss = { showUnifiedValidityModal = false }
                    )
                }

                if (showMultiPlaylistSelectorModal && orderedPlaylists.isNotEmpty()) {
                    FirstTimeMultiPlaylistSelectorModal(
                        playlists = orderedPlaylists,
                        isFirstTime = isFirstTimeSelectorMode,
                        lang = lang,
                        compact = isPhoneLandscape,
                        onSyncSingle = { chosenPl ->
                            showMultiPlaylistSelectorModal = false
                            preferences = preferences.copy(
                                playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                                activePlaylistFilterId = chosenPl.id
                            )
                            syncSingleXtreamPlaylist(chosenPl, showToast = true)
                        },
                        onSyncSelected = { selectedIds, includePortal ->
                            showMultiPlaylistSelectorModal = false
                            if (selectedIds.size == 1 && !includePortal) {
                                val singleId = selectedIds.first()
                                preferences = preferences.copy(
                                    playlistDisplayMode = PlaylistDisplayMode.SEPARATE_LISTS,
                                    activePlaylistFilterId = singleId
                                )
                            } else {
                                preferences = preferences.copy(
                                    playlistDisplayMode = PlaylistDisplayMode.MERGE_ALL,
                                    activePlaylistFilterId = "ALL"
                                )
                            }
                            syncSelectedXtreamPlaylists(selectedIds, includePortal)
                        },
                        onSyncAll = {
                            showMultiPlaylistSelectorModal = false
                            preferences = preferences.copy(
                                playlistDisplayMode = PlaylistDisplayMode.MERGE_ALL,
                                activePlaylistFilterId = "ALL"
                            )
                            syncServerNow(forceXtreamRefresh = true)
                        },
                        onDismiss = { showMultiPlaylistSelectorModal = false }
                    )
                }

                if ((isLoading || isSyncingXtream || !initialized || isSortingLocalLibrary) && playingChannel == null) {
                    ProfessionalWaitingOverlay(
                        lang = lang,
                        compact = isPhoneLandscape,
                        syncProgress = syncProgress,
                        message = when {
                            !initialized || isSortingLocalLibrary -> AppStrings.tr(
                                lang,
                                "جارٍ سحب وترتيب المحتوى المخزن محلياً (القنوات • الباقات • الأفلام • المسلسلات)...",
                                "Chargement et tri de la bibliothèque locale en cours...",
                                "Loading and sorting locally stored content library..."
                            )
                            syncProgress.phaseLabel.isNotBlank() -> syncProgress.phaseLabel
                            isSyncingXtream -> AppStrings.tr(
                                lang,
                                "يرجى الانتظار قليلاً... جارٍ تحديث محتوى قوائم XTREAM وفحص الصلاحية",
                                "Veuillez patienter... Mise à jour des listes XTREAM en cours",
                                "Please wait... Updating XTREAM playlists & checking validity"
                            )
                            else -> AppStrings.tr(
                                lang,
                                "يرجى الانتظار قليلاً... جارٍ تحديث المحتوى ومزامنة القنوات والباقات",
                                "Veuillez patienter... Synchronisation du contenu en cours",
                                "Please wait... Refreshing content and syncing channels"
                            )
                        }
                    )
                }

                val currentVoucherAlert = voucherAlert
                if (currentVoucherAlert != null) {
                    VoucherRedeemAlertModal(
                        alert = currentVoucherAlert,
                        deviceId = deviceId,
                        lang = lang,
                        compact = isPhoneLandscape,
                        onDismiss = { voucherAlert = null }
                    )
                }

                val activeDiagAlert = diagnosticAlert
                if (activeDiagAlert != null && playingChannel == null) {
                    SmartDiagnosticAlertModal(
                        alert = activeDiagAlert,
                        deviceId = deviceId,
                        compact = isPhoneLandscape,
                        onOpenCustomerSpace = {
                            diagnosticAlert = null
                            activeTab = MainTab.XTREAM
                        },
                        onRetrySync = {
                            diagnosticAlert = null
                            syncServerNow(forceXtreamRefresh = true)
                        },
                        onDismiss = { diagnosticAlert = null }
                    )
                }

                if (showExitConfirmation) {
                    ExitConfirmationModal(
                        lang = lang,
                        compact = isPhoneLandscape,
                        onStay = { showExitConfirmation = false },
                        onConfirmExit = {
                            showExitConfirmation = false
                            activity?.finishAffinity()
                        }
                    )
                }
            }
        }
    }
}

internal fun copyDeviceCodeToClipboard(context: Context, code: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LIVE PRO Device Code", code))
        Toast.makeText(context, "تم نسخ الرمز: $code", Toast.LENGTH_SHORT).show()
    }
}

private fun formatVoucherCodeInput(raw: String): String {
    val clean = raw.uppercase().removePrefix("DEV-").replace(Regex("[^A-Z0-9-]"), "")
    val alnum = clean.replace("-", "").take(8)
    return if (alnum.length > 4) {
        "${alnum.substring(0, 4)}-${alnum.substring(4)}"
    } else {
        alnum
    }
}

private fun pasteVoucherFromClipboard(context: Context): String {
    return runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val raw = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        formatVoucherCodeInput(raw)
    }.getOrDefault("")
}

@Composable
private fun VoucherRedeemAlertModal(
    alert: VoucherAlertInfo,
    deviceId: String,
    lang: AppUiLanguage,
    compact: Boolean,
    onDismiss: () -> Unit
) {
    var okFocused by remember { mutableStateOf(true) }
    val okFocusRequester = remember { FocusRequester() }
    val isSuccess = alert.type == VoucherAlertType.SUCCESS
    val isUsedOrExpired = alert.type == VoucherAlertType.USED_CARD || alert.type == VoucherAlertType.EXPIRED_CARD

    LaunchedEffect(alert) {
        delay(60L)
        runCatching { okFocusRequester.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 310.dp, max = if (compact) 480.dp else 560.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        when {
                            isSuccess -> listOf(Color(0xFF0B2920), BeinSurface, BeinDarkBg)
                            isUsedOrExpired -> listOf(Color(0xFF2D0A16), BeinSurface, BeinDarkBg)
                            else -> listOf(Color(0xFF230F42), BeinSurface, BeinDarkBg)
                        }
                    )
                )
                .border(
                    width = 2.5.dp,
                    color = when {
                        isSuccess -> EmeraldLive
                        isUsedOrExpired -> CrimsonAlert
                        else -> BeinHighlightPurple
                    },
                    shape = RoundedCornerShape(22.dp)
                )
                .padding(if (compact) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 48.dp else 58.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isSuccess -> EmeraldLive.copy(alpha = 0.2f)
                            isUsedOrExpired -> CrimsonAlert.copy(alpha = 0.22f)
                            else -> BeinPrimaryPurple
                        }
                    )
                    .border(
                        2.dp,
                        when {
                            isSuccess -> EmeraldLive
                            isUsedOrExpired -> CrimsonAlert
                            else -> BeinHighlightPurple
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (alert.type) {
                        VoucherAlertType.SUCCESS -> "✅"
                        VoucherAlertType.USED_CARD -> "⛔"
                        VoucherAlertType.EXPIRED_CARD -> "⌛"
                        VoucherAlertType.RATE_LIMITED -> "🛡️"
                        VoucherAlertType.NETWORK_ERROR -> "📡"
                        VoucherAlertType.INVALID_OR_NOT_FOUND -> "❌"
                    },
                    fontSize = if (compact) 22.sp else 26.sp
                )
            }

            Text(
                text = alert.title,
                color = when {
                    isSuccess -> EmeraldLive
                    isUsedOrExpired -> Color(0xFFFCA5A5)
                    else -> Color.White
                },
                fontSize = if (compact) 17.sp else 19.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            if (alert.enteredCode.isNotBlank()) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = alert.enteredCode,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (compact) 17.sp else 18.sp,
                        letterSpacing = 2.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(BeinDarkBg)
                            .border(
                                1.5.dp,
                                if (isSuccess) EmeraldLive else if (isUsedOrExpired) CrimsonAlert else BeinHighlightPurple,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            Text(
                text = alert.message,
                color = Color(0xFFEDE9FE),
                fontSize = if (compact) 14.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center
            )

            if (isSuccess && (alert.addedDays > 0 || alert.newExpireDate.isNotBlank())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0A231B))
                        .border(1.5.dp, EmeraldLive, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (alert.addedDays > 0) {
                        Text(
                            text = "➕ المدة المضافة: ${alert.addedDays} يوم",
                            color = EmeraldLive,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 13.sp else 15.sp
                        )
                    }
                    if (alert.newExpireDate.isNotBlank()) {
                        Text(
                            text = "📅 الانتهاء الجديد: ${alert.newExpireDate}",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 13.sp else 15.sp
                        )
                    }
                }
            } else if (!isSuccess) {
                Text(
                    text = "📞 للدعم أو طلب قسيمة جديدة — علي سات: $ALI_SAT_PHONE_NUMBER • رمز الشاشة: $deviceId",
                    color = Color(0xFFE2E8F0),
                    fontSize = if (compact) 12.sp else 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
            }

            Button(
                onClick = { SafeClickGuard.runThrottled("VOUCHER_MODAL_OK", 220L) { onDismiss() } },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (okFocused) Color.White else if (isSuccess) EmeraldLive else BeinPrimaryPurple,
                    contentColor = if (okFocused || isSuccess) Color.Black else Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (compact) 42.dp else 48.dp)
                    .focusRequester(okFocusRequester)
                    .scale(if (okFocused) 1.03f else 1.0f)
                    .border(
                        width = if (okFocused) 3.dp else 1.5.dp,
                        color = if (okFocused) BeinFocusGlow else Color.White,
                        shape = RoundedCornerShape(24.dp)
                    )
                    .onFocusChanged { okFocused = it.isFocused }
                    .focusable()
            ) {
                Text(
                    text = AppStrings.tr(lang, "حسناً، فهمت ✓", "Compris ✓", "Got it ✓"),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 14.sp else 16.sp
                )
            }
        }
    }
}

@Composable
private fun MandatoryCloudflareVpnGate(
    vpnStatus: CloudflareTunnelStatus,
    isConnecting: Boolean,
    isPermissionDialogShowing: Boolean = false,
    hasLocalLibrary: Boolean = false,
    lang: AppUiLanguage,
    compact: Boolean,
    onConnectCloudflareVpn: (Boolean) -> Unit,
    onBrowseLocalOffline: () -> Unit = {},
    onRequestExit: () -> Unit
) {
    val connectBtnFocus = remember { FocusRequester() }

    // Trigger VPN connection ONCE on initial entry — never spam VpnService.prepare() while the Android system VPN confirmation dialog is open!
    LaunchedEffect(Unit) {
        delay(220L)
        if (!vpnStatus.connected && !isConnecting && !isPermissionDialogShowing) {
            onConnectCloudflareVpn(false)
        }
        delay(350L)
        runCatching { connectBtnFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF140826), BeinDarkBg)
                )
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = if (compact) 410.dp else 480.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(BeinSurface)
                .border(2.dp, BeinHighlightPurple, RoundedCornerShape(24.dp))
                .padding(if (compact) 18.dp else 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
        ) {
            BrandLogoBadge(
                modifier = Modifier.size(if (compact) 62.dp else 80.dp),
                cornerRadius = 18.dp
            )

            Text(
                text = APP_BRAND_NAME,
                color = Color.White,
                fontSize = if (compact) 19.sp else 24.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Box(
                modifier = Modifier.padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = BeinHighlightPurple,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(if (compact) 48.dp else 60.dp)
                )
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(if (compact) 28.dp else 36.dp)
                )
            }

            Text(
                text = if (isPermissionDialogShowing) {
                    AppStrings.tr(
                        lang,
                        "🔒 يرجى الضغط على «موافق / OK» في نافذة الأندرويد لتأكيد تشغيل الـ VPN لأول مرة",
                        "🔒 Veuillez appuyer sur « OK » dans la fenêtre Android pour autoriser le VPN",
                        "🔒 Please press 'OK' on the Android system prompt to allow VPN first time"
                    )
                } else {
                    AppStrings.tr(
                        lang,
                        "🔒 جارٍ تشغيل نفق الـ VPN الآمن تلقائياً... يرجى الانتظار لحظات",
                        "🔒 Connexion automatique au tunnel VPN sécurisé en cours...",
                        "🔒 Automatically connecting internal secure VPN tunnel..."
                    )
                },
                color = Color.White,
                fontSize = if (compact) 14.sp else 17.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Text(
                text = vpnStatus.statusText.ifBlank {
                    AppStrings.tr(
                        lang,
                        "يتم الاتصال تلقائياً بدون الحاجة لأي نقر وسيفتح المشغل فوراً",
                        "Ouverture automatique dès l'activation du tunnel",
                        "Opening player automatically once tunnel is ready"
                    )
                },
                color = Color(0xFFE2E8F0),
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            BeinFocusableButton(
                text = AppStrings.tr(
                    lang,
                    "🔒 تفعيل اتصال الـ VPN الآمن والمتابعة الآن",
                    "🔒 Activer le VPN sécurisé maintenant",
                    "🔒 Activate Secure VPN & Continue Now"
                ),
                isPrimary = true,
                compact = compact,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(connectBtnFocus),
                onClick = { onConnectCloudflareVpn(true) }
            )

            if (hasLocalLibrary) {
                BeinFocusableButton(
                    text = AppStrings.tr(
                        lang,
                        "📂 تصفح المكتبة المحفوظة محلياً فوراً",
                        "📂 Parcourir la bibliothèque locale immédiatement",
                        "📂 Browse Stored Local Library Now"
                    ),
                    isPrimary = false,
                    compact = compact,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBrowseLocalOffline
                )
            }
        }
    }
}

/**
 * Professional Subscription Expired / Inactive Gate:
 * Displays the customer's Screen Code (DEV-XXXX-XXXX) and clear contact details
 * for Ali Sat (00213697372480) to renew/activate the player.
 */
@Composable
private fun AliSatSubscriptionExpiredGate(
    deviceId: String,
    deviceName: String,
    subscription: SubscriptionInfo,
    lang: AppUiLanguage,
    isLoading: Boolean,
    compact: Boolean,
    onVerifyNow: () -> Unit,
    onRedeemCode: (String) -> Unit,
    onRequestExit: () -> Unit
) {
    val context = LocalContext.current
    var directCodeInput by remember { mutableStateOf("") }
    var verifyFocused by remember { mutableStateOf(true) }
    var copyFocused by remember { mutableStateOf(false) }
    var exitFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF240F42), BeinDarkBg)
                )
            )
            .padding(if (compact) 8.dp else 16.dp),
        contentAlignment = Alignment.Center
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = if (compact) 600.dp else 720.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(BeinSurface)
                .border(2.dp, BeinHighlightPurple, RoundedCornerShape(24.dp))
                .padding(if (compact) 16.dp else 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
        ) {
            // Centered Brand Header & Status Badge
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    BrandLogoBadge(
                        modifier = Modifier.size(if (compact) 54.dp else 70.dp),
                        cornerRadius = 16.dp
                    )
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "انتهت صلاحية اشتراك المشغل",
                            "Abonnement du lecteur expiré",
                            "Player Subscription Expired"
                        ),
                        color = Color.White,
                        fontSize = if (compact) 18.sp else 23.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (subscription.expireDate.isNotBlank()) {
                            AppStrings.tr(
                                lang,
                                "تاريخ انتهاء الصلاحية: ${subscription.expireDate} • خدمة الـ VPN والمشغل متوقفة لحين التجديد",
                                "Expiré le : ${subscription.expireDate} • Lecteur et VPN verrouillés",
                                "Expired on: ${subscription.expireDate} • Player & VPN locked until renewal"
                            )
                        } else {
                            AppStrings.tr(
                                lang,
                                "الاشتراك غير مفعّل أو منتهي الصلاحية • لا يتم فتح المشغل أو الـ VPN إلا باشتراك ساري المفعول",
                                "Abonnement inactif • Le lecteur et le VPN nécessitent un abonnement valide",
                                "Subscription inactive • Player & VPN require an active subscription"
                            )
                        },
                        color = Color(0xFFFCA5A5),
                        fontSize = if (compact) 13.sp else 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Centered Two-Column Info Cards: (1) Screen Code Card + (2) Ali Sat Contact Card
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Card 1: Screen Code
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(BeinDarkBg)
                            .border(1.8.dp, BeinHighlightPurple, RoundedCornerShape(16.dp))
                            .clickable { copyDeviceCodeToClipboard(context, deviceId) }
                            .padding(if (compact) 12.dp else 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = AppStrings.tr(
                                lang,
                                "🖥️ رمز الشاشة الخاص بجهازك",
                                "🖥️ Votre Code Écran",
                                "🖥️ Your Screen Code"
                            ),
                            color = Color(0xFFE2E8F0),
                            fontSize = if (compact) 13.sp else 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = deviceId,
                                color = Color.White,
                                fontSize = if (compact) 19.sp else 24.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 2.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        Text(
                            text = AppStrings.tr(lang, "اضغط لنسخ الرمز وتقديمه للتفعيل", "Cliquez pour copier", "Tap to copy code"),
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Card 2: Ali Sat Official Renewal Contact
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(BeinPrimaryPurple, Color(0xFF210E3A))
                                )
                            )
                            .border(1.8.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                            .padding(if (compact) 12.dp else 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = AppStrings.tr(
                                lang,
                                "📞 لتجديد الاشتراك تواصل مع علي سات",
                                "📞 Contactez Ali Sat pour renouveler",
                                "📞 Contact Ali Sat to Renew"
                            ),
                            color = Color(0xFFE2E8F0),
                            fontSize = if (compact) 13.sp else 15.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = ALI_SAT_PHONE_NUMBER,
                                color = Color.White,
                                fontSize = if (compact) 19.sp else 24.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.5.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        Text(
                            text = AppStrings.tr(lang, "أرسل رمز الشاشة لـ علي سات للتفعيل الفوري", "Envoyez votre Code Écran à Ali Sat", "Send your Screen Code to Ali Sat"),
                            color = Color.White,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // Direct Activation Voucher Input Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = directCodeInput,
                        onValueChange = { directCodeInput = formatVoucherCodeInput(it) },
                        placeholder = {
                            Text(
                                AppStrings.tr(lang, "أدخل رمز القسيمة هنا (مثال: A1B2-C3D4)", "Code d'activation (ex: A1B2-C3D4)", "Enter Voucher Code (e.g. A1B2-C3D4)"),
                                color = Color(0xFFBFA7F2),
                                fontSize = if (compact) 13.sp else 15.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = if (compact) 16.sp else 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp,
                            textAlign = TextAlign.Center
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "📋 لصق", "📋 Coller", "📋 Paste"),
                        isPrimary = false,
                        compact = compact,
                        onClick = {
                            val pasted = pasteVoucherFromClipboard(context)
                            if (pasted.isNotBlank()) directCodeInput = pasted
                        }
                    )
                    BeinFocusableButton(
                        text = if (isLoading) "⏳..." else AppStrings.tr(lang, "تفعيل الكود ✓", "Activer ✓", "Activate ✓"),
                        isPrimary = true,
                        compact = compact,
                        onClick = { onRedeemCode(directCodeInput) }
                    )
                }
            }

            // Action Buttons Row (Centered)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onVerifyNow,
                        enabled = !isLoading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (verifyFocused) Color.White else BeinPrimaryPurple,
                            contentColor = if (verifyFocused) Color.Black else Color.White
                        ),
                        modifier = Modifier
                            .weight(1.3f)
                            .height(if (compact) 44.dp else 50.dp)
                            .scale(if (verifyFocused) 1.03f else 1.0f)
                            .border(
                                width = if (verifyFocused) 3.dp else 1.5.dp,
                                color = if (verifyFocused) BeinFocusGlow else Color.White,
                                shape = RoundedCornerShape(25.dp)
                            )
                            .onFocusChanged { verifyFocused = it.isFocused }
                            .focusable()
                    ) {
                        Text(
                            text = if (isLoading) {
                                AppStrings.tr(lang, "⏳ جارٍ التحقق من التفعيل...", "⏳ Vérification...", "⏳ Checking...")
                            } else {
                                AppStrings.tr(
                                    lang,
                                    "🔄 تحقق من التفعيل وتجديد الاشتراك الآن",
                                    "🔄 Vérifier le renouvellement",
                                    "🔄 Check Renewal & Activate Now"
                                )
                            },
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 13.sp else 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Button(
                        onClick = { copyDeviceCodeToClipboard(context, deviceId) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (copyFocused) Color.White else BeinCard,
                            contentColor = if (copyFocused) Color.Black else Color.White
                        ),
                        modifier = Modifier
                            .weight(0.75f)
                            .height(if (compact) 44.dp else 50.dp)
                            .scale(if (copyFocused) 1.03f else 1.0f)
                            .border(2.dp, if (copyFocused) BeinFocusGlow else BeinHighlightPurple, RoundedCornerShape(25.dp))
                            .onFocusChanged { copyFocused = it.isFocused }
                            .focusable()
                    ) {
                        Text(
                            AppStrings.tr(lang, "📋 نسخ الرمز", "📋 Copier", "📋 Copy Code"),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 13.sp else 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Button(
                        onClick = onRequestExit,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (exitFocused) CrimsonAlert else Color(0xFF281220),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .weight(0.55f)
                            .height(if (compact) 44.dp else 50.dp)
                            .scale(if (exitFocused) 1.03f else 1.0f)
                            .border(1.5.dp, if (exitFocused) Color.White else CrimsonAlert, RoundedCornerShape(25.dp))
                            .onFocusChanged { exitFocused = it.isFocused }
                            .focusable()
                    ) {
                        Text(
                            AppStrings.tr(lang, "خروج", "Quitter", "Exit"),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 13.sp else 15.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

/**
 * Customer XTREAM Space Manager View:
 * • Integrated with standalone customer_portal.php & api.php via Screen Code
 * • Verifies player subscription validity before allowing new playlists
 */
@Composable
private fun MultiPlaylistXtreamManagerView(
    deviceId: String,
    subscription: SubscriptionInfo?,
    playlists: List<XtreamAccountConfig>,
    showPortalMain: Boolean = true,
    portalStatsLabel: String = "",
    playlistMode: PlaylistDisplayMode,
    activePlaylistFilterId: String,
    lang: AppUiLanguage,
    isSyncing: Boolean,
    compact: Boolean,
    onChangePlaylistMode: (PlaylistDisplayMode) -> Unit,
    onSelectActivePlaylist: (String) -> Unit,
    onAddOrUpdatePlaylist: (XtreamAccountConfig) -> Unit,
    onMovePlaylist: (Int, Int) -> Unit,
    onTogglePlaylistEnabled: (XtreamAccountConfig) -> Unit,
    onDeletePlaylist: (XtreamAccountConfig) -> Unit,
    onPullRemoteListsByScreenCode: () -> Unit
) {
    val context = LocalContext.current
    val localScope = rememberCoroutineScope()
    var showAddForm by remember { mutableStateOf(false) }
    var showTransferForm by remember { mutableStateOf(false) }
    var targetTransferCode by remember { mutableStateOf("") }
    var isTransferring by remember { mutableStateOf(false) }
    var isAutoDiscoveringHost by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf("") }
    var serverName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val isSubActive = subscription == null || (subscription.active && !subscription.isExpired)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (compact) 8.dp else 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
    ) {
        // 1. Sleek Top Control Bar (Screen Code + Quick Actions)
        item {
            Row(
                modifier = Modifier
                    .widthIn(max = 820.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF140826), BeinCard)
                        )
                    )
                    .border(1.5.dp, BeinHighlightPurple, RoundedCornerShape(16.dp))
                    .padding(horizontal = if (compact) 12.dp else 16.dp, vertical = if (compact) 10.dp else 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column {
                        Text(
                            text = AppStrings.tr(lang, "📲 فضاء الزبون", "📲 Espace Client", "📲 Customer Space"),
                            color = Color(0xFFE2E8F0),
                            fontSize = if (compact) 13.sp else 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = deviceId,
                                color = Color.White,
                                fontSize = if (compact) 18.sp else 20.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                    if (subscription != null && subscription.active) {
                        Text(
                            text = "⏳ ${subscription.daysLeft} يوم",
                            color = EmeraldLive,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .background(EmeraldLive.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                                .border(1.dp, EmeraldLive, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isSubActive) {
                        BeinFocusableButton(
                            text = if (showAddForm) {
                                AppStrings.tr(lang, "✕ إغلاق", "✕ Fermer", "✕ Close")
                            } else {
                                AppStrings.tr(lang, "➕ إضافة قائمة", "➕ Ajouter", "➕ Add List")
                            },
                            isPrimary = true,
                            compact = compact,
                            onClick = {
                                editingId = ""
                                serverName = ""
                                serverUrl = ""
                                username = ""
                                password = ""
                                showAddForm = !showAddForm
                            }
                        )
                    }
                    BeinFocusableButton(
                        text = if (isSyncing) {
                            AppStrings.tr(lang, "⏳ جارٍ السحب...", "⏳ Sync...", "⏳ Syncing...")
                        } else {
                            AppStrings.tr(lang, "🔄 استعادة الجديد", "🔄 Récupérer", "🔄 Sync New")
                        },
                        isPrimary = false,
                        compact = compact,
                        onClick = onPullRemoteListsByScreenCode
                    )
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "🔄 نقل لشاشة أخرى", "🔄 Transférer", "🔄 Transfer"),
                        isPrimary = showTransferForm,
                        compact = compact,
                        onClick = { showTransferForm = !showTransferForm }
                    )
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "📋 نسخ", "📋 Copier", "📋 Copy"),
                        isPrimary = false,
                        compact = compact,
                        onClick = { copyDeviceCodeToClipboard(context, deviceId) }
                    )
                }
            }
        }

        if (showTransferForm) {
            item {
                Column(
                    modifier = Modifier
                        .widthIn(max = 820.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(BeinCard)
                        .border(2.dp, Color(0xFFF59E0B), RoundedCornerShape(16.dp))
                        .padding(if (compact) 12.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "🔄 نقل الشاشة والاشتراك وجميع القوائم إلى جهاز آخر",
                            "🔄 Transférer l'abonnement et les listes vers un autre appareil",
                            "🔄 Transfer Subscription & Lists to Another Device"
                        ),
                        color = Color(0xFFFDE68A),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp
                    )
                    OutlinedTextField(
                        value = targetTransferCode,
                        onValueChange = { targetTransferCode = it.uppercase() },
                        label = { Text(AppStrings.tr(lang, "رمز الشاشة الجديد (DEV-XXXX-XXXX)", "Nouveau code (DEV-XXXX-XXXX)", "New Screen Code (DEV-XXXX-XXXX)")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    BeinFocusableButton(
                        text = if (isTransferring) {
                            "⏳ جارٍ نقل الاشتراك والقوائم..."
                        } else {
                            AppStrings.tr(lang, "🚀 تأكيد النقل الفوري", "🚀 Confirmer le transfert", "🚀 Confirm Transfer")
                        },
                        isPrimary = true,
                        compact = compact,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (targetTransferCode.isBlank() || isTransferring) return@BeinFocusableButton
                            isTransferring = true
                            localScope.launch {
                                val res = ChannelRepository.transferDeviceToNewScreenCode(deviceId, targetTransferCode)
                                isTransferring = false
                                res.onSuccess { msg ->
                                    showTransferForm = false
                                    targetTransferCode = ""
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    onPullRemoteListsByScreenCode()
                                }.onFailure { err ->
                                    Toast.makeText(context, err.message ?: "تعذّر نقل الشاشة", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }
            }
        }

        // 2. Compact Display Mode Toggle (Merge All vs Separate)
        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 820.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinCard)
                    .border(1.dp, BeinBorder, RoundedCornerShape(14.dp))
                    .padding(if (compact) 10.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PlaylistDisplayMode.values().forEach { mode ->
                        val selected = playlistMode == mode
                        BeinFocusableButton(
                            text = if (mode == PlaylistDisplayMode.MERGE_ALL) {
                                AppStrings.tr(lang, "🔀 دمج جميع القوائم", "🔀 Fusionner tout", "🔀 Merge All")
                            } else {
                                AppStrings.tr(lang, "📂 فصل كل قائمة لوحدها", "📂 Listes séparées", "📂 Separate Lists")
                            },
                            isPrimary = selected,
                            compact = compact,
                            modifier = Modifier.weight(1f),
                            onClick = { onChangePlaylistMode(mode) }
                        )
                    }
                }

                if (playlistMode == PlaylistDisplayMode.SEPARATE_LISTS && playlists.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(playlists, key = { idx, pl -> "${idx}_${pl.id}" }) { _, pl ->
                            BeinFocusableButton(
                                text = "⚡ ${pl.serverName.ifBlank { "XTREAM" }}",
                                isPrimary = activePlaylistFilterId == pl.id,
                                compact = true,
                                onClick = { onSelectActivePlaylist(pl.id) }
                            )
                        }
                        if (showPortalMain) {
                            item {
                                BeinFocusableButton(
                                    text = AppStrings.tr(lang, "📡 الرئيسية", "📡 Principale", "📡 Main"),
                                    isPrimary = activePlaylistFilterId == "PORTAL_MAIN",
                                    compact = true,
                                    onClick = { onSelectActivePlaylist("PORTAL_MAIN") }
                                )
                            }
                        }
                        item {
                            BeinFocusableButton(
                                text = AppStrings.tr(lang, "🔀 الكل", "🔀 Toutes", "🔀 All"),
                                isPrimary = activePlaylistFilterId == "ALL",
                                compact = true,
                                onClick = { onSelectActivePlaylist("ALL") }
                            )
                        }
                    }
                }
            }
        }

        // 3. Clean Add / Edit XTREAM Form
        if (showAddForm && isSubActive) {
            item {
                var useAutoHostMode by remember(editingId) { mutableStateOf(editingId.isBlank() && serverUrl.isBlank()) }
                val nameFocus = remember { FocusRequester() }
                val urlFocus = remember { FocusRequester() }
                val userFocus = remember { FocusRequester() }
                val passFocus = remember { FocusRequester() }
                val saveBtnFocus = remember { FocusRequester() }

                LaunchedEffect(showAddForm, useAutoHostMode) {
                    delay(100L)
                    runCatching {
                        if (useAutoHostMode) userFocus.requestFocus() else nameFocus.requestFocus()
                    }
                }

                Column(
                    modifier = Modifier
                        .widthIn(max = 820.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(BeinCard)
                        .border(2.dp, BeinHighlightPurple, RoundedCornerShape(16.dp))
                        .padding(if (compact) 12.dp else 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (editingId.isNotBlank()) "✏️ تعديل القائمة" else "➕ إضافة قائمة جديدة (تحقق تلقائي أو إدخال يدوي مباشر)",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BeinFocusableButton(
                            text = AppStrings.tr(lang, "🤖 تلقائي ذكي (اليوزر والباسورد فقط)", "🤖 Auto (User & Pass)", "🤖 Smart Auto (User & Pass)"),
                            isPrimary = useAutoHostMode,
                            compact = compact,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                useAutoHostMode = true
                                serverUrl = ""
                                serverName = ""
                            }
                        )
                        BeinFocusableButton(
                            text = AppStrings.tr(lang, "🌐 تحديد الهوست يدوياً", "🌐 Hôte Manuel", "🌐 Manual Host"),
                            isPrimary = !useAutoHostMode,
                            compact = compact,
                            modifier = Modifier.weight(1f),
                            onClick = { useAutoHostMode = false }
                        )
                    }

                    if (!useAutoHostMode) {
                        OutlinedTextField(
                            value = serverName,
                            onValueChange = { serverName = it },
                            label = { Text(AppStrings.tr(lang, "اسم القائمة", "Nom de la liste", "Playlist Name")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { runCatching { urlFocus.requestFocus() } }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(nameFocus)
                        )

                        OutlinedTextField(
                            value = serverUrl,
                            onValueChange = { inputVal ->
                                val split = SmartM3uParser.tryExtractXtreamCredentials(inputVal)
                                if (split != null) {
                                    serverUrl = split.serverUrl
                                    username = split.username
                                    password = split.password
                                } else {
                                    serverUrl = inputVal
                                }
                            },
                            label = { Text(AppStrings.tr(lang, "رابط السيرفر أو M3U", "URL Serveur ou M3U", "Server URL or M3U")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { runCatching { userFocus.requestFocus() } }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(urlFocus)
                        )
                    } else {
                        Text(
                            text = AppStrings.tr(
                                lang,
                                "✨ في الوضع التلقائي: أدخل اسم المستخدم وكلمة المرور فقط ليتم التحقق التلقائي وحفظ القائمة في قاعدة بيانات السيرفر والجهاز!",
                                "✨ Mode auto : entrez uniquement utilisateur et mot de passe pour vérification et sauvegarde !",
                                "✨ Auto mode: enter Username & Password only for automatic verification and dual database save!"
                            ),
                            color = Color(0xFFE2E8F0),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Username") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { runCatching { passFocus.requestFocus() } }),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(userFocus)
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { runCatching { saveBtnFocus.requestFocus() } }),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(passFocus)
                        )
                    }

                    BeinFocusableButton(
                        text = if (isAutoDiscoveringHost) {
                            "🔍 جارٍ التحقق التلقائي وتسجيل السيرفر المتوافق..."
                        } else if (!useAutoHostMode) {
                            AppStrings.tr(
                                lang,
                                "💾 إدخال مباشر وتسجيل فوري في قاعدة البيانات",
                                "💾 Enregistrement Direct en Base de Données",
                                "💾 Direct Save to Database"
                            )
                        } else {
                            AppStrings.tr(
                                lang,
                                "🔍 تحقق تلقائي ذكي وتسجيل في قاعدة البيانات",
                                "🔍 Vérification Auto & Enregistrement",
                                "🔍 Smart Auto-Verify & Save to DB"
                            )
                        },
                        isPrimary = true,
                        compact = compact,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(saveBtnFocus),
                        onClick = {
                            if (isAutoDiscoveringHost) return@BeinFocusableButton
                            val existingPl = playlists.find { it.id == editingId }
                            val effectiveUrl = serverUrl.trim().ifBlank { existingPl?.serverUrl.orEmpty() }
                            val effectivePass = password.trim().ifBlank { existingPl?.password.orEmpty() }
                            if (effectiveUrl.isNotBlank() && (username.isNotBlank() && effectivePass.isNotBlank() || effectiveUrl.contains(".m3u", ignoreCase = true))) {
                                val targetId = editingId.ifBlank { UUID.randomUUID().toString().take(8) }
                                val newCfg = XtreamAccountConfig(
                                    id = targetId,
                                    serverName = serverName.trim().ifBlank { existingPl?.serverName ?: "XTREAM #${playlists.size + 1}" },
                                    serverUrl = effectiveUrl,
                                    username = username.trim(),
                                    password = effectivePass,
                                    enabled = true,
                                    orderIndex = existingPl?.orderIndex ?: playlists.size
                                )
                                onAddOrUpdatePlaylist(newCfg)
                                editingId = ""
                                serverName = ""
                                serverUrl = ""
                                username = ""
                                password = ""
                                showAddForm = false
                            } else if (serverUrl.isBlank() && username.isNotBlank() && password.isNotBlank()) {
                                isAutoDiscoveringHost = true
                                localScope.launch {
                                    val discovered = ChannelRepository.autoDiscoverXtreamHostViaPortal(
                                        deviceId = deviceId,
                                        username = username.trim(),
                                        password = password.trim()
                                    )
                                    isAutoDiscoveringHost = false
                                    if (discovered != null) {
                                        val (foundUrl, foundName) = discovered
                                        val targetId = editingId.ifBlank { UUID.randomUUID().toString().take(8) }
                                        val newCfg = XtreamAccountConfig(
                                            id = targetId,
                                            serverName = serverName.trim().ifBlank { foundName },
                                            serverUrl = foundUrl,
                                            username = username.trim(),
                                            password = password.trim(),
                                            enabled = true,
                                            orderIndex = playlists.size
                                        )
                                        Toast.makeText(context, "🎯 تم التعرّف التلقائي على السيرفر: $foundName", Toast.LENGTH_LONG).show()
                                        onAddOrUpdatePlaylist(newCfg)
                                        editingId = ""
                                        serverName = ""
                                        serverUrl = ""
                                        username = ""
                                        password = ""
                                        showAddForm = false
                                    } else {
                                        val offline = !ExpiryAlertHelper.isInternetAvailable(context)
                                        val tip = when {
                                            offline -> "📡 لا يوجد اتصال إنترنت مستقر — تحقق من الشبكة ثم أعد المحاولة."
                                            else -> "🔑 لم يُعثر على سيرفر مطابق. تأكد من Username/Password أو استخدم «إدخال يدوي» مع رابط الهوست."
                                        }
                                        Toast.makeText(context, tip, Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    "⚠️ يرجى إدخال اسم المستخدم وكلمة المرور (ويمكنك ترك الهوست فارغاً للاكتشاف التلقائي!)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                }
            }
        }

        // 4. Clean Playlists Cards List
        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 820.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BeinCard)
                    .border(1.dp, BeinBorder, RoundedCornerShape(16.dp))
                    .padding(if (compact) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val totalVisibleListsCount = playlists.size + (if (showPortalMain) 1 else 0)
                Text(
                    text = AppStrings.tr(
                        lang,
                        "📋 القوائم المتصلة ($totalVisibleListsCount)",
                        "📋 Listes connectées ($totalVisibleListsCount)",
                        "📋 Connected Lists ($totalVisibleListsCount)"
                    ),
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 15.sp else 17.sp
                )

                if (showPortalMain) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0C241B))
                            .border(1.5.dp, EmeraldLive, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "📡 القائمة الرئيسية (محمية عبر VPN 🔒)",
                                    color = EmeraldLive,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp
                                )
                            }
                            if (portalStatsLabel.isNotBlank()) {
                                Text(
                                    text = "متصل ✓ ($portalStatsLabel)",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        BeinFocusableButton(
                            text = AppStrings.tr(lang, "عرض الرئيسية ⚡", "Principale ⚡", "View Main ⚡"),
                            isPrimary = activePlaylistFilterId == "PORTAL_MAIN",
                            compact = true,
                            onClick = {
                                onChangePlaylistMode(PlaylistDisplayMode.SEPARATE_LISTS)
                                onSelectActivePlaylist("PORTAL_MAIN")
                            }
                        )
                    }
                }

                if (playlists.isEmpty()) {
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "لا توجد قوائم مضافة حالياً • أضف قائمة من الزر أعلاه أو عبر رمز الشاشة $deviceId",
                            "Aucune liste • Ajoutez une liste ou utilisez le code $deviceId",
                            "No lists yet • Add a list above or via Screen Code $deviceId"
                        ),
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                } else {
                    playlists.forEachIndexed { idx, pl ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (pl.enabled) BeinSurface else Color(0xFF121217))
                                .border(
                                    width = if (ExpiryAlertHelper.isDateExpiringSoon(pl.expDate)) 2.dp else 1.dp,
                                    color = when {
                                        ExpiryAlertHelper.isDateExpiringSoon(pl.expDate) -> CrimsonAlert
                                        pl.enabled -> BeinHighlightPurple
                                        else -> BeinBorder
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "${idx + 1}. ${pl.serverName.ifBlank { "XTREAM" }}",
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 15.sp
                                    )
                                    if (pl.expDate.isNotBlank()) {
                                        Text(
                                            text = "📅 ${pl.expDate}",
                                            color = if (ExpiryAlertHelper.isDateExpiringSoon(pl.expDate)) Color(0xFFFCA5A5) else EmeraldLive,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    text = pl.statusNote,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                DpadSmallControlBtn(
                                    label = "▲",
                                    enabled = idx > 0,
                                    onClick = { onMovePlaylist(idx, idx - 1) }
                                )
                                DpadSmallControlBtn(
                                    label = "▼",
                                    enabled = idx < playlists.lastIndex,
                                    onClick = { onMovePlaylist(idx, idx + 1) }
                                )
                                DpadSmallControlBtn(
                                    label = "✏️",
                                    enabled = isSubActive,
                                    onClick = {
                                        editingId = pl.id
                                        serverName = pl.serverName
                                        // إخفاء الهوست المستعاد وكلمة المرور كلياً عند التعديل لحمايتهما من الكشف
                                        serverUrl = ""
                                        username = pl.username
                                        password = ""
                                        showAddForm = true
                                    }
                                )
                                DpadSmallControlBtn(
                                    label = "🔄",
                                    enabled = !isSyncing && isSubActive,
                                    onClick = { onAddOrUpdatePlaylist(pl) }
                                )
                                DpadSmallControlBtn(
                                    label = if (pl.enabled) "✓" else "⏸",
                                    enabled = true,
                                    onClick = { onTogglePlaylistEnabled(pl) }
                                )
                                DpadSmallControlBtn(
                                    label = "✕",
                                    enabled = true,
                                    onClick = { onDeletePlaylist(pl) }
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
internal fun BeinFocusableButton(
    text: String,
    isPrimary: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .scale(if (focused) 1.05f else 1.0f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    focused -> Color.White
                    isPrimary -> BeinPrimaryPurple
                    else -> BeinSurface
                }
            )
            .border(
                width = if (focused) 2.5.dp else 1.2.dp,
                color = if (focused) BeinFocusGlow else if (isPrimary) BeinHighlightPurple else BeinBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    ev.nativeKeyEvent.repeatCount == 0 &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    SafeClickGuard.runThrottled("UI_BTN_CLICK", 200L) { onClick() }
                    true
                } else false
            }
            .focusable()
            .clickable { SafeClickGuard.runThrottled("UI_BTN_CLICK", 200L) { onClick() } }
            .padding(
                horizontal = when { dense -> 7.dp; compact -> 10.dp; else -> 14.dp },
                vertical = when { dense -> 3.dp; compact -> 7.dp; else -> 9.dp }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (focused) Color.Black else Color.White,
            fontSize = when { dense -> 10.sp; compact -> 12.sp; else -> 14.sp },
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SeriesEpisodesModal(
    series: TvChannel,
    isFavorite: Boolean = false,
    onToggleSeriesFavorite: () -> Unit = {},
    xtreamLists: List<XtreamAccountConfig>,
    vodWatchProgressMap: Map<String, VodWatchProgress> = emptyMap(),
    compact: Boolean,
    onPlayEpisode: (SeriesEpisode) -> Unit,
    onDismiss: () -> Unit
) {
    val cachedInitial = remember(series.stableKey, series.seriesId) {
        XtreamRepository.getCachedSeriesEpisodesNow(series)
    }
    var episodes by remember(series.stableKey, series.seriesId) {
        mutableStateOf(cachedInitial.orEmpty())
    }
    var loading by remember(series.stableKey, series.seriesId) {
        mutableStateOf(cachedInitial.isNullOrEmpty())
    }

    val lastWatchedEpisodePair = remember(episodes, vodWatchProgressMap, series.stableKey) {
        episodes.mapNotNull { ep ->
            val prog = vodWatchProgressMap[ep.url.trim()]
                ?: vodWatchProgressMap.values.firstOrNull { it.resolvedKey == ep.url.trim() }
            if (prog != null && prog.positionMs >= 3_000L) (ep to prog) else null
        }.maxByOrNull { it.second.updatedAt }
    }

    val episodesBySeason = remember(episodes) {
        episodes.groupBy { it.seasonNumber }.mapValues { (_, list) ->
            list.sortedBy { it.episodeNumber }
        }
    }
    val seasons = remember(episodesBySeason) {
        episodesBySeason.keys.sorted()
    }

    // Automatically select the last-watched season (or Season 1) so ALL Parts/Seasons and ALL Episodes
    // of the active season are immediately displayed together on a natural Series page!
    var selectedSeason by remember(series.stableKey, series.seriesId) {
        val initSeasons = cachedInitial?.map { it.seasonNumber }?.distinct()?.sorted().orEmpty()
        mutableStateOf(initSeasons.firstOrNull())
    }
    val seasonEpisodes = remember(episodesBySeason, selectedSeason, seasons) {
        val activeSeason = selectedSeason ?: seasons.firstOrNull()
        if (activeSeason == null) emptyList()
        else episodesBySeason[activeSeason].orEmpty()
    }

    val firstFocusRequester = remember { FocusRequester() }
    val episodesListState = remember { LazyListState() }

    LaunchedEffect(series.stableKey, series.seriesId) {
        if (episodes.isEmpty()) {
            loading = true
            episodes = XtreamRepository.fetchSeriesEpisodes(series, xtreamLists)
            loading = false
        }
        val distinctSeasons = episodes.map { it.seasonNumber }.distinct().sorted()
        val lastWatchedSeason = episodes.mapNotNull { ep ->
            val prog = vodWatchProgressMap[ep.url.trim()]
                ?: vodWatchProgressMap.values.firstOrNull { it.resolvedKey == ep.url.trim() }
            if (prog != null && prog.positionMs >= 3_000L) (ep.seasonNumber to prog.updatedAt) else null
        }.maxByOrNull { it.second }?.first

        if (lastWatchedSeason != null && distinctSeasons.contains(lastWatchedSeason)) {
            selectedSeason = lastWatchedSeason
        } else if (selectedSeason == null && distinctSeasons.isNotEmpty()) {
            selectedSeason = distinctSeasons.first()
        }
        delay(20L)
        runCatching { firstFocusRequester.requestFocus() }
    }

    LaunchedEffect(selectedSeason, episodes.size) {
        if (!loading && seasonEpisodes.isNotEmpty()) {
            val lastEpId = lastWatchedEpisodePair?.first?.id
            val targetIdx = if (lastEpId != null) {
                seasonEpisodes.indexOfFirst { it.id == lastEpId }
            } else -1
            if (targetIdx > 2) {
                runCatching { episodesListState.scrollToItem((targetIdx - 1).coerceAtLeast(0)) }
            } else if (targetIdx == -1) {
                runCatching { episodesListState.scrollToItem(0) }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 360.dp, max = 760.dp)
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(18.dp))
                .background(BeinSurface)
                .border(2.dp, BeinHighlightPurple, RoundedCornerShape(18.dp))
                .padding(if (compact) 12.dp else 18.dp)
        ) {
            // 1. Series Header: Title, Total Seasons & Episodes, Favorite Toggle, Close Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "📺 ${series.name}",
                        color = Color.White,
                        fontSize = if (compact) 16.sp else 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 2,
                        textAlign = TextAlign.Start
                    )
                    val activeS = selectedSeason ?: seasons.firstOrNull()
                    Text(
                        text = when {
                            loading -> "جارٍ تحميل جميع الأجزاء والحلقات..."
                            episodes.isEmpty() -> "لم يتم العثور على حلقات حالياً"
                            activeS != null -> "${seasons.size} أجزاء/مواسم • إجمالي ${episodes.size} حلقة — تعرض الآن الجزء $activeS (${seasonEpisodes.size} حلقة)"
                            else -> "${seasons.size} أجزاء/مواسم • ${episodes.size} حلقة"
                        },
                        color = Color(0xFFE2E8F0),
                        fontSize = if (compact) 12.sp else 13.sp,
                        textAlign = TextAlign.Start
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BeinFocusableButton(
                        text = if (isFavorite) "❤️ في المفضلة ✓" else "⭐ إضافة للمفضلة",
                        isPrimary = isFavorite,
                        compact = true,
                        onClick = onToggleSeriesFavorite
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                    }
                }
            }

            // 2. Continue Watching Last Episode Banner (if user watched any episode of this series)
            if (lastWatchedEpisodePair != null) {
                val (lastEp, lastProg) = lastWatchedEpisodePair
                Spacer(Modifier.height(8.dp))
                BeinFocusableButton(
                    text = "▶ متابعة المشاهدة: الجزء ${lastEp.seasonNumber} - الحلقة ${lastEp.episodeNumber} — ${lastEp.title} (${lastProg.formattedPosition})",
                    isPrimary = true,
                    compact = compact,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(firstFocusRequester),
                    onClick = { onPlayEpisode(lastEp) }
                )
            }

            // 3. Persistent All Seasons / Parts Bar (كل الأجزاء والمواسم ظاهرة دائماً للتنقل الطبيعي)
            if (!loading && seasons.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    itemsIndexed(seasons, key = { _, s -> "season_tab_$s" }) { _, seasonNum ->
                        val isSelectedSeason = (selectedSeason ?: seasons.firstOrNull()) == seasonNum
                        val count = episodesBySeason[seasonNum]?.size ?: 0
                        var sFocused by remember { mutableStateOf(false) }
                        Box(
                            modifier = Modifier
                                .height(if (compact) 32.dp else 38.dp)
                                .scale(if (sFocused) 1.05f else 1.0f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        sFocused -> Color.White
                                        isSelectedSeason -> BeinPrimaryPurple
                                        else -> BeinCard
                                    }
                                )
                                .border(
                                    width = if (sFocused) 2.5.dp else if (isSelectedSeason) 2.dp else 1.dp,
                                    color = if (sFocused) BeinFocusGlow else if (isSelectedSeason) Color.White else BeinBorder,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .focusable()
                                .onFocusChanged { sFocused = it.isFocused }
                                .clickable {
                                    SafeClickGuard.runThrottled("SEASON_$seasonNum", 60L) {
                                        selectedSeason = seasonNum
                                    }
                                }
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "📂 الجزء / الموسم $seasonNum ($count حلقة)",
                                color = if (sFocused) Color.Black else Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (compact) 12.sp else 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 4. Episodes List for the Active Season
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            } else if (episodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "تعذّر جلب قائمة الحلقات حالياً — تأكد من اتصال السيرفر أو أعد المحاولة",
                            color = Color.LightGray,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BeinFocusableButton(
                                text = "🔄 إعادة تحميل الحلقات",
                                isPrimary = true,
                                compact = compact,
                                onClick = {
                                    loading = true
                                }
                            )
                            if (series.url.isNotBlank() && !series.url.contains("action=get_series_info", ignoreCase = true)) {
                                BeinFocusableButton(
                                    text = "▶ تشغيل مباشر",
                                    isPrimary = false,
                                    compact = compact,
                                    onClick = {
                                        onPlayEpisode(
                                            SeriesEpisode(
                                                id = series.streamId.toString(),
                                                seasonNumber = 1,
                                                episodeNumber = 1,
                                                title = series.name,
                                                url = series.url,
                                                containerExtension = series.containerExtension.ifBlank { "mp4" }
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = episodesListState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(seasonEpisodes, key = { idx, ep -> "${idx}_${ep.seasonNumber}_${ep.id}" }) { idx, ep ->
                        var focused by remember { mutableStateOf(false) }
                        val epProg = vodWatchProgressMap[ep.url.trim()]
                            ?: vodWatchProgressMap.values.firstOrNull { it.resolvedKey == ep.url.trim() }
                        val isLastWatchedEp = lastWatchedEpisodePair?.first?.id == ep.id &&
                            lastWatchedEpisodePair.first.seasonNumber == ep.seasonNumber
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (idx == 0 && lastWatchedEpisodePair == null) {
                                        Modifier.focusRequester(firstFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                                .scale(if (focused) 1.02f else 1.0f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    when {
                                        focused -> BeinFocusGlow
                                        isLastWatchedEp -> Color(0xFF1E1038)
                                        else -> BeinCard
                                    }
                                )
                                .border(
                                    width = if (focused || isLastWatchedEp) 2.dp else 1.dp,
                                    color = when {
                                        focused -> BeinPrimaryPurple
                                        isLastWatchedEp -> EmeraldLive
                                        else -> BeinBorder
                                    },
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .focusable()
                                .onFocusChanged { focused = it.isFocused }
                                .clickable {
                                    SafeClickGuard.runThrottled("EP_${ep.id}", 60L) {
                                        onPlayEpisode(ep)
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "الجزء ${ep.seasonNumber} • الحلقة ${ep.episodeNumber} — ${ep.title.ifBlank { "حلقة ${ep.episodeNumber}" }}",
                                    color = if (focused) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (compact) 14.sp else 16.sp,
                                    maxLines = 2
                                )
                                if (epProg != null && epProg.positionMs >= 5_000L) {
                                    Text(
                                        text = if (epProg.isCompleted) {
                                            "✅ تمت المشاهدة (${epProg.formattedDuration})"
                                        } else {
                                            "⏯ متابعة من ${epProg.formattedPosition} / ${epProg.formattedDuration} (${(epProg.progressFraction * 100).toInt()}%)"
                                        },
                                        color = if (focused) Color(0xFF383846) else if (epProg.isCompleted) EmeraldLive else Color(0xFFFBBF24),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = if (compact) 11.sp else 13.sp
                                    )
                                } else if (ep.durationLabel.isNotBlank()) {
                                    Text(
                                        text = "⏱ المدة: ${ep.durationLabel}",
                                        color = if (focused) Color(0xFF383846) else Color(0xFFCBD5E1),
                                        fontSize = if (compact) 11.sp else 12.sp
                                    )
                                }
                            }
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = if (focused) BeinPrimaryPurple else if (epProg != null) EmeraldLive else Color.White
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(loading) {
        if (loading && episodes.isEmpty()) {
            episodes = XtreamRepository.fetchSeriesEpisodes(series, xtreamLists)
            loading = false
            val distinctSeasons = episodes.map { it.seasonNumber }.distinct().sorted()
            if (selectedSeason == null && distinctSeasons.isNotEmpty()) {
                selectedSeason = distinctSeasons.first()
            }
        }
    }
}


@Composable
private fun VodMoviesAndSeriesCatalogView(
    titleAr: String,
    emptyHintAr: String,
    items: List<TvChannel>,
    itemsByCategory: Map<String, List<TvChannel>>,
    categories: List<BouquetSummary>,
    favorites: List<TvChannel>,
    vodWatchProgressMap: Map<String, VodWatchProgress> = emptyMap(),
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    gridState: LazyGridState,
    categoryListState: LazyListState = remember { LazyListState() },
    lastWatchedKey: String?,
    restoreFocusTrigger: Int,
    compact: Boolean,
    onPlay: (TvChannel) -> Unit,
    onToggleFavorite: (TvChannel) -> Unit,
    onOpenXtream: () -> Unit,
    onRefresh: () -> Unit
) {
    var vodSearch by remember { mutableStateOf("") }
    val lastWatchedFocusRequester = remember { FocusRequester() }

    val watchedItemsList by produceState(
        initialValue = emptyList<TvChannel>(),
        items,
        vodWatchProgressMap
    ) {
        value = if (vodWatchProgressMap.isEmpty() || items.isEmpty()) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) {
                items.filter { ch ->
                    val p = vodWatchProgressMap[ch.stableKey]
                        ?: (if (ch.seriesId > 0) vodWatchProgressMap["SERIES_ID_${ch.playlistId}_${ch.seriesId}"] else null)
                        ?: vodWatchProgressMap[ch.url.trim()]
                    p != null && p.positionMs >= 3_000L
                }.sortedByDescending { ch ->
                    (vodWatchProgressMap[ch.stableKey]?.updatedAt
                        ?: (if (ch.seriesId > 0) vodWatchProgressMap["SERIES_ID_${ch.playlistId}_${ch.seriesId}"]?.updatedAt else null)
                        ?: vodWatchProgressMap[ch.url.trim()]?.updatedAt) ?: 0L
                }
            }
        }
    }

    val favoriteVodItems = remember(items, favorites) {
        if (favorites.isEmpty() || items.isEmpty()) emptyList()
        else {
            val favKeys = favorites.map { it.stableKey }.toHashSet()
            val favSeriesIds = favorites.filter { it.contentType == StreamContentType.SERIES && it.seriesId > 0 }
                .map { "${it.playlistId}_${it.seriesId}" }.toHashSet()
            items.filter {
                it.stableKey in favKeys ||
                    (it.contentType == StreamContentType.SERIES && it.seriesId > 0 && "${it.playlistId}_${it.seriesId}" in favSeriesIds)
            }
        }
    }

    // Instant O(1) category lookup + Favorites + Continue Watching filter + background Dispatchers.Default search filter
    val categorySlice = remember(items, itemsByCategory, selectedCategory, watchedItemsList, favoriteVodItems) {
        when (selectedCategory) {
            "الكل" -> items
            "__FAVORITES_VOD__" -> favoriteVodItems
            "__CONTINUE_WATCHING__" -> watchedItemsList
            else -> itemsByCategory[selectedCategory].orEmpty()
        }
    }

    val filtered by produceState(
        initialValue = categorySlice,
        categorySlice,
        vodSearch
    ) {
        val q = vodSearch.trim()
        value = if (q.isEmpty()) {
            categorySlice
        } else {
            withContext(Dispatchers.Default) {
                val qLow = q.lowercase(java.util.Locale.ROOT)
                categorySlice.filter { item -> item.matchesSearch(qLow) }
            }
        }
    }

    val targetWatchedIdx = remember(filtered, lastWatchedKey) {
        if (lastWatchedKey == null) -1
        else {
            val key = lastWatchedKey
            filtered.indexOfFirst { ch ->
                ch.stableKey == key ||
                    (ch.contentType == StreamContentType.SERIES && ch.seriesId > 0 &&
                        (key == ch.stableKey || key.endsWith("_${ch.seriesId}") || key.contains("${ch.playlistId}_${ch.seriesId}")))
            }
        }
    }

    // Windowed Lazy Paging: render 120 items per batch and expand automatically on scroll!
    var visibleLimit by remember(selectedCategory, vodSearch, items.size) {
        val minNeeded = if (targetWatchedIdx >= 0) targetWatchedIdx + 80 else 120
        mutableStateOf(minNeeded.coerceAtLeast(120).coerceAtMost(filtered.size.coerceAtLeast(120)))
    }
    val windowedItems = remember(filtered, visibleLimit) {
        if (filtered.size <= visibleLimit) filtered
        else filtered.subList(0, visibleLimit)
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= windowedItems.size - 24 && windowedItems.size < filtered.size
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            visibleLimit = (visibleLimit + 120).coerceAtMost(filtered.size)
        }
    }

    val favSet = remember(favorites) { favorites.map { it.stableKey }.toHashSet() }

    // Movies / Series: after Back from fullscreen, land focus on the same title that was playing
    LaunchedEffect(restoreFocusTrigger) {
        if (restoreFocusTrigger <= 0) return@LaunchedEffect
        delay(50L)
        val idx = targetWatchedIdx
        if (idx < 0 || filtered.isEmpty()) return@LaunchedEffect
        if (visibleLimit <= idx) {
            visibleLimit = (idx + 100).coerceAtMost(filtered.size)
        }
        val catIdx = categories.indexOfFirst { it.name == selectedCategory }
        if (catIdx >= 0) {
            runCatching { categoryListState.scrollToItem(catIdx) }
        }
        runCatching { gridState.scrollToItem((idx - 2).coerceAtLeast(0)) }
        for (attempt in 1..10) {
            delay(if (attempt <= 3) 30L else 55L)
            val visibleNow = gridState.layoutInfo.visibleItemsInfo.any { it.index == idx }
            if (!visibleNow) {
                runCatching { gridState.scrollToItem((idx - 1).coerceAtLeast(0)) }
            }
            if (runCatching { lastWatchedFocusRequester.requestFocus() }.isSuccess) break
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = titleAr,
                color = Color.White,
                fontSize = if (compact) 15.sp else 17.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BeinFocusableButton(
                    text = "📲 فضاء الزبون",
                    isPrimary = false,
                    compact = true,
                    onClick = onOpenXtream
                )
                CompactSearchBar(
                    query = vodSearch,
                    onQueryChange = { vodSearch = it },
                    compact = compact,
                    modifier = Modifier
                        .width(if (compact) 145.dp else 200.dp)
                        .height(if (compact) 28.dp else 34.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        LazyRow(
            state = categoryListState,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "vod_favorites_chip") {
                val isSelected = selectedCategory == "__FAVORITES_VOD__"
                var focused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .height(if (compact) 28.dp else 34.dp)
                        .scale(if (focused) 1.05f else 1.0f)
                        .clip(RoundedCornerShape(14.dp))
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .background(
                            when {
                                focused -> Color.White
                                isSelected -> Color(0xFFBE185D)
                                else -> Color(0xFF2B0B1E)
                            }
                        )
                        .border(
                            width = if (focused) 2.5.dp else 1.5.dp,
                            color = if (focused) BeinFocusGlow else Color(0xFFFF4D6D),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelectCategory("__FAVORITES_VOD__") }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "★ المفضلة (${favoriteVodItems.size})",
                        color = if (focused) Color.Black else Color.White,
                        fontSize = if (compact) 12.sp else 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
            item(key = "continue_watching_chip") {
                val isSelected = selectedCategory == "__CONTINUE_WATCHING__"
                var focused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .height(if (compact) 28.dp else 34.dp)
                        .scale(if (focused) 1.05f else 1.0f)
                        .clip(RoundedCornerShape(14.dp))
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .background(
                            when {
                                focused -> Color.White
                                isSelected -> EmeraldLive
                                else -> Color(0xFF0C241B)
                            }
                        )
                        .border(
                            width = if (focused) 2.5.dp else 1.5.dp,
                            color = if (focused) BeinFocusGlow else EmeraldLive,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelectCategory("__CONTINUE_WATCHING__") }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⏯ متابعة المشاهدة (${watchedItemsList.size})",
                        color = if (focused || isSelected) Color.Black else EmeraldLive,
                        fontSize = if (compact) 12.sp else 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
            itemsIndexed(categories, key = { idx, cat -> "${idx}_${cat.name}" }) { _, cat ->
                val isSelected = selectedCategory == cat.name
                var focused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .height(if (compact) 28.dp else 34.dp)
                        .scale(if (focused) 1.05f else 1.0f)
                        .clip(RoundedCornerShape(14.dp))
                        .onFocusChanged { focused = it.isFocused }
                        .focusable()
                        .background(
                            when {
                                focused -> Color.White
                                isSelected -> BeinPrimaryPurple
                                else -> BeinCard
                            }
                        )
                        .border(
                            width = if (focused) 2.5.dp else 1.dp,
                            color = if (focused) BeinFocusGlow else if (isSelected) Color.White else BeinBorder,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { onSelectCategory(cat.name) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${cat.name} (${cat.count})",
                        color = if (focused) Color.Black else Color.White,
                        fontSize = if (compact) 12.sp else 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(emptyHintAr, color = Color.LightGray, fontSize = if (compact) 13.sp else 16.sp, textAlign = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BeinFocusableButton(
                            text = "فتح فضاء الزبون 📲",
                            isPrimary = true,
                            compact = compact,
                            onClick = onOpenXtream
                        )
                        BeinFocusableButton(
                            text = "تحديث الآن 🔄",
                            isPrimary = false,
                            compact = compact,
                            onClick = onRefresh
                        )
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(5),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(windowedItems, key = { idx, item -> "${idx}_${item.stableKey}" }) { idx, item ->
                    val itemMod = if (idx == targetWatchedIdx) {
                        Modifier.focusRequester(lastWatchedFocusRequester)
                    } else {
                        Modifier
                    }
                    BeinChannelCard(
                        channel = item,
                        isFavorite = favSet.contains(item.stableKey) ||
                            (item.contentType == StreamContentType.SERIES && item.seriesId > 0 &&
                                favorites.any { it.contentType == StreamContentType.SERIES && it.seriesId == item.seriesId && it.playlistId == item.playlistId }),
                        isLastWatched = item.stableKey == lastWatchedKey,
                        watchProgress = vodWatchProgressMap[item.stableKey]
                            ?: (if (item.seriesId > 0) vodWatchProgressMap["SERIES_ID_${item.playlistId}_${item.seriesId}"] else null)
                            ?: vodWatchProgressMap[item.url.trim()],
                        compact = compact,
                        modifier = itemMod,
                        onClick = { onPlay(item) },
                        onToggleFavorite = { onToggleFavorite(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ExitConfirmationModal(
    lang: AppUiLanguage,
    compact: Boolean,
    onStay: () -> Unit,
    onConfirmExit: () -> Unit
) {
    var stayFocused by remember { mutableStateOf(true) }
    var exitFocused by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onStay) {
        Column(
            modifier = Modifier
                .widthIn(min = if (compact) 280.dp else 330.dp, max = if (compact) 380.dp else 440.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(if (compact) 18.dp else 24.dp))
                .background(BeinSurface)
                .border(2.dp, BeinHighlightPurple, RoundedCornerShape(if (compact) 18.dp else 24.dp))
                .padding(if (compact) 16.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = AppStrings.tr(lang, "تأكيد الخروج من التطبيق", "Confirmer la fermeture", "Confirm Exit"),
                color = Color.White,
                fontSize = if (compact) 17.sp else 19.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(if (compact) 8.dp else 14.dp))

            BrandLogoBadge(
                modifier = Modifier.size(if (compact) 62.dp else 86.dp),
                cornerRadius = if (compact) 14.dp else 20.dp
            )

            Spacer(Modifier.height(if (compact) 14.dp else 22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (stayFocused) Color.White else BeinPrimaryPurple,
                        contentColor = if (stayFocused) Color.Black else Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(if (compact) 42.dp else 48.dp)
                        .scale(if (stayFocused) 1.05f else 1.0f)
                        .border(2.5.dp, if (stayFocused) BeinFocusGlow else Color.Transparent, RoundedCornerShape(24.dp))
                        .onFocusChanged { stayFocused = it.isFocused }
                        .focusable()
                ) {
                    Text(
                        AppStrings.tr(lang, "البقاء", "Rester", "Stay"),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (compact) 13.sp else 15.sp
                    )
                }

                Button(
                    onClick = onConfirmExit,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (exitFocused) CrimsonAlert else Color(0xFF281220),
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(if (compact) 42.dp else 48.dp)
                        .scale(if (exitFocused) 1.05f else 1.0f)
                        .border(2.5.dp, if (exitFocused) Color.White else CrimsonAlert, RoundedCornerShape(24.dp))
                        .onFocusChanged { exitFocused = it.isFocused }
                        .focusable()
                ) {
                    Text(
                        AppStrings.tr(lang, "خروج", "Quitter", "Exit"),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (compact) 13.sp else 15.sp
                    )
                }
            }
        }
    }
}

/**
 * Top Header with Restored Subscription Duration & Expiry Badge!
 */
@Composable
private fun TopBeinHeader(
    deviceId: String,
    subscription: SubscriptionInfo?,
    vpnStatus: CloudflareTunnelStatus,
    totalCount: Int,
    moviesCount: Int,
    seriesCount: Int,
    bouquetCount: Int,
    isLoading: Boolean,
    isCheckingValidityOnly: Boolean = false,
    networkSpeedLabel: String = "",
    uiLayoutStyle: UiLayoutStyle = UiLayoutStyle.CLASSIC_LIVE_PRO,
    showClassicHomeButton: Boolean = false,
    onReturnToClassicDashboard: () -> Unit = {},
    onToggleUiLayoutStyle: () -> Unit = {},
    useGrid: Boolean,
    lang: AppUiLanguage,
    playlistMode: PlaylistDisplayMode,
    playlists: List<XtreamAccountConfig>,
    activePlaylistFilterId: String,
    showScreenCode: Boolean,
    compact: Boolean,
    onSelectLanguage: (AppUiLanguage) -> Unit,
    onCyclePlaylistSource: () -> Unit,
    onToggleScreenCodeVisibility: () -> Unit,
    onOpenBouquets: () -> Unit,
    onOpenXtream: () -> Unit,
    onOpenPlaylistSelectorModal: () -> Unit,
    onOpenQuickAddAccount: () -> Unit = { LiveProGlobalActions.onOpenQuickAddAccount() },
    onOpenVpnControl: () -> Unit = { LiveProGlobalActions.onOpenVpnControl() },
    onRefreshValidityOnly: () -> Unit,
    onOpenActivation: () -> Unit,
    onRefresh: () -> Unit
) {
    val isSingleListMode = playlistMode == PlaylistDisplayMode.SEPARATE_LISTS && activePlaylistFilterId != "ALL"
    val activeSourceLabel = remember(playlistMode, activePlaylistFilterId, playlists, lang) {
        if (playlistMode == PlaylistDisplayMode.MERGE_ALL || activePlaylistFilterId == "ALL") {
            AppStrings.tr(lang, "🔀 مدمجة", "🔀 Fusionnées", "🔀 Merged")
        } else if (activePlaylistFilterId == "PORTAL_MAIN") {
            AppStrings.tr(lang, "📡 الرئيسية", "📡 Principale", "📡 Main")
        } else {
            val found = playlists.find { it.id == activePlaylistFilterId }?.serverName ?: "XTREAM"
            "⚡ ${found.take(14)}"
        }
    }

    val refreshBtnLabel = remember(isSingleListMode, lang) {
        if (isSingleListMode) {
            AppStrings.tr(lang, "تحديث القائمة", "Actualiser liste", "Refresh List")
        } else {
            AppStrings.tr(lang, "تحديث الكل", "Actualiser tout", "Refresh All")
        }
    }

    // Check if Player Subscription OR any Xtream playlist is expiring within 5 days -> Red Alert Frame!
    val isPlayerExpiringSoon = remember(subscription) {
        subscription != null && (!subscription.active || subscription.daysLeft <= 5 || ExpiryAlertHelper.isDateExpiringSoon(subscription.expireDate))
    }
    val isAnyXtreamExpiringSoon = remember(playlists) {
        playlists.any { it.enabled && ExpiryAlertHelper.isDateExpiringSoon(it.expDate) }
    }
    val hasExpiryAlert = isPlayerExpiringSoon || isAnyXtreamExpiringSoon

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 44.dp else 52.dp)
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF14141A), BeinSurface, Color(0xFF07030E))
                )
            )
            .border(1.dp, BeinBorder, RectangleShape)
            .padding(horizontal = if (compact) 6.dp else 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Right/Start Brand + VPN Shield (Compact & Non-Overflowing)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 7.dp)
        ) {
            BrandLogoBadge(
                modifier = Modifier.size(if (compact) 24.dp else 30.dp),
                cornerRadius = if (compact) 6.dp else 8.dp
            )
            Column(verticalArrangement = Arrangement.Center) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = APP_BRAND_NAME,
                        color = Color.White,
                        fontSize = if (compact) 12.sp else 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                    var vpnBadgeFocused by remember { mutableStateOf(false) }
                    Text(
                        text = if (vpnStatus.connected) "🔒 VPN" else "🔓 VPN متوقف",
                        color = when {
                            vpnBadgeFocused -> Color.Black
                            vpnStatus.connected -> EmeraldLive
                            else -> Color(0xFFFBBF24)
                        },
                        fontSize = if (compact) 7.sp else 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(
                                when {
                                    vpnBadgeFocused -> Color.White
                                    vpnStatus.connected -> Color(0xFF0C221C)
                                    else -> Color(0xFF2B1B08)
                                }
                            )
                            .border(
                                1.dp,
                                if (vpnBadgeFocused) BeinFocusGlow else if (vpnStatus.connected) EmeraldLive.copy(alpha = 0.7f) else Color(0xFFF59E0B),
                                RoundedCornerShape(5.dp)
                            )
                            .onFocusChanged { vpnBadgeFocused = it.isFocused }
                            .onKeyEvent { ev ->
                                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                    ev.nativeKeyEvent.repeatCount == 0 &&
                                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                                ) {
                                    SafeClickGuard.runThrottled("TOP_VPN_BTN", 150L) {
                                        onOpenVpnControl()
                                        LiveProGlobalActions.onOpenVpnControl()
                                    }
                                    true
                                } else false
                            }
                            .focusable()
                            .clickable {
                                SafeClickGuard.runThrottled("TOP_VPN_BTN", 150L) {
                                    onOpenVpnControl()
                                    LiveProGlobalActions.onOpenVpnControl()
                                }
                            }
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                    if (networkSpeedLabel.isNotBlank()) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = "⬇ $networkSpeedLabel",
                                color = EmeraldLive,
                                fontSize = if (compact) 7.sp else 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier
                                    .background(Color(0xFF081C15), RoundedCornerShape(4.dp))
                                    .border(1.dp, EmeraldLive.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                if (!compact) {
                    Text(
                        text = "$totalCount ${AppStrings.tr(lang, "قناة", "Ch", "Ch")} • $moviesCount ${AppStrings.tr(lang, "فيلم", "Films", "M")} • $seriesCount ${AppStrings.tr(lang, "مسلسل", "Séries", "S")}",
                        color = Color(0xFFCBD5E1),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
        }

        // 2. Center & Action Bar — Fitted cleanly inside screen frame (Zero Overflow!)
        Row(
            modifier = Modifier.weight(1f, fill = false).padding(start = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A. SEPARATE DEDICATED FRAME 1: Player Remaining Days Badge (إطار مستقل خاص بعدد الأيام المتبقية للمشغل)
            var daysFocused by remember { mutableStateOf(false) }
            val daysCount = subscription?.daysLeft ?: 30
            val daysText = AppStrings.tr(lang, "⏳ $daysCount يوم", "⏳ $daysCount j", "⏳ $daysCount d")
            Box(
                modifier = Modifier
                    .height(if (compact) 27.dp else 32.dp)
                    .scale(if (daysFocused) 1.05f else 1.0f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        when {
                            daysFocused -> Color.White
                            hasExpiryAlert -> Color(0xFF2D0C16)
                            else -> Color(0xFF0C241B)
                        }
                    )
                    .border(
                        width = if (daysFocused) 2.dp else if (hasExpiryAlert) 1.8.dp else 1.2.dp,
                        color = when {
                            daysFocused -> BeinFocusGlow
                            hasExpiryAlert -> CrimsonAlert
                            else -> EmeraldLive
                        },
                        shape = RoundedCornerShape(9.dp)
                    )
                    .onFocusChanged { daysFocused = it.isFocused }
                    .onKeyEvent { ev ->
                        if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            ev.nativeKeyEvent.repeatCount == 0 &&
                            (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                                ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                        ) {
                            SafeClickGuard.runThrottled("TOP_DAYS", 150L) { onOpenActivation() }
                            true
                        } else false
                    }
                    .focusable()
                    .clickable { SafeClickGuard.runThrottled("TOP_DAYS", 150L) { onOpenActivation() } }
                    .padding(horizontal = if (compact) 7.dp else 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = daysText,
                    color = if (daysFocused) Color.Black else if (hasExpiryAlert) Color(0xFFFCA5A5) else EmeraldLive,
                    fontSize = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }

            // B. SEPARATE DEDICATED FRAME 2: Screen Code Badge (إطار مستقل خاص برمز الشاشة مفصول تماماً عن الأيام)
            var codeFocused by remember { mutableStateOf(false) }
            val codeText = if (showScreenCode) deviceId else "DEV-••••-••••"
            Box(
                modifier = Modifier
                    .height(if (compact) 27.dp else 32.dp)
                    .scale(if (codeFocused) 1.05f else 1.0f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (codeFocused) Color.White else Color(0xFF130926))
                    .border(
                        width = if (codeFocused) 2.dp else 1.2.dp,
                        color = if (codeFocused) BeinFocusGlow else BeinHighlightPurple,
                        shape = RoundedCornerShape(9.dp)
                    )
                    .onFocusChanged { codeFocused = it.isFocused }
                    .onKeyEvent { ev ->
                        if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            ev.nativeKeyEvent.repeatCount == 0 &&
                            (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                                ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                        ) {
                            SafeClickGuard.runThrottled("TOP_CODE", 150L) { onOpenActivation() }
                            true
                        } else false
                    }
                    .focusable()
                    .clickable { SafeClickGuard.runThrottled("TOP_CODE", 150L) { onOpenActivation() } }
                    .padding(horizontal = if (compact) 7.dp else 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = codeText,
                            color = if (codeFocused) Color.Black else Color.White,
                            fontSize = if (compact) 10.sp else 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                    }
                    Icon(
                        imageVector = if (showScreenCode) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = "إظهار/إخفاء الرمز",
                        tint = if (codeFocused) Color.Black else Color(0xFFE2E8F0),
                        modifier = Modifier
                            .size(if (compact) 11.dp else 13.dp)
                            .clickable { onToggleScreenCodeVisibility() }
                    )
                }
            }

            // C. MIDDLE SPACE UTILITY TOOL 1: Instant Validity Check Only ("⚡ فحص الصلاحية" — بدون سحب القنوات)
            SlimTopBarPill(
                text = if (isCheckingValidityOnly) "⏳..." else AppStrings.tr(lang, "⚡ فحص الصلاحية", "⚡ Validité", "⚡ Check Validity"),
                isHighlighted = false,
                compact = compact,
                onClick = onRefreshValidityOnly
            )

            // D.1 Prominent "+ إضافة حساب" Quick Add Account Button right in Top Bar!
            var addAccFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .height(if (compact) 27.dp else 32.dp)
                    .scale(if (addAccFocused) 1.06f else 1.0f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (addAccFocused) Color.White else Color(0xFF0C2A20)
                    )
                    .border(
                        width = if (addAccFocused) 2.2.dp else 1.4.dp,
                        color = if (addAccFocused) BeinFocusGlow else EmeraldLive,
                        shape = RoundedCornerShape(9.dp)
                    )
                    .onFocusChanged { addAccFocused = it.isFocused }
                    .onKeyEvent { ev ->
                        if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            ev.nativeKeyEvent.repeatCount == 0 &&
                            (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                                ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                        ) {
                            SafeClickGuard.runThrottled("TOP_ADD_ACCOUNT", 150L) {
                                onOpenQuickAddAccount()
                                LiveProGlobalActions.onOpenQuickAddAccount()
                            }
                            true
                        } else false
                    }
                    .focusable()
                    .clickable {
                        SafeClickGuard.runThrottled("TOP_ADD_ACCOUNT", 150L) {
                            onOpenQuickAddAccount()
                            LiveProGlobalActions.onOpenQuickAddAccount()
                        }
                    }
                    .padding(horizontal = if (compact) 7.dp else 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = AppStrings.tr(lang, "➕ إضافة حساب", "➕ Ajouter Compte", "➕ Add Account"),
                    color = if (addAccFocused) Color.Black else EmeraldLive,
                    fontSize = if (compact) 10.sp else 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }

            // D.2 MIDDLE SPACE UTILITY TOOL 2: Multi-Playlist Picker Modal ("🎛️ اختيار القوائم")
            if (playlists.size > 1) {
                SlimTopBarPill(
                    text = AppStrings.tr(lang, "🎛️ اختيار القوائم (${playlists.size})", "🎛️ Listes (${playlists.size})", "🎛️ Pick Lists (${playlists.size})"),
                    isHighlighted = false,
                    compact = compact,
                    onClick = onOpenPlaylistSelectorModal
                )
            }

            // E. Playlist Mode / Source Switcher Pill
            SlimTopBarPill(
                text = activeSourceLabel,
                isHighlighted = isSingleListMode,
                compact = compact,
                onClick = onCyclePlaylistSource
            )

            // F. Context-Aware Content Refresh Button (تحديث القائمة الحالية أو تحديث الكل)
            var refreshFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .height(if (compact) 27.dp else 32.dp)
                    .scale(if (refreshFocused) 1.05f else 1.0f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (refreshFocused) {
                            Brush.horizontalGradient(listOf(Color.White, Color.White))
                        } else {
                            Brush.horizontalGradient(listOf(Color(0xFF3B156E), Color(0xFF240B47)))
                        }
                    )
                    .border(
                        width = if (refreshFocused) 2.dp else 1.2.dp,
                        color = if (refreshFocused) BeinFocusGlow else Color(0xFFE2E8F0),
                        shape = RoundedCornerShape(9.dp)
                    )
                    .onFocusChanged { refreshFocused = it.isFocused }
                    .onKeyEvent { ev ->
                        if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            ev.nativeKeyEvent.repeatCount == 0 &&
                            (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                                ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                        ) {
                            SafeClickGuard.runThrottled("TOP_REFRESH", 250L) { onRefresh() }
                            true
                        } else false
                    }
                    .focusable()
                    .clickable { SafeClickGuard.runThrottled("TOP_REFRESH", 250L) { onRefresh() } }
                    .padding(horizontal = if (compact) 7.dp else 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = if (refreshFocused) Color.Black else Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(if (compact) 10.dp else 12.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = if (refreshFocused) Color.Black else Color.White,
                            modifier = Modifier.size(if (compact) 11.dp else 13.dp)
                        )
                    }
                    Text(
                        text = refreshBtnLabel,
                        color = if (refreshFocused) Color.Black else Color.White,
                        fontSize = if (compact) 10.sp else 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1
                    )
                }
            }

            // F.2 Return to Classic Pro Dashboard or Switch UI Theme (كلاسيكي احترافي / متطور)
            if (showClassicHomeButton) {
                SlimTopBarPill(
                    text = AppStrings.tr(lang, "🏠 الرئيسية", "🏠 Accueil", "🏠 Home"),
                    isHighlighted = true,
                    compact = compact,
                    onClick = onReturnToClassicDashboard
                )
            } else {
                SlimTopBarPill(
                    text = if (uiLayoutStyle == UiLayoutStyle.CLASSIC_LIVE_PRO) {
                        AppStrings.tr(lang, "🎨 متطور", "🎨 Moderne", "🎨 Modern")
                    } else {
                        AppStrings.tr(lang, "🏛️ الشكل الكلاسيكي", "🏛️ Mode Classique", "🏛️ Classic Mode")
                    },
                    isHighlighted = uiLayoutStyle == UiLayoutStyle.CLASSIC_LIVE_PRO,
                    compact = compact,
                    onClick = onToggleUiLayoutStyle
                )
            }

            // G. Compact Language Switcher (Fits inside frame without overflowing!)
            SlimTopBarPill(
                text = "🌐 ${lang.nativeLabel}",
                isHighlighted = false,
                compact = compact,
                onClick = {
                    val values = AppUiLanguage.values()
                    val next = values[(lang.ordinal + 1) % values.size]
                    onSelectLanguage(next)
                }
            )
        }
    }
}

@Composable
private fun SlimTopBarPill(
    text: String,
    isHighlighted: Boolean,
    compact: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .height(if (compact) 27.dp else 32.dp)
            .scale(if (focused) 1.05f else 1.0f)
            .clip(RoundedCornerShape(9.dp))
            .background(
                when {
                    focused -> Color.White
                    isHighlighted -> BeinPrimaryPurple
                    else -> BeinCard
                }
            )
            .border(
                width = if (focused) 2.2.dp else 1.dp,
                color = if (focused) BeinFocusGlow else if (isHighlighted) BeinHighlightPurple else BeinBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    ev.nativeKeyEvent.repeatCount == 0 &&
                    (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    SafeClickGuard.runThrottled("UI_BTN_CLICK", 200L) { onClick() }
                    true
                } else false
            }
            .focusable()
            .clickable { SafeClickGuard.runThrottled("UI_BTN_CLICK", 200L) { onClick() } }
            .padding(horizontal = if (compact) 7.dp else 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (focused) Color.Black else Color.White,
            fontSize = if (compact) 10.sp else 12.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

/**
 * Unified Popup Modal for:
 * 1) Screen Code (DEV-XXXX-XXXX) with Copy & Show/Hide Toggle
 * 2) Player Subscription Expiry Date & Remaining Days (Red Border if <= 5 days!)
 * 3) Xtream Server(s) Expiry Date(s) & Status (Red Border if <= 5 days!)
 */
@Composable
private fun UnifiedValidityAndScreenCodeModal(
    deviceId: String,
    deviceName: String,
    subscription: SubscriptionInfo?,
    playlists: List<XtreamAccountConfig>,
    showScreenCode: Boolean,
    lang: AppUiLanguage,
    compact: Boolean,
    onToggleScreenCode: () -> Unit,
    onOpenQuickAddAccount: () -> Unit = { LiveProGlobalActions.onOpenQuickAddAccount() },
    onOpenCustomerSpace: () -> Unit,
    onOpenFullActivation: () -> Unit,
    onRedeemCode: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var modalVoucherCode by remember { mutableStateOf("") }
    val playerExpiringSoon = subscription != null && (!subscription.active || subscription.daysLeft <= 5 || ExpiryAlertHelper.isDateExpiringSoon(subscription.expireDate))

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = if (compact) 540.dp else 640.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0F061E), BeinSurface, BeinDarkBg)
                    )
                )
                .border(2.dp, if (playerExpiringSoon) CrimsonAlert else BeinHighlightPurple, RoundedCornerShape(22.dp))
                .padding(if (compact) 14.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = AppStrings.tr(
                        lang,
                        "👑 بطاقة الهوية وصلاحية المشغل وسيرفرات XTREAM",
                        "👑 Identité, Validité du Lecteur & Serveurs XTREAM",
                        "👑 Screen ID, Player Validity & XTREAM Servers"
                    ),
                    color = Color.White,
                    fontSize = if (compact) 15.sp else 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "➕ إضافة حساب", "➕ Ajouter Compte", "➕ Add Account"),
                        isPrimary = true,
                        compact = true,
                        onClick = onOpenQuickAddAccount
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                    }
                }
            }

            // 1. Screen Code Card (رمز الشاشة الدائم)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinDarkBg)
                    .border(1.5.dp, BeinHighlightPurple, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 11.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = AppStrings.tr(lang, "🖥️ رمز الشاشة الثابت (لا يتغير حتى بعد ضبط المصنع)", "🖥️ Code Écran Permanent", "🖥️ Permanent Hardware Screen Code"),
                    color = Color(0xFFE2E8F0),
                    fontSize = if (compact) 12.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = if (showScreenCode) deviceId else "DEV-••••-••••",
                            color = Color.White,
                            fontSize = if (compact) 17.sp else 21.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp
                        )
                    }
                    SlimTopBarPill(
                        text = if (showScreenCode) "👁 إخفاء" else "👁 إظهار",
                        isHighlighted = false,
                        compact = true,
                        onClick = onToggleScreenCode
                    )
                    SlimTopBarPill(
                        text = "📋 نسخ",
                        isHighlighted = true,
                        compact = true,
                        onClick = { copyDeviceCodeToClipboard(context, deviceId) }
                    )
                }
            }

            // 2. Player Subscription Validity Card (تتحول لإطار أحمر عند اقتراب انتهاء الصلاحية!)
            if (subscription != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (playerExpiringSoon) Color(0xFF280B14) else Color(0xFF140B26))
                        .border(
                            width = if (playerExpiringSoon) 2.2.dp else 1.5.dp,
                            color = if (playerExpiringSoon) CrimsonAlert else EmeraldLive,
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 11.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (playerExpiringSoon) {
                            AppStrings.tr(lang, "⚠️ تنبيه: اقترب انتهاء صلاحية اشتراك المشغل!", "⚠️ Attention : Abonnement bientôt expiré !", "⚠️ Alert: Player Subscription Expiring Soon!")
                        } else {
                            AppStrings.tr(lang, "✅ صلاحية اشتراك المشغل (LIVE PRO)", "✅ Validité du Lecteur LIVE PRO", "✅ LIVE PRO Player Subscription Validity")
                        },
                        color = if (playerExpiringSoon) Color(0xFFFCA5A5) else EmeraldLive,
                        fontSize = if (compact) 13.sp else 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "الأيام المتبقية: ${subscription.daysLeft} يوم" + (if (subscription.expireDate.isNotBlank()) " • تاريخ الانتهاء: ${subscription.expireDate}" else ""),
                            "Jours restants : ${subscription.daysLeft} j • Expire le : ${subscription.expireDate}",
                            "Days Left: ${subscription.daysLeft} days • Expires: ${subscription.expireDate}"
                        ),
                        color = Color.White,
                        fontSize = if (compact) 13.sp else 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 3. Xtream Server(s) Validity List (كل سيرفر يتحول إطاره للأحمر إذا اقترب انتهاء صلاحيته!)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinCard)
                    .border(1.dp, BeinBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = AppStrings.tr(
                        lang,
                        "⚡ صلاحية سيرفرات XTREAM المرتبطة (${playlists.size})",
                        "⚡ Validité des serveurs XTREAM (${playlists.size})",
                        "⚡ Linked XTREAM Servers Validity (${playlists.size})"
                    ),
                    color = Color(0xFFE2E8F0),
                    fontSize = if (compact) 13.sp else 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                if (playlists.isEmpty()) {
                    Text(
                        text = AppStrings.tr(lang, "لا توجد قائمة XTREAM مضافة حالياً", "Aucune liste XTREAM", "No XTREAM list added yet"),
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                } else {
                    playlists.take(3).forEach { pl ->
                        val xExpiring = ExpiryAlertHelper.isDateExpiringSoon(pl.expDate)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (xExpiring) Color(0xFF2A0C15) else BeinSurface)
                                .border(
                                    width = if (xExpiring) 2.dp else 1.dp,
                                    color = if (xExpiring) CrimsonAlert else BeinBorder,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = pl.serverName.ifBlank { "XTREAM VIP" },
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (compact) 12.sp else 14.sp
                            )
                            Text(
                                text = if (pl.expDate.isNotBlank()) {
                                    (if (xExpiring) "⚠️ ينتهي: " else "⏳ ينتهي: ") + pl.expDate
                                } else {
                                    "متصل ✓"
                                },
                                color = if (xExpiring) Color(0xFFFCA5A5) else EmeraldLive,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (compact) 12.sp else 14.sp
                            )
                        }
                    }
                }
            }

            // Direct Voucher Input Row inside Unified Modal
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BeinDarkBg)
                    .border(1.2.dp, BeinHighlightPurple, RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = modalVoucherCode,
                    onValueChange = { modalVoucherCode = formatVoucherCodeInput(it) },
                    placeholder = {
                        Text(
                            AppStrings.tr(lang, "رمز القسيمة: A1B2-C3D4", "Code: A1B2-C3D4", "Voucher: A1B2-C3D4"),
                            color = Color(0xFFBFA7F2),
                            fontSize = if (compact) 12.sp else 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = if (compact) 15.sp else 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                        textAlign = TextAlign.Center
                    ),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                )
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "📋 لصق", "📋 Coller", "📋 Paste"),
                    isPrimary = false,
                    compact = true,
                    onClick = {
                        val pasted = pasteVoucherFromClipboard(context)
                        if (pasted.isNotBlank()) modalVoucherCode = pasted
                    }
                )
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "تفعيل ✓", "Activer ✓", "Activate ✓"),
                    isPrimary = true,
                    compact = true,
                    onClick = { onRedeemCode(modalVoucherCode) }
                )
            }

            // Bottom Action Buttons inside Unified Modal
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "📲 إدارة القوائم", "📲 Gérer les listes", "📲 Manage Lists"),
                    isPrimary = true,
                    compact = true,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenCustomerSpace
                )
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "💳 تفعيل قسيمة", "💳 Activer un code", "💳 Redeem Voucher"),
                    isPrimary = false,
                    compact = true,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenFullActivation
                )
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "🔄 تحديث الصلاحية فقط", "🔄 Validité seule", "🔄 Refresh Validity Only"),
                    isPrimary = false,
                    compact = true,
                    onClick = onRefresh
                )
            }
        }
    }
}

/**
 * Professional Animated Waiting / Loading Overlay when refreshing content or entering the app.
 */


/**
 * Interactive Multi-Playlist Selector Modal (صفحة منبثقة احترافية لاختيار القوائم المراد تحديثها):
 * Shown automatically on first-time launch when multiple Xtream playlists are linked to the Screen Code,
 * or anytime via the "🎛️ اختيار القوائم" button in the top bar.
 * Allows the customer to update:
 *   1) A single playlist ("تحديث هذه القائمة فقط ⚡")
 *   2) Multiple selected playlists via checkboxes ("تحديث القوائم المحددة ✅")
 *   3) All playlists at once ("تحديث الجميع 🚀")
 */
@Composable
private fun FirstTimeMultiPlaylistSelectorModal(
    playlists: List<XtreamAccountConfig>,
    showPortalMain: Boolean = LiveProGlobalActions.isVpnActiveAndProtected,
    isFirstTime: Boolean,
    lang: AppUiLanguage,
    compact: Boolean,
    onOpenQuickAddAccount: () -> Unit = { LiveProGlobalActions.onDismissPlaylistSelectorAndOpenQuickAdd() },
    onSyncSingle: (XtreamAccountConfig) -> Unit,
    onSyncSelected: (Set<String>, Boolean) -> Unit,
    onSyncAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val selectedIds = remember(playlists) {
        mutableStateOf(playlists.filter { it.enabled }.map { it.id }.toSet())
    }
    var includePortalMain by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 340.dp, max = if (compact) 580.dp else 680.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF130826), BeinSurface, BeinDarkBg)
                    )
                )
                .border(2.5.dp, BeinHighlightPurple, RoundedCornerShape(22.dp))
                .padding(if (compact) 14.dp else 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isFirstTime) {
                            AppStrings.tr(
                                lang,
                                "🚀 تم العثور على عدة سيرفرات XTREAM مرتبطة بشاشتك (${playlists.size})",
                                "🚀 Plusieurs serveurs XTREAM détectés (${playlists.size})",
                                "🚀 Multiple XTREAM Servers Linked to Your Screen (${playlists.size})"
                            )
                        } else {
                            AppStrings.tr(
                                lang,
                                "🎛️ اختيار القوائم المراد تحديثها (${playlists.size})",
                                "🎛️ Choisir les listes à actualiser (${playlists.size})",
                                "🎛️ Select Playlists to Refresh (${playlists.size})"
                            )
                        },
                        color = Color.White,
                        fontSize = if (compact) 15.sp else 18.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "اختر تحديث قائمة واحدة، أو حدّد عدة قوائم، أو اضغط «تحديث الجميع»",
                            "Actualisez une seule liste, plusieurs listes sélectionnées, ou toutes les listes",
                            "Choose to update a single playlist, multiple selected playlists, or all playlists"
                        ),
                        color = Color(0xFFE2E8F0),
                        fontSize = if (compact) 12.sp else 14.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "➕ إضافة حساب", "➕ Ajouter Compte", "➕ Add Account"),
                        isPrimary = true,
                        compact = true,
                        onClick = onOpenQuickAddAccount
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (compact) 190.dp else 260.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                itemsIndexed(playlists, key = { idx, pl -> "${idx}_${pl.id}" }) { idx, pl ->
                    val isChecked = pl.id in selectedIds.value
                    var rowFocused by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    rowFocused -> Color(0xFF2B1454)
                                    isChecked -> Color(0xFF1A0D33)
                                    else -> BeinCard
                                }
                            )
                            .border(
                                width = if (rowFocused) 2.dp else 1.2.dp,
                                color = if (rowFocused) BeinFocusGlow else if (isChecked) EmeraldLive else BeinBorder,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .onFocusChanged { rowFocused = it.isFocused }
                            .focusable()
                            .clickable {
                                val curr = selectedIds.value
                                selectedIds.value = if (isChecked) curr - pl.id else curr + pl.id
                            }
                            .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (compact) 20.dp else 24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isChecked) EmeraldLive else Color(0xFF0E071B))
                                    .border(1.5.dp, if (isChecked) EmeraldLive else Color.Gray, RoundedCornerShape(6.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isChecked) {
                                    Text(
                                        text = "✓",
                                        color = Color.Black,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = if (compact) 13.sp else 15.sp
                                    )
                                }
                            }
                            Column {
                                Text(
                                    text = "${idx + 1}. " + pl.serverName.ifBlank { "XTREAM #" + (idx + 1) },
                                    color = Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = if (compact) 13.sp else 15.sp
                                )
                                Text(
                                    text = if (pl.expDate.isNotBlank()) "⏳ الصلاحية: ${pl.expDate} • ${pl.statusNote}" else pl.statusNote,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = if (compact) 11.sp else 13.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        BeinFocusableButton(
                            text = AppStrings.tr(lang, "تحديث لوحدها ⚡", "Seule ⚡", "Update Only ⚡"),
                            isPrimary = true,
                            compact = true,
                            onClick = { onSyncSingle(pl) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BeinFocusableButton(
                    text = AppStrings.tr(
                        lang,
                        "✅ تحديث المحدد (${selectedIds.value.size})",
                        "✅ Actualiser sélection (${selectedIds.value.size})",
                        "✅ Update Selected (${selectedIds.value.size})"
                    ),
                    isPrimary = selectedIds.value.isNotEmpty(),
                    compact = compact,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (selectedIds.value.isNotEmpty()) {
                            onSyncSelected(selectedIds.value, includePortalMain)
                        }
                    }
                )
                BeinFocusableButton(
                    text = AppStrings.tr(
                        lang,
                        "🚀 تحديث الجميع (${playlists.size})",
                        "🚀 Tout actualiser (${playlists.size})",
                        "🚀 Update All (${playlists.size})"
                    ),
                    isPrimary = true,
                    compact = compact,
                    modifier = Modifier.weight(1f),
                    onClick = onSyncAll
                )
                BeinFocusableButton(
                    text = if (selectedIds.value.size == playlists.size) {
                        AppStrings.tr(lang, "إلغاء التحديد", "Tout désélectionner", "Unselect All")
                    } else {
                        AppStrings.tr(lang, "تحديد الكل", "Tout sélectionner", "Select All")
                    },
                    isPrimary = false,
                    compact = compact,
                    onClick = {
                        selectedIds.value = if (selectedIds.value.size == playlists.size) {
                            emptySet()
                        } else {
                            playlists.map { it.id }.toSet()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ProfessionalWaitingOverlay(
    lang: AppUiLanguage,
    compact: Boolean,
    syncProgress: SyncProgressState = SyncProgressState(),
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x9905020A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 270.dp, max = if (compact) 380.dp else 440.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF110722), BeinSurface)
                    )
                )
                .border(2.dp, BeinHighlightPurple, RoundedCornerShape(22.dp))
                .padding(horizontal = 24.dp, vertical = if (compact) 16.dp else 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = BeinHighlightPurple,
                    strokeWidth = 3.5.dp,
                    modifier = Modifier.size(if (compact) 44.dp else 54.dp)
                )
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(if (compact) 26.dp else 32.dp)
                )
            }
            Text(
                text = AppStrings.tr(lang, "⏳ يرجى الانتظار...", "⏳ Veuillez patienter...", "⏳ Please wait..."),
                color = Color.White,
                fontSize = if (compact) 16.sp else 18.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                color = Color(0xFFE2E8F0),
                fontSize = if (compact) 13.sp else 14.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            if (syncProgress.progressPercent > 0) {
                LinearProgressIndicator(
                    progress = { (syncProgress.progressPercent / 100f).coerceIn(0.05f, 1f) },
                    color = EmeraldLive,
                    trackColor = Color(0xFF281545),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
                Text(
                    text = buildString {
                        append("${syncProgress.progressPercent}%")
                        if (syncProgress.fetchedLiveCount > 0 || syncProgress.fetchedMovieCount > 0 || syncProgress.fetchedSeriesCount > 0) {
                            append(" • تم جلب ${syncProgress.fetchedLiveCount} قناة • ${syncProgress.fetchedMovieCount} فيلم • ${syncProgress.fetchedSeriesCount} مسلسل")
                        }
                    },
                    color = EmeraldLive,
                    fontSize = if (compact) 12.sp else 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun LandscapeSideNavigationRail(
    activeTab: MainTab,
    lang: AppUiLanguage,
    compact: Boolean,
    onSelectTab: (MainTab) -> Unit,
    onExitClick: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight()
            .width(if (compact) 64.dp else 88.dp)
            .background(BeinSurface)
            .border(1.dp, BeinBorder, RectangleShape)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(MainTab.values().toList(), key = { it.name }) { tab ->
            val selected = activeTab == tab
            var focused by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp)
                    .scale(if (focused) 1.07f else 1.0f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        when {
                            focused -> Color.White
                            selected -> BeinPrimaryPurple
                            else -> Color.Transparent
                        }
                    )
                    .border(
                        width = if (focused) 2.5.dp else if (selected) 1.5.dp else 0.dp,
                        color = if (focused) BeinFocusGlow else if (selected) Color.White else Color.Transparent,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { ev ->
                        if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                        ) {
                            onSelectTab(tab)
                            true
                        } else false
                    }
                    .focusable()
                    .clickable { SafeClickGuard.runThrottled("TAB_CLICK", 180L) { onSelectTab(tab) } }
                    .padding(vertical = if (compact) 6.dp else 8.dp, horizontal = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = when (tab) {
                        MainTab.LIVE -> Icons.Default.LiveTv
                        MainTab.BOUQUETS -> Icons.Default.Folder
                        MainTab.MOVIES -> Icons.Default.Movie
                        MainTab.SERIES -> Icons.Default.VideoLibrary
                        MainTab.XTREAM -> Icons.Default.CloudDownload
                        MainTab.FAVORITES -> Icons.Default.Favorite
                        MainTab.ACTIVATION -> Icons.Default.VpnKey
                        MainTab.SETTINGS -> Icons.Default.Settings
                    },
                    contentDescription = tab.label(lang),
                    tint = if (focused) Color.Black else Color.White,
                    modifier = Modifier.size(if (compact) 17.dp else 21.dp)
                )
                if (!compact) {
                    Text(
                        text = tab.label(lang),
                        color = if (focused) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = if (selected || focused) FontWeight.ExtraBold else FontWeight.Medium,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        item {
            var exitFocused by remember { mutableStateOf(false) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 5.dp, vertical = 4.dp)
                    .scale(if (exitFocused) 1.07f else 1.0f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (exitFocused) CrimsonAlert else Color.Transparent)
                    .border(2.dp, if (exitFocused) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
                    .onFocusChanged { exitFocused = it.isFocused }
                    .focusable()
                    .clickable { onExitClick() }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Exit",
                    tint = Color.White,
                    modifier = Modifier.size(if (compact) 16.dp else 20.dp)
                )
            }
        }
    }
}

/**
 * Responsive Bouquets Grid Directory View:
 * Adaptive card width + multi-line text so every bouquet name appears in full without clipping!
 */
@Composable
private fun BouquetsGridDirectoryView(
    bouquetSummaries: List<BouquetSummary>,
    selectedCategory: String,
    lang: AppUiLanguage,
    compact: Boolean,
    onSelectBouquet: (String) -> Unit
) {
    var filterText by remember { mutableStateOf("") }
    val filteredBouquets = remember(bouquetSummaries, filterText) {
        val q = filterText.trim()
        if (q.isEmpty()) bouquetSummaries
        else bouquetSummaries.filter { it.name.contains(q, ignoreCase = true) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = if (compact) 10.dp else 16.dp,
                vertical = if (compact) 6.dp else 10.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "📂 ${AppStrings.tr(lang, "دليل الباقات والمفضلة", "Répertoire des Bouquets", "Bouquets Directory")} (${bouquetSummaries.size})",
                    color = Color.White,
                    fontSize = if (compact) 16.sp else 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            CompactSearchBar(
                query = filterText,
                onQueryChange = { filterText = it },
                compact = compact,
                modifier = Modifier
                    .width(if (compact) 160.dp else 220.dp)
                    .height(if (compact) 30.dp else 36.dp)
            )
        }

        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = if (compact) 180.dp else 235.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(filteredBouquets, key = { idx, bq -> "${idx}_${bq.name}" }) { _, bq ->
                val isSelected = bq.name == selectedCategory
                var focused by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .defaultMinSize(minHeight = if (compact) 86.dp else 104.dp)
                        .scale(if (focused) 1.03f else 1.0f)
                        .clip(RoundedCornerShape(if (compact) 14.dp else 16.dp))
                        .background(
                            when {
                                focused -> Color.White
                                isSelected -> BeinPrimaryPurple
                                else -> BeinCard
                            }
                        )
                        .border(
                            width = if (focused) 2.8.dp else if (isSelected) 1.8.dp else 1.dp,
                            color = if (focused) BeinFocusGlow else if (isSelected) Color.White else BeinBorder,
                            shape = RoundedCornerShape(if (compact) 14.dp else 16.dp)
                        )
                        .onFocusChanged { focused = it.isFocused }
                        .onKeyEvent { ev ->
                            if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                    ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                            ) {
                                onSelectBouquet(bq.name)
                                true
                            } else false
                        }
                        .focusable()
                        .clickable { onSelectBouquet(bq.name) }
                        .padding(if (compact) 12.dp else 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (bq.isFavoritesSpecial) Icons.Default.Favorite else Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (focused) BeinPrimaryPurple else Color.White,
                            modifier = Modifier.size(if (compact) 20.dp else 24.dp)
                        )

                        Text(
                            text = "${bq.count} ${AppStrings.tr(lang, "قناة", "Chaînes", "Ch")}",
                            color = Color.White,
                            fontSize = if (compact) 12.sp else 14.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .background(
                                    if (focused) BeinPrimaryPurple else Color(0xFF14141A),
                                    RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(Modifier.height(if (compact) 8.dp else 10.dp))

                    // Centered Full Bouquet Name without clipping!
                    Text(
                        text = bq.name,
                        color = if (focused) Color.Black else Color.White,
                        fontSize = if (compact) 15.sp else 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 3,
                        overflow = TextOverflow.Visible,
                        lineHeight = 19.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * استوديو بناء وإدارة القنوات المتعددة الجودات يدوياً (Manual Multi-Quality Studio):
 * - يمكّن المستخدم من اختيار قناة من باقة معينة وتعيينها كجودة (LOW / SD / HD / FHD / 4K)،
 *   ثم إضافة قنوات أخرى لجودات أخرى من اختياره (من نفس الباقة أو من أي باقة/سيرفر آخر)
 *   لتشكيل قناة واحدة ذكية متعددة الجودات تتكيف تلقائياً مع حركة الإنترنت وثقل أو توقف المصدر.
 */
@Composable
private fun ManualMultiQualityBuilderDialog(
    allLiveChannels: List<TvChannel>,
    channelsByBouquet: Map<String, List<TvChannel>> = emptyMap(),
    initialBouquet: String = "الكل",
    seedChannel: TvChannel? = null,
    initialEditGroupId: String? = null,
    compact: Boolean,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val ranks = StreamQualityRank.selectableRanks

    var existingGroups by remember {
        mutableStateOf(MultiQualityEngine.listUserGroups())
    }

    val initialGroupToEdit = remember(initialEditGroupId, existingGroups) {
        if (!initialEditGroupId.isNullOrBlank()) {
            existingGroups.firstOrNull { it.id == initialEditGroupId }
        } else null
    }

    var attachToExistingMode by remember(seedChannel, initialEditGroupId) {
        mutableStateOf(false)
    }

    var editingGroupId by remember(initialGroupToEdit) {
        mutableStateOf(initialGroupToEdit?.id)
    }

    var displayName by remember(seedChannel, initialGroupToEdit) {
        mutableStateOf(
            when {
                initialGroupToEdit != null -> initialGroupToEdit.displayName
                seedChannel != null -> MultiQualityEngine.cleanBaseChannelName(seedChannel.name)
                else -> ""
            }
        )
    }

    var seedQuality by remember(seedChannel) {
        mutableStateOf(StreamQualityRank.LOW)
    }

    var selectedSources by remember(seedChannel, initialGroupToEdit) {
        val initList = when {
            initialGroupToEdit != null -> initialGroupToEdit.sources.sortedBy { it.quality.rank }
            else -> emptyList()
        }
        mutableStateOf(initList)
    }

    fun suggestNextAscendingQuality(
        current: List<MultiQualityEngine.SavedQualitySource>,
        afterQuality: StreamQualityRank? = null
    ): StreamQualityRank {
        val used = current.map { it.quality }.toSet()
        if (afterQuality != null) {
            val nextHigher = ranks.firstOrNull { it.rank > afterQuality.rank && it !in used }
            if (nextHigher != null) return nextHigher
        }
        for (candidate in ranks) {
            if (candidate !in used) return candidate
        }
        return StreamQualityRank.UHD_4K
    }

    var activePickingQuality by remember(initialGroupToEdit) {
        mutableStateOf(suggestNextAscendingQuality(selectedSources))
    }

    val availableBouquets = remember(channelsByBouquet, allLiveChannels) {
        val bqNames = if (channelsByBouquet.isNotEmpty()) {
            channelsByBouquet.keys.filter { it != MultiQualityEngine.BOUQUET_NAME }
        } else {
            allLiveChannels.asSequence()
                .map { it.group }
                .filter { it.isNotBlank() && it != MultiQualityEngine.BOUQUET_NAME }
                .distinct()
                .toList()
        }
        listOf("الكل") + bqNames
    }

    var pickerBouquet by remember(seedChannel, initialBouquet) {
        val startBq = seedChannel?.group?.takeIf { it.isNotBlank() && it != MultiQualityEngine.BOUQUET_NAME }
            ?: initialBouquet.takeIf { it.isNotBlank() && it != MultiQualityEngine.BOUQUET_NAME && !it.startsWith("★") }
            ?: "الكل"
        mutableStateOf(startBq)
    }

    var pickSearch by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }

    // TV D-Pad Focus Requesters for effortless 3-Zone Remote Control Navigation
    val rankRowFocusRequester = remember { FocusRequester() }
    val bouquetColFocusRequester = remember { FocusRequester() }
    val channelColFocusRequester = remember { FocusRequester() }
    val saveBtnFocusRequester = remember { FocusRequester() }
    val bouquetListState = rememberLazyListState()
    val channelListState = rememberLazyListState()

    val candidateChannels = remember(allLiveChannels, channelsByBouquet, pickerBouquet, pickSearch, selectedSources, seedChannel) {
        val basePool = if (pickerBouquet == "الكل" || channelsByBouquet.isEmpty()) {
            allLiveChannels
        } else {
            channelsByBouquet[pickerBouquet].orEmpty()
        }
        val q = pickSearch.trim()
        val filteredList = basePool.asSequence()
            .filter { it.contentType == StreamContentType.LIVE_TV && it.playlistId != MultiQualityEngine.PLAYLIST_ID }
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.group.contains(q, ignoreCase = true) }
            .take(150)
            .toList()
        if (seedChannel != null && seedChannel.playlistId != MultiQualityEngine.PLAYLIST_ID && q.isEmpty() &&
            (pickerBouquet == "الكل" || pickerBouquet == seedChannel.group)
        ) {
            listOf(seedChannel) + filteredList.filterNot { it.stableKey == seedChannel.stableKey }
        } else {
            filteredList
        }
    }

    LaunchedEffect(Unit) {
        delay(80L)
        val bqIdx = availableBouquets.indexOf(pickerBouquet).coerceAtLeast(0)
        runCatching { bouquetListState.scrollToItem((bqIdx - 2).coerceAtLeast(0)) }
        runCatching { channelColFocusRequester.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(BeinSurface)
                .border(1.5.dp, BeinHighlightPurple, RoundedCornerShape(20.dp))
                .padding(if (compact) 4.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 5.dp)
        ) {
            // 1. Top Header Bar + Mode Switch + Save/Close Buttons (Always visible without scrolling!)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 0.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    Text(
                        text = if (editingGroupId != null) {
                            "🎛️ تعديل القناة المتعددة الجودات يدوياً"
                        } else {
                            "🎛️ استوديو بناء قناة متعددة الجودات (يدوياً بالريموت)"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (compact) 12.sp else 14.sp,
                        lineHeight = if (compact) 14.sp else 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "تنقل بالأسهم ◀ ▶ بين الباقات والقنوات • اضغط OK على القناة لإضافتها للجودة المختارة",
                        color = Color(0xFFFDE68A),
                        fontSize = if (compact) 8.sp else 9.sp,
                        lineHeight = if (compact) 9.sp else 10.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!attachToExistingMode) {
                        BeinFocusableButton(
                            text = if (editingGroupId != null) {
                                "💾 حفظ التعديل (${selectedSources.size})"
                            } else {
                                "💾 حفظ القناة (${selectedSources.size} جودات)"
                            },
                            isPrimary = selectedSources.size >= 2,
                            compact = true,
                            dense = true,
                            modifier = Modifier.focusRequester(saveBtnFocusRequester),
                            onClick = {
                                val ok = MultiQualityEngine.upsertUserGroupWithSources(
                                    context = context,
                                    displayName = displayName,
                                    sources = selectedSources,
                                    existingId = editingGroupId
                                )
                                if (ok) {
                                    Toast.makeText(
                                        context,
                                        "✅ تم حفظ القناة في باقة (${MultiQualityEngine.BOUQUET_NAME}) بنجاح!",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    existingGroups = MultiQualityEngine.listUserGroups()
                                    onSaved()
                                    onDismiss()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "⚠️ يرجى اختيار قناتين (جودتين) على الأقل من القائمة أدناه لحفظ القناة المتعددة",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )
                    }
                    BeinFocusableButton(
                        text = "✕ إغلاق",
                        isPrimary = false,
                        compact = true,
                        dense = true,
                        onClick = onDismiss
                    )
                }
            }

            if (seedChannel != null && seedChannel.playlistId != MultiQualityEngine.PLAYLIST_ID && existingGroups.isNotEmpty() && editingGroupId == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BeinFocusableButton(
                        text = "✨ تجهيز قناة متعددة جودات جديدة",
                        isPrimary = !attachToExistingMode,
                        compact = true,
                        modifier = Modifier.weight(1f),
                        onClick = { attachToExistingMode = false }
                    )
                    BeinFocusableButton(
                        text = "➕ ضمّها لقناة متعددة محفوظة (${existingGroups.size})",
                        isPrimary = attachToExistingMode,
                        compact = true,
                        modifier = Modifier.weight(1f),
                        onClick = { attachToExistingMode = true }
                    )
                }
            }

            if (attachToExistingMode && seedChannel != null) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(BeinCard)
                        .border(1.dp, EmeraldLive.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "📺 القناة: ${seedChannel.name} (${seedChannel.group})",
                            color = EmeraldLive,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "1️⃣ حدّد جودتها:",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            ranks.forEach { r ->
                                BeinFocusableButton(
                                    text = r.labelAr,
                                    isPrimary = seedQuality == r,
                                    compact = true,
                                    modifier = Modifier.weight(1f),
                                    onClick = { seedQuality = r }
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            text = "2️⃣ اضغط OK على القناة المتعددة المراد إضافة الجودة (${seedQuality.labelAr}) إليها:",
                            color = Color(0xFFFDE68A),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                    items(existingGroups, key = { "attach_${it.id}" }) { grp ->
                        val qualsSummary = grp.sources.map { it.quality.labelAr }.distinct().joinToString("/")
                        var rowFocused by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .scale(if (rowFocused) 1.02f else 1.0f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (rowFocused) Color.White else BeinSurface)
                                .border(
                                    width = if (rowFocused) 2.5.dp else 1.dp,
                                    color = if (rowFocused) BeinFocusGlow else BeinBorder,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .onFocusChanged { rowFocused = it.isFocused }
                                .onKeyEvent { ev ->
                                    if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                        ev.nativeKeyEvent.repeatCount == 0 &&
                                        (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                            ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                                            ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                                    ) {
                                        val ok = MultiQualityEngine.addChannelToExistingGroup(
                                            context = context,
                                            groupId = grp.id,
                                            channel = seedChannel,
                                            quality = seedQuality
                                        )
                                        if (ok) {
                                            Toast.makeText(
                                                context,
                                                "✅ تمت إضافة (${seedChannel.name}) كجودة ${seedQuality.labelAr} إلى (${grp.displayName})",
                                                Toast.LENGTH_LONG
                                            ).show()
                                            onSaved()
                                            onDismiss()
                                        }
                                        true
                                    } else false
                                }
                                .focusable()
                                .clickable {
                                    val ok = MultiQualityEngine.addChannelToExistingGroup(
                                        context = context,
                                        groupId = grp.id,
                                        channel = seedChannel,
                                        quality = seedQuality
                                    )
                                    if (ok) {
                                        Toast.makeText(
                                            context,
                                            "✅ تمت إضافة (${seedChannel.name}) كجودة ${seedQuality.labelAr} إلى (${grp.displayName})",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        onSaved()
                                        onDismiss()
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = grp.displayName,
                                    color = if (rowFocused) Color.Black else Color.White,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "الجودات الحالية: [$qualsSummary] • (${grp.sources.size} مصادر)",
                                    color = if (rowFocused) Color(0xFF334155) else Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = "➕ إضافة كـ ${seedQuality.labelAr}",
                                color = if (rowFocused) Color(0xFF047857) else EmeraldLive,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                // 2. Top Quality Slots Strip + Channel Name & Search Bar (TV Remote Friendly)
                val activeStepNum = (ranks.indexOf(activePickingQuality) + 1).coerceAtLeast(1)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF101017))
                        .border(1.2.dp, BeinBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BeinFocusableButton(
                            text = if (displayName.isBlank()) "✏️ اسم القناة: تلقائي (اضغط للتسمية)" else "🏷️ الاسم: $displayName ✏️",
                            isPrimary = false,
                            compact = true,
                            modifier = Modifier.weight(1f),
                            onClick = { showRenameDialog = true }
                        )
                        CompactSearchBar(
                            query = pickSearch,
                            onQueryChange = { pickSearch = it },
                            compact = true,
                            modifier = Modifier
                                .width(if (compact) 160.dp else 220.dp)
                                .height(32.dp)
                        )
                        if (selectedSources.isNotEmpty()) {
                            BeinFocusableButton(
                                text = "🗑️ تفريغ الكل",
                                isPrimary = false,
                                compact = true,
                                onClick = {
                                    selectedSources = emptyList()
                                    activePickingQuality = StreamQualityRank.LOW
                                }
                            )
                        }
                    }

                    // 5 Quality Slots Bar (1.LOW -> 2.SD -> 3.HD -> 4.FHD -> 5.4K)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🎯 اختر الجودة:",
                            color = Color(0xFFFDE68A),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 11.sp else 12.sp
                        )
                        ranks.forEachIndexed { rIdx, rank ->
                            val sourcesForRank = selectedSources.filter { it.quality == rank }
                            val isCurrentRank = activePickingQuality == rank
                            val hasSource = sourcesForRank.isNotEmpty()
                            val rankMod = if (rIdx == 0) Modifier.weight(1f).focusRequester(rankRowFocusRequester) else Modifier.weight(1f)
                            BeinFocusableButton(
                                text = when {
                                    hasSource -> "${rIdx + 1}.${rank.labelAr} ✓(${sourcesForRank.size})"
                                    else -> "${rIdx + 1}.${rank.labelAr}"
                                },
                                isPrimary = isCurrentRank,
                                compact = true,
                                modifier = rankMod.onKeyEvent { ev ->
                                    if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                        ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                                    ) {
                                        runCatching { channelColFocusRequester.requestFocus() }
                                        true
                                    } else false
                                },
                                onClick = {
                                    activePickingQuality = rank
                                    runCatching { channelColFocusRequester.requestFocus() }
                                }
                            )
                        }
                    }

                    // Selected Sources Compact Chips (Press OK on any added source chip to remove it)
                    if (selectedSources.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(selectedSources, key = { idx, s -> "sel_${idx}_${s.sourceId}" }) { idx, srcItem ->
                                var chipFocused by remember { mutableStateOf(false) }
                                Row(
                                    modifier = Modifier
                                        .scale(if (chipFocused) 1.04f else 1.0f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (chipFocused) Color.White else BeinCard)
                                        .border(
                                            width = if (chipFocused) 2.dp else 1.dp,
                                            color = if (chipFocused) BeinFocusGlow else EmeraldLive.copy(alpha = 0.7f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .onFocusChanged { chipFocused = it.isFocused }
                                        .onKeyEvent { ev ->
                                            if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                                ev.nativeKeyEvent.repeatCount == 0 &&
                                                (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                                    ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                                                    ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                                            ) {
                                                val updated = selectedSources.filterIndexed { i, _ -> i != idx }.sortedBy { it.quality.rank }
                                                selectedSources = updated
                                                activePickingQuality = suggestNextAscendingQuality(updated)
                                                true
                                            } else false
                                        }
                                        .focusable()
                                        .clickable {
                                            val updated = selectedSources.filterIndexed { i, _ -> i != idx }.sortedBy { it.quality.rank }
                                            selectedSources = updated
                                            activePickingQuality = suggestNextAscendingQuality(updated)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(
                                        text = "[${srcItem.quality.labelAr}]",
                                        color = if (chipFocused) Color(0xFF047857) else EmeraldLive,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.sp
                                    )
                                    Text(
                                        text = srcItem.channelName,
                                        color = if (chipFocused) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = "✕",
                                        color = if (chipFocused) CrimsonAlert else Color(0xFFFCA5A5),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Split Two-Pane TV Browser: [Right in RTL: Bouquets Column] | [Left in RTL: Channels Column]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // RIGHT PANE (in RTL): Bouquets List
                    Column(
                        modifier = Modifier
                            .width(if (compact) 190.dp else 240.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0E0E14))
                            .border(1.2.dp, BeinBorder, RoundedCornerShape(12.dp))
                            .padding(6.dp)
                    ) {
                        Text(
                            text = "📂 الباقات (${availableBouquets.size})",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 11.sp else 12.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp)
                        )
                        val activeBqIdx = availableBouquets.indexOf(pickerBouquet).coerceAtLeast(0)
                        LazyColumn(
                            state = bouquetListState,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(availableBouquets, key = { idx, bq -> "mq_bq_${idx}_$bq" }) { idx, bqName ->
                                val isSelBq = pickerBouquet == bqName
                                var bqFocused by remember { mutableStateOf(false) }
                                val bqMod = if (idx == activeBqIdx) {
                                    Modifier.focusRequester(bouquetColFocusRequester)
                                } else {
                                    Modifier
                                }
                                Row(
                                    modifier = bqMod
                                        .fillMaxWidth()
                                        .scale(if (bqFocused) 1.03f else 1.0f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            when {
                                                bqFocused -> Color.White
                                                isSelBq -> BeinPrimaryPurple
                                                else -> BeinCard
                                            }
                                        )
                                        .border(
                                            width = if (bqFocused) 2.2.dp else if (isSelBq) 1.5.dp else 0.dp,
                                            color = if (bqFocused) BeinFocusGlow else if (isSelBq) EmeraldLive else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .onFocusChanged { bqFocused = it.isFocused }
                                        .onKeyEvent { ev ->
                                            if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                                ev.nativeKeyEvent.repeatCount == 0
                                            ) {
                                                when (ev.nativeKeyEvent.keyCode) {
                                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                                    KeyEvent.KEYCODE_ENTER,
                                                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                        pickerBouquet = bqName
                                                        runCatching { channelColFocusRequester.requestFocus() }
                                                        true
                                                    }
                                                    else -> false
                                                }
                                            } else false
                                        }
                                        .focusable()
                                        .clickable {
                                            pickerBouquet = bqName
                                            runCatching { channelColFocusRequester.requestFocus() }
                                        }
                                        .padding(horizontal = 9.dp, vertical = if (compact) 9.dp else 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (bqName == "الكل") "🔀 كل الباقات" else bqName,
                                        color = if (bqFocused) Color.Black else Color.White,
                                        fontWeight = if (bqFocused || isSelBq) FontWeight.ExtraBold else FontWeight.Medium,
                                        fontSize = if (compact) 11.sp else 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }

                    // LEFT PANE (in RTL): Channels List for Instant 1-Click Quality Assignment
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0E0E14))
                            .border(1.2.dp, BeinHighlightPurple, RoundedCornerShape(12.dp))
                            .padding(6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📺 قنوات ($pickerBouquet) — اضغط OK لإضافة القناة كجودة [$activeStepNum.${activePickingQuality.labelAr}]",
                                color = EmeraldLive,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (compact) 11.sp else 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${candidateChannels.size} قناة",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        LazyColumn(
                            state = channelListState,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(candidateChannels, key = { idx, ch -> "pick_${idx}_${ch.stableKey}" }) { idx, ch ->
                                val existingSourceForCh = selectedSources.firstOrNull {
                                    it.stableKey == ch.stableKey || (it.url == ch.url && ch.url.isNotBlank())
                                }
                                val alreadyAdded = existingSourceForCh != null
                                var chFocused by remember { mutableStateOf(false) }
                                val chFocusMod = if (idx == 0) {
                                    Modifier.focusRequester(channelColFocusRequester)
                                } else {
                                    Modifier
                                }

                                fun toggleOrAssignChannel() {
                                    if (alreadyAdded && existingSourceForCh != null) {
                                        // Pressing OK again on an already-added channel removes it or updates its quality
                                        if (existingSourceForCh.quality == activePickingQuality) {
                                            val updated = selectedSources.filterNot {
                                                it.stableKey == ch.stableKey || (it.url == ch.url && ch.url.isNotBlank())
                                            }.sortedBy { it.quality.rank }
                                            selectedSources = updated
                                        } else {
                                            val replaced = selectedSources.map {
                                                if (it.stableKey == ch.stableKey || (it.url == ch.url && ch.url.isNotBlank())) {
                                                    it.copy(quality = activePickingQuality)
                                                } else it
                                            }.sortedBy { it.quality.rank }
                                            selectedSources = replaced
                                            activePickingQuality = suggestNextAscendingQuality(replaced, afterQuality = activePickingQuality)
                                        }
                                    } else {
                                        val chosenQuality = activePickingQuality
                                        val newSource = MultiQualityEngine.SavedQualitySource.fromChannel(
                                            channel = ch,
                                            quality = chosenQuality
                                        )
                                        val updated = (selectedSources + newSource)
                                            .distinctBy { "${it.quality.name}_${it.stableKey}_${it.url}" }
                                            .sortedBy { it.quality.rank }
                                        selectedSources = updated
                                        if (displayName.isBlank()) {
                                            displayName = MultiQualityEngine.cleanBaseChannelName(ch.name)
                                        }
                                        activePickingQuality = suggestNextAscendingQuality(updated, afterQuality = chosenQuality)
                                    }
                                }

                                Row(
                                    modifier = chFocusMod
                                        .fillMaxWidth()
                                        .scale(if (chFocused) 1.02f else 1.0f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            when {
                                                chFocused -> Color.White
                                                alreadyAdded -> Color(0xFF0E2920)
                                                else -> BeinCard
                                            }
                                        )
                                        .border(
                                            width = if (chFocused) 2.4.dp else 1.dp,
                                            color = when {
                                                chFocused -> BeinFocusGlow
                                                alreadyAdded -> EmeraldLive
                                                else -> BeinBorder
                                            },
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .onFocusChanged { chFocused = it.isFocused }
                                        .onKeyEvent { ev ->
                                            if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                                ev.nativeKeyEvent.repeatCount == 0
                                            ) {
                                                when (ev.nativeKeyEvent.keyCode) {
                                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                                    KeyEvent.KEYCODE_ENTER,
                                                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                                        SafeClickGuard.runThrottled("MQ_PICK_CH", 140L) {
                                                            toggleOrAssignChannel()
                                                        }
                                                        true
                                                    }
                                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                        runCatching { bouquetColFocusRequester.requestFocus() }
                                                        true
                                                    }
                                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                        if (selectedSources.size >= 2) {
                                                            runCatching { saveBtnFocusRequester.requestFocus() }
                                                        } else {
                                                            runCatching { rankRowFocusRequester.requestFocus() }
                                                        }
                                                        true
                                                    }
                                                    else -> false
                                                }
                                            } else false
                                        }
                                        .focusable()
                                        .clickable {
                                            SafeClickGuard.runThrottled("MQ_PICK_CH", 140L) {
                                                toggleOrAssignChannel()
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ch.name,
                                            color = if (chFocused) Color.Black else Color.White,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = if (compact) 12.sp else 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "📂 ${ch.group}  •  📡 ${ch.playlistName}",
                                            color = if (chFocused) Color(0xFF334155) else Color(0xFF94A3B8),
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (alreadyAdded) {
                                            "✓ [${existingSourceForCh?.quality?.labelAr ?: ""}]"
                                        } else {
                                            "➕ ${activePickingQuality.labelAr} (OK)"
                                        },
                                        color = when {
                                            chFocused && alreadyAdded -> Color(0xFF047857)
                                            chFocused -> Color(0xFF1E1B4B)
                                            alreadyAdded -> EmeraldLive
                                            else -> Color(0xFFFDE68A)
                                        },
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Compact Saved Multi-Quality Channels Strip at Bottom (if any exist)
                if (existingGroups.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        item {
                            Text(
                                text = "📋 المحفوظة (${existingGroups.size}):",
                                color = Color(0xFFCBD5E1),
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 11.sp
                            )
                        }
                        items(existingGroups, key = { "saved_mq_${it.id}" }) { g ->
                            val quals = g.sources.map { it.quality.labelAr }.distinct().joinToString("/")
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BeinCard)
                                    .border(1.dp, if (editingGroupId == g.id) EmeraldLive else BeinBorder, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${g.displayName} [$quals]",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                                BeinFocusableButton(
                                    text = "✏️",
                                    isPrimary = editingGroupId == g.id,
                                    compact = true,
                                    onClick = {
                                        attachToExistingMode = false
                                        editingGroupId = g.id
                                        displayName = g.displayName
                                        selectedSources = g.sources
                                        activePickingQuality = suggestNextAscendingQuality(g.sources)
                                    }
                                )
                                BeinFocusableButton(
                                    text = "🗑️",
                                    isPrimary = false,
                                    compact = true,
                                    onClick = {
                                        MultiQualityEngine.deleteUserGroup(context, g.id)
                                        existingGroups = MultiQualityEngine.listUserGroups()
                                        if (editingGroupId == g.id) {
                                            editingGroupId = null
                                            selectedSources = emptyList()
                                        }
                                        onSaved()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        var draftName by remember(displayName) { mutableStateOf(displayName) }
        Dialog(onDismissRequest = { showRenameDialog = false }) {
            Column(
                modifier = Modifier
                    .widthIn(min = 300.dp, max = 460.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(BeinSurface)
                    .border(2.dp, BeinHighlightPurple, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "🏷️ اسم القناة المتعددة الجودات",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
                OutlinedTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    singleLine = true,
                    label = { Text("اكتب اسم القناة (أو اتركه فارغاً للاسم التلقائي)") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = EmeraldLive,
                        unfocusedBorderColor = BeinBorder
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BeinFocusableButton(
                        text = "إلغاء",
                        isPrimary = false,
                        compact = true,
                        modifier = Modifier.weight(1f),
                        onClick = { showRenameDialog = false }
                    )
                    BeinFocusableButton(
                        text = "✓ اعتماد الاسم",
                        isPrimary = true,
                        compact = true,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            displayName = draftName.trim()
                            showRenameDialog = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun BeinCatalogBrowserView(
    items: List<TvChannel>,
    channelsByBouquet: Map<String, List<TvChannel>>,
    bouquetSummaries: List<BouquetSummary>,
    favorites: List<TvChannel>,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    statusMessage: String,
    isLoading: Boolean,
    useGrid: Boolean,
    showBouquetSidebar: Boolean,
    onToggleBouquetSidebar: () -> Unit,
    gridState: LazyGridState,
    listState: LazyListState,
    bouquetListState: LazyListState,
    lastWatchedKey: String?,
    restoreFocusTrigger: Int,
    previewChannel: TvChannel? = null,
    onSelectPreviewChannel: (TvChannel) -> Unit = {},
    xtreamPlaylists: List<XtreamAccountConfig> = emptyList(),
    preferences: PlayerPreferences = PlayerPreferences(),
    networkSpeedLabel: String = "",
    lang: AppUiLanguage,
    compact: Boolean,
    deviceId: String,
    onPlay: (TvChannel) -> Unit,
    onToggleFavorite: (TvChannel) -> Unit,
    onRefresh: () -> Unit,
    onMultiQualityChanged: () -> Unit = {}
) {
    val favLabel = AppStrings.favoritesLabel(lang)
    val allLabel = AppStrings.allBouquetsLabel(lang)

    val selectedBouquetFocusRequester = remember { FocusRequester() }
    val activeChannelInListFocusRequester = remember { FocusRequester() }
    val miniPlayerFocusRequester = remember { FocusRequester() }
    var focusChannelListTrigger by remember { mutableStateOf(0) }
    var focusBouquetSidebarTrigger by remember { mutableStateOf(0) }
    var showMqBuilder by remember { mutableStateOf(false) }
    var mqSeedChannel by remember { mutableStateOf<TvChannel?>(null) }
    var mqEditGroupId by remember { mutableStateOf<String?>(null) }
    var mqRefreshTick by remember { mutableStateOf(0) }

    // Instant O(1) bouquet slice lookup — zero UI-thread filtering across 60,000+ channels!
    val bouquetSlice = remember(items, channelsByBouquet, favorites, selectedCategory, favLabel, allLabel) {
        when (selectedCategory) {
            favLabel -> favorites.filter { it.contentType == StreamContentType.LIVE_TV }
            allLabel, "الكل" -> items
            else -> channelsByBouquet[selectedCategory].orEmpty()
        }
    }

    val filteredItems by produceState(
        initialValue = bouquetSlice,
        bouquetSlice,
        searchQuery
    ) {
        val q = searchQuery.trim()
        value = if (q.isEmpty()) {
            bouquetSlice
        } else {
            withContext(Dispatchers.Default) {
                val qLow = q.lowercase(java.util.Locale.ROOT)
                bouquetSlice.filter { ch -> ch.matchesSearch(qLow) }
            }
        }
    }

    val effectiveFocusKey = previewChannel?.stableKey ?: lastWatchedKey
    val targetWatchedIdx = remember(filteredItems, effectiveFocusKey) {
        if (effectiveFocusKey == null) -1 else filteredItems.indexOfFirst { it.stableKey == effectiveFocusKey }
    }

    // Windowed Lazy Paging: render 150 items per batch and expand automatically as user scrolls!
    var visibleLimit by remember(selectedCategory, searchQuery, items.size) {
        val minNeeded = if (targetWatchedIdx >= 0) targetWatchedIdx + 100 else 150
        mutableStateOf(minNeeded.coerceAtLeast(150).coerceAtMost(filteredItems.size.coerceAtLeast(150)))
    }
    val windowedItems = remember(filteredItems, visibleLimit) {
        if (filteredItems.size <= visibleLimit) filteredItems
        else filteredItems.subList(0, visibleLimit)
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= windowedItems.size - 28 && windowedItems.size < filteredItems.size
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            visibleLimit = (visibleLimit + 150).coerceAtMost(filteredItems.size)
        }
    }

    val favSet = remember(favorites) { favorites.map { it.stableKey }.toHashSet() }

    val targetChannelFocusIdx = remember(targetWatchedIdx) {
        if (targetWatchedIdx >= 0) targetWatchedIdx else 0
    }

    // When user presses OK on a Bouquet -> smoothly scroll & move D-Pad focus straight into the Middle Channels List!
    LaunchedEffect(focusChannelListTrigger) {
        if (focusChannelListTrigger > 0 && filteredItems.isNotEmpty()) {
            val idx = if (targetWatchedIdx >= 0) targetWatchedIdx else targetChannelFocusIdx
            if (visibleLimit <= idx) {
                visibleLimit = (idx + 120).coerceAtMost(filteredItems.size)
            }
            runCatching { listState.scrollToItem((idx - 2).coerceAtLeast(0)) }
            for (attempt in 1..8) {
                delay(if (attempt <= 2) 20L else 40L)
                if (runCatching { activeChannelInListFocusRequester.requestFocus() }.isSuccess) break
                if (attempt == 4) runCatching { listState.scrollToItem((idx - 1).coerceAtLeast(0)) }
            }
        }
    }

    // When user presses DPAD_RIGHT from Channels List -> scroll active bouquet into view if needed and focus it!
    LaunchedEffect(focusBouquetSidebarTrigger) {
        if (focusBouquetSidebarTrigger > 0 && showBouquetSidebar && bouquetSummaries.isNotEmpty()) {
            val bqIdx = bouquetSummaries.indexOfFirst { it.name == selectedCategory }.coerceAtLeast(0)
            val vis = bouquetListState.layoutInfo.visibleItemsInfo.any { it.index == bqIdx }
            if (!vis) {
                runCatching { bouquetListState.scrollToItem((bqIdx - 2).coerceAtLeast(0)) }
            }
            for (attempt in 1..5) {
                delay(25L)
                if (runCatching { selectedBouquetFocusRequester.requestFocus() }.isSuccess) break
            }
        }
    }

    // Restore D-Pad on the SAME live channel after Back from fullscreen / when list opens
    LaunchedEffect(restoreFocusTrigger) {
        if (restoreFocusTrigger <= 0) return@LaunchedEffect
        delay(50L)
        val idx = targetWatchedIdx
        if (idx < 0 || filteredItems.isEmpty()) return@LaunchedEffect
        if (visibleLimit <= idx) {
            visibleLimit = (idx + 120).coerceAtMost(filteredItems.size)
        }
        val bqIdx = bouquetSummaries.indexOfFirst { it.name == selectedCategory }
        if (bqIdx >= 0) {
            runCatching { bouquetListState.scrollToItem((bqIdx - 1).coerceAtLeast(0)) }
        }
        runCatching { listState.scrollToItem((idx - 2).coerceAtLeast(0)) }
        for (attempt in 1..10) {
            delay(if (attempt <= 3) 30L else 55L)
            val vis = listState.layoutInfo.visibleItemsInfo.any { it.index == idx }
            if (!vis) runCatching { listState.scrollToItem((idx - 1).coerceAtLeast(0)) }
            if (runCatching { activeChannelInListFocusRequester.requestFocus() }.isSuccess) break
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = if (compact) 6.dp else 10.dp,
                vertical = if (compact) 4.dp else 6.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
    ) {
        // =========================================================================
        // 1. RIGHT COLUMN (in RTL): Bouquets Directory ("📂 الباقات")
        //    Pressing OK selects the bouquet and moves focus to the Channels List!
        // =========================================================================
        if (showBouquetSidebar && bouquetSummaries.size > 1) {
            Column(
                modifier = Modifier
                    .width(if (compact) 158.dp else 210.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinSurface)
                    .border(1.5.dp, BeinBorder, RoundedCornerShape(14.dp))
                    .padding(if (compact) 5.dp else 7.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📂 ${AppStrings.tr(lang, "الباقات", "Bouquets", "Bouquets")}",
                        color = Color.White,
                        fontSize = if (compact) 13.sp else 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "${bouquetSummaries.size}",
                        color = EmeraldLive,
                        fontSize = if (compact) 11.sp else 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                LazyColumn(
                    state = bouquetListState,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(bouquetSummaries, key = { idx, bq -> "${idx}_${bq.name}" }) { _, bq ->
                        val isSelected = selectedCategory == bq.name
                        var focused by remember { mutableStateOf(false) }
                        val bqFocusMod = if (isSelected) {
                            Modifier.focusRequester(selectedBouquetFocusRequester)
                        } else {
                            Modifier
                        }
                        Row(
                            modifier = bqFocusMod
                                .fillMaxWidth()
                                .wrapContentHeight()
                                .scale(if (focused) 1.03f else 1.0f)
                                .clip(RoundedCornerShape(10.dp))
                                .onFocusChanged { focused = it.isFocused }
                                .onKeyEvent { ev ->
                                    if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                        ev.nativeKeyEvent.repeatCount == 0
                                    ) {
                                        when (ev.nativeKeyEvent.keyCode) {
                                            KeyEvent.KEYCODE_DPAD_CENTER,
                                            KeyEvent.KEYCODE_ENTER,
                                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                                SafeClickGuard.runThrottled("BOUQUET_CLICK", 120L) {
                                                    onSelectCategory(bq.name)
                                                    focusChannelListTrigger += 1
                                                }
                                                true
                                            }
                                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                if (!isSelected) onSelectCategory(bq.name)
                                                focusChannelListTrigger += 1
                                                true
                                            }
                                            else -> false
                                        }
                                    } else false
                                }
                                .focusable()
                                .background(
                                    when {
                                        focused -> Color.White
                                        isSelected -> BeinPrimaryPurple
                                        else -> BeinCard
                                    }
                                )
                                .border(
                                    width = if (focused) 2.5.dp else if (isSelected) 1.6.dp else 0.dp,
                                    color = if (focused) BeinFocusGlow else if (isSelected) EmeraldLive else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    SafeClickGuard.runThrottled("BOUQUET_CLICK", 120L) {
                                        onSelectCategory(bq.name)
                                        focusChannelListTrigger += 1
                                    }
                                }
                                .padding(
                                    horizontal = if (compact) 8.dp else 10.dp,
                                    vertical = if (compact) 7.dp else 9.dp
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = bq.name,
                                color = if (focused) Color.Black else Color.White,
                                fontSize = if (compact) 12.sp else 14.sp,
                                fontWeight = if (focused || isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Visible,
                                lineHeight = 17.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = "${bq.count}",
                                color = if (focused) BeinPrimaryPurple else if (isSelected) EmeraldLive else Color(0xFFE2E8F0),
                                fontSize = if (compact) 11.sp else 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }

        // =========================================================================
        // 2. MAIN WORKSPACE: Top Filter Bar + [Middle Channels List | Left Mini Player]
        // =========================================================================
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BeinFocusableButton(
                    text = if (showBouquetSidebar) {
                        "📂 ${AppStrings.tr(lang, "إخفاء الباقات", "Masquer", "Hide Bouquets")}"
                    } else {
                        "📂 ${AppStrings.tr(lang, "إظهار الباقات", "Afficher", "Show Bouquets")}"
                    },
                    isPrimary = showBouquetSidebar,
                    compact = true,
                    onClick = onToggleBouquetSidebar
                )
                if (!CustomOrderHelper.isMultiQualityBouquetHidden(preferences.hiddenBouquets)) {
                    BeinFocusableButton(
                        text = "🎛️ متعدد جودات",
                        isPrimary = selectedCategory == MultiQualityEngine.BOUQUET_NAME,
                        compact = true,
                        onClick = {
                            mqSeedChannel = previewChannel?.takeIf { it.playlistId != MultiQualityEngine.PLAYLIST_ID }
                            mqEditGroupId = previewChannel?.takeIf { it.playlistId == MultiQualityEngine.PLAYLIST_ID }
                                ?.let { MultiQualityEngine.findGroupIdForChannel(it) }
                            showMqBuilder = true
                        }
                    )
                }

                Text(
                    text = "$selectedCategory (${filteredItems.size})",
                    color = Color(0xFFE2E8F0),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = if (compact) 13.sp else 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                CompactSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchChange,
                    compact = compact,
                    modifier = Modifier
                        .widthIn(
                            min = if (compact) 125.dp else 160.dp,
                            max = if (compact) 195.dp else 240.dp
                        )
                        .height(if (compact) 30.dp else 34.dp)
                )
            }

            Spacer(Modifier.height(if (compact) 5.dp else 8.dp))

            if (showMqBuilder) {
                ManualMultiQualityBuilderDialog(
                    allLiveChannels = items,
                    channelsByBouquet = channelsByBouquet,
                    initialBouquet = selectedCategory,
                    seedChannel = mqSeedChannel,
                    initialEditGroupId = mqEditGroupId,
                    compact = compact,
                    onDismiss = {
                        showMqBuilder = false
                        mqSeedChannel = null
                        mqEditGroupId = null
                    },
                    onSaved = {
                        mqRefreshTick++
                        onMultiQualityChanged()
                    }
                )
            }

            if (filteredItems.isEmpty() && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = AppStrings.tr(lang, "لا توجد قنوات مطابقة حالياً", "Aucune chaîne trouvée", "No matching channels"),
                            color = Color.White,
                            fontSize = if (compact) 16.sp else 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "📞 علي سات : $ALI_SAT_PHONE_NUMBER • رمز الشاشة: $deviceId",
                            color = Color(0xFFE2E8F0),
                            fontSize = if (compact) 13.sp else 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))
                        BeinFocusableButton(
                            text = AppStrings.tr(lang, "تحديث القنوات الآن 🔄", "Actualiser maintenant 🔄", "Refresh Channels Now 🔄"),
                            isPrimary = true,
                            compact = compact,
                            onClick = onRefresh
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
                ) {
                    // -----------------------------------------------------------------
                    // MIDDLE COLUMN (Orange Frame in Screenshot): Channels List
                    // - 1st OK on channel -> plays inside the Mini Player (Green Frame)
                    // - 2nd OK on the SAME channel -> expands to Fullscreen!
                    // -----------------------------------------------------------------
                    Column(
                        modifier = Modifier
                            .weight(0.44f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp))
                            .background(BeinSurface)
                            .border(1.5.dp, Color(0xFF383846), RoundedCornerShape(14.dp))
                            .padding(if (compact) 6.dp else 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📺 ${AppStrings.tr(lang, "قائمة القنوات", "Liste des Chaînes", "Channels List")}",
                                color = Color.White,
                                fontSize = if (compact) 12.sp else 14.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = AppStrings.tr(lang, "OK معـاينة • OK×2 تكبير ⛶", "OK Aperçu • OK×2 Plein écran", "OK Preview • OK×2 Fullscreen"),
                                color = EmeraldLive,
                                fontSize = if (compact) 10.sp else 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(Modifier.height(4.dp))

                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            itemsIndexed(windowedItems, key = { idx, ch -> "${idx}_${ch.stableKey}" }) { idx, ch ->
                                val isCurrentlyPreviewing = previewChannel?.stableKey == ch.stableKey
                                val isLastWatchedItem = ch.stableKey == effectiveFocusKey ||
                                    (previewChannel != null && ch.stableKey == previewChannel.stableKey)
                                val itemFocusMod = if (idx == targetWatchedIdx || idx == targetChannelFocusIdx) {
                                    Modifier.focusRequester(activeChannelInListFocusRequester)
                                } else {
                                    Modifier
                                }
                                LiveSplitChannelRow(
                                    indexNumber = idx + 1,
                                    channel = ch,
                                    isFavorite = favSet.contains(ch.stableKey),
                                    isPreviewing = isCurrentlyPreviewing,
                                    isLastWatched = isLastWatchedItem,
                                    compact = compact,
                                    modifier = itemFocusMod,
                                    onClickChannel = {
                                        if (isCurrentlyPreviewing) {
                                            // Second OK on the same channel -> Expand to Fullscreen!
                                            onPlay(ch)
                                        } else {
                                            // First OK on channel -> Play in Mini Video Player!
                                            onSelectPreviewChannel(ch)
                                        }
                                    },
                                    onToggleFavorite = { onToggleFavorite(ch) },
                                    onOpenMultiQualityStudio = {
                                        if (ch.playlistId == MultiQualityEngine.PLAYLIST_ID) {
                                            mqSeedChannel = null
                                            mqEditGroupId = MultiQualityEngine.findGroupIdForChannel(ch)
                                        } else {
                                            mqSeedChannel = ch
                                            mqEditGroupId = null
                                        }
                                        showMqBuilder = true
                                    },
                                    onMoveFocusToBouquets = {
                                        if (showBouquetSidebar && bouquetSummaries.size > 1) {
                                            focusBouquetSidebarTrigger += 1
                                        }
                                    },
                                    onMoveFocusToMiniPlayer = {
                                        if (previewChannel != null) {
                                            runCatching { miniPlayerFocusRequester.requestFocus() }
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // -----------------------------------------------------------------
                    // LEFT COLUMN (Green Frame in Screenshot): Live Mini Video Player
                    // -----------------------------------------------------------------
                    Column(
                        modifier = Modifier
                            .weight(0.56f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF0A0A0E))
                            .border(
                                width = 2.dp,
                                color = if (previewChannel != null) EmeraldLive.copy(alpha = 0.85f) else BeinBorder,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(if (compact) 6.dp else 10.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
                    ) {
                        val activePreview = previewChannel
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black)
                                .border(1.dp, Color(0xFF262632), RoundedCornerShape(12.dp))
                                .clickable {
                                    if (activePreview != null) {
                                        onPlay(activePreview)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (activePreview != null) {
                                LiveMiniPreviewPlayer(
                                    channel = activePreview,
                                    xtreamPlaylists = xtreamPlaylists,
                                    preferences = preferences,
                                    networkSpeedLabel = networkSpeedLabel,
                                    compact = compact,
                                    onExpandFullScreen = { onPlay(activePreview) }
                                )
                            } else {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    BrandLogoBadge(
                                        modifier = Modifier.size(if (compact) 56.dp else 74.dp),
                                        cornerRadius = 16.dp
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = AppStrings.tr(
                                            lang,
                                            "📺 شاشة المعاينة المباشرة",
                                            "📺 Écran d'Aperçu en Direct",
                                            "📺 Live Preview Player"
                                        ),
                                        color = Color.White,
                                        fontSize = if (compact) 15.sp else 18.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = AppStrings.tr(
                                            lang,
                                            "اضغط (OK) على أي قناة في القائمة لتشغيلها هنا فوراً، واضغط (OK) مرة ثانية على نفس القناة لتكبير الشاشة كلياً ⛶",
                                            "Appuyez sur (OK) sur une chaîne pour la lire ici, puis une 2ème fois sur (OK) pour le plein écran ⛶",
                                            "Press (OK) on any channel to preview it here, and press (OK) again on the same channel for Fullscreen ⛶"
                                        ),
                                        color = Color(0xFFCBD5E1),
                                        fontSize = if (compact) 12.sp else 13.sp,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 19.sp
                                    )
                                }
                            }
                        }

                        // Mini Player Bottom Info & Quick Control Bar
                        if (activePreview != null) {
                            val activeIsFav = favSet.contains(activePreview.stableKey)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BeinCard)
                                    .border(1.dp, BeinBorder, RoundedCornerShape(12.dp))
                                    .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        ChannelAvatar(
                                            name = activePreview.name,
                                            logoUrl = activePreview.logo,
                                            modifier = Modifier.size(if (compact) 34.dp else 42.dp),
                                            cornerRadius = 8.dp,
                                            innerPadding = 2.dp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = activePreview.name,
                                                color = Color.White,
                                                fontSize = if (compact) 13.sp else 15.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = activePreview.group,
                                                color = EmeraldLive,
                                                fontSize = if (compact) 11.sp else 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Text(
                                        text = "● LIVE",
                                        color = Color.White,
                                        fontSize = if (compact) 10.sp else 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier
                                            .background(CrimsonAlert, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    BeinFocusableButton(
                                        text = AppStrings.tr(lang, "⛶ تكبير الشاشة كلياً (OK)", "⛶ Plein écran (OK)", "⛶ Fullscreen (OK)"),
                                        isPrimary = true,
                                        compact = true,
                                        modifier = Modifier
                                            .weight(1.4f)
                                            .focusRequester(miniPlayerFocusRequester)
                                            .onKeyEvent { ev ->
                                                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                                    ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                                                ) {
                                                    runCatching { activeChannelInListFocusRequester.requestFocus() }
                                                    true
                                                } else false
                                            },
                                        onClick = { onPlay(activePreview) }
                                    )
                                    BeinFocusableButton(
                                        text = "⏮",
                                        isPrimary = false,
                                        compact = true,
                                        onClick = {
                                            if (filteredItems.size > 1) {
                                                val curIdx = filteredItems.indexOfFirst { it.stableKey == activePreview.stableKey }
                                                val prevIdx = if (curIdx > 0) curIdx - 1 else filteredItems.lastIndex
                                                onSelectPreviewChannel(filteredItems[prevIdx])
                                            }
                                        }
                                    )
                                    BeinFocusableButton(
                                        text = "⏭",
                                        isPrimary = false,
                                        compact = true,
                                        onClick = {
                                            if (filteredItems.size > 1) {
                                                val curIdx = filteredItems.indexOfFirst { it.stableKey == activePreview.stableKey }
                                                val nextIdx = if (curIdx >= 0 && curIdx < filteredItems.lastIndex) curIdx + 1 else 0
                                                onSelectPreviewChannel(filteredItems[nextIdx])
                                            }
                                        }
                                    )
                                    BeinFocusableButton(
                                        text = if (activePreview.playlistId == MultiQualityEngine.PLAYLIST_ID) "🎛️ تعديل جودات" else "🎛️ +جودة",
                                        isPrimary = activePreview.playlistId == MultiQualityEngine.PLAYLIST_ID,
                                        compact = true,
                                        onClick = {
                                            if (activePreview.playlistId == MultiQualityEngine.PLAYLIST_ID) {
                                                mqSeedChannel = null
                                                mqEditGroupId = MultiQualityEngine.findGroupIdForChannel(activePreview)
                                            } else {
                                                mqSeedChannel = activePreview
                                                mqEditGroupId = null
                                            }
                                            showMqBuilder = true
                                        }
                                    )
                                    BeinFocusableButton(
                                        text = if (activeIsFav) "❤️" else "🤍",
                                        isPrimary = activeIsFav,
                                        compact = true,
                                        onClick = { onToggleFavorite(activePreview) }
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

@Composable
private fun LiveSplitChannelRow(
    indexNumber: Int,
    channel: TvChannel,
    isFavorite: Boolean,
    isPreviewing: Boolean,
    isLastWatched: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClickChannel: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenMultiQualityStudio: () -> Unit = {},
    onMoveFocusToBouquets: () -> Unit,
    onMoveFocusToMiniPlayer: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var centerLongHandled by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(if (isFocused) 1.02f else 1.0f)
            .clip(RoundedCornerShape(11.dp))
            .background(
                when {
                    isFocused -> Color.White
                    isPreviewing -> Color(0xFF0E2920)
                    isLastWatched -> Color(0xFF17102B)
                    else -> BeinCard
                }
            )
            .border(
                width = when {
                    isFocused -> 2.6.dp
                    isPreviewing -> 2.dp
                    isLastWatched -> 1.4.dp
                    else -> 1.dp
                },
                color = when {
                    isFocused -> BeinFocusGlow
                    isPreviewing -> EmeraldLive
                    isLastWatched -> BeinHighlightPurple
                    else -> BeinBorder
                },
                shape = RoundedCornerShape(11.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (!it.isFocused) centerLongHandled = false
            }
            .onKeyEvent { ev ->
                val code = ev.nativeKeyEvent.keyCode
                val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER ||
                    code == KeyEvent.KEYCODE_ENTER ||
                    code == KeyEvent.KEYCODE_NUMPAD_ENTER
                val isFavRemoteKey = code == KeyEvent.KEYCODE_MENU ||
                    code == KeyEvent.KEYCODE_BOOKMARK ||
                    code == KeyEvent.KEYCODE_STAR ||
                    code == KeyEvent.KEYCODE_CAPTIONS ||
                    code == KeyEvent.KEYCODE_PROG_YELLOW ||
                    code == KeyEvent.KEYCODE_PROG_RED
                val isMqStudioRemoteKey = code == KeyEvent.KEYCODE_PROG_BLUE ||
                    code == KeyEvent.KEYCODE_PROG_GREEN ||
                    code == KeyEvent.KEYCODE_INFO

                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when {
                        isCenter -> {
                            if (ev.nativeKeyEvent.repeatCount == 0) {
                                centerLongHandled = false
                                true
                            } else if (ev.nativeKeyEvent.repeatCount >= 1 && !centerLongHandled) {
                                centerLongHandled = true
                                SafeClickGuard.runThrottled("FAV_TOGGLE", 240L) { onToggleFavorite() }
                                true
                            } else {
                                true
                            }
                        }
                        isFavRemoteKey && ev.nativeKeyEvent.repeatCount == 0 -> {
                            SafeClickGuard.runThrottled("FAV_TOGGLE", 240L) { onToggleFavorite() }
                            true
                        }
                        isMqStudioRemoteKey && ev.nativeKeyEvent.repeatCount == 0 -> {
                            SafeClickGuard.runThrottled("MQ_ROW_OPEN", 240L) { onOpenMultiQualityStudio() }
                            true
                        }
                        code == KeyEvent.KEYCODE_DPAD_RIGHT && ev.nativeKeyEvent.repeatCount == 0 -> {
                            onMoveFocusToBouquets()
                            true
                        }
                        code == KeyEvent.KEYCODE_DPAD_LEFT && ev.nativeKeyEvent.repeatCount == 0 -> {
                            onMoveFocusToMiniPlayer()
                            true
                        }
                        else -> false
                    }
                } else if (ev.nativeKeyEvent.action == KeyEvent.ACTION_UP && isCenter) {
                    if (!centerLongHandled) {
                        SafeClickGuard.runThrottled("SPLIT_CH_CLICK", 140L) { onClickChannel() }
                    }
                    centerLongHandled = false
                    true
                } else false
            }
            .focusable()
            .combinedClickable(
                onClick = { SafeClickGuard.runThrottled("SPLIT_CH_CLICK", 140L) { onClickChannel() } },
                onLongClick = { SafeClickGuard.runThrottled("FAV_TOGGLE", 240L) { onToggleFavorite() } }
            )
            .padding(
                horizontal = if (compact) 8.dp else 10.dp,
                vertical = if (compact) 6.dp else 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Channel index number badge
        Box(
            modifier = Modifier
                .widthIn(min = if (compact) 26.dp else 32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        isFocused -> Color(0xFF14141D)
                        isPreviewing -> EmeraldLive
                        else -> Color(0xFF101016)
                    }
                )
                .padding(horizontal = 5.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$indexNumber",
                color = if (isPreviewing && !isFocused) Color.Black else Color.White,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        Spacer(Modifier.width(8.dp))

        ChannelAvatar(
            name = channel.name,
            logoUrl = channel.logo,
            modifier = Modifier.size(if (compact) 38.dp else 46.dp),
            cornerRadius = 8.dp,
            innerPadding = 2.dp
        )

        Spacer(Modifier.width(8.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = channel.name,
                color = if (isFocused) Color.Black else Color.White,
                fontSize = if (compact) 13.sp else 14.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (isFocused || isPreviewing) Modifier.fillMaxWidth().basicMarquee() else Modifier.fillMaxWidth()
            )
            if (isPreviewing) {
                Text(
                    text = if (isFocused) "▶ شغّال الآن • اضغط OK لتكبير الشاشة ⛶" else "▶ شغّال في المشغل المصغّر",
                    color = if (isFocused) Color(0xFF047857) else EmeraldLive,
                    fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }
        }

        if (isFavorite) {
            Text(
                text = "❤️",
                fontSize = if (compact) 12.sp else 14.sp,
                modifier = Modifier
                    .clickable { SafeClickGuard.runThrottled("FAV_TOGGLE", 200L) { onToggleFavorite() } }
                    .padding(horizontal = 4.dp)
            )
        }

        Text(
            text = "🎛️",
            fontSize = if (compact) 12.sp else 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (isFocused) Color(0xFF1E1B4B) else Color(0xFF181824))
                .clickable { SafeClickGuard.runThrottled("MQ_ROW_OPEN", 200L) { onOpenMultiQualityStudio() } }
                .padding(horizontal = 5.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun CompactSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    var isClearFocused by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = modifier
                .scale(if (isFocused) 1.04f else 1.0f)
                .clip(RoundedCornerShape(18.dp))
                .background(if (isFocused) Color.White else if (query.isNotEmpty()) BeinPrimaryPurple else BeinCard)
                .border(
                    width = if (isFocused) 2.5.dp else 1.2.dp,
                    color = if (isFocused) BeinFocusGlow else if (query.isNotEmpty()) Color.White else BeinBorder,
                    shape = RoundedCornerShape(18.dp)
                )
                .onFocusChanged { isFocused = it.isFocused }
                .onKeyEvent { ev ->
                    if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                        ev.nativeKeyEvent.repeatCount == 0 &&
                        (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                            ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                    ) {
                        showSearchDialog = true
                        true
                    } else false
                }
                .focusable()
                .clickable { showSearchDialog = true }
                .padding(horizontal = if (compact) 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = if (isFocused) Color.Black else Color.White,
                modifier = Modifier.size(if (compact) 14.dp else 16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (query.isEmpty()) "بحث (اضغط OK)..." else "🔍 $query",
                color = if (isFocused) Color.Black else Color.White,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = if (isFocused || query.isNotEmpty()) FontWeight.ExtraBold else FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(if (compact) 28.dp else 34.dp)
                    .clip(CircleShape)
                    .background(if (isClearFocused) Color.White else Color(0xFF991B1B))
                    .border(
                        width = if (isClearFocused) 2.dp else 1.dp,
                        color = if (isClearFocused) BeinFocusGlow else Color.White.copy(alpha = 0.7f),
                        shape = CircleShape
                    )
                    .onFocusChanged { isClearFocused = it.isFocused }
                    .onKeyEvent { ev ->
                        if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                        ) {
                            onQueryChange("")
                            true
                        } else false
                    }
                    .focusable()
                    .clickable { onQueryChange("") },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "مسح البحث",
                    tint = if (isClearFocused) Color.Black else Color.White,
                    modifier = Modifier.size(if (compact) 14.dp else 16.dp)
                )
            }
        }
    }

    if (showSearchDialog) {
        var draftQuery by remember(query) { mutableStateOf(query) }
        val inputFocusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(80L)
            runCatching { inputFocusRequester.requestFocus() }
        }
        Dialog(onDismissRequest = { showSearchDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 460.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(BeinSurface)
                    .border(2.dp, BeinHighlightPurple, RoundedCornerShape(18.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "🔍 البحث السريع",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                OutlinedTextField(
                    value = draftQuery,
                    onValueChange = {
                        draftQuery = it
                        onQueryChange(it)
                    },
                    placeholder = {
                        Text("اكتب اسم القناة أو الفيلم أو الباقة...", color = Color.LightGray, fontSize = 14.sp)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { showSearchDialog = false }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BeinFocusableButton(
                        text = "✅ عرض النتائج (${if (draftQuery.isBlank()) "الكل" else draftQuery})",
                        isPrimary = true,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onQueryChange(draftQuery)
                            showSearchDialog = false
                        }
                    )
                    if (draftQuery.isNotEmpty()) {
                        BeinFocusableButton(
                            text = "🗑️ مسح",
                            isPrimary = false,
                            compact = compact,
                            onClick = {
                                draftQuery = ""
                                onQueryChange("")
                                showSearchDialog = false
                            }
                        )
                    }
                    BeinFocusableButton(
                        text = "إغلاق",
                        isPrimary = false,
                        compact = compact,
                        onClick = { showSearchDialog = false }
                    )
                }
            }
        }
    }
}

/**
 * Clean beIN Dark Purple Channel Card:
 * • NO '#' or Channel Numbers on the logo/card in the main UI!
 * • Full Channel Name & Bouquet Name displayed clearly without edge clipping!
 */
@Composable
private fun BeinChannelCard(
    channel: TvChannel,
    isFavorite: Boolean,
    isLastWatched: Boolean = false,
    watchProgress: VodWatchProgress? = null,
    showContentTypeBadge: Boolean = false,
    showQuickFavoriteFooter: Boolean = false,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var centerLongHandled by remember { mutableStateOf(false) }

    LaunchedEffect(isFocused, channel.stableKey) {
        if (isFocused && channel.contentType == StreamContentType.SERIES && channel.seriesId > 0) {
            delay(160L)
            if (isFocused && XtreamRepository.getCachedSeriesEpisodesNow(channel) == null) {
                runCatching { XtreamRepository.fetchSeriesEpisodes(channel, emptyList()) }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .scale(if (isFocused) 1.04f else 1.0f)
            .clip(RoundedCornerShape(if (compact) 12.dp else 16.dp))
            .background(
                when {
                    isFocused -> BeinPrimaryPurple
                    isLastWatched -> Color(0xFF130926)
                    else -> BeinCard
                }
            )
            .border(
                width = if (isFocused) 2.8.dp else if (isLastWatched) 1.8.dp else 1.dp,
                color = if (isFocused) BeinFocusGlow else if (isLastWatched) BeinHighlightPurple else BeinBorder,
                shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (!it.isFocused) centerLongHandled = false
            }
            .onKeyEvent { ev ->
                val code = ev.nativeKeyEvent.keyCode
                val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER ||
                    code == KeyEvent.KEYCODE_ENTER ||
                    code == KeyEvent.KEYCODE_NUMPAD_ENTER
                val isFavRemoteKey = code == KeyEvent.KEYCODE_MENU ||
                    code == KeyEvent.KEYCODE_BOOKMARK ||
                    code == KeyEvent.KEYCODE_STAR ||
                    code == KeyEvent.KEYCODE_CAPTIONS ||
                    code == KeyEvent.KEYCODE_PROG_YELLOW ||
                    code == KeyEvent.KEYCODE_PROG_RED

                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    if (isCenter) {
                        if (ev.nativeKeyEvent.repeatCount == 0) {
                            centerLongHandled = false
                            true
                        } else if (ev.nativeKeyEvent.repeatCount >= 1 && !centerLongHandled) {
                            centerLongHandled = true
                            SafeClickGuard.runThrottled("FAV_TOGGLE", 240L) { onToggleFavorite() }
                            true
                        } else {
                            true
                        }
                    } else if (isFavRemoteKey && ev.nativeKeyEvent.repeatCount == 0) {
                        SafeClickGuard.runThrottled("FAV_TOGGLE", 240L) { onToggleFavorite() }
                        true
                    } else false
                } else if (ev.nativeKeyEvent.action == KeyEvent.ACTION_UP && isCenter) {
                    if (!centerLongHandled) {
                        SafeClickGuard.runThrottled("UI_BTN_CLICK", 180L) { onClick() }
                    }
                    centerLongHandled = false
                    true
                } else false
            }
            .focusable()
            .combinedClickable(
                onClick = { SafeClickGuard.runThrottled("UI_BTN_CLICK", 180L) { onClick() } },
                onLongClick = { SafeClickGuard.runThrottled("FAV_TOGGLE", 240L) { onToggleFavorite() } }
            )
    ) {
        val isVodPoster = channel.contentType != StreamContentType.LIVE_TV
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    if (isVodPoster) {
                        if (compact) 118.dp else 156.dp
                    } else {
                        if (compact) 88.dp else 118.dp
                    }
                )
                .background(Color(0xFF080311))
                .padding(if (isVodPoster) 4.dp else if (compact) 5.dp else 7.dp)
        ) {
            ChannelAvatar(
                name = channel.name,
                logoUrl = channel.logo,
                modifier = Modifier
                    .fillMaxSize()
                    .align(Alignment.Center),
                cornerRadius = if (compact) 10.dp else 14.dp,
                contentScale = if (isVodPoster) ContentScale.Crop else ContentScale.Fit,
                innerPadding = if (isVodPoster) 0.dp else 4.dp,
                maxDimensionPx = if (isVodPoster) 780 else 480,
                isPoster = isVodPoster
            )

            // Top-Start Badge: Content-Type Badge (in Favorites) OR Rating Badge (in VOD)
            if (showContentTypeBadge) {
                val (typeText, typeBg) = when (channel.contentType) {
                    StreamContentType.LIVE_TV -> "📺 قناة" to Color(0xCC0F766E)
                    StreamContentType.MOVIE -> "🎬 فيلم" to Color(0xCCB45309)
                    StreamContentType.SERIES -> "🎞️ مسلسل" to Color(0xCC1D4ED8)
                }
                Text(
                    text = typeText,
                    color = Color.White,
                    fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(typeBg, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            } else if (channel.contentType != StreamContentType.LIVE_TV && channel.rating.isNotBlank()) {
                Text(
                    text = "★ ${channel.rating}",
                    color = Color.White,
                    fontSize = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .background(BeinPrimaryPurple, RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }

            // Easy-Tap Top-End Favorite Button (Larger & Clearer for Touch + Mouse)
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isFavorite) Color(0xDD881337) else Color(0xBB07050C))
                    .border(
                        width = 1.dp,
                        color = if (isFavorite) Color(0xFFFF4D6D) else Color.White.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { SafeClickGuard.runThrottled("FAV_TOGGLE", 200L) { onToggleFavorite() } }
                    .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 3.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "مفضلة",
                    tint = if (isFavorite) Color(0xFFFF4D6D) else Color.White,
                    modifier = Modifier.size(if (compact) 13.dp else 16.dp)
                )
                if (isFavorite || isFocused) {
                    Text(
                        text = if (isFavorite) "مفضلة" else "+مفضلة",
                        color = Color.White,
                        fontSize = if (compact) 9.sp else 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // Remote Hint Pill when focused on TV
            if (isFocused && (watchProgress == null || watchProgress.positionMs < 5_000L)) {
                Text(
                    text = if (isFavorite) "❤️ مطولاً OK للإزالة" else "⭐ مطولاً OK للمفضلة",
                    color = Color.White,
                    fontSize = if (compact) 9.sp else 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 3.dp)
                        .background(Color(0xCC090414), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }

            // VOD Watch Progress Badge & Bottom Timeline Bar (ما شاهدته + نقطة الاستئناف)
            if (watchProgress != null && watchProgress.positionMs >= 3_000L) {
                Text(
                    text = if (watchProgress.isCompleted) "✅ شوهد" else "⏯ متابعة ${watchProgress.formattedPosition}",
                    color = Color.Black,
                    fontSize = if (compact) 10.sp else 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(bottom = 6.dp, start = 4.dp)
                        .background(
                            if (watchProgress.isCompleted) EmeraldLive else Color(0xFFFBBF24),
                            RoundedCornerShape(5.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x88000000))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(watchProgress.progressFraction.coerceIn(0.06f, 1f))
                            .background(if (watchProgress.isCompleted) EmeraldLive else Color(0xFFFBBF24))
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (compact) 8.dp else 12.dp,
                    vertical = if (compact) 7.dp else 10.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = channel.name,
                color = Color.White,
                fontSize = if (compact) 13.sp else 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 2,
                overflow = TextOverflow.Visible,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = channel.group,
                color = if (isFocused) Color(0xFFF8FAFC) else Color(0xFFCBD5E1),
                fontSize = if (compact) 11.sp else 13.sp,
                maxLines = 1,
                textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis,
                modifier = if (isFocused) Modifier.fillMaxWidth().basicMarquee() else Modifier.fillMaxWidth()
            )

            if (showQuickFavoriteFooter) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isFavorite) Color(0xFF3B0A1E) else Color(0xFF142921))
                        .border(
                            1.dp,
                            if (isFavorite) Color(0xFFFF4D6D) else EmeraldLive,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { SafeClickGuard.runThrottled("FAV_TOGGLE", 200L) { onToggleFavorite() } }
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isFavorite) "❌ إزالة من المفضلة" else "⭐ إضافة للمفضلة",
                        color = if (isFavorite) Color(0xFFFCA5A5) else EmeraldLive,
                        fontSize = if (compact) 11.sp else 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelListRow(
    channel: TvChannel,
    isFavorite: Boolean,
    isLastWatched: Boolean = false,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var centerLongHandled by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .scale(if (isFocused) 1.02f else 1.0f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isFocused -> BeinPrimaryPurple
                    isLastWatched -> Color(0xFF130926)
                    else -> BeinCard
                }
            )
            .border(
                width = if (isFocused) 2.5.dp else if (isLastWatched) 1.5.dp else 1.dp,
                color = if (isFocused) BeinFocusGlow else if (isLastWatched) BeinHighlightPurple else BeinBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged {
                isFocused = it.isFocused
                if (!it.isFocused) centerLongHandled = false
            }
            .onKeyEvent { ev ->
                val code = ev.nativeKeyEvent.keyCode
                val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER ||
                    code == KeyEvent.KEYCODE_ENTER ||
                    code == KeyEvent.KEYCODE_NUMPAD_ENTER
                val isFavRemoteKey = code == KeyEvent.KEYCODE_MENU ||
                    code == KeyEvent.KEYCODE_BOOKMARK ||
                    code == KeyEvent.KEYCODE_STAR ||
                    code == KeyEvent.KEYCODE_CAPTIONS ||
                    code == KeyEvent.KEYCODE_PROG_YELLOW ||
                    code == KeyEvent.KEYCODE_PROG_RED

                if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    if (isCenter) {
                        if (ev.nativeKeyEvent.repeatCount == 0) {
                            centerLongHandled = false
                            true
                        } else if (ev.nativeKeyEvent.repeatCount >= 1 && !centerLongHandled) {
                            centerLongHandled = true
                            SafeClickGuard.runThrottled("FAV_TOGGLE", 240L) { onToggleFavorite() }
                            true
                        } else {
                            true
                        }
                    } else if (isFavRemoteKey && ev.nativeKeyEvent.repeatCount == 0) {
                        SafeClickGuard.runThrottled("FAV_TOGGLE", 240L) { onToggleFavorite() }
                        true
                    } else false
                } else if (ev.nativeKeyEvent.action == KeyEvent.ACTION_UP && isCenter) {
                    if (!centerLongHandled) {
                        SafeClickGuard.runThrottled("UI_BTN_CLICK", 180L) { onClick() }
                    }
                    centerLongHandled = false
                    true
                } else false
            }
            .focusable()
            .combinedClickable(
                onClick = { SafeClickGuard.runThrottled("UI_BTN_CLICK", 180L) { onClick() } },
                onLongClick = { SafeClickGuard.runThrottled("FAV_TOGGLE", 240L) { onToggleFavorite() } }
            )
            .padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 7.dp else 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ChannelAvatar(
            name = channel.name,
            logoUrl = channel.logo,
            modifier = Modifier.size(if (compact) 48.dp else 62.dp),
            cornerRadius = 10.dp,
            innerPadding = 3.dp
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = channel.name,
                color = Color.White,
                fontSize = if (compact) 14.sp else 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = channel.group,
                color = Color(0xFFE2E8F0),
                fontSize = if (compact) 12.sp else 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        IconButton(
            onClick = { SafeClickGuard.runThrottled("FAV_TOGGLE", 200L) { onToggleFavorite() } },
            modifier = Modifier.size(if (compact) 32.dp else 40.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "مفضلة",
                tint = if (isFavorite) Color(0xFFFF4D6D) else Color.White,
                modifier = Modifier.size(if (compact) 18.dp else 22.dp)
            )
        }
    }
}

/**
 * Segmented Favorites & Watch History Hub:
 * Divides Favorites (and History) by Content Type (All / Live TV Channels / Movies / Series)
 * for instant access, search, and easy 1-click management on both TV Remote and Touch.
 */
@Composable
private fun FavoritesAndHistoryView(
    favorites: List<TvChannel>,
    history: List<TvChannel>,
    vodWatchProgressMap: Map<String, VodWatchProgress> = emptyMap(),
    preferences: PlayerPreferences = PlayerPreferences(),
    onUpdatePreferences: (PlayerPreferences) -> Unit = {},
    lang: AppUiLanguage,
    compact: Boolean,
    onPlay: (TvChannel) -> Unit,
    onToggleFavorite: (TvChannel) -> Unit,
    onClearFavoritesByType: (StreamContentType?) -> Unit = {},
    onBackupFavoritesToServer: () -> Unit = {},
    onRestoreFavoritesFromServer: () -> Unit = {},
    onClearHistory: () -> Unit
) {
    var subTab by remember { mutableStateOf(0) } // 0 = Favorites, 1 = History
    var selectedTypeFilter by remember { mutableStateOf<StreamContentType?>(null) } // null = ALL
    var searchFilter by remember { mutableStateOf("") }

    val favSet = remember(favorites) { favorites.map { it.stableKey }.toHashSet() }
    val baseList = if (subTab == 0) favorites else history

    val liveCount = remember(baseList) { baseList.count { it.contentType == StreamContentType.LIVE_TV } }
    val moviesCount = remember(baseList) { baseList.count { it.contentType == StreamContentType.MOVIE } }
    val seriesCount = remember(baseList) { baseList.count { it.contentType == StreamContentType.SERIES } }

    val filteredList = remember(baseList, selectedTypeFilter, searchFilter) {
        val byType = if (selectedTypeFilter == null) {
            baseList
        } else {
            baseList.filter { it.contentType == selectedTypeFilter }
        }
        val q = searchFilter.trim().lowercase(java.util.Locale.ROOT)
        if (q.isEmpty()) byType
        else byType.filter { it.matchesSearch(q) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
    ) {
        // 1. Primary Mode Switcher (Favorites vs Recent History) + Search + Clear Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BeinFocusableButton(
                    text = "⭐ ${AppStrings.tr(lang, "قائمتي المفضلة", "Mes Favoris", "My Favorites")} (${favorites.size})",
                    isPrimary = subTab == 0,
                    compact = compact,
                    onClick = { subTab = 0 }
                )
                BeinFocusableButton(
                    text = "🕒 ${AppStrings.tr(lang, "المشاهدة أخيراً", "Historique", "Recent")} (${history.size})",
                    isPrimary = subTab == 1,
                    compact = compact,
                    onClick = { subTab = 1 }
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val isFavBqHidden = CustomOrderHelper.isFavoritesBouquetHidden(preferences.hiddenBouquets)
                val isMqBqHidden = CustomOrderHelper.isMultiQualityBouquetHidden(preferences.hiddenBouquets)
                BeinFocusableButton(
                    text = if (isFavBqHidden) "👁 إظهار المفضلة بالباقات" else "🚫 إخفاء المفضلة من الباقات",
                    isPrimary = !isFavBqHidden,
                    compact = true,
                    onClick = {
                        onUpdatePreferences(
                            preferences.copy(
                                hiddenBouquets = CustomOrderHelper.toggleFavoritesBouquetHidden(
                                    preferences.hiddenBouquets,
                                    AppStrings.favoritesLabel(lang)
                                )
                            )
                        )
                    }
                )
                BeinFocusableButton(
                    text = if (isMqBqHidden) "👁 إظهار متعدد جودات" else "🚫 إخفاء متعدد جودات",
                    isPrimary = !isMqBqHidden,
                    compact = true,
                    onClick = {
                        onUpdatePreferences(
                            preferences.copy(
                                hiddenBouquets = CustomOrderHelper.toggleMultiQualityBouquetHidden(preferences.hiddenBouquets)
                            )
                        )
                    }
                )
                CompactSearchBar(
                    query = searchFilter,
                    onQueryChange = { searchFilter = it },
                    compact = compact,
                    modifier = Modifier
                        .width(if (compact) 135.dp else 185.dp)
                        .height(if (compact) 28.dp else 34.dp)
                )
                if (subTab == 0 && filteredList.isNotEmpty()) {
                    BeinFocusableButton(
                        text = when (selectedTypeFilter) {
                            StreamContentType.LIVE_TV -> AppStrings.tr(lang, "🗑️ مسح مفضلة القنوات", "🗑️ Vider Chaînes", "🗑️ Clear Live Favs")
                            StreamContentType.MOVIE -> AppStrings.tr(lang, "🗑️ مسح مفضلة الأفلام", "🗑️ Vider Films", "🗑️ Clear Movie Favs")
                            StreamContentType.SERIES -> AppStrings.tr(lang, "🗑️ مسح مفضلة المسلسلات", "🗑️ Vider Séries", "🗑️ Clear Series Favs")
                            null -> AppStrings.tr(lang, "🗑️ مسح كل المفضلة", "🗑️ Vider Favoris", "🗑️ Clear All Favs")
                        },
                        isPrimary = false,
                        compact = true,
                        onClick = { onClearFavoritesByType(selectedTypeFilter) }
                    )
                } else if (subTab == 1 && history.isNotEmpty()) {
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "🗑️ مسح السجل", "🗑️ Effacer", "🗑️ Clear History"),
                        isPrimary = false,
                        compact = true,
                        onClick = onClearHistory
                    )
                }
            }
        }

        // —— نسخة احتياطية للمفضلة على السيرفر (ظاهر دائماً في تبويب المفضلة) ——
        if (subTab == 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(BeinCard)
                    .border(1.5.dp, BeinHighlightPurple.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                    .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 8.dp else 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = AppStrings.tr(
                        lang,
                        "☁️ مفضلة الجهاز على السيرفر",
                        "☁️ Favoris sur le serveur",
                        "☁️ Server favorites backup"
                    ),
                    color = Color.White,
                    fontSize = if (compact) 12.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "☁️ حفظ نسخة احتياطية", "☁️ Sauvegarder", "☁️ Save Backup"),
                    isPrimary = true,
                    compact = compact,
                    onClick = onBackupFavoritesToServer
                )
                BeinFocusableButton(
                    text = AppStrings.tr(lang, "📥 استعادة من قاعدة البيانات", "📥 Restaurer DB", "📥 Restore from DB"),
                    isPrimary = false,
                    compact = compact,
                    onClick = onRestoreFavoritesFromServer
                )
            }
        }

        // 2. Content-Type Segmented Tabs (تقسيم القائمة المفضلة حسب نوع المحتوى: الكل / القنوات المباشرة / الأفلام / المسلسلات)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(BeinCard)
                .border(1.dp, BeinBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BeinFocusableButton(
                text = AppStrings.tr(lang, "🔀 الكل (${baseList.size})", "🔀 Tout (${baseList.size})", "🔀 All (${baseList.size})"),
                isPrimary = selectedTypeFilter == null,
                compact = compact,
                modifier = Modifier.weight(1f),
                onClick = { selectedTypeFilter = null }
            )
            BeinFocusableButton(
                text = AppStrings.tr(lang, "📺 القنوات المباشرة ($liveCount)", "📺 Chaînes TV ($liveCount)", "📺 Live TV ($liveCount)"),
                isPrimary = selectedTypeFilter == StreamContentType.LIVE_TV,
                compact = compact,
                modifier = Modifier.weight(1.15f),
                onClick = { selectedTypeFilter = StreamContentType.LIVE_TV }
            )
            BeinFocusableButton(
                text = AppStrings.tr(lang, "🎬 الأفلام ($moviesCount)", "🎬 Films ($moviesCount)", "🎬 Movies ($moviesCount)"),
                isPrimary = selectedTypeFilter == StreamContentType.MOVIE,
                compact = compact,
                modifier = Modifier.weight(1f),
                onClick = { selectedTypeFilter = StreamContentType.MOVIE }
            )
            BeinFocusableButton(
                text = AppStrings.tr(lang, "🎞️ المسلسلات ($seriesCount)", "🎞️ Séries ($seriesCount)", "🎞️ Series ($seriesCount)"),
                isPrimary = selectedTypeFilter == StreamContentType.SERIES,
                compact = compact,
                modifier = Modifier.weight(1f),
                onClick = { selectedTypeFilter = StreamContentType.SERIES }
            )
        }

        // 3. Quick Remote & Touch Control Guide Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0C221C))
                .border(1.dp, EmeraldLive.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = if (compact) 4.dp else 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = AppStrings.tr(
                    lang,
                    "💡 للإضافة أو الإزالة السريعة من المفضلة في أي قسم: اضغط مطولاً على زر (OK) في الريموت أو اضغط أيقونة القلب (❤️) على أي قناة أو فيلم أو مسلسل",
                    "💡 Astuce : Appuyez longuement sur (OK) ou cliquez sur le cœur (❤️) sur n'importe quelle chaîne, film ou série",
                    "💡 Tip: Long-press (OK) on remote or tap the heart (❤️) on any Channel, Movie, or Series to toggle Favorites"
                ),
                color = EmeraldLive,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 4. Content Grid
        if (filteredList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (subTab == 0) {
                            when (selectedTypeFilter) {
                                StreamContentType.LIVE_TV -> AppStrings.tr(lang, "لا توجد قنوات مباشرة في المفضلة حالياً", "Aucune chaîne favorite", "No favorite live channels yet")
                                StreamContentType.MOVIE -> AppStrings.tr(lang, "لا توجد أفلام في المفضلة حالياً", "Aucun film favori", "No favorite movies yet")
                                StreamContentType.SERIES -> AppStrings.tr(lang, "لا توجد مسلسلات في المفضلة حالياً", "Aucune série favorite", "No favorite series yet")
                                null -> AppStrings.tr(lang, "قائمة المفضلة فارغة حالياً", "Aucun favori pour le moment", "Your favorites list is empty")
                            }
                        } else {
                            AppStrings.tr(lang, "سجل المشاهدة فارغ في هذا القسم", "Historique vide", "Watch history is empty")
                        },
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = if (compact) 15.sp else 17.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "اضغط مطولاً على زر OK في الريموت أو اضغط أيقونة القلب ❤️ على أي قناة أو فيلم أو مسلسل لإضافته هنا فوراً",
                            "Appuyez longuement sur OK ou cliquez sur ❤️ pour ajouter vos chaînes, films et séries ici",
                            "Long-press OK or tap ❤️ on any channel, movie, or series to add it here"
                        ),
                        color = Color(0xFFCBD5E1),
                        fontSize = if (compact) 12.sp else 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(filteredList, key = { idx, ch -> "${idx}_${ch.stableKey}" }) { _, ch ->
                    BeinChannelCard(
                        channel = ch,
                        isFavorite = favSet.contains(ch.stableKey) ||
                            (ch.contentType == StreamContentType.SERIES && ch.seriesId > 0 &&
                                favorites.any { it.contentType == StreamContentType.SERIES && it.seriesId == ch.seriesId && it.playlistId == ch.playlistId }),
                        watchProgress = vodWatchProgressMap[ch.stableKey]
                            ?: (if (ch.seriesId > 0) vodWatchProgressMap["SERIES_ID_${ch.playlistId}_${ch.seriesId}"] else null)
                            ?: vodWatchProgressMap[ch.url.trim()],
                        showContentTypeBadge = true,
                        showQuickFavoriteFooter = (subTab == 0),
                        compact = compact,
                        onClick = { onPlay(ch) },
                        onToggleFavorite = { onToggleFavorite(ch) }
                    )
                }
            }
        }
    }
}

/**
 * Restored Full Subscription Validity & Screen Activation Status View!
 */
@Composable
private fun ScreenActivationStatusView(
    deviceId: String,
    deviceName: String,
    subscription: SubscriptionInfo?,
    lang: AppUiLanguage,
    isLoading: Boolean,
    compact: Boolean,
    onVerifyNow: () -> Unit,
    onRedeemCode: (String) -> Unit
) {
    val context = LocalContext.current
    var directCode by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (compact) 10.dp else 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 660.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(BeinCard)
                    .border(2.dp, BeinHighlightPurple, RoundedCornerShape(18.dp))
                    .padding(if (compact) 14.dp else 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = AppStrings.tr(lang, "حالة صلاحية المشغل ورمز الشاشة", "Validité du Lecteur & Code Écran", "Player Validity & Screen Code"),
                    color = Color.White,
                    fontSize = if (compact) 17.sp else 19.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(8.dp))

                // Subscription Duration & Expiry Details Card
                if (subscription != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (subscription.active) Color(0xFF0C261E) else Color(0xFF2B1118))
                            .border(
                                1.5.dp,
                                if (subscription.active) EmeraldLive else CrimsonAlert,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (subscription.active) "✅ حالة المشغل: نشط ومفعّل" else "⛔ حالة المشغل: منتهي الصلاحية",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = if (compact) 14.sp else 16.sp
                            )
                            if (subscription.expireDate.isNotBlank()) {
                                Text(
                                    text = "تاريخ انتهاء الصلاحية: ${subscription.expireDate}",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = if (compact) 13.sp else 15.sp
                                )
                            }
                        }
                        Text(
                            text = "${subscription.daysLeft} يوم متبقٍ",
                            color = if (subscription.active) EmeraldLive else CrimsonAlert,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = if (compact) 16.sp else 17.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                Text(
                    text = "📞 للتجديد والتفعيل تواصل مع علي سات : $ALI_SAT_PHONE_NUMBER",
                    color = Color(0xFFE2E8F0),
                    fontSize = if (compact) 15.sp else 18.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BeinDarkBg)
                        .border(2.dp, BeinHighlightPurple, RoundedCornerShape(12.dp))
                        .clickable { copyDeviceCodeToClipboard(context, deviceId) }
                        .padding(vertical = if (compact) 10.dp else 16.dp, horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = deviceId,
                            color = Color.White,
                            fontSize = if (compact) 20.sp else 26.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "نسخ رمز الشاشة 📋", "Copier le code 📋", "Copy Screen Code 📋"),
                        isPrimary = true,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = { copyDeviceCodeToClipboard(context, deviceId) }
                    )
                    BeinFocusableButton(
                        text = if (isLoading) "..." else AppStrings.tr(lang, "تحديث الصلاحية 🔄", "Actualiser 🔄", "Refresh Validity 🔄"),
                        isPrimary = false,
                        compact = compact,
                        modifier = Modifier.weight(1f),
                        onClick = onVerifyNow
                    )
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 660.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(BeinCard)
                    .border(1.8.dp, BeinHighlightPurple, RoundedCornerShape(16.dp))
                    .padding(if (compact) 12.dp else 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = AppStrings.tr(
                        lang,
                        "💳 تفعيل وتجديد الاشتراك برمز القسيمة المباشر",
                        "💳 Activer ou renouveler avec un code de recharge",
                        "💳 Activate or Renew with Voucher Code"
                    ),
                    color = Color.White,
                    fontSize = if (compact) 15.sp else 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = AppStrings.tr(
                        lang,
                        "أدخل رمز التفعيل المكون من 8 أحرف وأرقام (مثال: A1B2-C3D4) ثم اضغط «تفعيل الآن ✓»",
                        "Entrez le code de 8 caractères (ex: A1B2-C3D4) puis cliquez sur Activer",
                        "Enter your 8-character voucher code (e.g. A1B2-C3D4) then click Activate"
                    ),
                    color = Color(0xFFE2E8F0),
                    fontSize = if (compact) 12.sp else 14.sp,
                    textAlign = TextAlign.Center
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = directCode,
                        onValueChange = { directCode = formatVoucherCodeInput(it) },
                        placeholder = {
                            Text(
                                "A1B2-C3D4",
                                color = Color(0xFFBFA7F2),
                                fontSize = if (compact) 14.sp else 16.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = if (compact) 17.sp else 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 2.sp,
                            textAlign = TextAlign.Center
                        ),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    BeinFocusableButton(
                        text = AppStrings.tr(lang, "📋 لصق", "📋 Coller", "📋 Paste"),
                        isPrimary = false,
                        compact = compact,
                        onClick = {
                            val pasted = pasteVoucherFromClipboard(context)
                            if (pasted.isNotBlank()) directCode = pasted
                        }
                    )
                    BeinFocusableButton(
                        text = if (isLoading) "⏳ جارٍ التفعيل..." else AppStrings.tr(lang, "تفعيل الآن ✓", "Activer ✓", "Activate ✓"),
                        isPrimary = true,
                        compact = compact,
                        onClick = { onRedeemCode(directCode) }
                    )
                }
            }
        }
    }
}

/**
 * Sleek, Responsive Quick Add Account Popup Modal ("+ إضافة حساب" from Main Interface):
 * - Automatic Mode (Default): Just 2 fields (Username & Password) to auto-discover the server,
 *   save it in both Server DB and Local SQLite DB, and sync immediately!
 * - Manual Mode: Allows entering Playlist Name, Server Host / M3U URL, Username, and Password
 *   for direct immediate registration in both databases without blocking on server check.
 */
@Composable
internal fun QuickAddXtreamAccountModal(
    deviceId: String,
    existingPlaylistsCount: Int,
    lang: AppUiLanguage,
    compact: Boolean,
    onAddAccount: (XtreamAccountConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var useAutoMode by remember { mutableStateOf(true) }
    var isDiscovering by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var feedbackError by remember { mutableStateOf("") }

    var serverName by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val nameFocus = remember { FocusRequester() }
    val urlFocus = remember { FocusRequester() }
    val userFocus = remember { FocusRequester() }
    val passFocus = remember { FocusRequester() }
    val submitFocus = remember { FocusRequester() }

    LaunchedEffect(useAutoMode) {
        feedbackError = ""
        delay(80L)
        runCatching {
            if (useAutoMode) userFocus.requestFocus() else urlFocus.requestFocus()
        }
    }

    Dialog(onDismissRequest = { if (!isDiscovering) onDismiss() }) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = if (compact) 520.dp else 580.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF161622), Color(0xFF0E0E15), Color(0xFF08080C))
                    )
                )
                .border(1.8.dp, Color(0xFF2E2E3E), RoundedCornerShape(24.dp))
                .padding(horizontal = if (compact) 16.dp else 24.dp, vertical = if (compact) 14.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)
        ) {
            // 1. Clean Minimalist Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (compact) 32.dp else 38.dp)
                            .clip(CircleShape)
                            .background(EmeraldLive.copy(alpha = 0.16f))
                            .border(1.5.dp, EmeraldLive, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = EmeraldLive,
                            modifier = Modifier.size(if (compact) 18.dp else 22.dp)
                        )
                    }
                    Text(
                        text = AppStrings.tr(
                            lang,
                            "إضافة اشتراك جديد",
                            "Ajouter un Abonnement",
                            "Add New Subscription"
                        ),
                        color = Color.White,
                        fontSize = if (compact) 16.sp else 19.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                IconButton(
                    onClick = { if (!isDiscovering) onDismiss() },
                    modifier = Modifier
                        .size(if (compact) 30.dp else 34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF22222E))
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "إغلاق",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // 2. Sleek Segmented Mode Switcher: [⚡ إدخال تلقائي] | [🌐 إدخال يدوي]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0A0A0F))
                    .border(1.dp, Color(0xFF262634), RoundedCornerShape(14.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                var autoTabFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(if (compact) 36.dp else 42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                autoTabFocused -> Color.White
                                useAutoMode -> EmeraldLive
                                else -> Color.Transparent
                            }
                        )
                        .onFocusChanged { autoTabFocused = it.isFocused }
                        .onKeyEvent { ev ->
                            if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                ev.nativeKeyEvent.repeatCount == 0 &&
                                (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                    ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                            ) {
                                useAutoMode = true
                                feedbackError = ""
                                true
                            } else false
                        }
                        .focusable()
                        .clickable {
                            useAutoMode = true
                            feedbackError = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = AppStrings.tr(lang, "⚡ إدخال تلقائي", "⚡ Mode Automatique", "⚡ Auto Input"),
                        color = if (autoTabFocused || useAutoMode) Color.Black else Color(0xFFCBD5E1),
                        fontSize = if (compact) 13.sp else 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                var manualTabFocused by remember { mutableStateOf(false) }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(if (compact) 36.dp else 42.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                manualTabFocused -> Color.White
                                !useAutoMode -> EmeraldLive
                                else -> Color.Transparent
                            }
                        )
                        .onFocusChanged { manualTabFocused = it.isFocused }
                        .onKeyEvent { ev ->
                            if (ev.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                                ev.nativeKeyEvent.repeatCount == 0 &&
                                (ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                    ev.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER)
                            ) {
                                useAutoMode = false
                                feedbackError = ""
                                true
                            } else false
                        }
                        .focusable()
                        .clickable {
                            useAutoMode = false
                            feedbackError = ""
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = AppStrings.tr(lang, "🌐 إدخال يدوي", "🌐 Mode Manuel", "🌐 Manual Input"),
                        color = if (manualTabFocused || !useAutoMode) Color.Black else Color(0xFFCBD5E1),
                        fontSize = if (compact) 13.sp else 15.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // 3. Manual Mode Fields (Server Host / M3U + Optional Name)
            if (!useAutoMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { inputVal ->
                            feedbackError = ""
                            val split = SmartM3uParser.tryExtractXtreamCredentials(inputVal)
                            if (split != null) {
                                serverUrl = split.serverUrl
                                username = split.username
                                password = split.password
                            } else {
                                serverUrl = inputVal
                            }
                        },
                        label = {
                            Text(
                                AppStrings.tr(lang, "رابط السيرفر (Host) أو M3U", "URL Serveur ou M3U", "Server URL or M3U"),
                                fontSize = if (compact) 11.sp else 13.sp
                            )
                        },
                        placeholder = {
                            Text("http://server.com:8080", color = Color.Gray, fontSize = 12.sp)
                        },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = if (compact) 14.sp else 15.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { runCatching { nameFocus.requestFocus() } }),
                        modifier = Modifier
                            .weight(1.3f)
                            .focusRequester(urlFocus),
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = serverName,
                        onValueChange = { serverName = it },
                        label = {
                            Text(
                                AppStrings.tr(lang, "اسم القائمة (اختياري)", "Nom (Optionnel)", "Name (Optional)"),
                                fontSize = if (compact) 11.sp else 13.sp
                            )
                        },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = if (compact) 14.sp else 15.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { runCatching { userFocus.requestFocus() } }),
                        modifier = Modifier
                            .weight(0.7f)
                            .focusRequester(nameFocus),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            // 4. Clean Username & Password Fields
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { inputVal ->
                        feedbackError = ""
                        val split = SmartM3uParser.tryExtractXtreamCredentials(inputVal)
                        if (split != null) {
                            serverUrl = split.serverUrl
                            username = split.username
                            password = split.password
                        } else {
                            username = inputVal
                        }
                    },
                    label = {
                        Text(
                            AppStrings.tr(lang, "اسم المستخدم (Username)", "Utilisateur (Username)", "Username"),
                            fontSize = if (compact) 12.sp else 13.sp
                        )
                    },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = if (compact) 15.sp else 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { runCatching { passFocus.requestFocus() } }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(userFocus),
                    shape = RoundedCornerShape(12.dp)
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        feedbackError = ""
                    },
                    label = {
                        Text(
                            AppStrings.tr(lang, "كلمة المرور (Password)", "Mot de passe (Password)", "Password"),
                            fontSize = if (compact) 12.sp else 13.sp
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    visualTransformation = if (showPassword) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        androidx.compose.ui.text.input.PasswordVisualTransformation()
                    },
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = if (compact) 15.sp else 16.sp,
                        fontWeight = FontWeight.ExtraBold
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { runCatching { submitFocus.requestFocus() } }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(passFocus),
                    shape = RoundedCornerShape(12.dp)
                )
            }

            if (feedbackError.isNotBlank()) {
                Text(
                    text = feedbackError,
                    color = Color(0xFFFCA5A5),
                    fontSize = if (compact) 12.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 5. Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BeinFocusableButton(
                    text = when {
                        isDiscovering -> AppStrings.tr(
                            lang,
                            "⏳ جارٍ الاتصال وتفعيل الحساب...",
                            "⏳ Connexion en cours...",
                            "⏳ Connecting & Activating..."
                        )
                        useAutoMode -> AppStrings.tr(
                            lang,
                            "🚀 دخول وتفعيل تلقائي",
                            "🚀 Connexion Automatique",
                            "🚀 Auto Connect & Activate"
                        )
                        else -> AppStrings.tr(
                            lang,
                            "💾 حفظ وتشغيل القائمة",
                            "💾 Enregistrer & Ouvrir",
                            "💾 Save & Open Playlist"
                        )
                    },
                    isPrimary = true,
                    compact = compact,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(submitFocus),
                    onClick = {
                        if (isDiscovering) return@BeinFocusableButton
                        val cleanUser = username.trim()
                        val cleanPass = password.trim()
                        val cleanUrl = serverUrl.trim()
                        val cleanName = serverName.trim()

                        if (useAutoMode) {
                            if (cleanUser.isBlank() || cleanPass.isBlank()) {
                                feedbackError = "⚠️ يرجى إدخال اسم المستخدم وكلمة المرور"
                                return@BeinFocusableButton
                            }
                            isDiscovering = true
                            feedbackError = ""
                            scope.launch {
                                val discovered = ChannelRepository.autoDiscoverXtreamHostViaPortal(
                                    deviceId = deviceId,
                                    username = cleanUser,
                                    password = cleanPass
                                )
                                isDiscovering = false
                                if (discovered != null) {
                                    val (foundUrl, foundName) = discovered
                                    val newCfg = XtreamAccountConfig(
                                        id = UUID.randomUUID().toString().take(8),
                                        serverName = foundName.ifBlank { "حساب #${existingPlaylistsCount + 1}" },
                                        serverUrl = foundUrl,
                                        username = cleanUser,
                                        password = cleanPass,
                                        enabled = true,
                                        orderIndex = existingPlaylistsCount,
                                        statusNote = "تم التفعيل التلقائي ✓"
                                    )
                                    Toast.makeText(
                                        context,
                                        "✅ تم الاتصال بالسيرفر ($foundName) بنجاح!",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    onAddAccount(newCfg)
                                    onDismiss()
                                } else {
                                    feedbackError = "⚠️ تعذّر العثور على السيرفر تلقائياً — تأكد من البيانات أو اختر «🌐 إدخال يدوي»"
                                }
                            }
                        } else {
                            if (cleanUrl.isBlank() && (cleanUser.isBlank() || cleanPass.isBlank())) {
                                feedbackError = "⚠️ يرجى إدخال رابط السيرفر مع اسم المستخدم وكلمة المرور"
                                return@BeinFocusableButton
                            }
                            val resolvedUrl = if (cleanUrl.isNotBlank() && !cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
                                KnownIptvServersCatalog.resolveHostByServerName(cleanUrl).ifBlank { "http://$cleanUrl" }
                            } else if (cleanUrl.isBlank() && cleanName.isNotBlank()) {
                                KnownIptvServersCatalog.resolveHostByServerName(cleanName)
                            } else {
                                cleanUrl
                            }
                            if (resolvedUrl.isBlank()) {
                                feedbackError = "⚠️ يرجى إدخال رابط السيرفر (Host URL)"
                                return@BeinFocusableButton
                            }
                            val autoTitle = cleanName.ifBlank {
                                KnownIptvServersCatalog.detectServerNameByHost(resolvedUrl)
                                    .ifBlank { "حساب #${existingPlaylistsCount + 1}" }
                            }
                            val newCfg = XtreamAccountConfig(
                                id = UUID.randomUUID().toString().take(8),
                                serverName = autoTitle,
                                serverUrl = resolvedUrl,
                                username = cleanUser,
                                password = cleanPass,
                                enabled = true,
                                orderIndex = existingPlaylistsCount,
                                statusNote = "متصل ✓"
                            )
                            onAddAccount(newCfg)
                            onDismiss()
                        }
                    }
                )

                BeinFocusableButton(
                    text = AppStrings.tr(lang, "إلغاء", "Annuler", "Cancel"),
                    isPrimary = false,
                    compact = compact,
                    onClick = { if (!isDiscovering) onDismiss() }
                )
            }
        }
    }
}

/**
 * Interactive VPN Security & Control Modal:
 * - Allows turning VPN ON immediately (default fast encrypted mode)
 * - Allows pausing VPN temporarily for the current session (auto-starts again on next app launch)
 * - Allows disabling VPN permanently (until re-enabled)
 * - Automatically hides the Main Server Playlist ("PORTAL_MAIN") whenever VPN is stopped to prevent stream URL sniffing!
 */
@Composable
internal fun VpnControlModal(
    vpnStatus: CloudflareTunnelStatus,
    vpnPausedTemporarily: Boolean,
    vpnPermanentDisable: Boolean,
    vpnAutoStartOnLaunch: Boolean,
    lang: AppUiLanguage,
    compact: Boolean,
    onEnableVpnNow: () -> Unit,
    onPauseVpnTemporarily: () -> Unit,
    onDisableVpnPermanently: () -> Unit,
    onToggleAutoStartOnLaunch: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val isVpnActive = vpnStatus.connected && !vpnPausedTemporarily && !vpnPermanentDisable
    val primaryFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { primaryFocus.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 340.dp, max = if (compact) 560.dp else 640.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF14141D), BeinSurface, BeinDarkBg)
                    )
                )
                .border(
                    2.2.dp,
                    if (isVpnActive) EmeraldLive else Color(0xFFF59E0B),
                    RoundedCornerShape(22.dp)
                )
                .padding(if (compact) 14.dp else 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = if (isVpnActive) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (isVpnActive) EmeraldLive else Color(0xFFF59E0B),
                        modifier = Modifier.size(if (compact) 24.dp else 28.dp)
                    )
                    Column {
                        Text(
                            text = AppStrings.tr(
                                lang,
                                "🛡️ إدارة نفق الحماية والتشفير السريع (VPN)",
                                "🛡️ Gestion du Tunnel VPN Sécurisé",
                                "🛡️ Fast Encrypted VPN Tunnel Control"
                            ),
                            color = Color.White,
                            fontSize = if (compact) 15.sp else 18.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = if (isVpnActive) {
                                "الحالة: متصّل ومشفّر بالكامل ✓ (القائمة الرئيسية مفعّلة ومحمية)"
                            } else if (vpnPausedTemporarily) {
                                "الحالة: متوقف مؤقتاً لهذه الجلسة (تم إخفاء القائمة الرئيسية لحماية الروابط)"
                            } else {
                                "الحالة: متوقف دائماً (القائمة الرئيسية مخفية تلقائياً لمنع سحب الروابط)"
                            },
                            color = if (isVpnActive) EmeraldLive else Color(0xFFFDE68A),
                            fontSize = if (compact) 11.sp else 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = Color.White)
                }
            }

            // Security anti-sniffing notice box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isVpnActive) Color(0xFF0C241B) else Color(0xFF281909))
                    .border(
                        1.dp,
                        if (isVpnActive) EmeraldLive.copy(alpha = 0.6f) else Color(0xFFF59E0B).copy(alpha = 0.7f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(10.dp)
            ) {
                Text(
                    text = AppStrings.tr(
                        lang,
                        "🔒 حماية الروابط: تمر جميع حركة البيانات عبر نفق VPN المشفّر السريع لمنع التقطيع ومنع اعتراض البيانات. عند إيقاف الـ VPN تختفي «القائمة الرئيسية» تلقائياً من السيرفرات وتعود للظهور فور تشغيل الـ VPN.",
                        "🔒 Protection des flux : tout le trafic passe par le tunnel chiffré. Lorsque le VPN est arrêté, la liste principale est automatiquement masquée.",
                        "🔒 Anti-Sniffing Protection: All traffic routes through the fast encrypted tunnel. When VPN is off, the Main Playlist automatically hides and reappears when VPN is re-enabled."
                    ),
                    color = Color(0xFFE2E8F0),
                    fontSize = if (compact) 11.sp else 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 1. Enable / Reconnect Fast VPN Now
            BeinFocusableButton(
                text = AppStrings.tr(
                    lang,
                    "🔒 تشغيل وتفعيل الـ VPN السريع الآن (إظهار القائمة الرئيسية)",
                    "🔒 Activer le VPN Rapide Maintenant (Afficher Liste Principale)",
                    "🔒 Enable Fast VPN Now (Show Main Playlist)"
                ),
                isPrimary = isVpnActive,
                compact = compact,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(primaryFocus),
                onClick = {
                    onEnableVpnNow()
                    onDismiss()
                }
            )

            // 2. Temporary Pause (Current Session Only - Auto-Starts on Next Launch)
            BeinFocusableButton(
                text = AppStrings.tr(
                    lang,
                    "⏸️ إيقاف الـ VPN مؤقتاً (لهذه الجلسة فقط — يشتغل تلقائياً عند فتح التطبيق)",
                    "⏸️ Pause VPN Temporaire (Cette session — démarrage auto au lancement)",
                    "⏸️ Pause VPN Temporarily (This session — auto-starts on next launch)"
                ),
                isPrimary = vpnPausedTemporarily && !isVpnActive,
                compact = compact,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onPauseVpnTemporarily()
                    onDismiss()
                }
            )

            // 3. Permanent Stop (Hides Main Playlist Until Re-enabled)
            BeinFocusableButton(
                text = AppStrings.tr(
                    lang,
                    "🛑 إيقاف الـ VPN دائماً (مع إخفاء القائمة الرئيسية لحماية الروابط)",
                    "🛑 Désactiver le VPN Toujours (Masquer la liste principale)",
                    "🛑 Disable VPN Permanently (Hide Main Playlist)"
                ),
                isPrimary = vpnPermanentDisable && !isVpnActive,
                compact = compact,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onDisableVpnPermanently()
                    onDismiss()
                }
            )

            // 4. Always Auto-Start with VPN Toggle
            BeinFocusableButton(
                text = if (vpnAutoStartOnLaunch) {
                    AppStrings.tr(
                        lang,
                        "🚀 البدء التلقائي الدائم مع الـ VPN عند فتح التطبيق: مفعّل ✓",
                        "🚀 Démarrage Automatique avec VPN au lancement : Activé ✓",
                        "🚀 Always Auto-Start with VPN on App Launch: Enabled ✓"
                    )
                } else {
                    AppStrings.tr(
                        lang,
                        "🚀 البدء التلقائي مع الـ VPN عند فتح التطبيق: متوقف (اضغط للتفعيل)",
                        "🚀 Démarrage Automatique avec VPN : Désactivé",
                        "🚀 Auto-Start with VPN on Launch: Disabled (Click to Enable)"
                    )
                },
                isPrimary = false,
                compact = compact,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onToggleAutoStartOnLaunch(!vpnAutoStartOnLaunch)
                }
            )
        }
    }
}
