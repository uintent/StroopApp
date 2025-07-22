package com.research.projector.utils

import com.research.projector.models.RuntimeConfig
import com.research.projector.models.StroopStimulus
import com.research.projector.models.StimulusTimingInfo
import com.research.projector.models.TimedStroopStimulus
import kotlin.random.Random
import java.io.Serializable

/**
 * Generates Stroop stimuli with random color assignments and timing intervals.
 * Ensures proper Stroop test conditions (no word-color matching) and timing accuracy.
 */
class StroopGenerator(private val runtimeConfig: RuntimeConfig) : Serializable {
    
    private val random = Random.Default
    private val availableWords = runtimeConfig.baseConfig.getColorWords()
    private val colorInts = runtimeConfig.baseConfig.getColorInts()
    
    init {
        // Validate that we have enough colors for proper Stroop testing
        require(availableWords.size >= 2) {
            "At least 2 colors required for Stroop generation. Found: ${availableWords.size}"
        }
        require(colorInts.size >= 2) {
            "Color mapping incomplete. Words: ${availableWords.size}, Colors: ${colorInts.size}"
        }
    }
    
    /**
     * Generate a single random Stroop stimulus
     * Guarantees that the word will not appear in its matching color
     */
    fun generateStimulus(sequenceNumber: Int = 0): TimedStroopStimulus? {
        // Randomly select a color word
        val selectedWord = availableWords.random(random)
        
        // Get all available colors except the one that matches the word
        val wordColor = colorInts[selectedWord]
        val availableColors = colorInts.filterValues { colorInt ->
            colorInt != wordColor
        }
        
        // If no valid colors available (shouldn't happen with proper config), return null
        if (availableColors.isEmpty()) {
            return null
        }
        
        // Randomly select a display color from available colors
        val (displayColorName, displayColorInt) = availableColors.entries.random(random)
        
        // Create the stimulus
        val stimulus = StroopStimulus.createSafe(
            colorWord = selectedWord,
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
     * Generate random timing information for a stimulus
     */
    fun generateTimingInfo(): StimulusTimingInfo {
        val effectiveTiming = runtimeConfig.getEffectiveTiming()
        
        // Generate random interval between min and max
        val intervalDuration = random.nextLong(
            from = effectiveTiming.minInterval.toLong(),
            until = effectiveTiming.maxInterval.toLong() + 1
        )
        
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
        val stimuli = mutableListOf<TimedStroopStimulus>()
        
        for (i in 1..count) {
            val stimulus = generateStimulus(i)
            if (stimulus != null) {
                stimuli.add(stimulus)
            }
        }
        
        return stimuli
    }
    
    /**
     * Generate stimuli with balanced word distribution
     * Ensures each color word appears roughly equally in the sequence
     */
    fun generateBalancedSequence(totalCount: Int): List<TimedStroopStimulus> {
        val stimuli = mutableListOf<TimedStroopStimulus>()
        val wordsPerCycle = availableWords.size
        val fullCycles = totalCount / wordsPerCycle
        val remainingCount = totalCount % wordsPerCycle
        
        var sequenceNumber = 1
        
        // Generate full cycles where each word appears once
        repeat(fullCycles) {
            val shuffledWords = availableWords.shuffled(random)
            
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
            
            for (word in shuffledWords) {
                val stimulus = generateStimulusForWord(word, sequenceNumber)
                if (stimulus != null) {
                    stimuli.add(stimulus)
                    sequenceNumber++
                }
            }
        }
        
        return stimuli
    }
    
    /**
     * Generate a stimulus for a specific word with random color assignment
     */
    private fun generateStimulusForWord(word: String, sequenceNumber: Int): TimedStroopStimulus? {
        // Get all available colors except the one that matches the word
        val wordColor = colorInts[word]
        val availableColors = colorInts.filterValues { colorInt ->
            colorInt != wordColor
        }
        
        if (availableColors.isEmpty()) {
            return null
        }
        
        // Randomly select a display color
        val (displayColorName, displayColorInt) = availableColors.entries.random(random)
        
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
        if (availableWords.size < 2) {
            return StroopGenerationValidation.Error("Insufficient color words: ${availableWords.size}. Need at least 2.")
        }
        
        if (colorInts.size < 2) {
            return StroopGenerationValidation.Error("Insufficient colors: ${colorInts.size}. Need at least 2.")
        }
        
        // Check that each word has at least one non-matching color available
        val problematicWords = mutableListOf<String>()
        
        for (word in availableWords) {
            val wordColor = colorInts[word]
            val availableColorsForWord = colorInts.filterValues { it != wordColor }
            
            if (availableColorsForWord.isEmpty()) {
                problematicWords.add(word)
            }
        }
        
        if (problematicWords.isNotEmpty()) {
            return StroopGenerationValidation.Error(
                "Words with no available non-matching colors: ${problematicWords.joinToString(", ")}"
            )
        }
        
        val effectiveTiming = runtimeConfig.getEffectiveTiming()
        if (effectiveTiming.minInterval > effectiveTiming.maxInterval) {
            return StroopGenerationValidation.Error(
                "Invalid timing: min interval (${effectiveTiming.minInterval}) > max interval (${effectiveTiming.maxInterval})"
            )
        }
        
        return StroopGenerationValidation.Valid
    }
    
    /**
     * Create a new generator with updated configuration
     */
    fun withUpdatedConfig(newConfig: RuntimeConfig): StroopGenerator {
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