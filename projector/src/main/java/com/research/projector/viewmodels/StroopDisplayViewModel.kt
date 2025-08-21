package com.research.projector.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.research.projector.models.*
import com.research.projector.models.RuntimeConfig as ProjectorRuntimeConfig
import com.research.projector.utils.FontSizer
import com.research.projector.utils.FontSizingResult
import com.research.projector.utils.StroopGenerator
import com.research.projector.network.ProjectorNetworkManager
import com.research.projector.network.ProjectorNetworkService
import com.research.shared.network.*
// Import the correct TaskStatusMessage from NetworkMessage.kt (not TaskControlMessages.kt)
import com.research.shared.network.TaskStatusMessage as NetworkTaskStatusMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Enhanced ViewModel for StroopDisplayActivity
 * Now integrates with Master commands for proper task control
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
    private val _taskExecutionState = MutableLiveData<TaskExecutionState?>()
    val taskExecutionState: LiveData<TaskExecutionState?> = _taskExecutionState

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
    private var runtimeConfig: ProjectorRuntimeConfig? = null
    private var currentJob: Job? = null
    private var stimulusSequenceNumber = 0

    // NEW: Network and task control state
    private var networkService: ProjectorNetworkService? = null
    private var currentTaskId: String? = null
    private var isTaskRunning = false
    private var masterControlled = false  // Whether task is controlled by Master

    init {
        // Initialize network service and listen for Master commands
        initializeNetworkService()
    }

    /**
     * Initialize network service and set up Master command listening
     */
    private fun initializeNetworkService() {
        try {
            networkService = ProjectorNetworkManager.getNetworkService(getApplication())

            // Listen for incoming Master commands
            viewModelScope.launch {
                networkService?.receiveMessages()?.collectLatest { message ->
                    handleMasterCommand(message)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("StroopDisplay", "Failed to initialize network service", e)
            _errorEvent.value = "Network initialization failed: ${e.message}"
        }
    }

    /**
     * Handle incoming commands from Master
     */
    private fun handleMasterCommand(message: NetworkMessage) {
        android.util.Log.d("StroopDisplay", "Received Master command: ${message.messageType}")

        viewModelScope.launch {
            when (message) {
                is StartTaskMessage -> {
                    handleStartTaskCommand(message)
                }

                is EndTaskMessage -> {
                    handleEndTaskCommand(message)
                }

                is TaskResetCommand -> {
                    handleResetTaskCommand(message)
                }

                else -> {
                    android.util.Log.d("StroopDisplay", "Ignoring non-task command: ${message.messageType}")
                }
            }
        }
    }

    /**
     * Handle START_TASK command from Master
     * DEFENSIVE: Handle different message structures safely
     */
    private suspend fun handleStartTaskCommand(message: StartTaskMessage) {
        android.util.Log.d("StroopDisplay", "Starting task from Master: ${message.taskId}")

        try {
            currentTaskId = message.taskId
            masterControlled = true

            // Extract configuration from message - handle different property names
            val stroopConfig = try {
                // Try to access stroopSettings property
                val field = message::class.java.getDeclaredField("stroopSettings")
                field.isAccessible = true
                field.get(message) as StroopSettings
            } catch (e: Exception) {
                android.util.Log.e("StroopDisplay", "StartTaskMessage missing stroopSettings property", e)
                throw IllegalArgumentException("Master message missing required Stroop configuration")
            }

            // Extract timeout from message - handle different property names
            val timeoutSeconds = try {
                val field = message::class.java.getDeclaredField("timeoutSeconds")
                field.isAccessible = true
                when (val value = field.get(message)) {
                    is Int -> value
                    is Long -> value.toInt()
                    else -> throw IllegalArgumentException("Invalid timeout type")
                }
            } catch (e: Exception) {
                android.util.Log.e("StroopDisplay", "StartTaskMessage missing timeoutSeconds property", e)
                throw IllegalArgumentException("Master message missing required timeout")
            }

            // Master MUST provide all configuration - no fallbacks
            val masterRuntimeConfig = createProjectorRuntimeConfigFromStroopSettings(stroopConfig)

            // Validate that we have minimum required configuration
            if (!validateMinimumConfig(stroopConfig)) {
                throw IllegalArgumentException("Insufficient configuration from Master")
            }

            // Create task execution state
            val taskExecution = TaskExecutionState(
                taskId = message.taskId,
                timeoutDuration = timeoutSeconds.toLong() * 1000L
            ).start()

            // Create StroopGenerator with Master's configuration
            val stroopGen = StroopGenerator(masterRuntimeConfig)

            // Send confirmation to Master
            sendTaskStatusToMaster(message.taskId, TaskStatus.COUNTDOWN)

            // Start the display sequence with Master's configuration
            initialize(taskExecution, masterRuntimeConfig, stroopGen,
                getCurrentDisplayWidth(), getCurrentDisplayHeight())

        } catch (e: Exception) {
            android.util.Log.e("StroopDisplay", "Error handling start task command - insufficient Master config", e)
            _errorEvent.postValue("Master configuration incomplete: ${e.message}")

            // Send error to Master - configuration is Master's responsibility
            sendErrorToMaster("INVALID_MASTER_CONFIG", "Configuration from Master is incomplete: ${e.message}")
        }
    }

    /**
     * Handle END_TASK command from Master
     * DEFENSIVE: Handle different message structures safely
     */
    private suspend fun handleEndTaskCommand(message: EndTaskMessage) {
        // Extract reason from message - handle different property names
        val reasonText = try {
            val field = message::class.java.getDeclaredField("reason")
            field.isAccessible = true
            field.get(message)?.toString() ?: "UNKNOWN"
        } catch (e: Exception) {
            android.util.Log.w("StroopDisplay", "EndTaskMessage missing reason property", e)
            "MANUAL_STOP"
        }

        android.util.Log.d("StroopDisplay", "Ending task from Master: ${message.taskId}, reason: $reasonText")

        if (message.taskId == currentTaskId && isTaskRunning) {
            // Stop current task immediately
            currentJob?.cancel()

            val currentExecution = _taskExecutionState.value
            if (currentExecution != null) {
                completeTask(currentExecution, masterInitiated = true)
            }

            // Send confirmation to Master
            sendTaskStatusToMaster(message.taskId, TaskStatus.COMPLETED)
        }
    }

    /**
     * Handle RESET_TASK command from Master
     */
    private suspend fun handleResetTaskCommand(message: TaskResetCommand) {
        android.util.Log.d("StroopDisplay", "Resetting task from Master")

        // Cancel current operations
        currentJob?.cancel()
        currentTaskId = null
        isTaskRunning = false
        masterControlled = false

        // Reset to waiting state
        _displayState.value = StimulusDisplayState.WAITING
        _displayContent.value = DisplayContent.BlackScreen
        _taskExecutionState.value = null
    }

    /**
     * Convert Master's StroopSettings to projector's RuntimeConfig format
     * STRICT: No defaults - Master must provide complete configuration
     */
    private fun createProjectorRuntimeConfigFromStroopSettings(stroopSettings: StroopSettings): ProjectorRuntimeConfig {
        android.util.Log.d("StroopDisplay", "Converting Master config: ${stroopSettings.colors.size} colors, ${stroopSettings.displayDurationMs}ms display")

        // Validate Master provided sufficient colors
        if (stroopSettings.colors.size < 2) {
            throw IllegalArgumentException("Master must provide at least 2 colors for Stroop test. Received: ${stroopSettings.colors.size}")
        }

        // Master must provide color mappings - we don't guess
        val stroopColors = stroopSettings.colors.associateWith { colorName ->
            // Use the actual colors the Master intends
            // NOTE: Master should ideally send color mappings, not just names
            // For now, use the standard mapping but this should be enhanced
            when (colorName.lowercase()) {
                "rot" -> "#FF0000"
                "blau" -> "#0000FF"
                "grün", "green" -> "#00FF00"
                "gelb", "yellow" -> "#FFFF00"
                "schwarz", "black" -> "#000000"
                "braun", "brown" -> "#8B4513"
                "orange" -> "#FF8000"
                "lila", "purple" -> "#800080"
                else -> {
                    android.util.Log.w("StroopDisplay", "Unknown color from Master: $colorName, using default")
                    "#808080" // Gray for unknown colors - Master should provide proper mapping
                }
            }
        }

        // Validate timing values from Master
        if (stroopSettings.displayDurationMs <= 0) {
            throw IllegalArgumentException("Invalid display duration from Master: ${stroopSettings.displayDurationMs}")
        }
        if (stroopSettings.minIntervalMs <= 0 || stroopSettings.maxIntervalMs <= 0) {
            throw IllegalArgumentException("Invalid interval timing from Master: ${stroopSettings.minIntervalMs}-${stroopSettings.maxIntervalMs}")
        }
        if (stroopSettings.minIntervalMs > stroopSettings.maxIntervalMs) {
            throw IllegalArgumentException("Master config error: min interval > max interval")
        }

        // Create projector's base config using Master's exact specifications
        val baseConfig = com.research.projector.models.StroopConfig(
            stroopColors = stroopColors,
            tasks = emptyMap(), // Tasks are not needed for Stroop display
            taskLists = emptyMap(), // Task lists are not needed for Stroop display
            timing = com.research.projector.models.TimingConfig(
                stroopDisplayDuration = stroopSettings.displayDurationMs.toInt(),
                minInterval = stroopSettings.minIntervalMs.toInt(),
                maxInterval = stroopSettings.maxIntervalMs.toInt(),
                countdownDuration = (stroopSettings.countdownDurationMs / 1000).toInt()
            )
        )

        return ProjectorRuntimeConfig(baseConfig = baseConfig)
    }

    /**
     * Validate that Master provided minimum required configuration
     */
    private fun validateMinimumConfig(stroopSettings: StroopSettings): Boolean {
        // Master must provide at least 2 colors for valid Stroop test
        if (stroopSettings.colors.size < 2) {
            android.util.Log.e("StroopDisplay", "Master provided insufficient colors: ${stroopSettings.colors.size}")
            return false
        }

        // Master must provide positive timing values
        if (stroopSettings.displayDurationMs <= 0 ||
            stroopSettings.minIntervalMs <= 0 ||
            stroopSettings.maxIntervalMs <= 0 ||
            stroopSettings.countdownDurationMs <= 0) {
            android.util.Log.e("StroopDisplay", "Master provided invalid timing values")
            return false
        }

        // Interval range must be valid
        if (stroopSettings.minIntervalMs > stroopSettings.maxIntervalMs) {
            android.util.Log.e("StroopDisplay", "Master provided invalid interval range")
            return false
        }

        return true
    }

    /**
     * Send task status update to Master
     * FIXED: Use exact parameters from your NetworkMessage.kt definition
     */
    private suspend fun sendTaskStatusToMaster(taskId: String, status: TaskStatus) {
        try {
            val sessionId = networkService?.getCurrentSessionId() ?: return
            val elapsedTime = _taskExecutionState.value?.getElapsedTime() ?: 0L

            android.util.Log.d("StroopDisplay", "Creating TaskStatusMessage - sessionId: $sessionId, taskId: $taskId, status: $status, elapsedTime: $elapsedTime")

            // Your exact definition:
            // data class TaskStatusMessage(
            //     override val sessionId: String,           // ✓
            //     override val timestamp: Long = System.currentTimeMillis(), // ✓ has default
            //     val taskId: String,                       // ✓
            //     val status: TaskStatus,                   // ✓
            //     val elapsedTimeMs: Long                   // ✓
            // )

            // Use the imported TaskStatusMessage from shared.network.*
            val statusMessage = NetworkTaskStatusMessage(
                sessionId = sessionId,
                taskId = taskId,
                status = status,
                elapsedTimeMs = elapsedTime
            )

            networkService?.sendMessage(statusMessage)
            android.util.Log.d("StroopDisplay", "Successfully sent task status: $status")

        } catch (e: Exception) {
            android.util.Log.e("StroopDisplay", "TaskStatusMessage creation failed", e)

            // Debug: Try to see what NetworkTaskStatusMessage we're actually importing
            try {
                val messageClass = NetworkTaskStatusMessage::class.java
                android.util.Log.d("StroopDisplay", "NetworkTaskStatusMessage class: ${messageClass.name}")
                android.util.Log.d("StroopDisplay", "Constructors: ${messageClass.constructors.contentToString()}")
            } catch (debugError: Exception) {
                android.util.Log.e("StroopDisplay", "Debug info failed", debugError)
            }
        }
    }

    /**
     * Send error message to Master
     * FIXED: Use named parameters to avoid parameter order issues
     */
    private suspend fun sendErrorToMaster(errorCode: String, errorMessage: String) {
        try {
            val sessionId = networkService?.getCurrentSessionId() ?: return

            android.util.Log.d("StroopDisplay", "Creating ErrorMessage with named parameters")

            // Use named parameters to avoid constructor order issues
            val error = ErrorMessage(
                sessionId = sessionId,
                errorCode = errorCode,
                errorDescription = errorMessage,
                isFatal = false
            )

            networkService?.sendMessage(error)
            android.util.Log.d("StroopDisplay", "Successfully sent error message to Master")

        } catch (e: Exception) {
            android.util.Log.e("StroopDisplay", "Failed to send error to Master: ${e.message}", e)
        }
    }

    /**
     * Initialize the display with task execution parameters
     * ENHANCED: Now supports both manual and Master-controlled initialization
     */
    fun initialize(
        taskExecution: TaskExecutionState,
        runtimeConfig: ProjectorRuntimeConfig,
        stroopGenerator: StroopGenerator,
        displayWidth: Int,
        displayHeight: Int
    ) {
        this.runtimeConfig = runtimeConfig
        this.stroopGenerator = stroopGenerator
        this.stimulusSequenceNumber = 0
        this.isTaskRunning = true

        _taskExecutionState.value = taskExecution

        // Calculate optimal font sizes
        calculateFontSizes(runtimeConfig, displayWidth, displayHeight)

        // Send initial status to Master if controlled
        if (masterControlled) {
            viewModelScope.launch {
                sendTaskStatusToMaster(taskExecution.taskId, TaskStatus.COUNTDOWN)
            }
        }

        // Start the display sequence
        startDisplaySequence(taskExecution)
    }

    /**
     * Calculate optimal font sizes for display
     */
    private fun calculateFontSizes(runtimeConfig: ProjectorRuntimeConfig, width: Int, height: Int) {
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
     * FINAL FIX: countdownDuration is already in milliseconds, don't multiply by 1000
     */
    private fun startDisplaySequence(taskExecution: TaskExecutionState) {
        _displayState.value = StimulusDisplayState.COUNTDOWN

        val config = runtimeConfig ?: return
        val timing = config.getEffectiveTiming()

        android.util.Log.d("DisplaySequence", "=== DISPLAY SEQUENCE DEBUG ===")
        android.util.Log.d("DisplaySequence", "Timing config: $timing")
        android.util.Log.d("DisplaySequence", "Countdown duration from config: ${timing.countdownDuration}")

        // FINAL FIX: countdownDuration is already in milliseconds (4000ms), use directly
        val countdownDurationMs = timing.countdownDuration.toLong()
        android.util.Log.d("DisplaySequence", "Countdown duration calculated: ${countdownDurationMs}ms")

        // Create countdown state with correct timing
        val countdownState = CountdownState.initial(countdownDurationMs)
        android.util.Log.d("DisplaySequence", "CountdownState created:")
        android.util.Log.d("DisplaySequence", "  - Total duration: ${countdownState.totalDuration}ms")
        android.util.Log.d("DisplaySequence", "  - Current number: ${countdownState.currentNumber}")
        android.util.Log.d("DisplaySequence", "  - Remaining time: ${countdownState.remainingTime}ms")

        val updatedExecution = taskExecution.updateCountdown(countdownState)
        _taskExecutionState.value = updatedExecution

        // Start countdown sequence
        android.util.Log.d("DisplaySequence", "Starting countdown sequence")
        startCountdown(updatedExecution)
    }

    /**
     * Execute countdown sequence (3, 2, 1, 0)
     * ENHANCED: Complete debugging version to identify countdown stuck issue
     */
    private fun startCountdown(taskExecution: TaskExecutionState) {
        android.util.Log.d("Countdown", "=== STARTCOUNTDOWN CALLED ===")

        val countdownState = taskExecution.countdownState
        if (countdownState == null) {
            android.util.Log.e("Countdown", "❌ No countdown state in taskExecution!")
            _errorEvent.value = "No countdown state available"
            return
        }

        android.util.Log.d("Countdown", "=== COUNTDOWN STATE DEBUG ===")
        android.util.Log.d("Countdown", "Current number: ${countdownState.currentNumber}")
        android.util.Log.d("Countdown", "Total duration: ${countdownState.totalDuration}ms")
        android.util.Log.d("Countdown", "Remaining time: ${countdownState.remainingTime}ms")
        android.util.Log.d("Countdown", "Is complete: ${countdownState.isComplete}")
        android.util.Log.d("Countdown", "Is active: ${countdownState.isActive()}")

        val config = runtimeConfig
        if (config == null) {
            android.util.Log.e("Countdown", "❌ No runtime config available!")
            _errorEvent.value = "No runtime configuration"
            return
        }

        val timing = config.getEffectiveTiming()
        android.util.Log.d("Countdown", "=== TIMING CONFIG DEBUG ===")
        android.util.Log.d("Countdown", "Effective timing: $timing")
        android.util.Log.d("Countdown", "Countdown duration from config: ${timing.countdownDuration}")

        // Check if timing has countdownMillis() method or similar
        val countdownMillis = try {
            timing.countdownDuration.toLong()  // ✅ FIXED: Use directly, don't multiply by 1000 again
        } catch (e: Exception) {
            android.util.Log.e("Countdown", "Error getting countdown millis", e)
            4000L  // Fallback to 4 seconds
        }

        android.util.Log.d("Countdown", "Countdown millis calculated: ${countdownMillis}ms")
        val stepDuration = countdownMillis / 4
        android.util.Log.d("Countdown", "Step duration calculated: ${stepDuration}ms")

        if (stepDuration <= 0) {
            android.util.Log.e("Countdown", "❌ Invalid step duration: ${stepDuration}ms")
            _errorEvent.value = "Invalid countdown timing configuration"
            return
        }

        // Update display content to show current countdown number
        _displayContent.value = DisplayContent.Countdown(countdownState.currentNumber)
        android.util.Log.d("Countdown", "Display content set to: ${countdownState.currentNumber}")

        // Cancel any existing job
        currentJob?.let { job ->
            android.util.Log.d("Countdown", "Cancelling existing job: active=${job.isActive}, cancelled=${job.isCancelled}")
            job.cancel()
        }

        // Create new countdown job
        currentJob = viewModelScope.launch {
            android.util.Log.d("Countdown", "🚀 Starting coroutine - delay for ${countdownState.remainingTime}ms")

            try {
                // Use the remaining time from countdown state
                val delayTime = countdownState.remainingTime
                android.util.Log.d("Countdown", "About to delay for: ${delayTime}ms")

                delay(delayTime)

                android.util.Log.d("Countdown", "✅ Delay completed successfully!")

                // Check if countdown is complete
                if (countdownState.isComplete) {
                    android.util.Log.d("Countdown", "🎯 Countdown complete - starting Stroop sequence")
                    startStroopSequence(taskExecution)
                } else {
                    android.util.Log.d("Countdown", "⏭️ Moving to next countdown step")

                    // Calculate next countdown state
                    val nextCountdown = countdownState.getNext(stepDuration)
                    android.util.Log.d("Countdown", "Next countdown state:")
                    android.util.Log.d("Countdown", "  - Number: ${nextCountdown.currentNumber}")
                    android.util.Log.d("Countdown", "  - Remaining: ${nextCountdown.remainingTime}ms")
                    android.util.Log.d("Countdown", "  - Complete: ${nextCountdown.isComplete}")

                    // Update task execution with new countdown state
                    val updatedExecution = taskExecution.updateCountdown(nextCountdown)
                    _taskExecutionState.value = updatedExecution

                    // Recursive call for next step
                    android.util.Log.d("Countdown", "🔄 Calling startCountdown recursively")
                    startCountdown(updatedExecution)
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.w("Countdown", "⚠️ Countdown job was cancelled", e)
            } catch (e: Exception) {
                android.util.Log.e("Countdown", "❌ Error during countdown delay", e)
                _errorEvent.value = "Countdown error: ${e.message}"
            }
        }

        // Log job status immediately after creation
        android.util.Log.d("Countdown", "=== JOB STATUS DEBUG ===")
        android.util.Log.d("Countdown", "Job created: ${currentJob != null}")
        android.util.Log.d("Countdown", "Job active: ${currentJob?.isActive}")
        android.util.Log.d("Countdown", "Job cancelled: ${currentJob?.isCancelled}")
        android.util.Log.d("Countdown", "Job completed: ${currentJob?.isCompleted}")
        android.util.Log.d("Countdown", "ViewModelScope context: ${viewModelScope.coroutineContext}")

        android.util.Log.d("Countdown", "=== STARTCOUNTDOWN FINISHED ===")
    }

    /**
     * Start displaying Stroop stimuli sequence
     */
    private fun startStroopSequence(taskExecution: TaskExecutionState) {
        _displayState.value = StimulusDisplayState.DISPLAY

        // Clear countdown state
        val clearedExecution = taskExecution.updateCountdown(null)
        _taskExecutionState.value = clearedExecution

        // Send status update to Master
        if (masterControlled) {
            viewModelScope.launch {
                sendTaskStatusToMaster(taskExecution.taskId, TaskStatus.ACTIVE)
            }
        }

        generateAndDisplayNextStimulus(clearedExecution)
    }

    /**
     * Generate and display the next Stroop stimulus
     * ENHANCED: Now sends detailed information to Master
     */
    private fun generateAndDisplayNextStimulus(taskExecution: TaskExecutionState) {
        android.util.Log.d("StroopDisplay", "=== generateAndDisplayNextStimulus called ===")

        // Check if task should continue
        if (taskExecution.hasTimedOut() || !taskExecution.isActive || !isTaskRunning) {
            android.util.Log.d("StroopDisplay", "Task should not continue - completing task")
            completeTask(taskExecution)
            return
        }

        val generator = stroopGenerator
        if (generator == null) {
            android.util.Log.e("StroopDisplay", "StroopGenerator is null!")
            _errorEvent.value = "StroopGenerator is null"
            completeTask(taskExecution)
            return
        }

        // Generate new stimulus
        stimulusSequenceNumber++

        val timedStimulus = generator.generateStimulus(stimulusSequenceNumber)
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
        _taskExecutionState.value = updatedExecution

        // Update display state
        _displayState.value = StimulusDisplayState.DISPLAY
        _displayContent.value = DisplayContent.Stroop(timedStimulus.stimulus)

        // ENHANCED: Send detailed Stroop information to Master
        sendStroopStartedToMaster(timedStimulus)

        // Schedule stimulus completion
        currentJob = viewModelScope.launch {
            delay(timedStimulus.timing.displayDuration)

            // Complete the stimulus display
            onStimulusDisplayComplete(updatedExecution, timedStimulus)
        }
    }

    /**
     * Send detailed Stroop started message to Master
     * FIXED: Use ProjectorNetworkManager instead of direct service call
     */
    private fun sendStroopStartedToMaster(timedStimulus: TimedStroopStimulus) {
        Log.d("StroopDisplay", "=== ATTEMPTING TO SEND MESSAGE ===")
        Log.d("StroopDisplay", "Network service available: ${networkService != null}")
        Log.d("StroopDisplay", "Is connected: ${networkService?.isConnected()}")

        try {
            val sessionId = networkService?.getCurrentSessionId()
            Log.d("StroopDisplay", "Session ID: $sessionId")

            if (sessionId == null) {
                Log.e("StroopDisplay", "❌ No session ID - cannot send message")
                return
            }

            val taskId = currentTaskId ?: "1" // Use fallback task ID if Master didn't set one
            if (taskId == null) {
                Log.e("StroopDisplay", "❌ No task ID - cannot send message")
                return
            }

            val hexColor = String.format("#%06X", (0xFFFFFF and timedStimulus.stimulus.displayColor))

            // FIXED: Use ProjectorNetworkManager instead of direct service call
            ProjectorNetworkManager.sendStroopStarted(
                taskId = taskId,
                stroopIndex = stimulusSequenceNumber,
                word = timedStimulus.stimulus.colorWord,
                displayColor = hexColor,
                correctAnswer = timedStimulus.stimulus.displayColorName
            )

            Log.d("StroopDisplay", "✅ StroopStarted sent via ProjectorNetworkManager: ${timedStimulus.stimulus.displayColorName}")

        } catch (e: Exception) {
            Log.e("StroopDisplay", "❌ Error sending Stroop started to Master", e)
        }
    }

    /**
     * Send Stroop ended message to Master
     */
    private fun sendStroopEndedToMaster(stroopIndex: Int, endReason: StroopEndReason, duration: Long) {
        try {
            val sessionId = networkService?.getCurrentSessionId() ?: return
            val taskId = currentTaskId ?: return

            val stroopEnded = StroopEndedMessage(
                sessionId = sessionId,
                taskId = taskId,
                stroopIndex = stroopIndex,
                endReason = endReason,
                displayDuration = duration
            )

            viewModelScope.launch {
                networkService?.sendMessage(stroopEnded)
                android.util.Log.d("StroopDisplay", "Sent StroopEnded to Master: reason=$endReason")
            }

        } catch (e: Exception) {
            android.util.Log.e("StroopDisplay", "Error sending Stroop ended to Master", e)
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

        // Send Stroop ended message to Master
        val actualDuration = completedStimulus.timing.getActualDisplayDuration() ?: timedStimulus.timing.displayDuration
        sendStroopEndedToMaster(stimulusSequenceNumber, StroopEndReason.TIMEOUT, actualDuration)

        // LEGACY: Keep existing Stroop hidden message for compatibility
        sendStroopHiddenMessage()

        // Start interval period
        startInterval(updatedExecution, completedStimulus.timing.intervalDuration)
    }

    /**
     * Legacy Stroop hidden message (for compatibility)
     */
    private fun sendStroopHiddenMessage() {
        try {
            android.util.Log.d("StroopDisplay", "Sending legacy Stroop hidden message")
            ProjectorNetworkManager.sendStroopHidden()
        } catch (e: Exception) {
            android.util.Log.e("StroopDisplay", "Error sending Stroop hidden message", e)
        }
    }

    /**
     * Display interval (white screen) between stimuli
     */
    private fun startInterval(taskExecution: TaskExecutionState, intervalDuration: Long) {
        android.util.Log.d("StroopDisplay", "Starting interval - Duration: ${intervalDuration}ms")

        _displayState.value = StimulusDisplayState.INTERVAL
        _displayContent.value = DisplayContent.Interval
        _taskExecutionState.value = taskExecution

        currentJob = viewModelScope.launch {
            delay(intervalDuration)

            // Check task state after interval
            if (taskExecution.isActive && !taskExecution.hasTimedOut() && isTaskRunning) {
                val clearedExecution = taskExecution.clearCurrentStimulus()
                _taskExecutionState.value = clearedExecution
                generateAndDisplayNextStimulus(clearedExecution)
            } else {
                completeTask(taskExecution)
            }
        }
    }

    /**
     * Complete the task and return to task execution
     * ENHANCED: Now sends completion data to Master
     */
    private fun completeTask(taskExecution: TaskExecutionState, masterInitiated: Boolean = false) {
        currentJob?.cancel()
        isTaskRunning = false

        _displayState.value = StimulusDisplayState.COMPLETED
        _displayContent.value = DisplayContent.BlackScreen

        val completedExecution = if (!taskExecution.isCompleted) {
            taskExecution.complete()
        } else {
            taskExecution
        }

        _taskExecutionState.value = completedExecution

        // Send completion data to Master
        if (masterControlled && !masterInitiated) {
            sendTaskCompletionToMaster(completedExecution)
        }

        _taskCompletionEvent.value = TaskCompletionEvent(completedExecution)

        // Reset Master control state
        masterControlled = false
        currentTaskId = null
    }

    /**
     * Send task completion results to Master
     */
    private fun sendTaskCompletionToMaster(taskExecution: TaskExecutionState) {
        try {
            val sessionId = networkService?.getCurrentSessionId() ?: return
            val taskId = currentTaskId ?: taskExecution.taskId

            // Create Stroop results (placeholder - would be calculated from actual responses)
            val stroopResults = StroopResultsMessage(
                sessionId = sessionId,
                taskId = taskId,
                totalStroops = taskExecution.getStimulusCount(),
                correctResponses = 0, // TODO: Track actual responses from Master
                incorrectResponses = 0,
                averageReactionTimeMs = 0L,
                individualResults = emptyList()
            )

            viewModelScope.launch {
                // Send final status
                sendTaskStatusToMaster(taskId, TaskStatus.COMPLETED)

                // Send results
                networkService?.sendMessage(stroopResults)

                android.util.Log.d("StroopDisplay", "Sent task completion to Master: ${taskExecution.getStimulusCount()} stroops")
            }

        } catch (e: Exception) {
            android.util.Log.e("StroopDisplay", "Error sending task completion to Master", e)
        }
    }

    /**
     * Emergency stop (called from UI emergency button)
     * SIMPLIFIED: Only provides way back to main screen, no task controls
     */
    fun emergencyStop() {
        android.util.Log.d("StroopDisplay", "Emergency stop - returning to main screen")

        val currentExecution = _taskExecutionState.value
        if (currentExecution != null && masterControlled) {
            // Notify Master that we're stopping
            sendStroopEndedToMaster(stimulusSequenceNumber, StroopEndReason.TASK_STOPPED, 0L)
        }

        // Stop everything and return to main screen
        if (currentExecution != null) {
            completeTask(currentExecution, masterInitiated = true)
        }

        // Reset to initial state
        currentTaskId = null
        masterControlled = false
        isTaskRunning = false
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
     * Get current display dimensions (placeholder)
     */
    private fun getCurrentDisplayWidth(): Int = 1920  // TODO: Get from actual display
    private fun getCurrentDisplayHeight(): Int = 1080  // TODO: Get from actual display

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
     * Clear events after handling
     * ALTERNATIVE: Use postValue if direct assignment fails
     */
    fun clearTaskCompletionEvent() {
        try {
            _taskCompletionEvent.value = null
        } catch (e: Exception) {
            _taskCompletionEvent.postValue(null)
        }
    }

    fun clearErrorEvent() {
        try {
            _errorEvent.value = null
        } catch (e: Exception) {
            _errorEvent.postValue(null)
        }
    }

    override fun onCleared() {
        super.onCleared()
        currentJob?.cancel()
        isTaskRunning = false
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