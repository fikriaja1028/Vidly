/*
 * Vidly Project Original (2026)
 * fikriaja1028 (GitHub.com/fikriaja1028)
 * Licenced Under GPL-3.0+
*/
package com.fikriaja.vidly

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.util.Consumer
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.fikriaja.vidly.domain.model.VideoItem
import com.fikriaja.vidly.services.PlaybackService
import com.fikriaja.vidly.ui.components.GlassSurface
import com.fikriaja.vidly.ui.components.main.OfflineBottomBanner
import com.fikriaja.vidly.ui.components.main.VidlyTopAppBar
import com.fikriaja.vidly.ui.components.main.RestorationBanner
import com.fikriaja.vidly.ui.navigation.NavGraph
import com.fikriaja.vidly.ui.navigation.Destination
import com.fikriaja.vidly.ui.navigation.toDestination
import com.fikriaja.vidly.ui.screens.player.MiniPlayerManager
import com.fikriaja.vidly.ui.screens.player.PlayerOverlay
import com.fikriaja.vidly.ui.screens.settings.UpdateViewModel
import com.fikriaja.vidly.ui.theme.IncognitoPurple
import com.fikriaja.vidly.ui.theme.VidlyTheme
import com.fikriaja.vidly.utils.ConnectivityObserver
import com.fikriaja.vidly.utils.VidlyLog
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    companion object {
        val BOTTOM_BAR_HEIGHT = 64.dp
    }

    @Inject lateinit var connectivityObserver: ConnectivityObserver
    @Inject lateinit var miniPlayerManager: MiniPlayerManager
    // FEATURE (Biometric lock)
    @Inject lateinit var preferencesManager: com.fikriaja.vidly.data.local.PreferencesManager
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Handle permission result if needed
    }

    private val mainViewModel: MainViewModel by viewModels()
    @OptIn(UnstableApi::class)
    private val playerViewModel: com.fikriaja.vidly.ui.screens.player.PlayerViewModel by viewModels()
    private val updateViewModel: UpdateViewModel by viewModels()

    private val pendingDeepLink = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        pendingDeepLink.value = intent

        // Safety timeout for SplashScreen: don't hang for more than 3 seconds
        val startTime = System.currentTimeMillis()
        splashScreen.setKeepOnScreenCondition {
            val elapsed = System.currentTimeMillis() - startTime
            val statusKnown = mainViewModel.isOnboardingCompleted.value != null
            !statusKnown && elapsed < 3000
        }

        // Local Network Protection check (Android 16+ / API 36+)
        if (Build.VERSION.SDK_INT >= 36) {
            val permission = if (Build.VERSION.SDK_INT >= 37) {
                "android.permission.ACCESS_LOCAL_NETWORK"
            } else {
                Manifest.permission.NEARBY_WIFI_DEVICES
            }

            try {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    // Only request if not previously denied in this session or check rationale
                    if (shouldShowRequestPermissionRationale(permission)) {
                        VidlyLog.d("MainActivity", "Local Network permission rationale needed.")
                        // For simplicity in startup, we just launch, but a dialog would be better
                    }
                    requestPermissionLauncher.launch(permission)
                }
            } catch (e: Exception) {
                VidlyLog.e("MainActivity", "Failed to request local network permission", e)
            }
        }

        // Observe critical events
        lifecycleScope.launch {
            playerViewModel.sleepTimerManager.timerFinishedEvent.collectLatest {
                if (playerViewModel.sleepTimerManager.shouldCloseApp.value) {
                    finishAndRemoveTask()
                }
            }
        }

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val isBackgroundPlayEnabled by mainViewModel.isBackgroundPlayEnabled.collectAsStateWithLifecycle()
            val isOnboardingCompleted by mainViewModel.isOnboardingCompleted.collectAsStateWithLifecycle()

            if (isOnboardingCompleted == null) return@setContent

            // FEATURE (Biometric lock): gate the entire app behind biometric /
            // device-credential authentication. The lock re-engages whenever the
            // app leaves the foreground.
            val isAppLockEnabled by preferencesManager.isAppLockEnabled.collectAsStateWithLifecycle(initialValue = false)
            var isUnlocked by rememberSaveable { mutableStateOf(false) }
            val lockLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            DisposableEffect(lockLifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                        isUnlocked = false
                    }
                }
                lockLifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lockLifecycleOwner.lifecycle.removeObserver(observer) }
            }
            val showLock = isAppLockEnabled && !isUnlocked
            if (showLock) {
                VidlyTheme(
                    darkTheme = darkTheme,
                    isDynamicColorEnabled = mainViewModel.isDynamicColorEnabled.collectAsStateWithLifecycle().value
                ) {
                    LockScreen(onUnlock = { showBiometricPrompt { isUnlocked = true } })
                }
                return@setContent
            }

            val startDestination = if (isOnboardingCompleted == true) Destination.Home else Destination.Onboarding

            // Background Play MediaSession Connection
            if (isBackgroundPlayEnabled) {
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }

                DisposableEffect(Unit) {
                    val sessionToken = SessionToken(this@MainActivity, android.content.ComponentName(this@MainActivity, PlaybackService::class.java))
                    val controllerFuture = MediaController.Builder(this@MainActivity, sessionToken).buildAsync()
                    controllerFuture.addListener({}, MoreExecutors.directExecutor())
                    onDispose {
                        MediaController.releaseFuture(controllerFuture)
                    }
                }
            }

            VidlyTheme(
                darkTheme = darkTheme,
                isDynamicColorEnabled = mainViewModel.isDynamicColorEnabled.collectAsStateWithLifecycle().value
            ) {
                val navController = rememberNavController()
                val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()

                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                LaunchedEffect(navController, isOnboardingCompleted, lifecycleOwner) {
                    if (isOnboardingCompleted == true) {
                        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                            pendingDeepLink.collectLatest { intent ->
                                if (intent != null) {
                                    handleDeepLink(intent, navController)
                                    pendingDeepLink.value = null
                                }
                            }
                        }
                    }
                }

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                val playerVisibility by miniPlayerManager.visibilityState.collectAsStateWithLifecycle()
                val isExpanded = playerVisibility == com.fikriaja.vidly.ui.screens.player.MiniPlayerVisibility.Expanded

                val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
                val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

                LaunchedEffect(isExpanded) {
                    if (isExpanded) {
                        focusManager.clearFocus(force = true)
                        keyboardController?.hide()
                    }
                }

                val currentVideo by miniPlayerManager.currentVideo.collectAsStateWithLifecycle()
                
                androidx.activity.compose.BackHandler(enabled = isExpanded) {
                    currentVideo?.let { miniPlayerManager.minimize(it) } ?: miniPlayerManager.close()
                }

                val isIncognitoMode by mainViewModel.isIncognitoMode.collectAsStateWithLifecycle()
                val isOffline by mainViewModel.isOffline.collectAsStateWithLifecycle()

                val currentScreen = remember<Destination?>(currentRoute) { currentRoute.toDestination() }
                val isMainRoute = currentScreen?.isTopLevel == true
                val isOnboarding = currentScreen is Destination.Onboarding
                
                LaunchedEffect(currentRoute) {
                    val isPlayer = currentRoute?.contains("Player") == true
                    mainViewModel.setPlayerScreen(isPlayer)
                    
                    if (isMainRoute) {
                        mainViewModel.setBarsVisibility(true)
                    } else if (isOnboarding || isPlayer) {
                        mainViewModel.setBarsVisibility(false)
                    }
                }

                val showBars by remember(isMainRoute, isOnboarding, uiState.isInPipMode) {
                    derivedStateOf { isMainRoute && !uiState.isInPipMode && !isOnboarding }
                }

                val showTopBarActual by remember(showBars, currentScreen) {
                    derivedStateOf { showBars && currentScreen !is Destination.Search }
                }
                
                val barsVisibilityProgress by animateFloatAsState(
                    targetValue = if (showBars && uiState.isBarsVisible) 1f else 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "BarsVisibility"
                )

                DisposableEffect(Unit) {
                    val consumer = Consumer<Configuration> {
                        mainViewModel.setPipMode(isInPictureInPictureMode)
                    }
                    addOnConfigurationChangedListener(consumer)
                    onDispose {
                        removeOnConfigurationChangedListener(consumer)
                    }
                }

                DisposableEffect(playerViewModel.player, isExpanded) {
                    val listener = object : androidx.media3.common.Player.Listener {
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            if (isPlaying && isExpanded) {
                                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                        }
                    }
                    playerViewModel.player.addListener(listener)
                    if (playerViewModel.player.isPlaying && isExpanded) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    
                    onDispose {
                        playerViewModel.player.removeListener(listener)
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        if (showTopBarActual) {
                            val barsProgress = barsVisibilityProgress
                            var heightPx by remember { mutableFloatStateOf(0f) }
                            
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { heightPx = it.height.toFloat() }
                                    .graphicsLayer {
                                        translationY = -heightPx * (1f - barsProgress)
                                        alpha = barsProgress
                                    }
                            ) {
                                GlassSurface(
                                    tonalElevation = 3.dp,
                                    border = null,
                                    containerColor = MaterialTheme.colorScheme.surface
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                                        RestorationBanner(isOffline)
                                        if (uiState.isBarsVisible || barsProgress > 0f) {
                                            Box(modifier = Modifier
                                                .fillMaxWidth()
                                                .height(BOTTOM_BAR_HEIGHT)
                                            ) {
                                                VidlyTopAppBar(
                                                    isIncognitoMode = isIncognitoMode,
                                                    currentRoute = currentRoute,
                                                    navController = navController,
                                                    mainViewModel = mainViewModel,
                                                    updateViewModel = updateViewModel
                                                )
                                            }
                                        }
                                        HorizontalDivider(thickness = 0.5.dp)
                                    }
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    val barsProgress = barsVisibilityProgress
                    val topPadding = innerPadding.calculateTopPadding()
                    val density = LocalDensity.current
                    val statusBarHeightPx = WindowInsets.statusBars.getTop(density).toFloat()

                    Box(modifier = Modifier
                        .fillMaxSize()
                        .padding(top = topPadding, bottom = innerPadding.calculateBottomPadding())
                        .graphicsLayer {
                            val topPaddingPx = topPadding.toPx()
                            if (topPaddingPx > 0) {
                                translationY = -(topPaddingPx - statusBarHeightPx) * (1f - barsProgress)
                            }
                        }
                    ) {
                        NavGraph(
                            navController = navController,
                            startDestination = startDestination,
                            onBarsVisibilityChange = { mainViewModel.setBarsVisibility(it) }
                        )

                        if (showBars || barsVisibilityProgress > 0f) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 24.dp, vertical = 20.dp)
                                    .navigationBarsPadding()
                                    .graphicsLayer {
                                        translationY = 100.dp.toPx() * (1f - barsVisibilityProgress)
                                        alpha = barsVisibilityProgress
                                    }
                            ) {
                                GlassSurface(
                                    modifier = Modifier
                                        .widthIn(max = 500.dp)
                                        .fillMaxWidth()
                                        .height(MainActivity.BOTTOM_BAR_HEIGHT),
                                    shape = RoundedCornerShape(32.dp),
                                    shadowElevation = 16.dp,
                                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                                ) {
                                    VidlyBottomBar(navController = navController)
                                }
                            }
                        }
                    }
                }

                PlayerOverlay(
                    isExpanded = isExpanded,
                    currentVideo = currentVideo,
                    bottomBarHeight = if (showBars) BOTTOM_BAR_HEIGHT * barsVisibilityProgress else 0.dp,
                    isIncognito = isIncognitoMode,
                    viewModel = playerViewModel,
                    navController = navController,
                    onClose = { miniPlayerManager.close { playerViewModel.stopPlayback() } },
                    onMaximize = { miniPlayerManager.maximize() },
                    onMinimize = { currentVideo?.let { miniPlayerManager.minimize(it) } },
                    onChannelClick = { url ->
                        currentVideo?.let { miniPlayerManager.minimize(it) }
                        navController.navigate(Destination.Channel(url))
                    },
                    onVideoClick = { playerViewModel.loadVideo(it) },
                    onAddToPlaylistClick = { mainViewModel.showPlaylistSelection(it) },
                    content = {}
                )

                // Global Offline Notification
                var showBanner by remember { mutableStateOf(false) }
                LaunchedEffect(isOffline) {
                    if (isOffline) {
                        showBanner = true
                        kotlinx.coroutines.delay(5000L)
                        showBanner = false
                    } else showBanner = false
                }

                val isMiniPlayerActive = playerVisibility == com.fikriaja.vidly.ui.screens.player.MiniPlayerVisibility.Minimized
                Box(modifier = Modifier.fillMaxSize().zIndex(200f)) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = if (showBars) BOTTOM_BAR_HEIGHT + 24.dp else 16.dp)
                            .then(if (isMiniPlayerActive) Modifier.padding(bottom = 80.dp) else Modifier)
                            .navigationBarsPadding(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        OfflineBottomBanner(visible = showBanner, onNavigateToDownloads = {
                            navController.navigate(Destination.Downloads) { launchSingleTop = true }
                        })
                    }
                }

                // Global Offline Dialog
                val showOfflineDialog by mainViewModel.showOfflineDialog.collectAsStateWithLifecycle()
                if (showOfflineDialog) {
                    AlertDialog(
                        onDismissRequest = { mainViewModel.dismissOfflineDialog() },
                        icon = { Icon(Icons.Default.WifiOff, null) },
                        title = { Text(stringResource(R.string.no_internet)) },
                        text = { Text(stringResource(R.string.offline_dialog_text)) },
                        confirmButton = {
                            Button(onClick = { 
                                mainViewModel.dismissOfflineDialog()
                                navController.navigate(Destination.Downloads) { launchSingleTop = true }
                            }) { Text(stringResource(R.string.go_to_downloads)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { mainViewModel.dismissOfflineDialog() }) { Text(stringResource(R.string.close)) }
                        }
                    )
                }

                // Global Playlist Selection Sheet
                val playlistSelectionState by mainViewModel.playlistSelectionState.collectAsStateWithLifecycle()
                val localPlaylists by mainViewModel.localPlaylists.collectAsStateWithLifecycle()
                
                if (playlistSelectionState.isVisible) {
                    var showCreateDialog by remember { mutableStateOf(false) }
                    
                    com.fikriaja.vidly.ui.components.AddToPlaylistSheet(
                        playlists = localPlaylists,
                        playlistsWithVideo = playlistSelectionState.playlistsWithVideo,
                        onDismiss = { mainViewModel.hidePlaylistSelection() },
                        onPlaylistSelected = { playlist ->
                            playlistSelectionState.video?.let { video ->
                                mainViewModel.addVideoToPlaylist(playlist.id, video)
                            }
                        },
                        onCreateNewPlaylist = { showCreateDialog = true }
                    )

                    if (showCreateDialog) {
                        var name by remember { mutableStateOf("") }
                        AlertDialog(
                            onDismissRequest = { showCreateDialog = false },
                            title = { Text("Create New Playlist") },
                            text = {
                                TextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    placeholder = { Text("Playlist name") },
                                    singleLine = true
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (name.isNotBlank()) mainViewModel.createLocalPlaylist(name)
                                        showCreateDialog = false
                                    }
                                ) { Text("Create") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showCreateDialog = false }) { Text("Cancel") }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val isInPictureInPictureMode = isInPictureInPictureMode
        val isBackgroundPlayEnabled = mainViewModel.isBackgroundPlayEnabled.value
        if (!isInPictureInPictureMode && !isChangingConfigurations && !isBackgroundPlayEnabled) {
            playerViewModel.player.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            playerViewModel.player.stop()
            playerViewModel.player.clearMediaItems()
            miniPlayerManager.clear()
            stopService(Intent(this, PlaybackService::class.java))
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        mainViewModel.setPipMode(isInPictureInPictureMode)
        if (!isInPictureInPictureMode && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) {
            playerViewModel.player.pause()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (mainViewModel.isPipEnabled.value && playerViewModel.player.isPlaying) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink.value = intent
    }

    private fun handleDeepLink(intent: Intent, navController: NavHostController) {
        if (intent.getBooleanExtra("OPEN_PLAYER", false)) {
            miniPlayerManager.maximize()
            intent.removeExtra("OPEN_PLAYER")
        }

        val data: Uri = intent.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        val url = data.toString()
        val videoId = com.fikriaja.vidly.utils.VideoUtils.extractVideoId(url)
        val playlistId = com.fikriaja.vidly.utils.VideoUtils.extractPlaylistId(url)
        when {
            url.contains("/playlist?list=") || url.contains("&list=") -> {
                if (playlistId.isNotBlank()) navController.navigate(Destination.Playlist(playlistId)) { launchSingleTop = true }
            }
            url.contains("/channel/") || url.contains("/c/") || url.contains("/user/") || url.contains("/@") -> {
                navController.navigate(Destination.Channel(url)) { launchSingleTop = true }
            }
            url.contains("/results?search_query=") || url.contains("/results?q=") -> {
                val query = data.getQueryParameter("search_query") ?: data.getQueryParameter("q")
                if (!query.isNullOrBlank()) navController.navigate(Destination.Search(query)) { launchSingleTop = true }
            }
            videoId.isNotBlank() -> playerViewModel.loadVideo(VideoItem(id = videoId, title = "Loading...", thumbnailUrl = "", uploaderName = "", uploaderUrl = null, viewCount = 0, uploadDate = null, duration = 0))
        }
        intent.action = null
    }

    /**
     * FEATURE (Biometric lock): launches a BiometricPrompt (fingerprint / face /
     * device PIN). Falls back to unlocking when the device has no usable
     * authenticator, so the user can never be locked out of their own app.
     */
    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val manager = BiometricManager.from(this)
        val authenticators = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
        val canAuth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(authenticators)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            VidlyLog.w("MainActivity", "App lock enabled but no authenticator available (code=$canAuth); unlocking")
            onSuccess()
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.app_name))
            .setSubtitle("Verify your identity to continue")
            .setAllowedAuthenticators(authenticators)
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }
                // onAuthenticationError: user cancelled or too many attempts â€”
                // the LockScreen's "Unlock" button can retry the prompt.
            }
        )
        prompt.authenticate(promptInfo)
    }
}

