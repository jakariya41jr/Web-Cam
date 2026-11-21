package com.example.xposed

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.view.Surface
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executor

/**
 * Hooks Camera2 (CameraManager, CameraDevice, CaptureRequest.Builder, SessionConfiguration),
 * MediaRecorder, and ImageReader pipelines with API-level and null guards.
 */
object Camera2Hooks {
    private const val TAG = "[VirtualCam-Cam2]"

    private val trackedOutputSurfaces = Collections.newSetFromMap(WeakHashMap<Surface, Boolean>())
    private val imageReaderSurfaces = Collections.newSetFromMap(WeakHashMap<Surface, Boolean>())
    private val mediaRecorderSurfaces = Collections.newSetFromMap(WeakHashMap<Surface, Boolean>())

    @Volatile
    private var dummySurfaceTexture: SurfaceTexture? = null

    @Volatile
    private var dummySurface: Surface? = null

    private fun getOrCreateDummySurface(): Surface {
        val current = dummySurface
        if (current != null && current.isValid) return current
        val tex = SurfaceTexture(0).apply { setDefaultBufferSize(1280, 720) }
        dummySurfaceTexture = tex
        return Surface(tex).also { dummySurface = it }
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        hookCameraManager(lpparam, pkg)
        hookCameraDevice(lpparam, pkg)
        hookCaptureRequestAndOutput(lpparam, pkg)
        hookMediaRecorder(lpparam, pkg)
        hookImageReader(lpparam, pkg)
        XposedBridge.log("$TAG Installed Camera2 / Surface / MediaRecorder / ImageReader hooks for $pkg")
    }

