package com.research.shared.network

import kotlinx.serialization.Serializable

/**
 * Network messages for task control between Master and Projector apps
 * These extend the existing NetworkMessage system
 */

/**
 * Master -> Projector: Start a task with specific parameters
 * Triggers countdown and Stroop test sequence
 */
@Serializable
data class StartTaskMessage(
    val taskId: String,
    val taskLabel: String,
    val taskTimeoutMs: Long,
    val stroopDisplayDurationMs: Long,
    val stroopMinIntervalMs: Long,
    val stroopMaxIntervalMs: Long,
    val sessionId: String
) : NetworkMessage

/**
 * Master -> Projector: Pause the current task
 * Stops Stroop generation but maintains task state
 */
@Serializable
data class PauseTaskMessage(
    val taskId: String,
    val sessionId: String
) : NetworkMessage

/**
 * Master -> Projector: Resume a paused task
 * Continues Stroop generation from where it left off
 */
@Serializable
data class ResumeTaskMessage(
    val taskId: String,
    val sessionId: String
) : NetworkMessage

/**
 * Master -> Projector: Reset/cancel the current task
 * Stops all activity and returns to ready state
 */
@Serializable
data class ResetTaskMessage(
    val taskId: String,
    val sessionId: String
) : NetworkMessage

/**
 * Master -> Projector: End the current task
 * Triggers final data collection and cleanup
 */
@Serializable
data class EndTaskMessage(
    val taskId: String,
    val endCondition: String, // "Success", "Failed", "Partial Success", "Timed Out"
    val sessionId: String
) : NetworkMessage

/**
 * Master -> Projector: Initiate voice recognition calibration
 * Triggers FR-ST-011 calibration process
 */
@Serializable
data class StartVoiceCalibrationMessage(
    val sessionId: String
) : NetworkMessage

/**
 * Projector -> Master: Task has been started (countdown began)
 * Confirms that task initiation was successful
 */
@Serializable
data class TaskStartedMessage(
    val taskId: String,
    val countdownStartTime: Long,
    val sessionId: String
) : NetworkMessage

/**
 * Projector -> Master: Countdown completed, Stroop sequence active
 * Task timer should start counting on Master side
 */
@Serializable
data class TaskActiveMessage(
    val taskId: String,
    val taskStartTime: Long, // When countdown ended and task actually began
    val sessionId: String
) : NetworkMessage

/**
 * Projector -> Master: Task has been paused
 * Confirms pause state
 */
@Serializable
data class TaskPausedMessage(
    val taskId: String,
    val pauseTime: Long,
    val sessionId: String
) : NetworkMessage

/**
 * Projector -> Master: Task has been resumed
 * Confirms resume state
 */
@Serializable
data class TaskResumedMessage(
    val taskId: String,
    val resumeTime: Long,
    val sessionId: String
) : NetworkMessage

/**
 * Projector -> Master: Task completed (by timeout or manual end)
 * Includes complete Stroop test results
 */
@Serializable
data class TaskCompletedMessage(
    val taskId: String,
    val endCondition: String,
    val taskDurationMs: Long,
    val stroopResults: StroopTestResults,
    val taskEndTime: Long,
    val sessionId: String
) : NetworkMessage

/**
 * Projector -> Master: Voice calibration completed
 * Reports calibration success/failure
 */
@Serializable
data class VoiceCalibrationCompletedMessage(
    val success: Boolean,
    val message: String, // Success/error message
    val calibrationData: Map<String, Double>?, // Optional calibration metrics
    val sessionId: String
) : NetworkMessage

/**
 * Complete Stroop test results from Projector
 * FR-DCT-001: Required data transmission
 */
@Serializable
data class StroopTestResults(
    val totalStroopsShown: Int,
    val correctResponses: Int,
    val incorrectResponses: Int,
    val averageReactionTimeMs: Double,
    val individualReactionTimesMs: List<Double>,
    val errorRateCorrect: Double, // Percentage
    val errorRateIncorrect: Double, // Percentage
    val stroopDetails: List<StroopResult> // Individual Stroop data
)

/**
 * Individual Stroop test result
 * For detailed analysis and debugging
 */
@Serializable
data class StroopResult(
    val stroopId: String, // Unique ID for this stroop
    val wordDisplayed: String, // Text word shown (e.g., "RED")
    val colorDisplayed: String, // Hex color code (e.g., "#0000FF")
    val correctAnswer: String, // What participant should say (e.g., "blue")
    val participantResponse: String?, // What was actually said (null if no response)
    val isCorrect: Boolean, // Whether response was correct
    val reactionTimeMs: Double, // Time from display to speech start
    val displayStartTime: Long, // Timestamp when Stroop appeared
    val responseStartTime: Long?, // Timestamp when speech started (null if no response)
    val displayDurationMs: Long, // How long Stroop was shown
    val recognitionConfidence: Double? // Voice recognition confidence (0.0-1.0)
)

/**
 * Error message for task-related failures
 * Sent from Projector to Master when something goes wrong
 */
@Serializable
data class TaskErrorMessage(
    val taskId: String?,
    val errorType: String, // "TASK_START_FAILED", "VOICE_ERROR", "CONFIG_ERROR", etc.
    val errorMessage: String,
    val errorDetails: Map<String, String>? = null,
    val sessionId: String
) : NetworkMessage

/**
 * Status update message
 * General purpose status updates during task execution
 */
@Serializable
data class TaskStatusMessage(
    val taskId: String,
    val status: String, // "COUNTDOWN_STARTED", "STROOP_SEQUENCE_ACTIVE", etc.
    val statusData: Map<String, String>? = null,
    val timestamp: Long,
    val sessionId: String
) : NetworkMessage