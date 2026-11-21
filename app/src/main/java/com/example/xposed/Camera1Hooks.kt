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
 * Hooks legacy Camera1 (android.hardware.Camera) preview, buffer callbacks, and shutter capture.
 */
@Suppress("DEPRECATION")
object Camera1Hooks {
    private const val TAG = "[VirtualCam-Cam1]"

    private val targetSurfaceMap = WeakHashMap<Camera, Surface>()
    private val dummyTextureMap = WeakHashMap<Camera, SurfaceTexture>()
    private val previewCallbackMap = WeakHashMap<Camera, Camera.PreviewCallback>()

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
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
                        val state = HookMediaBridge.readRuntimeState(pkg)
                        if (!state.isActiveForPackage(pkg)) return

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
                        val state = HookMediaBridge.readRuntimeState(pkg)
                        if (!state.isActiveForPackage(pkg)) return

                        // Bind a Virtual Surface wrapping the app's SurfaceTexture and hand a dummy
                        // SurfaceTexture to hardware so the real camera does not overwrite our frames.
                        val virtualSurface = Surface(targetTexture)
                        targetSurfaceMap[camera] = virtualSurface

                        val dummy = dummyTextureMap.getOrPut(camera) { SurfaceTexture(0) }
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
                        val state = HookMediaBridge.readRuntimeState(pkg)
                        val surface = targetSurfaceMap[camera]
                        if (state.isActiveForPackage(pkg) && surface != null && surface.isValid) {
                            HookMediaBridge.startRendererForSurface(surface, pkg, useMediaPlayerForVideo = true)
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
                            if (methodName == "release") {
                                dummyTextureMap.remove(camera)?.release()
                                previewCallbackMap.remove(camera)
                            }
                        }
                    }
                )
            }
        }

        // 5. PreviewCallback interception for apps reading raw NV21 frames
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
                            val camera = param.thisObject as? Camera ?: return
                            val original = param.args.firstOrNull() as? Camera.PreviewCallback ?: return
                            previewCallbackMap[camera] = original

                            param.args[0] = Camera.PreviewCallback { data, cam ->
                                val state = HookMediaBridge.readRuntimeState(pkg)
                                if (!state.isActiveForPackage(pkg) || data == null) {
                                    original.onPreviewFrame(data, cam)
                                    return@PreviewCallback
                                }
                                val size = runCatching { cam.parameters.previewSize }.getOrNull()
                                val w = size?.width ?: 640
                                val h = size?.height ?: 480
                                val bmp = HookMediaBridge.loadBitmapFromActiveMedia(
                                    HookMediaBridge.currentAppContext(),
                                    state,
                                    maxDimension = maxOf(w, h)
                                )
                                if (bmp != null) {
                                    val transformed = HookMediaBridge.applyTransform(
                                        bmp,
                                        state.mirrorHorizontal,
                                        state.rotationDegrees
                                    )
                                    val nv21 = HookMediaBridge.bitmapToNv21(transformed, w, h)
                                    if (transformed !== bmp) transformed.recycle()
                                    bmp.recycle()
                                    original.onPreviewFrame(nv21, cam)
                                } else {
                                    original.onPreviewFrame(data, cam)
                                }
                            }
                        }
                    }
                )
            }
        }

        // 6. takePicture(ShutterCallback, PictureCallback raw, PictureCallback postview, PictureCallback jpeg)
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
                        val state = HookMediaBridge.readRuntimeState(pkg)
                        if (!state.isActiveForPackage(pkg)) return

                        val shutter = param.args[0] as? Camera.ShutterCallback
                        val jpegCallback = param.args[3] as? Camera.PictureCallback
                        val jpegBytes = HookMediaBridge.encodeJpegFromActiveMedia(pkg)
                        if (jpegBytes != null && jpegCallback != null) {
                            shutter?.onShutter()
                            jpegCallback.onPictureTaken(jpegBytes, camera)
                            param.result = null
                        }
                    }
                }
            )
        }

        XposedBridge.log("$TAG Installed Camera1 hooks for $pkg")
    }
}
