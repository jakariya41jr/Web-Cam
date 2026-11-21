package com.example.xposed

import com.example.model.VirtualCamConstants
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

import java.util.concurrent.ConcurrentHashMap

/**
 * Primary Xposed / LSPosed entry point declared in assets/xposed_init.
 * Intercepts target package loading and installs Camera1 & Camera2/Surface hooks.
 */
class VirtualCamHookEntry : IXposedHookLoadPackage {

    private val hookedPackages = ConcurrentHashMap.newKeySet<String>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (lpparam.classLoader == null) return

        // Self-hook so VirtualCam UI accurately reflects LSPosed activation status
        if (pkg == VirtualCamConstants.MODULE_PACKAGE) {
            if (hookedPackages.add(pkg)) {
                hookModuleSelfStatus(lpparam)
            }
            return
        }

        // Skip system/framework processes unless explicitly scoped
        if (pkg == "android" || pkg == "com.android.systemui") {
            return
        }

        // Avoid duplicate hook registration if handleLoadPackage fires multiple times for split APKs
        if (!hookedPackages.add(pkg)) {
            return
        }

        XposedBridge.log("[VirtualCam] Loaded into scoped target: $pkg (process=${lpparam.processName})")

        // Install both Camera1 and Camera2/Surface hooks; runtime state & bypass are checked dynamically
        runCatching {
            Camera1Hooks.install(lpparam)
        }.onFailure { err ->
            XposedBridge.log("[VirtualCam] Camera1 hook install warning for $pkg: ${err.message}")
        }

        runCatching {
            Camera2Hooks.install(lpparam)
        }.onFailure { err ->
            XposedBridge.log("[VirtualCam] Camera2 hook install warning for $pkg: ${err.message}")
        }
    }

    private fun hookModuleSelfStatus(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val statusClass = XposedHelpers.findClass(
                "com.example.xposed.ModuleStatus",
                lpparam.classLoader
            )
            XposedHelpers.findAndHookMethod(
                statusClass,
                "isModuleActive",
                XC_MethodReplacement.returnConstant(true)
            )
            XposedHelpers.findAndHookMethod(
                statusClass,
                "getXposedApiVersion",
                XC_MethodReplacement.returnConstant(XposedBridge.getXposedVersion())
            )
        }.onFailure { err ->
            XposedBridge.log("[VirtualCam] Self-hook failed: ${err.message}")
        }
    }
}
