/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import com.fikriaja.vidly.domain.model.VideoItem
import com.fikriaja.vidly.ui.components.ThumbnailImage
import com.fikriaja.vidly.ui.components.ThumbnailQuality

/**
 * FEATURE (Queue editing): bottom sheet showing the user queue with
 * long-press drag reordering, per-item remove, tap-to-play, plus shuffle and
 * repeat toggles â€” mirroring the YouTube queue screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<VideoItem>,
    currentIndex: Int,
    isShuffleEnabled: Boolean,
    repeatMode: Int,
    onDismiss: () -> Unit,
    onPlayItem: (Int) -> Unit,
    onRemoveItem: (Int) -> Unit,
    onMoveItem: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit
) {
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    // --- drag-reorder state (stable across recomposition) ---
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // Live bounds of each row (window coords); height is used as swap threshold
    val itemBounds = remember { mutableStateMapOf<Int, androidx.compose.ui.geometry.Rect>() }

    // Stable reads inside pointer callbacks â€” these State objects persist across
    // recompositions, so the pointerInput(Unit) closure never goes stale.
    val draggingIndexState = rememberUpdatedState(draggingIndex)
    val dragOffsetState = rememberUpdatedState(dragOffset)
    val queueState = rememberUpdatedState(queue)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            // Header: title + shuffle + repeat + clear
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Queue",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (isShuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        imageVector = if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                        contentDescription = "Repeat",
                        tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onClearQueue, enabled = queue.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear queue",
                        tint = if (queue.isNotEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }

            if (queue.isEmpty()) {
                Text(
                    text = "Your queue is empty. Long-press a related video and choose \"Play next\" or \"Add to queue\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                ) {
                    itemsIndexed(queue, key = { _, item -> "queue_${item.id}" }) { index, item ->
                        val isDragging = draggingIndexState.value == index
                        val itemIndexState = rememberUpdatedState(index)
                        val itemHeightState = rememberUpdatedState(itemBounds[index]?.height ?: 0f)

                        Box(
                            modifier = Modifier
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    if (draggingIndexState.value == index) {
                                        translationY = dragOffsetState.value
                                    }
                                }
                                .onGloballyPositioned { coords ->
                                    itemBounds[index] = coords.boundsInWindow()
                                }
                                // Live-swap: when the dragged row moves past half the
                                // height of a neighbour, swap positions in the queue.
                                .pointerInput(Unit) {
                                    var accumulated = 0f
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingIndex = itemIndexState.value
                                            dragOffset = 0f
                                            accumulated = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            val from = draggingIndexState.value ?: return@detectDragGesturesAfterLongPress
                                            dragOffset += amount.y
                                            accumulated += amount.y

                                            val myHeight = itemHeightState.value
                                            val threshold = if (myHeight > 0f) myHeight / 2f else 40f

                                            if (accumulated > threshold && from < queueState.value.lastIndex) {
                                                onMoveItem(from, from + 1)
                                                draggingIndex = from + 1
                                                dragOffset -= myHeight
                                                accumulated -= myHeight
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            } else if (accumulated < -threshold && from > 0) {
                                                onMoveItem(from, from - 1)
                                                draggingIndex = from - 1
                                                dragOffset += myHeight
                                                accumulated += myHeight
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        },
                                        onDragEnd = {
                                            draggingIndex = null
                                            dragOffset = 0f
                                            accumulated = 0f
                                        },
                                        onDragCancel = {
                                            draggingIndex = null
                                            dragOffset = 0f
                                            accumulated = 0f
                                        }
                                    )
                                }
                        ) {
                            QueueItemRow(
                                video = item,
                                isCurrent = index == currentIndex,
                                onPlay = { onPlayItem(index) },
                                onRemove = { onRemoveItem(index) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemRow(
    video: VideoItem,
    isCurrent: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else Color.Transparent,
                RoundedCornerShape(12.dp)
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "Reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(width = 96.dp, height = 54.dp)
        ) {
            ThumbnailImage(
                videoId = video.id,
                thumbnailUrl = video.thumbnailUrl,
                quality = ThumbnailQuality.Medium,
                modifier = Modifier.size(width = 96.dp, height = 54.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Text(
                    text = video.uploaderName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        IconButton(onClick = onPlay, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "Play",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
