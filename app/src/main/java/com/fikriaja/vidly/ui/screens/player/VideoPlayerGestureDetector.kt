/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly.ui.screens.player

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun VideoPlayerGestureDetector(
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit,
    onSingleTap: () -> Unit,
    onSwipeDown: () -> Unit,
    onSwipeUp: () -> Unit,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onVerticalSwipeLeft: (Float) -> Unit = {},
    onVerticalSwipeRight: (Float) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val doubleTapTimeout = 300L
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                coroutineScope {
                    var tapCount = 0
                    var tapJob: kotlinx.coroutines.Job? = null
                    // FIX(MEDIUM): the single-tap timer previously fired on timer
                    // expiry even when the finger had already started DRAGGING
                    // (volume/brightness swipes) or was still held down â€” every
                    // vertical drag also toggled the controls overlay mid-gesture.
                    // A single tap now requires: exactly one tap, the pointer has
                    // been LIFTED, and it never moved beyond the touch slop.
                    var tapDownPosition = Offset.Unspecified
                    var pointerLifted = false
                    var pointerMoved = false
                    
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.find { it.changedToDownIgnoreConsumed() }
                            
                            if (down != null) {
                                tapCount++
                                tapJob?.cancel()
                                tapDownPosition = down.position
                                pointerLifted = false
                                pointerMoved = false
                                
                                val isLeftSide = (down.position.x < size.width / 2)
                                
                                if (tapCount >= 2) {
                                    // Multi-tap detected
                                    down.consume()
                                    if (isLeftSide) onDoubleTapLeft() else onDoubleTapRight()
                                    
                                    // After a double tap, we reset to avoid triple-tap confusion
                                    tapCount = 0
                                } else {
                                    // First tap, wait to see if it's a double tap
                                    tapJob = launch {
                                        delay(doubleTapTimeout)
                                        if (tapCount == 1 && pointerLifted && !pointerMoved) {
                                            onSingleTap()
                                        }
                                        tapCount = 0
                                    }
                                }
                            } else {
                                // Track movement + lift for the pending single tap
                                val change = event.changes.firstOrNull()
                                if (change != null) {
                                    if (change.changedToUpIgnoreConsumed()) {
                                        pointerLifted = true
                                    }
                                    if (tapDownPosition != Offset.Unspecified &&
                                        (change.position - tapDownPosition).getDistance() > viewConfiguration.touchSlop
                                    ) {
                                        pointerMoved = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                var totalDrag = 0f
                var dragStartX = 0f
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        totalDrag = 0f
                        dragStartX = offset.x
                        onDragStart()
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        totalDrag += dragAmount
                        
                        val screenHeight = size.height
                        val screenWidth = size.width
                        val dragPercentage = -dragAmount / screenHeight
                        val sideMargin = screenWidth * 0.30f // 30% from each side
                        
                        if (change.position.x < sideMargin) {
                            onVerticalSwipeLeft(dragPercentage)
                        } else if (change.position.x > screenWidth - sideMargin) {
                            onVerticalSwipeRight(dragPercentage)
                        }
                    },
                    onDragEnd = {
                        val screenWidth = size.width
                        val sideMargin = screenWidth * 0.30f
                        
                        // Only trigger minimize/maximize if the drag started in the center "dead zone"
                        // to avoid conflicts with volume/brightness adjustments on the sides.
                        val startedInCenter = dragStartX in sideMargin..(screenWidth - sideMargin)

                        if (startedInCenter) {
                            if (totalDrag > 150) {
                                onSwipeDown()
                            } else if (totalDrag < -150) {
                                onSwipeUp()
                            }
                        }
                        onDragEnd()
                    },
                    onDragCancel = {
                        onDragCancel()
                    }
                )
            }
    ) {
        content()
    }
}
