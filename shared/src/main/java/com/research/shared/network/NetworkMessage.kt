package com.research.shared.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Base class for all network messages between Master and Projector apps
 */
@Serializable
sealed class NetworkMessage {
    abstract val messageType: MessageType
    abstract val sessionId: String
    abstract val timestamp: Long
}

/**
 * Message types for network communication
 */
@Serializable
enum class MessageType {
    // Connection messages
    HANDSHAKE,
    HANDSHAKE_RESPONSE,
    HEARTBEAT,
    DISCONNECT,

    // Configuration messages
    CONFIG_TRANSFER,
    CONFIG_ACKNOWLEDGMENT,

    // Task control messages
    START_TASK,
    END_TASK,
    PAUSE_TASK,
    RESUME_TASK,
    RESET_TASK,
    TASK_TIMEOUT,

    // Stroop control messages
    START_COUNTDOWN,
    START_STROOP_SEQUENCE,
    STOP_STROOP_SEQUENCE,
    STROOP_DISPLAY,
    STROOP_HIDDEN,

    // Data messages
    STROOP_RESULTS,
    TASK_STATUS,
    ERROR,

    // Settings messages
    UPDATE_SETTINGS,
    REQUEST_STATUS
}

// Connection Messages

@Serializable
data class HandshakeMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val masterDeviceId: String,
    val masterVersion: String
) : NetworkMessage() {
    override val messageType = MessageType.HANDSHAKE
}

@Serializable
data class HandshakeResponseMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val projectorDeviceId: String,
    val projectorVersion: String,
    val isReady: Boolean
) : NetworkMessage() {
    override val messageType = MessageType.HANDSHAKE_RESPONSE
}

@Serializable
data class HeartbeatMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : NetworkMessage() {
    override val messageType = MessageType.HEARTBEAT
}

// Configuration Messages

@Serializable
data class ConfigTransferMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val projectorConfig: ProjectorConfig
) : NetworkMessage() {
    override val messageType = MessageType.CONFIG_TRANSFER
}

@Serializable
data class ConfigAcknowledgmentMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val success: Boolean,
    val errorMessage: String? = null
) : NetworkMessage() {
    override val messageType = MessageType.CONFIG_ACKNOWLEDGMENT
}

// Configuration Data Classes

@Serializable
data class ProjectorConfig(
    val stroopColors: Map<String, String>,
    val timing: ProjectorTimingConfig,
    val networkConfig: ProjectorNetworkConfig
)

@Serializable
data class ProjectorTimingConfig(
    val stroopDisplayDuration: Int,
    val minInterval: Int,
    val maxInterval: Int,
    val countdownDuration: Int
)

@Serializable
data class ProjectorNetworkConfig(
    val teamId: String,
    val deviceId: String,
    val heartbeatInterval: Long
)

// Existing Task Control Messages (KEEP THESE - they're already working!)

@Serializable
data class StartTaskMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val taskLabel: String,
    val timeoutSeconds: Int,
    val stroopSettings: StroopSettings
) : NetworkMessage() {
    override val messageType = MessageType.START_TASK
}

@Serializable
data class EndTaskMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val reason: EndReason
) : NetworkMessage() {
    override val messageType = MessageType.END_TASK
}

@Serializable
enum class EndReason {
    COMPLETED,
    TIMEOUT,
    CANCELLED,
    ERROR
}

// NEW: Task Control and Monitoring Messages

@Serializable
data class TaskStartCommand(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val timeoutSeconds: Int,
    val stroopTiming: StroopTimingParams
) : NetworkMessage() {
    override val messageType = MessageType.START_TASK
}

@Serializable
data class TaskEndCommand(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val reason: String = "manual_stop"
) : NetworkMessage() {
    override val messageType = MessageType.END_TASK
}

@Serializable
data class TaskResetCommand(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : NetworkMessage() {
    override val messageType = MessageType.RESET_TASK
}

@Serializable
data class TaskTimeoutMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val actualDuration: Long,  // How long the task actually ran
    val stroopsDisplayed: Int  // How many Stroops were shown
) : NetworkMessage() {
    override val messageType = MessageType.TASK_TIMEOUT
}

// NEW: Stroop Monitoring Messages (Projector → Master)

@Serializable
data class StroopStartedMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val stroopIndex: Int,           // Which Stroop in the sequence (1, 2, 3...)
    val word: String,               // The word displayed (e.g., "rot")
    val displayColor: String,       // Hex color it's shown in (e.g., "#0000FF")
    val correctAnswer: String,      // What participant should say (e.g., "blau")
    val displayStartTime: Long = System.currentTimeMillis()
) : NetworkMessage() {
    override val messageType = MessageType.STROOP_DISPLAY
}