    private fun hookCameraManager(lpparam: XC_LoadPackage.LoadPackageParam, pkg: String) {
        val managerClass = runCatching {
            XposedHelpers.findClass("android.hardware.camera2.CameraManager", lpparam.classLoader)
        }.getOrNull() ?: return

        // API 21+: openCamera(String, CameraDevice.StateCallback, Handler)
        runCatching {
            XposedHelpers.findAndHookMethod(
                managerClass,
                "openCamera",
                String::class.java,
                CameraDevice.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val callback = param.args[1] as? CameraDevice.StateCallback ?: return
                        param.args[1] = wrapCameraDeviceStateCallback(callback, pkg)
                    }
                }
            )
        }

        // API 28+: openCamera(String, Executor, CameraDevice.StateCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    managerClass,
                    "openCamera",
                    String::class.java,
                    Executor::class.java,
                    CameraDevice.StateCallback::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val callback = param.args[2] as? CameraDevice.StateCallback ?: return
                            param.args[2] = wrapCameraDeviceStateCallback(callback, pkg)
                        }
                    }
                )
            }
        }
    }

    private fun wrapCameraDeviceStateCallback(
        delegate: CameraDevice.StateCallback,
        pkg: String
    ): CameraDevice.StateCallback {
        return object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                delegate.onOpened(camera)
            }

            override fun onClosed(camera: CameraDevice) {
                HookMediaBridge.stopAllRenderers()
                delegate.onClosed(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                HookMediaBridge.stopAllRenderers()
                delegate.onDisconnected(camera)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                HookMediaBridge.stopAllRenderers()
                delegate.onError(camera, error)
            }
        }
    }

    private fun hookCameraDevice(lpparam: XC_LoadPackage.LoadPackageParam, pkg: String) {
        val deviceImplClass = runCatching {
            XposedHelpers.findClass(
                "android.hardware.camera2.impl.CameraDeviceImpl",
                lpparam.classLoader
            )
        }.getOrNull() ?: return

        // Legacy list-based createCaptureSession(List<Surface>, StateCallback, Handler)
        runCatching {
            XposedHelpers.findAndHookMethod(
                deviceImplClass,
                "createCaptureSession",
                List::class.java,
                CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val state = HookMediaBridge.readRuntimeState(pkg)
                        if (!state.isActiveForPackage(pkg)) return

                        @Suppress("UNCHECKED_CAST")
                        val surfaces = (param.args[0] as? List<Surface>)?.filterNotNull().orEmpty()
                        if (surfaces.isEmpty()) return

                        surfaces.forEach { surface ->
                            trackedOutputSurfaces.add(surface)
                            if (!imageReaderSurfaces.contains(surface)) {
                                HookMediaBridge.startRendererForSurface(surface, pkg, useMediaPlayerForVideo = true)
                            }
                        }
                        // Route real camera device session to dummy surface so hardware does not clash
                        param.args[0] = listOf(getOrCreateDummySurface())
                    }
                }
            )
        }

        // API 28+: createCaptureSession(SessionConfiguration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    deviceImplClass,
                    "createCaptureSession",
                    SessionConfiguration::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val state = HookMediaBridge.readRuntimeState(pkg)
                            if (!state.isActiveForPackage(pkg)) return

                            val config = param.args.firstOrNull() as? SessionConfiguration ?: return
                            val outputs = config.outputConfigurations
                            outputs.forEach { outputConfig ->
                                outputConfig.surface?.let { surface ->
                                    trackedOutputSurfaces.add(surface)
                                    if (!imageReaderSurfaces.contains(surface)) {
                                        HookMediaBridge.startRendererForSurface(
                                            surface,
                                            pkg,
                                            useMediaPlayerForVideo = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }

        // API 24+: createCaptureSessionByOutputConfigurations(List<OutputConfiguration>, StateCallback, Handler)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    deviceImplClass,
                    "createCaptureSessionByOutputConfigurations",
                    List::class.java,
                    CameraCaptureSession.StateCallback::class.java,
                    Handler::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val state = HookMediaBridge.readRuntimeState(pkg)
                            if (!state.isActiveForPackage(pkg)) return

                            @Suppress("UNCHECKED_CAST")
                            val configs = (param.args[0] as? List<OutputConfiguration>).orEmpty()
                            configs.forEach { cfg ->
                                cfg.surface?.let { surface ->
                                    trackedOutputSurfaces.add(surface)
                                    if (!imageReaderSurfaces.contains(surface)) {
                                        HookMediaBridge.startRendererForSurface(
                                            surface,
                                            pkg,
                                            useMediaPlayerForVideo = true
                                        )
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }

        // close() stops active renderers cleanly
        runCatching {
            XposedHelpers.findAndHookMethod(
                deviceImplClass,
                "close",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        HookMediaBridge.stopAllRenderers()
                    }
                }
            )
        }
    }

    private fun hookCaptureRequestAndOutput(lpparam: XC_LoadPackage.LoadPackageParam, pkg: String) {
        val builderClass = runCatching {
            XposedHelpers.findClass(
                "android.hardware.camera2.CaptureRequest\$Builder",
                lpparam.classLoader
            )
        }.getOrNull()

        if (builderClass != null) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    builderClass,
                    "addTarget",
                    Surface::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val surface = param.args.firstOrNull() as? Surface ?: return
                            val state = HookMediaBridge.readRuntimeState(pkg)
                            if (!state.isActiveForPackage(pkg)) return

                            trackedOutputSurfaces.add(surface)
                            if (!imageReaderSurfaces.contains(surface)) {
                                HookMediaBridge.startRendererForSurface(surface, pkg, useMediaPlayerForVideo = true)
                            }
                            // Redirect hardware CaptureRequest target to dummy surface to avoid BufferQueue collision
                            param.args[0] = getOrCreateDummySurface()
                        }
                    }
                )
            }

            runCatching {
                XposedHelpers.findAndHookMethod(
                    builderClass,
                    "removeTarget",
                    Surface::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val surface = param.args.firstOrNull() as? Surface ?: return
                            HookMediaBridge.stopRendererForSurface(surface)
                            trackedOutputSurfaces.remove(surface)
                        }
                    }
                )
            }
        }
    }

    private fun hookMediaRecorder(lpparam: XC_LoadPackage.LoadPackageParam, pkg: String) {
        val recorderClass = runCatching {
            XposedHelpers.findClass("android.media.MediaRecorder", lpparam.classLoader)
        }.getOrNull() ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                recorderClass,
                "getSurface",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val surface = param.result as? Surface ?: return
                        mediaRecorderSurfaces.add(surface)
                    }
                }
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                recorderClass,
                "start",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val state = HookMediaBridge.readRuntimeState(pkg)
                        if (!state.isActiveForPackage(pkg)) return

                        mediaRecorderSurfaces.forEach { surface ->
                            if (surface.isValid) {
                                HookMediaBridge.startRendererForSurface(
                                    surface,
                                    pkg,
                                    useMediaPlayerForVideo = true
                                )
                            }
                        }
                    }
                }
            )
        }

        for (methodName in listOf("stop", "release")) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    recorderClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            mediaRecorderSurfaces.forEach { HookMediaBridge.stopRendererForSurface(it) }
                        }
                    }
                )
            }
        }
    }

    private fun hookImageReader(lpparam: XC_LoadPackage.LoadPackageParam, pkg: String) {
        val readerClass = runCatching {
            XposedHelpers.findClass("android.media.ImageReader", lpparam.classLoader)
        }.getOrNull() ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                readerClass,
                "getSurface",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val surface = param.result as? Surface ?: return
                        imageReaderSurfaces.add(surface)
                    }
                }
            )
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                readerClass,
                "close",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val reader = param.thisObject as? ImageReader ?: return
                        runCatching { reader.surface }.getOrNull()?.let { surface ->
                            imageReaderSurfaces.remove(surface)
                            HookMediaBridge.stopRendererForSurface(surface)
                        }
                    }
                }
            )
        }
    }
}
