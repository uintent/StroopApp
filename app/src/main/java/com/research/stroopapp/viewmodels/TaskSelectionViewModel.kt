package com.research.stroopapp.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.research.stroopapp.models.RuntimeConfig
import com.research.stroopapp.models.SessionState
import com.research.stroopapp.models.TaskListConfig
import com.research.stroopapp.utils.ConfigLoadResult
import com.research.stroopapp.utils.ConfigLoader
import com.research.stroopapp.utils.SettingsManager
import kotlinx.coroutines.launch

/**
 * ViewModel for TaskSelectionActivity
 * Manages configuration loading, task sequence selection, and settings access
 * Now supports external config files with fallback to assets
 */
class TaskSelectionViewModel(application: Application) : AndroidViewModel(application) {

    private val configLoader = ConfigLoader(application)
    private val settingsManager = SettingsManager(application)

    // Configuration state
    private val _configState = MutableLiveData<ConfigState>()
    val configState: LiveData<ConfigState> = _configState

    // Task sequences available for selection
    private val _taskSequences = MutableLiveData<List<TaskSequenceItem>>()
    val taskSequences: LiveData<List<TaskSequenceItem>> = _taskSequences

    // Settings state
    private val _settingsState = MutableLiveData<SettingsState>()
    val settingsState: LiveData<SettingsState> = _settingsState

    // Config management state (for external config support)
    private val _configManagementState = MutableLiveData<ConfigManagementState>()
    val configManagementState: LiveData<ConfigManagementState> = _configManagementState

    // Navigation events (single-shot events)
    private val _navigationEvent = MutableLiveData<NavigationEvent?>()
    val navigationEvent: LiveData<NavigationEvent?> = _navigationEvent

    // Current runtime configuration
    private var runtimeConfig: RuntimeConfig? = null
    private var configSource: String = ""

    init {
        loadConfiguration()
        updateConfigManagementState()
    }

    /**
     * Load configuration from external storage or assets and initialize task sequences
     */
    fun loadConfiguration() {
        _configState.value = ConfigState.Loading

        viewModelScope.launch {
            when (val result = configLoader.loadConfig()) {
                is ConfigLoadResult.Success -> {
                    runtimeConfig = settingsManager.createRuntimeConfig(result.config.baseConfig)
                    configSource = result.source

                    val taskSequenceItems = createTaskSequenceItems(runtimeConfig!!.baseConfig.taskLists)

                    if (taskSequenceItems.isNotEmpty()) {
                        _taskSequences.value = taskSequenceItems
                        _configState.value = ConfigState.Success(configSource)
                        updateSettingsState()
                        updateConfigManagementState()
                    } else {
                        _configState.value = ConfigState.Error("No task sequences found in configuration")
                    }
                }

                is ConfigLoadResult.Error -> {
                    val errorMessage = configLoader.getErrorMessage(result.error)
                    _configState.value = ConfigState.Error(errorMessage)
                }
            }
        }
    }

    /**
     * Export default configuration to external storage for user editing
     */
    fun exportConfigToExternal() {
        viewModelScope.launch {
            val success = configLoader.copyDefaultConfigToExternal()

            if (success) {
                val path = configLoader.getExternalConfigPath()
                _navigationEvent.value = NavigationEvent.ShowConfigExported(path)
                updateConfigManagementState()
            } else {
                _navigationEvent.value = NavigationEvent.ShowError("Failed to export configuration. Check storage permissions.")
            }
        }
    }

    /**
     * Check if external config exists and get its path
     */
    private fun updateConfigManagementState() {
        val hasExternal = configLoader.hasExternalConfig()
        val externalPath = configLoader.getExternalConfigPath()
        val externalDir = configLoader.getExternalConfigDirectory()

        _configManagementState.value = ConfigManagementState(
            hasExternalConfig = hasExternal,
            externalConfigPath = externalPath,
            externalConfigDirectory = externalDir,
            canExport = true
        )
    }

    /**
     * Retry configuration loading after error
     */
    fun retryConfiguration() {
        loadConfiguration()
    }

