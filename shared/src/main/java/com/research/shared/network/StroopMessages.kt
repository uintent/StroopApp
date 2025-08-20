package com.research.shared.network

import kotlinx.serialization.Serializable

/**
 * Message sent from Projector to Master when a Stroop stimulus is displayed
 */
@Serializable
data class StroopDisplayMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val word: String,              // The word displayed (e.g., "RED", "BLUE")
    val displayColor: String,      // The color it's displayed in (hex format, e.g., "#FF0000")
    val correctAnswer: String,     // What the participant should say (the color name)
    val displayTime: Long = System.currentTimeMillis()
) : NetworkMessage() {
    override val messageType = MessageType.STROOP_DISPLAY
}

/**
 * Command to start displaying Stroop stimuli
 */
@Serializable
data class StartStroopCommand(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String,
    val displayDuration: Int = 2000,  // milliseconds
    val minInterval: Int = 1000,      // milliseconds
    val maxInterval: Int = 3000       // milliseconds
) : NetworkMessage() {
    override val messageType = MessageType.START_STROOP_SEQUENCE
}

/**
 * Command to stop displaying Stroop stimuli
 */
@Serializable
data class StopStroopCommand(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val taskId: String
) : NetworkMessage() {
    override val messageType = MessageType.STOP_STROOP_SEQUENCE
}

/**
 * Message sent when a Stroop stimulus disappears
 */
@Serializable
data class StroopHiddenMessage(
    override val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val hiddenTime: Long = System.currentTimeMillis()
) : NetworkMessage() {
    override val messageType = MessageType.STROOP_HIDDEN
}