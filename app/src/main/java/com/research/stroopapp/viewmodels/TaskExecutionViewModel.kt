package com.research.stroopapp.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.research.stroopapp.models.*
import com.research.stroopapp.utils.StroopGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ViewModel for TaskExecutionActivity (between-tasks screen)
 * Manages session state, task progression, and coordination with Stroop display
 */
class TaskExecutionViewModel(application: Application) : AndroidViewModel(application) {
    
    // Session and configuration state
    private val _sessionState = MutableLiveData<SessionState>()
    val sessionState: LiveData<SessionState> = _sessionState
    
    private val _runtimeConfig = MutableLiveData<RuntimeConfig>()
    val runtimeConfig: LiveData<RuntimeConfig> = _runtimeConfig
    
    // Current task execution state
    private val _taskExecutionState = MutableLiveData<TaskExecutionState?>()
    val taskExecutionState: LiveData<TaskExecutionState?> = _taskExecutionState
    
    // UI state for between-tasks screen
    private val _uiState = MutableLiveData<TaskExecutionUIState>()
    val uiState: LiveData<TaskExecutionUIState> = _uiState
    
    // Navigation events
    private val _navigationEvent = MutableLiveData<TaskExecutionNavigationEvent>()
    val navigationEvent: LiveData<TaskExecutionNavigationEvent> = _navigationEvent
    
    // Dialog events
    private val _dialogEvent = MutableLiveData<DialogEvent>()
    val dialogEvent: LiveData<DialogEvent> = _dialogEvent
    
    // Stroop generator for task execution
    private var stroopGenerator: StroopGenerator? = null
    
    // Background jobs
    private var taskTimeoutJob: Job? = null
    
    /**
     * Initialize with session and runtime configuration
     */
    fun initialize(sessionState: SessionState, runtimeConfig: RuntimeConfig) {
        _sessionState.value = sessionState
        _runtimeConfig.value = runtimeConfig
        stroopGenerator = StroopGenerator(runtimeConfig)
        
        updateUIState()
    }
    
    /**
     * Start the current task
     */
    fun startCurrentTask() {
        val session = _sessionState.value
        val config = _runtimeConfig.value
        
        if (session == null || config == null) {
            _dialogEvent.value = DialogEvent.ShowError("Session not initialized")
            return
        }
        
        val currentTaskId = session.getCurrentTaskId()
        if (currentTaskId == null) {
            _dialogEvent.value = DialogEvent.ShowError("No current task available")
            return
        }
        
        val taskConfig = config.baseConfig.tasks[currentTaskId]
        if (taskConfig == null) {
            _dialogEvent.value = DialogEvent.ShowError("Task configuration not found: $currentTaskId")
            return
        }
        
        // Create task execution state
        val taskExecution = TaskExecutionState(
            taskId = currentTaskId,
            timeoutDuration = taskConfig.timeoutMillis()
        ).start()
        
        _taskExecutionState.value = taskExecution
        
        // Start timeout monitoring
        startTaskTimeoutMonitoring(taskExecution)
        
        // Navigate to Stroop display
        _navigationEvent.value = TaskExecutionNavigationEvent.NavigateToStroopDisplay(
            taskExecution = taskExecution,
            runtimeConfig = config,
            stroopGenerator = stroopGenerator!!
        )
        
        updateUIState()
    }
    
    /**
     * Handle task completion (called when returning from Stroop display)
     */
    fun onTaskCompleted(completedTaskExecution: TaskExecutionState) {
        // Cancel timeout monitoring
        taskTimeoutJob?.cancel()
        
        // Update task execution state
        val finalTaskExecution = if (!completedTaskExecution.isCompleted) {
            completedTaskExecution.complete()
        } else {
            completedTaskExecution
        }
        
        _taskExecutionState.value = finalTaskExecution
        
        // Mark task as completed in session
        val currentSession = _sessionState.value
        if (currentSession != null) {
            val updatedSession = currentSession.completeCurrentTask()
            _sessionState.value = updatedSession
        }
        
        updateUIState()
    }
    
    /**
     * Move to next task in sequence
     */
    fun moveToNextTask() {
        val currentSession = _sessionState.value
        if (currentSession == null) return
        
        if (currentSession.hasNextTask()) {
            val updatedSession = currentSession.moveToNextTask()
            _sessionState.value = updatedSession
            _taskExecutionState.value = null
            updateUIState()
        } else {
            // Session is complete
            updateUIState()
        }
    }
    
    /**
     * Restart current task
     */
    fun restartCurrentTask() {
        val currentSession = _sessionState.value
        if (currentSession != null) {
            val updatedSession = currentSession.restartCurrentTask()
            _sessionState.value = updatedSession
            _taskExecutionState.value = null
            updateUIState()
        }
    }
    
    /**
     * Show cancel session confirmation dialog
     */
    fun requestCancelSession() {
        _dialogEvent.value = DialogEvent.ConfirmCancelSession
    }
    
    /**
     * Confirm session cancellation
     */
    fun confirmCancelSession() {
        // Cancel any running jobs
        taskTimeoutJob?.cancel()
        
        // Navigate back to task selection
        _navigationEvent.value = TaskExecutionNavigationEvent.NavigateToTaskSelection
    }
    
