/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.ui.screens.player

import androidx.media3.common.Player
import com.fikriaja.vidly.domain.model.VideoItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FEATURE (Queue editing): holds an explicit user-built playback queue with
 * "play next" / "add to queue" support, drag-reorder, remove, shuffle and
 * repeat modes. Thread-confined to the main dispatcher by convention; all
 * mutations happen through the ViewModel on the main thread.
 */
@Singleton
class QueueManager @Inject constructor() {
    private val _skipToNextEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val skipToNextEvent = _skipToNextEvent.asSharedFlow()

    private val _skipToPreviousEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val skipToPreviousEvent = _skipToPreviousEvent.asSharedFlow()

    private val _queue = MutableStateFlow<List<VideoItem>>(emptyList())
    val queue: StateFlow<List<VideoItem>> = _queue.asStateFlow()

    /** Index of the currently-playing item inside [queue], or -1 when idle. */
    private val _currentQueueIndex = MutableStateFlow(-1)
    val currentQueueIndex: StateFlow<Int> = _currentQueueIndex.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    /** One of [Player.REPEAT_MODE_OFF], [Player.REPEAT_MODE_ONE], [Player.REPEAT_MODE_ALL]. */
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    fun skipToNext() {
        _skipToNextEvent.tryEmit(Unit)
    }

    fun skipToPrevious() {
        _skipToPreviousEvent.tryEmit(Unit)
    }

    fun enqueueNext(video: VideoItem) {
        val list = _queue.value.toMutableList()
        val current = _currentQueueIndex.value
        val insertAt = if (current in list.indices) current + 1 else 0
        // Avoid exact duplicates immediately adjacent
        if (list.getOrNull(insertAt)?.id == video.id) return
        list.add(insertAt, video)
        if (_currentQueueIndex.value == -1 && list.size == 1) _currentQueueIndex.value = 0
        _queue.value = list
    }

    fun enqueueLast(video: VideoItem) {
        val list = _queue.value.toMutableList()
        if (list.any { it.id == video.id }) return
        list.add(video)
        if (_currentQueueIndex.value == -1 && list.size == 1) _currentQueueIndex.value = 0
        _queue.value = list
    }

    fun removeAt(index: Int) {
        val list = _queue.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        val current = _currentQueueIndex.value
        _currentQueueIndex.value = when {
            list.isEmpty() -> -1
            index < current -> current - 1
            index == current -> current.coerceAtMost(list.lastIndex)
            else -> current
        }
        _queue.value = list
    }

    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        val list = _queue.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        val item = list.removeAt(from)
        list.add(to, item)
        _queue.value = list
        // Keep pointing at the same playing video after reordering
        _currentQueueIndex.value = when (_currentQueueIndex.value) {
            from -> to
            in minOf(from, to)..maxOf(from, to) -> _currentQueueIndex.value + (if (from > to) 1 else -1)
            else -> _currentQueueIndex.value
        }
    }

    fun clearQueue() {
        _queue.value = emptyList()
        _currentQueueIndex.value = -1
    }

    fun setCurrentIndex(index: Int) {
        _currentQueueIndex.value = index
    }

    /** Returns the next queued item (advancing the index), honouring repeat-all wrap. */
    fun pollNext(): VideoItem? {
        val list = _queue.value
        if (list.isEmpty()) return null
        var next = _currentQueueIndex.value + 1
        if (next >= list.size) {
            if (_repeatMode.value == Player.REPEAT_MODE_ALL) next = 0 else return null
        }
        _currentQueueIndex.value = next
        return list[next]
    }

    /** Returns the previous queued item, honouring repeat-all wrap. */
    fun pollPrevious(): VideoItem? {
        val list = _queue.value
        if (list.isEmpty()) return null
        var prev = _currentQueueIndex.value - 1
        if (prev < 0) {
            if (_repeatMode.value == Player.REPEAT_MODE_ALL) prev = list.lastIndex else return null
        }
        _currentQueueIndex.value = prev
        return list[prev]
    }

    fun setCurrentItem(videoId: String) {
        _currentQueueIndex.value = _queue.value.indexOfFirst { it.id == videoId }
    }

    fun setShuffleEnabled(enabled: Boolean) {
        _isShuffleEnabled.value = enabled
    }

    fun setRepeatMode(mode: Int) {
        _repeatMode.value = mode
    }
}
