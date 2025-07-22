package com.research.projector.models

import java.io.Serializable

/**
 * Represents the current state and progress of a task sequence session.
 * Manages task progression, completion tracking, and session state.
 */
data class SessionState(
    val taskSequenceId: String,                     // ID of selected task sequence
    val taskSequenceLabel: String,                  // Human-readable label
    val taskIds: List<String>,                      // List of task IDs in sequence
    val currentTaskIndex: Int = 0,                  // Current position in sequence (0-based)
    val completedTasks: Set<Int> = emptySet(),      // Indices of completed tasks
    val isSessionComplete: Boolean = false          // Whether all tasks finished
) : Serializable {
    
    /**
     * Get the current task ID, or null if session complete
     */
    fun getCurrentTaskId(): String? {
        return if (currentTaskIndex < taskIds.size) {
            taskIds[currentTaskIndex]
        } else {
            null
        }
    }
    
    /**
     * Get total number of tasks in sequence
     */
    fun getTotalTasks(): Int = taskIds.size
    
    /**
     * Get current task number (1-based for display)
     */
    fun getCurrentTaskNumber(): Int = currentTaskIndex + 1
    
    /**
     * Check if there are more tasks after current one
     */
    fun hasNextTask(): Boolean = currentTaskIndex < taskIds.size - 1
    
    /**
     * Check if current task is completed
     */
    fun isCurrentTaskCompleted(): Boolean = completedTasks.contains(currentTaskIndex)
    
    /**
     * Move to next task in sequence
     */
    fun moveToNextTask(): SessionState {
        val nextIndex = currentTaskIndex + 1
        return copy(
            currentTaskIndex = nextIndex,
            isSessionComplete = nextIndex >= taskIds.size
        )
    }
    
    /**
     * Mark current task as completed
     */
    fun completeCurrentTask(): SessionState {
        val updatedCompleted = completedTasks + currentTaskIndex
        return copy(
            completedTasks = updatedCompleted,
            isSessionComplete = updatedCompleted.size >= taskIds.size
        )
    }
    
    /**
     * Restart current task (remove from completed set)
     */
    fun restartCurrentTask(): SessionState {
        return copy(
            completedTasks = completedTasks - currentTaskIndex
        )
    }
    
    /**
     * Reset entire session to beginning
     */
    fun resetSession(): SessionState {
        return copy(
            currentTaskIndex = 0,
            completedTasks = emptySet(),
            isSessionComplete = false
        )
    }
    
    /**
     * Get progress as percentage (0-100)
     */
    fun getProgressPercentage(): Int {
        return if (taskIds.isEmpty()) 100 else (completedTasks.size * 100) / taskIds.size
    }
    
    /**
     * Get a display string for progress (e.g., "Task 2 of 4")
     */
    fun getProgressDisplay(): String {
        return "Task ${getCurrentTaskNumber()} of ${getTotalTasks()}"
    }
}

/**
 * Represents the state of an individual task execution
 */
data class TaskExecutionState(
    val taskId: String,                             // Current task ID
    val timeoutDuration: Long,                      // Task timeout in milliseconds
    val startTime: Long = 0L,                       // When task started (timestamp)
    val endTime: Long = 0L,                         // When task ended (timestamp)
    val currentStimulus: TimedStroopStimulus? = null, // Currently displayed stimulus
    val displayedStimuli: List<TimedStroopStimulus> = emptyList(), // All stimuli shown in this task
    val displayState: StimulusDisplayState = StimulusDisplayState.WAITING,
    val countdownState: CountdownState? = null,     // Current countdown state
    val isActive: Boolean = false,                  // Whether task is currently running
    val isCompleted: Boolean = false                // Whether task finished
) : Serializable {
    
    /**
     * Get elapsed time since task started
     */
    fun getElapsedTime(): Long {
        return if (startTime > 0) {
            val endTimeToUse = if (endTime > 0) endTime else System.currentTimeMillis()
            endTimeToUse - startTime
        } else {
            0L
        }
    }
    
    /**
     * Get remaining time before timeout
     */
    fun getRemainingTime(): Long {
        val elapsed = getElapsedTime()
        return maxOf(0L, timeoutDuration - elapsed)
    }
    
    /**
     * Check if task has timed out
     */
    fun hasTimedOut(): Boolean {
        return isActive && getRemainingTime() <= 0L
    }
    
    /**
     * Get total number of stimuli displayed
     */
    fun getStimulusCount(): Int = displayedStimuli.size
    
    /**
     * Start the task
     */
    fun start(currentTime: Long = System.currentTimeMillis()): TaskExecutionState {
        return copy(
            startTime = currentTime,
            isActive = true,
            displayState = StimulusDisplayState.COUNTDOWN
        )
    }
    
    /**
     * Complete the task
     */
    fun complete(currentTime: Long = System.currentTimeMillis()): TaskExecutionState {
        return copy(
            endTime = currentTime,
            isActive = false,
            isCompleted = true,
            displayState = StimulusDisplayState.COMPLETED,
            currentStimulus = null
        )
    }
    
    /**
     * Add a new stimulus to the displayed list
     */
    fun addStimulus(stimulus: TimedStroopStimulus): TaskExecutionState {
        return copy(
            currentStimulus = stimulus,
            displayedStimuli = displayedStimuli + stimulus,
            displayState = StimulusDisplayState.DISPLAY
        )
    }
    
    /**
     * Update current stimulus (e.g., when it completes)
     */
    fun updateCurrentStimulus(updatedStimulus: TimedStroopStimulus): TaskExecutionState {
        val updatedList = displayedStimuli.dropLast(1) + updatedStimulus
        return copy(
            currentStimulus = updatedStimulus,
            displayedStimuli = updatedList
        )
    }
    
    /**
     * Set display state
     */
    fun setDisplayState(newState: StimulusDisplayState): TaskExecutionState {
        return copy(displayState = newState)
    }
    
    /**
     * Update countdown state
     */
    fun updateCountdown(newCountdownState: CountdownState?): TaskExecutionState {
        return copy(countdownState = newCountdownState)
    }
    
    /**
     * Clear current stimulus (during intervals)
     */
    fun clearCurrentStimulus(): TaskExecutionState {
        return copy(
            currentStimulus = null,
            displayState = StimulusDisplayState.INTERVAL
        )
    }
    
    /**
     * Get task summary for logging/debugging
     */
    fun getSummary(): String {
        val duration = if (endTime > 0) getElapsedTime() else getRemainingTime()
        val status = when {
            isCompleted -> "Completed"
            hasTimedOut() -> "Timed Out"
            isActive -> "Active"
            else -> "Waiting"
        }
        
        return "Task $taskId: $status, ${getStimulusCount()} stimuli, ${duration}ms"
    }
}

