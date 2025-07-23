package com.research.projector.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.research.projector.models.*
import com.research.projector.utils.FontSizer
import com.research.projector.utils.FontSizingResult
import com.research.projector.utils.StroopGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.research.projector.network.ProjectorNetworkManager

/**
 * ViewModel for StroopDisplayActivity
 * Manages precise timing for countdown, Stroop stimuli display, and intervals
 */
class StroopDisplayViewModel(application: Application) : AndroidViewModel(application) {
    
    private val fontSizer = FontSizer(application)
    
    // Display state
    private val _displayState = MutableLiveData<StimulusDisplayState>()
    val displayState: LiveData<StimulusDisplayState> = _displayState
    
    // Current display content
    private val _displayContent = MutableLiveData<DisplayContent>()
    val displayContent: LiveData<DisplayContent> = _displayContent
    
    // Task execution state
    private val _taskExecutionState = MutableLiveData<TaskExecutionState>()
    val taskExecutionState: LiveData<TaskExecutionState> = _taskExecutionState
    
    // Font sizing information
    private val _fontInfo = MutableLiveData<FontInfo>()
    val fontInfo: LiveData<FontInfo> = _fontInfo
    
    // Completion event
    private val _taskCompletionEvent = MutableLiveData<TaskCompletionEvent?>()
    val taskCompletionEvent: LiveData<TaskCompletionEvent?> = _taskCompletionEvent
    
    // Error events
    private val _errorEvent = MutableLiveData<String?>()
    val errorEvent: LiveData<String?> = _errorEvent
    
    // Internal state
    private var stroopGenerator: StroopGenerator? = null
    private var runtimeConfig: RuntimeConfig? = null
    private var currentJob: Job? = null
    private var stimulusSequenceNumber = 0
    
    /**
     * Initialize the display with task execution parameters
     */
    fun initialize(
        taskExecution: TaskExecutionState,
        runtimeConfig: RuntimeConfig,
        stroopGenerator: StroopGenerator,
        displayWidth: Int,
        displayHeight: Int
    ) {
        this.runtimeConfig = runtimeConfig
        this.stroopGenerator = stroopGenerator
        this.stimulusSequenceNumber = 0
        
        _taskExecutionState.value = taskExecution
        
        // Calculate optimal font sizes
        calculateFontSizes(runtimeConfig, displayWidth, displayHeight)
        
        // Start the display sequence
        startDisplaySequence(taskExecution)
    }
    
    /**
     * Calculate optimal font sizes for display
     */
    private fun calculateFontSizes(runtimeConfig: RuntimeConfig, width: Int, height: Int) {
        when (val result = fontSizer.calculateOptimalFontSize(runtimeConfig, width, height)) {
            is FontSizingResult.Success -> {
                val countdownSize = fontSizer.calculateCountdownFontSize(
                    result.fontSize, width, height
                )
                
                _fontInfo.value = FontInfo(
                    stroopFontSize = result.fontSize,
                    countdownFontSize = countdownSize,
                    longestWord = result.longestWord,
                    utilization = result.getUtilization()
                )
            }
            
            is FontSizingResult.Error -> {
                _errorEvent.value = "Font sizing error: ${result.message}"
                // Use fallback sizes
                _fontInfo.value = FontInfo(
                    stroopFontSize = 72f,
                    countdownFontSize = 120f,
                    longestWord = "fallback",
                    utilization = 0.5f
                )
            }
        }
    }
    
    /**
     * Start the complete display sequence (countdown → Stroop stimuli)
     */
    private fun startDisplaySequence(taskExecution: TaskExecutionState) {
        _displayState.value = StimulusDisplayState.COUNTDOWN
        
        val config = runtimeConfig ?: return
        val timing = config.getEffectiveTiming()
        
        // Create countdown state
        val countdownState = CountdownState.initial(timing.countdownMillis())
        val updatedExecution = taskExecution.updateCountdown(countdownState)
        _taskExecutionState.value = updatedExecution
        
        // Start countdown sequence
        startCountdown(updatedExecution)
    }
    
    /**
     * Execute countdown sequence (3, 2, 1, 0)
     */
    private fun startCountdown(taskExecution: TaskExecutionState) {
        val countdownState = taskExecution.countdownState ?: return
        
        _displayContent.value = DisplayContent.Countdown(countdownState.currentNumber)
        
        currentJob = viewModelScope.launch {
            delay(countdownState.remainingTime)
            
            if (countdownState.isComplete) {
                // Countdown finished, start Stroop display
                startStroopSequence(taskExecution)
            } else {
                // Move to next countdown step
                val timing = runtimeConfig?.getEffectiveTiming() ?: return@launch
                val stepDuration = timing.countdownMillis() / 4
                val nextCountdown = countdownState.getNext(stepDuration)
                val updatedExecution = taskExecution.updateCountdown(nextCountdown)
                _taskExecutionState.value = updatedExecution
                
                startCountdown(updatedExecution)
            }
        }
    }
    
