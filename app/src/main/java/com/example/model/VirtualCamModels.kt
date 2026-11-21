package com.example.model

enum class MediaKind(val wireValue: String) {
    NONE("none"),
    VIDEO("video"),
    IMAGE("image");

    companion object {
        fun fromWire(value: String?): MediaKind =
            entries.firstOrNull { it.wireValue == value } ?: NONE
    }
}

data class TargetAppScope(
    val packageName: String,
    val displayName: String,
    val category: String,
    val isEnabled: Boolean,
    val isBuiltIn: Boolean = true
)

data class SelectedMediaInfo(
    val kind: MediaKind = MediaKind.NONE,
    val displayName: String = "",
    val localFilePath: String = "",
    val sourceUriString: String = "",
    val mimeType: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val updatedAtMs: Long = 0L
) {
    val hasMedia: Boolean
        get() = kind != MediaKind.NONE && localFilePath.isNotBlank()
}

data class VirtualCamConfig(
    val enabled: Boolean = false,
    val forceBypass: Boolean = false,
    val audioEnabled: Boolean = true,
    val micEnabled: Boolean = true,
    val loopVideo: Boolean = true,
    val mirrorHorizontal: Boolean = false,
    val rotationDegrees: Int = 0,
    val allowAllScopedApps: Boolean = true,
    val overlayRunning: Boolean = false,
    val selectedMedia: SelectedMediaInfo = SelectedMediaInfo(),
    val targetApps: List<TargetAppScope> = VirtualCamConstants.defaultTargetApps,
    val worldReadableActive: Boolean = false,
    val lsposedActive: Boolean = false,
    val xposedApiVersion: Int = 0
) {
    val isEffectivelyArmed: Boolean
        get() = enabled && !forceBypass && selectedMedia.hasMedia
}

object VirtualCamConstants {
    const val MODULE_PACKAGE = "com.aistudio.virtualcam.xkpzqm"
    const val PREFS_NAME = "virtualcam_prefs"
    const val PROVIDER_AUTHORITY = "$MODULE_PACKAGE.provider"
    const val ACTIVE_MEDIA_URI = "content://$PROVIDER_AUTHORITY/active_media"
    const val ACTIVE_CONFIG_URI = "content://$PROVIDER_AUTHORITY/config"
    const val ACTION_CONFIG_CHANGED = "$MODULE_PACKAGE.ACTION_CONFIG_CHANGED"

    const val KEY_ENABLED = "enabled"
    const val KEY_FORCE_BYPASS = "force_bypass"
    const val KEY_AUDIO_ENABLED = "audio_enabled"
    const val KEY_MIC_ENABLED = "mic_enabled"
    const val KEY_LOOP_VIDEO = "loop_video"
    const val KEY_MIRROR_HORIZONTAL = "mirror_horizontal"
    const val KEY_ROTATION_DEGREES = "rotation_degrees"
    const val KEY_ALLOW_ALL_SCOPED = "allow_all_scoped_apps"
    const val KEY_OVERLAY_RUNNING = "overlay_running"
    const val KEY_MEDIA_KIND = "media_kind"
    const val KEY_MEDIA_NAME = "media_name"
    const val KEY_MEDIA_PATH = "media_path"
    const val KEY_MEDIA_URI = "media_uri"
    const val KEY_MEDIA_MIME = "media_mime"
    const val KEY_MEDIA_WIDTH = "media_width"
    const val KEY_MEDIA_HEIGHT = "media_height"
    const val KEY_MEDIA_DURATION = "media_duration_ms"
    const val KEY_MEDIA_SIZE = "media_size_bytes"
    const val KEY_MEDIA_UPDATED_AT = "media_updated_at_ms"
    const val KEY_ENABLED_PACKAGES = "enabled_target_packages"
    const val KEY_CUSTOM_PACKAGES = "custom_target_packages"

    val defaultTargetApps = listOf(
        TargetAppScope("com.whatsapp", "WhatsApp", "Messaging", isEnabled = true),
        TargetAppScope("com.imo.android.imoim", "imo", "Video Call", isEnabled = true),
        TargetAppScope("omegle.tv", "OmeTV", "Video Chat", isEnabled = true),
        TargetAppScope("com.facebook.orca", "Messenger", "Messaging", isEnabled = true),
        TargetAppScope("com.instagram.android", "Instagram", "Social", isEnabled = true),
        TargetAppScope("org.telegram.messenger", "Telegram", "Messaging", isEnabled = true),
        TargetAppScope("com.snapchat.android", "Snapchat", "Social", isEnabled = false),
        TargetAppScope("com.discord", "Discord", "Voice & Video", isEnabled = false),
        TargetAppScope("com.google.android.apps.tachyon", "Google Meet", "Conferencing", isEnabled = false),
        TargetAppScope("com.android.camera", "AOSP Camera", "System", isEnabled = true),
        TargetAppScope("com.google.android.GoogleCamera", "Pixel Camera", "System", isEnabled = true)
    )
}
