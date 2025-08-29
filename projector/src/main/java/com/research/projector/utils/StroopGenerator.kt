package com.research.projector.utils

import com.research.projector.models.RuntimeConfig
import com.research.projector.models.StroopStimulus
import com.research.projector.models.StimulusTimingInfo
import com.research.projector.models.TimedStroopStimulus
import kotlin.random.Random
import java.io.Serializable
import android.util.Log

/**
 * Generates Stroop stimuli with random color assignments and timing intervals.
 * Ensures proper Stroop test conditions (no word-color matching) and timing accuracy.
 */
class StroopGenerator(private val runtimeConfig: RuntimeConfig) : Serializable {

    private val random = Random.Default
    private val availableWords = runtimeConfig.baseConfig.getColorWords()
    private val colorInts = runtimeConfig.baseConfig.getColorInts()

    init {
        // Debug logging for initialization
        Log.d("StroopGenerator", "=== STROOP GENERATOR INITIALIZATION ===")
        Log.d("StroopGenerator", "Available words: ${availableWords.size}")
        availableWords.forEach { word ->
            Log.d("StroopGenerator", "  Word: '$word'")
        }
        Log.d("StroopGenerator", "Available colors: ${colorInts.size}")
        colorInts.forEach { (name, colorInt) ->
            Log.d("StroopGenerator", "  Color: '$name' -> $colorInt")
        }

        // Validate that we have enough colors for proper Stroop testing
        require(availableWords.size >= 2) {
            "At least 2 colors required for Stroop generation. Found: ${availableWords.size}"
        }
        require(colorInts.size >= 2) {
            "Color mapping incomplete. Words: ${availableWords.size}, Colors: ${colorInts.size}"
        }

        Log.d("StroopGenerator", "Validation passed - ready to generate stimuli")
    }

    /**
     * Generate a single random Stroop stimulus
     * Guarantees that the word will not appear in its matching color
     */
    fun generateStimulus(sequenceNumber: Int = 0): TimedStroopStimulus? {
        Log.d("StroopGenerator", "=== GENERATE STIMULUS DEBUG ===")
        Log.d("StroopGenerator", "Sequence number: $sequenceNumber")
        Log.d("StroopGenerator", "Available words: ${availableWords.size} - $availableWords")
        Log.d("StroopGenerator", "Available colors: ${colorInts.size} - ${colorInts.keys}")

        if (availableWords.isEmpty()) {
            Log.e("StroopGenerator", "No available words!")
            return null
        }

        if (colorInts.isEmpty()) {
            Log.e("StroopGenerator", "No available colors!")
            return null
        }

        // Randomly select a color word
        val selectedWord = availableWords.random(random)
        Log.d("StroopGenerator", "Selected word: '$selectedWord'")

        // Get all available colors except the one that matches the word
        val wordColor = colorInts[selectedWord]
        Log.d("StroopGenerator", "Word color for '$selectedWord': $wordColor")

        val availableColors = colorInts.filterValues { colorInt ->
            colorInt != wordColor
        }
        Log.d("StroopGenerator", "Available display colors: ${availableColors.size}")
        availableColors.forEach { (name, colorInt) ->
            Log.d("StroopGenerator", "  Available: '$name' -> $colorInt")
        }

        // If no valid colors available (shouldn't happen with proper config), return null
        if (availableColors.isEmpty()) {
            Log.e("StroopGenerator", "No valid colors available for word '$selectedWord' - this should not happen!")
            Log.e("StroopGenerator", "Word color: $wordColor")
            Log.e("StroopGenerator", "All colors: $colorInts")
            return null
        }

        // Randomly select a display color from available colors
        val (displayColorName, displayColorInt) = availableColors.entries.random(random)
        Log.d("StroopGenerator", "Selected display color: '$displayColorName' ($displayColorInt)")

        // Create the stimulus
        val stimulus = StroopStimulus.createSafe(
            colorWord = selectedWord,
            displayColor = displayColorInt,
            displayColorName = displayColorName,
            wordColorMapping = colorInts
        )

        if (stimulus == null) {
            Log.e("StroopGenerator", "StroopStimulus.createSafe returned null!")
            return null
        }

        Log.d("StroopGenerator", "Created stimulus: ${stimulus.getDescription()}")

        // Generate timing information
        val timingInfo = generateTimingInfo()
        Log.d("StroopGenerator", "Generated timing: ${timingInfo.displayDuration}ms display, ${timingInfo.intervalDuration}ms interval")

        val result = TimedStroopStimulus(
            stimulus = stimulus,
            timing = timingInfo,
            sequenceNumber = sequenceNumber
        )

        Log.d("StroopGenerator", "Successfully generated timed stimulus #$sequenceNumber")
        return result
    }

