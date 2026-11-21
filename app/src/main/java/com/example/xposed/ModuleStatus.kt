package com.example.xposed

/**
 * Self-hooked by [VirtualCamHookEntry] when LSPosed is active on this module package.
 * Allows the UI to detect reliably whether the Xposed/LSPosed framework is loaded.
 */
object ModuleStatus {
    @JvmStatic
    fun isModuleActive(): Boolean = false

    @JvmStatic
    fun getXposedApiVersion(): Int = 0
}
