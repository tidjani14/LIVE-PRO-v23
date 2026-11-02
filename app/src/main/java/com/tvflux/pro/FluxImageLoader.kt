package com.tvflux.pro

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val SharedAvatarBgBrush = Brush.linearGradient(
    listOf(Color(0xFF14141D), Color(0xFF0A0A0F))
)

/**
 * Ultra-HD Crystal-Clear Thumbnail & Poster Engine (v24.0):
 * 1. Decodes in full 32-bit ARGB_8888 (true color + full alpha channel) instead of blurry RGB_565.
 * 2. Renders with FilterQuality.High bicubic/bilinear sampling for razor-sharp channel logos & VOD posters.
 * 3. Automatically upgrades TMDB & IPTV low-res thumbnail URLs (w92/w154/w185/w300 -> w780 HD).
 * 4. Purges legacy low-res cache folder ("flux_thumbs") automatically and stores HD assets in "flux_thumbs_uhd_v24".
 */
object FluxBitmapCache {
    private const val UHD_CACHE_DIR_NAME = "flux_thumbs_uhd_v24"
    private const val LEGACY_CACHE_DIR_NAME = "flux_thumbs"
    private val legacyPurged = AtomicBoolean(false)

    // Solution 4.1: Safe 1/8 Heap Allocation (8MB..48MB) + Critical Memory GC Guard
    private val maxRamKb: Int = run {
        val maxMem = Runtime.getRuntime().maxMemory() / 1024L
        val eighth = (maxMem / 8L).toInt()
        eighth.coerceIn(8 * 1024, 48 * 1024)
    }

    private val evictionCounter = AtomicInteger(0)

