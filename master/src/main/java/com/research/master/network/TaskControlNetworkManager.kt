package com.research.master.network

import com.research.shared.network.*
import com.research.shared.models.RuntimeConfig
import com.research.master.utils.DebugLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Handles task control message sending and response handling
 * FIXED VERSION - Now sends real network messages instead of placeholders
 * SOLUTION 1: Removed TaskTimeoutMessage handling to let ColorDisplayActivity handle it directly
 */
class TaskControlNetworkManager(
    private val networkClient: MasterNetworkClient
) {

    // Task state management
    private val _currentTaskState = MutableStateFlow<TaskState?>(null)
    val currentTaskState: StateFlow<TaskState?> = _currentTaskState.asStateFlow()

    private val _lastTaskError = MutableStateFlow<String?>(null)
    val lastTaskError: StateFlow<String?> = _lastTaskError.asStateFlow()

    /**
     * Start a task on the Projector
     * FR-TM-001: Manual task start/stop
     * FIXED: Now sends actual StartTaskMessage
     */
    suspend fun startTask(
        taskId: String,
        taskLabel: String,
        taskTimeoutMs: Long,
        sessionId: String,
        runtimeConfig: RuntimeConfig? = null
    ): Boolean {
        return try {
            DebugLogger.d("TaskControl", "Starting task: $taskId with timeout: ${taskTimeoutMs}ms")

            // Create timing parameters from config or defaults
            val timing = runtimeConfig?.getEffectiveTiming() ?: run {
                DebugLogger.w("TaskControl", "No runtime config provided, using defaults")
                com.research.shared.models.TimingConfig(
                    stroopDisplayDuration = 2000,
                    minInterval = 1000,
                    maxInterval = 3000,
                    countdownDuration = 4000
                )
            }

            // Create and send StartTaskMessage
            val startMessage = StartTaskMessage(
                sessionId = sessionId,
                taskId = taskId,
                taskLabel = taskLabel,
                timeoutSeconds = (taskTimeoutMs / 1000).toInt(),
                stroopSettings = StroopSettings(
                    displayDurationMs = timing.stroopDisplayDuration.toLong(),
                    minIntervalMs = timing.minInterval.toLong(),
                    maxIntervalMs = timing.maxInterval.toLong(),
                    countdownDurationMs = timing.countdownDuration * 1000L,
                    colors = runtimeConfig?.baseConfig?.getColorWords() ?: listOf("rot", "blau", "grün", "gelb", "schwarz", "orange", "lila"),
                    language = "de-DE"
                )
            )

            networkClient.sendMessage(startMessage)
            _currentTaskState.value = TaskState.Starting(taskId, taskLabel)

            DebugLogger.d("TaskControl", "START_TASK message sent successfully")
            true

        } catch (e: Exception) {
            DebugLogger.e("TaskControl", "Error starting task", e)
            _lastTaskError.value = "Error starting task: ${e.message}"
            _currentTaskState.value = TaskState.Error("START_FAILED", e.message ?: "Unknown error")
            false
        }
    }

    /**
     * End the current task with a specific condition
     * FR-TM-005: Manual end condition setting
     * FIXED: Now sends actual EndTaskMessage
     */
    suspend fun endTask(
        taskId: String,
        endCondition: String,
        sessionId: String
    ): Boolean {
        return try {
            DebugLogger.d("TaskControl", "Ending task: $taskId with condition: $endCondition")

            // Map end condition to EndReason
            val endReason = when (endCondition.lowercase()) {
                "success" -> EndReason.COMPLETED
                "failed" -> EndReason.CANCELLED
                "partial success" -> EndReason.CANCELLED
                "timeout" -> EndReason.TIMEOUT
                else -> EndReason.CANCELLED
            }

            // Create and send EndTaskMessage
            val endMessage = EndTaskMessage(
                sessionId = sessionId,
                taskId = taskId,
                reason = endReason
            )

            networkClient.sendMessage(endMessage)
            _currentTaskState.value = TaskState.Completed(taskId, endCondition, System.currentTimeMillis())

            DebugLogger.d("TaskControl", "END_TASK message sent successfully")
            true

        } catch (e: Exception) {
            DebugLogger.e("TaskControl", "Error ending task", e)
            _lastTaskError.value = "Error ending task: ${e.message}"
            false
        }
    }

    /**
     * Reset/cancel the current task
     * FIXED: Now sends actual TaskResetCommand
     */
    suspend fun resetTask(taskId: String, sessionId: String): Boolean {
        return try {
            DebugLogger.d("TaskControl", "Resetting task: $taskId")

            val resetMessage = TaskResetCommand(sessionId = sessionId)
            networkClient.sendMessage(resetMessage)
            _currentTaskState.value = TaskState.Ready

            DebugLogger.d("TaskControl", "RESET_TASK message sent successfully")
            true

        } catch (e: Exception) {
            DebugLogger.e("TaskControl", "Error resetting task", e)
            _lastTaskError.value = "Error resetting task: ${e.message}"
            false
        }
    }

    /**
     * Pause the current task
     * TODO: Implement when PauseTaskMessage is added to shared module
     */
    suspend fun pauseTask(taskId: String, sessionId: String): Boolean {
        DebugLogger.w("TaskControl", "Pause task not yet implemented - PauseTaskMessage needed in shared module")
        return false
    }

    /**
     * Resume a paused task
     * TODO: Implement when ResumeTaskMessage is added to shared module
     */
    suspend fun resumeTask(taskId: String, sessionId: String): Boolean {
        DebugLogger.w("TaskControl", "Resume task not yet implemented - ResumeTaskMessage needed in shared module")
        return false
    }

    /**
     * Start voice recognition calibration
     * FR-TM-021: Voice Recognition System Check
     * TODO: Implement when VoiceCalibrationMessage is added to shared module
     */
    suspend fun startVoiceCalibration(sessionId: String): Boolean {
        DebugLogger.w("TaskControl", "Voice calibration not yet implemented - VoiceCalibrationMessage needed")
        return false
    }

    /**
     * Handle incoming task-related messages from Projector
     * SOLUTION 1 FIX: Removed TaskTimeoutMessage handling - ColorDisplayActivity handles it directly
     */
    fun handleTaskMessage(message: NetworkMessage) {
        DebugLogger.d("TaskControl", "TaskControlManager handling message: ${message.messageType}")

        when (message) {
            is TaskStatusMessage -> {
                handleTaskStatusMessage(message)
            }

            // REMOVED: TaskTimeoutMessage handling - ColorDisplayActivity handles this directly
            // This prevents the message from being consumed before the activity can process it
            // is TaskTimeoutMessage -> {
            //     handleTaskTimeoutMessage(message)
            // }

            is StroopResultsMessage -> {
                handleStroopResultsMessage(message)
            }

            is ErrorMessage -> {
                handleErrorMessage(message)
            }

            // Stroop monitoring messages - update task state
            is StroopStartedMessage -> {
                val currentState = _currentTaskState.value
                if (currentState is TaskState.Starting || currentState is TaskState.CountdownActive) {
                    _currentTaskState.value = TaskState.Active(message.taskId, System.currentTimeMillis())
                }
            }

            else -> {
                DebugLogger.d("TaskControl", "TaskControlManager - Unhandled message type: ${message.messageType}")
            }
        }
    }

    /**
     * Handle task status updates from Projector
     */
    private fun handleTaskStatusMessage(message: TaskStatusMessage) {
        DebugLogger.d("TaskControl", "Task ${message.taskId} status: ${message.status}")

        val newState = when (message.status) {
            TaskStatus.WAITING -> TaskState.Ready
            TaskStatus.COUNTDOWN -> TaskState.CountdownActive(message.taskId, System.currentTimeMillis())
            TaskStatus.ACTIVE -> TaskState.Active(message.taskId, System.currentTimeMillis())
            TaskStatus.COMPLETED -> TaskState.Completed(message.taskId, "completed", System.currentTimeMillis())
            TaskStatus.PAUSED -> TaskState.Paused(message.taskId, System.currentTimeMillis())
            TaskStatus.ERROR -> TaskState.Error("PROJECTOR_ERROR", "Task error on projector")
        }

        _currentTaskState.value = newState
    }

    /**
     * REMOVED: This method is no longer called since we removed TaskTimeoutMessage handling
     * The timeout is now handled directly by ColorDisplayActivity for better UI control
     */
    // private fun handleTaskTimeoutMessage(message: TaskTimeoutMessage) {
    //     DebugLogger.d("TaskControl", "Task ${message.taskId} timed out after ${message.actualDuration}ms, ${message.stroopsDisplayed} stroops shown")
    //     _currentTaskState.value = TaskState.Completed(message.taskId, "Timed Out", System.currentTimeMillis())
    // }

    /**
     * Handle Stroop results from Projector
     */
    private fun handleStroopResultsMessage(message: StroopResultsMessage) {
        DebugLogger.d("TaskControl", "Received Stroop results for task ${message.taskId}: ${message.totalStroops} stroops, ${message.correctResponses} correct")

        // TODO: Forward to SessionManager for data persistence
        // This will be implemented when SessionManager integration is complete
    }

    /**
     * Handle error messages from Projector
     */
    private fun handleErrorMessage(message: ErrorMessage) {
        DebugLogger.e("TaskControl", "Projector error: ${message.errorCode} - ${message.errorDescription}")
        _lastTaskError.value = "Projector error: ${message.errorDescription}"

        if (message.isFatal) {
            _currentTaskState.value = TaskState.Error(message.errorCode, message.errorDescription)
        }
    }

    /**
     * NEW: Manual timeout handling for when ColorDisplayActivity processes timeout
     * This allows the activity to handle UI updates while keeping state management here
     */
    fun notifyTaskTimeout(taskId: String, duration: Long, stroopsDisplayed: Int) {
        DebugLogger.d("TaskControl", "Notified of task timeout: $taskId after ${duration}ms, $stroopsDisplayed stroops")
        _currentTaskState.value = TaskState.Completed(taskId, "Timed Out", System.currentTimeMillis())
    }

    /**
     * Clear any error state
     */
    fun clearError() {
        _lastTaskError.value = null
    }

    /**
     * Get current task state for debugging
     */
    fun getCurrentTaskState(): TaskState? = _currentTaskState.value
}

/**
 * Task state representation for UI
 * ENHANCED: Added more detailed state information
 * FIXED: Resolved JVM signature conflict by renaming method
 */
sealed class TaskState {
    object Ready : TaskState()
    data class Starting(val taskId: String, val taskLabel: String) : TaskState()
    data class CountdownActive(val taskId: String, val startTime: Long) : TaskState()
    data class Active(val taskId: String, val startTime: Long) : TaskState()
    data class Paused(val taskId: String, val pauseTime: Long) : TaskState()
    data class Completed(val taskId: String, val endCondition: String, val endTime: Long) : TaskState()
    data class Error(val errorType: String, val errorMessage: String) : TaskState()

    /**
     * Check if task is currently running
     */
    fun isActive(): Boolean = this is Active || this is CountdownActive

    /**
     * Get task ID if available
     * FIXED: Renamed from getTaskId() to avoid JVM signature conflict with data class auto-generated getters
     */
    fun getTaskIdOrNull(): String? = when (this) {
        is Starting -> taskId
        is CountdownActive -> taskId
        is Active -> taskId
        is Paused -> taskId
        is Completed -> taskId
        else -> null
    }
}