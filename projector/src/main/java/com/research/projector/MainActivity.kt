package com.research.projector

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
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
     */
    private fun handleStartTaskCommand(message: StartTaskMessage) {
        if (isTaskRunning) {
            android.util.Log.w("ProjectorApp", "Task already running, ignoring start command")
            return
        }

        try {
            android.util.Log.d("ProjectorApp", "Handling StartTaskMessage: ${message.taskId}")

            // Convert Master's configuration to local format
            val runtimeConfig = convertStroopSettingsToRuntimeConfig(message.stroopSettings)

            // Create task execution state
            val taskExecution = TaskExecutionState(
                taskId = message.taskId,
                timeoutDuration = message.timeoutSeconds.toLong() * 1000L
            ).start()

            // Create Stroop generator
            val stroopGenerator = StroopGenerator(runtimeConfig)

            // Update state
            isTaskRunning = true
            currentTaskId = message.taskId

            // Launch StroopDisplayActivity with required data
            val intent = Intent(this, StroopDisplayActivity::class.java).apply {
                putExtra(TaskExecutionActivity.EXTRA_TASK_EXECUTION, taskExecution)
                putExtra(TaskExecutionActivity.EXTRA_RUNTIME_CONFIG, runtimeConfig)
                putExtra(TaskExecutionActivity.EXTRA_STROOP_GENERATOR, stroopGenerator)
            }

            startActivityForResult(intent, REQUEST_CODE_STROOP_DISPLAY)

            android.util.Log.d("ProjectorApp", "Launched StroopDisplayActivity for task: ${message.taskId}")

        } catch (e: Exception) {
            android.util.Log.e("ProjectorApp", "Failed to handle start task command", e)

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
                }
            }
        }
    }

    /**
     * Handle END_TASK command from Master
     */
    private fun handleEndTaskCommand(message: EndTaskMessage) {
        android.util.Log.d("ProjectorApp", "Handling EndTaskMessage: ${message.taskId}")

        if (message.taskId == currentTaskId && isTaskRunning) {
            // Task should be ended - the StroopDisplayActivity should handle this
            // through its own ViewModel listening to the same message stream
            android.util.Log.d("ProjectorApp", "Task end command acknowledged")
        }
    }

    /**
     * Handle RESET_TASK command from Master
     */
    private fun handleResetTaskCommand(message: TaskResetCommand) {
        android.util.Log.d("ProjectorApp", "Handling TaskResetCommand")

        // Reset task state
        isTaskRunning = false
        currentTaskId = null

        // If StroopDisplayActivity is running, it should handle the reset
        // and return to this activity
    }

    /**
     * Convert Master's StroopSettings to local RuntimeConfig format
     */
    private fun convertStroopSettingsToRuntimeConfig(stroopSettings: StroopSettings): RuntimeConfig {
        android.util.Log.d("ProjectorApp", "Converting StroopSettings: ${stroopSettings.colors.size} colors")

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
                    android.util.Log.w("ProjectorApp", "Unknown color from Master: $colorName")
                    "#808080" // Gray for unknown colors
                }
            }
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

        return RuntimeConfig(baseConfig = baseConfig)
    }

    /**
     * Handle result from StroopDisplayActivity
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_CODE_STROOP_DISPLAY -> {
                android.util.Log.d("ProjectorApp", "StroopDisplayActivity result: $resultCode")

                // Reset task state
                isTaskRunning = false
                currentTaskId = null

                when (resultCode) {
                    RESULT_OK -> {
                        // Task completed successfully
                        val completedExecution = data?.getSerializableExtra(
                            TaskExecutionActivity.EXTRA_COMPLETED_TASK_EXECUTION
                        ) as? TaskExecutionState

                        android.util.Log.d("ProjectorApp", "Task completed: ${completedExecution?.taskId}")

                        // Send completion notification to Master if needed
                        // (This might already be handled by StroopDisplayViewModel)
                    }

                    RESULT_CANCELED -> {
                        // Task was cancelled or error occurred
                        val errorMessage = data?.getStringExtra("error_message")
                        android.util.Log.w("ProjectorApp", "Task cancelled: $errorMessage")

                        // Send error to Master if needed
                        if (errorMessage != null) {
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
                                }
                            }
                        }
                    }
                }
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

    /**
     * Handle back button press
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // On main activity, back button should show exit confirmation
        // NOT calling super.onBackPressed() because we want to show dialog first
        showExitConfirmation()
    }

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