    private val memoryCache = object : LruCache<String, ImageBitmap>(maxRamKb) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return ((value.width * value.height * 4) / 1024).coerceAtLeast(1)
        }

        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: ImageBitmap,
            newValue: ImageBitmap?
        ) {
            super.entryRemoved(evicted, key, oldValue, newValue)
            if (evicted && evictionCounter.incrementAndGet() % 50 == 0) {
                val rt = Runtime.getRuntime()
                if ((rt.totalMemory() - rt.freeMemory()) > (rt.maxMemory() * 0.85)) {
                    System.gc()
                }
            }
        }
    }

    private val keyCache = ConcurrentHashMap<String, String>(4096)
    private val downloadSemaphore = Semaphore(10)
    private val writeCounter = AtomicInteger(0)

    private val tmdbLowResRegex = Regex("/t/p/(?:w92|w154|w185|w200|w300|w342|w400|w500)/", RegexOption.IGNORE_CASE)

    @Volatile
    var maxDiskCacheBytes: Long = ThumbnailCacheLimitOption.MB_128.maxBytes

    /**
     * Upgrades known low-resolution poster/logo URLs (such as TMDB w185/w300 thumbnails)
     * to high-definition w780 URLs so movie & series covers are crystal clear.
     */
    fun upgradeImageUrlToHighRes(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""
        var upgraded = trimmed
        if (upgraded.contains("image.tmdb.org/t/p/", ignoreCase = true)) {
            upgraded = tmdbLowResRegex.replace(upgraded, "/t/p/w780/")
        }
        return upgraded
    }

    private fun purgeLegacyBlurryCacheOnce(context: Context) {
        if (legacyPurged.compareAndSet(false, true)) {
            runCatching {
                val oldDir = File(context.cacheDir, LEGACY_CACHE_DIR_NAME)
                if (oldDir.exists()) {
                    oldDir.deleteRecursively()
                }
            }
        }
    }

    fun get(url: String): ImageBitmap? {
        if (url.isBlank()) return null
        val upUrl = upgradeImageUrlToHighRes(url)
        return synchronized(memoryCache) {
            memoryCache.get(upUrl) ?: memoryCache.get(url)
        }
    }

    fun put(url: String, bmp: ImageBitmap) = synchronized(memoryCache) {
        memoryCache.put(url, bmp)
    }

    fun getCacheSizeMb(context: Context): String {
        purgeLegacyBlurryCacheOnce(context)
        val thumbDir = File(context.cacheDir, UHD_CACHE_DIR_NAME)
        val bytes = if (thumbDir.exists()) {
            thumbDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else 0L
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    fun enforceDiskQuota(context: Context, limitBytes: Long = maxDiskCacheBytes) {
        runCatching {
            val thumbDir = File(context.cacheDir, UHD_CACHE_DIR_NAME)
            if (!thumbDir.exists()) return
            val files = thumbDir.listFiles()?.filter { it.isFile } ?: return
            var totalBytes = files.sumOf { it.length() }
            if (totalBytes <= limitBytes) return

            val targetBytes = (limitBytes * 85L) / 100L
            val sortedOldest = files.sortedBy { it.lastModified() }
            for (f in sortedOldest) {
                val len = f.length()
                if (f.delete()) {
                    totalBytes -= len
                    if (totalBytes <= targetBytes) break
                }
            }
        }
    }

    fun clear(context: Context) {
        synchronized(memoryCache) { memoryCache.evictAll() }
        keyCache.clear()
        runCatching {
            File(context.cacheDir, LEGACY_CACHE_DIR_NAME).deleteRecursively()
            File(context.cacheDir, UHD_CACHE_DIR_NAME).deleteRecursively()
        }
    }

    suspend fun load(
        context: Context,
        url: String,
        maxDimensionPx: Int = 640,
        isPoster: Boolean = false
    ): ImageBitmap? {
        val cleanUrl = upgradeImageUrlToHighRes(url)
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) return null
        get(cleanUrl)?.let { return it }

        val targetMaxPx = if (DevicePerformanceDetector.isLowRamOrWeakDevice(context)) {
            if (isPoster) maxDimensionPx.coerceIn(480, 640) else maxDimensionPx.coerceIn(360, 480)
        } else {
            if (isPoster) maxDimensionPx.coerceIn(640, 900) else maxDimensionPx.coerceIn(440, 640)
        }

        return withContext(Dispatchers.IO) {
            purgeLegacyBlurryCacheOnce(context)
            downloadSemaphore.withPermit {
                get(cleanUrl)?.let { return@withPermit it }
                try {
                    val cacheDir = File(context.cacheDir, UHD_CACHE_DIR_NAME).apply { mkdirs() }
                    val diskFile = File(cacheDir, diskKeyForUrl("${cleanUrl}_${targetMaxPx}"))

                    if (diskFile.exists() && diskFile.length() > 64L) {
                        val cachedBytes = diskFile.readBytes()
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inScaled = false
                            inDither = false
                        }
                        val bmp = BitmapFactory.decodeByteArray(cachedBytes, 0, cachedBytes.size, opts)
                        if (bmp != null) {
                            val imgBmp = bmp.asImageBitmap()
                            put(cleanUrl, imgBmp)
                            return@withPermit imgBmp
                        }
                    }

                    // Try upgraded HD URL first; if server returns 404 on upgraded TMDB size, fall back to original URL
                    val rawBytes: ByteArray = fetchImageBytes(cleanUrl)
                        ?: (if (cleanUrl != url.trim()) fetchImageBytes(url.trim()) else null)
                        ?: return@withPermit null

                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withPermit null

                    var sampleSize = 1
                    while ((bounds.outWidth / (sampleSize * 2)) >= targetMaxPx &&
                        (bounds.outHeight / (sampleSize * 2)) >= targetMaxPx
                    ) {
                        sampleSize *= 2
                    }

                    val decodeOpts = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                        inScaled = false
                        inDither = false
                    }
                    val decodedBmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                        ?: return@withPermit null

                    val maxSide = maxOf(decodedBmp.width, decodedBmp.height)
                    val finalBmp = if (maxSide > targetMaxPx && targetMaxPx > 0) {
                        val ratio = targetMaxPx.toFloat() / maxSide.toFloat()
                        val targetW = (decodedBmp.width * ratio).toInt().coerceAtLeast(1)
                        val targetH = (decodedBmp.height * ratio).toInt().coerceAtLeast(1)
                        val scaled = Bitmap.createScaledBitmap(decodedBmp, targetW, targetH, true)
                        if (scaled != decodedBmp) {
                            runCatching { decodedBmp.recycle() }
                        }
                        scaled
                    } else {
                        decodedBmp
                    }

                    runCatching {
                        val bos = ByteArrayOutputStream(32768)
                        if (!isPoster || finalBmp.hasAlpha()) {
                            // Lossless PNG for channel logos & transparent icons so edges are 100% razor-sharp!
                            finalBmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                        } else {
                            @Suppress("DEPRECATION")
                            finalBmp.compress(Bitmap.CompressFormat.WEBP, 96, bos)
                        }
                        val thumbBytes = bos.toByteArray()
                        if (thumbBytes.size > 64) {
                            diskFile.writeBytes(thumbBytes)
                        }
                        if (writeCounter.incrementAndGet() % 35 == 0) {
                            enforceDiskQuota(context, maxDiskCacheBytes)
                        }
                    }

                    val imgBmp = finalBmp.asImageBitmap()
                    put(cleanUrl, imgBmp)
                    imgBmp
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun fetchImageBytes(targetUrl: String): ByteArray? {
        return runCatching {
            val req = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; LIVE-PRO/24.0 UHD)")
                .header("Accept", "image/webp,image/png,image/jpeg,image/*,*/*;q=0.8")
                .build()
            ChannelRepository.httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val b = resp.body?.bytes() ?: return@use null
                if (b.size < 64 || b.size > (8 * 1024 * 1024)) return@use null
                b
            }
        }.getOrNull()
    }

    private fun diskKeyForUrl(input: String): String {
        keyCache[input]?.let { return it }
        val computed = runCatching {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            buildString(32) {
                for (i in 0 until 16) {
                    val b = digest[i].toInt() and 0xFF
                    append("0123456789abcdef"[b ushr 4])
                    append("0123456789abcdef"[b and 0x0F])
                }
            }
        }.getOrElse { input.hashCode().toUInt().toString(16) }
        if (keyCache.size > 8192) keyCache.clear()
        keyCache[input] = computed
        return computed
    }
}