    /**
     * Restart entire session from beginning
     */
    fun restartEntireSession() {
        val currentSession = _sessionState.value
        if (currentSession != null) {
            val resetSession = currentSession.resetSession()
            _sessionState.value = resetSession
            _taskExecutionState.value = null
            updateUIState()
        }
    }
    
    /**
     * Return to main menu (task selection)
     */
    fun returnToMainMenu() {
        _navigationEvent.value = TaskExecutionNavigationEvent.NavigateToTaskSelection
    }
    
    /**
     * Update UI state based on current session and task state
     */
    private fun updateUIState() {
        val session = _sessionState.value
        val taskExecution = _taskExecutionState.value
        
        if (session == null) {
            _uiState.value = TaskExecutionUIState.Error("Session not initialized")
            return
        }
        
        val uiState = when {
            session.isSessionComplete -> {
                TaskExecutionUIState.SessionComplete(
                    progressText = session.getProgressDisplay(),
                    completedTasksCount = session.completedTasks.size,
                    totalTasksCount = session.getTotalTasks()
                )
            }
            
            taskExecution?.isCompleted == true -> {
                TaskExecutionUIState.TaskCompleted(
                    progressText = session.getProgressDisplay(),
                    taskId = taskExecution.taskId,
                    stimulusCount = taskExecution.getStimulusCount(),
                    duration = taskExecution.getElapsedTime(),
                    hasNextTask = session.hasNextTask()
                )
            }
            
            taskExecution?.isActive == true -> {
                TaskExecutionUIState.TaskInProgress(
                    progressText = session.getProgressDisplay(),
                    taskId = taskExecution.taskId,
                    remainingTime = taskExecution.getRemainingTime()
                )
            }
            
            else -> {
                val currentTaskId = session.getCurrentTaskId()
                if (currentTaskId != null) {
                    TaskExecutionUIState.ReadyToStart(
                        progressText = session.getProgressDisplay(),
                        taskId = currentTaskId
                    )
                } else {
                    TaskExecutionUIState.Error("No current task available")
                }
            }
        }
        
        _uiState.value = uiState
    }
    
    /**
     * Start monitoring task timeout
     */
    private fun startTaskTimeoutMonitoring(taskExecution: TaskExecutionState) {
        taskTimeoutJob?.cancel()
        
        taskTimeoutJob = viewModelScope.launch {
            delay(taskExecution.timeoutDuration)
            
            // Check if task is still active
            val currentExecution = _taskExecutionState.value
            if (currentExecution?.isActive == true && currentExecution.taskId == taskExecution.taskId) {
                // Task timed out
                onTaskTimeout(currentExecution)
            }
        }
    }
    
    /**
     * Handle task timeout
     */
    private fun onTaskTimeout(taskExecution: TaskExecutionState) {
        val timedOutExecution = taskExecution.complete()
        onTaskCompleted(timedOutExecution)
    }
    
    /**
     * Get current task configuration
     */
    fun getCurrentTaskConfig(): TaskConfig? {
        val session = _sessionState.value
        val config = _runtimeConfig.value
        val currentTaskId = session?.getCurrentTaskId()
        
        return if (currentTaskId != null && config != null) {
            config.baseConfig.tasks[currentTaskId]
        } else {
            null
        }
    }
    
    /**
     * Clear navigation event after handling
     */
    fun clearNavigationEvent() {

    }
    
    /**
     * Clear dialog event after handling
     */
    fun clearDialogEvent() {

    }
    
    /**
     * Handle activity pause/resume for proper lifecycle management
     */
    fun onPause() {
        // If task is active, we might want to pause/save state
        // For research purposes, we'll let it continue running
    }
    
    fun onResume() {
        // Refresh UI state when returning to activity
        updateUIState()
    }
    
    override fun onCleared() {
        super.onCleared()
        // Cancel any running jobs
        taskTimeoutJob?.cancel()
    }
}

/**
 * UI state for task execution screen
 */
sealed class TaskExecutionUIState {
    data class ReadyToStart(
        val progressText: String,
        val taskId: String
    ) : TaskExecutionUIState()
    
    data class TaskInProgress(
        val progressText: String,
        val taskId: String,
        val remainingTime: Long
    ) : TaskExecutionUIState()
    
    data class TaskCompleted(
        val progressText: String,
        val taskId: String,
        val stimulusCount: Int,
        val duration: Long,
        val hasNextTask: Boolean
    ) : TaskExecutionUIState()
    
    data class SessionComplete(
        val progressText: String,
        val completedTasksCount: Int,
        val totalTasksCount: Int
    ) : TaskExecutionUIState()
    
    data class Error(val message: String) : TaskExecutionUIState()
}

/**
 * Navigation events for task execution
 */
sealed class TaskExecutionNavigationEvent {
    data class NavigateToStroopDisplay(
        val taskExecution: TaskExecutionState,
        val runtimeConfig: RuntimeConfig,
        val stroopGenerator: StroopGenerator
    ) : TaskExecutionNavigationEvent()
    
    object NavigateToTaskSelection : TaskExecutionNavigationEvent()
}

/**
 * Dialog events
 */
sealed class DialogEvent {
    object ConfirmCancelSession : DialogEvent()
    data class ShowError(val message: String) : DialogEvent()
}