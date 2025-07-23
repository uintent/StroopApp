package com.research.shared.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Base class for all network messages between Master and Projector apps
 */
@Serializable
sealed class NetworkMessage {
    abstract val sessionId: String
    abstract val timestamp: Long
}

// Connection Messages

@Serializable
data class HandshakeMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val masterDeviceId: String,
    val masterVersion: String
) : NetworkMessage()

@Serializable
data class HandshakeResponseMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val projectorDeviceId: String,
    val projectorVersion: String,
    val isReady: Boolean
) : NetworkMessage()

@Serializable
data class HeartbeatMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : NetworkMessage()

// Task Control Messages

@Serializable
data class StartTaskMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val taskLabel: String,
    val timeoutSeconds: Int,
    val stroopSettings: StroopSettings
) : NetworkMessage()

@Serializable
data class EndTaskMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val reason: EndReason
) : NetworkMessage()

@Serializable
enum class EndReason {
    COMPLETED,
    TIMEOUT,
    CANCELLED,
    ERROR
}

// Stroop Messages are defined in StroopMessages.kt

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
) : NetworkMessage()

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
) : NetworkMessage()

// Network utilities

object NetworkProtocol {
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        // Use default discriminator "type" for polymorphic serialization
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