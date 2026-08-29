/*
 * Vidly Project Original (2026)
 * YT Shorts Feature – Vertical swipe feed
 */
package com.fikriaja.vidly.ui.screens.shorts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.fikriaja.vidly.domain.model.VideoItem
import com.fikriaja.vidly.ui.components.ThumbnailImage
import com.fikriaja.vidly.ui.components.player.VideoPlayerView
import androidx.media3.ui.AspectRatioFrameLayout

@OptIn(ExperimentalFoundationApi::class, UnstableApi::class)
@Composable
fun ShortsScreen(
    viewModel: ShortsViewModel = hiltViewModel(),
    onVideoClick: (VideoItem) -> Unit = {},
    onChannelClick: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val videos = uiState.videos

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (uiState.isLoading && videos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        } else if (uiState.error != null && videos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = uiState.error ?: "Failed to load Shorts", color = Color.White)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { viewModel.loadShorts(true) }) { Text("Retry") }
                }
            }
        } else if (videos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No Shorts found", color = Color.White)
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { videos.size })

            // Sync pager with ViewModel
            LaunchedEffect(pagerState.currentPage) {
                viewModel.onPageSelected(pagerState.currentPage)
            }

            // Also listen to ViewModel currentIndex if changed externally
            LaunchedEffect(uiState.currentIndex) {
                if (uiState.currentIndex != pagerState.currentPage) {
                    // pagerState.scrollToPage(uiState.currentIndex)
                }
            }

            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val video = videos[page]
                ShortsPlayerPage(
                    video = video,
                    player = viewModel.player,
                    isCurrentPage = pagerState.currentPage == page,
                    onToggleFavorite = { viewModel.toggleFavorite(video) },
                    onChannelClick = onChannelClick,
                    onVideoClick = onVideoClick,
                    viewModel = viewModel
                )
            }

            // Top bar – Shorts title
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Shorts",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 0.5.sp),
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}

@UnstableApi
@Composable
private fun ShortsPlayerPage(
    video: VideoItem,
    player: Player,
    isCurrentPage: Boolean,
    onToggleFavorite: () -> Unit,
    onChannelClick: (String) -> Unit,
    onVideoClick: (VideoItem) -> Unit,
    viewModel: ShortsViewModel
) {
    var isPlaying by remember { mutableStateOf(true) }
    var showPlayIcon by remember { mutableStateOf(false) }
    val isFavorite by viewModel.isFavoriteFlow(video.id).collectAsStateWithLifecycle(initialValue = false)

    // Keep play state synced with player when page visible
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            isPlaying = player.isPlaying
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable {
                if (player.isPlaying) {
                    viewModel.pause()
                    isPlaying = false
                } else {
                    viewModel.resume()
                    isPlaying = true
                }
                showPlayIcon = true
            }
    ) {
        // Video player – only show when current page, otherwise show thumbnail placeholder
        if (isCurrentPage) {
            VideoPlayerView(
                player = player,
                modifier = Modifier.fillMaxSize(),
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // fill vertical like Shorts
            )
        } else {
            // Offscreen pages show thumbnail to save resources
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
        }

        // Center play/pause icon flash
        if (showPlayIcon) {
            LaunchedEffect(showPlayIcon) {
                kotlinx.coroutines.delay(600)
                showPlayIcon = false
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    color = Color.Black.copy(alpha = 0.35f),
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }

        // Gradient scrim bottom
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                    )
                )
        )

        // Info overlay – bottom left
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.75f)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 80.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Channel row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(enabled = video.uploaderUrl != null) {
                    video.uploaderUrl?.let(onChannelClick)
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (video.uploaderThumbnailUrl != null) {
                        AsyncImage(
                            model = video.uploaderThumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(Icons.Default.Person, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = video.uploaderName,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }

            Text(
                text = video.title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Tags / description preview
            if (!video.uploadDate.isNullOrBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = video.uploadDate ?: "",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Right action bar – Like / Share / More
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 12.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Like
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onToggleFavorite() }) {
                Surface(
                    color = Color.White.copy(alpha = 0.15f),
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Like",
                            tint = if (isFavorite) Color.Red else Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isFavorite) "Liked" else "Like",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
                )
            }

            // Share
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onVideoClick(video) }) {
                Surface(
                    color = Color.White.copy(alpha = 0.15f),
                    shape = CircleShape,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Default.Share, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
                Text(
                    text = "Share",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // More – open full player
            Surface(
                color = Color.White.copy(alpha = 0.15f),
                shape = CircleShape,
                modifier = Modifier.size(48.dp).clickable { onVideoClick(video) }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.MoreVert, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
