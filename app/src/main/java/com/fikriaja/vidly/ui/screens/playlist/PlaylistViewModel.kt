/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.ui.screens.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fikriaja.vidly.domain.model.PlaylistDetails
import com.fikriaja.vidly.domain.model.VideoItem
import com.fikriaja.vidly.domain.model.StreamBundle
import com.fikriaja.vidly.data.local.PlaylistFavoriteEntity
import com.fikriaja.vidly.data.local.FavoriteEntity
import com.fikriaja.vidly.domain.repository.DownloadRepository
import com.fikriaja.vidly.domain.repository.VideoRepository
import com.fikriaja.vidly.domain.repository.LibraryRepository
import com.fikriaja.vidly.domain.usecase.*
import com.fikriaja.vidly.ui.components.DownloadDialogState
import com.fikriaja.vidly.utils.VidlyError
import com.fikriaja.vidly.utils.HistoryUtils.applyHistory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val getPlaylistDetailsUseCase: GetPlaylistDetailsUseCase,
    private val getVideoStreamsUseCase: GetVideoStreamsUseCase,
    private val downloadVideoUseCase: DownloadVideoUseCase,
    private val togglePlaylistFavoriteUseCase: TogglePlaylistFavoriteUseCase,
    private val isPlaylistFavoriteUseCase: IsPlaylistFavoriteUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    val libraryRepository: LibraryRepository,
    private val videoRepository: VideoRepository,
    val downloadRepository: DownloadRepository
) : ViewModel() {

    private val _internalUiState = MutableStateFlow<PlaylistUiState>(PlaylistUiState.Loading)
    val uiState: StateFlow<PlaylistUiState> = combine(
        _internalUiState,
        libraryRepository.getHistory()
    ) { state, history ->
        if (state is PlaylistUiState.Success) {
            state.copy(details = state.details.copy(videos = state.details.videos.applyHistory(history)))
        } else {
            state
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaylistUiState.Loading)

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>()
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    val downloadedVideoIds: StateFlow<Set<String>> = downloadRepository.getAllDownloads()
        .map { list -> 
            list.filter { it.status == com.fikriaja.vidly.data.local.DownloadStatus.COMPLETED }
                .map { it.videoId }
                .toSet() 
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun loadPlaylist(playlistId: String) {
        if (playlistId.startsWith("local:")) {
            val id = playlistId.substringAfter("local:").toIntOrNull()
            if (id != null) {
                loadLocalPlaylist(id)
                return
            }
        }

        val playlistUrl = if (playlistId.startsWith("http")) {
            playlistId
        } else {
            "https://www.youtube.com/playlist?list=$playlistId"
        }
        viewModelScope.launch {
            _internalUiState.value = PlaylistUiState.Loading
            
            // Watch favorite status
            launch {
                isPlaylistFavoriteUseCase(playlistId).collectLatest {
                    _isFavorite.value = it
                }
            }

            getPlaylistDetailsUseCase(playlistUrl)
                .onSuccess { details ->
                    _internalUiState.value = PlaylistUiState.Success(details)
                }
                .onFailure { exception ->
                    _internalUiState.value = PlaylistUiState.Error(VidlyError.fromThrowable(exception))
                }
        }
    }

    private fun loadLocalPlaylist(id: Int) {
        viewModelScope.launch {
            _internalUiState.value = PlaylistUiState.Loading
            libraryRepository.getLocalPlaylists().collectLatest { playlists ->
                val playlist = playlists.find { it.id == id }
                if (playlist != null) {
                    libraryRepository.getVideosForLocalPlaylist(id).collectLatest { videos ->
                        val details = PlaylistDetails(
                            id = "local:$id",
                            title = playlist.name,
                            thumbnailUrl = playlist.thumbnailUrl ?: videos.firstOrNull()?.thumbnailUrl ?: "",
                            uploaderName = "Local Playlist",
                            uploaderUrl = null,
                            videos = videos.map { it.toVideoItem() }
                        )
                        _internalUiState.value = PlaylistUiState.Success(details)
                    }
                } else {
                    _internalUiState.value = PlaylistUiState.Error(VidlyError.Unknown("Playlist not found"))
                }
            }
        }
    }

    private fun com.fikriaja.vidly.data.local.LocalPlaylistVideoEntity.toVideoItem() = VideoItem(
        id = videoId,
        title = title,
        thumbnailUrl = thumbnailUrl,
        uploaderName = uploaderName,
        uploaderUrl = null,
        uploaderThumbnailUrl = null,
        viewCount = 0,
        uploadDate = null,
        rawUploadDate = null,
        duration = duration
    )

    fun downloadPlaylist(quality: String) {
        val state = _internalUiState.value as? PlaylistUiState.Success ?: return
        val details = state.details
        
        viewModelScope.launch {
            _snackbarMessage.emit("Playlist download started (${quality})")
            details.videos.forEach { video ->
                if (!downloadedVideoIds.value.contains(video.id)) {
                    downloadVideoUseCase(
                        videoId = video.id,
                        url = null, // Will be fetched by the worker
                        title = video.title,
                        thumbnailUrl = video.thumbnailUrl,
                        uploaderName = video.uploaderName,
                        quality = quality,
                        format = null, // Will be fetched by the worker
                        audioUrl = null,
                        playlistId = details.id,
                        playlistTitle = details.title
                    )
                }
            }
            _showPlaylistDownloadDialog.value = false
        }
    }

    private val _showPlaylistDownloadDialog = MutableStateFlow(false)
    val showPlaylistDownloadDialog: StateFlow<Boolean> = _showPlaylistDownloadDialog.asStateFlow()

    fun showPlaylistDownloadDialog() {
        _showPlaylistDownloadDialog.value = true
    }

    fun dismissPlaylistDownloadDialog() {
        _showPlaylistDownloadDialog.value = false
    }

    fun togglePlaylistFavorite() {
        val state = _internalUiState.value as? PlaylistUiState.Success ?: return
        val details = state.details
        viewModelScope.launch {
            togglePlaylistFavoriteUseCase(
                PlaylistFavoriteEntity(
                    playlistId = details.id,
                    title = details.title,
                    thumbnailUrl = details.thumbnailUrl,
                    uploaderName = details.uploaderName
                )
            )
        }
    }

    fun toggleVideoFavorite(video: VideoItem) {
        viewModelScope.launch {
            toggleFavoriteUseCase(
                com.fikriaja.vidly.data.local.FavoriteEntity(
                    videoId = video.id,
                    title = video.title,
                    thumbnailUrl = video.thumbnailUrl,
                    uploaderName = video.uploaderName
                )
            )
            val isFav = libraryRepository.isFavorite(video.id).first()
            _snackbarMessage.emit(if (isFav) "Added to Liked Videos" else "Removed from Liked Videos")
        }
    }

    private val _downloadState = MutableStateFlow<com.fikriaja.vidly.ui.components.DownloadDialogState>(com.fikriaja.vidly.ui.components.DownloadDialogState.Idle)
    val downloadState: StateFlow<com.fikriaja.vidly.ui.components.DownloadDialogState> = _downloadState.asStateFlow()

    fun prepareDownload(video: VideoItem) {
        viewModelScope.launch {
            // Optimistic Cache Check
            val cachedBundle = videoRepository.getCachedStreamBundle(video.id)
            if (cachedBundle != null && !cachedBundle.videoStreams.isEmpty()) {
                _downloadState.value = com.fikriaja.vidly.ui.components.DownloadDialogState.ShowDialog(video, cachedBundle)
                return@launch
            }

            _downloadState.value = com.fikriaja.vidly.ui.components.DownloadDialogState.Loading(video)
            getVideoStreamsUseCase(video.id)
                .onSuccess { bundle ->
                    _downloadState.value = com.fikriaja.vidly.ui.components.DownloadDialogState.ShowDialog(video, bundle)
                }
                .onFailure {
                    _downloadState.value = com.fikriaja.vidly.ui.components.DownloadDialogState.Idle
                }
        }
    }

    fun download(video: VideoItem, bundle: com.fikriaja.vidly.domain.model.StreamBundle, url: String?, quality: String?, format: String?, isAdaptive: Boolean) {
        viewModelScope.launch {
            val audioUrl = if (isAdaptive) {
                val isWebm = format?.contains("webm", ignoreCase = true) == true
                val compatibleStreams = bundle.audioStreams.filter { audio ->
                    if (isWebm) {
                        audio.format.contains("webm", ignoreCase = true) || 
                        audio.format.contains("opus", ignoreCase = true)
                    } else {
                        audio.format.contains("m4a", ignoreCase = true) || 
                        audio.format.contains("aac", ignoreCase = true)
                    }
                }

                val bestAudio = compatibleStreams.filter { it.trackType == "ORIGINAL" }
                    .maxByOrNull { it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                    ?: compatibleStreams.maxByOrNull { it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
                
                bestAudio?.url
            } else null

            // Fallback to standalone progressive stream if adaptive audio pairing fails
            val finalUrl = if (isAdaptive && audioUrl == null) {
                bundle.videoStreams.find { !it.isAdaptive }?.url ?: url
            } else url

            val finalIsAdaptive = isAdaptive && audioUrl != null

            downloadVideoUseCase(
                videoId = video.id,
                url = finalUrl,
                title = video.title,
                thumbnailUrl = video.thumbnailUrl,
                uploaderName = video.uploaderName,
                quality = quality,
                format = format,
                audioUrl = if (finalIsAdaptive) audioUrl else null
            )
            _snackbarMessage.emit("Downloading started")
            _downloadState.value = com.fikriaja.vidly.ui.components.DownloadDialogState.Idle
        }
    }

    fun dismissDownloadDialog() {
        _downloadState.value = com.fikriaja.vidly.ui.components.DownloadDialogState.Idle
    }

    fun removeFromPlaylist(video: VideoItem) {
        val currentUiState = _internalUiState.value
        if (currentUiState is PlaylistUiState.Success && currentUiState.details.id.startsWith("local:")) {
            val playlistId = currentUiState.details.id.substringAfter("local:").toIntOrNull() ?: return
            viewModelScope.launch {
                libraryRepository.removeVideoFromLocalPlaylist(playlistId, video.id)
                _snackbarMessage.emit("Removed from Playlist")
            }
        }
    }
}

sealed interface PlaylistUiState {
    object Loading : PlaylistUiState
    data class Success(val details: PlaylistDetails) : PlaylistUiState
    data class Error(val error: VidlyError) : PlaylistUiState
}