    /**
     * Start displaying Stroop stimuli sequence
     */
    private fun startStroopSequence(taskExecution: TaskExecutionState) {
        _displayState.value = StimulusDisplayState.DISPLAY
        
        // Clear countdown state
        val clearedExecution = taskExecution.updateCountdown(null)
        _taskExecutionState.value = clearedExecution
        
        generateAndDisplayNextStimulus(clearedExecution)
    }

    /**
     * Generate and display the next Stroop stimulus - DEBUG VERSION
     */
    private fun generateAndDisplayNextStimulus(taskExecution: TaskExecutionState) {
        android.util.Log.d("StroopDisplay", "=== generateAndDisplayNextStimulus called ===")
        android.util.Log.d("StroopDisplay", "TaskExecution isActive: ${taskExecution.isActive}")
        android.util.Log.d("StroopDisplay", "TaskExecution hasTimedOut: ${taskExecution.hasTimedOut()}")

        // Check if task should continue
        if (taskExecution.hasTimedOut() || !taskExecution.isActive) {
            android.util.Log.d("StroopDisplay", "Task should not continue - completing task")
            completeTask(taskExecution)
            return
        }

        val generator = stroopGenerator
        android.util.Log.d("StroopDisplay", "StroopGenerator is null: ${generator == null}")

        if (generator == null) {
            android.util.Log.e("StroopDisplay", "StroopGenerator is null!")
            _errorEvent.value = "StroopGenerator is null"
            completeTask(taskExecution)
            return
        }

        // Generate new stimulus
        stimulusSequenceNumber++
        android.util.Log.d("StroopDisplay", "Generating stimulus #$stimulusSequenceNumber")

        val timedStimulus = generator.generateStimulus(stimulusSequenceNumber)
        android.util.Log.d("StroopDisplay", "Generated stimulus is null: ${timedStimulus == null}")

        if (timedStimulus == null) {
            android.util.Log.e("StroopDisplay", "Failed to generate Stroop stimulus")
            _errorEvent.value = "Failed to generate Stroop stimulus"
            completeTask(taskExecution)
            return
        }

        android.util.Log.d("StroopDisplay", "Generated stimulus: ${timedStimulus.stimulus.colorWord} in ${timedStimulus.stimulus.displayColorName}")

        // Add stimulus to task execution
        val updatedExecution = taskExecution.addStimulus(
            timedStimulus.copy(timing = timedStimulus.timing.withStartTime(System.currentTimeMillis()))
        )
        android.util.Log.d("StroopDisplay", "Updated task execution state")
        _taskExecutionState.value = updatedExecution

        // Update display state
        android.util.Log.d("StroopDisplay", "Setting display state to DISPLAY")
        _displayState.value = StimulusDisplayState.DISPLAY

        // Display the stimulus
        android.util.Log.d("StroopDisplay", "Setting display content to Stroop stimulus")
        _displayContent.value = DisplayContent.Stroop(timedStimulus.stimulus)

        // SEND NETWORK MESSAGE - STROOP DISPLAYED
        sendStroopDisplayMessage(timedStimulus.stimulus)

        // Schedule stimulus completion
        android.util.Log.d("StroopDisplay", "Scheduling stimulus completion in ${timedStimulus.timing.displayDuration}ms")
        currentJob = viewModelScope.launch {
            delay(timedStimulus.timing.displayDuration)
            android.util.Log.d("StroopDisplay", "Stimulus display duration completed")

            // Complete the stimulus display
            onStimulusDisplayComplete(updatedExecution, timedStimulus)
        }

        android.util.Log.d("StroopDisplay", "=== generateAndDisplayNextStimulus completed ===")
    }

    /**
     * Send Stroop display message over network
     */
    private fun sendStroopDisplayMessage(stimulus: StroopStimulus) {
        try {
            val hexColor = String.format("#%06X", (0xFFFFFF and stimulus.displayColor))
            android.util.Log.d("StroopDisplay", "Sending Stroop display message: ${stimulus.colorWord} in $hexColor")

            ProjectorNetworkManager.sendStroopDisplay(
                word = stimulus.colorWord,
                displayColor = hexColor,
                correctAnswer = stimulus.displayColorName
            )
        } catch (e: Exception) {
            android.util.Log.e("StroopDisplay", "Error sending Stroop display message", e)
        }
    }

    /**
     * Send Stroop hidden message over network
     */
    private fun sendStroopHiddenMessage() {
        try {
            android.util.Log.d("StroopDisplay", "Sending Stroop hidden message")
            ProjectorNetworkManager.sendStroopHidden()
        } catch (e: Exception) {
            android.util.Log.e("StroopDisplay", "Error sending Stroop hidden message", e)
        }
    }

