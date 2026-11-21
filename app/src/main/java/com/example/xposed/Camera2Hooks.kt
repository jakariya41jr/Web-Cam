package com.example.xposed

import android.graphics.Bitmap
import android.graphics.ImageFormat
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
import android.os.HandlerThread
import android.view.Surface
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Hooks Camera2 (CameraManager, CameraDeviceImpl, SessionConfiguration, CaptureRequest.Builder),
 * MediaRecorder, and ImageReader pipelines.
 *
 * Key stability fixes:
 * - Replaces OutputConfiguration / SessionConfiguration (API 28+) consistently with a per-device
 *   bridge surface so CaptureRequest.Builder.addTarget(...) never mismatches the session outputs
 *   (fixes Imo crash ~1s after opening camera).
 * - Uses a draining SurfaceTexture or bridge YUV_420_888 ImageReader so hardware BufferQueue
 *   never stalls AND allows instant mid-call switching between Virtual Camera and Real Camera.
 */
object Camera2Hooks {
    private const val TAG = "[VirtualCam-Cam2]"

    private class DeviceBridgeSession(
        val pkg: String,
        val processName: String
    ) {
        private var bridgeThread: HandlerThread? = null
        private var bridgeHandler: Handler? = null
        private var dummyTexture: SurfaceTexture? = null
        private var dummySurface: Surface? = null
        private var bridgeReader: ImageReader? = null

        @Volatile
        private var latestRealBitmap: Bitmap? = null
        private val realBitmapLock = Any()

        val appSurfaces: MutableSet<Surface> = Collections.newSetFromMap(ConcurrentHashMap())

        @Synchronized
        fun getOrCreateBridgeSurface(preferYuvSpec: ReaderSurfaceSpec?): Surface {
            if (preferYuvSpec != null && bridgeReader == null) {
                runCatching {
                    val thread = ensureThread()
                    val reader = ImageReader.newInstance(
                        preferYuvSpec.width.coerceAtLeast(320),
                        preferYuvSpec.height.coerceAtLeast(240),
                        ImageFormat.YUV_420_888,
                        3
                    )
                    reader.setOnImageAvailableListener({ r ->
                        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        try {
                            val state = HookMediaBridge.readRuntimeState(pkg, processName)
                            // Only convert real camera image if user toggled Bypass mid-session
                            if (!state.isActiveForPackage(pkg, processName)) {
                                val bmp = VideoFramePipeline.yuvImageToBitmap(img, maxDim = 960)
                                if (bmp != null) {
                                    synchronized(realBitmapLock) {
                                        latestRealBitmap?.let { if (!it.isRecycled) it.recycle() }
                                        latestRealBitmap = bmp
                                    }
                                }
                            }
                        } finally {
                            runCatching { img.close() }
                        }
                    }, ensureHandler(thread))
                    bridgeReader = reader
                    return reader.surface
                }
            }

            bridgeReader?.surface?.let { if (it.isValid) return it }

            val existing = dummySurface
            if (existing != null && existing.isValid) return existing

            val thread = ensureThread()
            val tex = SurfaceTexture(0).apply {
                setDefaultBufferSize(720, 1280)
                setOnFrameAvailableListener({ st ->
                    runCatching { st.updateTexImage() }
                }, ensureHandler(thread))
            }
            dummyTexture = tex
            return Surface(tex).also { dummySurface = it }
        }

        fun copyLatestRealFrame(): Bitmap? {
            synchronized(realBitmapLock) {
                val bmp = latestRealBitmap
                if (bmp != null && !bmp.isRecycled) {
                    return bmp.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
            return null
        }

        @Synchronized
        fun release() {
            appSurfaces.forEach { HookMediaBridge.stopRendererForSurface(it) }
            appSurfaces.clear()
            runCatching { bridgeReader?.close() }
            bridgeReader = null
            runCatching { dummySurface?.release() }
            dummySurface = null
            runCatching { dummyTexture?.release() }
            dummyTexture = null
            runCatching { bridgeThread?.quitSafely() }
            bridgeThread = null
            bridgeHandler = null
            synchronized(realBitmapLock) {
                latestRealBitmap?.let { if (!it.isRecycled) it.recycle() }
                latestRealBitmap = null
            }
        }

        private fun ensureThread(): HandlerThread {
            return bridgeThread ?: HandlerThread("VirtualCamBridgeHAL").apply { start() }
                .also { bridgeThread = it }
        }

        private fun ensureHandler(thread: HandlerThread): Handler {
            return bridgeHandler ?: Handler(thread.looper).also { bridgeHandler = it }
        }
    }

    private val deviceSessions = WeakHashMap<Any, DeviceBridgeSession>()
    private val builderToDevice = WeakHashMap<Any, Any>()
    private val mediaRecorderSurfaces = Collections.newSetFromMap(WeakHashMap<Surface, Boolean>())

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        val processName = lpparam.processName ?: pkg
        hookImageReader(lpparam)
        hookMediaRecorder(lpparam, pkg, processName)
        hookCameraManager(lpparam, pkg, processName)
        hookCameraDevice(lpparam, pkg, processName)
        hookCaptureRequestBuilder(lpparam, pkg, processName)
        XposedBridge.log("$TAG Installed Camera2 / Surface / MediaRecorder / ImageReader hooks for $pkg (process=$processName)")
    }

    private fun getOrCreateDeviceSession(device: Any, pkg: String, processName: String): DeviceBridgeSession {
        synchronized(deviceSessions) {
            return deviceSessions.getOrPut(device) { DeviceBridgeSession(pkg, processName) }
        }
    }

    private fun hookCameraManager(lpparam: XC_LoadPackage.LoadPackageParam, pkg: String, processName: String) {
        val managerClass = runCatching {
            XposedHelpers.findClass("android.hardware.camera2.CameraManager", lpparam.classLoader)
        }.getOrNull() ?: return

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
                        param.args[1] = wrapCameraDeviceStateCallback(callback)
                    }
                }
            )
        }

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
                            param.args[2] = wrapCameraDeviceStateCallback(callback)
                        }
                    }
                )
            }
        }
    }

    private fun wrapCameraDeviceStateCallback(
        delegate: CameraDevice.StateCallback
    ): CameraDevice.StateCallback {
        return object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                delegate.onOpened(camera)
            }

            override fun onClosed(camera: CameraDevice) {
                synchronized(deviceSessions) {
                    deviceSessions.remove(camera)?.release()
                }
                delegate.onClosed(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                synchronized(deviceSessions) {
                    deviceSessions.remove(camera)?.release()
                }
                delegate.onDisconnected(camera)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                synchronized(deviceSessions) {
                    deviceSessions.remove(camera)?.release()
                }
                delegate.onError(camera, error)
            }
        }
    }

    private fun hookCameraDevice(lpparam: XC_LoadPackage.LoadPackageParam, pkg: String, processName: String) {
        val deviceImplClass = runCatching {
            XposedHelpers.findClass("android.hardware.camera2.impl.CameraDeviceImpl", lpparam.classLoader)
        }.getOrNull() ?: return

        // Associate CaptureRequest.Builder -> CameraDeviceImpl when createCaptureRequest is called
        runCatching {
            XposedHelpers.findAndHookMethod(
                deviceImplClass,
                "createCaptureRequest",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val device = param.thisObject ?: return
                        val builder = param.result ?: return
                        synchronized(builderToDevice) {
                            builderToDevice[builder] = device
                        }
                    }
                }
            )
        }

        // 1. Legacy createCaptureSession(List<Surface>, StateCallback, Handler)
        runCatching {
            XposedHelpers.findAndHookMethod(
                deviceImplClass,
                "createCaptureSession",
                List::class.java,
                CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val device = param.thisObject ?: return
                        val state = HookMediaBridge.readRuntimeState(pkg, processName, forceRefresh = true)
                        if (!state.isActiveForPackage(pkg, processName)) return

                        @Suppress("UNCHECKED_CAST")
                        val surfaces = (param.args[0] as? List<Surface>)?.filterNotNull().orEmpty()
                        if (surfaces.isEmpty()) return

                        val session = getOrCreateDeviceSession(device, pkg, processName)
                        val bridgeSurface = prepareBridgeForOutputs(session, surfaces)
                        param.args[0] = listOf(bridgeSurface)

                        val origCb = param.args[1] as? CameraCaptureSession.StateCallback
                        if (origCb != null) {
                            param.args[1] = wrapSessionStateCallback(origCb, session, surfaces, pkg, processName)
                        }
                    }
                }
            )
        }

        // 2. API 24+: createCaptureSessionByOutputConfigurations(List<OutputConfiguration>, StateCallback, Handler)
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
                            val device = param.thisObject ?: return
                            val state = HookMediaBridge.readRuntimeState(pkg, processName, forceRefresh = true)
                            if (!state.isActiveForPackage(pkg, processName)) return

                            @Suppress("UNCHECKED_CAST")
                            val configs = (param.args[0] as? List<OutputConfiguration>).orEmpty()
                            val surfaces = configs.mapNotNull { it.surface }
                            if (surfaces.isEmpty()) return

                            val session = getOrCreateDeviceSession(device, pkg, processName)
                            val bridgeSurface = prepareBridgeForOutputs(session, surfaces)
                            param.args[0] = listOf(OutputConfiguration(bridgeSurface))

                            val origCb = param.args[1] as? CameraCaptureSession.StateCallback
                            if (origCb != null) {
                                param.args[1] = wrapSessionStateCallback(origCb, session, surfaces, pkg, processName)
                            }
                        }
                    }
                )
            }
        }

        // 3. API 28+: createCaptureSession(SessionConfiguration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    deviceImplClass,
                    "createCaptureSession",
                    SessionConfiguration::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val device = param.thisObject ?: return
                            val state = HookMediaBridge.readRuntimeState(pkg, processName, forceRefresh = true)
                            if (!state.isActiveForPackage(pkg, processName)) return

                            val origConfig = param.args.firstOrNull() as? SessionConfiguration ?: return
                            val surfaces = origConfig.outputConfigurations.mapNotNull { it.surface }
                            if (surfaces.isEmpty()) return

                            val session = getOrCreateDeviceSession(device, pkg, processName)
                            val bridgeSurface = prepareBridgeForOutputs(session, surfaces)
                            val wrappedCb = wrapSessionStateCallback(
                                origConfig.stateCallback,
                                session,
                                surfaces,
                                pkg,
                                processName
                            )

                            val newConfig = SessionConfiguration(
                                origConfig.sessionType,
                                listOf(OutputConfiguration(bridgeSurface)),
                                origConfig.executor,
                                wrappedCb
                            ).apply {
                                origConfig.sessionParameters?.let { sessionParameters = it }
                                origConfig.inputConfiguration?.let { inputConfiguration = it }
                            }
                            param.args[0] = newConfig
                        }
                    }
                )
            }
        }

        // 4. close()
        runCatching {
            XposedHelpers.findAndHookMethod(
                deviceImplClass,
                "close",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val device = param.thisObject ?: return
                        synchronized(deviceSessions) {
                            deviceSessions.remove(device)?.release()
                        }
                    }
                }
            )
        }
    }

    private fun prepareBridgeForOutputs(
        session: DeviceBridgeSession,
        appSurfaces: List<Surface>
    ): Surface {
        val yuvSpec = appSurfaces
            .mapNotNull { HookMediaBridge.getImageReaderSpec(it) }
            .firstOrNull { it.format == ImageFormat.YUV_420_888 }
        session.appSurfaces.addAll(appSurfaces)
        return session.getOrCreateBridgeSurface(yuvSpec)
    }

    private fun wrapSessionStateCallback(
        delegate: CameraCaptureSession.StateCallback,
        session: DeviceBridgeSession,
        surfaces: List<Surface>,
        pkg: String,
        processName: String
    ): CameraCaptureSession.StateCallback {
        return object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                surfaces.forEach { surface ->
                    if (surface.isValid) {
                        HookMediaBridge.startRendererForSurface(
                            surface = surface,
                            packageName = pkg,
                            processName = processName,
                            passthroughProvider = { session.copyLatestRealFrame() }
                        )
                    }
                }
                delegate.onConfigured(captureSession)
            }

            override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                delegate.onConfigureFailed(captureSession)
            }

            override fun onReady(captureSession: CameraCaptureSession) {
                delegate.onReady(captureSession)
            }

            override fun onActive(captureSession: CameraCaptureSession) {
                delegate.onActive(captureSession)
            }

            override fun onCaptureQueueEmpty(captureSession: CameraCaptureSession) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    delegate.onCaptureQueueEmpty(captureSession)
                }
            }

            override fun onClosed(captureSession: CameraCaptureSession) {
                surfaces.forEach { HookMediaBridge.stopRendererForSurface(it) }
                delegate.onClosed(captureSession)
            }

            override fun onSurfacePrepared(captureSession: CameraCaptureSession, surface: Surface) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    delegate.onSurfacePrepared(captureSession, surface)
                }
            }
        }
    }

    private fun hookCaptureRequestBuilder(
        lpparam: XC_LoadPackage.LoadPackageParam,
        pkg: String,
        processName: String
    ) {
        val builderClass = runCatching {
            XposedHelpers.findClass("android.hardware.camera2.CaptureRequest\$Builder", lpparam.classLoader)
        }.getOrNull() ?: return

        runCatching {
            XposedHelpers.findAndHookMethod(
                builderClass,
                "addTarget",
                Surface::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val builder = param.thisObject ?: return
                        val targetSurface = param.args.firstOrNull() as? Surface ?: return
                        val state = HookMediaBridge.readRuntimeState(pkg, processName)
                        if (!state.isActiveForPackage(pkg, processName)) return

                        val device = synchronized(builderToDevice) { builderToDevice[builder] } ?: return
                        val session = getOrCreateDeviceSession(device, pkg, processName)
                        val spec = HookMediaBridge.getImageReaderSpec(targetSurface)
                        val bridgeSurface = session.getOrCreateBridgeSurface(
                            spec?.takeIf { it.format == ImageFormat.YUV_420_888 }
                        )
                        session.appSurfaces.add(targetSurface)
                        param.args[0] = bridgeSurface
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
                    }
                }
            )
        }
    }

    private fun hookMediaRecorder(lpparam: XC_LoadPackage.LoadPackageParam, pkg: String, processName: String) {
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
                        val state = HookMediaBridge.readRuntimeState(pkg, processName, forceRefresh = true)
                        if (!state.isActiveForPackage(pkg, processName)) return

                        mediaRecorderSurfaces.forEach { surface ->
                            if (surface.isValid) {
                                HookMediaBridge.startRendererForSurface(surface, pkg, processName)
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

    private fun hookImageReader(lpparam: XC_LoadPackage.LoadPackageParam) {
        val readerClass = runCatching {
            XposedHelpers.findClass("android.media.ImageReader", lpparam.classLoader)
        }.getOrNull() ?: return

        // Track ImageReader dimensions & format on creation so ImageWriter writes exact matching YUV_420_888 frames
        runCatching {
            XposedHelpers.findAndHookMethod(
                readerClass,
                "newInstance",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val reader = param.result as? ImageReader ?: return
                        val w = (param.args[0] as? Int) ?: reader.width
                        val h = (param.args[1] as? Int) ?: reader.height
                        val fmt = (param.args[2] as? Int) ?: reader.imageFormat
                        val maxImages = (param.args[3] as? Int) ?: reader.maxImages
                        runCatching {
                            HookMediaBridge.registerImageReaderSurface(
                                reader.surface,
                                ReaderSurfaceSpec(w, h, fmt, maxImages)
                            )
                        }
                    }
                }
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                XposedHelpers.findAndHookMethod(
                    readerClass,
                    "newInstance",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val reader = param.result as? ImageReader ?: return
                            val w = (param.args[0] as? Int) ?: reader.width
                            val h = (param.args[1] as? Int) ?: reader.height
                            val fmt = (param.args[2] as? Int) ?: reader.imageFormat
                            val maxImages = (param.args[3] as? Int) ?: reader.maxImages
                            runCatching {
                                HookMediaBridge.registerImageReaderSurface(
                                    reader.surface,
                                    ReaderSurfaceSpec(w, h, fmt, maxImages)
                                )
                            }
                        }
                    }
                )
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                readerClass,
                "close",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val reader = param.thisObject as? ImageReader ?: return
                        runCatching { reader.surface }.getOrNull()?.let { surface ->
                            HookMediaBridge.unregisterImageReaderSurface(surface)
                            HookMediaBridge.stopRendererForSurface(surface)
                        }
                    }
                }
            )
        }
    }
}
