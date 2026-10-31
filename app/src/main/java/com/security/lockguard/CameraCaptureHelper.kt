package com.security.lockguard

import android.Manifest
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

object CameraCaptureHelper {
    private const val TAG = "CameraCaptureHelper"
    private const val MAX_PENDING_PHOTOS = 5

    @Volatile
    private var isCapturing = false

    fun getPendingAlertsDir(context: Context): File {
        val storageCtx = context.applicationContext.createDeviceProtectedStorageContext()
        val dir = File(storageCtx.cacheDir, "pending_alerts")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Ensures cache never holds more than MAX_PENDING_PHOTOS (5 photos ~ 200KB total).
     * Deletes oldest files if limit is exceeded.
     */
    fun enforceCacheLimit(context: Context) {
        try {
            val dir = getPendingAlertsDir(context)
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return
            if (files.size >= MAX_PENDING_PHOTOS) {
                val sorted = files.sortedBy { it.lastModified() }
                val toDelete = sorted.size - MAX_PENDING_PHOTOS + 1
                for (i in 0 until toDelete) {
                    sorted[i].delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error enforcing cache limit", e)
        }
    }

    fun captureFrontPhoto(context: Context, onComplete: (File?) -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Camera permission not granted")
            onComplete(null)
            return
        }

        if (isCapturing) {
            Log.d(TAG, "Capture already in progress")
            return
        }
        isCapturing = true

        val handlerThread = HandlerThread("LockGuardCameraThread").apply { start() }
        val backgroundHandler = Handler(handlerThread.looper)

        fun cleanupAndFinish(file: File?) {
            isCapturing = false
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

            val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
            val dummySurfaceTexture = SurfaceTexture(10)
            dummySurfaceTexture.setDefaultBufferSize(640, 480)
            val previewSurface = Surface(dummySurfaceTexture)

            var cameraDeviceRef: CameraDevice? = null
            var hasSaved = false

            fun closeResources() {
                try { cameraDeviceRef?.close() } catch (_: Exception) {}
                try { imageReader.close() } catch (_: Exception) {}
                try { previewSurface.release() } catch (_: Exception) {}
                try { dummySurfaceTexture.release() } catch (_: Exception) {}
            }

            imageReader.setOnImageAvailableListener({ reader ->
                if (hasSaved) return@setOnImageAvailableListener
                val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
                if (image != null) {
                    hasSaved = true
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
                        closeResources()
                        cleanupAndFinish(outputFile)
                    }
                }
            }, backgroundHandler)

            cameraManager.openCamera(frontCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDeviceRef = camera
                    try {
                        val surfaces = Arrays.asList(previewSurface, imageReader.surface)
                        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val previewReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(previewSurface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    }
                                    session.setRepeatingRequest(previewReq.build(), null, backgroundHandler)

                                    backgroundHandler.postDelayed({
                                        try {
                                            val captureReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                addTarget(imageReader.surface)
                                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                                            }
                                            session.stopRepeating()
                                            session.capture(captureReq.build(), null, backgroundHandler)
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Still capture failed", e)
                                            closeResources()
                                            cleanupAndFinish(null)
                                        }
                                    }, 450L)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Preview request failed", e)
                                    closeResources()
                                    cleanupAndFinish(null)
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                closeResources()
                                cleanupAndFinish(null)
                            }
                        }, backgroundHandler)
                    } catch (e: Exception) {
                        closeResources()
                        cleanupAndFinish(null)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    cameraDeviceRef = camera
                    closeResources()
                    if (!hasSaved) cleanupAndFinish(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    cameraDeviceRef = camera
                    closeResources()
                    if (!hasSaved) cleanupAndFinish(null)
                }
            }, backgroundHandler)

            backgroundHandler.postDelayed({
                if (!hasSaved && isCapturing) {
                    Log.w(TAG, "Camera capture timed out")
                    closeResources()
                    cleanupAndFinish(null)
                }
            }, 6000L)

        } catch (e: SecurityException) {
            cleanupAndFinish(null)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected camera error", e)
            cleanupAndFinish(null)
        }
    }
}
