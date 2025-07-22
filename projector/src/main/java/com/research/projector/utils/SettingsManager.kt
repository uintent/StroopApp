package com.research.projector.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.research.projector.models.RuntimeConfig
import com.research.projector.models.StroopConfig
import com.research.projector.models.TimingConfig

/**
 * Manages app settings persistence using SharedPreferences.
 * Handles timing parameter overrides while preserving original JSON configuration.
 */
class SettingsManager(private val context: Context) {
    
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )
    
    companion object {
        private const val PREFS_NAME = "stroop_app_settings"
        
        // Setting keys
        private const val KEY_STROOP_DISPLAY_DURATION = "stroop_display_duration"
        private const val KEY_MIN_INTERVAL = "min_interval"
        private const val KEY_MAX_INTERVAL = "max_interval"
        private const val KEY_COUNTDOWN_DURATION = "countdown_duration"
        private const val KEY_SETTINGS_INITIALIZED = "settings_initialized"
        
        // Default values (will be overridden by JSON config)
        private const val DEFAULT_STROOP_DURATION = 2000
        private const val DEFAULT_MIN_INTERVAL = 1000
        private const val DEFAULT_MAX_INTERVAL = 3000
        private const val DEFAULT_COUNTDOWN_DURATION = 4
    }
    
    /**
     * Save timing settings to SharedPreferences
     */
    fun saveTimingSettings(
        stroopDisplayDuration: Int,
        minInterval: Int,
        maxInterval: Int,
        countdownDuration: Int
    ) {
        sharedPreferences.edit {
            putInt(KEY_STROOP_DISPLAY_DURATION, stroopDisplayDuration)
            putInt(KEY_MIN_INTERVAL, minInterval)
            putInt(KEY_MAX_INTERVAL, maxInterval)
            putInt(KEY_COUNTDOWN_DURATION, countdownDuration)
            putBoolean(KEY_SETTINGS_INITIALIZED, true)
        }
    }
    
    /**
     * Save timing settings from TimingConfig object
     */
    fun saveTimingSettings(timingConfig: TimingConfig) {
        saveTimingSettings(
            stroopDisplayDuration = timingConfig.stroopDisplayDuration,
            minInterval = timingConfig.minInterval,
            maxInterval = timingConfig.maxInterval,
            countdownDuration = timingConfig.countdownDuration
        )
    }
    
    /**
     * Load individual timing setting with fallback to provided default
     */
    fun getStroopDisplayDuration(defaultValue: Int = DEFAULT_STROOP_DURATION): Int {
        return sharedPreferences.getInt(KEY_STROOP_DISPLAY_DURATION, defaultValue)
    }
    
    fun getMinInterval(defaultValue: Int = DEFAULT_MIN_INTERVAL): Int {
        return sharedPreferences.getInt(KEY_MIN_INTERVAL, defaultValue)
    }
    
    fun getMaxInterval(defaultValue: Int = DEFAULT_MAX_INTERVAL): Int {
        return sharedPreferences.getInt(KEY_MAX_INTERVAL, defaultValue)
    }
    
    fun getCountdownDuration(defaultValue: Int = DEFAULT_COUNTDOWN_DURATION): Int {
        return sharedPreferences.getInt(KEY_COUNTDOWN_DURATION, defaultValue)
    }
    
    /**
     * Check if settings have been initialized (user has saved custom settings)
     */
    fun areSettingsInitialized(): Boolean {
        return sharedPreferences.getBoolean(KEY_SETTINGS_INITIALIZED, false)
    }
    
    /**
     * Create RuntimeConfig with settings overrides applied to base configuration
     */
    fun createRuntimeConfig(baseConfig: StroopConfig): RuntimeConfig {
        return if (areSettingsInitialized()) {
            // Use saved settings as overrides
            RuntimeConfig(
                baseConfig = baseConfig,
                stroopDisplayDuration = getStroopDisplayDuration(baseConfig.timing.stroopDisplayDuration),
                minInterval = getMinInterval(baseConfig.timing.minInterval),
                maxInterval = getMaxInterval(baseConfig.timing.maxInterval),
                countdownDuration = getCountdownDuration(baseConfig.timing.countdownDuration)
            )
        } else {
            // Use base config values (no overrides)
            RuntimeConfig(baseConfig)
        }
    }
    
    /**
     * Reset settings to defaults from base configuration
     */
    fun resetToDefaults(baseConfig: StroopConfig) {
        saveTimingSettings(baseConfig.timing)
        
        // Mark as not initialized so next load will use base config
        sharedPreferences.edit {
            putBoolean(KEY_SETTINGS_INITIALIZED, false)
        }
    }
    
    /**
     * Get current timing settings as TimingConfig object
     */
    fun getCurrentTimingConfig(baseConfig: StroopConfig): TimingConfig {
        return TimingConfig(
            stroopDisplayDuration = getStroopDisplayDuration(baseConfig.timing.stroopDisplayDuration),
            minInterval = getMinInterval(baseConfig.timing.minInterval),
            maxInterval = getMaxInterval(baseConfig.timing.maxInterval),
            countdownDuration = getCountdownDuration(baseConfig.timing.countdownDuration)
        )
    }
    
    /**
     * Validate timing settings before saving
     */
    fun validateTimingSettings(
        stroopDisplayDuration: Int,
        minInterval: Int,
        maxInterval: Int,
        countdownDuration: Int
    ): SettingsValidationResult {
        
        if (stroopDisplayDuration <= 0) {
            return SettingsValidationResult.Error("Stroop display duration must be positive")
        }
        
        if (minInterval <= 0) {
            return SettingsValidationResult.Error("Minimum interval must be positive")
        }
        
        if (maxInterval <= 0) {
            return SettingsValidationResult.Error("Maximum interval must be positive")
        }
        
        if (minInterval > maxInterval) {
            return SettingsValidationResult.Error("Minimum interval cannot be greater than maximum interval")
        }
        
        if (countdownDuration <= 0) {
            return SettingsValidationResult.Error("Countdown duration must be positive")
        }
        
        // Additional research-appropriate validation
        if (stroopDisplayDuration < 100) {
            return SettingsValidationResult.Error("Stroop display duration too short (minimum 100ms)")
        }
        
        if (stroopDisplayDuration > 10000) {
            return SettingsValidationResult.Error("Stroop display duration too long (maximum 10 seconds)")
        }
        
        if (minInterval < 100) {
            return SettingsValidationResult.Error("Minimum interval too short (minimum 100ms)")
        }
        
        if (maxInterval > 30000) {
            return SettingsValidationResult.Error("Maximum interval too long (maximum 30 seconds)")
        }
        
        if (countdownDuration > 10) {
            return SettingsValidationResult.Error("Countdown duration too long (maximum 10 seconds)")
        }
        
        return SettingsValidationResult.Valid
    }
    
    /**
     * Save settings with validation
     */
    fun saveTimingSettingsWithValidation(
        stroopDisplayDuration: Int,
        minInterval: Int,
        maxInterval: Int,
        countdownDuration: Int
    ): SettingsValidationResult {
        
        val validationResult = validateTimingSettings(
            stroopDisplayDuration, minInterval, maxInterval, countdownDuration
        )
        
        return if (validationResult is SettingsValidationResult.Valid) {
            saveTimingSettings(stroopDisplayDuration, minInterval, maxInterval, countdownDuration)
            SettingsValidationResult.Valid
        } else {
            validationResult
        }
    }
    
    /**
     * Clear all settings (for testing or factory reset)
     */
    fun clearAllSettings() {
        sharedPreferences.edit {
            clear()
        }
    }
    
    /**
     * Get all current settings as a map for debugging/export
     */
    fun getAllSettings(): Map<String, Any> {
        return mapOf(
            "stroop_display_duration" to getStroopDisplayDuration(),
            "min_interval" to getMinInterval(),
            "max_interval" to getMaxInterval(),
            "countdown_duration" to getCountdownDuration(),
            "settings_initialized" to areSettingsInitialized()
        )
    }
    
    /**
     * Check if current settings differ from base configuration
     */
    fun hasCustomSettings(baseConfig: StroopConfig): Boolean {
        if (!areSettingsInitialized()) return false
        
        val currentTiming = getCurrentTimingConfig(baseConfig)
        val baseTiming = baseConfig.timing
        
        return currentTiming.stroopDisplayDuration != baseTiming.stroopDisplayDuration ||
                currentTiming.minInterval != baseTiming.minInterval ||
                currentTiming.maxInterval != baseTiming.maxInterval ||
                currentTiming.countdownDuration != baseTiming.countdownDuration
    }
    
    /**
     * Get settings summary for display
     */
    fun getSettingsSummary(baseConfig: StroopConfig): String {
        val current = getCurrentTimingConfig(baseConfig)
        val customized = if (hasCustomSettings(baseConfig)) " (customized)" else ""
        
        return """
            Stroop Display: ${current.stroopDisplayDuration}ms
            Min Interval: ${current.minInterval}ms
            Max Interval: ${current.maxInterval}ms
            Countdown: ${current.countdownDuration}s$customized
        """.trimIndent()
    }
}

/**
 * Result of settings validation
 */
sealed class SettingsValidationResult {
    object Valid : SettingsValidationResult()
    data class Error(val message: String) : SettingsValidationResult()
}

/**
 * Extension functions for easier settings management
 */
fun RuntimeConfig.saveToSettings(settingsManager: SettingsManager) {
    settingsManager.saveTimingSettings(getEffectiveTiming())
}

fun SettingsManager.loadRuntimeConfig(baseConfig: StroopConfig): RuntimeConfig {
    return createRuntimeConfig(baseConfig)
}