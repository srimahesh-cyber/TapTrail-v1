package com.taptrail.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.taptrail.app.R

/**
 * Overlay view for TapTrail's transmission disclosure before screen capture.
 */
class ConsentDialog(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onAgree: () -> Unit,
    private val onCancel: () -> Unit
) {
    private var view: View? = null
    var isShowing: Boolean = false
        private set

    fun show() {
        if (isShowing) return

        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.overlay_consent, null)
        view = dialogView

        val btnAgree = dialogView.findViewById<Button>(R.id.btnConsentAgree)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnConsentCancel)

        btnAgree.setOnClickListener {
            dismiss()
            onAgree()
        }

        btnCancel.setOnClickListener {
            dismiss()
            onCancel()
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(dialogView, params)
            isShowing = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun dismiss() {
        if (!isShowing) return
        view?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // Ignore removal race conditions
            }
        }
        view = null
        isShowing = false
    }
}
