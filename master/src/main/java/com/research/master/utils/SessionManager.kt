package com.research.master.utils

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Manages research session data and JSON file creation
 * UPDATED: Now uses FileManager task iteration system with Int task numbers
 * Handles participant information, task data, and file persistence
 * According to requirements FR-SM-001, FR-SM-002, FR-SM-003, FR-DP-001, FR-DP-002, FR-DP-003
 */
object SessionManager {

    private val _currentSession = MutableStateFlow<SessionData?>(null)
    val currentSession: StateFlow<SessionData?> = _currentSession.asStateFlow()

    private lateinit var appContext: Context
    private lateinit var fileManager: FileManager
    private var currentSessionFile: File? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        fileManager = FileManager(appContext)
    }

    /**
     * Create new session with participant information
     * UPDATED: Now creates session file using FileManager
     * FR-SM-001: Record participant details
     * FR-SM-002: Record exact start time
     */
    suspend fun createSession(
        participantName: String,
        participantId: String,
        participantAge: Int,
        carModel: String
    ): String {
        val sessionId = generateSessionId()
        val startTime = System.currentTimeMillis()

        val sessionData = SessionData(
            sessionId = sessionId,
            participantName = participantName,
            participantId = participantId,
            participantAge = participantAge,
            carModel = carModel,
            interviewStartTime = startTime,
            interviewEndTime = null,
            tasks = mutableListOf(),
            sessionStatus = "in_progress"
        )

        _currentSession.value = sessionData

        // Create session file using FileManager
        val sessionExport = convertToSessionExport(sessionData)
        currentSessionFile = fileManager.createSessionFile(sessionId, sessionExport)

        DebugLogger.d("SessionManager", "Created session: $sessionId for participant: $participantName")
        return sessionId
    }

    /**
     * End the current session
     * UPDATED: Uses FileManager for session finalization
     * FR-SM-003: Record exact end time
     * Marks session as ended successfully
     */
    suspend fun endSession() {
        val session = _currentSession.value ?: return
        val sessionFile = currentSessionFile ?: return

        DebugLogger.d("SessionManager", "=== STARTING END SESSION ===")

        val updatedSession = session.copy(
            interviewEndTime = System.currentTimeMillis(),
            sessionStatus = "completed_normally",
            ended = 1
        )

        _currentSession.value = updatedSession
        val sessionExport = convertToSessionExport(updatedSession)

        // UPDATE: Add the missing finalization step
        try {
            // Get user's export folder from settings
            val exportFolderPath = fileManager.getExportFolder()
            val exportFolder = File(exportFolderPath)

            // Finalize session - this moves it to the user folder
            val finalFile = fileManager.finalizeSession(sessionFile, sessionExport, exportFolder)

            // Clear temp file reference since it's now been moved
            currentSessionFile = null

            DebugLogger.d("SessionManager", "Session finalized to: ${finalFile.absolutePath}")

        } catch (e: Exception) {
            DebugLogger.e("SessionManager", "Failed to finalize session to user folder", e)
            // Fallback: just update the temp file
            fileManager.updateSessionFile(sessionFile, sessionExport)
            throw e
        }

        DebugLogger.d("SessionManager", "=== END SESSION COMPLETE ===")
    }

    /**
     * Get the current session file for completion status checking
     * @return Current session file or null if no active session
     */
    fun getCurrentSessionFile(): File? {
        return currentSessionFile
    }

    /**
     * Clear the current session without saving
     * UPDATED: Uses FileManager for session cleanup
     * Used when discarding session data
     * Marks session as discarded and saves to file
     */
    suspend fun clearSession() {
        val session = _currentSession.value
        val sessionFile = currentSessionFile

        if (session != null && sessionFile != null) {
            DebugLogger.d("SessionManager", "=== STARTING CLEAR SESSION ===")
            DebugLogger.d("SessionManager", "Current session ID: ${session.sessionId}")

            try {
                // Mark session as discarded
                val discardedSession = session.copy(
                    discarded = 1,
                    sessionStatus = "discarded"
                )

                DebugLogger.d("SessionManager", "Updated current session in memory: discarded=${discardedSession.discarded}")

                // Save the updated session with discarded flag using FileManager
                val sessionExport = convertToSessionExport(discardedSession)
                fileManager.updateSessionFile(sessionFile, sessionExport)
                DebugLogger.d("SessionManager", "Saved current session to file")

                // Clear the current session from memory
                _currentSession.value = null
                currentSessionFile = null

                DebugLogger.d("SessionManager", "=== CLEAR SESSION COMPLETE ===")
                DebugLogger.d("SessionManager", "Session marked as discarded and saved")

            } catch (e: Exception) {
                DebugLogger.e("SessionManager", "Error marking session as discarded", e)
                // Still clear from memory even if saving fails
                _currentSession.value = null
                currentSessionFile = null
                throw e
            }
        } else {
            DebugLogger.d("SessionManager", "No session to clear")
        }
    }

    /**
     * Add task completion data to current session
     * UPDATED: Now uses FileManager task iteration system
     * FR-DP-002: Store task completion data
     */
    suspend fun addTaskData(taskData: TaskCompletionData) {
        val session = _currentSession.value ?: return
        val sessionFile = currentSessionFile ?: return

        DebugLogger.d("SessionManager", "=== ADDING TASK DATA ===")
        DebugLogger.d("SessionManager", "Task: ${taskData.taskNumber} (iteration ${taskData.iterationCounter})")

        // Add task to local session data
        val updatedTasks = session.tasks.toMutableList()
        updatedTasks.add(taskData)
        val updatedSession = session.copy(tasks = updatedTasks)
        _currentSession.value = updatedSession

        // Convert to TaskExport and add to FileManager
        val taskExport = convertTaskToExport(taskData)
        fileManager.addTaskIteration(sessionFile, taskExport)

        DebugLogger.d("SessionManager", "Added task data: ${taskData.taskNumber} to session: ${session.sessionId}")
    }

    /**
     * Update ASQ data for a specific task iteration
     * UPDATED: Now uses FileManager task iteration system with taskNumber and iterationCounter
     * FR-DP-002: Store ASQ responses with task data
     */
    suspend fun updateTaskASQData(taskNumber: Int, iterationCounter: Int, asqData: Map<String, String>) {
        val session = _currentSession.value ?: throw IllegalStateException("No current session")
        val sessionFile = currentSessionFile ?: throw IllegalStateException("No current session file")

        DebugLogger.d("SessionManager", "=== UPDATING TASK ASQ DATA ===")
        DebugLogger.d("SessionManager", "Task Number: $taskNumber, Iteration: $iterationCounter")
        DebugLogger.d("SessionManager", "ASQ Data: $asqData")

        // Update local session data
        val tasks = session.tasks.toMutableList()
        val taskIndex = tasks.indexOfLast {
            it.taskNumber == taskNumber && it.iterationCounter == iterationCounter
        }

        if (taskIndex == -1) {
            DebugLogger.e("SessionManager", "Task $taskNumber (iteration $iterationCounter) not found in session")
            throw IllegalArgumentException("Task $taskNumber (iteration $iterationCounter) not found in current session")
        }

        // Update the task with ASQ scores
        val updatedTask = tasks[taskIndex].copy(asqScores = asqData)
        tasks[taskIndex] = updatedTask

        // Update the session with modified task list
        val updatedSession = session.copy(tasks = tasks)
        _currentSession.value = updatedSession

        // Get current task export and update with ASQ data
        val currentTaskExport = fileManager.getLatestTaskIteration(sessionFile, taskNumber)
            ?: throw IllegalStateException("Task iteration not found in FileManager")

        val updatedTaskExport = currentTaskExport.copy(
            asq_responses = AsqResponses(
                asq_ease = asqData["ease"]?.toIntOrNull() ?: 0,
                asq_time = asqData["time"]?.toIntOrNull() ?: 0
            )
        )

        // Update the specific iteration using FileManager
        fileManager.updateTaskIteration(sessionFile, taskNumber, iterationCounter, updatedTaskExport)

        DebugLogger.d("SessionManager", "Task ${taskNumber} (iteration ${iterationCounter}) updated with ASQ data: $asqData")
        DebugLogger.d("SessionManager", "=== TASK ASQ UPDATE COMPLETE ===")
    }

    /**
     * Get the next iteration counter for a task
     * NEW: Uses FileManager to determine iteration count
     */
    suspend fun getNextIterationCounter(taskNumber: Int): Int {
        val sessionFile = currentSessionFile ?: return 0
        return fileManager.getNextIterationCounter(sessionFile, taskNumber)
    }

    /**
     * Get task iteration statistics
     * NEW: Uses FileManager for iteration analysis
     */
    suspend fun getTaskIterationStats(taskNumber: Int): TaskIterationStats? {
        val sessionFile = currentSessionFile ?: return null
        return fileManager.getTaskIterationStats(sessionFile, taskNumber)
    }

    /**
     * Check for incomplete sessions on startup using FileManager
     * UPDATED: Uses FileManager's session recovery system
     * FR-SR-002: Session recovery
     */
    suspend fun checkForIncompleteSession(): SessionData? {
        try {
            // Use FileManager to find recoverable sessions
            val recoverableSessions = fileManager.findRecoverableSessions()

            if (recoverableSessions.isEmpty()) {
                DebugLogger.d("SessionManager", "No recoverable sessions found")
                return null
            }

            // Get the most recent recoverable session
            val mostRecentFile = recoverableSessions.maxByOrNull { it.lastModified() }
            if (mostRecentFile != null) {
                val sessionExport = fileManager.readSessionData(mostRecentFile)

                // Check if session is actually incomplete
                if (fileManager.isSessionIncomplete(mostRecentFile)) {
                    currentSessionFile = mostRecentFile
                    val sessionData = convertFromSessionExport(sessionExport)
                    DebugLogger.d("SessionManager", "Found incomplete session: ${sessionData.sessionId}")
                    return sessionData
                }
            }

        } catch (e: Exception) {
            DebugLogger.e("SessionManager", "Error checking for incomplete session", e)
        }

        return null
    }

    /**
     * Resume incomplete session
     * UPDATED: Sets current session file
     */
    suspend fun resumeSession(sessionData: SessionData) {
        _currentSession.value = sessionData

        // Try to find the session file
        val recoverableSessions = fileManager.findRecoverableSessions()
        currentSessionFile = recoverableSessions.find { file ->
            try {
                val sessionExport = fileManager.readSessionData(file)
                sessionExport.session_info.participant_name == sessionData.participantName &&
                        sessionExport.session_info.participant_number == sessionData.participantId
            } catch (e: Exception) {
                false
            }
        }

        DebugLogger.d("SessionManager", "Resumed session: ${sessionData.sessionId}")
    }

    /**
     * Convert SessionData to SessionExport format for FileManager compatibility
     * UPDATED: Now includes iteration counter in task conversion
     */
    private fun convertToSessionExport(sessionData: SessionData): SessionExport {
        return SessionExport(
            session_info = SessionInfo(
                participant_name = sessionData.participantName,
                participant_number = sessionData.participantId,
                participant_age = sessionData.participantAge,
                car_model = sessionData.carModel,
                ended = sessionData.ended,
                discarded = sessionData.discarded,
                session_start_time = formatTimestamp(sessionData.interviewStartTime),
                session_end_time = sessionData.interviewEndTime?.let { formatTimestamp(it) }
            ),
            tasks = sessionData.tasks.map { convertTaskToExport(it) }
        )
    }

    /**
     * Convert TaskCompletionData to TaskExport
     * UPDATED: Now includes iteration counter and uses Int task numbers
     */
    private fun convertTaskToExport(taskData: TaskCompletionData): TaskExport {
        return TaskExport(
            task_number = taskData.taskNumber, // Now Int instead of String
            iteration_counter = taskData.iterationCounter, // NEW: iteration support
            task_start_time = formatTimestamp(taskData.taskEndTime - taskData.timeRequiredMs),
            task_metrics = TaskMetrics(
                task_label = taskData.taskLabel,
                successful_stroops = (taskData.stroopTotalCount * taskData.stroopErrorRateCorrect / 100).toInt(),
                mean_reaction_time_successful = if (taskData.stroopErrorRateCorrect > 0) taskData.stroopAverageReactionTime else null,
                incorrect_stroops = (taskData.stroopTotalCount * taskData.stroopErrorRateIncorrect / 100).toInt(),
                mean_reaction_time_incorrect = if (taskData.stroopErrorRateIncorrect > 0) taskData.stroopAverageReactionTime else null,
                mean_reaction_time_overall = if (taskData.stroopTotalCount > 0) taskData.stroopAverageReactionTime else null,
                missed_stroops = taskData.stroopTotalCount - (taskData.stroopErrorRateCorrect + taskData.stroopErrorRateIncorrect).toInt() / 100 * taskData.stroopTotalCount,
                time_on_task = taskData.timeRequiredMs,
                countdown_duration = 4000L
            ),
            asq_responses = AsqResponses(
                asq_ease = taskData.asqScores["ease"]?.toIntOrNull() ?: 0,
                asq_time = taskData.asqScores["time"]?.toIntOrNull() ?: 0
            ),
            end_condition = taskData.endCondition
        )
    }

    /**
     * Convert SessionExport back to SessionData format
     * UPDATED: Now handles iteration counter in task conversion
     */
    private fun convertFromSessionExport(sessionExport: SessionExport): SessionData {
        return SessionData(
            sessionId = generateSessionIdFromExport(sessionExport),
            participantName = sessionExport.session_info.participant_name,
            participantId = sessionExport.session_info.participant_number,
            participantAge = sessionExport.session_info.participant_age,
            carModel = sessionExport.session_info.car_model,
            interviewStartTime = parseTimestamp(sessionExport.session_info.session_start_time),
            interviewEndTime = sessionExport.session_info.session_end_time?.let { parseTimestamp(it) },
            tasks = sessionExport.tasks.map { taskExport ->
                TaskCompletionData(
                    taskNumber = taskExport.task_number, // Now Int instead of String
                    iterationCounter = taskExport.iteration_counter, // NEW: iteration support
                    taskLabel = taskExport.task_metrics.task_label,
                    timeRequiredMs = taskExport.task_metrics.time_on_task,
                    endCondition = taskExport.end_condition,
                    stroopErrorRateCorrect = calculatePercentage(taskExport.task_metrics.successful_stroops, getTotalStroops(taskExport.task_metrics)),
                    stroopErrorRateIncorrect = calculatePercentage(taskExport.task_metrics.incorrect_stroops, getTotalStroops(taskExport.task_metrics)),
                    stroopTotalCount = getTotalStroops(taskExport.task_metrics),
                    stroopAverageReactionTime = taskExport.task_metrics.mean_reaction_time_overall ?: 0.0,
                    stroopIndividualTimes = emptyList(),
                    asqScores = mapOf(
                        "ease" to taskExport.asq_responses.asq_ease.toString(),
                        "time" to taskExport.asq_responses.asq_time.toString()
                    ),
                    taskEndTime = parseTimestamp(sessionExport.session_info.session_end_time ?: sessionExport.session_info.session_start_time)
                )
            },
            sessionStatus = when {
                sessionExport.session_info.ended == 1 -> "completed_normally"
                sessionExport.session_info.discarded == 1 -> "discarded"
                else -> "in_progress"
            },
            ended = sessionExport.session_info.ended,
            discarded = sessionExport.session_info.discarded
        )
    }

    /**
     * Helper methods for data conversion
     */
    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date(timestamp))
    }

    private fun parseTimestamp(timestamp: String): Long {
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).parse(timestamp)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun generateSessionIdFromExport(sessionExport: SessionExport): String {
        return "session_${sessionExport.session_info.participant_name}_${sessionExport.session_info.participant_number}"
    }

    private fun getTotalStroops(metrics: TaskMetrics): Int {
        return metrics.successful_stroops + metrics.incorrect_stroops + metrics.missed_stroops
    }

    private fun calculatePercentage(count: Int, total: Int): Double {
        return if (total > 0) (count.toDouble() / total.toDouble()) * 100.0 else 0.0
    }

    /**
     * Generate unique session ID
     */
    private fun generateSessionId(): String {
        val timestamp = System.currentTimeMillis()
        val random = (1000..9999).random()
        return "session_${timestamp}_${random}"
    }
}

