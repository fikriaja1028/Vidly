/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.ui.screens.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.fikriaja.vidly.domain.model.StreamBundle
import com.fikriaja.vidly.domain.model.StreamItem
import com.fikriaja.vidly.utils.VidlyLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@UnstableApi
@Singleton
class PlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val player: ExoPlayer,
    private val libraryRepository: com.fikriaja.vidly.domain.repository.LibraryRepository,
    private val bandwidthMeter: BandwidthMeter,
    private val okHttpClient: okhttp3.OkHttpClient,
    @Named("HttpDataSourceFactory") private val httpDataSourceFactory: DataSource.Factory,
    private val dataSourceFactory: DataSource.Factory,
    private val trackManager: PlayerTrackManager
) {
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()

    private val _playbackError = MutableSharedFlow<PlaybackException>()
    val playbackError: SharedFlow<PlaybackException> = _playbackError.asSharedFlow()

    private val _mediaItemTransition = MutableSharedFlow<String?>(replay = 1)
    val mediaItemTransition: SharedFlow<String?> = _mediaItemTransition.asSharedFlow()

    private val _playbackEnded = MutableSharedFlow<Unit>()
    val playbackEnded: SharedFlow<Unit> = _playbackEnded.asSharedFlow()

    private val _recoveryRequired = MutableSharedFlow<Long>()
    val recoveryRequired: SharedFlow<Long> = _recoveryRequired.asSharedFlow()

    private val _onSponsorSkipped = MutableSharedFlow<com.fikriaja.vidly.domain.model.SponsorSegment>()
    val onSponsorSkipped: SharedFlow<com.fikriaja.vidly.domain.model.SponsorSegment> = _onSponsorSkipped.asSharedFlow()

    private val _playbackStats = MutableStateFlow(PlaybackStats())
    val playbackStats: StateFlow<PlaybackStats> = _playbackStats.asStateFlow()

    private var _lastPauseTimestamp = 0L
    val lastPauseTimestamp: Long get() = _lastPauseTimestamp

    private var currentExtractedAt = 0L
    private var currentBundle: StreamBundle? = null
    private var currentStream: StreamItem? = null
    // FIX(quality berubah-ubah): was always true – even when user locked to 720p
    // the SABR loop would still auto-downgrade. Now toggled by ViewModel.
    private var isAutoQualityEnabled = true
    private var sponsorSegments: List<com.fikriaja.vidly.domain.model.SponsorSegment> = emptyList()
    // Debounce downgrade to avoid flapping when bandwidth/buffer oscillates around threshold
    private var lastDowngradeMs = 0L
    private val DOWNGRADE_DEBOUNCE_MS = 15_000L

    private var managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null
    private var heartbeatJob: Job? = null

    private val BITRATE_1080P_THRESHOLD = 5_000_000L // 5 Mbps
    private val BITRATE_720P_THRESHOLD = 2_500_000L // 2.5 Mbps

    fun getBandwidthEstimate(): Long {
        return bandwidthMeter.bitrateEstimate
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = playbackState == Player.STATE_BUFFERING
            val rawDuration = player.duration
            _duration.value = if (rawDuration == C.TIME_UNSET) 0L else rawDuration.coerceAtLeast(0L)
            
            if (playbackState == Player.STATE_READY) {
                startProgressUpdate()
            } else {
                stopProgressUpdate()
            }

            if (playbackState == Player.STATE_ENDED) {
                managerScope.launch { _playbackEnded.emit(Unit) }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val cause = error.cause

            // FIX(BUG #5): Only a 403 (expired stream URL) justifies a full re-extraction
            // hot-swap. ALL other network errors are now forwarded to the ViewModel so its
            // capped exponential-backoff retry machinery (scheduleRetry) actually engages.
            // Previously every transient error triggered an uncapped extract/fail loop.
            val is403 = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS &&
                        cause is HttpDataSource.InvalidResponseCodeException &&
                        cause.responseCode == 403

            if (is403) {
                VidlyLog.w("PlaybackManager", "403 Forbidden detected. Triggering immediate recovery.")
                val pos = player.currentPosition
                managerScope.launch { _recoveryRequired.emit(pos) }
            } else {
                managerScope.launch { _playbackError.emit(error) }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (!isPlaying) {
                _lastPauseTimestamp = System.currentTimeMillis()
                startHeartbeat()
            } else {
                _lastPauseTimestamp = 0L
                stopHeartbeat()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            _currentPosition.value = newPosition.positionMs
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            managerScope.launch { 
                _mediaItemTransition.emit(mediaItem?.mediaId) 
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _playbackStats.update { it.copy(width = videoSize.width, height = videoSize.height) }
        }

        override fun onMetadata(metadata: Metadata) {
            // Handle metadata if needed
        }
    }

    init {
        ensureListenerRegistered()
        player.repeatMode = Player.REPEAT_MODE_OFF
        
        // Emit current media item if already exists (e.g. Service restoration)
        player.currentMediaItem?.mediaId?.let { id ->
            managerScope.launch { _mediaItemTransition.emit(id) }
        }
    }

    fun ensureListenerRegistered() {
        // Safe-guard to prevent duplicate listeners if called multiple times
        player.removeListener(playerListener)
        player.addListener(playerListener)
    }

    fun isCurrentMediaLocal(): Boolean {
        return player.currentMediaItem?.localConfiguration?.uri?.scheme == "file"
    }


    fun setAutoQualityEnabled(enabled: Boolean) {
        isAutoQualityEnabled = enabled
        VidlyLog.d("PlaybackManager", "Auto-quality ${if (enabled) "ENABLED" else "DISABLED"} (stream=${currentStream?.quality})")
    }

    fun play(videoId: String, bundle: StreamBundle, stream: StreamItem, startPosition: Long = 0) {
        currentExtractedAt = bundle.extractedAt
        currentBundle = bundle
        currentStream = stream
        
        val metadata = MediaMetadata.Builder()
            .setTitle(bundle.title)
            .setArtist(bundle.uploaderName)
            .setArtworkUri(bundle.thumbnailUrl?.let { Uri.parse(it) })
            .build()

        val subtitleConfigs = trackManager.createSubtitleConfigs(bundle)

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(stream.url)
            .setMediaId(videoId)
            .setMediaMetadata(metadata)
            .setSubtitleConfigurations(subtitleConfigs)
            
        val isManifest = stream.format == "m3u8" || stream.format == "mpd"

        if (bundle.isLive || isManifest) {
            val mimeType = if (stream.format == "mpd") MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8
            mediaItemBuilder.setMimeType(mimeType)
            mediaItemBuilder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(10000)
                    .setMaxPlaybackSpeed(1.1f)
                    .setMinPlaybackSpeed(0.9f)
                    .build()
            )
        }

        val mediaItem = mediaItemBuilder.build()
        val effectiveDataSourceFactory = if (isManifest) httpDataSourceFactory else dataSourceFactory
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(effectiveDataSourceFactory)
        
        stopHeartbeat()
        player.stop()
        player.clearMediaItems()
        
        if (stream.isAdaptive) {
            val audioUrl = bundle.bestAudioStreamUrl
            if (audioUrl != null) {
                val videoSource = mediaSourceFactory.createMediaSource(mediaItem)
                val audioSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(audioUrl))
                player.setMediaSource(MergingMediaSource(videoSource, audioSource))
            } else {
                // Fallback: If adaptive is requested but no audio stream is found, 
                // try to find a non-adaptive stream (standard MPEG-4) to avoid silent playback.
                val fallbackStream = bundle.videoStreams.find { !it.isAdaptive }
                if (fallbackStream != null) {
                    VidlyLog.w("PlaybackManager", "Adaptive stream requested but audio URL missing. Falling back to standard stream: ${fallbackStream.quality}")
                    val fallbackMediaItem = mediaItem.buildUpon().setUri(fallbackStream.url).build()
                    player.setMediaSource(mediaSourceFactory.createMediaSource(fallbackMediaItem))
                } else {
                    player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
                }
            }
        } else {
            player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
        }
        
        player.prepare()
        if (startPosition > 0 && !bundle.isLive) {
            player.seekTo(startPosition)
        }
        player.playWhenReady = true
    }

    /**
     * Switches the video quality without stopping the player or resetting position.
     */
    fun switchQualitySeamlessly(videoId: String, bundle: StreamBundle, stream: StreamItem) {
        currentStream = stream
        // Any manual switch disables auto-downgrade; ViewModel will re-enable on "Auto"
        // but we keep it disabled here to avoid immediate re-downgrade flip-flop
        // The ViewModel's setQuality() will explicitly setAutoQualityEnabled() accordingly.
        val currentPosition = player.currentPosition
        val metadata = MediaMetadata.Builder()
            .setTitle(bundle.title)
            .setArtist(bundle.uploaderName)
            .setArtworkUri(bundle.thumbnailUrl?.let { Uri.parse(it) })
            .build()

        val subtitleConfigs = trackManager.createSubtitleConfigs(bundle)
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(stream.url)
            .setMediaId(videoId)
            .setMediaMetadata(metadata)
            .setSubtitleConfigurations(subtitleConfigs)

        val isManifest = stream.format == "m3u8" || stream.format == "mpd"
        if (bundle.isLive || isManifest) {
            val mimeType = if (stream.format == "mpd") MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8
            mediaItemBuilder.setMimeType(mimeType)
        }

        val mediaItem = mediaItemBuilder.build()
        val effectiveDataSourceFactory = if (isManifest) httpDataSourceFactory else dataSourceFactory
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(effectiveDataSourceFactory)

        val newSource = if (stream.isAdaptive) {
            val audioUrl = bundle.bestAudioStreamUrl
            if (audioUrl != null) {
                val videoSource = mediaSourceFactory.createMediaSource(mediaItem)
                val audioSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(audioUrl))
                MergingMediaSource(videoSource, audioSource)
            } else {
                mediaSourceFactory.createMediaSource(mediaItem)
            }
        } else {
            mediaSourceFactory.createMediaSource(mediaItem)
        }

        player.setMediaSource(newSource, false) // false = don't reset position
        player.prepare()
        // No need to call play() if it was already playing
    }

    fun playLocal(videoId: String, file: java.io.File, title: String, uploader: String, thumbnail: String?) {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(uploader)
            .setArtworkUri(thumbnail?.let { Uri.parse(it) })
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(videoId)
            .setMediaMetadata(metadata)
            .build()

        player.stop()
        player.clearMediaItems()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        currentExtractedAt = 0L
        stopProgressUpdate()
        stopHeartbeat()
    }

    fun pause() {
        player.pause()
    }

    fun resume() {
        val now = System.currentTimeMillis()
        val pauseDuration = if (_lastPauseTimestamp > 0) now - _lastPauseTimestamp else 0L
        
        val isPausedTooLong = pauseDuration > 60 * 60 * 1000 // 60 minutes
        val isLinkExpired = currentExtractedAt > 0 && now - currentExtractedAt > 5.5 * 60 * 60 * 1000
        
        if (isPausedTooLong || isLinkExpired) {
            val pos = player.currentPosition
            VidlyLog.d("PlaybackManager", "Resume check: isPausedTooLong=$isPausedTooLong, isLinkExpired=$isLinkExpired. Requesting recovery.")
            managerScope.launch { _recoveryRequired.emit(pos) }
            _lastPauseTimestamp = 0L // Reset to prevent multiple emissions
        } else {
            // Phase 1: Contextual Rewind
            if (pauseDuration > 60_000L) {
                val rewindPos = (player.currentPosition - 3000L).coerceAtLeast(0L)
                VidlyLog.d("PlaybackManager", "Applying contextual rewind: -3s to $rewindPos")
                player.seekTo(rewindPos)
            }
            player.play()
        }
    }

    /**
     * Seamlessly swaps the current media source with a fresh one (e.g. after 403 recovery).
     * Retains the current position and state.
     */
    fun hotSwapSource(videoId: String, bundle: StreamBundle, stream: StreamItem, position: Long) {
        currentExtractedAt = bundle.extractedAt
        currentBundle = bundle
        currentStream = stream
        
        val metadata = MediaMetadata.Builder()
            .setTitle(bundle.title)
            .setArtist(bundle.uploaderName)
            .setArtworkUri(bundle.thumbnailUrl?.let { Uri.parse(it) })
            .build()

        val subtitleConfigs = trackManager.createSubtitleConfigs(bundle)
        val mediaItemBuilder = MediaItem.Builder()
            .setUri(stream.url)
            .setMediaId(videoId)
            .setMediaMetadata(metadata)
            .setSubtitleConfigurations(subtitleConfigs)

        if (bundle.isLive || stream.format == "m3u8" || stream.format == "mpd") {
            val mimeType = if (stream.format == "mpd") MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8
            mediaItemBuilder.setMimeType(mimeType)
        }

        val mediaItem = mediaItemBuilder.build()
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
        
        val newSource = if (stream.isAdaptive) {
            val audioUrl = bundle.bestAudioStreamUrl
            if (audioUrl != null) {
                val videoSource = mediaSourceFactory.createMediaSource(mediaItem)
                val audioSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(audioUrl))
                MergingMediaSource(videoSource, audioSource)
            } else {
                mediaSourceFactory.createMediaSource(mediaItem)
            }
        } else {
            mediaSourceFactory.createMediaSource(mediaItem)
        }

        VidlyLog.d("PlaybackManager", "Performing hot-swap for $videoId at position $position")
        player.setMediaSource(newSource, false) // false = don't reset position
        player.prepare()
        if (position > 0) {
            player.seekTo(position)
        }
        player.play()
    }

    fun seekTo(position: Long) {
        player.seekTo(position)
    }

    fun setPlaybackSpeed(speed: Float) {
        player.setPlaybackParameters(PlaybackParameters(speed, player.playbackParameters.pitch))
    }

    fun setPitch(pitch: Float) {
        player.setPlaybackParameters(PlaybackParameters(player.playbackParameters.speed, pitch))
    }

    fun updateCcState(enabled: Boolean, preferredLang: String?) {
        trackManager.updateCcState(player, enabled, preferredLang)
    }

    fun setSponsorSegments(segments: List<com.fikriaja.vidly.domain.model.SponsorSegment>) {
        sponsorSegments = segments
    }

    @OptIn(UnstableApi::class)
    fun prepareNextSource(video: com.fikriaja.vidly.domain.model.VideoItem, bundle: StreamBundle) {
        if (player.mediaItemCount > 1) return

        // FIX: use numeric resolution instead of string contains (360 could match 360p60 etc)
        // Prefer lowest muxed for preloading to save bandwidth
        val stream = bundle.videoStreams.filter { !it.isAdaptive }.minByOrNull { parseQualityInt(it.quality) }
            ?: bundle.videoStreams.minByOrNull { parseQualityInt(it.quality) }
            ?: bundle.videoStreams.firstOrNull() ?: return
            
        val metadata = MediaMetadata.Builder()
            .setTitle(bundle.title)
            .setArtist(bundle.uploaderName)
            .setArtworkUri(bundle.thumbnailUrl?.let { Uri.parse(it) })
            .build()

        val mediaItemBuilder = MediaItem.Builder()
            .setUri(stream.url)
            .setMediaId(video.id)
            .setMediaMetadata(metadata)
            .setSubtitleConfigurations(trackManager.createSubtitleConfigs(bundle))

        if (stream.format == "m3u8" || stream.format == "mpd") {
            val mimeType = if (stream.format == "mpd") MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8
            mediaItemBuilder.setMimeType(mimeType)
        }

        val mediaItem = mediaItemBuilder.build()
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
        val finalSource = if (stream.isAdaptive) {
            val audioUrl = bundle.bestAudioStreamUrl
            if (audioUrl != null) {
                val videoSource = mediaSourceFactory.createMediaSource(mediaItem)
                val audioSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(audioUrl))
                MergingMediaSource(videoSource, audioSource)
            } else {
                mediaSourceFactory.createMediaSource(mediaItem)
            }
        } else {
            mediaSourceFactory.createMediaSource(mediaItem)
        }

        player.addMediaSource(1, finalSource)
    }

    fun prepareNextLocalSource(video: com.fikriaja.vidly.domain.model.VideoItem, file: java.io.File) {
        if (player.mediaItemCount > 1) return

        val metadata = MediaMetadata.Builder()
            .setTitle(video.title)
            .setArtist(video.uploaderName)
            .setArtworkUri(video.thumbnailUrl.let { Uri.parse(it) })
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(video.id)
            .setMediaMetadata(metadata)
            .build()

        player.addMediaItem(1, mediaItem)
    }

    private fun parseQualityInt(q: String): Int {
        // Consistent with VideoRepositoryImpl.parseQualityToInt – extracts leading number before 'p'
        val m = Regex("""(\d+)\s*p""", RegexOption.IGNORE_CASE).find(q)
        if (m != null) return m.groupValues[1].toIntOrNull() ?: 0
        return Regex("""\d+""").find(q)?.value?.toIntOrNull() ?: 0
    }

    private fun dropQuality() {
        val bundle = currentBundle ?: return
        val currentVideoId = player.currentMediaItem?.mediaId ?: return
        // Debounce: don't flap every 500ms when bandwidth hovers around threshold
        val now = System.currentTimeMillis()
        if (now - lastDowngradeMs < DOWNGRADE_DEBOUNCE_MS) return

        // Find next lower quality by numeric resolution, not string contains
        val currentRes = parseQualityInt(currentStream?.quality ?: "")
        if (currentRes == 0) return

        // Candidates: strictly lower resolution, highest among lower
        val candidates = bundle.videoStreams
            .filter { parseQualityInt(it.quality) in 1 until currentRes }
            .sortedByDescending { parseQualityInt(it.quality) }

        // Prefer same family (adaptive vs progressive) to keep muxing stable;
        // otherwise take highest lower
        val currentIsAdaptive = currentStream?.isAdaptive ?: false
        val nextStream = candidates.find { it.isAdaptive == currentIsAdaptive } ?: candidates.firstOrNull()

        if (nextStream != null) {
            lastDowngradeMs = now
            VidlyLog.w("PlaybackManager", "Dropping quality ${currentStream?.quality} -> ${nextStream.quality}")
            switchQualitySeamlessly(currentVideoId, bundle, nextStream)
        }
    }

    private fun startProgressUpdate() {
        progressJob?.cancel()
        progressJob = managerScope.launch {
            var lastSaveTime = 0L
            while (true) {
                val currentPos = player.currentPosition
                val dur = if (player.duration == C.TIME_UNSET) 0L else player.duration
                
                _currentPosition.value = currentPos
                _duration.value = dur
                _bufferedPosition.value = player.bufferedPosition

                // Phase 3: Decoupled High-Frequency Progress Tracking (Every 5 seconds)
                val now = System.currentTimeMillis()
                if (now - lastSaveTime >= 5000L && dur > 0 && !player.isCurrentMediaItemLive) {
                    player.currentMediaItem?.mediaId?.let { videoId ->
                        withContext(Dispatchers.IO) {
                            libraryRepository.updateWatchProgress(videoId, currentPos, dur)
                        }
                    }
                    lastSaveTime = now
                }

                // Phase 2: Dynamic Bitrate Dropping (SABR) – only when Auto is active
                // Also checks buffered duration to avoid flapping (buffer 5s gate was too tight)
                if (isAutoQualityEnabled && !player.isCurrentMediaItemLive) {
                    val estimate = bandwidthMeter.bitrateEstimate
                    val currentRes = parseQualityInt(currentStream?.quality ?: "")
                    val bufferedMs = player.bufferedPosition - currentPos
                    // Require both low bandwidth AND low buffer to downgrade; prevents
                    // single low estimate spike from dropping 1080p->480p and then bouncing.
                    val shouldDrop1080 = currentRes >= 1080 && estimate in 1 until BITRATE_1080P_THRESHOLD && bufferedMs < 8000
                    val shouldDrop720 = currentRes in 720..1079 && estimate in 1 until BITRATE_720P_THRESHOLD && bufferedMs < 6000
                    if (shouldDrop1080 || shouldDrop720) {
                        VidlyLog.w("PlaybackManager", "Bandwidth $estimate buffered $bufferedMs low -> downgrade from $currentRes p")
                        dropQuality()
                    }
                }

                // Update Stats
                val videoFormat = player.videoFormat
                val bandwidth = bandwidthMeter.bitrateEstimate
                _playbackStats.update { stats ->
                    stats.copy(
                        videoFormat = videoFormat?.sampleMimeType,
                        bitrate = videoFormat?.bitrate ?: 0,
                        resolution = videoFormat?.let { "${it.width}x${it.height}" } ?: "",
                        droppedFrames = player.videoDecoderCounters?.droppedBufferCount ?: 0,
                        bandwidthEstimate = bandwidth
                    )
                }

                // SponsorBlock Skipping Logic
                if (sponsorSegments.isNotEmpty()) {
                    val matchingSegment = sponsorSegments.find { 
                        currentPos >= it.startMs && currentPos < it.endMs 
                    }
                    if (matchingSegment != null) {
                        VidlyLog.d("PlaybackManager", "Skipping sponsor segment: ${matchingSegment.category}")
                        player.seekTo(matchingSegment.endMs)
                        _onSponsorSkipped.emit(matchingSegment)
                    }
                }

                delay(500)
            }
        }
    }

    private fun stopProgressUpdate() {
        progressJob?.cancel()
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        val url = currentStream?.url ?: return
        if (url.isBlank() || !url.contains("googlevideo.com")) return

        heartbeatJob = managerScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(90_000L) // Ping every 90 seconds
                try {
                    val request = okhttp3.Request.Builder()
                        .url(url)
                        .header("Range", "bytes=0-1")
                        .build()
                    
                    okHttpClient.newCall(request).execute().use { response ->
                        VidlyLog.d("PlaybackManager", "Heartbeat sent to stream server. Status: ${response.code}")
                    }
                } catch (e: Exception) {
                    VidlyLog.w("PlaybackManager", "Heartbeat failed: ${e.message}")
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun cleanUp() {
        stopProgressUpdate()
        stopHeartbeat()
        // We don't remove the listener here because this is a Singleton.
        // If we remove it, subsequent uses of this instance (e.g. re-entering the app) 
        // will not receive updates. The listener is managed by process lifecycle.
        managerScope.coroutineContext.cancelChildren()
    }

    fun release() {
        cleanUp()
        // DO NOT call player.release() here. 
        // Both PlaybackManager and ExoPlayer are Singletons. 
        // Releasing the singleton player makes it unusable for the rest of the process lifetime.
    }
}

data class PlaybackStats(
    val width: Int = 0,
    val height: Int = 0,
    val videoFormat: String? = null,
    val bitrate: Int = 0,
    val resolution: String = "",
    val droppedFrames: Int = 0,
    val bandwidthEstimate: Long = 0
)
