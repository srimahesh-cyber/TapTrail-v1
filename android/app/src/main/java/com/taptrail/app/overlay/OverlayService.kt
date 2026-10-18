package com.taptrail.app.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.taptrail.app.MainActivity
import com.taptrail.app.R
import com.taptrail.app.TapTrailApplication
import com.taptrail.app.capture.CaptureManager
import com.taptrail.app.consent.ConsentManager
import com.taptrail.app.guidance.CoordinateMapper
import com.taptrail.app.guidance.GuidanceEngine
import com.taptrail.app.network.BackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class OverlayService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var coordinateMapper: CoordinateMapper
    private lateinit var consentManager: ConsentManager
    private lateinit var captureManager: CaptureManager
    private lateinit var backendClient: BackendClient
    private lateinit var overlayManager: OverlayManager
    private lateinit var guidanceEngine: GuidanceEngine

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OverlayService onCreate")
        instance = this
        isRunning = true

        startServiceForeground()
        initializeComponents()
    }

    private fun initializeComponents() {
        coordinateMapper = CoordinateMapper(this)
        consentManager = ConsentManager(this)
        captureManager = CaptureManager(this, coordinateMapper)
        backendClient = BackendClient(this)

        overlayManager = OverlayManager(
            context = this,
            coordinateMapper = coordinateMapper,
            onButtonTapped = {
                handleFloatingButtonTapped()
            },
            onNextStepTapped = {
                guidanceEngine.nextStep()
            },
            onStopTapped = {
                guidanceEngine.stopSession()
            },
            onRetryTapped = {
                guidanceEngine.retry()
            },
            onCloseTapped = {
                stopSelf()
            }
        )

        guidanceEngine = GuidanceEngine(
            context = this,
            scope = serviceScope,
            consentManager = consentManager,
            captureManager = captureManager,
            coordinateMapper = coordinateMapper,
            backendClient = backendClient,
            overlayManager = overlayManager
        )

        // Show the initial floating button
        overlayManager.showFloatingButton()
    }

    private fun handleFloatingButtonTapped() {
        // Tapping button opens the input panel where user types their help request
        overlayManager.showHelpRequestDialog(
            onSubmit = { query ->
                guidanceEngine.startSession(query)
            },
            onCancel = {
                // Dismissed without input
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Screen configuration changed (orientation/insets)")
        // When screen rotates, re-adjust button and bubble positions within screen
        overlayManager.resetFloatingButtonPosition()
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, TapTrailApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_taptrail)
            .setOngoing(true)
            .setContentIntent(pendingOpenApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startServiceForeground() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Start with SPECIAL_USE because MediaProjection token is not yet granted
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun upgradeToMediaProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                Log.d(TAG, "Upgraded foreground service type to include mediaProjection")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to upgrade foreground service type: ${e.message}", e)
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "OverlayService onDestroy")
        isRunning = false
        instance = null
        serviceScope.cancel()

        if (::guidanceEngine.isInitialized) {
            guidanceEngine.stopSession()
        }
        if (::overlayManager.isInitialized) {
            overlayManager.destroy()
        }
        if (::captureManager.isInitialized) {
            captureManager.destroy()
        }
        if (::consentManager.isInitialized) {
            consentManager.clearAll()
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "OverlayService"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.taptrail.app.action.STOP_OVERLAY"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var instance: OverlayService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
