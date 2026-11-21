package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.VirtualCamRepository
import com.example.diagnostics.LogCollector
import com.example.model.AppPreset
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
    fun `repository persists audio, mic, presets, and selected media metadata`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = VirtualCamRepository(context)

        val mediaDir = File(context.filesDir, "virtual_cam_media").apply { mkdirs() }
        val dummyFile = File(mediaDir, "active_media.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }

        val imoPreset = AppPreset(
            packageName = "com.imo.android.imoim",
            virtualModeEnabled = true,
            audioEnabled = true,
            micEnabled = false,
            loopVideo = true,
            mirrorHorizontal = true,
            rotationDegrees = 270
        )

        val saved = repo.saveConfig(
            repo.loadConfig().copy(
                enabled = true,
                forceBypass = false,
                audioEnabled = true,
                micEnabled = false,
                loopVideo = true,
                mirrorHorizontal = true,
                rotationDegrees = 90,
                autoApplyPresets = true,
                appPresets = mapOf(imoPreset.packageName to imoPreset),
                selectedMedia = SelectedMediaInfo(
                    kind = MediaKind.VIDEO,
                    displayName = "sample_clip.mp4",
                    localFilePath = dummyFile.absolutePath,
                    mimeType = "video/mp4",
                    width = 1280,
                    height = 720,
                    durationMs = 5000L,
                    sizeBytes = 4L,
                    fps = 30f,
                    videoCodec = "video/avc",
                    audioCodec = "audio/mp4a-latm",
                    hasAudioTrack = true,
                    audioSampleRate = 48000
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
        assertEquals(30f, reloaded.selectedMedia.fps, 0.01f)
        assertTrue(reloaded.selectedMedia.hasAudioTrack)
        assertEquals(48000, reloaded.selectedMedia.audioSampleRate)

        val reloadedPreset = reloaded.appPresets["com.imo.android.imoim"]
        assertNotNull(reloadedPreset)
        assertFalse(reloadedPreset!!.micEnabled)
        assertEquals(270, reloadedPreset.rotationDegrees)
    }

    @Test
    fun `webview process resolves to target host package and applies preset`() {
        val imoPreset = AppPreset(
            packageName = "com.imo.android.imoim",
            virtualModeEnabled = true,
            audioEnabled = true,
            micEnabled = false,
            loopVideo = true,
            mirrorHorizontal = false,
            rotationDegrees = 180
        )
        val state = HookRuntimeState(
            enabled = true,
            forceBypass = false,
            audioEnabled = false,
            micEnabled = true,
            rotationDegrees = 0,
            allowAllScopedApps = false,
            autoApplyPresets = true,
            mediaKind = MediaKind.VIDEO,
            enabledPackages = setOf("omegle.tv", "com.imo.android.imoim"),
            appPresets = mapOf("com.imo.android.imoim" to imoPreset)
        )

        assertTrue(state.isActiveForPackage("com.google.android.webview", "omegle.tv"))
        assertTrue(state.isActiveForPackage("com.google.android.webview", "com.imo.android.imoim"))
        assertFalse(state.isActiveForPackage("com.google.android.webview", "com.unscoped.browser"))

        val effectiveImo = state.withPresetFor("com.google.android.webview", "com.imo.android.imoim")
        assertTrue(effectiveImo.audioEnabled)
        assertFalse(effectiveImo.micEnabled)
        assertEquals(180, effectiveImo.rotationDegrees)
    }

    @Test
    fun `video precheck flags high resolution, missing audio, and non-h264 codec`() {
        val optimal = SelectedMediaInfo(
            kind = MediaKind.VIDEO,
            localFilePath = "/tmp/ok.mp4",
            width = 1080,
            height = 1920,
            fps = 30f,
            videoCodec = "video/avc",
            hasAudioTrack = true
        )
        assertTrue(optimal.buildPreCheckWarnings().isEmpty())

        val risky = SelectedMediaInfo(
            kind = MediaKind.VIDEO,
            localFilePath = "/tmp/4k.mkv",
            width = 3840,
            height = 2160,
            fps = 60f,
            videoCodec = "video/hevc",
            hasAudioTrack = false,
            videoRotationDegrees = 90
        )
        val warnings = risky.buildPreCheckWarnings()
        assertTrue(warnings.any { it.isSevere && it.title.contains("High Resolution") })
        assertTrue(warnings.any { it.title.contains("No Audio Track") })
        assertTrue(warnings.any { it.title.contains("Non-H.264") })
        assertTrue(warnings.any { it.title.contains("Unusual Frame Rate") })
        assertTrue(warnings.any { it.title.contains("Embedded Video Rotation") })
    }

    @Test
    fun `log collector records and clears internal diagnostic events`() {
        LogCollector.clearInternalEvents()
        LogCollector.recordEvent("Test", "First event")
        LogCollector.recordEvent("Test", "Second event")

        val events = LogCollector.getInternalEvents()
        assertEquals(2, events.size)
        assertTrue(events[0].contains("Second event"))
        assertTrue(events[1].contains("First event"))

        LogCollector.clearInternalEvents()
        assertTrue(LogCollector.getInternalEvents().isEmpty())
    }

    @Test
    fun `media provider exposes audio, mic, and preset config columns`() {
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
            assertTrue(it.getColumnIndex(VirtualCamConstants.KEY_AUTO_APPLY_PRESETS) >= 0)
            assertTrue(it.getColumnIndex(VirtualCamConstants.KEY_APP_PRESETS_SET) >= 0)
        }
    }
}
