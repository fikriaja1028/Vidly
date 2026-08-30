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
    onLongPressStart: () -> Unit = {},
    onLongPressEnd: () -> Unit = {},
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
                    var longPressJob: kotlinx.coroutines.Job? = null
                    var tapDownPosition = Offset.Unspecified
                    var pointerMoved = false
                    var isLongPressed = false
                    
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.find { it.changedToDownIgnoreConsumed() }
                            
                            if (down != null) {
                                val isLeftSide = (down.position.x < size.width / 2)
                                // Second tap within window -> double tap (cancel long-press/single)
                                if (tapCount == 1 && tapJob?.isActive == true) {
                                    tapJob?.cancel()
                                    longPressJob?.cancel()
                                    down.consume()
                                    if (isLeftSide) onDoubleTapLeft() else onDoubleTapRight()
                                    tapCount = 0
                                    tapDownPosition = Offset.Unspecified
                                    pointerMoved = false
                                    isLongPressed = false
                                    continue
                                }
                                // First tap
                                tapCount = 1
                                tapDownPosition = down.position
                                pointerMoved = false
                                isLongPressed = false
                                tapJob?.cancel()
                                longPressJob?.cancel()
                                longPressJob = launch {
                                    delay(500)
                                    if (!pointerMoved && !isLongPressed && tapCount == 1) {
                                        isLongPressed = true
                                        tapJob?.cancel()
                                        onLongPressStart()
                                    }
                                }
                            } else {
                                val change = event.changes.firstOrNull()
                                if (change != null) {
                                    // Movement -> cancel long-press / pending singleTap (it's a drag)
                                    if (!pointerMoved && tapDownPosition != Offset.Unspecified &&
                                        (change.position - tapDownPosition).getDistance() > viewConfiguration.touchSlop
                                    ) {
                                        pointerMoved = true
                                        longPressJob?.cancel()
                                        if (isLongPressed) {
                                            onLongPressEnd()
                                            isLongPressed = false
                                            tapCount = 0
                                            tapDownPosition = Offset.Unspecified
                                        } else {
                                            // drag cancels pending singleTap
                                            tapJob?.cancel()
                                            tapCount = 0
                                            tapDownPosition = Offset.Unspecified
                                        }
                                    }
                                    if (change.changedToUpIgnoreConsumed()) {
                                        if (isLongPressed) {
                                            onLongPressEnd()
                                            isLongPressed = false
                                            tapCount = 0
                                            tapDownPosition = Offset.Unspecified
                                            longPressJob?.cancel()
                                            tapJob?.cancel()
                                        } else if (tapCount == 1 && !pointerMoved) {
                                            // schedule singleTap after doubleTap window
                                            tapJob?.cancel()
                                            tapJob = launch {
                                                delay(doubleTapTimeout)
                                                if (tapCount == 1 && !pointerMoved && !isLongPressed) {
                                                    onSingleTap()
                                                }
                                                tapCount = 0
                                                tapDownPosition = Offset.Unspecified
                                            }
                                        } else if (pointerMoved) {
                                            // drag ended, ensure reset
                                            tapCount = 0
                                            tapDownPosition = Offset.Unspecified
                                        }
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
