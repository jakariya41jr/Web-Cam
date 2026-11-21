package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.VirtualCamRepository
import com.example.model.MediaKind
import com.example.model.SelectedMediaInfo
import com.example.model.VirtualCamConstants
import com.example.provider.VirtualCamMediaProvider
import com.example.xposed.HookRuntimeState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExampleRobolectricTest {

    @Test
    fun `app name matches VirtualCam`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("VirtualCam", appName)
    }

    @Test
    fun `repository persists audio, mic, and selected media metadata`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = VirtualCamRepository(context)

        val mediaDir = File(context.filesDir, "virtual_cam_media").apply { mkdirs() }
        val dummyFile = File(mediaDir, "active_media.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val saved = repo.saveConfig(
            repo.loadConfig().copy(
                enabled = true,
                forceBypass = false,
                audioEnabled = true,
                micEnabled = false,
                loopVideo = true,
                mirrorHorizontal = true,
                rotationDegrees = 90,
                selectedMedia = SelectedMediaInfo(
                    kind = MediaKind.VIDEO,
                    displayName = "sample_clip.mp4",
                    localFilePath = dummyFile.absolutePath,
                    mimeType = "video/mp4",
                    width = 1280,
                    height = 720,
                    durationMs = 5000L,
                    sizeBytes = 4L
                )
            )
        )

        assertTrue(saved.isEffectivelyArmed)
        val reloaded = repo.loadConfig()
        assertTrue(reloaded.enabled)
        assertTrue(reloaded.audioEnabled)
        assertFalse(reloaded.micEnabled)
        assertTrue(reloaded.mirrorHorizontal)
        assertEquals(90, reloaded.rotationDegrees)
        assertEquals(MediaKind.VIDEO, reloaded.selectedMedia.kind)
        assertEquals("sample_clip.mp4", reloaded.selectedMedia.displayName)
    }

    @Test
    fun `webview process resolves to target host package`() {
        val state = HookRuntimeState(
            enabled = true,
            forceBypass = false,
            allowAllScopedApps = false,
            mediaKind = MediaKind.VIDEO,
            enabledPackages = setOf("omegle.tv", "com.imo.android.imoim")
        )

        assertTrue(state.isActiveForPackage("com.google.android.webview", "omegle.tv"))
        assertTrue(state.isActiveForPackage("com.google.android.webview", "com.imo.android.imoim"))
        assertFalse(state.isActiveForPackage("com.google.android.webview", "com.unscoped.browser"))
    }

    @Test
    fun `media provider exposes audio and mic config columns`() {
        val controller = Robolectric.buildContentProvider(VirtualCamMediaProvider::class.java)
            .create(VirtualCamConstants.PROVIDER_AUTHORITY)
        val provider = controller.get()

        val cursor = provider.query(
            Uri.parse(VirtualCamConstants.ACTIVE_CONFIG_URI),
            null,
            null,
            null,
            null
        )
        assertNotNull(cursor)
        cursor!!.use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getColumnIndex(VirtualCamConstants.KEY_AUDIO_ENABLED) >= 0)
            assertTrue(it.getColumnIndex(VirtualCamConstants.KEY_MIC_ENABLED) >= 0)
            assertTrue(it.getColumnIndex(VirtualCamConstants.KEY_MEDIA_KIND) >= 0)
        }
    }
}
