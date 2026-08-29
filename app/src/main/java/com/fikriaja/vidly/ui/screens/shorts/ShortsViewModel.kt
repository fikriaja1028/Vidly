/*
 * Vidly Project Original (2026)
 * YT Shorts Feature
 */
package com.fikriaja.vidly.ui.screens.shorts

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.fikriaja.vidly.data.local.FavoriteEntity
import com.fikriaja.vidly.domain.model.VideoItem
import com.fikriaja.vidly.domain.repository.LibraryRepository
import com.fikriaja.vidly.domain.repository.VideoRepository
import com.fikriaja.vidly.domain.usecase.GetVideoStreamsUseCase
import com.fikriaja.vidly.domain.usecase.ToggleFavoriteUseCase
import com.fikriaja.vidly.utils.Constants
import com.fikriaja.vidly.utils.VidlyLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Page
import javax.inject.Inject

data class ShortsUiState(
    val videos: List<VideoItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val nextPage: Page? = null,
    val currentIndex: Int = 0
)

@UnstableApi
@HiltViewModel
class ShortsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoRepository: VideoRepository,
    private val libraryRepository: LibraryRepository,
    private val getVideoStreamsUseCase: GetVideoStreamsUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShortsUiState(isLoading = true))
    val uiState: StateFlow<ShortsUiState> = _uiState.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    // Dedicated player for Shorts – loops like YT Shorts
    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE
        volume = 1f
        playWhenReady = true
    }

    private var loadJob: Job? = null
    private var streamJob: Job? = null

    init {
        loadShorts()
    }

    fun loadShorts(refresh: Boolean = false) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
                val result = videoRepository.getShortsVideos()
                val videos = result.items
                VidlyLog.d("ShortsViewModel", "Fetched ${videos.size} shorts")
                
                _uiState.value = ShortsUiState(
                    videos = videos,
                    isLoading = false,
                    nextPage = result.nextPage,
                    currentIndex = 0
                )
                if (videos.isNotEmpty()) {
                    playShort(0)
                } else {
                    VidlyLog.w("ShortsViewModel", "No shorts videos found in result")
                }
            } catch (e: Exception) {
                VidlyLog.e("ShortsViewModel", "loadShorts failed", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || state.nextPage == null) return
        viewModelScope.launch {
            try {
                _uiState.value = state.copy(isLoadingMore = true)
                val result = videoRepository.fetchNextShortsPage(state.nextPage)
                val newVideos = state.videos + result.items
                _uiState.value = state.copy(
                    videos = newVideos.distinctBy { it.id },
                    isLoadingMore = false,
                    nextPage = result.nextPage
                )
            } catch (e: Exception) {
                VidlyLog.e("ShortsViewModel", "loadMore failed", e)
                _uiState.value = state.copy(isLoadingMore = false)
            }
        }
    }

    fun onPageSelected(index: Int) {
        val size = _uiState.value.videos.size
        if (index !in 0 until size) return
        _uiState.value = _uiState.value.copy(currentIndex = index)
        playShort(index)
        // Preload next
        if (index >= size - 3) loadMore()
    }

    private fun playShort(index: Int) {
        val video = _uiState.value.videos.getOrNull(index) ?: return
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            try {
                // Try to get best stream (prefer vertical-friendly: 720p, 480p, 360p)
                val bundle = videoRepository.getCachedStreamBundle(video.id)?.takeIf { !it.isExpired() }
                    ?: getVideoStreamsUseCase(video.id).getOrNull()

                if (bundle != null && bundle.videoStreams.isNotEmpty()) {
                    val preferred = bundle.videoStreams.sortedByDescending { parseRes(it.quality) }
                        .find { parseRes(it.quality) in 360..720 } // shorts are usually 720p or lower
                        ?: bundle.videoStreams.first()
                    val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                        .setUserAgent(Constants.DEFAULT_USER_AGENT)
                    val mediaItem = MediaItem.Builder()
                        .setUri(preferred.url)
                        .setMediaId(video.id)
                        .build()
                    val factory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSourceFactory)
                    val source = if (preferred.isAdaptive && bundle.bestAudioStreamUrl != null) {
                        val videoSource = factory.createMediaSource(mediaItem)
                        val audioSource = factory.createMediaSource(MediaItem.fromUri(bundle.bestAudioStreamUrl))
                        androidx.media3.exoplayer.source.MergingMediaSource(videoSource, audioSource)
                    } else {
                        factory.createMediaSource(mediaItem)
                    }
                    player.setMediaSource(source)
                    player.prepare()
                    player.playWhenReady = true
                    VidlyLog.d("ShortsViewModel", "Playing short ${video.id} quality=${preferred.quality}")
                } else {
                    // Fallback: try direct progressive via StreamInfo url already? If bundle null, keep player idle
                    VidlyLog.w("ShortsViewModel", "No bundle for short ${video.id}")
                }
            } catch (e: Exception) {
                VidlyLog.e("ShortsViewModel", "playShort failed for ${video.id}", e)
            }
        }
    }

    private fun parseRes(q: String): Int {
        val m = Regex("""(\d+)\s*p""", RegexOption.IGNORE_CASE).find(q)
        if (m != null) return m.groupValues[1].toIntOrNull() ?: 0
        return Regex("""\d+""").find(q)?.value?.toIntOrNull() ?: 0
    }

    fun toggleFavorite(video: VideoItem) {
        viewModelScope.launch {
            val isFav = libraryRepository.isFavorite(video.id).first()
            toggleFavoriteUseCase(
                FavoriteEntity(
                    videoId = video.id,
                    title = video.title,
                    thumbnailUrl = video.thumbnailUrl,
                    uploaderName = video.uploaderName
                )
            )
            _snackbar.tryEmit(if (isFav) "Removed from Liked" else "Added to Liked")
        }
    }

    fun isFavoriteFlow(videoId: String) = libraryRepository.isFavorite(videoId)

    fun pause() {
        player.pause()
    }

    fun resume() {
        player.play()
    }

    override fun onCleared() {
        super.onCleared()
        player.release()
    }
}