    /**
     * Generate random timing information for a stimulus
     */
    fun generateTimingInfo(): StimulusTimingInfo {
        val effectiveTiming = runtimeConfig.getEffectiveTiming()

        Log.d("StroopGenerator", "Generating timing from config:")
        Log.d("StroopGenerator", "  Display duration: ${effectiveTiming.stroopDisplayDuration}ms")
        Log.d("StroopGenerator", "  Min interval: ${effectiveTiming.minInterval}ms")
        Log.d("StroopGenerator", "  Max interval: ${effectiveTiming.maxInterval}ms")

        // Generate random interval between min and max
        val intervalDuration = random.nextLong(
            from = effectiveTiming.minInterval.toLong(),
            until = effectiveTiming.maxInterval.toLong() + 1
        )

        Log.d("StroopGenerator", "Generated random interval: ${intervalDuration}ms")

        return StimulusTimingInfo(
            displayDuration = effectiveTiming.stroopDisplayDuration.toLong(),
            intervalDuration = intervalDuration
        )
    }

    /**
     * Generate a sequence of Stroop stimuli
     * Useful for pre-generating stimuli or testing
     */
    fun generateSequence(count: Int): List<TimedStroopStimulus> {
        Log.d("StroopGenerator", "Generating sequence of $count stimuli")
        val stimuli = mutableListOf<TimedStroopStimulus>()

        for (i in 1..count) {
            val stimulus = generateStimulus(i)
            if (stimulus != null) {
                stimuli.add(stimulus)
                Log.d("StroopGenerator", "Added stimulus $i to sequence")
            } else {
                Log.e("StroopGenerator", "Failed to generate stimulus $i")
            }
        }

        Log.d("StroopGenerator", "Generated ${stimuli.size} out of $count requested stimuli")
        return stimuli
    }

    /**
     * Generate stimuli with balanced word distribution
     * Ensures each color word appears roughly equally in the sequence
     */
    fun generateBalancedSequence(totalCount: Int): List<TimedStroopStimulus> {
        Log.d("StroopGenerator", "Generating balanced sequence of $totalCount stimuli")
        val stimuli = mutableListOf<TimedStroopStimulus>()
        val wordsPerCycle = availableWords.size
        val fullCycles = totalCount / wordsPerCycle
        val remainingCount = totalCount % wordsPerCycle

        Log.d("StroopGenerator", "Full cycles: $fullCycles, remaining: $remainingCount")

        var sequenceNumber = 1

        // Generate full cycles where each word appears once
        repeat(fullCycles) {
            val shuffledWords = availableWords.shuffled(random)
            Log.d("StroopGenerator", "Cycle word order: $shuffledWords")

            for (word in shuffledWords) {
                val stimulus = generateStimulusForWord(word, sequenceNumber)
                if (stimulus != null) {
                    stimuli.add(stimulus)
                    sequenceNumber++
                }
            }
        }

        // Generate remaining stimuli
        if (remainingCount > 0) {
            val shuffledWords = availableWords.shuffled(random).take(remainingCount)
            Log.d("StroopGenerator", "Final partial cycle: $shuffledWords")

            for (word in shuffledWords) {
                val stimulus = generateStimulusForWord(word, sequenceNumber)
                if (stimulus != null) {
                    stimuli.add(stimulus)
                    sequenceNumber++
                }
            }
        }

        Log.d("StroopGenerator", "Generated balanced sequence: ${stimuli.size} stimuli")
        return stimuli
    }

    /**
     * Generate a stimulus for a specific word with random color assignment
     */
    private fun generateStimulusForWord(word: String, sequenceNumber: Int): TimedStroopStimulus? {
        Log.d("StroopGenerator", "Generating stimulus for specific word: '$word'")

        // Get all available colors except the one that matches the word
        val wordColor = colorInts[word]
        val availableColors = colorInts.filterValues { colorInt ->
            colorInt != wordColor
        }

        if (availableColors.isEmpty()) {
            Log.e("StroopGenerator", "No available colors for word '$word'")
            return null
        }

        // Randomly select a display color
        val (displayColorName, displayColorInt) = availableColors.entries.random(random)
        Log.d("StroopGenerator", "Selected '$displayColorName' for word '$word'")

        // Create the stimulus
        val stimulus = StroopStimulus.createSafe(
            colorWord = word,
            displayColor = displayColorInt,
            displayColorName = displayColorName,
            wordColorMapping = colorInts
        ) ?: return null

        // Generate timing information
        val timingInfo = generateTimingInfo()

        return TimedStroopStimulus(
            stimulus = stimulus,
            timing = timingInfo,
            sequenceNumber = sequenceNumber
        )
    }

    /**
     * Generate interval duration for use between stimuli
     */
    fun generateIntervalDuration(): Long {
        val effectiveTiming = runtimeConfig.getEffectiveTiming()
        return random.nextLong(
            from = effectiveTiming.minInterval.toLong(),
            until = effectiveTiming.maxInterval.toLong() + 1
        )
    }

