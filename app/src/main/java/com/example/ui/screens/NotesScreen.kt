package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.VirtualCamConstants
import com.example.ui.VirtualCamUiState

@Composable
fun NotesScreen(
    state: VirtualCamUiState,
    onToggleUseRootLogs: (Boolean) -> Unit,
    onRefreshLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onLogsCopied: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config = state.config
    val scrollState = rememberScrollState()
    val context = LocalContext.current

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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            LogCollectorCard(
                logs = state.logs,
                isCollecting = state.isCollectingLogs,
                useRootLogs = state.useRootLogs,
                onToggleUseRootLogs = onToggleUseRootLogs,
                onRefreshLogs = onRefreshLogs,
                onClearLogs = onClearLogs,
                onCopyLogs = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(
                        ClipData.newPlainText("VirtualCam Logs", state.logs.joinToString("\n"))
                    )
                    onLogsCopied()
                }
            )

            NoteCard(
                icon = Icons.Default.CameraAlt,
                title = "Imo, WhatsApp & OmeTV Video Pipeline",
                tag = "VideoFramePipeline",
                body = "• MediaCodec + ImageWriter + Direct-Plane Fallback: Feeds MediaCodec YUV_420_888 frames through ImageWriter at 30fps, and if ImageWriter encounters 'Failed to connect to native window', automatically falls back to direct-ByteBuffer ImageReader.acquireLatestImage() SurfacePlane injection.\n• Deferred OutputConfiguration & SessionConfiguration: Hooks finalizeOutputConfigurations and multi-surface OutputConfiguration.getSurfaces() so WebRTC / Camera2Session never misses virtual frame replacement."
            )

            NoteCard(
                icon = Icons.Default.GraphicEq,
                title = "Independent Video Sound & Main Mic Routing",
                tag = "AudioRecord Hooks",
                body = "AudioHooks intercepts AudioRecord.startRecording() and read(byte[] / short[] / float[] / ByteBuffer) with soft-gain mixing (0.65x mic + 0.85x video audio, or 0.95x video audio when mic is muted) so video sound reaches imo/WhatsApp calls without acoustic echo or WebRTC clipping."
            )

            NoteCard(
                icon = Icons.Default.Layers,
                title = "Floating Quick-Switch Overlay & Safe Fallback",
                tag = "SYSTEM_ALERT_WINDOW",
                body = "Grant Display Over Other Apps to launch a draggable floating pill over active calls. Tap the pill to swap Virtual <-> Original/Bypass instantaneously without restarting the call, or expand it for live Video Audio and Main Mic toggles."
            )

            NoteCard(
                icon = Icons.Default.FolderSpecial,
                title = "Scoped Storage & Zero-Permission Media Picker",
                tag = "Android 11–15+",
                body = "Uses Android Photo Picker (PickVisualMedia) and Storage Access Framework (OpenDocument). Picked streams are copied into internal filesDir/virtual_cam_media/active_media.* and inspected for resolution, FPS, codec, and audio tracks."
            )

            NoteCard(
                icon = Icons.Default.Lock,
                title = "New XSharedPreferences & ContentProvider Bridge",
                tag = if (config.worldReadableActive) "WORLD_READABLE OK" else "FALLBACK MODE",
                body = "AndroidManifest declares xposedminversion=93 and xposedsharedprefs=true. MODE_WORLD_READABLE writes are proxied to hooked apps via XSharedPreferences, backed by VirtualCamMediaProvider (${VirtualCamConstants.ACTIVE_MEDIA_URI})."
            )

            NoteCard(
                icon = Icons.Default.Security,
                title = "SELinux & LSPosed Reinstall Checklist",
                tag = "Runtime",
                body = "After updating or reinstalling VirtualCam APK, force-stop target apps (WhatsApp, imo, OmeTV) once so LSPosed reloads the new APK path instead of logging a stale NoSuchFileException.\n" +
                    "Package: ${VirtualCamConstants.MODULE_PACKAGE}\n" +
                    "Prefs Mode: ${if (config.worldReadableActive) "MODE_WORLD_READABLE" else "MODE_PRIVATE (LSPosed inactive)"}"
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun LogCollectorCard(
    logs: List<String>,
    isCollecting: Boolean,
    useRootLogs: Boolean,
    onToggleUseRootLogs: (Boolean) -> Unit,
    onRefreshLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onCopyLogs: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("log_collector_card")
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
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "In-App Diagnostic Log Collector",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Async collector (never blocks camera/audio hooks)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isCollecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        text = "Include Root (su) Vector / LSPosed Logcat",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Optional: pulls recent VirtualCam & VectorLegacyBridge lines via su with a 3.5s timeout",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = useRootLogs,
                    onCheckedChange = onToggleUseRootLogs,
                    modifier = Modifier.testTag("root_logs_switch")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onRefreshLogs,
                    enabled = !isCollecting,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("refresh_logs_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Refresh")
                }

                OutlinedButton(
                    onClick = onCopyLogs,
                    enabled = logs.isNotEmpty(),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("copy_logs_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Copy")
                }

                TextButton(
                    onClick = onClearLogs,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("clear_logs_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF070B12))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (logs.isEmpty()) "(No logs collected yet)" else logs.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFFCBD5E1)
                )
            }
        }
    }
}

@Composable
private fun NoteCard(
    icon: ImageVector,
    title: String,
    tag: String,
    body: String
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = tag,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
