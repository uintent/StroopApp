package com.research.projector

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import com.research.projector.databinding.ActivitySettingsBinding
import com.research.projector.models.RuntimeConfig
import com.research.projector.utils.SettingsManager
import com.research.projector.utils.SettingsValidationResult

/**
 * Activity for configuring timing settings.
 * Allows real-time adjustment of Stroop display and interval timing parameters.
 */
class SettingsActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settingsManager: SettingsManager
    private var runtimeConfig: RuntimeConfig? = null
    
    // Current values for real-time validation
    private var currentStroopDuration = 0
    private var currentMinInterval = 0
    private var currentMaxInterval = 0
    private var currentCountdownDuration = 0
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set up view binding
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Initialize settings manager
        settingsManager = SettingsManager(this)
        
        // Get runtime config from intent
        extractIntentData()
        
        // Load current settings
        loadCurrentSettings()
        
        // Set up text watchers for real-time validation
        setupTextWatchers()
        
        // Set up click listeners
        setupClickListeners()
        
        // Update preview
        updatePreview()
    }
    
    /**
     * Extract runtime configuration from intent
     */
    private fun extractIntentData() {
        runtimeConfig = intent.getSerializableExtra(TaskSelectionActivity.EXTRA_RUNTIME_CONFIG) as? RuntimeConfig
        
        if (runtimeConfig == null) {
            showErrorAndFinish("Invalid configuration data received")
        }
    }
    
    /**
     * Load current settings into form fields
     */
    private fun loadCurrentSettings() {
        val config = runtimeConfig ?: return
        
        val currentTiming = settingsManager.getCurrentTimingConfig(config.baseConfig)
        
        // Set input field values
        binding.etStroopDuration.setText(currentTiming.stroopDisplayDuration.toString())
        binding.etMinInterval.setText(currentTiming.minInterval.toString())
        binding.etMaxInterval.setText(currentTiming.maxInterval.toString())
        binding.etCountdownDuration.setText(currentTiming.countdownDuration.toString())
        
        // Store current values
        currentStroopDuration = currentTiming.stroopDisplayDuration
        currentMinInterval = currentTiming.minInterval
        currentMaxInterval = currentTiming.maxInterval
        currentCountdownDuration = currentTiming.countdownDuration
    }
    
    /**
     * Set up text watchers for real-time validation and preview updates
     */
    private fun setupTextWatchers() {
        // Stroop display duration
        binding.etStroopDuration.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentStroopDuration = s?.toString()?.toIntOrNull() ?: 0
                validateAndUpdatePreview()
            }
        })
        
        // Minimum interval
        binding.etMinInterval.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentMinInterval = s?.toString()?.toIntOrNull() ?: 0
                validateAndUpdatePreview()
            }
        })
        
        // Maximum interval
        binding.etMaxInterval.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentMaxInterval = s?.toString()?.toIntOrNull() ?: 0
                validateAndUpdatePreview()
            }
        })
        
        // Countdown duration
        binding.etCountdownDuration.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentCountdownDuration = s?.toString()?.toIntOrNull() ?: 0
                validateAndUpdatePreview()
            }
        })
    }
    
    /**
     * Set up click listeners for action buttons
     */
    private fun setupClickListeners() {
        // Save settings button
        binding.btnSave.setOnClickListener {
            saveSettings()
        }
        
        // Reset to defaults button
        binding.btnResetDefaults.setOnClickListener {
            resetToDefaults()
        }
        
        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }
    }
    
    /**
     * Validate current inputs and update preview
     */
    private fun validateAndUpdatePreview() {
        val validationResult = settingsManager.validateTimingSettings(
            currentStroopDuration,
            currentMinInterval,
            currentMaxInterval,
            currentCountdownDuration
        )
        
        when (validationResult) {
            is SettingsValidationResult.Valid -> {
                hideValidationError()
                enableSaveButton()
            }
            
            is SettingsValidationResult.Error -> {
                showValidationError(validationResult.message)
                disableSaveButton()
            }
        }
        
        updatePreview()
    }
    
    /**
     * Update the configuration preview
     */
    private fun updatePreview() {
        val previewText = buildString {
            append("Stroop Display: ${currentStroopDuration}ms\n")
            append("Min Interval: ${currentMinInterval}ms\n")
            append("Max Interval: ${currentMaxInterval}ms\n")
            append("Countdown: ${currentCountdownDuration}s")
            
            // Add timing analysis
            if (currentMinInterval > 0 && currentMaxInterval > 0 && currentStroopDuration > 0) {
                val avgInterval = (currentMinInterval + currentMaxInterval) / 2.0
                val avgCycleTime = currentStroopDuration + avgInterval
                append("\n\nAverage cycle: ${avgCycleTime.toInt()}ms")
                
                // Estimate stimuli per minute
                if (avgCycleTime > 0) {
                    val stimuliPerMinute = (60000 / avgCycleTime).toInt()
                    append("\nEstimated rate: ~${stimuliPerMinute} stimuli/min")
                }
            }
        }
        
        binding.tvCurrentValues.text = previewText
    }
    
    /**
     * Show validation error message
     */
    private fun showValidationError(message: String) {
        binding.tvValidationMessage.text = message
        binding.tvValidationMessage.isVisible = true
    }
    
    /**
     * Hide validation error message
     */
    private fun hideValidationError() {
        binding.tvValidationMessage.isVisible = false
    }
    
    /**
     * Enable save button
     */
    private fun enableSaveButton() {
        binding.btnSave.isEnabled = true
    }
    
    /**
     * Disable save button
     */
    private fun disableSaveButton() {
        binding.btnSave.isEnabled = false
    }
    
    /**
     * Save current settings
     */
    private fun saveSettings() {
        val validationResult = settingsManager.saveTimingSettingsWithValidation(
            currentStroopDuration,
            currentMinInterval,
            currentMaxInterval,
            currentCountdownDuration
        )
        
        when (validationResult) {
            is SettingsValidationResult.Valid -> {
                showSuccessMessage("Settings saved successfully")
                setResult(RESULT_OK)
            }
            
            is SettingsValidationResult.Error -> {
                showErrorMessage("Failed to save settings: ${validationResult.message}")
            }
        }
    }
    
    /**
     * Reset settings to configuration defaults
     */
    private fun resetToDefaults() {
        val config = runtimeConfig ?: return
        
        // Show confirmation dialog
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Reset to Defaults")
            .setMessage("This will reset all timing settings to the original configuration values. Continue?")
            .setPositiveButton("Reset") { _, _ ->
                performResetToDefaults(config)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    /**
     * Perform the actual reset to defaults
     */
    private fun performResetToDefaults(config: RuntimeConfig) {
        // Reset settings manager
        settingsManager.resetToDefaults(config.baseConfig)
        
        // Reload settings into form
        loadCurrentSettings()
        
        // Update preview
        updatePreview()
        
        // Show success message
        showSuccessMessage("Settings reset to defaults")
    }
    
    /**
     * Show success message
     */
    private fun showSuccessMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(getColor(R.color.success))
            .setTextColor(getColor(R.color.on_success))
            .show()
    }
    
    /**
     * Show error message
     */
    private fun showErrorMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setBackgroundTint(getColor(R.color.error))
            .setTextColor(getColor(R.color.on_error))
            .show()
    }
    
    /**
     * Show error and finish activity
     */
    private fun showErrorAndFinish(message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Handle back button press
     */
    override fun onBackPressed() {
        // Check if there are unsaved changes
        if (hasUnsavedChanges()) {
            showUnsavedChangesDialog()
        } else {
            super.onBackPressed()
        }
    }
    
    /**
     * Check if there are unsaved changes
     */
    private fun hasUnsavedChanges(): Boolean {
        val config = runtimeConfig ?: return false
        val currentTiming = settingsManager.getCurrentTimingConfig(config.baseConfig)
        
        return currentStroopDuration != currentTiming.stroopDisplayDuration ||
                currentMinInterval != currentTiming.minInterval ||
                currentMaxInterval != currentTiming.maxInterval ||
                currentCountdownDuration != currentTiming.countdownDuration
    }
    
    /**
     * Show unsaved changes confirmation dialog
     */
    private fun showUnsavedChangesDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Unsaved Changes")
            .setMessage("You have unsaved changes. Do you want to save them before leaving?")
            .setPositiveButton("Save") { _, _ ->
                saveSettings()
                if (binding.btnSave.isEnabled) {
                    // Only finish if save was successful (button still enabled means validation failed)
                    finish()
                }
            }
            .setNegativeButton("Discard") { _, _ ->
                finish()
            }
            .setNeutralButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    /**
     * Format timing value for display
     */
    private fun formatTimingValue(value: Int, unit: String): String {
        return when {
            value >= 1000 && unit == "ms" -> "${value / 1000}.${(value % 1000) / 100}s"
            else -> "$value$unit"
        }
    }
    
    /**
     * Get settings summary for debugging
     */
    private fun getSettingsSummary(): String {
        val config = runtimeConfig ?: return "No configuration"
        return settingsManager.getSettingsSummary(config.baseConfig)
    }
}