    /**
     * Get statistics about possible stimulus combinations
     */
    fun getGenerationStats(): GenerationStats {
        val totalWords = availableWords.size
        val totalColors = colorInts.size
        val possibleCombinations = totalWords * (totalColors - 1) // Each word can use any color except its own

        return GenerationStats(
            totalWords = totalWords,
            totalColors = totalColors,
            possibleCombinations = possibleCombinations,
            averageInterval = (runtimeConfig.getEffectiveTiming().minInterval +
                    runtimeConfig.getEffectiveTiming().maxInterval) / 2.0
        )
    }

    /**
     * Validate that the current configuration supports proper Stroop generation
     */
    fun validateConfiguration(): StroopGenerationValidation {
        Log.d("StroopGenerator", "Validating configuration...")

        if (availableWords.size < 2) {
            Log.e("StroopGenerator", "Insufficient color words: ${availableWords.size}")
            return StroopGenerationValidation.Error("Insufficient color words: ${availableWords.size}. Need at least 2.")
        }

        if (colorInts.size < 2) {
            Log.e("StroopGenerator", "Insufficient colors: ${colorInts.size}")
            return StroopGenerationValidation.Error("Insufficient colors: ${colorInts.size}. Need at least 2.")
        }

        // Check that each word has at least one non-matching color available
        val problematicWords = mutableListOf<String>()

        for (word in availableWords) {
            val wordColor = colorInts[word]
            val availableColorsForWord = colorInts.filterValues { it != wordColor }

            Log.d("StroopGenerator", "Word '$word' has ${availableColorsForWord.size} available colors")

            if (availableColorsForWord.isEmpty()) {
                problematicWords.add(word)
                Log.e("StroopGenerator", "Word '$word' has no available colors!")
            }
        }

        if (problematicWords.isNotEmpty()) {
            Log.e("StroopGenerator", "Problematic words: $problematicWords")
            return StroopGenerationValidation.Error(
                "Words with no available non-matching colors: ${problematicWords.joinToString(", ")}"
            )
        }

        val effectiveTiming = runtimeConfig.getEffectiveTiming()
        if (effectiveTiming.minInterval > effectiveTiming.maxInterval) {
            Log.e("StroopGenerator", "Invalid timing: min (${effectiveTiming.minInterval}) > max (${effectiveTiming.maxInterval})")
            return StroopGenerationValidation.Error(
                "Invalid timing: min interval (${effectiveTiming.minInterval}) > max interval (${effectiveTiming.maxInterval})"
            )
        }

        Log.d("StroopGenerator", "Configuration validation passed")
        return StroopGenerationValidation.Valid
    }

    /**
     * Create a new generator with updated configuration
     */
    fun withUpdatedConfig(newConfig: RuntimeConfig): StroopGenerator {
        Log.d("StroopGenerator", "Creating generator with updated configuration")
        return StroopGenerator(newConfig)
    }
}

/**
 * Statistics about Stroop generation capabilities
 */
data class GenerationStats(
    val totalWords: Int,
    val totalColors: Int,
    val possibleCombinations: Int,
    val averageInterval: Double
) {
    fun getDescription(): String {
        return """
            Available Words: $totalWords
            Available Colors: $totalColors
            Possible Combinations: $possibleCombinations
            Average Interval: ${averageInterval.toInt()}ms
        """.trimIndent()
    }
}

/**
 * Validation result for Stroop generation configuration
 */
sealed class StroopGenerationValidation {
    object Valid : StroopGenerationValidation()
    data class Error(val message: String) : StroopGenerationValidation()
}

/**
 * Utility functions for Stroop generation
 */
object StroopGenerationUtils {

    /**
     * Estimate how many stimuli could be shown in a given time period
     */
    fun estimateStimulusCount(
        taskDurationMs: Long,
        averageDisplayDuration: Long,
        averageIntervalDuration: Long
    ): Int {
        val averageCycleDuration = averageDisplayDuration + averageIntervalDuration
        return (taskDurationMs / averageCycleDuration).toInt()
    }

    /**
     * Calculate expected task duration for a given number of stimuli
     */
    fun estimateTaskDuration(
        stimulusCount: Int,
        averageDisplayDuration: Long,
        averageIntervalDuration: Long
    ): Long {
        val averageCycleDuration = averageDisplayDuration + averageIntervalDuration
        return stimulusCount * averageCycleDuration
    }

    /**
     * Validate timing configuration for research purposes
     */
    fun validateResearchTiming(
        displayDuration: Long,
        minInterval: Long,
        maxInterval: Long
    ): Boolean {
        // Research-appropriate timing constraints
        return displayDuration in 500..5000 &&  // 0.5-5 seconds display
                minInterval in 200..10000 &&     // 0.2-10 seconds min interval
                maxInterval in 500..30000 &&     // 0.5-30 seconds max interval
                minInterval <= maxInterval
    }
}