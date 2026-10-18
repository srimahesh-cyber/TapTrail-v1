package com.taptrail.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.taptrail.app.databinding.ActivityMainBinding
import com.taptrail.app.network.ApiResult
import com.taptrail.app.network.BackendClient
import com.taptrail.app.overlay.OverlayService
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var backendClient: BackendClient

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateUiState()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Notification permission updated
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        backendClient = BackendClient(this)
        initViews()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun initViews() {
        val sanitizedUrl = BackendClient.sanitizeUrl(backendClient.baseUrl)
        backendClient.baseUrl = sanitizedUrl
        binding.etBackendUrl.setText(sanitizedUrl)

        binding.btnResetUrl.setOnClickListener {
            val defaultUrl = BackendClient.DEFAULT_EMULATOR_URL
            binding.etBackendUrl.setText(defaultUrl)
            backendClient.baseUrl = defaultUrl
            testBackendConnection()
        }

        binding.btnGrantOverlay.setOnClickListener {
            requestOverlayPermission()
        }

        binding.btnToggleService.setOnClickListener {
            saveBackendUrl()
            if (OverlayService.isRunning) {
                OverlayService.stop(this)
                binding.root.postDelayed({ updateUiState() }, 300)
            } else {
                if (!hasOverlayPermission()) {
                    Toast.makeText(
                        this,
                        "Please allow 'Display over other apps' to start TapTrail",
                        Toast.LENGTH_LONG
                    ).show()
                    requestOverlayPermission()
                } else {
                    OverlayService.start(this)
                    binding.root.postDelayed({
                        updateUiState()
                        moveTaskToBack(true)
                    }, 350)
                }
            }
        }

        binding.btnOpenSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Settings: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnOpenBrowser.setOnClickListener {
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                startActivity(browserIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Browser: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnOpenCamera.setOnClickListener {
            try {
                val cameraIntent = Intent("android.media.action.STILL_IMAGE_CAMERA")
                startActivity(cameraIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not open Camera: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTestConnection.setOnClickListener {
            saveBackendUrl()
            testBackendConnection()
        }

        binding.btnUpdateApiKey.setOnClickListener {
            val key = binding.etApiKey.text?.toString()?.trim().orEmpty()
            if (key.isBlank()) {
                Toast.makeText(this, "Please enter an API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveBackendUrl()
            lifecycleScope.launch {
                binding.tvConnectionStatus.visibility = View.VISIBLE
                binding.tvConnectionStatus.text = "Updating API key on backend..."
                when (val res = backendClient.updateApiKey(key)) {
                    is ApiResult.Success -> {
                        Toast.makeText(this@MainActivity, "API Key updated!", Toast.LENGTH_SHORT).show()
                        testBackendConnection()
                    }
                    is ApiResult.Error -> {
                        binding.tvConnectionStatus.text = "✗ Key update failed: ${res.message}"
                        binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.taptrail_error))
                    }
                }
            }
        }
    }

    private fun saveBackendUrl() {
        val inputUrl = binding.etBackendUrl.text.toString().trim()
        val sanitized = BackendClient.sanitizeUrl(inputUrl)
        binding.etBackendUrl.setText(sanitized)
        backendClient.baseUrl = sanitized
    }

    private fun testBackendConnection() {
        binding.tvConnectionStatus.visibility = View.VISIBLE
        binding.tvConnectionStatus.text = "Testing connection to ${backendClient.baseUrl}..."
        binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this, R.color.taptrail_on_surface_secondary))

        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            when (val result = backendClient.checkHealth()) {
                is ApiResult.Success -> {
                    val latencyMs = System.currentTimeMillis() - startTime
                    val modelName = result.data
                    binding.tvConnectionStatus.text = "✓ Connected to AI ($modelName, ${latencyMs}ms)"
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.taptrail_accent))
                }
                is ApiResult.Error -> {
                    binding.tvConnectionStatus.text = "✗ Connection failed: ${result.message}"
                    binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.taptrail_error))
                }
            }
        }
    }

    private fun updateUiState() {
        val hasOverlay = hasOverlayPermission()

        binding.btnToggleService.isEnabled = true

        if (!hasOverlay) {
            binding.ivStatusDot.setBackgroundResource(R.drawable.status_dot_inactive)
            binding.btnGrantOverlay.visibility = View.VISIBLE
            binding.btnToggleService.text = getString(R.string.start_taptrail)
            binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.taptrail_primary))
            binding.tvStatus.text = getString(R.string.overlay_permission_needed)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.taptrail_warning))
        } else {
            binding.btnGrantOverlay.visibility = View.GONE

            if (OverlayService.isRunning) {
                binding.ivStatusDot.setBackgroundResource(R.drawable.status_dot_active)
                binding.btnToggleService.text = getString(R.string.stop_taptrail)
                binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.taptrail_error))
                binding.tvStatus.text = getString(R.string.overlay_active)
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.taptrail_accent))
            } else {
                binding.ivStatusDot.setBackgroundResource(R.drawable.status_dot_inactive)
                binding.btnToggleService.text = getString(R.string.start_taptrail)
                binding.btnToggleService.setBackgroundColor(ContextCompat.getColor(this, R.color.taptrail_primary))
                binding.tvStatus.text = "TapTrail overlay is stopped. Ready to start."
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.taptrail_on_surface))
            }
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                try {
                    val fallbackIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    overlayPermissionLauncher.launch(fallbackIntent)
                } catch (e2: Exception) {
                    Toast.makeText(this, "Could not open overlay settings. Please enable manually in system settings.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