/**
 * Complete application state combining session and task execution
 */
data class AppState(
    val sessionState: SessionState? = null,         // Current session state
    val taskExecutionState: TaskExecutionState? = null, // Current task execution
    val isConfigLoaded: Boolean = false,            // Whether config loaded successfully
    val runtimeConfig: RuntimeConfig? = null        // Current runtime configuration
) : Serializable {
    
    /**
     * Check if app is ready for task execution
     */
    fun isReadyForExecution(): Boolean {
        return isConfigLoaded && sessionState != null && runtimeConfig != null
    }
    
    /**
     * Check if a session is currently active
     */
    fun hasActiveSession(): Boolean {
        return sessionState != null && !sessionState.isSessionComplete
    }
    
    /**
     * Check if a task is currently running
     */
    fun hasActiveTask(): Boolean {
        return taskExecutionState?.isActive == true
    }
    
    /**
     * Get current task configuration
     */
    fun getCurrentTaskConfig(): TaskConfig? {
        val currentTaskId = sessionState?.getCurrentTaskId()
        return currentTaskId?.let { taskId ->
            runtimeConfig?.baseConfig?.tasks?.get(taskId)
        }
    }
    
    /**
     * Start a new session
     */
    fun startSession(newSessionState: SessionState): AppState {
        return copy(
            sessionState = newSessionState,
            taskExecutionState = null
        )
    }
    
    /**
     * Start task execution
     */
    fun startTaskExecution(taskExecution: TaskExecutionState): AppState {
        return copy(taskExecutionState = taskExecution)
    }
    
    /**
     * Update session state
     */
    fun updateSession(updatedSession: SessionState): AppState {
        return copy(sessionState = updatedSession)
    }
    
    /**
     * Update task execution state
     */
    fun updateTaskExecution(updatedExecution: TaskExecutionState): AppState {
        return copy(taskExecutionState = updatedExecution)
    }
    
    /**
     * Clear current task execution (return to between-tasks state)
     */
    fun clearTaskExecution(): AppState {
        return copy(taskExecutionState = null)
    }
    
    /**
     * Reset to initial state (keep config)
     */
    fun reset(): AppState {
        return copy(
            sessionState = null,
            taskExecutionState = null
        )
    }
}

/**
 * Enum representing different app navigation states
 */
enum class AppNavigationState {
    LOADING,                // Initial loading/config validation
    TASK_SELECTION,         // Selecting task sequence
    TASK_PREPARATION,       // Between tasks (ready to start)
    TASK_EXECUTION,         // Active Stroop display
    TASK_COMPLETED,         // Task finished, showing results
    SESSION_COMPLETED,      // All tasks finished
    SETTINGS,               // In settings screen
    ERROR                   // Configuration or other error
}

/**
 * Helper functions for state management
 */
object StateUtils {
    
    /**
     * Create initial session state from task list configuration
     */
    fun createSession(
        taskListId: String,
        taskListConfig: TaskListConfig
    ): SessionState {
        return SessionState(
            taskSequenceId = taskListId,
            taskSequenceLabel = taskListConfig.label,
            taskIds = taskListConfig.getTaskIds()
        )
    }
    
    /**
     * Create initial task execution state
     */
    fun createTaskExecution(
        taskId: String,
        taskConfig: TaskConfig
    ): TaskExecutionState {
        return TaskExecutionState(
            taskId = taskId,
            timeoutDuration = taskConfig.timeoutMillis()
        )
    }
    
    /**
     * Determine current navigation state based on app state
     */
    fun getNavigationState(appState: AppState): AppNavigationState {
        return when {
            !appState.isConfigLoaded -> AppNavigationState.LOADING
            appState.sessionState == null -> AppNavigationState.TASK_SELECTION
            appState.taskExecutionState?.isActive == true -> AppNavigationState.TASK_EXECUTION
            appState.taskExecutionState?.isCompleted == true -> AppNavigationState.TASK_COMPLETED
            appState.sessionState?.isSessionComplete == true -> AppNavigationState.SESSION_COMPLETED
            else -> AppNavigationState.TASK_PREPARATION
        }
    }
}