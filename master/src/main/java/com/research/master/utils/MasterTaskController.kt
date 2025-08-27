package com.research.master.utils

import android.content.Context
import com.research.master.network.NetworkManager
import com.research.shared.models.RuntimeConfig
import com.research.shared.network.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Manages task execution control for the Master app.
 * Sends task commands to Projector and tracks task state.
 */
object MasterTaskController {

    private const val TAG = "MasterTaskController"

    private val _currentTask = MutableStateFlow<ActiveTask?>(null)
    val currentTask: StateFlow<ActiveTask?> = _currentTask.asStateFlow()

    private val _taskState = MutableStateFlow<TaskControlState>(TaskControlState.Idle)
    val taskState: StateFlow<TaskControlState> = _taskState.asStateFlow()

    /**
     * Current active task information
     */
    data class ActiveTask(
        val taskId: String,
        val timeoutSeconds: Int,
        val startTime: Long,
        val stroopTiming: StroopTimingParams
    ) {
        fun getElapsedSeconds(): Long = (System.currentTimeMillis() - startTime) / 1000
        fun getRemainingSeconds(): Long = maxOf(0, timeoutSeconds - getElapsedSeconds())
        fun hasTimedOut(): Boolean = getRemainingSeconds() <= 0
    }

    /**
     * Task control states
     */
    sealed class TaskControlState {
        object Idle : TaskControlState()
        object Starting : TaskControlState()
        data class Running(val task: ActiveTask) : TaskControlState()
        object Stopping : TaskControlState()
        data class Error(val message: String) : TaskControlState()
    }

    /**
     * Start a new Stroop task on the connected Projector
     */
    suspend fun startTask(
        context: Context,
        timeoutSeconds: Int,
        stroopTiming: StroopTimingParams? = null
    ): Boolean {
        if (!NetworkManager.isConnected()) {
            DebugLogger.e(TAG, "Cannot start task: Not connected to Projector")
            _taskState.value = TaskControlState.Error("Not connected to Projector")
            return false
        }

        if (_currentTask.value != null) {
            DebugLogger.w(TAG, "Task already running, stopping current task first")
            stopCurrentTask(context)
        }

        val config = MasterConfigManager.getCurrentConfig()
        if (config == null) {
            DebugLogger.e(TAG, "Cannot start task: No configuration loaded")
            _taskState.value = TaskControlState.Error("No configuration loaded")
            return false
        }

        return try {
            _taskState.value = TaskControlState.Starting

            // Generate unique task ID
            val taskId = "task_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"

            // Use provided timing or get from config
            val timing = stroopTiming ?: StroopTimingParams(
                stroopDisplayDuration = config.stroopDisplayDuration,
                minInterval = config.minInterval,
                maxInterval = config.maxInterval,
                countdownDuration = config.countdownDuration
            )

            // Create task start command
            val sessionId = NetworkManager.getCurrentSessionId() ?: run {
                DebugLogger.e(TAG, "No active session ID")
                _taskState.value = TaskControlState.Error("No active session")
                return false
            }

            val startCommand = TaskStartCommand(
                sessionId = sessionId,
                taskId = taskId,
                timeoutSeconds = timeoutSeconds,
                stroopTiming = timing
            )

            // Send command to Projector
            val networkClient = NetworkManager.getNetworkClient(context)
            networkClient.sendMessage(startCommand)

            // Track the active task
            val activeTask = ActiveTask(
                taskId = taskId,
                timeoutSeconds = timeoutSeconds,
                startTime = System.currentTimeMillis(),
                stroopTiming = timing
            )

            _currentTask.value = activeTask
            _taskState.value = TaskControlState.Running(activeTask)

            DebugLogger.d(TAG, "Task started: $taskId, timeout: ${timeoutSeconds}s")
            true

        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to start task", e)
            _taskState.value = TaskControlState.Error("Failed to start task: ${e.message}")
            false
        }
    }

    /**
     * Stop the currently running task
     */
    suspend fun stopCurrentTask(context: Context, reason: String = "manual_stop"): Boolean {
        val currentTask = _currentTask.value ?: run {
            DebugLogger.w(TAG, "No task running to stop")
            return true
        }

        if (!NetworkManager.isConnected()) {
            DebugLogger.e(TAG, "Cannot stop task: Not connected to Projector")
            return false
        }

        return try {
            _taskState.value = TaskControlState.Stopping

            val sessionId = NetworkManager.getCurrentSessionId() ?: run {
                DebugLogger.e(TAG, "No active session ID")
                return false
            }

            val endCommand = TaskEndCommand(  // Changed from TaskStopCommand
                sessionId = sessionId,
                taskId = currentTask.taskId,
                reason = reason
            )

            val networkClient = NetworkManager.getNetworkClient(context)
            networkClient.sendMessage(endCommand)  // Changed variable name

            // Clear current task
            _currentTask.value = null
            _taskState.value = TaskControlState.Idle

            DebugLogger.d(TAG, "Task stopped: ${currentTask.taskId}, reason: $reason")
            true

        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to stop task", e)
            _taskState.value = TaskControlState.Error("Failed to stop task: ${e.message}")
            false
        }
    }

    /**
     * Reset/clear the Projector display
     */
    suspend fun resetProjector(context: Context): Boolean {
        if (!NetworkManager.isConnected()) {
            DebugLogger.e(TAG, "Cannot reset: Not connected to Projector")
            return false
        }

        return try {
            val sessionId = NetworkManager.getCurrentSessionId() ?: run {
                DebugLogger.e(TAG, "No active session ID")
                return false
            }

            val resetCommand = TaskResetCommand(sessionId = sessionId)

            val networkClient = NetworkManager.getNetworkClient(context)
            networkClient.sendMessage(resetCommand)

            // Clear any current task
            _currentTask.value = null
            _taskState.value = TaskControlState.Idle

            DebugLogger.d(TAG, "Projector reset command sent")
            true

        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to reset Projector", e)
            false
        }
    }

    /**
     * Handle task timeout message from Projector
     */
    fun handleTaskTimeout(timeoutMessage: TaskTimeoutMessage) {
        val currentTask = _currentTask.value
        if (currentTask?.taskId == timeoutMessage.taskId) {
            DebugLogger.d(TAG, "Task timed out: ${timeoutMessage.taskId}, " +
                    "duration: ${timeoutMessage.actualDuration}ms, " +
                    "stroops: ${timeoutMessage.stroopsDisplayed}")

            _currentTask.value = null
            _taskState.value = TaskControlState.Idle
        } else {
            DebugLogger.w(TAG, "Received timeout for unknown task: ${timeoutMessage.taskId}")
        }
    }

    /**
     * Get current task progress (0.0 to 1.0)
     */
    fun getCurrentTaskProgress(): Float {
        val task = _currentTask.value ?: return 0f
        val elapsed = task.getElapsedSeconds()
        return (elapsed.toFloat() / task.timeoutSeconds).coerceIn(0f, 1f)
    }

    /**
     * Check if a task is currently running
     */
    fun isTaskRunning(): Boolean = _currentTask.value != null

    /**
     * Get formatted remaining time string
     */
    fun getRemainingTimeString(): String {
        val task = _currentTask.value ?: return "No task running"
        val remaining = task.getRemainingSeconds()
        return "${remaining}s"
    }
}