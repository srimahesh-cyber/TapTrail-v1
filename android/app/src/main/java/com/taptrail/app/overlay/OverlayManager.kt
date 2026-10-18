package com.taptrail.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.taptrail.app.R
import com.taptrail.app.guidance.CoordinateMapper
import com.taptrail.app.guidance.MappedTarget
import com.taptrail.app.network.GuidanceResponse
import com.taptrail.app.ui.ConsentDialog
import com.taptrail.app.ui.HelpRequestDialog
import kotlin.math.hypot

class OverlayManager(
    private val context: Context,
    private val coordinateMapper: CoordinateMapper,
    private val onButtonTapped: () -> Unit,
    private val onNextStepTapped: () -> Unit,
    private val onStopTapped: () -> Unit,
    private val onRetryTapped: () -> Unit,
    private val onCloseTapped: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Floating Button
    private var buttonView: View? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var isButtonAdded = false

    // Instruction Bubble
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var isBubbleAdded = false

    // Visual Trail Overlay
    private var trailOverlayView: TrailOverlayView? = null
    private var trailParams: WindowManager.LayoutParams? = null
    private var isTrailAdded = false

    // Dismiss Target Zone
    private var dismissTargetView: View? = null
    private var dismissParams: WindowManager.LayoutParams? = null
    private var isDismissTargetAdded = false
    private var isOverDismissTarget = false

    // State
    var isInGuidanceMode: Boolean = false
        private set

    // Dialogs
    private var helpRequestDialog: HelpRequestDialog? = null
    private var consentDialog: ConsentDialog? = null

    // Tracking touch for drag vs tap
    private var touchDownRawX = 0f
    private var touchDownRawY = 0f
    private var initialX = 0
    private var initialY = 0
    private var isDragging = false

    private val overlayType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    init {
        createFloatingButton()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingButton() {
        val inflater = LayoutInflater.from(context)
        buttonView = inflater.inflate(R.layout.overlay_floating_button, null)

        val buttonWindowSize = context.resources.getDimensionPixelSize(R.dimen.overlay_button_window_size)
        val screenSize = coordinateMapper.getScreenDimensions()

        buttonParams = WindowManager.LayoutParams(
            buttonWindowSize,
            buttonWindowSize,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Default position: mid-right edge of screen
            x = screenSize.x - buttonWindowSize - 16
            y = screenSize.y / 3
        }

        buttonView?.setOnTouchListener { _, event ->
            val params = buttonParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownRawX = event.rawX
                    touchDownRawY = event.rawY
                    initialX = params.x
                    initialY = params.y
                    isDragging = false
                    isOverDismissTarget = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - touchDownRawX
                    val deltaY = event.rawY - touchDownRawY

                    if (!isDragging && hypot(deltaX.toDouble(), deltaY.toDouble()) > touchSlop) {
                        isDragging = true
                        showDismissTarget()
                    }

                    if (isDragging) {
                        params.x = (initialX + deltaX).toInt()
                        params.y = (initialY + deltaY).toInt()
                        clampButtonPosition(params)
                        if (isButtonAdded && buttonView != null) {
                            try {
                                windowManager.updateViewLayout(buttonView, params)
                            } catch (e: Exception) {
                                // ignore
                            }
                        }

                        // Check proximity to dismiss target
                        checkDismissProximity(params.x, params.y)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        hideDismissTarget()
                        if (isOverDismissTarget) {
                            buttonView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onCloseTapped()
                        } else {
                            if (!isInGuidanceMode) {
                                snapButtonToEdge()
                            }
                        }
                    } else {
                        // User tapped the button
                        buttonView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onButtonTapped()
                    }
                    isDragging = false
                    isOverDismissTarget = false
                    true
                }
                else -> false
            }
        }
    }

    private fun checkDismissProximity(btnX: Int, btnY: Int) {
        val screenSize = coordinateMapper.getScreenDimensions()
        val navBarHeight = coordinateMapper.getNavigationBarHeight()
        val buttonSize = context.resources.getDimensionPixelSize(R.dimen.overlay_button_window_size)

        val btnCenterX = btnX + buttonSize / 2f
        val btnCenterY = btnY + buttonSize / 2f

        val dismissCenterX = screenSize.x / 2f
        val dismissOffsetPx = (80 * context.resources.displayMetrics.density)
        val dismissCenterY = screenSize.y - navBarHeight - dismissOffsetPx

        val dist = hypot((btnCenterX - dismissCenterX).toDouble(), (btnCenterY - dismissCenterY).toDouble())
        val hoverRadius = (75 * context.resources.displayMetrics.density)

        if (dist < hoverRadius) {
            if (!isOverDismissTarget) {
                isOverDismissTarget = true
                highlightDismissTarget(true)
                buttonView?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            }
        } else {
            if (isOverDismissTarget) {
                isOverDismissTarget = false
                highlightDismissTarget(false)
            }
        }
    }

    private fun ensureDismissTarget() {
        if (dismissTargetView == null) {
            val inflater = LayoutInflater.from(context)
            dismissTargetView = inflater.inflate(R.layout.overlay_dismiss_target, null)
            dismissTargetView?.alpha = 0f

            dismissParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (48 * context.resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun showDismissTarget() {
        ensureDismissTarget()
        val dView = dismissTargetView ?: return
        val dParams = dismissParams ?: return

        if (!isDismissTargetAdded) {
            try {
                windowManager.addView(dView, dParams)
                isDismissTargetAdded = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        dView.animate().alpha(1f).setDuration(160).start()
    }

    private fun hideDismissTarget() {
        val dView = dismissTargetView ?: return
        dView.animate().alpha(0f).setDuration(160).withEndAction {
            if (isDismissTargetAdded && dismissTargetView != null) {
                try {
                    windowManager.removeView(dismissTargetView)
                } catch (e: Exception) {
                    // ignore
                }
                isDismissTargetAdded = false
            }
        }.start()
    }

    private fun highlightDismissTarget(highlight: Boolean) {
        val circle = dismissTargetView?.findViewById<View>(R.id.dismissCircle) ?: return
        if (highlight) {
            circle.animate().scaleX(1.2f).scaleY(1.2f).setDuration(120).start()
        } else {
            circle.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
        }
    }

    private fun snapButtonToEdge() {
        val params = buttonParams ?: return
        val screenSize = coordinateMapper.getScreenDimensions()
        val buttonSize = context.resources.getDimensionPixelSize(R.dimen.overlay_button_window_size)
        val targetX = if (params.x + buttonSize / 2 < screenSize.x / 2) {
            0
        } else {
            screenSize.x - buttonSize
        }
        val startX = params.x
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val fraction = va.animatedFraction
                params.x = (startX + (targetX - startX) * fraction).toInt()
                clampButtonPosition(params)
                if (isButtonAdded && buttonView != null) {
                    try {
                        windowManager.updateViewLayout(buttonView, params)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }
        animator.start()
    }

    fun showFloatingButton() {
        if (!isButtonAdded && buttonView != null && buttonParams != null) {
            try {
                windowManager.addView(buttonView, buttonParams)
                isButtonAdded = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setButtonLoading(loading: Boolean) {
        buttonView?.post {
            val pb = buttonView?.findViewById<ProgressBar>(R.id.pbLoading)
            val iv = buttonView?.findViewById<ImageView>(R.id.ivFloatingIcon)
            pb?.visibility = if (loading) View.VISIBLE else View.GONE
            iv?.visibility = if (loading) View.GONE else View.VISIBLE
        }
    }

    /**
     * Trigger a subtle beacon pulse ring around the button to draw user attention to the target.
     */
    fun triggerBeaconPulse() {
        val pulseRing = buttonView?.findViewById<View>(R.id.viewPulseRing) ?: return
        pulseRing.post {
            pulseRing.alpha = 0.85f
            pulseRing.scaleX = 1.0f
            pulseRing.scaleY = 1.0f

            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.55f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.55f)
            val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0.85f, 0.0f)

            ObjectAnimator.ofPropertyValuesHolder(pulseRing, scaleX, scaleY, alpha).apply {
                duration = 650
                repeatCount = 2
                repeatMode = ValueAnimator.RESTART
                interpolator = AccelerateDecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        pulseRing.alpha = 0f
                        pulseRing.scaleX = 1.0f
                        pulseRing.scaleY = 1.0f
                    }
                })
                start()
            }
        }
    }

    /**
     * Animate moving the floating button to a specific target coordinate.
     */
    fun animateButtonTo(targetX: Int, targetY: Int, onEnd: (() -> Unit)? = null) {
        val params = buttonParams ?: return
        val startX = params.x
        val startY = params.y

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val fraction = va.animatedFraction
                params.x = (startX + (targetX - startX) * fraction).toInt()
                params.y = (startY + (targetY - startY) * fraction).toInt()
                clampButtonPosition(params)
                if (isButtonAdded && buttonView != null) {
                    try {
                        windowManager.updateViewLayout(buttonView, params)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                buttonView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                triggerBeaconPulse()
                onEnd?.invoke()
            }
        })
        animator.start()
    }

    fun resetFloatingButtonPosition() {
        isInGuidanceMode = false
        val screenSize = coordinateMapper.getScreenDimensions()
        val buttonSize = context.resources.getDimensionPixelSize(R.dimen.overlay_button_window_size)
        val defaultX = screenSize.x - buttonSize - 16
        val defaultY = screenSize.y / 3
        animateButtonTo(defaultX, defaultY)
    }

    private fun clampButtonPosition(params: WindowManager.LayoutParams) {
        val screenSize = coordinateMapper.getScreenDimensions()
        val buttonSize = context.resources.getDimensionPixelSize(R.dimen.overlay_button_window_size)
        val statusBarHeight = coordinateMapper.getStatusBarHeight()
        val navBarHeight = coordinateMapper.getNavigationBarHeight()

        params.x = params.x.coerceIn(0, screenSize.x - buttonSize)
        params.y = params.y.coerceIn(statusBarHeight, screenSize.y - navBarHeight - buttonSize)
    }

    private fun ensureBubbleView() {
        if (bubbleView == null) {
            val inflater = LayoutInflater.from(context)
            val bView = inflater.inflate(R.layout.overlay_instruction_bubble, null)
            bubbleView = bView

            bubbleParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val screenSize = coordinateMapper.getScreenDimensions()
                x = (screenSize.x - context.resources.getDimensionPixelSize(R.dimen.bubble_max_width)) / 2
                y = screenSize.y / 3
            }
        }

        bubbleView?.findViewById<ImageButton>(R.id.btnCloseBubble)?.setOnClickListener {
            hideInstructionBubble()
            onCloseTapped()
        }
        bubbleView?.findViewById<Button>(R.id.btnStopGuidance)?.setOnClickListener {
            onStopTapped()
        }
        bubbleView?.findViewById<Button>(R.id.btnNextStep)?.setOnClickListener {
            onNextStepTapped()
        }
        bubbleView?.findViewById<Button>(R.id.btnRetryGuidance)?.setOnClickListener {
            onRetryTapped()
        }
    }

    /**
     * Show or update the instruction bubble.
     */
    fun showInstructionBubble(
        stepIndex: Int,
        response: GuidanceResponse,
        placement: MappedTarget?,
        isError: Boolean = false
    ) {
        isInGuidanceMode = true
        ensureBubbleView()

        val bView = bubbleView ?: return
        val bParams = bubbleParams ?: return

        // Update bubble contents
        val tvStep = bView.findViewById<TextView>(R.id.tvStepLabel)
        val tvStepBadge = bView.findViewById<TextView>(R.id.tvStepBadge)
        val tvMessage = bView.findViewById<TextView>(R.id.tvInstructionMessage)
        val tvFallback = bView.findViewById<TextView>(R.id.tvFallbackNotice)
        val btnNext = bView.findViewById<Button>(R.id.btnNextStep)
        val btnRetry = bView.findViewById<Button>(R.id.btnRetryGuidance)
        val pbAnalyzing = bView.findViewById<ProgressBar>(R.id.pbAnalyzing)

        val layoutExpanded = bView.findViewById<View>(R.id.layoutExpandedContent)
        val layoutCollapsed = bView.findViewById<View>(R.id.layoutCollapsedBar)
        val btnMinimize = bView.findViewById<ImageButton>(R.id.btnMinimizeBubble)
        val btnExpand = bView.findViewById<ImageButton>(R.id.btnExpandBubble)
        val tvCollapsed = bView.findViewById<TextView>(R.id.tvCollapsedLabel)

        // Reset to expanded state on new step
        layoutExpanded?.visibility = View.VISIBLE
        layoutCollapsed?.visibility = View.GONE

        btnMinimize?.setOnClickListener {
            layoutExpanded?.visibility = View.GONE
            layoutCollapsed?.visibility = View.VISIBLE
            try {
                windowManager.updateViewLayout(bView, bParams)
            } catch (e: Exception) {
                // ignore
            }
        }

        val expandAction = View.OnClickListener {
            layoutCollapsed?.visibility = View.GONE
            layoutExpanded?.visibility = View.VISIBLE
            try {
                windowManager.updateViewLayout(bView, bParams)
            } catch (e: Exception) {
                // ignore
            }
        }
        btnExpand?.setOnClickListener(expandAction)
        layoutCollapsed?.setOnClickListener(expandAction)

        pbAnalyzing?.visibility = View.GONE

        if (isError) {
            tvStepBadge?.text = "ERROR"
            tvStepBadge?.setTextColor(context.getColor(R.color.taptrail_error))
            tvStep?.text = "Guidance Paused"
            tvStep?.setTextColor(context.getColor(R.color.taptrail_error))
            tvMessage?.text = response.message
            tvFallback?.visibility = View.GONE
            btnRetry?.visibility = View.VISIBLE
            btnNext?.visibility = View.GONE
            tvCollapsed?.text = "Error: Tap to expand ▾"
        } else {
            val userStepNumber = stepIndex + 1
            if (response.status == "complete") {
                tvStepBadge?.text = "DONE"
                tvStepBadge?.setTextColor(context.getColor(R.color.taptrail_accent))
                tvStep?.text = "Task Completed"
                tvStep?.setTextColor(context.getColor(R.color.taptrail_accent))
                btnNext?.visibility = View.VISIBLE
                btnNext?.text = "New Task ✦"
                btnNext?.setOnClickListener {
                    hideInstructionBubble()
                    onButtonTapped()
                }
                tvCollapsed?.text = "Task Completed ✓"
            } else {
                tvStepBadge?.text = "STEP $userStepNumber"
                tvStepBadge?.setTextColor(context.getColor(R.color.taptrail_primary))
                val stepTitle = response.targetLabel ?: "Action Item"
                tvStep?.text = stepTitle
                tvStep?.setTextColor(context.getColor(R.color.taptrail_on_surface))
                btnNext?.visibility = View.VISIBLE
                btnNext?.text = "Next Step →"
                btnNext?.setOnClickListener {
                    onNextStepTapped()
                }
                tvCollapsed?.text = "Step $userStepNumber: $stepTitle ▾"
            }

            tvMessage?.text = response.message
            tvFallback?.visibility = View.GONE
            btnRetry?.visibility = View.GONE
        }

        // Determine coordinates for bubble
        val screenSize = coordinateMapper.getScreenDimensions()
        val margin = context.resources.getDimensionPixelSize(R.dimen.bubble_margin)
        val bubbleMaxWidth = context.resources.getDimensionPixelSize(R.dimen.bubble_max_width)

        if (placement != null) {
            bParams.x = placement.bubbleX
            bParams.y = placement.bubbleY
        } else {
            // Default placement below current button position
            val btnP = buttonParams
            val currX = btnP?.x ?: (screenSize.x / 4)
            val currY = btnP?.y ?: (screenSize.y / 4)
            bParams.x = (currX - bubbleMaxWidth / 4).coerceIn(margin, screenSize.x - bubbleMaxWidth - margin)
            bParams.y = (currY + 120).coerceIn(coordinateMapper.getStatusBarHeight(), screenSize.y - 250)
        }

        if (!isBubbleAdded) {
            try {
                windowManager.addView(bView, bParams)
                isBubbleAdded = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                windowManager.updateViewLayout(bView, bParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun showBubbleProgress(message: String) {
        ensureBubbleView()

        val bView = bubbleView ?: return
        val bParams = bubbleParams ?: return

        bView.findViewById<TextView>(R.id.tvStepLabel).text = "TapTrail"
        bView.findViewById<TextView>(R.id.tvInstructionMessage).text = message
        bView.findViewById<TextView>(R.id.tvFallbackNotice).visibility = View.GONE
        bView.findViewById<ProgressBar>(R.id.pbAnalyzing).visibility = View.VISIBLE
        bView.findViewById<Button>(R.id.btnNextStep).visibility = View.GONE
        bView.findViewById<Button>(R.id.btnRetryGuidance).visibility = View.GONE

        if (!isBubbleAdded) {
            try {
                windowManager.addView(bView, bParams)
                isBubbleAdded = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                windowManager.updateViewLayout(bView, bParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun hideInstructionBubble() {
        isInGuidanceMode = false
        hideTrail()
        if (isBubbleAdded && bubbleView != null) {
            try {
                windowManager.removeView(bubbleView)
            } catch (e: Exception) {
                // ignore
            }
            isBubbleAdded = false
        }
    }

    fun getButtonCenter(): Point {
        val params = buttonParams ?: return Point(0, 0)
        val buttonSize = context.resources.getDimensionPixelSize(R.dimen.overlay_button_window_size)
        return Point(params.x + buttonSize / 2, params.y + buttonSize / 2)
    }

    fun showTrail(
        startX: Float,
        startY: Float,
        targetBbox: RectF?,
        centerX: Float,
        centerY: Float
    ) {
        if (trailOverlayView == null) {
            trailOverlayView = TrailOverlayView(context)
            trailParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
        }
        if (!isTrailAdded && trailOverlayView != null && trailParams != null) {
            try {
                windowManager.addView(trailOverlayView, trailParams)
                isTrailAdded = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        trailOverlayView?.visibility = View.VISIBLE
        trailOverlayView?.setTrail(startX, startY, targetBbox, centerX, centerY)
    }

    fun hideTrail() {
        trailOverlayView?.clearTrail()
        if (isTrailAdded && trailOverlayView != null) {
            try {
                windowManager.removeView(trailOverlayView)
            } catch (e: Exception) {
                // ignore
            }
            isTrailAdded = false
        }
    }

    fun showHelpRequestDialog(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
        helpRequestDialog?.dismiss()
        helpRequestDialog = HelpRequestDialog(context, windowManager, onSubmit, onCancel).also {
            it.show()
        }
    }

    fun dismissHelpRequestDialog() {
        helpRequestDialog?.dismiss()
        helpRequestDialog = null
    }

    fun showConsentDialog(onAgree: () -> Unit, onCancel: () -> Unit) {
        consentDialog?.dismiss()
        consentDialog = ConsentDialog(context, windowManager, onAgree, onCancel).also {
            it.show()
        }
    }

    fun dismissConsentDialog() {
        consentDialog?.dismiss()
        consentDialog = null
    }

    /**
     * Temporarily hide all overlay views before a screenshot so they aren't captured.
     */
    fun hideOverlaysForCapture() {
        buttonView?.visibility = View.INVISIBLE
        bubbleView?.visibility = View.INVISIBLE
        dismissTargetView?.visibility = View.INVISIBLE
        trailOverlayView?.visibility = View.INVISIBLE
    }

    /**
     * Restore overlay views after screenshot capture completes.
     */
    fun restoreOverlaysAfterCapture() {
        buttonView?.visibility = View.VISIBLE
        bubbleView?.visibility = View.VISIBLE
        dismissTargetView?.visibility = View.VISIBLE
        trailOverlayView?.visibility = View.VISIBLE
    }

    fun destroy() {
        dismissHelpRequestDialog()
        dismissConsentDialog()
        hideInstructionBubble()
        hideTrail()
        trailOverlayView = null

        if (isDismissTargetAdded && dismissTargetView != null) {
            try {
                windowManager.removeView(dismissTargetView)
            } catch (e: Exception) {
                // ignore
            }
            isDismissTargetAdded = false
        }
        dismissTargetView = null

        if (isButtonAdded && buttonView != null) {
            try {
                windowManager.removeView(buttonView)
            } catch (e: Exception) {
                // ignore
            }
            isButtonAdded = false
        }
        buttonView = null
        bubbleView = null
    }
}
