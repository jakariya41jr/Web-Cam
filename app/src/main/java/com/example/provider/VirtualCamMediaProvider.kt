package com.example.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.example.data.VirtualCamRepository
import com.example.model.VirtualCamConstants
import java.io.File
import java.io.FileNotFoundException

/**
 * Exported read-only ContentProvider bridging the module's internal media cache
 * to hooked camera target apps under Android 11–15+ scoped storage & SELinux rules.
 */
class VirtualCamMediaProvider : ContentProvider() {

    companion object {
        private const val CODE_ACTIVE_MEDIA = 1
        private const val CODE_CONFIG = 2

        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(VirtualCamConstants.PROVIDER_AUTHORITY, "active_media", CODE_ACTIVE_MEDIA)
            addURI(VirtualCamConstants.PROVIDER_AUTHORITY, "config", CODE_CONFIG)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        if (uriMatcher.match(uri) != CODE_CONFIG) return null

        val config = VirtualCamRepository(ctx).loadConfig()
        val columns = arrayOf(
            VirtualCamConstants.KEY_ENABLED,
            VirtualCamConstants.KEY_FORCE_BYPASS,
            VirtualCamConstants.KEY_AUDIO_ENABLED,
            VirtualCamConstants.KEY_LOOP_VIDEO,
            VirtualCamConstants.KEY_MIRROR_HORIZONTAL,
            VirtualCamConstants.KEY_ROTATION_DEGREES,
            VirtualCamConstants.KEY_ALLOW_ALL_SCOPED,
            VirtualCamConstants.KEY_MEDIA_KIND,
            VirtualCamConstants.KEY_MEDIA_MIME,
            VirtualCamConstants.KEY_MEDIA_WIDTH,
            VirtualCamConstants.KEY_MEDIA_HEIGHT,
            VirtualCamConstants.KEY_MEDIA_PATH
        )

        return MatrixCursor(columns).apply {
            addRow(
                arrayOf<Any>(
                    if (config.enabled) 1 else 0,
                    if (config.forceBypass) 1 else 0,
                    if (config.audioEnabled) 1 else 0,
                    if (config.loopVideo) 1 else 0,
                    if (config.mirrorHorizontal) 1 else 0,
                    config.rotationDegrees,
                    if (config.allowAllScopedApps) 1 else 0,
                    config.selectedMedia.kind.wireValue,
                    config.selectedMedia.mimeType,
                    config.selectedMedia.width,
                    config.selectedMedia.height,
                    config.selectedMedia.localFilePath
                )
            )
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uriMatcher.match(uri) != CODE_ACTIVE_MEDIA) {
            throw FileNotFoundException("Unsupported URI: $uri")
        }
        if (mode != "r") {
            throw SecurityException("VirtualCamMediaProvider is strictly read-only")
        }

        val ctx = context ?: throw FileNotFoundException("Provider context unavailable")
        val config = VirtualCamRepository(ctx).loadConfig()
        val mediaFile = resolveSafeMediaFile(ctx.filesDir, config.selectedMedia.localFilePath)
            ?: throw FileNotFoundException("No active virtual camera media selected")

        return ParcelFileDescriptor.open(mediaFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun resolveSafeMediaFile(filesDir: File, configuredPath: String): File? {
        val mediaDir = File(filesDir, "virtual_cam_media").canonicalFile
        val candidate = if (configuredPath.isNotBlank()) {
            File(configuredPath).canonicalFile
        } else {
            mediaDir.listFiles()?.firstOrNull { it.name.startsWith("active_media.") }?.canonicalFile
        } ?: return null

        // Prevent path traversal outside the app's virtual_cam_media directory
        if (!candidate.path.startsWith(mediaDir.path + File.separator)) {
            return null
        }
        return candidate.takeIf { it.exists() && it.isFile }
    }

    override fun getType(uri: Uri): String? {
        val ctx = context ?: return null
        return when (uriMatcher.match(uri)) {
            CODE_ACTIVE_MEDIA -> VirtualCamRepository(ctx).loadConfig().selectedMedia.mimeType.ifBlank {
                "application/octet-stream"
            }
            CODE_CONFIG -> "vnd.android.cursor.item/vnd.${VirtualCamConstants.PROVIDER_AUTHORITY}.config"
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
