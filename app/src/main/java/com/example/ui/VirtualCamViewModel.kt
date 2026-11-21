package com.example.ui

import android.app.Application
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.VirtualCamRepository
import com.example.diagnostics.LogCollector
import com.example.model.AppPreset
import com.example.model.MediaKind
import com.example.model.SelectedMediaInfo
import com.example.model.TargetAppScope
import com.example.model.VirtualCamConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VirtualCamUiState(
    val config: VirtualCamConfig = VirtualCamConfig(),
    val canDrawOverlays: Boolean = false,
    val isImporting: Boolean = false,
    val isPreviewPlaying: Boolean = true,
    val logs: List<String> = emptyList(),
    val isCollectingLogs: Boolean = false,
    val useRootLogs: Boolean = false,
    val feedbackMessage: String? = null
)

class VirtualCamViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repository = VirtualCamRepository(appContext)

    private val _uiState = MutableStateFlow(
        VirtualCamUiState(
            config = repository.loadConfig(),
            canDrawOverlays = Settings.canDrawOverlays(appContext),
            logs = LogCollector.getInternalEvents()
        )
    )
    val uiState: StateFlow<VirtualCamUiState> = _uiState.asStateFlow()

    init {
        val cfg = _uiState.value.config
        LogCollector.recordEvent(
            "App",
            "Loaded config: armed=${cfg.isEffectivelyArmed}, lsposed=${cfg.lsposedActive}, worldReadable=${cfg.worldReadableActive}"
        )
        refreshLogs()
    }

    fun refreshStatus() {
        val loaded = repository.loadConfig()
        val overlayAllowed = Settings.canDrawOverlays(appContext)
        _uiState.update {
            it.copy(
                config = loaded,
                canDrawOverlays = overlayAllowed
            )
        }
    }

    fun importMedia(uri: Uri, hintKind: MediaKind? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, feedbackMessage = null) }
            val result = repository.importMedia(uri, hintKind)
            result.fold(
                onSuccess = { mediaInfo ->
                    val updated = repository.saveConfig(
                        _uiState.value.config.copy(
                            enabled = true,
                            forceBypass = false,
                            selectedMedia = mediaInfo
                        )
                    )
                    LogCollector.recordEvent(
                        "Media",
                        "Imported ${mediaInfo.displayName} (${mediaInfo.width}x${mediaInfo.height}, " +
                            "${mediaInfo.fps.toInt()}fps, audio=${mediaInfo.hasAudioTrack}, codec=${mediaInfo.videoCodec.ifBlank { "unknown" }})"
                    )
                    _uiState.update {
                        it.copy(
                            config = updated,
                            isImporting = false,
                            isPreviewPlaying = true,
                            feedbackMessage = "Persisted ${mediaInfo.displayName} to module storage"
                        )
                    }
                    refreshLogs()
                },
                onFailure = { error ->
                    LogCollector.recordEvent("Media", "Import failed: ${error.message}")
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            feedbackMessage = "Import failed: ${error.message ?: "Unable to read URI"}"
                        )
                    }
                    refreshLogs()
                }
            )
        }
    }

    fun clearMedia() {
        viewModelScope.launch {
            repository.clearPersistedMedia()
            val updated = repository.saveConfig(
                _uiState.value.config.copy(
                    enabled = false,
                    forceBypass = false,
                    selectedMedia = SelectedMediaInfo()
                )
            )
            LogCollector.recordEvent("Media", "Cleared persisted virtual media")
            _uiState.update {
                it.copy(
                    config = updated,
                    feedbackMessage = "Cleared persisted virtual camera media"
                )
            }
            refreshLogs()
        }
    }

    fun setEnabled(enabled: Boolean) {
        updateConfig {
            copy(
                enabled = enabled,
                forceBypass = if (enabled) false else forceBypass
            )
        }
        LogCollector.recordEvent("Control", "Virtual Camera ${if (enabled) "ENABLED" else "STANDBY"}")
    }

    fun toggleQuickBypass() {
        val current = _uiState.value.config
        val nextBypass = !current.forceBypass
        updateConfig { copy(forceBypass = nextBypass) }
        LogCollector.recordEvent("Control", if (nextBypass) "Bypass ON (Real Camera)" else "Bypass OFF (Virtual Feed)")
        _uiState.update {
            it.copy(
                feedbackMessage = if (nextBypass) {
                    "Bypass active: original camera passed through"
                } else {
                    "Bypass released: virtual feed resumed"
                }
            )
        }
    }

    fun setAudioEnabled(enabled: Boolean) {
        updateConfig { copy(audioEnabled = enabled) }
        LogCollector.recordEvent("Audio", "Video Audio -> ${if (enabled) "ON" else "OFF"}")
    }

    fun setMicEnabled(enabled: Boolean) {
        updateConfig { copy(micEnabled = enabled) }
        LogCollector.recordEvent("Audio", "Main Mic -> ${if (enabled) "ON" else "MUTED"}")
    }

    fun setLoopVideo(loop: Boolean) {
        updateConfig { copy(loopVideo = loop) }
    }

    fun setMirrorHorizontal(mirror: Boolean) {
        updateConfig { copy(mirrorHorizontal = mirror) }
    }

    fun cycleRotation() {
        val current = _uiState.value.config.rotationDegrees
        val next = (current + 90) % 360
        updateConfig { copy(rotationDegrees = next) }
    }

    fun togglePreviewPlaying() {
        _uiState.update { it.copy(isPreviewPlaying = !it.isPreviewPlaying) }
    }

    fun setAllowAllScopedApps(allow: Boolean) {
        updateConfig { copy(allowAllScopedApps = allow) }
    }

    fun setAutoApplyPresets(autoApply: Boolean) {
        updateConfig { copy(autoApplyPresets = autoApply) }
        LogCollector.recordEvent("Presets", "Auto-apply per-app presets -> $autoApply")
    }

    fun saveCurrentAsPreset(packageName: String) {
        val cfg = _uiState.value.config
        val preset = AppPreset(
            packageName = packageName,
            virtualModeEnabled = !cfg.forceBypass,
            audioEnabled = cfg.audioEnabled,
            micEnabled = cfg.micEnabled,
            loopVideo = cfg.loopVideo,
            mirrorHorizontal = cfg.mirrorHorizontal,
            rotationDegrees = cfg.rotationDegrees
        )
        updateConfig {
            copy(appPresets = appPresets + (packageName to preset))
        }
        LogCollector.recordEvent(
            "Presets",
            "Saved preset for $packageName (vcam=${preset.virtualModeEnabled}, vidAudio=${preset.audioEnabled}, mic=${preset.micEnabled}, rot=${preset.rotationDegrees}°)"
        )
        _uiState.update { it.copy(feedbackMessage = "Saved current settings as preset for $packageName") }
    }

    fun applyPreset(packageName: String) {
        val preset = _uiState.value.config.appPresets[packageName] ?: return
        updateConfig {
            copy(
                enabled = true,
                forceBypass = !preset.virtualModeEnabled,
                audioEnabled = preset.audioEnabled,
                micEnabled = preset.micEnabled,
                loopVideo = preset.loopVideo,
                mirrorHorizontal = preset.mirrorHorizontal,
                rotationDegrees = preset.rotationDegrees
            )
        }
        LogCollector.recordEvent("Presets", "Applied preset for $packageName")
        _uiState.update { it.copy(feedbackMessage = "Applied preset for $packageName") }
    }

    fun deletePreset(packageName: String) {
        updateConfig {
            copy(appPresets = appPresets - packageName)
        }
        LogCollector.recordEvent("Presets", "Removed preset for $packageName")
        _uiState.update { it.copy(feedbackMessage = "Reset preset for $packageName") }
    }

    fun toggleTargetApp(packageName: String) {
        updateConfig {
            copy(
                targetApps = targetApps.map { scope ->
                    if (scope.packageName == packageName) {
                        scope.copy(isEnabled = !scope.isEnabled)
                    } else {
                        scope
                    }
                }
            )
        }
    }

    fun addCustomTargetApp(rawPackage: String) {
        val pkg = rawPackage.trim()
        val validRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
        if (!validRegex.matches(pkg)) {
            _uiState.update {
                it.copy(feedbackMessage = "Enter a valid package ID (e.g. com.example.cam)")
            }
            return
        }
        val current = _uiState.value.config
        if (current.targetApps.any { it.packageName == pkg }) {
            _uiState.update {
                it.copy(feedbackMessage = "$pkg is already in target scopes")
            }
            return
        }
        val newScope = TargetAppScope(
            packageName = pkg,
            displayName = pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() },
            category = "Custom",
            isEnabled = true,
            isBuiltIn = false
        )
        updateConfig { copy(targetApps = targetApps + newScope) }
        LogCollector.recordEvent("Scopes", "Added custom scope $pkg")
        _uiState.update { it.copy(feedbackMessage = "Added $pkg to target scopes") }
    }

    fun removeCustomTargetApp(packageName: String) {
        updateConfig {
            copy(
                targetApps = targetApps.filterNot { it.packageName == packageName && !it.isBuiltIn },
                appPresets = appPresets - packageName
            )
        }
    }

    fun setUseRootLogs(useRoot: Boolean) {
        _uiState.update { it.copy(useRootLogs = useRoot) }
        refreshLogs(useRootOverride = useRoot)
    }

    fun refreshLogs(useRootOverride: Boolean? = null) {
        val useRoot = useRootOverride ?: _uiState.value.useRootLogs
        viewModelScope.launch {
            _uiState.update { it.copy(isCollectingLogs = true) }
            val result = LogCollector.collectLogs(useRoot = useRoot)
            _uiState.update { state ->
                state.copy(
                    isCollectingLogs = false,
                    logs = result.getOrElse { listOf("Log collection error: ${it.message}") }
                )
            }
        }
    }

    fun clearLogs() {
        LogCollector.clearInternalEvents()
        _uiState.update {
            it.copy(
                logs = listOf("=== VirtualCam App Events ===", "(Cleared)"),
                feedbackMessage = "Cleared diagnostic log buffer"
            )
        }
    }

    fun showFeedback(message: String) {
        _uiState.update { it.copy(feedbackMessage = message) }
    }

    fun dismissFeedback() {
        _uiState.update { it.copy(feedbackMessage = null) }
    }

    private fun updateConfig(transform: VirtualCamConfig.() -> VirtualCamConfig) {
        val next = repository.saveConfig(_uiState.value.config.transform())
        _uiState.update {
            it.copy(
                config = next,
                canDrawOverlays = Settings.canDrawOverlays(appContext)
            )
        }
    }
}