    /**
     * Handle completion of stimulus display, start interval
     */
    private fun onStimulusDisplayComplete(
        taskExecution: TaskExecutionState,
        timedStimulus: TimedStroopStimulus
    ) {
        // Mark stimulus as complete
        val completedStimulus = timedStimulus.complete(System.currentTimeMillis())
        val updatedExecution = taskExecution.updateCurrentStimulus(completedStimulus)
        _taskExecutionState.value = updatedExecution

        // SEND NETWORK MESSAGE - STROOP HIDDEN
        sendStroopHiddenMessage()

        // Start interval period
        startInterval(updatedExecution, completedStimulus.timing.intervalDuration)
    }

    /**
     * Display interval (white screen) between stimuli - DEBUG VERSION
     */
    private fun startInterval(taskExecution: TaskExecutionState, intervalDuration: Long) {
        android.util.Log.d("StroopDisplay", "Starting interval - Duration: ${intervalDuration}ms")
        android.util.Log.d("StroopDisplay", "TaskExecution isActive: ${taskExecution.isActive}, hasTimedOut: ${taskExecution.hasTimedOut()}")

        _displayState.value = StimulusDisplayState.INTERVAL
        _displayContent.value = DisplayContent.Interval

        // Keep the execution state
        _taskExecutionState.value = taskExecution

        currentJob = viewModelScope.launch {
            android.util.Log.d("StroopDisplay", "Starting interval delay: ${intervalDuration}ms")
            delay(intervalDuration)
            android.util.Log.d("StroopDisplay", "Interval delay completed")

            // Check task state after interval
            if (taskExecution.isActive && !taskExecution.hasTimedOut()) {
                android.util.Log.d("StroopDisplay", "Generating next stimulus...")
                val clearedExecution = taskExecution.clearCurrentStimulus()
                _taskExecutionState.value = clearedExecution
                generateAndDisplayNextStimulus(clearedExecution)
            } else {
                android.util.Log.d("StroopDisplay", "Task should complete - isActive: ${taskExecution.isActive}, hasTimedOut: ${taskExecution.hasTimedOut()}")
                completeTask(taskExecution)
            }
        }
    }
    
    /**
     * Complete the task and return to task execution
     */
    private fun completeTask(taskExecution: TaskExecutionState) {
        currentJob?.cancel()
        
        _displayState.value = StimulusDisplayState.COMPLETED
        _displayContent.value = DisplayContent.BlackScreen
        
        val completedExecution = if (!taskExecution.isCompleted) {
            taskExecution.complete()
        } else {
            taskExecution
        }
        
        _taskExecutionState.value = completedExecution
        _taskCompletionEvent.value = TaskCompletionEvent(completedExecution)
    }
    
    /**
     * Emergency stop (called from UI emergency button)
     */
    fun emergencyStop() {
        val currentExecution = _taskExecutionState.value
        if (currentExecution != null) {
            completeTask(currentExecution)
        }
    }
    
    /**
     * Handle display area size changes
     */
    fun onDisplaySizeChanged(width: Int, height: Int) {
        val config = runtimeConfig
        if (config != null) {
            calculateFontSizes(config, width, height)
        }
    }
    
    /**
     * Get current stimulus count for debugging
     */
    fun getCurrentStimulusCount(): Int {
        return _taskExecutionState.value?.getStimulusCount() ?: 0
    }
    
    /**
     * Get current elapsed time for debugging
     */
    fun getCurrentElapsedTime(): Long {
        return _taskExecutionState.value?.getElapsedTime() ?: 0L
    }
    
    /**
     * Get current remaining time for debugging
     */
    fun getCurrentRemainingTime(): Long {
        return _taskExecutionState.value?.getRemainingTime() ?: 0L
    }
    
    /**
     * Clear task completion event after handling
     */
    fun clearTaskCompletionEvent() {

    }
    
    /**
     * Clear error event after handling
     */
    fun clearErrorEvent() {

    }
    
    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
    }
}

/**
 * Content to display on screen
 */
sealed class DisplayContent {
    data class Countdown(val number: Int) : DisplayContent()
    data class Stroop(val stimulus: StroopStimulus) : DisplayContent()
    object Interval : DisplayContent()
    object BlackScreen : DisplayContent()
}

/**
 * Font sizing information
 */
data class FontInfo(
    val stroopFontSize: Float,
    val countdownFontSize: Float,
    val longestWord: String,
    val utilization: Float
) {
    fun getSummary(): String {
        return "Stroop: ${stroopFontSize}sp, Countdown: ${countdownFontSize}sp, Utilization: ${(utilization * 100).toInt()}%"
    }
}

/**
 * Task completion event
 */
data class TaskCompletionEvent(
    val completedTaskExecution: TaskExecutionState
) {
    fun getSummary(): String {
        return "Task ${completedTaskExecution.taskId} completed: ${completedTaskExecution.getStimulusCount()} stimuli in ${completedTaskExecution.getElapsedTime()}ms"
    }
}