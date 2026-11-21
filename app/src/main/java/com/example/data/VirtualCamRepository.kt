package com.example.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.model.MediaKind
import com.example.model.SelectedMediaInfo
import com.example.model.TargetAppScope
import com.example.model.VirtualCamConfig
import com.example.model.VirtualCamConstants
import com.example.xposed.ModuleStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class VirtualCamRepository(private val context: Context) {

    private val mediaDirectory: File
        get() = File(context.filesDir, "virtual_cam_media").apply {
            if (!exists()) mkdirs()
        }

    /**
     * Opens SharedPreferences with Context.MODE_WORLD_READABLE when LSPosed (API 93+)
     * hooks ContextImpl.checkMode, and gracefully falls back to MODE_PRIVATE otherwise.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("WorldReadableFiles")
    private fun openPreferences(): Pair<SharedPreferences, Boolean> {
        return try {
            val prefs = context.getSharedPreferences(
                VirtualCamConstants.PREFS_NAME,
                Context.MODE_WORLD_READABLE
            )
            prefs to true
        } catch (_: SecurityException) {
            val prefs = context.getSharedPreferences(
                VirtualCamConstants.PREFS_NAME,
                Context.MODE_PRIVATE
            )
            prefs to false
        }
    }

    fun loadConfig(): VirtualCamConfig {
        val (prefs, worldReadable) = openPreferences()

        val mediaPath = prefs.getString(VirtualCamConstants.KEY_MEDIA_PATH, "").orEmpty()
        val rawKind = MediaKind.fromWire(prefs.getString(VirtualCamConstants.KEY_MEDIA_KIND, null))
        val validMediaExists = mediaPath.isNotBlank() && File(mediaPath).exists()

        val selectedMedia = if (validMediaExists && rawKind != MediaKind.NONE) {
            SelectedMediaInfo(
                kind = rawKind,
                displayName = prefs.getString(VirtualCamConstants.KEY_MEDIA_NAME, "Selected Media").orEmpty(),
                localFilePath = mediaPath,
                sourceUriString = prefs.getString(VirtualCamConstants.KEY_MEDIA_URI, "").orEmpty(),
                mimeType = prefs.getString(VirtualCamConstants.KEY_MEDIA_MIME, "").orEmpty(),
                width = prefs.getInt(VirtualCamConstants.KEY_MEDIA_WIDTH, 0),
                height = prefs.getInt(VirtualCamConstants.KEY_MEDIA_HEIGHT, 0),
                durationMs = prefs.getLong(VirtualCamConstants.KEY_MEDIA_DURATION, 0L),
                sizeBytes = prefs.getLong(VirtualCamConstants.KEY_MEDIA_SIZE, 0L),
                updatedAtMs = prefs.getLong(VirtualCamConstants.KEY_MEDIA_UPDATED_AT, 0L)
            )
        } else {
            SelectedMediaInfo()
        }

        val defaultEnabledSet = VirtualCamConstants.defaultTargetApps
            .filter { it.isEnabled }
            .map { it.packageName }
            .toSet()

        val enabledPackages = prefs.getStringSet(
            VirtualCamConstants.KEY_ENABLED_PACKAGES,
            defaultEnabledSet
        ) ?: defaultEnabledSet

        val customPackages = prefs.getStringSet(
            VirtualCamConstants.KEY_CUSTOM_PACKAGES,
            emptySet()
        ) ?: emptySet()

        val builtInTargets = VirtualCamConstants.defaultTargetApps.map { scope ->
            scope.copy(isEnabled = enabledPackages.contains(scope.packageName))
        }
        val builtInPkgNames = builtInTargets.map { it.packageName }.toSet()
        val customTargets = customPackages
            .filter { it.isNotBlank() && !builtInPkgNames.contains(it) }
            .sorted()
            .map { pkg ->
                TargetAppScope(
                    packageName = pkg,
                    displayName = pkg.substringAfterLast('.').replaceFirstChar { c -> c.uppercase() },
                    category = "Custom",
                    isEnabled = enabledPackages.contains(pkg),
                    isBuiltIn = false
                )
            }

        return VirtualCamConfig(
            enabled = prefs.getBoolean(VirtualCamConstants.KEY_ENABLED, false),
            forceBypass = prefs.getBoolean(VirtualCamConstants.KEY_FORCE_BYPASS, false),
            audioEnabled = prefs.getBoolean(VirtualCamConstants.KEY_AUDIO_ENABLED, true),
            micEnabled = prefs.getBoolean(VirtualCamConstants.KEY_MIC_ENABLED, true),
            loopVideo = prefs.getBoolean(VirtualCamConstants.KEY_LOOP_VIDEO, true),
            mirrorHorizontal = prefs.getBoolean(VirtualCamConstants.KEY_MIRROR_HORIZONTAL, false),
            rotationDegrees = prefs.getInt(VirtualCamConstants.KEY_ROTATION_DEGREES, 0),
            allowAllScopedApps = prefs.getBoolean(VirtualCamConstants.KEY_ALLOW_ALL_SCOPED, true),
            overlayRunning = prefs.getBoolean(VirtualCamConstants.KEY_OVERLAY_RUNNING, false),
            selectedMedia = selectedMedia,
            targetApps = builtInTargets + customTargets,
            worldReadableActive = worldReadable,
            lsposedActive = ModuleStatus.isModuleActive(),
            xposedApiVersion = ModuleStatus.getXposedApiVersion()
        )
    }

    fun saveConfig(config: VirtualCamConfig): VirtualCamConfig {
        val (prefs, worldReadable) = openPreferences()
        val enabledSet = config.targetApps.filter { it.isEnabled }.map { it.packageName }.toSet()
        val customSet = config.targetApps.filter { !it.isBuiltIn }.map { it.packageName }.toSet()

        prefs.edit().apply {
            putBoolean(VirtualCamConstants.KEY_ENABLED, config.enabled)
            putBoolean(VirtualCamConstants.KEY_FORCE_BYPASS, config.forceBypass)
            putBoolean(VirtualCamConstants.KEY_AUDIO_ENABLED, config.audioEnabled)
            putBoolean(VirtualCamConstants.KEY_MIC_ENABLED, config.micEnabled)
            putBoolean(VirtualCamConstants.KEY_LOOP_VIDEO, config.loopVideo)
            putBoolean(VirtualCamConstants.KEY_MIRROR_HORIZONTAL, config.mirrorHorizontal)
            putInt(VirtualCamConstants.KEY_ROTATION_DEGREES, config.rotationDegrees)
            putBoolean(VirtualCamConstants.KEY_ALLOW_ALL_SCOPED, config.allowAllScopedApps)
            putBoolean(VirtualCamConstants.KEY_OVERLAY_RUNNING, config.overlayRunning)

            putString(VirtualCamConstants.KEY_MEDIA_KIND, config.selectedMedia.kind.wireValue)
            putString(VirtualCamConstants.KEY_MEDIA_NAME, config.selectedMedia.displayName)
            putString(VirtualCamConstants.KEY_MEDIA_PATH, config.selectedMedia.localFilePath)
            putString(VirtualCamConstants.KEY_MEDIA_URI, config.selectedMedia.sourceUriString)
            putString(VirtualCamConstants.KEY_MEDIA_MIME, config.selectedMedia.mimeType)
            putInt(VirtualCamConstants.KEY_MEDIA_WIDTH, config.selectedMedia.width)
            putInt(VirtualCamConstants.KEY_MEDIA_HEIGHT, config.selectedMedia.height)
            putLong(VirtualCamConstants.KEY_MEDIA_DURATION, config.selectedMedia.durationMs)
            putLong(VirtualCamConstants.KEY_MEDIA_SIZE, config.selectedMedia.sizeBytes)
            putLong(VirtualCamConstants.KEY_MEDIA_UPDATED_AT, config.selectedMedia.updatedAtMs)

            putStringSet(VirtualCamConstants.KEY_ENABLED_PACKAGES, enabledSet)
            putStringSet(VirtualCamConstants.KEY_CUSTOM_PACKAGES, customSet)
            commit()
        }

        // Notify ContentResolver observers & local in-app components (FloatingControlService / MainActivity)
        runCatching {
            context.contentResolver.notifyChange(Uri.parse(VirtualCamConstants.ACTIVE_CONFIG_URI), null)
        }
        runCatching {
            val intent = Intent(VirtualCamConstants.ACTION_CONFIG_CHANGED).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }

        return config.copy(
            worldReadableActive = worldReadable,
            lsposedActive = ModuleStatus.isModuleActive(),
            xposedApiVersion = ModuleStatus.getXposedApiVersion()
        )
    }

    suspend fun importMedia(uri: Uri, hintKind: MediaKind? = null): Result<SelectedMediaInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val mimeType = resolver.getType(uri).orEmpty()
                val detectedKind = when {
                    mimeType.startsWith("video/") -> MediaKind.VIDEO
                    mimeType.startsWith("image/") -> MediaKind.IMAGE
                    hintKind != null && hintKind != MediaKind.NONE -> hintKind
                    else -> {
                        val lower = uri.toString().lowercase()
                        if (lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".webm")) {
                            MediaKind.VIDEO
                        } else {
                            MediaKind.IMAGE
                        }
                    }
                }

                var displayName = if (detectedKind == MediaKind.VIDEO) "virtual_video.mp4" else "virtual_photo.jpg"
                var reportedSize = 0L

                resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0) {
                            cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                        }
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            reportedSize = cursor.getLong(sizeIdx)
                        }
                    }
                }

                val extension = MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mimeType)
                    ?.takeIf { it.isNotBlank() }
                    ?: displayName.substringAfterLast('.', if (detectedKind == MediaKind.VIDEO) "mp4" else "jpg")

                mediaDirectory.listFiles()?.forEach { oldFile ->
                    if (oldFile.name.startsWith("active_media.")) {
                        oldFile.delete()
                    }
                }

                val destFile = File(mediaDirectory, "active_media.$extension")
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 32 * 1024)
                    }
                } ?: error("Unable to open selected media stream")

                destFile.setReadable(true, false)

                val actualSize = if (destFile.length() > 0L) destFile.length() else reportedSize
                var width = 0
                var height = 0
                var durationMs = 0L

                if (detectedKind == MediaKind.VIDEO) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(destFile.absolutePath)
                        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    } finally {
                        runCatching { retriever.release() }
                    }
                } else {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(destFile.absolutePath, opts)
                    width = opts.outWidth.coerceAtLeast(0)
                    height = opts.outHeight.coerceAtLeast(0)
                }

                val effectiveMime = mimeType.ifBlank {
                    if (detectedKind == MediaKind.VIDEO) "video/mp4" else "image/jpeg"
                }

                SelectedMediaInfo(
                    kind = detectedKind,
                    displayName = displayName,
                    localFilePath = destFile.absolutePath,
                    sourceUriString = uri.toString(),
                    mimeType = effectiveMime,
                    width = width,
                    height = height,
                    durationMs = durationMs,
                    sizeBytes = actualSize,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }

    suspend fun clearPersistedMedia() = withContext(Dispatchers.IO) {
        mediaDirectory.listFiles()?.forEach { file ->
            if (file.name.startsWith("active_media.")) {
                file.delete()
            }
        }
    }
}
