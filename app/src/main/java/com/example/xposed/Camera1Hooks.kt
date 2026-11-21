package com.example.xposed

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.view.Surface
import android.view.SurfaceHolder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.WeakHashMap

/**
 * Hooks legacy Camera1 (android.hardware.Camera) preview, in-place NV21 callbacks, and shutter capture.
 * Uses VideoFramePipeline so WebRTC Camera1Session (e.g. OmeTV/WebView) receives 30fps animated frames
 * in its exact pre-allocated callback buffer reference.
 */
@Suppress("DEPRECATION")
object Camera1Hooks {
    private const val TAG = "[VirtualCam-Cam1]"

    private val targetSurfaceMap = WeakHashMap<Camera, Surface>()
    private val dummyTextureMap = WeakHashMap<Camera, SurfaceTexture>()
    private val consumerAcquiredMap = WeakHashMap<Camera, Boolean>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        val processName = lpparam.processName ?: pkg
        val cameraClass = runCatching {
            XposedHelpers.findClass("android.hardware.Camera", lpparam.classLoader)
        }.getOrNull() ?: return

        // 1. setPreviewDisplay(SurfaceHolder)
        runCatching {
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "setPreviewDisplay",
                SurfaceHolder::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val camera = param.thisObject as? Camera ?: return
                        val holder = param.args.firstOrNull() as? SurfaceHolder ?: return
                        val state = HookMediaBridge.readRuntimeState(pkg, processName, forceRefresh = true)
                        if (!state.isActiveForPackage(pkg, processName)) return

                        val surface = holder.surface
                        if (surface != null && surface.isValid) {
                            targetSurfaceMap[camera] = surface
                        }
                    }
                }
            )
        }

        // 2. setPreviewTexture(SurfaceTexture)
        runCatching {
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "setPreviewTexture",
                SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val camera = param.thisObject as? Camera ?: return
                        val targetTexture = param.args.firstOrNull() as? SurfaceTexture ?: return
                        val state = HookMediaBridge.readRuntimeState(pkg, processName, forceRefresh = true)
                        if (!state.isActiveForPackage(pkg, processName)) return

                        runCatching {
                            val size = camera.parameters?.previewSize
                            if (size != null && size.width > 0 && size.height > 0) {
                                targetTexture.setDefaultBufferSize(size.width, size.height)
                            }
                        }

                        val virtualSurface = Surface(targetTexture)
                        if (virtualSurface.isValid) {
                            targetSurfaceMap[camera] = virtualSurface
                        }

                        val dummy = dummyTextureMap.getOrPut(camera) {
                            SurfaceTexture(0).apply {
                                setOnFrameAvailableListener { tex ->
                                    runCatching { tex.updateTexImage() }
                                }
                            }
                        }
                        param.args[0] = dummy
                    }
                }
            )
        }

        // 3. startPreview()
        runCatching {
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "startPreview",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val camera = param.thisObject as? Camera ?: return
                        val state = HookMediaBridge.readRuntimeState(pkg, processName, forceRefresh = true)
                        if (!state.isActiveForPackage(pkg, processName)) return

                        if (consumerAcquiredMap[camera] != true) {
                            VideoFramePipeline.acquireConsumer(HookMediaBridge.currentAppContext(), state)
                            consumerAcquiredMap[camera] = true
                        }
                        val surface = targetSurfaceMap[camera]
                        if (surface != null && surface.isValid) {
                            HookMediaBridge.startRendererForSurface(surface, pkg, processName)
                        }
                    }
                }
            )
        }

        // 4. stopPreview() & release()
        for (methodName in listOf("stopPreview", "release")) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    cameraClass,
                    methodName,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val camera = param.thisObject as? Camera ?: return
                            targetSurfaceMap.remove(camera)?.let { surface ->
                                HookMediaBridge.stopRendererForSurface(surface)
                            }
                            if (consumerAcquiredMap.remove(camera) == true) {
                                VideoFramePipeline.releaseConsumer()
                            }
                            if (methodName == "release") {
                                runCatching { dummyTextureMap.remove(camera)?.release() }
                            }
                        }
                    }
                )
            }
        }

        // 5. PreviewCallback interception for WebRTC / Camera1 NV21 buffers (mutates data in-place)
        val callbackMethods = listOf(
            "setPreviewCallback",
            "setPreviewCallbackWithBuffer",
            "setOneShotPreviewCallback"
        )
        callbackMethods.forEach { methodName ->
            runCatching {
                XposedHelpers.findAndHookMethod(
                    cameraClass,
                    methodName,
                    Camera.PreviewCallback::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val original = param.args.firstOrNull() as? Camera.PreviewCallback ?: return

                            param.args[0] = Camera.PreviewCallback { data, cam ->
                                val state = HookMediaBridge.readRuntimeState(pkg, processName)
                                if (!state.isActiveForPackage(pkg, processName) || data == null) {
                                    original.onPreviewFrame(data, cam)
                                    return@PreviewCallback
                                }

                                val size = runCatching { cam.parameters?.previewSize }.getOrNull()
                                val w = size?.width?.takeIf { it > 0 } ?: 640
                                val h = size?.height?.takeIf { it > 0 } ?: 480
                                val bmp = VideoFramePipeline.getCurrentFrameBitmap(
                                    HookMediaBridge.currentAppContext(),
                                    state
                                )
                                if (bmp != null) {
                                    try {
                                        val fitted = VideoFramePipeline.renderFittedBitmap(
                                            source = bmp,
                                            targetW = w,
                                            targetH = h,
                                            mirrorHorizontal = state.mirrorHorizontal,
                                            rotationDegrees = state.rotationDegrees
                                        )
                                        try {
                                            val nv21 = HookMediaBridge.bitmapToNv21(fitted, w, h)
                                            // Copy in-place so WebRTC Camera1Session buffer identity check passes
                                            val copyLen = minOf(nv21.size, data.size)
                                            System.arraycopy(nv21, 0, data, 0, copyLen)
                                        } finally {
                                            fitted.recycle()
                                        }
                                    } finally {
                                        bmp.recycle()
                                    }
                                }
                                original.onPreviewFrame(data, cam)
                            }
                        }
                    }
                )
            }
        }

        // 6. takePicture(...)
        runCatching {
            XposedHelpers.findAndHookMethod(
                cameraClass,
                "takePicture",
                Camera.ShutterCallback::class.java,
                Camera.PictureCallback::class.java,
                Camera.PictureCallback::class.java,
                Camera.PictureCallback::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val camera = param.thisObject as? Camera ?: return
                        val state = HookMediaBridge.readRuntimeState(pkg, processName, forceRefresh = true)
                        if (!state.isActiveForPackage(pkg, processName)) return

                        val shutter = param.args[0] as? Camera.ShutterCallback
                        val jpegCallback = param.args[3] as? Camera.PictureCallback
                        val jpegBytes = HookMediaBridge.encodeJpegFromActiveMedia(pkg, processName)
                        if (jpegBytes != null && jpegCallback != null) {
                            shutter?.onShutter()
                            jpegCallback.onPictureTaken(jpegBytes, camera)
                            param.result = null
                        }
                    }
                }
            )
        }

        XposedBridge.log("$TAG Installed Camera1 hooks for $pkg (process=$processName)")
    }
}
