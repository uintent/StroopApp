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
import com.research.projector.MainActivity
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
 * UPDATED: Now receives Master commands via MainActivity message forwarding
 * SIMPLIFIED: No longer directly listens to network messages - uses forwarding pattern
 * ENHANCED: Added timeout detection and Master notification with comprehensive debug logging
 * FIXED: Race condition in completeTask() method that prevented timeout messages from being sent
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

    // Network and task control state
    private var networkService: ProjectorNetworkService? = null
    private var currentTaskId: String? = null
    private var isTaskRunning = false
    private var masterControlled = false  // Whether task is controlled by Master

    init {
        Log.d("StroopDisplay", "🚀 StroopDisplayViewModel CREATED - instance: ${this.hashCode()}")
        // Initialize network service (without message listening)
        initializeNetworkService()
        // Register with MainActivity for message forwarding
        registerWithMainActivity()
    }

    /**
     * ✅ NEW: Register this ViewModel with MainActivity for message forwarding
     */
    private fun registerWithMainActivity() {
        try {
            val mainActivity = MainActivity.getInstance()
            if (mainActivity != null) {
                mainActivity.registerStroopViewModel(this)
                Log.d("StroopDisplay", "✅ Successfully registered with MainActivity")
            } else {
                Log.w("StroopDisplay", "⚠️ MainActivity instance not available for registration")
            }
        } catch (e: Exception) {
            Log.e("StroopDisplay", "❌ Failed to register with MainActivity", e)
        }
    }

    /**
     * Initialize network service (NO MESSAGE LISTENING - messages come via MainActivity)
     * SIMPLIFIED: Only gets network service reference, doesn't listen to messages
     */
    private fun initializeNetworkService() {
        Log.d("StroopDisplay", "=== INITIALIZING NETWORK SERVICE (NO MESSAGE LISTENING) ===")
        Log.d("StroopDisplay", "Thread: ${Thread.currentThread().name}")
        Log.d("StroopDisplay", "ViewModel instance: ${this.hashCode()}")

        try {
            networkService = ProjectorNetworkManager.getNetworkService(getApplication())
            Log.d("StroopDisplay", "✅ Network service obtained: ${networkService != null}")
            Log.d("StroopDisplay", "Network service instance: ${networkService?.hashCode()}")
            Log.d("StroopDisplay", "Network service class: ${networkService?.javaClass?.simpleName}")

            // Check if service is connected
            val isConnected = networkService?.isConnected() ?: false
            Log.d("StroopDisplay", "Service connected: $isConnected")

            // Get session ID for verification
            val sessionId = networkService?.getCurrentSessionId()
            Log.d("StroopDisplay", "Current session ID: $sessionId")

            // ❌ REMOVED: No longer listen to messages directly
            // Messages will be forwarded from MainActivity via handleXXXFromMaster() methods

            Log.d("StroopDisplay", "✅ Network service initialized (message forwarding mode)")

        } catch (e: Exception) {
            Log.e("StroopDisplay", "❌ Failed to initialize network service", e)
            Log.e("StroopDisplay", "Exception type: ${e.javaClass.simpleName}")
            Log.e("StroopDisplay", "Exception message: ${e.message}")
            e.printStackTrace()
            _errorEvent.value = "Network initialization failed: ${e.message}"
        }

        Log.d("StroopDisplay", "=== NETWORK SERVICE INITIALIZATION COMPLETE ===")
    }

    // ✅ NEW: Public methods for MainActivity to forward Master commands

    /**
     * Handle END_TASK command forwarded from MainActivity
     */
    fun handleEndTaskFromMaster(message: EndTaskMessage) {
        Log.d("StroopDisplay", "🛑 === RECEIVED END TASK FROM MAINACTIVITY ===")
        Log.d("StroopDisplay", "Task ID: ${message.taskId}")
        Log.d("StroopDisplay", "Current task ID: $currentTaskId")
        Log.d("StroopDisplay", "Is task running: $isTaskRunning")

        viewModelScope.launch {
            handleEndTaskCommand(message)
        }
    }

    /**
     * Handle PAUSE_TASK command forwarded from MainActivity
     */
    fun handlePauseTaskFromMaster(message: PauseTaskMessage) {
        Log.d("StroopDisplay", "⏸️ === RECEIVED PAUSE TASK FROM MAINACTIVITY ===")
        Log.d("StroopDisplay", "Task ID: ${message.taskId}")
        Log.d("StroopDisplay", "Current task ID: $currentTaskId")

        viewModelScope.launch {
            handlePauseTaskCommand(message)
        }
    }

    /**
     * Handle RESUME_TASK command forwarded from MainActivity
     */
    fun handleResumeTaskFromMaster(message: ResumeTaskMessage) {
        Log.d("StroopDisplay", "▶️ === RECEIVED RESUME TASK FROM MAINACTIVITY ===")
        Log.d("StroopDisplay", "Task ID: ${message.taskId}")
        Log.d("StroopDisplay", "Current task ID: $currentTaskId")

        viewModelScope.launch {
            handleResumeTaskCommand(message)
        }
    }

    /**
     * Handle RESET_TASK command forwarded from MainActivity
     */
    fun handleResetTaskFromMaster(message: TaskResetCommand) {
        Log.d("StroopDisplay", "🔄 === RECEIVED RESET TASK FROM MAINACTIVITY ===")
        Log.d("StroopDisplay", "Session ID: ${message.sessionId}")

        viewModelScope.launch {
            handleResetTaskCommand(message)
        }
    }

    /**
     * Handle START_TASK command from Master (still called directly for activity launch)
     * ENHANCED: Added comprehensive debug logging to trace masterControlled state
     */
    private suspend fun handleStartTaskCommand(message: StartTaskMessage) {
        Log.d("StroopDisplay", "=== HANDLING START TASK COMMAND ===")
        Log.d("StroopDisplay", "Starting task from Master: ${message.taskId}")
        Log.d("StroopDisplay", "Current masterControlled before: $masterControlled")
        Log.d("StroopDisplay", "Current currentTaskId before: $currentTaskId")

        try {
            currentTaskId = message.taskId
            masterControlled = true

            Log.d("StroopDisplay", "✅ Set masterControlled = true")
            Log.d("StroopDisplay", "✅ Set currentTaskId = ${message.taskId}")
            Log.d("StroopDisplay", "Current masterControlled after setting: $masterControlled")

            // Extract configuration from message - handle different property names
            val stroopConfig = try {
                // Try to access stroopSettings property
                val field = message::class.java.getDeclaredField("stroopSettings")
                field.isAccessible = true
                field.get(message) as StroopSettings
            } catch (e: Exception) {
                Log.e("StroopDisplay", "StartTaskMessage missing stroopSettings property", e)
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
                Log.e("StroopDisplay", "StartTaskMessage missing timeoutSeconds property", e)
                throw IllegalArgumentException("Master message missing required timeout")
            }

            Log.d("StroopDisplay", "Extracted timeout: ${timeoutSeconds}s")
            Log.d("StroopDisplay", "Extracted colors: ${stroopConfig.colors}")

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

            Log.d("StroopDisplay", "Created task execution with timeout: ${timeoutSeconds * 1000L}ms")

            // Create StroopGenerator with Master's configuration
            val stroopGen = StroopGenerator(masterRuntimeConfig)

            // Send confirmation to Master
            sendTaskStatusToMaster(message.taskId, TaskStatus.COUNTDOWN)

            Log.d("StroopDisplay", "Final masterControlled state: $masterControlled")
            Log.d("StroopDisplay", "Final currentTaskId state: $currentTaskId")

            // Start the display sequence with Master's configuration
            initialize(taskExecution, masterRuntimeConfig, stroopGen,
                getCurrentDisplayWidth(), getCurrentDisplayHeight())

            Log.d("StroopDisplay", "=== START TASK COMMAND COMPLETED SUCCESSFULLY ===")

        } catch (e: Exception) {
            Log.e("StroopDisplay", "❌ Error handling start task command - insufficient Master config", e)
            _errorEvent.postValue("Master configuration incomplete: ${e.message}")

            // Reset state on error
            masterControlled = false
            currentTaskId = null

            // Send error to Master - configuration is Master's responsibility
            sendErrorToMaster("INVALID_MASTER_CONFIG", "Configuration from Master is incomplete: ${e.message}")
        }
    }

    /**
     * Handle PAUSE_TASK command from Master
     * ENHANCED: Pause current Stroop display and timing
     */
    private suspend fun handlePauseTaskCommand(message: PauseTaskMessage) {
        Log.d("StroopDisplay", "=== HANDLING PAUSE TASK COMMAND ===")
        Log.d("StroopDisplay", "Pausing task: ${message.taskId}")
        Log.d("StroopDisplay", "Current task ID: $currentTaskId")
        Log.d("StroopDisplay", "Is task running: $isTaskRunning")

        if (message.taskId == currentTaskId && isTaskRunning) {
            Log.d("StroopDisplay", "✅ Valid pause command - pausing immediately")

            // 🎯 PAUSE current operations
            currentJob?.cancel()
            Log.d("StroopDisplay", "Cancelled current display job")

            // 🎯 Update display state to show paused status
            _displayState.value = StimulusDisplayState.PAUSED
            _displayContent.value = DisplayContent.PausedScreen
            Log.d("StroopDisplay", "Set display to paused screen")

            // 🎯 Send confirmation to Master
            sendTaskStatusToMaster(message.taskId, TaskStatus.PAUSED)
            Log.d("StroopDisplay", "✅ Sent pause confirmation to Master")

        } else {
            Log.w("StroopDisplay", "❌ Pause command ignored - task ID mismatch or not running")
            Log.w("StroopDisplay", "Message task ID: ${message.taskId}, Current: $currentTaskId, Running: $isTaskRunning")
        }

        Log.d("StroopDisplay", "=== PAUSE TASK COMMAND HANDLING COMPLETE ===")
    }

    /**
     * Handle RESUME_TASK command from Master
     * ENHANCED: Resume paused Stroop display from current state
     */
    private suspend fun handleResumeTaskCommand(message: ResumeTaskMessage) {
        Log.d("StroopDisplay", "=== HANDLING RESUME TASK COMMAND ===")
        Log.d("StroopDisplay", "Resuming task: ${message.taskId}")
        Log.d("StroopDisplay", "Current task ID: $currentTaskId")
        Log.d("StroopDisplay", "Current display state: ${_displayState.value}")

        if (message.taskId == currentTaskId && _displayState.value == StimulusDisplayState.PAUSED) {
            Log.d("StroopDisplay", "✅ Valid resume command - resuming from pause")

            // 🎯 Get current task execution state
            val currentExecution = _taskExecutionState.value
            if (currentExecution != null && !currentExecution.hasTimedOut()) {
                Log.d("StroopDisplay", "Resuming task execution")

                // 🎯 Send status update to Master
                sendTaskStatusToMaster(message.taskId, TaskStatus.ACTIVE)

                // 🎯 Resume from where we left off
                when {
                    // If we were in countdown, resume countdown
                    currentExecution.countdownState != null && !currentExecution.countdownState.isComplete -> {
                        Log.d("StroopDisplay", "Resuming countdown from: ${currentExecution.countdownState.currentNumber}")
                        _displayState.value = StimulusDisplayState.COUNTDOWN
                        startCountdown(currentExecution)
                    }

                    // If we were displaying a Stroop, resume Stroop sequence
                    currentExecution.currentStimulus != null -> {
                        Log.d("StroopDisplay", "Resuming Stroop sequence")
                        _displayState.value = StimulusDisplayState.DISPLAY
                        generateAndDisplayNextStimulus(currentExecution)
                    }

                    // If we were in interval, resume interval or generate next Stroop
                    else -> {
                        Log.d("StroopDisplay", "Resuming with next Stroop generation")
                        _displayState.value = StimulusDisplayState.DISPLAY
                        generateAndDisplayNextStimulus(currentExecution)
                    }
                }

                Log.d("StroopDisplay", "✅ Task resumed successfully")
            } else {
                Log.w("StroopDisplay", "❌ Cannot resume - task execution invalid or timed out")
                sendErrorToMaster("RESUME_FAILED", "Task execution invalid or timed out")
            }

        } else {
            Log.w("StroopDisplay", "❌ Resume command ignored - task ID mismatch or not paused")
            Log.w("StroopDisplay", "Message task ID: ${message.taskId}, Current: $currentTaskId")
            Log.w("StroopDisplay", "Current state: ${_displayState.value}, Expected: PAUSED")
        }

        Log.d("StroopDisplay", "=== RESUME TASK COMMAND HANDLING COMPLETE ===")
    }

    /**
     * Handle END_TASK command from Master
     * ENHANCED: Immediately stop Stroop display and send confirmation
     */
    private suspend fun handleEndTaskCommand(message: EndTaskMessage) {
        Log.d("StroopDisplay", "=== HANDLING END TASK COMMAND ===")

        // Extract reason from message - handle different property names
        val reasonText = try {
            val field = message::class.java.getDeclaredField("reason")
            field.isAccessible = true
            field.get(message)?.toString() ?: "UNKNOWN"
        } catch (e: Exception) {
            Log.w("StroopDisplay", "EndTaskMessage missing reason property", e)
            "MASTER_STOP"
        }

        Log.d("StroopDisplay", "Master ending task: ${message.taskId}, reason: $reasonText")
        Log.d("StroopDisplay", "Current task ID: $currentTaskId")
        Log.d("StroopDisplay", "Is task running: $isTaskRunning")

        if (message.taskId == currentTaskId && isTaskRunning) {
            Log.d("StroopDisplay", "✅ Valid end task command - stopping immediately")

            // 🎯 IMMEDIATELY stop current Stroop display
            currentJob?.cancel()
            Log.d("StroopDisplay", "Cancelled current display job")

            // 🎯 IMMEDIATELY go to black screen
            _displayState.value = StimulusDisplayState.COMPLETED
            _displayContent.value = DisplayContent.BlackScreen
            Log.d("StroopDisplay", "Set display to black screen")

            // 🎯 Send final Stroop ended message if we were in middle of Stroop
            if (_displayState.value == StimulusDisplayState.DISPLAY) {
                Log.d("StroopDisplay", "Sending final StroopEnded message for interrupted Stroop")
                sendStroopEndedToMaster(stimulusSequenceNumber, StroopEndReason.TASK_STOPPED, 0L)
            }

            // Get current execution for completion
            val currentExecution = _taskExecutionState.value
            if (currentExecution != null) {
                Log.d("StroopDisplay", "Completing task with Master initiation")
                completeTask(currentExecution, masterInitiated = true)
            }

            // 🎯 Send confirmation to Master that we've stopped
            sendTaskStatusToMaster(message.taskId, TaskStatus.COMPLETED)
            Log.d("StroopDisplay", "✅ Sent completion confirmation to Master")

        } else {
            Log.w("StroopDisplay", "❌ End task command ignored - task ID mismatch or not running")
            Log.w("StroopDisplay", "Message task ID: ${message.taskId}, Current: $currentTaskId, Running: $isTaskRunning")
        }

        Log.d("StroopDisplay", "=== END TASK COMMAND HANDLING COMPLETE ===")
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
     * ENHANCED: Send task timeout message to Master when task times out
     * FIXED: Added comprehensive debugging and improved error handling
     */
    private suspend fun sendTaskTimeoutToMaster(taskExecution: TaskExecutionState) {
        Log.d("StroopDisplay", "=== SENDING TASK TIMEOUT TO MASTER ===")
        Log.d("StroopDisplay", "Network service: ${networkService != null}")
        Log.d("StroopDisplay", "Network service class: ${networkService?.javaClass?.simpleName}")

        try {
            val sessionId = networkService?.getCurrentSessionId()
            Log.d("StroopDisplay", "Session ID from service: $sessionId")

            if (sessionId == null) {
                Log.e("StroopDisplay", "❌ No session ID - cannot send timeout message")
                return
            }

            val taskId = currentTaskId ?: "1"
            Log.d("StroopDisplay", "Task ID: $taskId")
            Log.d("StroopDisplay", "Elapsed time: ${taskExecution.getElapsedTime()}ms")
            Log.d("StroopDisplay", "Stroop count: ${taskExecution.getStimulusCount()}")

            val timeoutMessage = TaskTimeoutMessage(
                sessionId = sessionId,
                taskId = taskId,
                actualDuration = taskExecution.getElapsedTime(),
                stroopsDisplayed = taskExecution.getStimulusCount()
            )

            Log.d("StroopDisplay", "Created timeout message: $timeoutMessage")
            Log.d("StroopDisplay", "About to send message...")

            networkService?.sendMessage(timeoutMessage)

            Log.d("StroopDisplay", "✅ Task timeout message sent successfully!")

        } catch (e: Exception) {
            Log.e("StroopDisplay", "❌ Error sending task timeout to Master", e)
            Log.e("StroopDisplay", "Exception type: ${e.javaClass.simpleName}")
            Log.e("StroopDisplay", "Exception message: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Initialize the display with task execution parameters
     * ENHANCED: Now supports both manual and Master-controlled initialization
     * FIXED: Added Master control parameters
     */
    fun initialize(
        taskExecution: TaskExecutionState,
        runtimeConfig: ProjectorRuntimeConfig,
        stroopGenerator: StroopGenerator,
        displayWidth: Int,
        displayHeight: Int,
        isMasterControlled: Boolean = false,  // ✅ ADD THIS PARAMETER
        masterTaskId: String? = null          // ✅ ADD THIS PARAMETER
    ) {
        android.util.Log.d("StroopDisplay", "=== INITIALIZE CALLED ===")
        android.util.Log.d("StroopDisplay", "Master controlled param: $isMasterControlled")
        android.util.Log.d("StroopDisplay", "Master task ID param: $masterTaskId")
        android.util.Log.d("StroopDisplay", "Current masterControlled before: $masterControlled")

        this.runtimeConfig = runtimeConfig
        this.stroopGenerator = stroopGenerator
        this.stimulusSequenceNumber = 0
        this.isTaskRunning = true

        // ✅ SET MASTER CONTROL STATE FROM PARAMETERS
        if (isMasterControlled) {
            this.masterControlled = true
            this.currentTaskId = masterTaskId ?: taskExecution.taskId
            android.util.Log.d("StroopDisplay", "✅ MASTER CONTROLLED TASK - masterControlled set to TRUE")
            android.util.Log.d("StroopDisplay", "Master Task ID: ${this.currentTaskId}")
        } else {
            this.masterControlled = false
            this.currentTaskId = null
            android.util.Log.d("StroopDisplay", "Manual task - not Master controlled")
        }

        android.util.Log.d("StroopDisplay", "Final masterControlled state: $masterControlled")
        android.util.Log.d("StroopDisplay", "Final currentTaskId state: $currentTaskId")

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
                    android.util.Log.d("Countdown", "⭐ Moving to next countdown step")

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
     * FIXED: Properly handle Master-controlled vs timeout-initiated completion
     */
    private fun generateAndDisplayNextStimulus(taskExecution: TaskExecutionState) {
        android.util.Log.d("StroopDisplay", "=== generateAndDisplayNextStimulus called ===")

        // Check if task should continue
        if (taskExecution.hasTimedOut() || !taskExecution.isActive || !isTaskRunning) {
            android.util.Log.d("StroopDisplay", "Task should not continue - completing task")

            // FIXED: If Master is controlling and task timed out, don't mark as masterInitiated
            val isMasterInitiated = !taskExecution.hasTimedOut() && masterControlled
            completeTask(taskExecution, masterInitiated = isMasterInitiated)
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
     * ENHANCED: Added more end reasons for different scenarios
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
                Log.d("StroopDisplay", "Sent StroopEnded to Master: reason=$endReason, duration=${duration}ms")
            }

        } catch (e: Exception) {
            Log.e("StroopDisplay", "Error sending Stroop ended to Master", e)
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
     * ENHANCED: Complete the task and return to task execution
     * SIMPLIFIED: Projector can only timeout or be stopped by Master
     */
    private fun completeTask(taskExecution: TaskExecutionState, masterInitiated: Boolean = false) {
        Log.d("StroopDisplay", "=== COMPLETING TASK ===")
        Log.d("StroopDisplay", "Master controlled: $masterControlled")
        Log.d("StroopDisplay", "Master initiated: $masterInitiated")

        // ✅ SIMPLIFIED LOGIC: Check timeout BEFORE marking as complete
        val wasTimedOut = taskExecution.hasTimedOut()
        Log.d("StroopDisplay", "Task timed out: $wasTimedOut")
        Log.d("StroopDisplay", "Elapsed time: ${taskExecution.getElapsedTime()}ms")
        Log.d("StroopDisplay", "Timeout duration: ${taskExecution.timeoutDuration}ms")

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

        // ✅ SIMPLIFIED LOGIC: Only two scenarios for Projector
        if (masterControlled && !masterInitiated) {
            Log.d("StroopDisplay", "Master controlled task - sending notification")

            viewModelScope.launch {
                try {
                    if (wasTimedOut) {
                        Log.d("StroopDisplay", "⏰ Task timed out - notifying Master")
                        sendTaskTimeoutToMaster(completedExecution)
                    } else {
                        Log.d("StroopDisplay", "❌ ERROR: Projector task completed without timeout or Master command")
                        Log.d("StroopDisplay", "This should never happen - sending timeout anyway")
                        sendTaskTimeoutToMaster(completedExecution)
                    }
                    Log.d("StroopDisplay", "✅ Master notification sent successfully")
                } catch (e: Exception) {
                    Log.e("StroopDisplay", "❌ Error sending notification to Master", e)
                } finally {
                    Log.d("StroopDisplay", "Resetting Master control state")
                    masterControlled = false
                    currentTaskId = null
                }
            }
        } else {
            Log.d("StroopDisplay", "Not sending notification - masterControlled=$masterControlled, masterInitiated=$masterInitiated")
            masterControlled = false
            currentTaskId = null
        }

        _taskCompletionEvent.value = TaskCompletionEvent(completedExecution)
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

        // ✅ NEW: Unregister from MainActivity when ViewModel is destroyed
        try {
            val mainActivity = MainActivity.getInstance()
            mainActivity?.unregisterStroopViewModel()
            Log.d("StroopDisplay", "✅ Successfully unregistered from MainActivity")
        } catch (e: Exception) {
            Log.e("StroopDisplay", "❌ Failed to unregister from MainActivity", e)
        }

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
    object PausedScreen : DisplayContent()
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