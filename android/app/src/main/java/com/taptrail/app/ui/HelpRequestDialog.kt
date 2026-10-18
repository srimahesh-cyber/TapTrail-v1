package com.taptrail.app.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import com.taptrail.app.R
import com.taptrail.app.voice.VoiceInputActivity

/**
 * An overlay panel simulating a dialog for entering help requests.
 * Runs in the Service context using WindowManager.
 */
class HelpRequestDialog(
    private val context: Context,
    private val windowManager: WindowManager,
    private val onSubmit: (String) -> Unit,
    private val onCancel: () -> Unit
) {
    private var view: View? = null
    var isShowing: Boolean = false
        private set

    fun show() {
        if (isShowing) return

        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.overlay_help_request, null)
        view = dialogView

        val etRequest = dialogView.findViewById<EditText>(R.id.etUserRequest)
        val btnVoice = dialogView.findViewById<ImageButton>(R.id.btnVoiceInput)
        val btnSubmit = dialogView.findViewById<Button>(R.id.btnSubmit)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancel)

        val chipHotspot = dialogView.findViewById<View>(R.id.chipHotspot)
        val chipDarkTheme = dialogView.findViewById<View>(R.id.chipDarkTheme)
        val chipBatterySaver = dialogView.findViewById<View>(R.id.chipBatterySaver)
        val chipSearch = dialogView.findViewById<View>(R.id.chipSearch)
        val chipMessage = dialogView.findViewById<View>(R.id.chipMessage)
        val chipCamera = dialogView.findViewById<View>(R.id.chipCamera)
        val chipBluetooth = dialogView.findViewById<View>(R.id.chipBluetooth)

        fun selectTask(query: String) {
            etRequest.setText(query)
            dismiss()
            onSubmit(query)
        }

        btnVoice?.setOnClickListener {
            dismiss()
            VoiceInputActivity.start(context) { spokenText ->
                if (spokenText.isNotBlank()) {
                    onSubmit(spokenText)
                }
            }
        }

        chipHotspot?.setOnClickListener { selectTask("Help me turn on Wi-Fi hotspot") }
        chipDarkTheme?.setOnClickListener { selectTask("Turn on dark theme") }
        chipBatterySaver?.setOnClickListener { selectTask("Turn on battery saver") }
        chipSearch?.setOnClickListener { selectTask("Search the web in browser") }
        chipMessage?.setOnClickListener { selectTask("Send a new message") }
        chipCamera?.setOnClickListener { selectTask("Take a photo with camera") }
        chipBluetooth?.setOnClickListener { selectTask("Pair a new Bluetooth device") }

        btnSubmit.setOnClickListener {
            val text = etRequest.text.toString().trim()
            if (text.isNotEmpty()) {
                dismiss()
                onSubmit(text)
            }
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

        // For keyboard input, omit FLAG_NOT_FOCUSABLE and use FLAG_NOT_TOUCH_MODAL
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

            dialogView.postDelayed({
                etRequest.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(etRequest, InputMethodManager.SHOW_IMPLICIT)
            }, 100)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun dismiss() {
        if (!isShowing) return
        view?.let {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(it.windowToken, 0)
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
