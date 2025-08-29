package com.research.master

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.research.master.databinding.ActivityColorDisplayBinding
import com.research.master.network.NetworkManager
import com.research.master.network.TaskControlNetworkManager
import com.research.master.network.TaskState
import com.research.master.utils.SessionManager
import com.research.master.utils.TaskCompletionData
import com.research.master.utils.FileManager
import com.research.master.utils.ConfigLoadResult
import com.research.master.utils.DebugLogger
import com.research.shared.models.RuntimeConfig
import com.research.shared.network.StroopDisplayMessage
import com.research.shared.network.StroopHiddenMessage
import com.research.shared.network.HeartbeatMessage
import com.research.shared.network.HandshakeResponseMessage
import com.research.shared.network.StroopStartedMessage
import com.research.shared.network.StroopEndedMessage
import com.research.shared.network.TaskTimeoutMessage
import com.research.shared.network.PauseTaskMessage
import com.research.shared.network.ResumeTaskMessage
import com.research.shared.network.TaskResetCommand
import com.research.shared.network.MessageType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.google.android.material.snackbar.Snackbar
import androidx.core.content.ContextCompat

/**
 * Task Control Screen - displays task information and provides controls for task execution
 * UPDATED: Now uses Int task numbers and iteration counter support
 * Shows current Stroop word from Projector and allows moderator to control task flow
 * Enhanced with complete data collection system for task metrics and timing
 * Collects individual stroop responses in memory, aggregates when task ends
 * FIXED: Now loads and passes RuntimeConfig to ensure proper color configuration
 */
class ColorDisplayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityColorDisplayBinding
    private val networkClient by lazy { NetworkManager.getNetworkClient(this) }
    private lateinit var taskControlManager: TaskControlNetworkManager
    private lateinit var fileManager: FileManager

    // Task information from intent - UPDATED to support both Int and String
    private var taskNumber: Int = 0           // NEW: Int task number for FileManager
    private var taskId: String? = null        // Keep for display purposes
    private var taskLabel: String? = null
    private var taskText: String? = null
    private var taskTimeout: Int = 0
    private var isIndividualTask: Boolean = true
    private var iterationCounter: Int = 0     // NEW: Track current iteration

    // FIXED: Add RuntimeConfig to pass proper color configuration
    private var runtimeConfig: RuntimeConfig? = null

    // Task state
    private var currentStroopWord: String? = null
    private var currentSessionId: String? = null
    private var isStroopActive = false
    private var responseButtonsActive = false
    private var isTaskTimedOut = false
    private var isTaskRunning = false  // Track if task is currently active

    // Data collection system
    private val stroopResponses = mutableListOf<StroopResponse>()
    private var activityOpenTime: Long = 0
    private var taskActualStartTime: Long = 0 // After countdown ends
    private var countdownDuration: Long = 4000L // Default fallback, will be loaded from config
    private var currentStroopStartTime: Long = 0
    private var currentStroopIndex: Int = 0

    /**
     * Data structure for individual stroop responses (in-memory only)
     */
    data class StroopResponse(
        val stroopIndex: Int,
        val word: String,
        val correctAnswer: String,
        val response: String?, // "CORRECT", "INCORRECT", or null if missed
        val reactionTimeMs: Long,
        val startTime: Long,
        val responseTime: Long? // When response was recorded, null if missed
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize DebugLogger
        DebugLogger.initialize(this)

        // Record when activity opened
        activityOpenTime = System.currentTimeMillis()

        binding = ActivityColorDisplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize FileManager and task control manager
        fileManager = FileManager(this)
        taskControlManager = TaskControlNetworkManager(networkClient)

        // Load countdown duration from config EARLY
        loadCountdownFromConfig()

        // Get task information from intent - UPDATED
        extractTaskInfo()

        // Get iteration counter for this task
        getIterationCounter()

        // Set up custom toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = taskLabel ?: getString(R.string.color_display_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Check connection status
        if (!NetworkManager.isConnected()) {
            showNotConnected()
            return
        }

        // Get current session ID
        currentSessionId = NetworkManager.getCurrentSessionId()
        if (currentSessionId == null) {
            showSessionError()
            return
        }

        // Initialize UI
        setupTaskInfo()
        setupControlButtons()
        observeNetworkMessages()
        observeTaskState()

        // Set initial state
        showTaskReady()

        DebugLogger.d("TaskControl", "Activity opened at: $activityOpenTime, countdown duration: ${countdownDuration}ms")
        DebugLogger.d("TaskControl", "Task: $taskNumber (iteration $iterationCounter)")
    }

    /**
     * FIXED: Load countdown duration and RuntimeConfig from configuration using FileManager
     * Now stores the complete config for passing to startTask()
     */
    private fun loadCountdownFromConfig() {
        lifecycleScope.launch {
            try {
                when (val result = fileManager.loadConfiguration()) {
                    is ConfigLoadResult.Success -> {
                        runtimeConfig = result.config

                        DebugLogger.d("TaskControl", "=== LOADED CONFIG DEBUG ===")
                        DebugLogger.d("TaskControl", "Colors loaded: ${result.config.baseConfig.stroopColors.size}")
                        result.config.baseConfig.stroopColors.forEach { (name, hex) ->
                            DebugLogger.d("TaskControl", "  Config color: '$name' -> '$hex'")
                        }

                        val configCountdown = result.config.getEffectiveTiming().countdownDuration * 1000L
                        countdownDuration = configCountdown

                        DebugLogger.d("TaskControl", "Countdown duration loaded: ${countdownDuration}ms")
                        DebugLogger.d("TaskControl", "Colors available: ${result.config.baseConfig.stroopColors.size}")
                    }
                    is ConfigLoadResult.Error -> {
                        DebugLogger.w("TaskControl", "Config loading failed: ${fileManager.getErrorMessage(result.error)}")
                        runtimeConfig = null
                    }
                }
            } catch (e: Exception) {
                DebugLogger.e("TaskControl", "Error loading countdown duration from config, using default: ${countdownDuration}ms", e)
                runtimeConfig = null
            }
        }
    }

    /**
     * Extract task information from intent - UPDATED to handle both Int and String
     */
    private fun extractTaskInfo() {
        // Try to get taskNumber first (new format)
        taskNumber = intent.getIntExtra("TASK_NUMBER", 0)

        // Fallback: try to convert TASK_ID if taskNumber is 0
        if (taskNumber == 0) {
            val taskIdString = intent.getStringExtra("TASK_ID")
            if (taskIdString != null) {
                try {
                    taskNumber = taskIdString.toInt()
                    DebugLogger.d("TaskControl", "Converted TASK_ID '$taskIdString' to taskNumber $taskNumber")
                } catch (e: NumberFormatException) {
                    DebugLogger.e("TaskControl", "Invalid TASK_ID format: $taskIdString, expected integer")
                    // Set a default or show error
                    taskNumber = 1
                }
            }
        }

        // Keep taskId for display purposes
        taskId = intent.getStringExtra("TASK_ID") ?: taskNumber.toString()
        taskLabel = intent.getStringExtra("TASK_LABEL")
        taskText = intent.getStringExtra("TASK_TEXT")
        taskTimeout = intent.getIntExtra("TASK_TIMEOUT", 0)
        isIndividualTask = intent.getBooleanExtra("IS_INDIVIDUAL_TASK", true)

        DebugLogger.d("TaskControl", "Task info: Number=$taskNumber, ID=$taskId, Label=$taskLabel, Timeout=${taskTimeout}s")
    }

    /**
     * Get iteration counter for this task - NEW
     */
    private fun getIterationCounter() {
        lifecycleScope.launch {
            try {
                iterationCounter = SessionManager.getNextIterationCounter(taskNumber)
                DebugLogger.d("TaskControl", "Got iteration counter for task $taskNumber: $iterationCounter")
            } catch (e: Exception) {
                DebugLogger.e("TaskControl", "Error getting iteration counter, using 0", e)
                iterationCounter = 0
            }
        }
    }

    private fun setupTaskInfo() {
        binding.textTaskTitle.text = taskLabel ?: getString(R.string.color_display_unknown_task)
        binding.textTaskDescription.text = taskText ?: getString(R.string.color_display_no_description)
        binding.textTaskTimeout.text = getString(R.string.color_display_timeout_format, taskTimeout)
    }

    private fun setupControlButtons() {
        // Task control buttons
        binding.btnTriggerStroop.setOnClickListener { startTask() }
        binding.btnPauseTask.setOnClickListener { pauseTask() }
        binding.btnResumeTask.setOnClickListener { resumeTask() }
        binding.btnResetTask.setOnClickListener { resetTask() }

        // Task completion buttons
        binding.btnTaskSuccess.setOnClickListener { completeTask("Success") }
        binding.btnTaskFailed.setOnClickListener { completeTask("Failed") }
        binding.btnTaskGivenUp.setOnClickListener { completeTask("Given up") }

        // Response recording buttons
        binding.btnResponseCorrect.setOnClickListener { recordParticipantResponse(true) }
        binding.btnResponseIncorrect.setOnClickListener { recordParticipantResponse(false) }

        // Return to tasks button (initially hidden)
        binding.btnReturnToTasks.setOnClickListener { returnToTaskSelection() }

        // Initial button states
        updateButtonStates(TaskState.Ready)
        setResponseButtonsState(false)

        // Initially hide return button
        binding.btnReturnToTasks.visibility = View.GONE
    }

    private fun observeTaskState() {
        lifecycleScope.launch {
            taskControlManager.currentTaskState.collectLatest { taskState ->
                taskState?.let {
                    updateButtonStates(it)
                    handleTaskStateForDataCollection(it)
                }
            }
        }

        lifecycleScope.launch {
            taskControlManager.lastTaskError.collectLatest { error ->
                error?.let {
                    Snackbar.make(binding.root, getString(R.string.color_display_task_error_snackbar, it), Snackbar.LENGTH_LONG).show()
                    taskControlManager.clearError()
                }
            }
        }
    }

    /**
     * Handle task state changes for data collection
     */
    private fun handleTaskStateForDataCollection(taskState: TaskState) {
        when (taskState) {
            is TaskState.Active -> {
                // Task has started (countdown finished)
                if (taskActualStartTime == 0L) {
                    taskActualStartTime = System.currentTimeMillis()
                    DebugLogger.d("TaskControl", "Task actually started at: $taskActualStartTime (${taskActualStartTime - activityOpenTime}ms after activity opened)")
                }
            }
            is TaskState.Completed -> {
                // Task completed - will be handled in completeTask method
                DebugLogger.d("TaskControl", "Task state shows completed: ${taskState.endCondition}")
            }
            else -> {
                // Other states don't need special data collection handling
            }
        }
    }

    private fun updateButtonStates(taskState: TaskState) {
        // Don't update button states if task has timed out (timeout state overrides everything)
        if (isTaskTimedOut) {
            return
        }

        when (taskState) {
            is TaskState.Ready -> {
                binding.btnTriggerStroop.isEnabled = true
                binding.btnPauseTask.isEnabled = false
                binding.btnResumeTask.isEnabled = false
                binding.btnResetTask.isEnabled = false
                binding.btnTaskSuccess.isEnabled = false
                binding.btnTaskFailed.isEnabled = false
                binding.btnTaskGivenUp.isEnabled = false
                binding.btnReturnToTasks.visibility = View.GONE
            }

            is TaskState.Starting, is TaskState.CountdownActive -> {
                binding.btnTriggerStroop.isEnabled = false
                binding.btnPauseTask.isEnabled = false
                binding.btnResumeTask.isEnabled = false
                binding.btnResetTask.isEnabled = true
                binding.btnTaskSuccess.isEnabled = false
                binding.btnTaskFailed.isEnabled = false
                binding.btnTaskGivenUp.isEnabled = false
                binding.btnReturnToTasks.visibility = View.GONE

                binding.textStroopStatus.text = getString(R.string.color_display_starting_countdown)
            }

            is TaskState.Active -> {
                binding.btnTriggerStroop.isEnabled = false
                binding.btnPauseTask.isEnabled = true
                binding.btnResumeTask.isEnabled = false
                binding.btnResetTask.isEnabled = true
                binding.btnTaskSuccess.isEnabled = true
                binding.btnTaskFailed.isEnabled = true
                binding.btnTaskGivenUp.isEnabled = true
                binding.btnReturnToTasks.visibility = View.GONE

                if (binding.textStroopStatus.text != getString(R.string.color_display_stroop_active, currentStroopIndex)) {
                    binding.textStroopStatus.text = getString(R.string.color_display_task_active_waiting)
                }
            }

            is TaskState.Paused -> {
                binding.btnTriggerStroop.isEnabled = false
                binding.btnPauseTask.isEnabled = false
                binding.btnResumeTask.isEnabled = true
                binding.btnResetTask.isEnabled = true
                binding.btnTaskSuccess.isEnabled = true
                binding.btnTaskFailed.isEnabled = true
                binding.btnTaskGivenUp.isEnabled = true
                binding.btnReturnToTasks.visibility = View.GONE

                binding.textStroopStatus.text = getString(R.string.color_display_task_paused)
            }

            is TaskState.Completed -> {
                binding.btnTriggerStroop.isEnabled = true
                binding.btnPauseTask.isEnabled = false
                binding.btnResumeTask.isEnabled = false
                binding.btnResetTask.isEnabled = false
                binding.btnTaskSuccess.isEnabled = false
                binding.btnTaskFailed.isEnabled = false
                binding.btnTaskGivenUp.isEnabled = false
                binding.btnReturnToTasks.visibility = View.GONE

                binding.textStroopStatus.text = getString(R.string.color_display_task_completed, taskState.endCondition)

                // Show completion message
                Snackbar.make(
                    binding.root,
                    getString(R.string.color_display_task_completed_snackbar, taskState.endCondition),
                    Snackbar.LENGTH_LONG
                ).show()
            }

            is TaskState.Error -> {
                binding.btnTriggerStroop.isEnabled = true
                binding.btnPauseTask.isEnabled = false
                binding.btnResumeTask.isEnabled = false
                binding.btnResetTask.isEnabled = false
                binding.btnTaskSuccess.isEnabled = false
                binding.btnTaskFailed.isEnabled = false
                binding.btnTaskGivenUp.isEnabled = false
                binding.btnReturnToTasks.visibility = View.VISIBLE

                binding.textStroopStatus.text = getString(R.string.color_display_task_error, taskState.errorType)
            }
        }
    }

    /**
     * Enhanced message observation with data collection
     */
    private fun observeNetworkMessages() {
        DebugLogger.d("TaskControl", "Setting up message listener with data collection")

        lifecycleScope.launch {
            try {
                networkClient.receiveMessages().collectLatest { message ->
                    DebugLogger.d("TaskControl", "Message received: ${message::class.java.simpleName}")

                    // Only process messages if activity is resumed
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        when (message) {
                            is TaskTimeoutMessage -> {
                                handleTaskTimeout(message)
                            }

                            // Enhanced Stroop messages with data collection
                            is StroopStartedMessage -> {
                                handleStroopStarted(message)
                            }

                            is StroopEndedMessage -> {
                                handleStroopEnded(message)
                            }

                            // LEGACY: Keep old message handlers for compatibility
                            is StroopDisplayMessage -> {
                                handleStroopDisplay(message)
                            }

                            is StroopHiddenMessage -> {
                                handleStroopHidden()
                            }

                            // Connection management messages
                            is HeartbeatMessage -> {
                                DebugLogger.d("TaskControl", "Heartbeat received")
                            }

                            is HandshakeResponseMessage -> {
                                DebugLogger.d("TaskControl", "Handshake response received")
                            }

                            else -> {
                                DebugLogger.w("TaskControl", "Unmatched message: ${message::class.java.name}")
                                taskControlManager.handleTaskMessage(message)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLogger.e("TaskControl", "Critical error in message listener", e)
            }
        }
    }

    /**
     * Handle task timeout with raw data handoff
     * SPECIAL CASE: If stroop is active, allow 1 second grace period for response
     */
    private fun handleTaskTimeout(message: TaskTimeoutMessage) {
        DebugLogger.d("TaskControl", "Task timed out: ${message.taskId}, duration: ${message.actualDuration}ms")

        if (isTaskTimedOut) {
            DebugLogger.w("TaskControl", "Task already timed out, ignoring duplicate")
            return
        }

        isTaskTimedOut = true

        val isStroopActiveAtTimeout = isStroopActive && currentStroopWord != null

        if (isStroopActiveAtTimeout) {
            DebugLogger.d("TaskControl", "TIMEOUT WITH ACTIVE STROOP: Allowing 1 second grace period for response")

            // Disable all control buttons but KEEP response buttons active
            disableControlButtonsOnly()
            binding.textStroopStatus.text = getString(R.string.color_display_timeout_final_response)

            // Schedule navigation after 1 second grace period
            binding.root.postDelayed({
                DebugLogger.d("TaskControl", "Grace period ended - checking if response was recorded")

                // Check if response was recorded during grace period
                val responseRecordedInGrace = stroopResponses.any {
                    it.stroopIndex == currentStroopIndex && it.responseTime != null && it.responseTime!! > System.currentTimeMillis() - 1000
                }

                if (!responseRecordedInGrace) {
                    DebugLogger.d("TaskControl", "No response during grace period - marking stroop as missed")
                    recordMissedStroop()
                }

                DebugLogger.d("TaskControl", "Navigating to TaskSummary after grace period")
                navigateToTaskSummary("Timed Out")
            }, 1000) // 1 second grace period

        } else {
            DebugLogger.d("TaskControl", "TIMEOUT WITHOUT ACTIVE STROOP: Navigating immediately")
            // No active stroop - navigate immediately
            navigateToTaskSummary("Timed Out")
        }

        // Notify TaskControlManager in background
        try {
            taskControlManager.notifyTaskTimeout(
                message.taskId,
                message.actualDuration,
                message.stroopsDisplayed
            )
        } catch (e: Exception) {
            DebugLogger.e("TaskControl", "Error notifying TaskControlManager of timeout", e)
            // Don't block navigation on network error
        }
    }

    /**
     * Handle enhanced Stroop started message with data collection
     */
    private fun handleStroopStarted(message: StroopStartedMessage) {
        if (isTaskTimedOut) {
            DebugLogger.d("TaskControl", "Ignoring StroopStarted - task has timed out")
            return
        }

        DebugLogger.d("TaskControl", "Stroop started: #${message.stroopIndex}, word='${message.word}', answer='${message.correctAnswer}'")

        // Record stroop start for timing
        currentStroopStartTime = System.currentTimeMillis()
        currentStroopIndex = message.stroopIndex
        currentStroopWord = message.correctAnswer
        isStroopActive = true

        // Display the word
        displayWordWithOptimalSize(message.correctAnswer)
        binding.textStroopStatus.text = getString(R.string.color_display_stroop_active, message.stroopIndex)
        setResponseButtonsState(true)

        DebugLogger.d("TaskControl", "Stroop #${message.stroopIndex} started at: $currentStroopStartTime")
    }

    /**
     * Handle Stroop ended message with missed stroop detection
     */
    private fun handleStroopEnded(message: StroopEndedMessage) {
        if (isTaskTimedOut) {
            DebugLogger.d("TaskControl", "Ignoring StroopEnded - task has timed out")
            return
        }

        DebugLogger.d("TaskControl", "Stroop ended: #${message.stroopIndex}, reason=${message.endReason}")

        // Check if this stroop was missed (no response recorded)
        val wasResponseRecorded = stroopResponses.any { it.stroopIndex == message.stroopIndex }

        if (!wasResponseRecorded && isStroopActive) {
            // Schedule missed stroop check after 1 second grace period
            binding.root.postDelayed({
                // Double-check if response was recorded during grace period
                val responseRecordedInGrace = stroopResponses.any { it.stroopIndex == message.stroopIndex }
                if (!responseRecordedInGrace) {
                    recordMissedStroop()
                    DebugLogger.d("TaskControl", "Stroop #${message.stroopIndex} marked as missed after grace period")
                }
            }, 1000)
        }

        isStroopActive = false

        // Keep buttons active for grace period if no response was recorded
        if (responseButtonsActive && !wasResponseRecorded) {
            binding.root.postDelayed({
                if (!isStroopActive && !isTaskTimedOut) {
                    setResponseButtonsState(false)
                    showWaitingForStroop()
                }
            }, 1000)
        } else {
            if (!isTaskTimedOut) {
                showWaitingForStroop()
            }
        }
    }

    /**
     * Record missed stroop response
     */
    private fun recordMissedStroop() {
        val response = StroopResponse(
            stroopIndex = currentStroopIndex,
            word = currentStroopWord ?: "unknown",
            correctAnswer = currentStroopWord ?: "unknown",
            response = null, // null indicates missed
            reactionTimeMs = -1, // -1 indicates no reaction time
            startTime = currentStroopStartTime,
            responseTime = null
        )

        stroopResponses.add(response)
        DebugLogger.d("TaskControl", "Recorded missed stroop: #${currentStroopIndex}")
    }

    /**
     * LEGACY: Handle old Stroop display message format
     */
    private fun handleStroopDisplay(message: StroopDisplayMessage) {
        if (isTaskTimedOut) return

        DebugLogger.d("TaskControl", "Legacy Stroop displayed: ${message.correctAnswer}")

        currentStroopStartTime = System.currentTimeMillis()
        currentStroopIndex += 1 // Increment for legacy messages
        currentStroopWord = message.correctAnswer
        isStroopActive = true

        displayWordWithOptimalSize(message.correctAnswer)
        binding.textStroopStatus.text = getString(R.string.color_display_stroop_active, currentStroopIndex)
        setResponseButtonsState(true)
    }

    private fun handleStroopHidden() {
        if (isTaskTimedOut) return

        DebugLogger.d("TaskControl", "Stroop hidden")
        isStroopActive = false

        if (responseButtonsActive) {
            binding.root.postDelayed({
                if (!isStroopActive && !isTaskTimedOut) {
                    setResponseButtonsState(false)
                    showWaitingForStroop()
                }
            }, 1000)
        } else {
            if (!isTaskTimedOut) {
                showWaitingForStroop()
            }
        }
    }

    private fun displayWordWithOptimalSize(word: String) {
        binding.textCurrentColor.text = word

        binding.textCurrentColor.post {
            val optimalSize = calculateOptimalFontSize(word, binding.textCurrentColor.width, binding.textCurrentColor.height)
            binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, optimalSize)
        }

        DebugLogger.d("TaskControl", "Displaying word '$word'")
    }

    private fun calculateOptimalFontSize(text: String, availableWidth: Int, availableHeight: Int): Float {
        if (availableWidth <= 0 || availableHeight <= 0) {
            return 48f
        }

        val paint = binding.textCurrentColor.paint
        val maxSize = 200f
        val minSize = 12f

        // Use 90% of available space to ensure text fits with some padding
        val targetWidth = availableWidth * 0.90f
        val targetHeight = availableHeight * 0.90f

        var testSize = maxSize

        // Start from max size and work down until text fits
        while (testSize >= minSize) {
            paint.textSize = testSize * resources.displayMetrics.scaledDensity

            val textWidth = paint.measureText(text)
            val fontMetrics = paint.fontMetrics
            val textHeight = fontMetrics.bottom - fontMetrics.top

            // If both width and height fit, we found our size
            if (textWidth <= targetWidth && textHeight <= targetHeight) {
                return testSize
            }

            // Reduce size by 2sp and try again
            testSize -= 2f
        }

        return minSize
    }

    private fun setResponseButtonsState(active: Boolean) {
        val shouldBeActive = active && !isTaskTimedOut

        responseButtonsActive = shouldBeActive

        binding.btnResponseCorrect.isEnabled = shouldBeActive
        binding.btnResponseIncorrect.isEnabled = shouldBeActive

        if (shouldBeActive) {
            binding.btnResponseCorrect.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.btnResponseIncorrect.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.btnResponseCorrect.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            binding.btnResponseIncorrect.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            val disabledColor = ContextCompat.getColor(this, android.R.color.darker_gray)
            binding.btnResponseCorrect.setBackgroundColor(disabledColor)
            binding.btnResponseIncorrect.setBackgroundColor(disabledColor)
            binding.btnResponseCorrect.setTextColor(ContextCompat.getColor(this, android.R.color.black))
            binding.btnResponseIncorrect.setTextColor(ContextCompat.getColor(this, android.R.color.black))
        }
    }

    /**
     * Enhanced participant response recording with timing and data collection
     * WORKS DURING TIMEOUT GRACE PERIOD: Can still record responses for active stroop
     */
    private fun recordParticipantResponse(isCorrect: Boolean) {
        // Allow responses during timeout grace period for active stroop
        if (isTaskTimedOut && !isStroopActive) {
            DebugLogger.d("TaskControl", "Ignoring response - task timed out and no active stroop")
            return
        }

        val responseTime = System.currentTimeMillis()
        val reactionTimeMs = responseTime - currentStroopStartTime
        val responseType = if (isCorrect) "CORRECT" else "INCORRECT"

        DebugLogger.d("TaskControl", "Recording response: $responseType for stroop #$currentStroopIndex")
        DebugLogger.d("TaskControl", "Reaction time: ${reactionTimeMs}ms")

        if (isTaskTimedOut) {
            DebugLogger.d("TaskControl", "Response recorded DURING TIMEOUT GRACE PERIOD")
        }

        // Create and store response data
        val response = StroopResponse(
            stroopIndex = currentStroopIndex,
            word = currentStroopWord ?: "unknown",
            correctAnswer = currentStroopWord ?: "unknown",
            response = responseType,
            reactionTimeMs = reactionTimeMs,
            startTime = currentStroopStartTime,
            responseTime = responseTime
        )

        stroopResponses.add(response)

        // Immediately deactivate response buttons
        setResponseButtonsState(false)

        // Show feedback
        val baseMessage = if (isCorrect) {
            getString(R.string.color_display_response_correct)
        } else {
            getString(R.string.color_display_response_incorrect)
        }

        val feedbackMessage = if (isTaskTimedOut) {
            getString(R.string.color_display_response_grace_period, baseMessage)
        } else {
            baseMessage
        }

        Snackbar.make(binding.root, feedbackMessage, Snackbar.LENGTH_SHORT).show()

        // Update status
        if (isTaskTimedOut) {
            binding.textStroopStatus.text = getString(R.string.color_display_response_recorded_ending)
        } else {
            binding.textStroopStatus.text = getString(R.string.color_display_response_recorded_waiting)
        }

        DebugLogger.d("TaskControl", "Response recorded. Total responses: ${stroopResponses.size}")
    }

    /**
     * Navigate to TaskSummaryActivity with raw stroop data
     * UPDATED: Now passes taskNumber and iterationCounter
     */
    private fun navigateToTaskSummary(endCondition: String) {
        DebugLogger.d("TaskControl", getString(R.string.color_display_log_preparing_summary))
        DebugLogger.d("TaskControl", getString(R.string.color_display_log_total_responses, stroopResponses.size))
        DebugLogger.d("TaskControl", "End condition: $endCondition")

        // Calculate time on task (exclude countdown)
        val taskEndTime = System.currentTimeMillis()
        val timeOnTaskMs = if (taskActualStartTime > 0) {
            taskEndTime - taskActualStartTime
        } else {
            // Fallback: use total time minus countdown
            taskEndTime - activityOpenTime - countdownDuration
        }

        DebugLogger.d("TaskControl", getString(R.string.color_display_log_time_on_task, timeOnTaskMs))

        // Create intent for TaskSummaryActivity with raw data
        val intent = Intent(this, TaskSummaryActivity::class.java).apply {
            // UPDATED: Pass both taskNumber and taskId
            putExtra("TASK_NUMBER", taskNumber)  // NEW: Int task number
            putExtra("TASK_ID", taskId)          // Keep String for display
            putExtra("ITERATION_COUNTER", iterationCounter) // NEW: Iteration counter
            putExtra("TASK_LABEL", taskLabel)
            putExtra("TASK_TEXT", taskText)
            putExtra("END_CONDITION", endCondition)
            putExtra("TIME_ON_TASK_MS", timeOnTaskMs)
            putExtra("COUNTDOWN_DURATION_MS", countdownDuration)
            putExtra("TASK_START_TIME", taskActualStartTime)
            putExtra("TASK_END_TIME", taskEndTime)

            // Raw stroop response data - convert to arrays for Intent
            val stroopIndices = stroopResponses.map { it.stroopIndex }.toIntArray()
            val stroopWords = stroopResponses.map { it.word }.toTypedArray()
            val stroopAnswers = stroopResponses.map { it.correctAnswer }.toTypedArray()
            val stroopResponseTypes = stroopResponses.map { it.response ?: "MISSED" }.toTypedArray()
            val stroopReactionTimes = stroopResponses.map { it.reactionTimeMs }.toLongArray()
            val stroopStartTimes = stroopResponses.map { it.startTime }.toLongArray()
            val stroopResponseTimes = stroopResponses.map { it.responseTime ?: -1L }.toLongArray()

            putExtra("STROOP_INDICES", stroopIndices)
            putExtra("STROOP_WORDS", stroopWords)
            putExtra("STROOP_ANSWERS", stroopAnswers)
            putExtra("STROOP_RESPONSES", stroopResponseTypes)
            putExtra("STROOP_REACTION_TIMES", stroopReactionTimes)
            putExtra("STROOP_START_TIMES", stroopStartTimes)
            putExtra("STROOP_RESPONSE_TIMES", stroopResponseTimes)

            // Session info
            putExtra("SESSION_ID", currentSessionId)
            putExtra("IS_INDIVIDUAL_TASK", isIndividualTask)
        }

        DebugLogger.d("TaskControl", "Starting TaskSummaryActivity with ${stroopResponses.size} stroop responses")
        startActivity(intent)

        // Finish this activity since user shouldn't return here
        finish()
    }

    private fun showTaskReady() {
        // Reset all task state flags
        isTaskTimedOut = false
        isTaskRunning = false
        isStroopActive = false
        responseButtonsActive = false
        currentStroopWord = null

        // Update display
        binding.textStroopStatus.text = getString(R.string.color_display_task_ready)
        binding.textCurrentColor.text = getString(R.string.color_display_waiting)
        binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)

        // Enable START button and disable others
        binding.btnTriggerStroop.isEnabled = true
        binding.btnPauseTask.isEnabled = false
        binding.btnResumeTask.isEnabled = false
        binding.btnResetTask.isEnabled = false
        binding.btnTaskSuccess.isEnabled = false
        binding.btnTaskFailed.isEnabled = false
        binding.btnTaskGivenUp.isEnabled = false

        // Hide return button
        binding.btnReturnToTasks.visibility = View.GONE

        // Disable response buttons
        setResponseButtonsState(false)

        // Reset data collection
        resetDataCollection()

        DebugLogger.d("TaskControl", "Task ready state set - START button enabled")
    }

    /**
     * Reset data collection for new task
     */
    private fun resetDataCollection() {
        stroopResponses.clear()
        taskActualStartTime = 0L
        currentStroopStartTime = 0L
        currentStroopIndex = 0
        DebugLogger.d("TaskControl", "Data collection reset")
    }

    private fun showWaitingForStroop() {
        if (!isTaskTimedOut) {
            binding.textStroopStatus.text = getString(R.string.color_display_next_stroop_waiting)
            binding.textCurrentColor.text = getString(R.string.color_display_interval_period)
            binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            currentStroopWord = null
            setResponseButtonsState(false)
        }
    }

    private fun disableControlButtonsOnly() {
        binding.btnTriggerStroop.visibility = View.GONE
        binding.btnPauseTask.visibility = View.GONE
        binding.btnResumeTask.visibility = View.GONE
        binding.btnResetTask.visibility = View.GONE
        binding.btnTaskSuccess.visibility = View.GONE
        binding.btnTaskFailed.visibility = View.GONE
        binding.btnTaskGivenUp.visibility = View.GONE

        binding.btnReturnToTasks.visibility = View.VISIBLE
        binding.btnReturnToTasks.isEnabled = false
        binding.btnReturnToTasks.setBackgroundColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        binding.btnReturnToTasks.setTextColor(ContextCompat.getColor(this, android.R.color.black))
    }

    private fun showNotConnected() {
        binding.textStroopStatus.text = getString(R.string.color_display_not_connected)
        binding.textCurrentColor.text = getString(R.string.color_display_please_reconnect)
        updateButtonStates(TaskState.Error("CONNECTION_ERROR", "Not connected"))
        setResponseButtonsState(false)
    }

    private fun showSessionError() {
        binding.textStroopStatus.text = getString(R.string.color_display_no_session)
        binding.textCurrentColor.text = getString(R.string.color_display_restart_participant)
        updateButtonStates(TaskState.Error("SESSION_ERROR", "No session"))
        setResponseButtonsState(false)

        Snackbar.make(
            binding.root,
            getString(R.string.color_display_no_session_found),
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun returnToTaskSelection() {
        DebugLogger.d("TaskControl", "Returning to task selection")
        finish()
    }

    /**
     * FIXED: Start task - now passes the loaded RuntimeConfig
     * UPDATED to get fresh iteration counter on each start
     * SINGLE METHOD - No duplicates
     */
    private fun startTask() {
        val sessionIdValue = currentSessionId ?: return

        lifecycleScope.launch {
            try {
                iterationCounter = SessionManager.getNextIterationCounter(taskNumber)
                DebugLogger.d("TaskControl", "Starting task: $taskNumber (iteration $iterationCounter)")

                // Debug RuntimeConfig state before sending
                if (runtimeConfig != null) {
                    DebugLogger.d("TaskControl", "Passing RuntimeConfig with ${runtimeConfig!!.baseConfig.stroopColors.size} colors to TaskControlManager")
                    DebugLogger.d("TaskControl", "Master config colors:")
                    runtimeConfig!!.baseConfig.stroopColors.forEach { (name, hex) ->
                        DebugLogger.d("TaskControl", "  Master color: '$name' -> '$hex'")
                    }
                } else {
                    DebugLogger.w("TaskControl", "RuntimeConfig is null - TaskControlManager will use fallback colors")
                }

                resetDataCollection()
                isTaskTimedOut = false
                isTaskRunning = true

                val success = taskControlManager.startTask(
                    taskId = taskNumber.toString(),
                    taskLabel = taskLabel ?: getString(R.string.color_display_unknown_task),
                    taskTimeoutMs = (taskTimeout * 1000).toLong(),
                    sessionId = sessionIdValue,
                    runtimeConfig = runtimeConfig
                )

                if (!success) {
                    isTaskRunning = false
                    DebugLogger.e("TaskControl", "Failed to start task via TaskControlManager")
                    Snackbar.make(
                        binding.root,
                        getString(R.string.color_display_failed_start),
                        Snackbar.LENGTH_LONG
                    ).show()
                } else {
                    DebugLogger.d("TaskControl", "Task started successfully with iteration counter: $iterationCounter")
                }
            } catch (e: Exception) {
                DebugLogger.e("TaskControl", "Error getting iteration counter or starting task", e)
                isTaskRunning = false
                Snackbar.make(
                    binding.root,
                    getString(R.string.color_display_failed_start),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun pauseTask() {
        val sessionIdValue = currentSessionId ?: return

        DebugLogger.d("TaskControl", "Sending pause command to projector")

        lifecycleScope.launch {
            try {
                // Send PauseTaskMessage to projector
                val pauseMessage = PauseTaskMessage(
                    sessionId = sessionIdValue,
                    taskId = taskNumber.toString() // Convert to String for network compatibility
                )

                networkClient.sendMessage(pauseMessage)
                DebugLogger.d("TaskControl", "Pause message sent to projector")

            } catch (e: Exception) {
                DebugLogger.e("TaskControl", "Failed to send pause command", e)
                Snackbar.make(binding.root, getString(R.string.color_display_failed_pause), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun resumeTask() {
        val sessionIdValue = currentSessionId ?: return

        DebugLogger.d("TaskControl", "Sending resume command to projector")

        lifecycleScope.launch {
            try {
                // Send ResumeTaskMessage to projector
                val resumeMessage = ResumeTaskMessage(
                    sessionId = sessionIdValue,
                    taskId = taskNumber.toString() // Convert to String for network compatibility
                )

                networkClient.sendMessage(resumeMessage)
                DebugLogger.d("TaskControl", "Resume message sent to projector")

            } catch (e: Exception) {
                DebugLogger.e("TaskControl", "Failed to send resume command", e)
                Snackbar.make(binding.root, getString(R.string.color_display_failed_resume), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun resetTask() {
        val sessionIdValue = currentSessionId ?: return

        DebugLogger.d("TaskControl", "Sending reset command to projector")

        lifecycleScope.launch {
            try {
                // Send TaskResetCommand to projector
                val resetMessage = TaskResetCommand(
                    sessionId = sessionIdValue
                )

                networkClient.sendMessage(resetMessage)
                DebugLogger.d("TaskControl", "Reset message sent to projector")

                // Reset local UI state immediately to Ready state
                resetToReadyState()
                DebugLogger.d("TaskControl", "Local UI reset to ready state with START button enabled")

            } catch (e: Exception) {
                DebugLogger.e("TaskControl", "Failed to send reset command", e)
                Snackbar.make(binding.root, getString(R.string.color_display_failed_reset), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Reset UI to ready state (called after reset command)
     * UPDATED: Gets new iteration counter for next attempt
     */
    private fun resetToReadyState() {
        // Reset all task state flags
        isTaskTimedOut = false
        isTaskRunning = false
        isStroopActive = false
        responseButtonsActive = false
        currentStroopWord = null

        // Get new iteration counter for potential retry - IMPORTANT: This increments the counter
        getIterationCounter()

        // Reset data collection for new iteration
        resetDataCollection()

        // Update UI to ready state
        binding.textStroopStatus.text = getString(R.string.color_display_task_ready)
        binding.textCurrentColor.text = getString(R.string.color_display_waiting)
        binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)

        // Enable START button and disable all others
        binding.btnTriggerStroop.isEnabled = true
        binding.btnPauseTask.isEnabled = false
        binding.btnResumeTask.isEnabled = false
        binding.btnResetTask.isEnabled = false
        binding.btnTaskSuccess.isEnabled = false
        binding.btnTaskFailed.isEnabled = false
        binding.btnTaskGivenUp.isEnabled = false

        // Hide return button
        binding.btnReturnToTasks.visibility = View.GONE

        // Disable response buttons
        setResponseButtonsState(false)

        DebugLogger.d("TaskControl", "UI reset complete - START button enabled for iteration $iterationCounter")
    }

    /**
     * Enhanced task completion with raw data handoff to TaskSummaryActivity
     */
    private fun completeTask(condition: String) {
        val sessionIdValue = currentSessionId ?: return

        DebugLogger.d("TaskControl", "Completing task: $taskNumber with condition: $condition")

        // Show completion UI immediately
        showTaskCompletion(condition)

        // Navigate to TaskSummaryActivity with raw data - no need to wait for network
        navigateToTaskSummary(condition)

        // End task via network in background (don't wait for it)
        lifecycleScope.launch {
            val success = taskControlManager.endTask(taskNumber.toString(), condition, sessionIdValue)

            if (!success) {
                DebugLogger.w("TaskControl", "Failed to end task via TaskControlManager (network)")
                // Don't show error to user since we've already navigated away
            }
        }
    }

    private fun showTaskCompletion(condition: String) {
        DebugLogger.d("TaskControl", "Task completing with condition: $condition - navigating to summary")

        // Show a brief completion message
        binding.textStroopStatus.text = getString(R.string.color_display_task_ending, condition)
        binding.textCurrentColor.text = getString(R.string.color_display_processing)
        binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)

        // Disable all controls immediately
        setResponseButtonsState(false)
        binding.btnTriggerStroop.isEnabled = false
        binding.btnPauseTask.isEnabled = false
        binding.btnResumeTask.isEnabled = false
        binding.btnResetTask.isEnabled = false
        binding.btnTaskSuccess.isEnabled = false
        binding.btnTaskFailed.isEnabled = false
        binding.btnTaskGivenUp.isEnabled = false

        // Reset state flags
        isStroopActive = false
        currentStroopWord = null
        responseButtonsActive = false
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}