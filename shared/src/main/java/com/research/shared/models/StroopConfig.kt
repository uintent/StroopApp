package com.research.shared.models

import android.graphics.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.Serializable as JavaSerializable

/**
 * Data classes representing the structure of the research_config.json file
 * and related configuration objects for the Stroop test application.
 *
 * Located in shared module to be used by both Master and Projector apps.
 */

/**
 * Root configuration object loaded from JSON
 */
@Serializable
data class StroopConfig(
    @SerialName("stroop_colors")
    val stroopColors: Map<String, String>,

    @SerialName("tasks")
    val tasks: Map<String, TaskConfig>,

    @SerialName("task_lists")
    val taskLists: Map<String, TaskListConfig>,

    @SerialName("timing")
    val timing: TimingConfig
) : JavaSerializable {
    /**
     * Validates the entire configuration
     * @return ValidationResult indicating success or specific error
     */
    fun validate(): ValidationResult {
        // Validate stroop colors
        if (stroopColors.size < 2) {
            return ValidationResult.Error("At least 2 colors required for proper Stroop conflicts")
        }

        stroopColors.forEach { (colorName, hexValue) ->
            if (!isValidHexColor(hexValue)) {
                return ValidationResult.Error("Invalid hex color '$hexValue' for color '$colorName'")
            }
        }

        // Validate tasks
        if (tasks.isEmpty()) {
            return ValidationResult.Error("At least one task must be defined")
        }

        tasks.forEach { (taskId, taskConfig) ->
            val validationResult = taskConfig.validate(taskId)
            if (validationResult is ValidationResult.Error) {
                return validationResult
            }
        }

        // Validate task lists
        if (taskLists.isEmpty()) {
            return ValidationResult.Error("At least one task list must be defined")
        }

        taskLists.forEach { (listId, taskList) ->
            val validationResult = taskList.validate(listId, tasks.keys)
            if (validationResult is ValidationResult.Error) {
                return validationResult
            }
        }

        // Validate timing
        val timingValidation = timing.validate()
        if (timingValidation is ValidationResult.Error) {
            return timingValidation
        }

        return ValidationResult.Success
    }

    /**
     * Gets all available color words as a list
     */
    fun getColorWords(): List<String> = stroopColors.keys.toList()

    /**
     * Gets all available colors as Android Color integers
     */
    fun getColorInts(): Map<String, Int> {
        return stroopColors.mapValues { (_, hexValue) ->
            Color.parseColor(hexValue)
        }
    }

    private fun isValidHexColor(hexValue: String): Boolean {
        return try {
            val normalizedHex = if (hexValue.startsWith("#")) hexValue else "#$hexValue"
            normalizedHex.matches(Regex("^#[0-9A-Fa-f]{6}$"))
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * Configuration for individual tasks
 */
@Serializable
data class TaskConfig(
    @SerialName("label")
    val label: String,

    @SerialName("text")
    val text: String,

    @SerialName("timeout_seconds")
    val timeoutSeconds: Int
) : JavaSerializable {
    fun validate(taskId: String): ValidationResult {
        if (timeoutSeconds <= 0) {
            return ValidationResult.Error("Task '$taskId' has invalid timeout: $timeoutSeconds. Must be positive.")
        }

        if (label.isBlank()) {
            return ValidationResult.Error("Task '$taskId' has empty label.")
        }

        if (text.isBlank()) {
            return ValidationResult.Error("Task '$taskId' has empty text description.")
        }

        return ValidationResult.Success
    }

    /**
     * Convert timeout to milliseconds for internal use
     */
    fun timeoutMillis(): Long = timeoutSeconds * 1000L
}

/**
 * Configuration for task sequence lists
 */
@Serializable
data class TaskListConfig(
    @SerialName("label")
    val label: String,

    @SerialName("task_sequence")
    val taskSequence: String
) : JavaSerializable {
    /**
     * Parse the pipe-delimited task sequence into a list of task IDs
     */
    fun getTaskIds(): List<String> {
        return if (taskSequence.isBlank()) {
            emptyList()
        } else {
            taskSequence.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    fun validate(listId: String, availableTaskIds: Set<String>): ValidationResult {
        val taskIds = getTaskIds()

        if (taskIds.isEmpty()) {
            return ValidationResult.Error("Task list '$listId' has empty task sequence")
        }

        if (label.isBlank()) {
            return ValidationResult.Error("Task list '$listId' has empty label")
        }

        // Check for task repetition
        val uniqueTaskIds = taskIds.toSet()
        if (uniqueTaskIds.size != taskIds.size) {
            return ValidationResult.Error("Task list '$listId' contains repeated tasks. Task repetition is not allowed.")
        }

        // Check that all referenced task IDs exist
        taskIds.forEach { taskId ->
            if (taskId !in availableTaskIds) {
                return ValidationResult.Error("Task list '$listId' references non-existent task ID '$taskId'")
            }
        }

        return ValidationResult.Success
    }
}

/**
 * Timing configuration for Stroop display and intervals
 */
@Serializable
data class TimingConfig(
    @SerialName("stroop_display_duration")
    val stroopDisplayDuration: Int,

    @SerialName("min_interval")
    val minInterval: Int,

    @SerialName("max_interval")
    val maxInterval: Int,

    @SerialName("countdown_duration")
    val countdownDuration: Int
) : JavaSerializable {
    fun validate(): ValidationResult {
        if (stroopDisplayDuration <= 0) {
            return ValidationResult.Error("Stroop display duration must be positive, got: $stroopDisplayDuration")
        }

        if (minInterval <= 0) {
            return ValidationResult.Error("Minimum interval must be positive, got: $minInterval")
        }

        if (maxInterval <= 0) {
            return ValidationResult.Error("Maximum interval must be positive, got: $maxInterval")
        }

        if (minInterval > maxInterval) {
            return ValidationResult.Error("Minimum interval ($minInterval) cannot be greater than maximum interval ($maxInterval)")
        }

        if (countdownDuration <= 0) {
            return ValidationResult.Error("Countdown duration must be positive, got: $countdownDuration")
        }

        return ValidationResult.Success
    }

    /**
     * Convert countdown duration to milliseconds
     */
    fun countdownMillis(): Long = countdownDuration * 1000L
}

/**
 * Validation result sealed class for type-safe error handling
 */
sealed class ValidationResult {
    object Success : ValidationResult()
    data class Error(val message: String) : ValidationResult()
}

/**
 * Runtime configuration that can be modified through settings
 * Combines JSON config with user preferences
 */
@Serializable
data class RuntimeConfig(
    val baseConfig: StroopConfig,
    val stroopDisplayDuration: Int = baseConfig.timing.stroopDisplayDuration,
    val minInterval: Int = baseConfig.timing.minInterval,
    val maxInterval: Int = baseConfig.timing.maxInterval,
    val countdownDuration: Int = baseConfig.timing.countdownDuration
) : JavaSerializable {
    /**
     * Create updated runtime config with new timing values
     */
    fun withTimingUpdates(
        newStroopDuration: Int? = null,
        newMinInterval: Int? = null,
        newMaxInterval: Int? = null,
        newCountdownDuration: Int? = null
    ): RuntimeConfig {
        return copy(
            stroopDisplayDuration = newStroopDuration ?: stroopDisplayDuration,
            minInterval = newMinInterval ?: minInterval,
            maxInterval = newMaxInterval ?: maxInterval,
            countdownDuration = newCountdownDuration ?: countdownDuration
        )
    }

    /**
     * Validate runtime timing values
     */
    fun validateTiming(): ValidationResult {
        val tempTiming = TimingConfig(
            stroopDisplayDuration = stroopDisplayDuration,
            minInterval = minInterval,
            maxInterval = maxInterval,
            countdownDuration = countdownDuration
        )
        return tempTiming.validate()
    }

    /**
     * Get effective timing configuration
     */
    fun getEffectiveTiming(): TimingConfig {
        return TimingConfig(
            stroopDisplayDuration = stroopDisplayDuration,
            minInterval = minInterval,
            maxInterval = maxInterval,
            countdownDuration = countdownDuration
        )
    }
}