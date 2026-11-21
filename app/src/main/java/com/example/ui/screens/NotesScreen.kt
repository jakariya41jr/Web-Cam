package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.model.VirtualCamConfig
import com.example.model.VirtualCamConstants

@Composable
fun NotesScreen(
    config: VirtualCamConfig,
    modifier: Modifier = Modifier
) {
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
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            NoteCard(
                icon = Icons.Default.CameraAlt,
                title = "Imo Crash & WhatsApp/OmeTV Black Screen Fix",
                tag = "VideoFramePipeline",
                body = "• MediaCodec + ImageWriter Pipeline: Replaces fragile MediaPlayer.setSurface() on Camera2/ImageReader surfaces (which caused 'Video renderer fallback: null') with a dedicated MediaCodec YUV_420_888 decoder feeding ImageWriter at 30fps and Canvas preview surfaces.\n• SessionConfiguration Replacement: Rewrites API 28+ SessionConfiguration and CaptureRequest.Builder.addTarget(...) consistently with a per-device draining bridge surface so CameraCaptureSession never mismatches outputs or crashes Imo.\n• WebRTC Buffer Identity: Camera1.setPreviewCallbackWithBuffer mutates the app's registered NV21 byte[] in-place and resolves com.google.android.webview host processes (omegle.tv, imo)."
            )

            NoteCard(
                icon = Icons.Default.GraphicEq,
                title = "Independent Video Sound & Device Mic Routing",
                tag = "AudioRecord Hooks",
                body = "AudioHooks intercepts AudioRecord.read(...) inside scoped call apps. Toggle 'Main Device Microphone' off to zero out hardware mic samples cleanly, and toggle 'Video Audio (Send to Call)' on to decode and resample the selected video's audio track directly into the call stream without acoustic speaker echo."
            )

            NoteCard(
                icon = Icons.Default.Layers,
                title = "Floating Quick-Switch Overlay",
                tag = "SYSTEM_ALERT_WINDOW",
                body = "Grant Display Over Other Apps to launch a draggable floating pill over WhatsApp, Imo, or OmeTV. Tap the pill to toggle Virtual Camera vs. Real Camera instantaneously without restarting the call, or expand it for live Video Sound and Device Mic switches."
            )

            NoteCard(
                icon = Icons.Default.FolderSpecial,
                title = "Scoped Storage & Zero-Permission Media Picker",
                tag = "Android 11–15+",
                body = "Uses Android Photo Picker (PickVisualMedia) and Storage Access Framework (OpenDocument). Picked streams are copied into internal filesDir/virtual_cam_media/active_media.* so URI grants never expire across process restarts."
            )

            NoteCard(
                icon = Icons.Default.Lock,
                title = "New XSharedPreferences & ContentProvider Bridge",
                tag = if (config.worldReadableActive) "WORLD_READABLE OK" else "FALLBACK MODE",
                body = "AndroidManifest declares xposedminversion=93 and xposedsharedprefs=true. MODE_WORLD_READABLE writes are proxied to hooked apps via XSharedPreferences, backed by VirtualCamMediaProvider (${VirtualCamConstants.ACTIVE_MEDIA_URI})."
            )

            NoteCard(
                icon = Icons.Default.Security,
                title = "SELinux & LSPosed Scope Notes",
                tag = "Runtime",
                body = "Make sure target apps (WhatsApp, imo, OmeTV, System WebView if applicable) are checked in LSPosed Manager under Modules → VirtualCam.\n" +
                    "Package: ${VirtualCamConstants.MODULE_PACKAGE}\n" +
                    "Prefs Mode: ${if (config.worldReadableActive) "MODE_WORLD_READABLE" else "MODE_PRIVATE (LSPosed inactive)"}"
            )

            Spacer(modifier = Modifier.height(12.dp))
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
