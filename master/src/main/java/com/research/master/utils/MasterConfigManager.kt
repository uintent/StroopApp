package com.research.master.utils

import android.content.Context
import android.util.Log
import com.research.shared.models.RuntimeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages configuration loading for the Master app.
 * Handles loading config from JSON only - no longer sends config to Projector devices.
 */
object MasterConfigManager {

    private const val TAG = "MasterConfigManager"

    private var configLoader: MasterConfigLoader? = null
    private val _configState = MutableStateFlow<ConfigState>(ConfigState.NotLoaded)
    val configState: StateFlow<ConfigState> = _configState.asStateFlow()

    private var currentConfig: RuntimeConfig? = null

    /**
     * Configuration state enum
     */
    sealed class ConfigState {
        object NotLoaded : ConfigState()
        object Loading : ConfigState()
        data class Loaded(val config: RuntimeConfig, val source: String) : ConfigState()
        data class Error(val message: String) : ConfigState()
    }

    /**
     * Initialize the config manager with application context
     */
    fun initialize(context: Context) {
        configLoader = MasterConfigLoader(context.applicationContext)
    }

    /**
     * Load configuration from JSON file
     */
    suspend fun loadConfiguration(): Boolean {
        val loader = configLoader ?: run {
            _configState.value = ConfigState.Error("ConfigManager not initialized")
            return false
        }

        _configState.value = ConfigState.Loading

        return try {
            when (val result = loader.loadConfig()) {
                is ConfigLoadResult.Success -> {
                    currentConfig = result.config
                    _configState.value = ConfigState.Loaded(result.config, result.source)
                    Log.d(TAG, "Configuration loaded successfully from: ${result.source}")
                    true
                }
                is ConfigLoadResult.Error -> {
                    val errorMessage = loader.getErrorMessage(result.error)
                    _configState.value = ConfigState.Error(errorMessage)
                    Log.e(TAG, "Failed to load configuration: $errorMessage")
                    false
                }
            }
        } catch (e: Exception) {
            val errorMessage = "Unexpected error loading configuration: ${e.message}"
            _configState.value = ConfigState.Error(errorMessage)
            Log.e(TAG, errorMessage, e)
            false
        }
    }

    /**
     * Get current runtime configuration
     */
    fun getCurrentConfig(): RuntimeConfig? = currentConfig

    /**
     * Update runtime configuration (for settings changes)
     */
    fun updateRuntimeConfig(updatedConfig: RuntimeConfig) {
        currentConfig = updatedConfig
        _configState.value = ConfigState.Loaded(updatedConfig, "Runtime Update")
        Log.d(TAG, "Runtime configuration updated")
    }

    /**
     * Check if configuration is loaded and ready
     */
    fun isConfigurationReady(): Boolean {
        return currentConfig != null && _configState.value is ConfigState.Loaded
    }

    /**
     * Reload configuration from file
     */
    suspend fun reloadConfiguration(): Boolean {
        Log.d(TAG, "Reloading configuration...")
        return loadConfiguration()
    }

    /**
     * Get configuration loader for advanced operations
     */
    fun getConfigLoader(): MasterConfigLoader? = configLoader

    /**
     * Reset configuration state
     */
    fun reset() {
        currentConfig = null
        _configState.value = ConfigState.NotLoaded
        Log.d(TAG, "Configuration state reset")
    }
}