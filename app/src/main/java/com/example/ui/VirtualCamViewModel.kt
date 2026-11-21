package com.example.ui

import android.app.Application
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.VirtualCamRepository
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
    val feedbackMessage: String? = null
)

class VirtualCamViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val repository = VirtualCamRepository(appContext)

    private val _uiState = MutableStateFlow(
        VirtualCamUiState(
            config = repository.loadConfig(),
            canDrawOverlays = Settings.canDrawOverlays(appContext)
        )
    )
    val uiState: StateFlow<VirtualCamUiState> = _uiState.asStateFlow()

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
                    _uiState.update {
                        it.copy(
                            config = updated,
                            isImporting = false,
                            isPreviewPlaying = true,
                            feedbackMessage = "Persisted ${mediaInfo.displayName} to module storage"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            feedbackMessage = "Import failed: ${error.message ?: "Unable to read URI"}"
                        )
                    }
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
            _uiState.update {
                it.copy(
                    config = updated,
                    feedbackMessage = "Cleared persisted virtual camera media"
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        updateConfig {
            copy(
                enabled = enabled,
                forceBypass = if (enabled) false else forceBypass
            )
        }
    }

    fun toggleQuickBypass() {
        val current = _uiState.value.config
        val nextBypass = !current.forceBypass
        updateConfig { copy(forceBypass = nextBypass) }
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
    }

    fun setMicEnabled(enabled: Boolean) {
        updateConfig { copy(micEnabled = enabled) }
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
        _uiState.update { it.copy(feedbackMessage = "Added $pkg to target scopes") }
    }

    fun removeCustomTargetApp(packageName: String) {
        updateConfig {
            copy(targetApps = targetApps.filterNot { it.packageName == packageName && !it.isBuiltIn })
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
