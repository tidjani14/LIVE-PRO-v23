package com.tvflux.pro

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated Architecture ViewModel & Request Coordinator (Items 1, 3, 4):
 * - Survives Android configuration changes (rotation, window resize, multi-window, backgrounding).
 * - Uses atomic request sequence tokens (AtomicLong) to prevent stale network responses from
 *   overwriting newer channel or playlist selections (Race Condition Guard).
 */
class LiveProViewModel : ViewModel() {
    private val syncRequestSequence = AtomicLong(0L)
    private val playbackRequestSequence = AtomicLong(0L)

    var activeTabState by mutableStateOf(MainTab.LIVE)
    var selectedLiveBouquet by mutableStateOf("الكل")
    var selectedMovieGroup by mutableStateOf("الكل")
    var selectedSeriesGroup by mutableStateOf("الكل")
    var lastWatchedStableKey by mutableStateOf<String?>(null)
    var activePlayingChannel by mutableStateOf<TvChannel?>(null)
    var activeResumePosMs by mutableStateOf(0L)

    fun nextSyncToken(): Long = syncRequestSequence.incrementAndGet()
    fun isSyncTokenValid(token: Long): Boolean = syncRequestSequence.get() == token

    fun nextPlaybackToken(channelKey: String): Long {
        lastWatchedStableKey = channelKey
        PlaybackSessionGuard.beginSession(channelKey)
        return playbackRequestSequence.incrementAndGet()
    }

    fun isPlaybackTokenValid(token: Long): Boolean = playbackRequestSequence.get() == token
}
