package com.research.master.utils

import android.content.Context
import com.research.shared.models.RuntimeConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages configuration loading for the Master app.
 * Now uses FileManager directly for all file operations.
 * Handles loading config from JSON only - no longer sends config to Projector devices.
 */
object MasterConfigManager {

    private const val TAG = "MasterConfigManager"

    private var fileManager: FileManager? = null
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
        fileManager = FileManager(context.applicationContext)
    }

    /**
     * Load configuration from JSON file
     */
    suspend fun loadConfiguration(): Boolean {
        val manager = fileManager ?: run {
            _configState.value = ConfigState.Error("ConfigManager not initialized")
            return false
        }

        _configState.value = ConfigState.Loading

        return try {
            when (val result = manager.loadConfiguration()) {
                is ConfigLoadResult.Success -> {
                    currentConfig = result.config
                    _configState.value = ConfigState.Loaded(result.config, result.source)
                    DebugLogger.d(TAG, "Configuration loaded successfully from: ${result.source}")
                    true
                }
                is ConfigLoadResult.Error -> {
                    val errorMessage = manager.getErrorMessage(result.error)
                    _configState.value = ConfigState.Error(errorMessage)
                    DebugLogger.e(TAG, "Failed to load configuration: $errorMessage")
                    false
                }
                else -> {
                    val errorMessage = "Unknown configuration load result"
                    _configState.value = ConfigState.Error(errorMessage)
                    DebugLogger.e(TAG, errorMessage)
                    false
                }
            }
        } catch (e: Exception) {
            val errorMessage = "Unexpected error loading configuration: ${e.message}"
            _configState.value = ConfigState.Error(errorMessage)
            DebugLogger.e(TAG, errorMessage, e)
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
        DebugLogger.d(TAG, "Runtime configuration updated")
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
        DebugLogger.d(TAG, "Reloading configuration...")
        return loadConfiguration()
    }

    /**
     * Get FileManager for advanced file operations
     */
    fun getFileManager(): FileManager? = fileManager

    /**
     * Copy default configuration to external storage
     * Delegates to FileManager
     */
    suspend fun copyDefaultConfigToExternal(): Boolean {
        val manager = fileManager ?: return false
        return manager.copyDefaultConfigToExternal()
    }

    /**
     * Check if external config file exists
     * Delegates to FileManager
     */
    fun hasExternalConfig(): Boolean {
        val manager = fileManager ?: return false
        return manager.hasExternalConfig()
    }

    /**
     * Get external config path
     * Delegates to FileManager
     */
    fun getExternalConfigPath(): String {
        val manager = fileManager ?: return ""
        return manager.getExternalConfigPath()
    }

    /**
     * Get external config directory
     * Delegates to FileManager
     */
    fun getExternalConfigDirectory(): String {
        val manager = fileManager ?: return ""
        return manager.getExternalConfigDirectory()
    }

    /**
     * Reset configuration state
     */
    fun reset() {
        currentConfig = null
        _configState.value = ConfigState.NotLoaded
        DebugLogger.d(TAG, "Configuration state reset")
    }
}