@Serializable
data class StroopEndedMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val stroopIndex: Int,
    val endReason: StroopEndReason,
    val displayDuration: Long       // How long it was actually shown
) : NetworkMessage() {
    override val messageType = MessageType.STROOP_HIDDEN
}

@Serializable
enum class StroopEndReason {
    TIMEOUT,           // Stroop disappeared after display duration
    MARKED_CORRECT,    // Master marked as correct
    MARKED_INCORRECT,  // Master marked as incorrect
    TASK_STOPPED       // Task was stopped manually
}

// NEW: Master Response Messages (Master → Projector)

@Serializable
data class StroopResponseCommand(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val stroopIndex: Int,
    val response: StroopResponse
) : NetworkMessage() {
    override val messageType = MessageType.UPDATE_SETTINGS  // Reuse existing type
}

@Serializable
enum class StroopResponse {
    CORRECT,
    INCORRECT
}

@Serializable
data class StroopTimingParams(
    val stroopDisplayDuration: Int,  // milliseconds
    val minInterval: Int,           // milliseconds
    val maxInterval: Int,           // milliseconds
    val countdownDuration: Int      // seconds
)

// Existing Stroop Settings (KEEP THIS - it's already working!)

@Serializable
data class StroopSettings(
    val displayDurationMs: Long = 2000,
    val minIntervalMs: Long = 1000,
    val maxIntervalMs: Long = 3000,
    val countdownDurationMs: Long = 4000,
    val colors: List<String> = listOf("rot", "blau", "grün", "gelb", "schwarz", "braun"),
    val colorMappings: Map<String, String> = emptyMap(), // NEW: name -> hex mappings
    val language: String = "de-DE"
)

// Data Messages

@Serializable
data class StroopResultsMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val totalStroops: Int,
    val correctResponses: Int,
    val incorrectResponses: Int,
    val averageReactionTimeMs: Long,
    val individualResults: List<StroopResult>
) : NetworkMessage() {
    override val messageType = MessageType.STROOP_RESULTS

    val errorRateCorrect: Float
        get() = if (totalStroops > 0) correctResponses.toFloat() / totalStroops else 0f

    val errorRateIncorrect: Float
        get() = if (totalStroops > 0) incorrectResponses.toFloat() / totalStroops else 0f
}

@Serializable
data class StroopResult(
    val index: Int,
    val displayedColor: String,
    val wordText: String,
    val responseTimeMs: Long,
    val isCorrect: Boolean,
    val spokenResponse: String? = null
)

@Serializable
data class TaskStatusMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val status: TaskStatus,
    val elapsedTimeMs: Long
) : NetworkMessage() {
    override val messageType = MessageType.TASK_STATUS
}

@Serializable
enum class TaskStatus {
    WAITING,
    COUNTDOWN,
    ACTIVE,
    COMPLETED,
    PAUSED,
    ERROR
}

@Serializable
data class PauseTaskMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String
) : NetworkMessage() {
    override val messageType = MessageType.PAUSE_TASK
}

@Serializable
data class ResumeTaskMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String
) : NetworkMessage() {
    override val messageType = MessageType.RESUME_TASK
}

@Serializable
data class ErrorMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val errorCode: String,
    val errorDescription: String,
    val isFatal: Boolean
) : NetworkMessage() {
    override val messageType = MessageType.ERROR
}

// Network utilities

object NetworkProtocol {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"  // Changed from "messageType" to "type"
    }

    /**
     * Serialize a message to JSON string
     */
    fun serialize(message: NetworkMessage): String {
        return json.encodeToString(NetworkMessage.serializer(), message)
    }

    /**
     * Deserialize a JSON string to NetworkMessage
     */
    fun deserialize(jsonString: String): NetworkMessage {
        return json.decodeFromString(NetworkMessage.serializer(), jsonString)
    }

    /**
     * Create a frame for sending over TCP (with length prefix)
     */
    fun createFrame(message: NetworkMessage): ByteArray {
        val jsonString = serialize(message)
        val jsonBytes = jsonString.toByteArray(Charsets.UTF_8)
        val lengthBytes = jsonBytes.size.toByteArray()

        return lengthBytes + jsonBytes
    }

    /**
     * Convert Int to 4-byte array (big-endian)
     */
    private fun Int.toByteArray(): ByteArray {
        return byteArrayOf(
            (this shr 24).toByte(),
            (this shr 16).toByte(),
            (this shr 8).toByte(),
            this.toByte()
        )
    }
}