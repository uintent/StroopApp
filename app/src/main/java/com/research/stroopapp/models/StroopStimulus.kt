package com.research.stroopapp.models

import android.graphics.Color
import java.io.Serializable

/**
 * Represents a single Stroop stimulus with all its display properties.
 * This is the core data model for individual Stroop test items.
 */
data class StroopStimulus(
    val colorWord: String,          // The text to display (e.g., "rot", "红")
    val displayColor: Int,          // Android Color int for text color
    val displayColorName: String,   // Name of the display color (for logging/debugging)
    val isCongruent: Boolean = false // Whether word matches color (should always be false per requirements)
) : Serializable {
    
    /**
     * Get the display color as a hex string for debugging/logging
     */
    fun getDisplayColorHex(): String {
        return String.format("#%06X", 0xFFFFFF and displayColor)
    }
    
    /**
     * Validate that this stimulus follows Stroop test rules
     */
    fun isValid(): Boolean {
        // Per requirements: word should never match its meaning in color
        // This is a safety check - should always be true in our implementation
        return !isCongruent
    }
    
    /**
     * Create a description string for logging or debugging
     */
    fun getDescription(): String {
        return "Word: '$colorWord' in ${displayColorName} (${getDisplayColorHex()})"
    }
    
    companion object {
        /**
         * Create a StroopStimulus from color word and display color
         * Automatically determines congruence and validates the stimulus
         */
        fun create(
            colorWord: String,
            displayColor: Int,
            displayColorName: String,
            wordColorMapping: Map<String, Int>
        ): StroopStimulus {
            // Check if this would be a congruent stimulus (word matches color)
            val wordColor = wordColorMapping[colorWord]
            val isCongruent = wordColor == displayColor
            
            return StroopStimulus(
                colorWord = colorWord,
                displayColor = displayColor,
                displayColorName = displayColorName,
                isCongruent = isCongruent
            )
        }
        
        /**
         * Create a safe (non-congruent) StroopStimulus
         * Ensures the word never appears in its matching color
         */
        fun createSafe(
            colorWord: String,
            displayColor: Int,
            displayColorName: String,
            wordColorMapping: Map<String, Int>
        ): StroopStimulus? {
            val wordColor = wordColorMapping[colorWord]
            
            // Reject if this would create a congruent stimulus
            if (wordColor == displayColor) {
                return null
            }
            
            return StroopStimulus(
                colorWord = colorWord,
                displayColor = displayColor,
                displayColorName = displayColorName,
                isCongruent = false
            )
        }
    }
}

/**
 * Represents the timing information for a Stroop stimulus display sequence
 */
data class StimulusTimingInfo(
    val displayDuration: Long,      // How long stimulus is shown (ms)
    val intervalDuration: Long,     // How long interval after stimulus (ms)
    val startTime: Long = 0L,       // When stimulus display started (timestamp)
    val endTime: Long = 0L          // When stimulus display ended (timestamp)
) : Serializable {
    /**
     * Calculate the total time for this stimulus cycle (display + interval)
     */
    fun getTotalCycleDuration(): Long {
        return displayDuration + intervalDuration
    }
    
    /**
     * Check if this timing info is valid
     */
    fun isValid(): Boolean {
        return displayDuration > 0 && intervalDuration > 0
    }
    
    /**
     * Create timing info with start time set to current time
     */
    fun withStartTime(currentTime: Long): StimulusTimingInfo {
        return copy(startTime = currentTime)
    }
    
    /**
     * Create timing info with end time set
     */
    fun withEndTime(currentTime: Long): StimulusTimingInfo {
        return copy(endTime = currentTime)
    }
    
    /**
     * Calculate actual display duration based on timestamps
     */
    fun getActualDisplayDuration(): Long? {
        return if (startTime > 0 && endTime > 0 && endTime > startTime) {
            endTime - startTime
        } else {
            null
        }
    }
}

/**
 * Represents a complete Stroop stimulus with its timing information
 * Used during active display and for logging/analysis
 */
data class TimedStroopStimulus(
    val stimulus: StroopStimulus,
    val timing: StimulusTimingInfo,
    val sequenceNumber: Int = 0     // Position in the sequence (for tracking)
) : Serializable {
    /**
     * Check if this timed stimulus is complete (has end time)
     */
    fun isComplete(): Boolean {
        return timing.endTime > 0
    }
    
    /**
     * Get a summary description for logging
     */
    fun getSummary(): String {
        val actualDuration = timing.getActualDisplayDuration()
        val durationText = actualDuration?.let { "${it}ms (actual)" } ?: "${timing.displayDuration}ms (planned)"
        
        return "Stimulus #$sequenceNumber: ${stimulus.getDescription()}, Duration: $durationText"
    }
    
    /**
     * Create a completed version with end time
     */
    fun complete(endTime: Long): TimedStroopStimulus {
        return copy(timing = timing.withEndTime(endTime))
    }
}

/**
 * Enum representing the current state of Stroop stimulus display
 */
enum class StimulusDisplayState {
    WAITING,        // Before countdown starts
    COUNTDOWN,      // Showing countdown (3, 2, 1, 0)
    DISPLAY,        // Showing Stroop stimulus
    INTERVAL,       // White screen between stimuli
    COMPLETED       // Task finished
}

/**
 * Data class for countdown state management
 */
data class CountdownState(
    val currentNumber: Int,         // Current countdown number (3, 2, 1, 0)
    val totalDuration: Long,        // Total countdown duration in ms
    val remainingTime: Long,        // Time remaining in current countdown step
    val isComplete: Boolean = false // Whether countdown is finished
) : Serializable {
    /**
     * Check if countdown is in progress
     */
    fun isActive(): Boolean {
        return currentNumber >= 0 && !isComplete
    }
    
    /**
     * Get the next countdown state
     */
    fun getNext(stepDuration: Long): CountdownState {
        val nextNumber = currentNumber - 1
        return if (nextNumber < 0) {
            copy(currentNumber = 0, remainingTime = 0L, isComplete = true)
        } else {
            copy(currentNumber = nextNumber, remainingTime = stepDuration)
        }
    }
    
    companion object {
        /**
         * Create initial countdown state
         */
        fun initial(totalDuration: Long): CountdownState {
            val stepDuration = totalDuration / 4 // 4 steps: 3, 2, 1, 0
            return CountdownState(
                currentNumber = 3,
                totalDuration = totalDuration,
                remainingTime = stepDuration
            )
        }
    }
}