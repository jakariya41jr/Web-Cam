package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.model.MediaKind
import com.example.model.VirtualCamConfig
import com.example.ui.VirtualCamUiState
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun StudioScreen(
    state: VirtualCamUiState,
    onPickVideo: () -> Unit,
    onPickPhoto: () -> Unit,
    onBrowseFiles: () -> Unit,
    onClearMedia: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleQuickBypass: () -> Unit,
    onToggleAudio: (Boolean) -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onToggleLoop: (Boolean) -> Unit,
    onToggleMirror: (Boolean) -> Unit,
    onCycleRotation: () -> Unit,
    onTogglePreviewPlaying: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onToggleFloatingOverlay: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = state.config
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            HookStatusBanner(
                config = config,
                canDrawOverlays = state.canDrawOverlays,
                onToggleEnabled = onToggleEnabled,
                onToggleQuickBypass = onToggleQuickBypass
            )

            ViewfinderPreviewCard(
                config = config,
                isImporting = state.isImporting,
                isPreviewPlaying = state.isPreviewPlaying,
                onTogglePreviewPlaying = onTogglePreviewPlaying
            )

            if (config.selectedMedia.hasMedia && config.selectedMedia.kind == MediaKind.VIDEO) {
                VideoPreCheckCard(config = config)
            }

            MediaPickerDeck(
                hasMedia = config.selectedMedia.hasMedia,
                isImporting = state.isImporting,
                onPickVideo = onPickVideo,
                onPickPhoto = onPickPhoto,
                onBrowseFiles = onBrowseFiles,
                onClearMedia = onClearMedia
            )

            FloatingOverlayCard(
                canDrawOverlays = state.canDrawOverlays,
                overlayRunning = config.overlayRunning,
                onRequestOverlayPermission = onRequestOverlayPermission,
                onToggleFloatingOverlay = onToggleFloatingOverlay
            )

            PipelineControlsCard(
                config = config,
                onToggleAudio = onToggleAudio,
                onToggleMic = onToggleMic,
                onToggleLoop = onToggleLoop,
                onToggleMirror = onToggleMirror,
                onCycleRotation = onCycleRotation
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HookStatusBanner(
    config: VirtualCamConfig,
    canDrawOverlays: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleQuickBypass: () -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when {
            config.forceBypass -> MaterialTheme.colorScheme.tertiary
            config.isEffectivelyArmed -> MaterialTheme.colorScheme.primary
            config.enabled -> MaterialTheme.colorScheme.secondary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "statusColor"
    )

    val statusTitle = when {
        config.forceBypass -> "BYPASS ACTIVE (ORIGINAL CAMERA)"
        config.isEffectivelyArmed -> "VIRTUAL FEED ARMED"
        config.enabled -> "AWAITING MEDIA"
        else -> "INJECTION STANDBY"
    }

    val statusSubtitle = when {
        config.forceBypass -> "Passing real hardware camera frames cleanly"
        config.isEffectivelyArmed -> "Injecting ${config.selectedMedia.kind.wireValue} into Camera1 / Camera2 / ImageReader"
        config.enabled -> "Select a video or photo below to activate stream"
        else -> "Toggle Virtual Camera on to intercept scoped apps"
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Column {
                        Text(
                            text = statusTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = statusSubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Switch(
                    checked = config.enabled,
                    onCheckedChange = onToggleEnabled,
                    modifier = Modifier.testTag("master_enable_switch"),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // Live Status Indicator Strip
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.testTag("live_status_strip")
            ) {
                TelemetryPill(
                    label = if (config.isEffectivelyArmed) "MODE: VIRTUAL" else "MODE: BYPASS"
                )
                TelemetryPill(
                    label = when {
                        config.overlayRunning -> "OVERLAY: ACTIVE"
                        canDrawOverlays -> "OVERLAY: READY"
                        else -> "OVERLAY: NO PERMISSION"
                    }
                )
                TelemetryPill(
                    label = if (config.audioEnabled) "VID SOUND: ON" else "VID SOUND: OFF"
                )
                TelemetryPill(
                    label = if (config.micEnabled) "MAIN MIC: ON" else "MAIN MIC: MUTED"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadgeChip(
                    icon = if (config.lsposedActive) Icons.Default.Shield else Icons.Default.WarningAmber,
                    label = if (config.lsposedActive) {
                        "LSPosed API v${config.xposedApiVersion}"
                    } else {
                        "Preview Mode (LSPosed Inactive)"
                    },
                    tint = if (config.lsposedActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    },
                    modifier = Modifier.weight(1f)
                )

                FilledTonalButton(
                    onClick = onToggleQuickBypass,
                    enabled = config.enabled,
                    modifier = Modifier.testTag("quick_bypass_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (config.forceBypass) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        contentColor = if (config.forceBypass) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "Quick bypass hardware camera",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (config.forceBypass) "Resume" else stringResource(R.string.action_quick_bypass),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoPreCheckCard(config: VirtualCamConfig) {
    val media = config.selectedMedia
    val warnings = remember(media) { media.buildPreCheckWarnings() }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("video_precheck_card")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (warnings.isEmpty()) {
                        Icons.Default.CheckCircleOutline
                    } else {
                        Icons.Default.WarningAmber
                    },
                    contentDescription = null,
                    tint = if (warnings.isEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                )
                Text(
                    text = "Video Stream Pre-Check",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (media.fps > 0f) {
                    TelemetryPill(label = String.format(Locale.US, "%.0f FPS", media.fps))
                }
                if (media.videoCodec.isNotBlank()) {
                    TelemetryPill(label = media.videoCodec.uppercase(Locale.US))
                }
                TelemetryPill(
                    label = if (media.hasAudioTrack) {
                        val rate = if (media.audioSampleRate > 0) " ${media.audioSampleRate / 1000}kHz" else ""
                        "AUDIO TRACK$rate"
                    } else {
                        "NO AUDIO TRACK"
                    }
                )
                if (media.videoRotationDegrees != 0) {
                    TelemetryPill(label = "META ROT ${media.videoRotationDegrees}°")
                }
            }

            if (warnings.isEmpty()) {
                Text(
                    text = "Optimal H.264/MP4 parameters detected for imo, WhatsApp, and OmeTV WebRTC pipelines.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                warnings.forEach { warning ->
                    val accent = if (warning.isSevere) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.tertiary
                    }
                    Surface(
                        color = accent.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = warning.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = accent,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = warning.detail,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingOverlayCard(
    canDrawOverlays: Boolean,
    overlayRunning: Boolean,
    onRequestOverlayPermission: () -> Unit,
    onToggleFloatingOverlay: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Column {
                        Text(
                            text = "Floating Quick-Switch Overlay",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (canDrawOverlays) {
                                "Switch Virtual/Real camera, Video Sound, and Mic live over calls"
                            } else {
                                "Requires Display Over Other Apps permission"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (canDrawOverlays) {
                    Switch(
                        checked = overlayRunning,
                        onCheckedChange = onToggleFloatingOverlay,
                        modifier = Modifier.testTag("overlay_toggle_switch")
                    )
                }
            }

            if (!canDrawOverlays) {
                Button(
                    onClick = onRequestOverlayPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("overlay_permission_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grant Display Over Other Apps")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViewfinderPreviewCard(
    config: VirtualCamConfig,
    isImporting: Boolean,
    isPreviewPlaying: Boolean,
    onTogglePreviewPlaying: () -> Unit
) {
    val media = config.selectedMedia
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(22.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF060911)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isImporting -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                            Text(
                                text = "Copying media to module cache...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    media.kind == MediaKind.VIDEO && media.hasMedia -> {
                        VideoFeedPreview(
                            filePath = media.localFilePath,
                            isPlaying = isPreviewPlaying,
                            audioEnabled = config.audioEnabled,
                            loopVideo = config.loopVideo,
                            mirrorHorizontal = config.mirrorHorizontal,
                            rotationDegrees = config.rotationDegrees
                        )
                    }

                    media.kind == MediaKind.IMAGE && media.hasMedia -> {
                        ImageFeedPreview(
                            filePath = media.localFilePath,
                            mirrorHorizontal = config.mirrorHorizontal,
                            rotationDegrees = config.rotationDegrees
                        )
                    }

                    else -> {
                        Image(
                            painter = painterResource(id = R.drawable.img_empty_viewfinder_1795267995405),
                            contentDescription = "Virtual camera calibration viewfinder",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Black.copy(alpha = 0.25f),
                                            Color.Black.copy(alpha = 0.72f)
                                        )
                                    )
                                )
                        )
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Videocam,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "NO ACTIVE CAMERA FEED",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Pick a video or photo below — no manual folder rename needed",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                        }
                    }
                }

                ViewfinderReticleOverlay(
                    accentColor = if (config.isEffectivelyArmed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f)
                    }
                )

                if (media.hasMedia && !isImporting) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.68f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                    ) {
                        val micTag = if (config.micEnabled) "MIC ON" else "MIC MUTE"
                        Text(
                            text = if (media.kind == MediaKind.VIDEO) {
                                val vidAudio = if (config.audioEnabled) "VID SOUND" else "VID MUTE"
                                "$vidAudio • $micTag"
                            } else {
                                "STILL FRAME • $micTag"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    if (media.kind == MediaKind.VIDEO) {
                        IconButton(
                            onClick = onTogglePreviewPlaying,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.65f))
                                .testTag("preview_play_toggle")
                        ) {
                            Icon(
                                imageVector = if (isPreviewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPreviewPlaying) "Pause preview" else "Play preview",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            if (media.hasMedia) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = media.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TelemetryPill(label = media.kind.name)
                        if (media.width > 0 && media.height > 0) {
                            TelemetryPill(label = "${media.width}×${media.height}")
                        }
                        if (media.durationMs > 0L) {
                            TelemetryPill(label = formatDuration(media.durationMs))
                        }
                        if (media.sizeBytes > 0L) {
                            TelemetryPill(label = formatFileSize(media.sizeBytes))
                        }
                        TelemetryPill(
                            label = if (config.worldReadableActive) "WORLD_READABLE" else "PROVIDER IPC"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoFeedPreview(
    filePath: String,
    isPlaying: Boolean,
    audioEnabled: Boolean,
    loopVideo: Boolean,
    mirrorHorizontal: Boolean,
    rotationDegrees: Int
) {
    var activePlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(audioEnabled, activePlayer) {
        val vol = if (audioEnabled) 1f else 0f
        runCatching { activePlayer?.setVolume(vol, vol) }
    }

    LaunchedEffect(loopVideo, activePlayer) {
        runCatching { activePlayer?.isLooping = loopVideo }
    }

    DisposableEffect(filePath) {
        onDispose {
            activePlayer = null
        }
    }

    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(Uri.fromFile(File(filePath)))
                setOnPreparedListener { mp ->
                    activePlayer = mp
                    mp.isLooping = loopVideo
                    val vol = if (audioEnabled) 1f else 0f
                    mp.setVolume(vol, vol)
                    if (isPlaying) start()
                }
            }
        },
        update = { view ->
            if (isPlaying && !view.isPlaying) {
                view.start()
            } else if (!isPlaying && view.isPlaying) {
                view.pause()
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = if (mirrorHorizontal) -1f else 1f
                rotationZ = rotationDegrees.toFloat()
            }
    )
}

@Composable
private fun ImageFeedPreview(
    filePath: String,
    mirrorHorizontal: Boolean,
    rotationDegrees: Int
) {
    var bitmap by remember(filePath) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(filePath) {
        bitmap = withContext(Dispatchers.IO) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(filePath, opts)
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Selected virtual camera photo preview",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = if (mirrorHorizontal) -1f else 1f
                    rotationZ = rotationDegrees.toFloat()
                }
        )
    } else {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun ViewfinderReticleOverlay(accentColor: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stroke = 2.dp.toPx()
        val corner = 18.dp.toPx()
        val pad = 10.dp.toPx()

        drawLine(accentColor, Offset(pad, pad), Offset(pad + corner, pad), stroke)
        drawLine(accentColor, Offset(pad, pad), Offset(pad, pad + corner), stroke)
        drawLine(accentColor, Offset(size.width - pad, pad), Offset(size.width - pad - corner, pad), stroke)
        drawLine(accentColor, Offset(size.width - pad, pad), Offset(size.width - pad, pad + corner), stroke)
        drawLine(accentColor, Offset(pad, size.height - pad), Offset(pad + corner, size.height - pad), stroke)
        drawLine(accentColor, Offset(pad, size.height - pad), Offset(pad, size.height - pad - corner), stroke)
        drawLine(accentColor, Offset(size.width - pad, size.height - pad), Offset(size.width - pad - corner, size.height - pad), stroke)
        drawLine(accentColor, Offset(size.width - pad, size.height - pad), Offset(size.width - pad, size.height - pad - corner), stroke)
    }
}

@Composable
private fun MediaPickerDeck(
    hasMedia: Boolean,
    isImporting: Boolean,
    onPickVideo: () -> Unit,
    onPickPhoto: () -> Unit,
    onBrowseFiles: () -> Unit,
    onClearMedia: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Media Source Picker",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Choose any video or photo from your gallery or file provider. VirtualCam automatically copies and bridges it across LSPosed scopes.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPickVideo,
                    enabled = !isImporting,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("pick_video_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_pick_video))
                }

                FilledTonalButton(
                    onClick = onPickPhoto,
                    enabled = !isImporting,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("pick_photo_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_pick_photo))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onBrowseFiles,
                    enabled = !isImporting,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("browse_files_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_pick_document))
                }

                if (hasMedia) {
                    OutlinedButton(
                        onClick = onClearMedia,
                        enabled = !isImporting,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("clear_media_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear selected media",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear")
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineControlsCard(
    config: VirtualCamConfig,
    onToggleAudio: (Boolean) -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onToggleLoop: (Boolean) -> Unit,
    onToggleMirror: (Boolean) -> Unit,
    onCycleRotation: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Audio Routing & Optics Pipeline",
                style = MaterialTheme.typography.titleMedium
            )

            ControlToggleRow(
                icon = if (config.audioEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                title = "Video Audio (Send to Call)",
                subtitle = if (config.audioEnabled) {
                    "Injects video sound directly into call AudioRecord stream"
                } else {
                    "Video audio muted (silent video feed)"
                },
                checked = config.audioEnabled,
                onCheckedChange = onToggleAudio,
                testTag = "audio_playback_switch"
            )

            ControlToggleRow(
                icon = if (config.micEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                title = "Main Device Microphone",
                subtitle = if (config.micEnabled) {
                    "Your phone microphone sound is active in calls"
                } else {
                    "Device microphone muted (only video audio if enabled)"
                },
                checked = config.micEnabled,
                onCheckedChange = onToggleMic,
                testTag = "main_mic_switch"
            )

            ControlToggleRow(
                icon = Icons.Default.Loop,
                title = "Loop Video Feed",
                subtitle = "Seamlessly restart video at end-of-stream",
                checked = config.loopVideo,
                onCheckedChange = onToggleLoop,
                testTag = "loop_video_switch"
            )

            ControlToggleRow(
                icon = Icons.Default.Flip,
                title = "Horizontal Mirror",
                subtitle = "Flip frames horizontally for front-facing camera parity",
                checked = config.mirrorHorizontal,
                onCheckedChange = onToggleMirror,
                testTag = "mirror_horizontal_switch"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Rotate90DegreesCw,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Column {
                        Text(
                            text = "Sensor Orientation",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Rotate injected frames before surface post",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                FilledTonalButton(
                    onClick = onCycleRotation,
                    modifier = Modifier.testTag("rotation_cycle_button")
                ) {
                    Text(
                        text = "${config.rotationDegrees}°",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun StatusBadgeChip(
    icon: ImageVector,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = tint.copy(alpha = 0.14f),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TelemetryPill(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
