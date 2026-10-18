package com.taptrail.app.guidance

import android.app.Activity
import android.content.Context
import android.util.Log
import com.taptrail.app.R
import com.taptrail.app.capture.CaptureActivity
import com.taptrail.app.capture.CaptureManager
import com.taptrail.app.consent.ConsentManager
import com.taptrail.app.network.ApiResult
import com.taptrail.app.network.BackendClient
import com.taptrail.app.network.GuidanceRequest
import com.taptrail.app.network.GuidanceResponse
import com.taptrail.app.overlay.OverlayManager
import com.taptrail.app.overlay.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

enum class GuidanceState {
    IDLE,
    REQUESTING_CONSENT,
    AWAITING_CAPTURE_PERMISSION,
    CAPTURING,
    SENDING,
    SHOWING_GUIDANCE,
    COMPLETE,
    ERROR
}

class GuidanceEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val consentManager: ConsentManager,
    private val captureManager: CaptureManager,
    private val coordinateMapper: CoordinateMapper,
    private val backendClient: BackendClient,
    private val overlayManager: OverlayManager
) {
    var state: GuidanceState = GuidanceState.IDLE
        private set

    private var currentRequestId: String = ""
    private var currentInstruction: String = ""
    private var currentStepIndex: Int = 0
    private var lastResponse: GuidanceResponse? = null

    fun startSession(instruction: String) {
        currentRequestId = UUID.randomUUID().toString()
        currentInstruction = instruction
        currentStepIndex = 0
        lastResponse = null
        processCurrentStep()
    }

    fun nextStep() {
        if (state == GuidanceState.SHOWING_GUIDANCE || state == GuidanceState.COMPLETE) {
            currentStepIndex++
            processCurrentStep()
        }
    }

    fun retry() {
        processCurrentStep()
    }

    fun stopSession() {
        state = GuidanceState.IDLE
        overlayManager.setButtonLoading(false)
        overlayManager.hideInstructionBubble()
        overlayManager.resetFloatingButtonPosition()
    }

    private fun processCurrentStep() {
        scope.launch {
            // 1. Check transmission consent
            if (!consentManager.hasTransmissionConsent()) {
                state = GuidanceState.REQUESTING_CONSENT
                overlayManager.showConsentDialog(
                    onAgree = {
                        consentManager.grantTransmissionConsent()
                        processCurrentStep()
                    },
                    onCancel = {
                        stopSession()
                    }
                )
                return@launch
            }

            // 2. Check MediaProjection permission
            if (!consentManager.hasCapturePermission() || !captureManager.hasProjection()) {
                state = GuidanceState.AWAITING_CAPTURE_PERMISSION
                CaptureActivity.requestCapturePermission(context) { resultCode, data ->
                    if (resultCode == Activity.RESULT_OK && data != null) {
                        consentManager.grantCapturePermission(resultCode, data)
                        OverlayService.instance?.upgradeToMediaProjection()
                        captureManager.initializeProjection(resultCode, data)
                        processCurrentStep()
                    } else {
                        transitionToError("Screen capture permission was not granted.")
                    }
                }
                return@launch
            }

            // 3. Capturing screenshot
            state = GuidanceState.CAPTURING
            overlayManager.setButtonLoading(true)
            overlayManager.showBubbleProgress(context.getString(R.string.analyzing))

            // Temporarily hide overlays to exclude them from the screenshot
            overlayManager.hideOverlaysForCapture()
            delay(120) // Give WindowManager time to render without overlay views

            val frame = captureManager.captureScreenSingleFrame()
            overlayManager.restoreOverlaysAfterCapture()

            if (frame == null) {
                overlayManager.setButtonLoading(false)
                captureManager.releaseProjection()
                consentManager.revokeCapturePermission()
                transitionToError("Failed to capture screen image. Please try again.")
                return@launch
            }

            // 4. Send to backend
            state = GuidanceState.SENDING
            val request = GuidanceRequest(
                requestId = currentRequestId,
                instruction = currentInstruction,
                stepIndex = currentStepIndex,
                imageWidth = frame.width,
                imageHeight = frame.height
            )

            val apiResult = backendClient.requestGuidance(request, frame.jpegBytes)
            overlayManager.setButtonLoading(false)

            when (apiResult) {
                is ApiResult.Error -> {
                    transitionToError(apiResult.message)
                }
                is ApiResult.Success -> {
                    val guidance = apiResult.data
                    lastResponse = guidance

                    when (guidance.status) {
                        "error" -> {
                            transitionToError(guidance.message.ifBlank { context.getString(R.string.error_api) })
                        }
                        "complete" -> {
                            state = GuidanceState.COMPLETE
                            overlayManager.hideTrail()
                            overlayManager.showInstructionBubble(
                                stepIndex = currentStepIndex,
                                response = guidance,
                                placement = null
                            )
                        }
                        else -> {
                            // "guide", "answer", "clarify"
                            state = GuidanceState.SHOWING_GUIDANCE
                            val mapped = coordinateMapper.mapTarget(guidance.targetBboxNorm)
                            if (mapped != null) {
                                val buttonCenter = overlayManager.getButtonCenter()
                                overlayManager.showTrail(
                                    startX = buttonCenter.x.toFloat(),
                                    startY = buttonCenter.y.toFloat(),
                                    targetBbox = mapped.targetRect,
                                    centerX = mapped.targetCenterX.toFloat(),
                                    centerY = mapped.targetCenterY.toFloat()
                                )
                                overlayManager.animateButtonTo(mapped.buttonX, mapped.buttonY) {
                                    overlayManager.showInstructionBubble(
                                        stepIndex = currentStepIndex,
                                        response = guidance,
                                        placement = mapped
                                    )
                                }
                            } else {
                                overlayManager.hideTrail()
                                overlayManager.showInstructionBubble(
                                    stepIndex = currentStepIndex,
                                    response = guidance,
                                    placement = null
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun transitionToError(errorMessage: String) {
        state = GuidanceState.ERROR
        overlayManager.setButtonLoading(false)
        overlayManager.hideTrail()
        val errorResponse = GuidanceResponse(
            status = "error",
            message = errorMessage
        )
        overlayManager.showInstructionBubble(
            stepIndex = currentStepIndex,
            response = errorResponse,
            placement = null,
            isError = true
        )
    }

    companion object {
        private const val TAG = "GuidanceEngine"
    }
}