@Composable
fun BrandLogoBadge(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp
) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf(FluxBitmapCache.get(APP_LOGO_URL)) }

    LaunchedEffect(Unit) {
        if (bitmap == null) {
            bitmap = FluxBitmapCache.load(context, APP_LOGO_URL, maxDimensionPx = 480, isPoster = false)
        }
    }

    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(Color(0xFF120A1E))
            .border(1.5.dp, BeinHighlightPurple.copy(alpha = 0.85f), shape),
        contentAlignment = Alignment.Center
    ) {
        val currentBmp = bitmap
        if (currentBmp != null) {
            Image(
                bitmap = currentBmp,
                contentDescription = APP_BRAND_NAME,
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Image(
                painter = painterResource(id = R.drawable.app_logo),
                contentDescription = APP_BRAND_NAME,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun ChannelAvatar(
    name: String,
    logoUrl: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    contentScale: ContentScale = ContentScale.Fit,
    innerPadding: Dp = 3.dp,
    maxDimensionPx: Int = 520,
    isPoster: Boolean = false
) {
    val context = LocalContext.current
    var bitmap by remember(logoUrl) {
        mutableStateOf(logoUrl?.let { FluxBitmapCache.get(it) })
    }

    LaunchedEffect(logoUrl, maxDimensionPx, isPoster) {
        if (bitmap == null && !logoUrl.isNullOrBlank()) {
            // 45ms scroll-settle debounce: cancels off-screen loads during rapid scrolling while loading visible HD images fast
            delay(45L)
            bitmap = FluxBitmapCache.load(
                context = context,
                url = logoUrl,
                maxDimensionPx = maxDimensionPx,
                isPoster = isPoster
            )
        }
    }

    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .clip(shape)
            .background(SharedAvatarBgBrush),
        contentAlignment = Alignment.Center
    ) {
        val currentBmp = bitmap
        if (currentBmp != null) {
            Image(
                bitmap = currentBmp,
                contentDescription = name,
                contentScale = contentScale,
                filterQuality = FilterQuality.High,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            val initials = remember(name) {
                val trimmed = name.trim()
                if (trimmed.length >= 2) trimmed.take(2).uppercase() else if (trimmed.isNotEmpty()) trimmed.uppercase() else "TV"
            }
            Text(
                text = initials,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}
