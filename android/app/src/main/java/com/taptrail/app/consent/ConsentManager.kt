package com.taptrail.app.consent

import android.content.Context
import android.content.Intent

class ConsentManager(private val context: Context? = null) {

    private val prefs = context?.getSharedPreferences("taptrail_consent_prefs", Context.MODE_PRIVATE)
    private var transmissionConsentGranted: Boolean = prefs?.getBoolean("transmission_consent", false) ?: false
    private var captureResultCode: Int? = null
    private var captureResultData: Intent? = null

    /**
     * User explicitly consented to transmitting screen captures to backend & Gemma API.
     */
    fun hasTransmissionConsent(): Boolean = transmissionConsentGranted

    /**
     * MediaProjection permission has been granted by user via system prompt.
     */
    fun hasCapturePermission(): Boolean = captureResultCode != null && captureResultData != null

    /**
     * Both transmission disclosure and MediaProjection permission are present.
     */
    fun canProceed(): Boolean = hasTransmissionConsent() && hasCapturePermission()

    fun grantTransmissionConsent() {
        transmissionConsentGranted = true
        prefs?.edit()?.putBoolean("transmission_consent", true)?.apply()
    }

    fun revokeTransmissionConsent() {
        transmissionConsentGranted = false
        prefs?.edit()?.putBoolean("transmission_consent", false)?.apply()
    }

    fun grantCapturePermission(resultCode: Int, data: Intent) {
        captureResultCode = resultCode
        captureResultData = data
    }

    fun revokeCapturePermission() {
        captureResultCode = null
        captureResultData = null
    }

    fun getCaptureData(): Pair<Int, Intent>? {
        val code = captureResultCode ?: return null
        val data = captureResultData ?: return null
        return Pair(code, data)
    }

    /**
     * Revoke all consents upon session end or service destruction.
     */
    fun clearAll() {
        revokeTransmissionConsent()
        revokeCapturePermission()
    }
}
