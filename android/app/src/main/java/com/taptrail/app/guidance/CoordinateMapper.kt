package com.taptrail.app.guidance

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.graphics.RectF
import com.taptrail.app.R

data class MappedTarget(
    val buttonX: Int,
    val buttonY: Int,
    val bubbleX: Int,
    val bubbleY: Int,
    val targetCenterX: Int,
    val targetCenterY: Int,
    val targetRect: RectF? = null
)

class CoordinateMapper(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun getScreenDimensions(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            Point(metrics.widthPixels, metrics.heightPixels)
        }
    }

    fun getStatusBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    fun getNavigationBarHeight(): Int {
        val resourceId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    /**
     * Maps normalized bounding box [x1, y1, x2, y2] (values in 0.0..1.0) to screen pixel coordinates.
     * Calculates button placement to the left (or right) of the target element, and bubble placement.
     */
    fun mapTarget(bboxNorm: List<Float>?): MappedTarget? {
        if (bboxNorm == null || bboxNorm.size != 4) return null

        val x1 = bboxNorm[0]
        val y1 = bboxNorm[1]
        val x2 = bboxNorm[2]
        val y2 = bboxNorm[3]

        // Validate bounds
        if (x1 < 0f || y1 < 0f || x2 > 1f || y2 > 1f || x1 >= x2 || y1 >= y2) {
            Log.w(TAG, "Invalid normalized coordinates: $bboxNorm")
            return null
        }

        val screenSize = getScreenDimensions()
        val screenWidth = screenSize.x
        val screenHeight = screenSize.y
        val statusBarHeight = getStatusBarHeight()
        val navBarHeight = getNavigationBarHeight()

        val buttonWindowSize = context.resources.getDimensionPixelSize(R.dimen.overlay_button_window_size)
        val bubbleMaxWidth = context.resources.getDimensionPixelSize(R.dimen.bubble_max_width)
        val margin = context.resources.getDimensionPixelSize(R.dimen.bubble_margin)

        val targetLeft = (x1 * screenWidth).toInt()
        val targetTop = (y1 * screenHeight).toInt()
        val targetRight = (x2 * screenWidth).toInt()
        val targetBottom = (y2 * screenHeight).toInt()

        val targetCenterX = ((targetLeft + targetRight) / 2).coerceIn(0, screenWidth)
        val targetCenterY = ((targetTop + targetBottom) / 2).coerceIn(statusBarHeight, screenHeight - navBarHeight)

        // Try to place the button immediately to the left of the target element
        var buttonX = targetLeft - buttonWindowSize
        // If not enough room on the left (e.g. target starts near screen edge), place on the right or inside left
        if (buttonX < 0) {
            buttonX = if (targetRight + buttonWindowSize <= screenWidth) {
                targetRight - margin
            } else {
                (targetLeft + margin).coerceIn(margin, screenWidth - buttonWindowSize - margin)
            }
        }

        // Align button vertically with the center of the target element
        var buttonY = targetCenterY - (buttonWindowSize / 2)
        val minY = statusBarHeight + margin
        val maxY = screenHeight - navBarHeight - buttonWindowSize - margin
        buttonY = buttonY.coerceIn(minY, maxY)

        // Smart bubble placement:
        // If target is in the lower half of screen, place bubble ABOVE target
        // If target is in the upper half of screen, place bubble BELOW target
        val bubbleWidth = bubbleMaxWidth.coerceAtMost(screenWidth - 2 * margin)
        val bubbleX = (targetCenterX - bubbleWidth / 2).coerceIn(margin, screenWidth - bubbleWidth - margin)

        val estimatedBubbleHeight = (150 * context.resources.displayMetrics.density).toInt()
        val bubbleY = if (targetCenterY > (screenHeight / 2)) {
            // Place ABOVE target
            (targetTop - estimatedBubbleHeight - margin).coerceAtLeast(minY)
        } else {
            // Place BELOW target
            (targetBottom + margin).coerceAtMost(screenHeight - navBarHeight - estimatedBubbleHeight - margin)
        }

        return MappedTarget(
            buttonX = buttonX,
            buttonY = buttonY,
            bubbleX = bubbleX,
            bubbleY = bubbleY,
            targetCenterX = targetCenterX,
            targetCenterY = targetCenterY,
            targetRect = RectF(targetLeft.toFloat(), targetTop.toFloat(), targetRight.toFloat(), targetBottom.toFloat())
        )
    }

    companion object {
        private const val TAG = "CoordinateMapper"
    }
}
