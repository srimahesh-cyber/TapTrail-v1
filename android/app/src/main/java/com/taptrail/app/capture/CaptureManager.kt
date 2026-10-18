package com.taptrail.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.taptrail.app.guidance.CoordinateMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

data class CapturedFrame(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int
)

class CaptureManager(
    private val context: Context,
    private val coordinateMapper: CoordinateMapper
) {
    private val mediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var activeWidth = 0
    private var activeHeight = 0

    @Volatile
    private var pendingFrameDeferred: CompletableDeferred<CapturedFrame?>? = null

    init {
        val thread = HandlerThread("TapTrailCaptureThread").apply { start() }
        handlerThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    /**
     * Initializes or re-initializes MediaProjection with the granted token.
     */
    fun initializeProjection(resultCode: Int, data: Intent) {
        releaseProjection()
        val projection = mediaProjectionManager.getMediaProjection(resultCode, data) ?: run {
            Log.e(TAG, "Failed to get MediaProjection from manager")
            return
        }
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system.")
                releaseProjection()
            }
        }, backgroundHandler)
        mediaProjection = projection

        setupVirtualDisplay(projection)
    }

    private fun setupVirtualDisplay(projection: MediaProjection) {
        try {
            val screenSize = coordinateMapper.getScreenDimensions()
            activeWidth = screenSize.x
            activeHeight = screenSize.y
            val density = context.resources.displayMetrics.densityDpi

            val reader = ImageReader.newInstance(activeWidth, activeHeight, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            reader.setOnImageAvailableListener({ r ->
                var image: Image? = null
                try {
                    image = r.acquireLatestImage()
                    if (image != null) {
                        val deferred = pendingFrameDeferred
                        if (deferred != null && !deferred.isCompleted) {
                            pendingFrameDeferred = null
                            val frame = processImageToJpeg(image, activeWidth, activeHeight)
                            deferred.complete(frame)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error acquiring latest image", e)
                } finally {
                    image?.close()
                }
            }, backgroundHandler)

            virtualDisplay = projection.createVirtualDisplay(
                "TapTrailScreenCapture",
                activeWidth,
                activeHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                backgroundHandler
            )
            Log.d(TAG, "VirtualDisplay created successfully ($activeWidth x $activeHeight)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create VirtualDisplay", e)
            releaseProjection()
        }
    }

    fun hasProjection(): Boolean = mediaProjection != null && virtualDisplay != null

    /**
     * Captures a single frame from the screen, returning JPEG bytes and dimensions.
     */
    suspend fun captureScreenSingleFrame(timeoutMs: Long = 3000L): CapturedFrame? = withContext(Dispatchers.IO) {
        val projection = mediaProjection ?: run {
            Log.e(TAG, "Cannot capture: MediaProjection is null.")
            return@withContext null
        }
        val reader = imageReader ?: run {
            Log.e(TAG, "Cannot capture: ImageReader is null.")
            return@withContext null
        }

        // Drain stale images
        try {
            reader.acquireLatestImage()?.close()
        } catch (e: Exception) {
            // ignore
        }

        val deferred = CompletableDeferred<CapturedFrame?>()
        pendingFrameDeferred = deferred

        var frame = withTimeoutOrNull(timeoutMs) {
            deferred.await()
        }
        pendingFrameDeferred = null

        // Fallback: if timeout fired, attempt to acquire whatever is in the buffer
        if (frame == null) {
            try {
                val fallbackImg = reader.acquireLatestImage()
                if (fallbackImg != null) {
                    try {
                        frame = processImageToJpeg(fallbackImg, activeWidth, activeHeight)
                    } finally {
                        fallbackImg.close()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fallback frame extraction failed: ${e.message}")
            }
        }

        frame
    }

    private fun processImageToJpeg(image: Image, targetWidth: Int, targetHeight: Int): CapturedFrame? {
        val planes = image.planes
        if (planes.isEmpty()) return null

        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * targetWidth

        val bitmapWidth = targetWidth + rowPadding / pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, targetHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)

        val finalBitmap = if (rowPadding == 0 && bitmapWidth == targetWidth) {
            bitmap
        } else {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, targetWidth, targetHeight)
            bitmap.recycle()
            cropped
        }

        val outputStream = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val jpegBytes = outputStream.toByteArray()
        finalBitmap.recycle()

        return CapturedFrame(
            jpegBytes = jpegBytes,
            width = targetWidth,
            height = targetHeight
        )
    }

    fun releaseProjection() {
        pendingFrameDeferred?.complete(null)
        pendingFrameDeferred = null
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaProjection", e)
        }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    fun destroy() {
        releaseProjection()
        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null
    }

    companion object {
        private const val TAG = "TapTrailCaptureManager"
    }
}
