package com.research.projector

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.research.projector.databinding.ActivityMainBinding
import com.research.projector.models.*
import com.research.projector.network.ProjectorNetworkService
import com.research.projector.network.ProjectorNetworkManager
import com.research.projector.utils.StroopGenerator
import com.research.projector.viewmodels.ConfigState
import com.research.projector.viewmodels.TaskSelectionViewModel
import com.research.shared.network.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Main entry point activity for the Stroop Research app.
 * ENHANCED: Now handles Master command integration and activity launches
 * FIXED: Properly passes Master control information to StroopDisplayActivity
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TaskSelectionViewModel by viewModels()
    private lateinit var networkService: ProjectorNetworkService

    // Track task state
    private var isTaskRunning = false
    private var currentTaskId: String? = null

    companion object {
        private const val REQUEST_CODE_STROOP_DISPLAY = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        android.util.Log.d("StroopApp", "MainActivity onCreate started")

        // Set up view binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        android.util.Log.d("StroopApp", "ViewBinding set up, setting up observers")

        // ✅ MODERN BACK BUTTON HANDLING
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showExitConfirmation()
            }
        })

        // Show initial network info
        showInitialNetworkInfo()

        // Initialize and start network service
        initializeNetworkService()

        // Set up observers
        setupObservers()

        // Set up click listeners
        setupClickListeners()

        // CRITICAL: Set up Master command listening
        setupMasterCommandListener()

        android.util.Log.d("StroopApp", "MainActivity onCreate completed")

        // TEMPORARY TEST - Force success state after a delay
        binding.root.postDelayed({
            android.util.Log.d("StroopApp", "FORCING success state for test")
            binding.layoutLoading.isVisible = false
            binding.layoutReady.isVisible = true
            binding.layoutError.isVisible = false
            android.util.Log.d("StroopApp", "Forced - layout_ready visibility: ${binding.layoutReady.isVisible}")
        }, 2000) // Wait 2 seconds then force it
    }

    /**
     * CRITICAL FIX: Set up listener for Master commands to launch activities
     */
    private fun setupMasterCommandListener() {
        android.util.Log.d("ProjectorApp", "Setting up Master command listener")

        lifecycleScope.launch {
            networkService.receiveMessages().collectLatest { message ->
                android.util.Log.d("ProjectorApp", "Received Master message: ${message.messageType}")

                when (message) {
                    is StartTaskMessage -> {
                        handleStartTaskCommand(message)
                    }

                    is EndTaskMessage -> {
                        handleEndTaskCommand(message)
                    }

                    is TaskResetCommand -> {
                        handleResetTaskCommand(message)
                    }

                    else -> {
                        android.util.Log.d("ProjectorApp", "Ignoring non-task message: ${message.messageType}")
                    }
                }
            }
        }
    }

    /**
     * Handle START_TASK command from Master by launching StroopDisplayActivity
     * ENHANCED: Now properly passes Master control information to the activity
     */
    private fun handleStartTaskCommand(message: StartTaskMessage) {
        if (isTaskRunning) {
            android.util.Log.w("ProjectorApp", "Task already running, ignoring start command")
            return
        }

        try {
            android.util.Log.d("ProjectorApp", "=== HANDLING START TASK COMMAND ===")
            android.util.Log.d("ProjectorApp", "Task ID: ${message.taskId}")
            android.util.Log.d("ProjectorApp", "Timeout: ${message.timeoutSeconds}s")
            android.util.Log.d("ProjectorApp", "Colors: ${message.stroopSettings.colors}")

            // Convert Master's configuration to local format
            val runtimeConfig = convertStroopSettingsToRuntimeConfig(message.stroopSettings)
            android.util.Log.d("ProjectorApp", "✅ Runtime config created successfully")

            // Create task execution state
            val taskExecution = TaskExecutionState(
                taskId = message.taskId,
                timeoutDuration = message.timeoutSeconds.toLong() * 1000L
            ).start()
            android.util.Log.d("ProjectorApp", "✅ Task execution state created: ${taskExecution.taskId}")

            // Create Stroop generator
            val stroopGenerator = StroopGenerator(runtimeConfig)
            android.util.Log.d("ProjectorApp", "✅ Stroop generator created")

            // Update state
            isTaskRunning = true
            currentTaskId = message.taskId
            android.util.Log.d("ProjectorApp", "✅ Task state updated - isTaskRunning: $isTaskRunning")

            // ✅ Launch StroopDisplayActivity with Master control information
            val intent = Intent(this, StroopDisplayActivity::class.java).apply {
                putExtra(TaskExecutionActivity.EXTRA_TASK_EXECUTION, taskExecution)
                putExtra(TaskExecutionActivity.EXTRA_RUNTIME_CONFIG, runtimeConfig)
                putExtra(TaskExecutionActivity.EXTRA_STROOP_GENERATOR, stroopGenerator)

                // ✅ CRITICAL: Add Master control information
                putExtra("MASTER_CONTROLLED", true)
                putExtra("MASTER_TASK_ID", message.taskId)
                putExtra("MASTER_TIMEOUT_SECONDS", message.timeoutSeconds)

                android.util.Log.d("ProjectorApp", "✅ Intent created with Master control extras:")
                android.util.Log.d("ProjectorApp", "  - MASTER_CONTROLLED: true")
                android.util.Log.d("ProjectorApp", "  - MASTER_TASK_ID: ${message.taskId}")
                android.util.Log.d("ProjectorApp", "  - MASTER_TIMEOUT_SECONDS: ${message.timeoutSeconds}")
            }

            startActivityForResult(intent, REQUEST_CODE_STROOP_DISPLAY)

            android.util.Log.d("ProjectorApp", "✅ StroopDisplayActivity launched successfully for task: ${message.taskId}")

        } catch (e: Exception) {
            android.util.Log.e("ProjectorApp", "❌ Failed to handle start task command", e)

            // Send error back to Master
            lifecycleScope.launch {
                val sessionId = networkService.getCurrentSessionId()
                if (sessionId != null) {
                    val errorMessage = ErrorMessage(
                        sessionId = sessionId,
                        errorCode = "ACTIVITY_LAUNCH_FAILED",
                        errorDescription = "Failed to launch Stroop display: ${e.message}",
                        isFatal = false
                    )
                    networkService.sendMessage(errorMessage)
                    android.util.Log.d("ProjectorApp", "✅ Error message sent to Master")
                } else {
                    android.util.Log.e("ProjectorApp", "❌ No session ID - cannot send error to Master")
                }
            }

            // Reset state on error
            isTaskRunning = false
            currentTaskId = null
        }
    }

    /**
     * Handle END_TASK command from Master
     */
    private fun handleEndTaskCommand(message: EndTaskMessage) {
        android.util.Log.d("ProjectorApp", "=== HANDLING END TASK COMMAND ===")
        android.util.Log.d("ProjectorApp", "Task ID: ${message.taskId}")
        android.util.Log.d("ProjectorApp", "Current task ID: $currentTaskId")
        android.util.Log.d("ProjectorApp", "Is task running: $isTaskRunning")

        if (message.taskId == currentTaskId && isTaskRunning) {
            // Task should be ended - the StroopDisplayActivity should handle this
            // through its own ViewModel listening to the same message stream
            android.util.Log.d("ProjectorApp", "✅ Task end command acknowledged - StroopDisplayViewModel will handle it")
        } else {
            android.util.Log.w("ProjectorApp", "❌ End task command ignored - task ID mismatch or not running")
        }
    }

    /**
     * Handle RESET_TASK command from Master
     */
    private fun handleResetTaskCommand(message: TaskResetCommand) {
        android.util.Log.d("ProjectorApp", "=== HANDLING RESET TASK COMMAND ===")

        // Reset task state
        isTaskRunning = false
        currentTaskId = null

        android.util.Log.d("ProjectorApp", "✅ Task state reset - isTaskRunning: false, currentTaskId: null")

        // If StroopDisplayActivity is running, it should handle the reset
        // and return to this activity
    }

    /**
     * Convert Master's StroopSettings to local RuntimeConfig format
     * ENHANCED: Added better error handling and validation
     */
    private fun convertStroopSettingsToRuntimeConfig(stroopSettings: StroopSettings): RuntimeConfig {
        android.util.Log.d("ProjectorApp", "=== CONVERTING STROOP SETTINGS ===")
        android.util.Log.d("ProjectorApp", "Colors: ${stroopSettings.colors.size}")
        android.util.Log.d("ProjectorApp", "Display duration: ${stroopSettings.displayDurationMs}ms")
        android.util.Log.d("ProjectorApp", "Interval: ${stroopSettings.minIntervalMs}-${stroopSettings.maxIntervalMs}ms")
        android.util.Log.d("ProjectorApp", "Countdown: ${stroopSettings.countdownDurationMs}ms")

        // Validate input
        if (stroopSettings.colors.size < 2) {
            throw IllegalArgumentException("Master provided insufficient colors: ${stroopSettings.colors.size}")
        }

        // Create color mappings - Master should ideally provide these
        val stroopColors = stroopSettings.colors.associateWith { colorName ->
            when (colorName.lowercase()) {
                "rot" -> "#FF0000"
                "blau" -> "#0000FF"
                "grün", "green" -> "#00FF00"
                "gelb", "yellow" -> "#FFFF00"
                "schwarz", "black" -> "#000000"
                "braun", "brown" -> "#8B4513"
                "orange" -> "#FF8000"
                "lila", "purple" -> "#800080"
                else -> {
                    android.util.Log.w("ProjectorApp", "⚠️ Unknown color from Master: $colorName")
                    "#808080" // Gray for unknown colors
                }
            }
        }

        android.util.Log.d("ProjectorApp", "✅ Color mappings created: ${stroopColors.size} colors")

        // Validate timing values
        if (stroopSettings.displayDurationMs <= 0 ||
            stroopSettings.minIntervalMs <= 0 ||
            stroopSettings.maxIntervalMs <= 0 ||
            stroopSettings.countdownDurationMs <= 0) {
            throw IllegalArgumentException("Invalid timing values from Master")
        }

        if (stroopSettings.minIntervalMs > stroopSettings.maxIntervalMs) {
            throw IllegalArgumentException("Invalid interval range from Master")
        }

        // Create base configuration
        val baseConfig = StroopConfig(
            stroopColors = stroopColors,
            tasks = emptyMap(), // Not needed for display
            taskLists = emptyMap(), // Not needed for display
            timing = TimingConfig(
                stroopDisplayDuration = stroopSettings.displayDurationMs.toInt(),
                minInterval = stroopSettings.minIntervalMs.toInt(),
                maxInterval = stroopSettings.maxIntervalMs.toInt(),
                countdownDuration = (stroopSettings.countdownDurationMs / 1000).toInt()
            )
        )

        val runtimeConfig = RuntimeConfig(baseConfig = baseConfig)
        android.util.Log.d("ProjectorApp", "✅ Runtime config created successfully")

        return runtimeConfig
    }

    /**
     * Handle result from StroopDisplayActivity
     * ENHANCED: Now handles Master control information from the returned result
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_CODE_STROOP_DISPLAY -> {
                android.util.Log.d("ProjectorApp", "=== STROOP DISPLAY ACTIVITY RESULT ===")
                android.util.Log.d("ProjectorApp", "Result code: $resultCode")

                // ✅ Extract Master control information from result
                val wasMasterControlled = data?.getBooleanExtra("MASTER_CONTROLLED", false) ?: false
                val masterTaskId = data?.getStringExtra("MASTER_TASK_ID")

                android.util.Log.d("ProjectorApp", "Was Master controlled: $wasMasterControlled")
                android.util.Log.d("ProjectorApp", "Master task ID: $masterTaskId")

                // Reset task state
                isTaskRunning = false
                currentTaskId = null

                when (resultCode) {
                    RESULT_OK -> {
                        // Task completed successfully
                        val completedExecution = data?.getSerializableExtra(
                            TaskExecutionActivity.EXTRA_COMPLETED_TASK_EXECUTION
                        ) as? TaskExecutionState

                        android.util.Log.d("ProjectorApp", "✅ Task completed successfully: ${completedExecution?.taskId}")
                        android.util.Log.d("ProjectorApp", "Execution details:")
                        android.util.Log.d("ProjectorApp", "  - Duration: ${completedExecution?.getElapsedTime()}ms")
                        android.util.Log.d("ProjectorApp", "  - Stimuli: ${completedExecution?.getStimulusCount()}")
                        android.util.Log.d("ProjectorApp", "  - Timed out: ${completedExecution?.hasTimedOut()}")

                        // Note: Timeout/completion notifications to Master should already be
                        // handled by StroopDisplayViewModel, so we don't need to send them here
                    }

                    RESULT_CANCELED -> {
                        // Task was cancelled or error occurred
                        val errorMessage = data?.getStringExtra("error_message")
                        android.util.Log.w("ProjectorApp", "❌ Task cancelled or error: $errorMessage")

                        // Send error to Master if it was a Master-controlled task
                        if (wasMasterControlled && errorMessage != null) {
                            lifecycleScope.launch {
                                val sessionId = networkService.getCurrentSessionId()
                                if (sessionId != null) {
                                    val error = ErrorMessage(
                                        sessionId = sessionId,
                                        errorCode = "TASK_CANCELLED",
                                        errorDescription = errorMessage,
                                        isFatal = false
                                    )
                                    networkService.sendMessage(error)
                                    android.util.Log.d("ProjectorApp", "✅ Error message sent to Master")
                                } else {
                                    android.util.Log.e("ProjectorApp", "❌ No session ID - cannot send error to Master")
                                }
                            }
                        }
                    }
                }

                android.util.Log.d("ProjectorApp", "=== ACTIVITY RESULT HANDLED ===")
            }
        }
    }

    /**
     * Show initial network info before service starts
     */
    private fun showInitialNetworkInfo() {
        val ipAddress = getDeviceIpAddress()
        binding.tvConnectionInfo.text = "Device IP: $ipAddress\nStarting network service..."
        binding.cardConnectionInfo.visibility = View.VISIBLE
    }

    /**
     * Get the device's IP address
     */
    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is InetAddress) {
                        val hostAddress = address.hostAddress
                        // Check if it's IPv4 (not IPv6)
                        if (hostAddress?.contains(':') == false) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ProjectorApp", "Error getting IP address", e)
        }
        return "Unknown"
    }

    /**
     * Initialize and start the network service
     */
    private fun initializeNetworkService() {
        android.util.Log.d("ProjectorApp", "Initializing network service")

        // Create network service using singleton
        networkService = ProjectorNetworkManager.getNetworkService(this)

        // Observe connection state
        lifecycleScope.launch {
            networkService.connectionState.collect { state ->
                android.util.Log.d("ProjectorApp", "Network state changed: $state")

                // Update UI on main thread
                runOnUiThread {
                    // Always keep the connection info visible
                    binding.cardConnectionInfo.visibility = View.VISIBLE

                    when (state) {
                        ProjectorNetworkService.ConnectionState.DISCONNECTED -> {
                            val ipAddress = getDeviceIpAddress()
                            binding.tvConnectionInfo.text = "Device IP: $ipAddress\nNetwork: Initializing..."
                        }
                        ProjectorNetworkService.ConnectionState.ADVERTISING -> {
                            // Get the port from the service
                            val port = networkService.getServerPort()
                            val ipAddress = getDeviceIpAddress()

                            binding.tvConnectionInfo.text = "Ready for connection:\nIP: $ipAddress\nPort: $port"

                            Log.d("ProjectorApp", "Showing connection info - IP: $ipAddress, Port: $port")
                            Toast.makeText(this@MainActivity, "Network: Ready on port $port", Toast.LENGTH_SHORT).show()
                        }
                        ProjectorNetworkService.ConnectionState.CONNECTED -> {
                            val port = networkService.getServerPort()
                            val ipAddress = getDeviceIpAddress()
                            binding.tvConnectionInfo.text = "Connected to Master!\nIP: $ipAddress\nPort: $port"
                            Toast.makeText(this@MainActivity, "Connected to Master!", Toast.LENGTH_SHORT).show()
                        }
                        ProjectorNetworkService.ConnectionState.ERROR -> {
                            val ipAddress = getDeviceIpAddress()
                            binding.tvConnectionInfo.text = "Device IP: $ipAddress\nNetwork: Error"
                            Toast.makeText(this@MainActivity, "Network Error", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        android.util.Log.d("ProjectorApp", "Network service started")
    }

    /**
     * Set up LiveData observers
     */
    private fun setupObservers() {
        android.util.Log.d("StroopApp", "Setting up observers")

        // Observe configuration state
        viewModel.configState.observe(this) { state ->
            android.util.Log.d("StroopApp", "Config state changed: $state")
            handleConfigState(state)
        }
    }

    /**
     * Set up click listeners for UI elements
     */
    private fun setupClickListeners() {
        // Continue button (when config loaded successfully)
        binding.btnContinue.setOnClickListener {
            navigateToTaskSelection()
        }

        // Retry button (when config loading failed)
        binding.btnRetry.setOnClickListener {
            viewModel.retryConfiguration()
        }

        // Exit button (when config loading failed)
        binding.btnExit.setOnClickListener {
            showExitConfirmation()
        }
    }

    /**
     * Handle configuration state changes
     */
    private fun handleConfigState(state: ConfigState) {
        android.util.Log.d("StroopApp", "Config state: $state")
        when (state) {
            is ConfigState.Loading -> {
                android.util.Log.d("StroopApp", "Showing loading state")
                showLoadingState()
            }

            is ConfigState.Success -> {
                android.util.Log.d("StroopApp", "Showing success state")
                showSuccessState()
            }

            is ConfigState.Error -> {
                android.util.Log.d("StroopApp", "Showing error state: ${state.message}")
                showErrorState(state.message)
            }
        }
    }

    /**
     * Show loading state UI
     */
    private fun showLoadingState() {
        binding.layoutLoading.isVisible = true
        binding.layoutReady.isVisible = false
        binding.layoutError.isVisible = false
    }

    /**
     * Show success state UI
     */
    private fun showSuccessState() {
        android.util.Log.d("StroopApp", "showSuccessState called")

        binding.layoutLoading.isVisible = false
        binding.layoutReady.isVisible = true
        binding.layoutError.isVisible = false

        android.util.Log.d("StroopApp", "layout_ready visibility set to: ${binding.layoutReady.isVisible}")
        android.util.Log.d("StroopApp", "layout_loading visibility set to: ${binding.layoutLoading.isVisible}")
    }

    /**
     * Show error state UI
     */
    private fun showErrorState(errorMessage: String) {
        binding.layoutLoading.isVisible = false
        binding.layoutReady.isVisible = false
        binding.layoutError.isVisible = true

        // Set error message
        binding.tvErrorMessage.text = errorMessage
    }

    /**
     * Navigate to task selection activity
     */
    private fun navigateToTaskSelection() {
        val intent = Intent(this, TaskSelectionActivity::class.java)
        startActivity(intent)

        // Optionally finish this activity so user can't return with back button
        // finish()
    }

    /**
     * Show exit confirmation dialog
     */
    private fun showExitConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Exit Application")
            .setMessage("Are you sure you want to exit the Stroop Research app?")
            .setPositiveButton("Exit") { _, _ ->
                finishAffinity() // Close all activities and exit app
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    // REMOVED: onBackPressed() method - now handled by OnBackPressedDispatcher in onCreate()

    /**
     * Handle activity lifecycle - refresh config state when returning
     */
    override fun onResume() {
        super.onResume()

        // If we're returning from another activity and there was an error,
        // we might want to retry configuration loading
        val currentState = viewModel.configState.value
        if (currentState is ConfigState.Error) {
            // Optionally auto-retry or show retry option
        }
    }

    /**
     * Clean up network service when activity is destroyed
     */
    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("ProjectorApp", "MainActivity onDestroy - keeping network service running")
        // Don't stop the network service here - let it continue running
        // ProjectorNetworkManager.stop() // Only call this when app is completely shutting down
    }
}