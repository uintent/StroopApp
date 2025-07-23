package com.research.projector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.research.projector.databinding.ActivityTaskSelectionBinding
import com.research.projector.viewmodels.*
import com.research.projector.models.*
import com.research.projector.network.ProjectorNetworkManager
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Activity for selecting task sequences and accessing settings.
 * Displays available task sequences from configuration and handles navigation.
 * Now supports external config files with user-editable configurations.
 */
class TaskSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskSelectionBinding
    private val viewModel: TaskSelectionViewModel by viewModels()

    // Permission launcher for storage access
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.exportConfigToExternal()
        } else {
            showPermissionDeniedDialog()
        }
    }

    // Event tracking to prevent multiple handling
    private var lastNavigationEvent: NavigationEvent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up view binding
        binding = ActivityTaskSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up observers
        setupObservers()

        // Set up click listeners
        setupClickListeners()

        // Add test button for network testing (temporary)
        addTestStroopButton()
    }

    /**
     * Add a test button for sending Stroop messages (for development/testing)
     */
    private fun addTestStroopButton() {
        // Create a test button
        val testButton = MaterialButton(this).apply {
            text = "Test Network Stroop"
            setBackgroundColor(getColor(R.color.primary))
            setTextColor(getColor(android.R.color.white))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(16, 16, 16, 16)
            }

            setOnClickListener {
                sendTestStroopMessage()
            }
        }

        // Add it to the top of the task list layout
        binding.layoutTaskList.addView(testButton, 0)
    }

    /**
     * Test method to send a sample Stroop display message
     */
    private fun sendTestStroopMessage() {
        lifecycleScope.launch {
            try {
                // Check if connected
                if (!ProjectorNetworkManager.isConnected()) {
                    showErrorSnackbar("Not connected to Master app")
                    return@launch
                }

                // Send a test Stroop display message
                Log.d("TaskSelection", "Sending test Stroop message")
                ProjectorNetworkManager.sendStroopDisplay(
                    word = "BLAU",
                    displayColor = "#FF0000", // Red color
                    correctAnswer = "rot" // Correct answer is "red" in German
                )

                Toast.makeText(
                    this@TaskSelectionActivity,
                    "Test Stroop message sent!",
                    Toast.LENGTH_SHORT
                ).show()

                // Send hidden message after 2 seconds
                delay(2000)
                Log.d("TaskSelection", "Sending Stroop hidden message")
                ProjectorNetworkManager.sendStroopHidden()

                Toast.makeText(
                    this@TaskSelectionActivity,
                    "Stroop hidden message sent!",
                    Toast.LENGTH_SHORT
                ).show()

            } catch (e: Exception) {
                Log.e("TaskSelection", "Error sending test message", e)
                showErrorSnackbar("Error: ${e.message}")
            }
        }
    }

    /**
     * Set up LiveData observers
     */
    private fun setupObservers() {
        // Observe configuration state
        viewModel.configState.observe(this) { state ->
            handleConfigState(state)
        }

        // Observe task sequences
        viewModel.taskSequences.observe(this) { sequences ->
            updateTaskSequenceList(sequences)
        }

        // Observe settings state
        viewModel.settingsState.observe(this) { state ->
            updateSettingsButton(state)
        }

        // Observe config management state
        viewModel.configManagementState.observe(this) { state ->
            updateConfigManagementUI(state)
        }

        // Observe navigation events with one-time handling
        viewModel.navigationEvent.observe(this) { event ->
            if (event != null && event != lastNavigationEvent) {
                lastNavigationEvent = event
                handleNavigationEvent(event)
            }
        }
    }

    /**
     * Set up click listeners
     */
    private fun setupClickListeners() {
        // Settings button
        binding.btnSettings.setOnClickListener {
            viewModel.openSettings()
        }

        // Export config button
        binding.btnExportConfig.setOnClickListener {
            handleExportConfigClick()
        }

        // Reload config button
        binding.btnReloadConfig.setOnClickListener {
            viewModel.reloadConfiguration()
        }

        // Error OK button
        binding.btnErrorOk.setOnClickListener {
            finish() // Return to MainActivity
        }

        // Config info button (shows config source details)
        binding.btnConfigInfo.setOnClickListener {
            showConfigInfoDialog()
        }
    }

    /**
     * Handle configuration state changes
     */
    private fun handleConfigState(state: ConfigState) {
        when (state) {
            is ConfigState.Loading -> {
                showLoadingState()
            }

            is ConfigState.Success -> {
                showSuccessState(state.source)
            }

            is ConfigState.Error -> {
                showErrorState(state.message)
            }
        }
    }

    /**
     * Handle navigation events
     */
    private fun handleNavigationEvent(event: NavigationEvent) {
        when (event) {
            is NavigationEvent.NavigateToTaskExecution -> {
                startTaskExecution(event.sessionState, event.runtimeConfig)
            }

            is NavigationEvent.NavigateToSettings -> {
                startSettings(event.runtimeConfig)
            }

            is NavigationEvent.ShowError -> {
                showErrorSnackbar(event.message)
            }

            is NavigationEvent.ShowConfigExported -> {
                showConfigExportedDialog(event.path)
            }
        }

        // Event is now "consumed" by being handled - no need to clear explicitly
    }

    /**
     * Show loading state
     */
    private fun showLoadingState() {
        binding.progressLoading.isVisible = true
        binding.layoutTaskList.isVisible = false
        binding.layoutError.isVisible = false
        binding.layoutEmpty.isVisible = false
        binding.btnSettings.isEnabled = false
        binding.btnExportConfig.isEnabled = false
        binding.btnReloadConfig.isEnabled = false
    }

    /**
     * Show success state
     */
    private fun showSuccessState(source: String) {
        binding.progressLoading.isVisible = false
        binding.layoutError.isVisible = false
        binding.layoutEmpty.isVisible = false
        binding.btnSettings.isEnabled = true
        binding.btnExportConfig.isEnabled = true
        binding.btnReloadConfig.isEnabled = true

        // Update config source display
        binding.tvConfigSource.text = "Config: $source"
        binding.tvConfigSource.isVisible = true
    }

    /**
     * Show error state
     */
    private fun showErrorState(errorMessage: String) {
        binding.progressLoading.isVisible = false
        binding.layoutTaskList.isVisible = false
        binding.layoutEmpty.isVisible = false
        binding.layoutError.isVisible = true
        binding.btnSettings.isEnabled = false
        binding.btnExportConfig.isEnabled = true  // Still allow export even on error
        binding.btnReloadConfig.isEnabled = true

        // Set error message
        binding.tvErrorMessage.text = errorMessage
    }

    /**
     * Show empty state (no task sequences found)
     */
    private fun showEmptyState() {
        binding.progressLoading.isVisible = false
        binding.layoutTaskList.isVisible = false
        binding.layoutError.isVisible = false
        binding.layoutEmpty.isVisible = true
        binding.btnSettings.isEnabled = true
        binding.btnExportConfig.isEnabled = true
        binding.btnReloadConfig.isEnabled = true
    }

    /**
     * Update config management UI
     */
    private fun updateConfigManagementUI(state: ConfigManagementState) {
        // Update export button text based on whether external config exists
        binding.btnExportConfig.text = if (state.hasExternalConfig) {
            "Update External Config"
        } else {
            "Export Config for Editing"
        }

        // Show/hide reload button based on external config availability
        binding.btnReloadConfig.isVisible = state.hasExternalConfig

        // Update config info button visibility
        binding.btnConfigInfo.isVisible = true

        // Store state for info dialog
        currentConfigState = state
    }

    private var currentConfigState: ConfigManagementState? = null

    /**
     * Handle export config button click
     */
    private fun handleExportConfigClick() {
        // Check storage permission
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED -> {
                viewModel.exportConfigToExternal()
            }

            shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) -> {
                showPermissionRationaleDialog()
            }

            else -> {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    /**
     * Show permission rationale dialog
     */
    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Storage Permission Required")
            .setMessage("This app needs storage permission to export configuration files for editing. The exported files will be stored in your Documents folder.")
            .setPositiveButton("Grant Permission") { _, _ ->
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Show permission denied dialog
     */
    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Denied")
            .setMessage("Storage permission is required to export configuration files. You can grant this permission in your device settings.")
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Show config exported success dialog
     */
    private fun showConfigExportedDialog(path: String) {
        AlertDialog.Builder(this)
            .setTitle("Config Exported Successfully")
            .setMessage("Configuration file has been exported to:\n\n$path\n\nYou can now edit this file with any text editor. Restart the app or tap 'Reload Now' to load changes.")
            .setPositiveButton("OK", null)
            .setNeutralButton("Reload Now") { _, _ ->
                viewModel.reloadConfiguration()
            }
            .show()
    }

    /**
     * Show config information dialog
     */
    private fun showConfigInfoDialog() {
        val state = currentConfigState
        val source = viewModel.getConfigSource()

        val message = buildString {
            appendLine("Current Configuration:")
            appendLine("Source: $source")
            appendLine()

            if (state != null) {
                appendLine("External Config Available: ${if (state.hasExternalConfig) "Yes" else "No"}")

                if (state.hasExternalConfig) {
                    appendLine("External Path: ${state.externalConfigPath}")
                } else {
                    appendLine("Export Location: ${state.externalConfigDirectory}")
                }

                appendLine()
                appendLine("To customize configuration:")
                appendLine("1. Tap 'Export Config for Editing'")
                appendLine("2. Edit the exported file with any text editor")
                appendLine("3. Tap 'Reload Config' to apply changes")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Configuration Information")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Update task sequence list
     */
    private fun updateTaskSequenceList(sequences: List<TaskSequenceItem>) {
        if (sequences.isEmpty()) {
            showEmptyState()
            return
        }

        // Show task list
        binding.layoutTaskList.isVisible = true

        // Clear existing buttons (except the test button at position 0)
        while (binding.layoutTaskList.childCount > 1) {
            binding.layoutTaskList.removeViewAt(1)
        }

        // Create button for each task sequence
        sequences.forEach { sequence ->
            val button = createTaskSequenceButton(sequence)
            binding.layoutTaskList.addView(button)
        }
    }

    /**
     * Create a button for a task sequence
     */
    private fun createTaskSequenceButton(sequence: TaskSequenceItem): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            // Set text and styling
            text = sequence.getDisplayText()
            contentDescription = sequence.getAccessibilityDescription()

            // Set layout parameters
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(
                    resources.getDimensionPixelSize(R.dimen.margin_small),
                    resources.getDimensionPixelSize(R.dimen.margin_small),
                    resources.getDimensionPixelSize(R.dimen.margin_small),
                    resources.getDimensionPixelSize(R.dimen.margin_small)
                )
            }

            // Set minimum height for easy touch
            minHeight = resources.getDimensionPixelSize(R.dimen.list_item_height_large)

            // Set text alignment and padding
            textAlignment = View.TEXT_ALIGNMENT_TEXT_START
            setPadding(
                resources.getDimensionPixelSize(R.dimen.padding_large),
                resources.getDimensionPixelSize(R.dimen.padding_medium),
                resources.getDimensionPixelSize(R.dimen.padding_large),
                resources.getDimensionPixelSize(R.dimen.padding_medium)
            )

            // Set text size
            textSize = resources.getDimension(R.dimen.text_size_body_large) / resources.displayMetrics.scaledDensity

            // Set click listener
            setOnClickListener {
                viewModel.selectTaskSequence(sequence.id)
            }
        }
    }

    /**
     * Update settings button based on settings state
     */
    private fun updateSettingsButton(state: SettingsState) {
        binding.btnSettings.isEnabled = state.isAvailable

        // Since we're using regular Button instead of MaterialButton,
        // we can change the text to indicate custom settings
        if (state.hasCustomSettings) {
            binding.btnSettings.text = "⚙*" // Star indicates custom settings
        } else {
            binding.btnSettings.text = "⚙"
        }
    }

    /**
     * Start task execution activity
     */
    private fun startTaskExecution(sessionState: SessionState, runtimeConfig: RuntimeConfig) {
        val intent = Intent(this, TaskExecutionActivity::class.java).apply {
            putExtra(EXTRA_SESSION_STATE, sessionState)
            putExtra(EXTRA_RUNTIME_CONFIG, runtimeConfig)
        }
        startActivity(intent)
    }

    /**
     * Start settings activity
     */
    private fun startSettings(runtimeConfig: RuntimeConfig) {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            putExtra(EXTRA_RUNTIME_CONFIG, runtimeConfig)
        }
        startActivityForResult(intent, REQUEST_SETTINGS)
    }

    /**
     * Show error message as snackbar
     */
    private fun showErrorSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction("Retry") {
                viewModel.retryConfiguration()
            }
            .show()
    }

    /**
     * Handle returning from settings
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_SETTINGS) {
            // Notify ViewModel that we returned from settings
            viewModel.onReturnFromSettings()
        }
    }

    /**
     * Handle back button press
     */
    override fun onBackPressed() {
        // Return to MainActivity
        finish()
    }

    /**
     * Handle lifecycle events
     */
    override fun onResume() {
        super.onResume()

        // Refresh settings state in case settings were changed
        viewModel.onReturnFromSettings()
    }

    companion object {
        // Intent extras
        const val EXTRA_SESSION_STATE = "extra_session_state"
        const val EXTRA_RUNTIME_CONFIG = "extra_runtime_config"

        // Request codes
        private const val REQUEST_SETTINGS = 1001
    }
}