/**
 * Main session data structure
 * UPDATED: Tasks now use Int task numbers with iteration counters
 * FR-DP-002: Required session data fields
 */
@Serializable
data class SessionData(
    val sessionId: String,
    val participantName: String,
    val participantId: String,
    val participantAge: Int,
    val carModel: String, // "old" or "new"
    val interviewStartTime: Long,
    val interviewEndTime: Long?,
    val tasks: List<TaskCompletionData>,
    val sessionStatus: String, // "in_progress", "completed_normally", "interrupted", "discarded"
    val ended: Int = 0, // 0 = not ended, 1 = ended successfully
    val discarded: Int = 0 // 0 = not discarded, 1 = discarded
)

/**
 * Task completion data structure
 * UPDATED: Now uses Int taskNumber with iteration counter support
 * FR-DP-002: Required task data fields
 */
@Serializable
data class TaskCompletionData(
    val taskNumber: Int,                    // CHANGED: Now Int instead of String
    val iterationCounter: Int = 0,          // NEW: Support for multiple task attempts
    val taskLabel: String,
    val timeRequiredMs: Long,               // Time from countdown end to task end
    val endCondition: String,               // "Failed", "Success", "Partial Success", "Timed Out"
    val stroopErrorRateCorrect: Double,     // Percentage correct
    val stroopErrorRateIncorrect: Double,   // Percentage incorrect
    val stroopTotalCount: Int,
    val stroopAverageReactionTime: Double,  // milliseconds
    val stroopIndividualTimes: List<Double>, // milliseconds
    val asqScores: Map<String, String>,     // ASQ question ID to response ("0" if not answered)
    val taskEndTime: Long
)