package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.MediaKind
import com.example.model.VirtualCamConstants
import com.example.overlay.FloatingControlService
import com.example.ui.VirtualCamViewModel
import com.example.ui.screens.NotesScreen
import com.example.ui.screens.ScopesScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.VirtualCamTheme

private enum class VirtualCamTab(
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    STUDIO(
        labelRes = R.string.nav_studio,
        selectedIcon = Icons.Filled.Tune,
        unselectedIcon = Icons.Outlined.Tune,
        testTag = "nav_studio"
    ),
    SCOPES(
        labelRes = R.string.nav_scopes,
        selectedIcon = Icons.Filled.Apps,
        unselectedIcon = Icons.Outlined.Apps,
        testTag = "nav_scopes"
    ),
    NOTES(
        labelRes = R.string.nav_notes,
        selectedIcon = Icons.AutoMirrored.Filled.MenuBook,
        unselectedIcon = Icons.AutoMirrored.Outlined.MenuBook,
        testTag = "nav_notes"
    )
}

class MainActivity : ComponentActivity() {

    private val viewModel: VirtualCamViewModel by viewModels()

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.importMedia(uri, MediaKind.VIDEO)
        }
    }

    private val pickPhotoLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.importMedia(uri, MediaKind.IMAGE)
        }
    }

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importMedia(uri, null)
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshStatus()
        if (Settings.canDrawOverlays(this)) {
            startFloatingOverlayWithNotificationCheck()
        } else {
            viewModel.showFeedback("Display over other apps permission not granted")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Start floating overlay regardless; foreground notification shows if granted
        if (Settings.canDrawOverlays(this)) {
            FloatingControlService.start(this)
        }
    }

    private val configBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            viewModel.refreshStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ContextCompat.registerReceiver(
            this,
            configBroadcastReceiver,
            IntentFilter(VirtualCamConstants.ACTION_CONFIG_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            VirtualCamTheme(darkTheme = true) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                VirtualCamAppShell(
                    viewModel = viewModel,
                    feedbackMessage = uiState.feedbackMessage,
                    onPickVideo = {
                        pickVideoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    onPickPhoto = {
                        pickPhotoLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onBrowseFiles = {
                        openDocumentLauncher.launch(arrayOf("video/*", "image/*"))
                    },
                    onRequestOverlayPermission = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        overlayPermissionLauncher.launch(intent)
                    },
                    onToggleFloatingOverlay = { enable ->
                        if (enable) {
                            if (Settings.canDrawOverlays(this)) {
                                startFloatingOverlayWithNotificationCheck()
                            } else {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                )
                                overlayPermissionLauncher.launch(intent)
                            }
                        } else {
                            FloatingControlService.stop(this)
                            viewModel.refreshStatus()
                        }
                    }
                )
            }
        }
    }

    private fun startFloatingOverlayWithNotificationCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            FloatingControlService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStatus()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(configBroadcastReceiver) }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VirtualCamAppShell(
    viewModel: VirtualCamViewModel,
    feedbackMessage: String?,
    onPickVideo: () -> Unit,
    onPickPhoto: () -> Unit,
    onBrowseFiles: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onToggleFloatingOverlay: (Boolean) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var currentTab by rememberSaveable { mutableStateOf(VirtualCamTab.STUDIO) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(feedbackMessage) {
        if (!feedbackMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(feedbackMessage)
            viewModel.dismissFeedback()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 720.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                if (!useNavigationRail) {
                    NavigationBar(
                        modifier = Modifier.navigationBarsPadding(),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        VirtualCamTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        VirtualCamTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = stringResource(tab.labelRes)
                                    )
                                },
                                label = { Text(stringResource(tab.labelRes)) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (currentTab) {
                        VirtualCamTab.STUDIO -> StudioScreen(
                            state = uiState,
                            onPickVideo = onPickVideo,
                            onPickPhoto = onPickPhoto,
                            onBrowseFiles = onBrowseFiles,
                            onClearMedia = viewModel::clearMedia,
                            onToggleEnabled = viewModel::setEnabled,
                            onToggleQuickBypass = viewModel::toggleQuickBypass,
                            onToggleAudio = viewModel::setAudioEnabled,
                            onToggleMic = viewModel::setMicEnabled,
                            onToggleLoop = viewModel::setLoopVideo,
                            onToggleMirror = viewModel::setMirrorHorizontal,
                            onCycleRotation = viewModel::cycleRotation,
                            onTogglePreviewPlaying = viewModel::togglePreviewPlaying,
                            onRequestOverlayPermission = onRequestOverlayPermission,
                            onToggleFloatingOverlay = onToggleFloatingOverlay
                        )

                        VirtualCamTab.SCOPES -> ScopesScreen(
                            config = uiState.config,
                            onToggleAllowAllScoped = viewModel::setAllowAllScopedApps,
                            onToggleAutoApplyPresets = viewModel::setAutoApplyPresets,
                            onToggleTargetApp = viewModel::toggleTargetApp,
                            onSavePreset = viewModel::saveCurrentAsPreset,
                            onApplyPreset = viewModel::applyPreset,
                            onDeletePreset = viewModel::deletePreset,
                            onAddCustomPackage = viewModel::addCustomTargetApp,
                            onRemoveCustomPackage = viewModel::removeCustomTargetApp
                        )

                        VirtualCamTab.NOTES -> NotesScreen(
                            state = uiState,
                            onToggleUseRootLogs = viewModel::setUseRootLogs,
                            onRefreshLogs = { viewModel.refreshLogs() },
                            onClearLogs = viewModel::clearLogs,
                            onLogsCopied = { viewModel.showFeedback("Copied diagnostic logs to clipboard") }
                        )
                    }
                }
            }
        }
    }
}