/**
 * FEATURE (Biometric lock): full-screen lock gate shown on launch and whenever
 * the app returns from the background while the lock is enabled.
 */
@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    // Show the system biometric prompt as soon as the lock appears
    androidx.compose.runtime.LaunchedEffect(Unit) { onUnlock() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Vidly is locked",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Verify your identity to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onUnlock) {
                Text("Unlock")
            }
        }
    }
}

@Composable
fun VidlyBottomBar(navController: androidx.navigation.NavHostController) {
    val items = listOf(
        Triple(Destination.Home, Icons.Default.Home, stringResource(R.string.tab_for_you)),
        Triple(Destination.Shorts, Icons.Default.PlayArrow, "Shorts"),
        Triple(Destination.Subscriptions, Icons.Default.Subscriptions, stringResource(R.string.subscriptions)),
        Triple(Destination.Search(""), Icons.Default.Search, stringResource(R.string.search)),
        Triple(Destination.Library, Icons.Default.LibraryMusic, stringResource(R.string.library))
    )
    
    NavigationBar(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        
        items.forEach { (destination, icon, label) ->
            val isSelected = currentDestination?.hierarchy?.any { 
                it.route?.contains(destination.routeRoot, ignoreCase = true) == true 
            } == true
            
            NavigationBarItem(
                icon = { 
                    Icon(
                        imageVector = icon, 
                        contentDescription = null, 
                        modifier = Modifier.size(22.dp)
                    ) 
                },
                label = { 
                    Text(
                        text = label, 
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    ) 
                },
                selected = isSelected,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    indicatorColor = Color.Transparent // Modern transparent indicator
                ),
                onClick = {
                    if (!isSelected) {
                        navController.navigate(destination) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    }
}
