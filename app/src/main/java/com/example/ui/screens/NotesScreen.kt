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
                icon = Icons.Default.FolderSpecial,
                title = "Scoped Storage & Zero-Permission Media Picker",
                tag = "Android 11–15+",
                body = "Instead of requesting deprecated READ_EXTERNAL_STORAGE or broad READ_MEDIA_* permissions, VirtualCam uses Android Photo Picker (PickVisualMedia) and Storage Access Framework (OpenDocument). Picked streams are copied into internal filesDir/virtual_cam_media/active_media.* so URI grants never expire across process restarts."
            )

            NoteCard(
                icon = Icons.Default.Lock,
                title = "New XSharedPreferences & ContentProvider Bridge",
                tag = if (config.worldReadableActive) "WORLD_READABLE OK" else "FALLBACK MODE",
                body = "AndroidManifest declares xposedminversion=93 and xposedsharedprefs=true. When LSPosed is active, MODE_WORLD_READABLE writes are transparently proxied to hooked apps via XSharedPreferences. Additionally, VirtualCamMediaProvider (${VirtualCamConstants.ACTIVE_MEDIA_URI}) serves read-only ParcelFileDescriptors across app sandboxes."
            )

            NoteCard(
                icon = Icons.Default.CameraAlt,
                title = "Camera1 & Camera2 / Surface Pipeline",
                tag = "Hook Targets",
                body = "• Camera1: Intercepts setPreviewDisplay, setPreviewTexture, startPreview, NV21 PreviewCallback, and takePicture JPEG callbacks.\n• Camera2: Wraps CameraManager.openCamera callbacks, redirects createCaptureSession / CaptureRequest.Builder.addTarget surfaces to VirtualFrameRenderer, and guards SessionConfiguration (API 28+) & OutputConfiguration (API 24+).\n• MediaRecorder & ImageReader: Tracks getSurface() targets and honors live audio (no-silent) and quick bypass switches."
            )

            NoteCard(
                icon = Icons.Default.Security,
                title = "SELinux, Zygisk & Package Visibility",
                tag = "Zygisk / LSPlant",
                body = "Under Android 11–15 SELinux (untrusted_app), hooked apps cannot directly open another app's /data/user/0/<pkg>/files path. Zygisk + LSPosed (LSPlant) executes hooks inside the target app's Dalvik/ART process, where New XSharedPreferences uses Binder IPC and ContentResolver streams the active media file descriptor safely."
            )

            NoteCard(
                icon = Icons.Default.Memory,
                title = "Quick Diagnostics",
                tag = "Runtime",
                body = "Package: ${VirtualCamConstants.MODULE_PACKAGE}\n" +
                    "Entry: com.example.xposed.VirtualCamHookEntry\n" +
                    "Prefs Mode: ${if (config.worldReadableActive) "MODE_WORLD_READABLE" else "MODE_PRIVATE (LSPosed inactive)"}\n" +
                    "Provider URI: ${VirtualCamConstants.ACTIVE_MEDIA_URI}"
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
