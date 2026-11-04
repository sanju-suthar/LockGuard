package com.security.lockguard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean

object CameraCaptureHelper {
    private const val TAG = "CameraCaptureHelper"
    private const val MAX_PENDING_PHOTOS = 5
    private val isCapturing = AtomicBoolean(false)

    fun getPendingAlertsDir(context: Context): File {
        val storageCtx = context.applicationContext.createDeviceProtectedStorageContext()
        val dir = File(storageCtx.cacheDir, "pending_alerts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Ensures cache never holds more than MAX_PENDING_PHOTOS (5 photos ~ 200KB total).
     * Deletes oldest files (and their .loc metadata) if limit is exceeded.
     */
    fun enforceCacheLimit(context: Context) {
        try {
            val dir = getPendingAlertsDir(context)
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return
            if (files.size >= MAX_PENDING_PHOTOS) {
                val sorted = files.sortedBy { it.lastModified() }
                val toDelete = sorted.size - MAX_PENDING_PHOTOS + 1
                for (i in 0 until toDelete) {
                    val oldFile = sorted[i]
                    try { File(oldFile.parentFile, oldFile.nameWithoutExtension + ".loc").delete() } catch (_: Exception) {}
                    try { oldFile.delete() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enforcing cache limit", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun captureFrontPhoto(context: Context, onComplete: (File?) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Camera permission not granted")
            onComplete(null)
            return
        }

        // Bug 1 fix: Always invoke onComplete(null) if capture is already running so UI never hangs
        if (!isCapturing.compareAndSet(false, true)) {
            Log.d(TAG, "Capture already in progress")
            onComplete(null)
            return
        }

        val handlerThread = HandlerThread("LockGuardCameraThread").apply { start() }
        val backgroundHandler = Handler(handlerThread.looper)

        var imageReader: ImageReader? = null
        var dummySurfaceTexture: SurfaceTexture? = null
        var previewSurface: Surface? = null
        var cameraDeviceRef: CameraDevice? = null
        val hasFinished = AtomicBoolean(false)

        fun closeResources() {
            try { cameraDeviceRef?.close() } catch (_: Exception) {}
            try { imageReader?.close() } catch (_: Exception) {}
            try { previewSurface?.release() } catch (_: Exception) {}
            try { dummySurfaceTexture?.release() } catch (_: Exception) {}
        }

        fun cleanupAndFinish(file: File?) {
            if (!hasFinished.compareAndSet(false, true)) return
            closeResources()
            isCapturing.set(false)
            try {
                handlerThread.quitSafely()
            } catch (_: Exception) {}
            onComplete(file)
        }

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var frontCameraId: String? = null
            var sensorOrientation = 270

            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    frontCameraId = id
                    sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
                    break
                }
            }

            if (frontCameraId == null && cameraManager.cameraIdList.isNotEmpty()) {
                frontCameraId = cameraManager.cameraIdList[0]
            }

            if (frontCameraId == null) {
                cleanupAndFinish(null)
                return
            }

            // Compute upright portrait JPEG orientation for front camera
            val jpegOrientation = (sensorOrientation + 360) % 360

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            dummySurfaceTexture = SurfaceTexture(10).apply {
                setDefaultBufferSize(640, 480)
            }
            previewSurface = Surface(dummySurfaceTexture)

            imageReader.setOnImageAvailableListener({ reader ->
                val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
                if (image != null) {
                    var outputFile: File? = null
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        enforceCacheLimit(context)
                        val pendingDir = getPendingAlertsDir(context)
                        outputFile = File(pendingDir, "intruder_${System.currentTimeMillis()}.jpg")
                        FileOutputStream(outputFile).use { it.write(bytes) }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed saving JPEG", e)
                    } finally {
                        image.close()
                        cleanupAndFinish(outputFile)
                    }
                }
            }, backgroundHandler)

            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDeviceRef = camera
                    try {
                        val surfaces = Arrays.asList(previewSurface!!, imageReader!!.surface)
                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    // Warm up auto-exposure for 750ms so photo is bright even in indoor/low light
                                    val previewReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(previewSurface!!)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    }
                                    session.setRepeatingRequest(previewReq.build(), null, backgroundHandler)

                                    backgroundHandler.postDelayed({
                                        try {
                                            val captureReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                addTarget(imageReader!!.surface)
                                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                                            }
                                            session.stopRepeating()
                                            session.capture(captureReq.build(), null, backgroundHandler)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Still capture failed", e)
                                            cleanupAndFinish(null)
                                        }
                                    }, 750L)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Preview request failed", e)
                                    cleanupAndFinish(null)
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                cleanupAndFinish(null)
                            }
                        }, backgroundHandler)
                    } catch (e: Exception) {
                        cleanupAndFinish(null)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDeviceRef = camera
                    cleanupAndFinish(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraDeviceRef = camera
                    cleanupAndFinish(null)
                }
            }, backgroundHandler)

            // Safety timeout (6.5 seconds)
            backgroundHandler.postDelayed({
                cleanupAndFinish(null)
            }, 6500L)

        } catch (e: SecurityException) {
            cleanupAndFinish(null)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected camera error", e)
            cleanupAndFinish(null)
        }
    }
}
