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

    // Task control messages
    START_TASK,
    END_TASK,
    PAUSE_TASK,
    RESUME_TASK,

    // Stroop control messages
    START_COUNTDOWN,
    START_STROOP_SEQUENCE,
    STOP_STROOP_SEQUENCE,

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

// Task Control Messages

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

// Stroop Settings

@Serializable
data class StroopSettings(
    val displayDurationMs: Long = 2000,
    val minIntervalMs: Long = 1000,
    val maxIntervalMs: Long = 3000,
    val countdownDurationMs: Long = 4000,
    val colors: List<String> = listOf("rot", "blau", "grün", "gelb", "schwarz", "braun"),
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
        classDiscriminator = "messageType"
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

/**
 * Constants for network communication
 */
object NetworkConstants {
    const val SERVICE_TYPE = "_drivertest._tcp."
    const val SERVICE_NAME_PREFIX = "DriverDistraction"
    const val DEFAULT_PORT = 0 // Let system assign
    const val SOCKET_TIMEOUT_MS = 10_000
    const val HEARTBEAT_INTERVAL_MS = 5_000L
    const val RECONNECTION_DELAY_MS = 2_000L
    const val DISCOVERY_TIMEOUT_MS = 30_000L
    const val MAX_MESSAGE_SIZE = 1_048_576 // 1MB
}