    /**
     * Select a task sequence and create session
     */
    fun selectTaskSequence(taskSequenceId: String) {
        val config = runtimeConfig
        if (config == null) {
            _navigationEvent.value = NavigationEvent.ShowError("Configuration not loaded")
            return
        }

        val taskListConfig = config.baseConfig.taskLists[taskSequenceId]
        if (taskListConfig == null) {
            _navigationEvent.value = NavigationEvent.ShowError("Task sequence not found: $taskSequenceId")
            return
        }

        // Create session state
        val sessionState = SessionState(
            taskSequenceId = taskSequenceId,
            taskSequenceLabel = taskListConfig.label,
            taskIds = taskListConfig.getTaskIds()
        )

        // Navigate to task execution with session and config
        _navigationEvent.value = NavigationEvent.NavigateToTaskExecution(sessionState, config)
    }

    /**
     * Navigate to settings screen
     */
    fun openSettings() {
        val config = runtimeConfig
        if (config != null) {
            _navigationEvent.value = NavigationEvent.NavigateToSettings(config)
        } else {
            _navigationEvent.value = NavigationEvent.ShowError("Configuration not loaded")
        }
    }

    /**
     * Handle return from settings screen with updated configuration
     */
    fun onReturnFromSettings() {
        // Reload runtime config to pick up any settings changes
        val baseConfig = runtimeConfig?.baseConfig
        if (baseConfig != null) {
            runtimeConfig = settingsManager.createRuntimeConfig(baseConfig)
            updateSettingsState()
        }
    }

    /**
     * Reload configuration (useful after external config changes)
     */
    fun reloadConfiguration() {
        loadConfiguration()
    }

    /**
     * Create task sequence items for display
     */
    private fun createTaskSequenceItems(taskLists: Map<String, TaskListConfig>): List<TaskSequenceItem> {
        return taskLists.map { (id, config) ->
            val taskIds = config.getTaskIds()
            TaskSequenceItem(
                id = id,
                label = config.label,
                taskCount = taskIds.size,
                taskIds = taskIds,
                description = "Tasks: ${taskIds.joinToString(", ")}"
            )
        }.sortedBy { it.label }
    }

    /**
     * Update settings state based on current configuration
     */
    private fun updateSettingsState() {
        val config = runtimeConfig
        if (config != null) {
            val hasCustomSettings = settingsManager.hasCustomSettings(config.baseConfig)
            val settingsSummary = settingsManager.getSettingsSummary(config.baseConfig)

            _settingsState.value = SettingsState(
                hasCustomSettings = hasCustomSettings,
                settingsSummary = settingsSummary,
                isAvailable = true
            )
        } else {
            _settingsState.value = SettingsState(
                hasCustomSettings = false,
                settingsSummary = "Configuration not loaded",
                isAvailable = false
            )
        }
    }

    /**
     * Get current runtime configuration (for testing/debugging)
     */
    fun getCurrentRuntimeConfig(): RuntimeConfig? = runtimeConfig

    /**
     * Get current config source information
     */
    fun getConfigSource(): String = configSource

    /**
     * Clear navigation event after handling
     */
    fun clearNavigationEvent() {
        // Don't set to null - let the activity handle one-time events properly
        // The event will be naturally "consumed" by being handled
    }
}

/**
 * Configuration loading state
 */
sealed class ConfigState {
    object Loading : ConfigState()
    data class Success(val source: String) : ConfigState()
    data class Error(val message: String) : ConfigState()
}

/**
 * Settings display state
 */
data class SettingsState(
    val hasCustomSettings: Boolean,
    val settingsSummary: String,
    val isAvailable: Boolean
)

/**
 * Config management state for external file support
 */
data class ConfigManagementState(
    val hasExternalConfig: Boolean,
    val externalConfigPath: String,
    val externalConfigDirectory: String,
    val canExport: Boolean
)

/**
 * Navigation events (single-shot events)
 */
sealed class NavigationEvent {
    data class NavigateToTaskExecution(
        val sessionState: SessionState,
        val runtimeConfig: RuntimeConfig
    ) : NavigationEvent()

    data class NavigateToSettings(val runtimeConfig: RuntimeConfig) : NavigationEvent()
    data class ShowError(val message: String) : NavigationEvent()
    data class ShowConfigExported(val path: String) : NavigationEvent()
}

/**
 * Task sequence item for display in list
 */
data class TaskSequenceItem(
    val id: String,
    val label: String,
    val taskCount: Int,
    val taskIds: List<String>,
    val description: String
) {
    /**
     * Get display text for the task sequence
     */
    fun getDisplayText(): String {
        return "$label ($taskCount tasks)"
    }

    /**
     * Get detailed description for accessibility
     */
    fun getAccessibilityDescription(): String {
        return "Task sequence: $label with $taskCount tasks: ${taskIds.joinToString(", ")}"
    }
}