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
import com.research.shared.network.StroopDisplayMessage
import com.research.shared.network.StroopHiddenMessage
import com.research.shared.network.HeartbeatMessage
import com.research.shared.network.HandshakeResponseMessage
import com.research.shared.network.StroopStartedMessage
import com.research.shared.network.StroopEndedMessage
import com.research.shared.network.TaskTimeoutMessage
import com.research.shared.network.MessageType
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.util.Log
import com.google.android.material.snackbar.Snackbar
import androidx.core.content.ContextCompat

/**
 * Task Control Screen - displays task information and provides controls for task execution
 * Shows current Stroop word from Projector and allows moderator to control task flow
 * ENHANCED: Complete data collection system for task metrics and timing
 * Collects individual stroop responses in memory, aggregates when task ends
 */
class ColorDisplayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityColorDisplayBinding
    private val networkClient by lazy { NetworkManager.getNetworkClient(this) }
    private lateinit var taskControlManager: TaskControlNetworkManager
    private lateinit var fileManager: FileManager

    // Task information from intent
    private var taskId: String? = null
    private var taskLabel: String? = null
    private var taskText: String? = null
    private var taskTimeout: Int = 0
    private var isIndividualTask: Boolean = true

    // Task state
    private var currentStroopWord: String? = null
    private var currentSessionId: String? = null
    private var isStroopActive = false
    private var responseButtonsActive = false
    private var isTaskTimedOut = false

    // 🎯 NEW: Data collection system
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

        // Record when activity opened
        activityOpenTime = System.currentTimeMillis()

        binding = ActivityColorDisplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize FileManager and task control manager
        fileManager = FileManager(this)
        taskControlManager = TaskControlNetworkManager(networkClient)

        // Load countdown duration from config EARLY
        loadCountdownFromConfig()

        // Get task information from intent
        extractTaskInfo()

        // Set up custom toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = taskLabel ?: "Task Control"
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

        Log.d("TaskControl", "Activity opened at: $activityOpenTime, countdown duration: ${countdownDuration}ms")
    }

    /**
     * Load countdown duration from configuration using FileManager
     * Now loads synchronously to ensure value is available immediately
     */
    private fun loadCountdownFromConfig() {
        lifecycleScope.launch {
            try {
                when (val result = fileManager.loadConfiguration()) {
                    is ConfigLoadResult.Success -> {
                        // Get countdown duration from config (in seconds) and convert to milliseconds
                        val configCountdown = result.config.countdownDuration * 1000L

                        // Update the class variable
                        countdownDuration = configCountdown

                        Log.d("TaskControl", "Countdown duration loaded from config: ${countdownDuration}ms (${result.config.countdownDuration}s)")
                    }
                    is ConfigLoadResult.Error -> {
                        // Keep default fallback if config loading fails
                        Log.w("TaskControl", "Config loading failed: ${fileManager.getErrorMessage(result.error)}, using default countdown duration: ${countdownDuration}ms")
                    }
                }
            } catch (e: Exception) {
                // Keep default fallback on any error
                Log.e("TaskControl", "Error loading countdown duration from config, using default: ${countdownDuration}ms", e)
            }
        }
    }

    private fun extractTaskInfo() {
        taskId = intent.getStringExtra("TASK_ID")
        taskLabel = intent.getStringExtra("TASK_LABEL")
        taskText = intent.getStringExtra("TASK_TEXT")
        taskTimeout = intent.getIntExtra("TASK_TIMEOUT", 0)
        isIndividualTask = intent.getBooleanExtra("IS_INDIVIDUAL_TASK", true)

        Log.d("TaskControl", "Task info: ID=$taskId, Label=$taskLabel, Timeout=${taskTimeout}s")
    }

    private fun setupTaskInfo() {
        binding.textTaskTitle.text = taskLabel ?: "Unknown Task"
        binding.textTaskDescription.text = taskText ?: "No description available"
        binding.textTaskTimeout.text = "Timeout: ${taskTimeout}s"
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
                    Snackbar.make(binding.root, "Task Error: $it", Snackbar.LENGTH_LONG).show()
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
                    Log.d("TaskControl", "Task actually started at: $taskActualStartTime (${taskActualStartTime - activityOpenTime}ms after activity opened)")
                }
            }
            is TaskState.Completed -> {
                // Task completed - will be handled in completeTask method
                Log.d("TaskControl", "Task state shows completed: ${taskState.endCondition}")
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

                binding.textStroopStatus.text = "Starting task... Countdown active"
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

                if (binding.textStroopStatus.text != "Stroop Active - Listening for response") {
                    binding.textStroopStatus.text = "Task active - Waiting for Stroop"
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

                binding.textStroopStatus.text = "Task paused"
            }

            is TaskState.Completed -> {
                binding.btnTriggerStroop.isEnabled = true
                binding.btnPauseTask.isEnabled = false
                binding.btnResumeTask.isEnabled = false
                binding.btnResetTask.isEnabled = false
                binding.btnTaskSuccess.isEnabled = false
                binding.btnTaskFailed.isEnabled = false
                binding.btnTaskGivenUp.isEnabled = false
                binding.btnReturnToTasks.visibility = View.VISIBLE

                binding.textStroopStatus.text = "Task completed: ${taskState.endCondition}"

                // Show completion message
                Snackbar.make(
                    binding.root,
                    "Task completed with condition: ${taskState.endCondition}",
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

                binding.textStroopStatus.text = "Task error: ${taskState.errorType}"
            }
        }
    }

    /**
     * Enhanced message observation with data collection
     */
    private fun observeNetworkMessages() {
        Log.d("TaskControl", "Setting up message listener with data collection")

        lifecycleScope.launch {
            try {
                networkClient.receiveMessages().collectLatest { message ->
                    Log.d("TaskControl", "Message received: ${message::class.java.simpleName}")

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
                                Log.v("TaskControl", "Heartbeat received")
                            }

                            is HandshakeResponseMessage -> {
                                Log.d("TaskControl", "Handshake response received")
                            }

                            else -> {
                                Log.w("TaskControl", "Unmatched message: ${message::class.java.name}")
                                taskControlManager.handleTaskMessage(message)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("TaskControl", "Critical error in message listener", e)
            }
        }
    }

    /**
     * Handle task timeout with raw data handoff
     * SPECIAL CASE: If stroop is active, allow 1 second grace period for response
     */
    private fun handleTaskTimeout(message: TaskTimeoutMessage) {
        Log.d("TaskControl", "Task timed out: ${message.taskId}, duration: ${message.actualDuration}ms")

        if (isTaskTimedOut) {
            Log.w("TaskControl", "Task already timed out, ignoring duplicate")
            return
        }

        isTaskTimedOut = true

        val isStroopActiveAtTimeout = isStroopActive && currentStroopWord != null

        if (isStroopActiveAtTimeout) {
            Log.d("TaskControl", "TIMEOUT WITH ACTIVE STROOP: Allowing 1 second grace period for response")

            // Disable all control buttons but KEEP response buttons active
            disableControlButtonsOnly()
            binding.textStroopStatus.text = "Task Timeout - Final response window (1 second)"

            // Schedule navigation after 1 second grace period
            binding.root.postDelayed({
                Log.d("TaskControl", "Grace period ended - checking if response was recorded")

                // Check if response was recorded during grace period
                val responseRecordedInGrace = stroopResponses.any {
                    it.stroopIndex == currentStroopIndex && it.responseTime != null && it.responseTime!! > System.currentTimeMillis() - 1000
                }

                if (!responseRecordedInGrace) {
                    Log.d("TaskControl", "No response during grace period - marking stroop as missed")
                    recordMissedStroop()
                }

                Log.d("TaskControl", "Navigating to TaskSummary after grace period")
                navigateToTaskSummary("Timed Out")
            }, 1000) // 1 second grace period

        } else {
            Log.d("TaskControl", "TIMEOUT WITHOUT ACTIVE STROOP: Navigating immediately")
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
            Log.e("TaskControl", "Error notifying TaskControlManager of timeout", e)
            // Don't block navigation on network error
        }
    }

    /**
     * Handle enhanced Stroop started message with data collection
     */
    private fun handleStroopStarted(message: StroopStartedMessage) {
        if (isTaskTimedOut) {
            Log.d("TaskControl", "Ignoring StroopStarted - task has timed out")
            return
        }

        Log.d("TaskControl", "Stroop started: #${message.stroopIndex}, word='${message.word}', answer='${message.correctAnswer}'")

        // Record stroop start for timing
        currentStroopStartTime = System.currentTimeMillis()
        currentStroopIndex = message.stroopIndex
        currentStroopWord = message.correctAnswer
        isStroopActive = true

        // Display the word
        displayWordWithOptimalSize(message.correctAnswer)
        binding.textStroopStatus.text = "Stroop #${message.stroopIndex} Active - Listening for response"
        setResponseButtonsState(true)

        Log.d("TaskControl", "Stroop #${message.stroopIndex} started at: $currentStroopStartTime")
    }

    /**
     * Handle Stroop ended message with missed stroop detection
     */
    private fun handleStroopEnded(message: StroopEndedMessage) {
        if (isTaskTimedOut) {
            Log.d("TaskControl", "Ignoring StroopEnded - task has timed out")
            return
        }

        Log.d("TaskControl", "Stroop ended: #${message.stroopIndex}, reason=${message.endReason}")

        // Check if this stroop was missed (no response recorded)
        val wasResponseRecorded = stroopResponses.any { it.stroopIndex == message.stroopIndex }

        if (!wasResponseRecorded && isStroopActive) {
            // Schedule missed stroop check after 1 second grace period
            binding.root.postDelayed({
                // Double-check if response was recorded during grace period
                val responseRecordedInGrace = stroopResponses.any { it.stroopIndex == message.stroopIndex }
                if (!responseRecordedInGrace) {
                    recordMissedStroop()
                    Log.d("TaskControl", "Stroop #${message.stroopIndex} marked as missed after grace period")
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
        Log.d("TaskControl", "Recorded missed stroop: #${currentStroopIndex}")
    }

    /**
     * LEGACY: Handle old Stroop display message format
     */
    private fun handleStroopDisplay(message: StroopDisplayMessage) {
        if (isTaskTimedOut) return

        Log.d("TaskControl", "Legacy Stroop displayed: ${message.correctAnswer}")

        currentStroopStartTime = System.currentTimeMillis()
        currentStroopIndex += 1 // Increment for legacy messages
        currentStroopWord = message.correctAnswer
        isStroopActive = true

        displayWordWithOptimalSize(message.correctAnswer)
        binding.textStroopStatus.text = "Stroop Active - Listening for response"
        setResponseButtonsState(true)
    }

    private fun handleStroopHidden() {
        if (isTaskTimedOut) return

        Log.d("TaskControl", "Stroop hidden")
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

        Log.d("TaskControl", "Displaying word '$word'")
    }

    private fun calculateOptimalFontSize(text: String, availableWidth: Int, availableHeight: Int): Float {
        if (availableWidth <= 0 || availableHeight <= 0) {
            return 48f
        }

        val paint = binding.textCurrentColor.paint
        var testSize = 100f
        val maxSize = 100f
        val minSize = 16f

        val usableWidth = availableWidth * 0.8f
        val usableHeight = availableHeight * 0.8f

        var low = minSize
        var high = maxSize

        while (high - low > 1) {
            testSize = (low + high) / 2
            paint.textSize = testSize * resources.displayMetrics.scaledDensity

            val textWidth = paint.measureText(text)
            val textHeight = paint.fontMetrics.let { it.bottom - it.top }

            if (textWidth <= usableWidth && textHeight <= usableHeight) {
                low = testSize
            } else {
                high = testSize
            }
        }

        return low
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
            Log.d("TaskControl", "Ignoring response - task timed out and no active stroop")
            return
        }

        val responseTime = System.currentTimeMillis()
        val reactionTimeMs = responseTime - currentStroopStartTime
        val responseType = if (isCorrect) "CORRECT" else "INCORRECT"

        Log.d("TaskControl", "Recording response: $responseType for stroop #$currentStroopIndex")
        Log.d("TaskControl", "Reaction time: ${reactionTimeMs}ms")

        if (isTaskTimedOut) {
            Log.d("TaskControl", "Response recorded DURING TIMEOUT GRACE PERIOD")
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
        val message = if (isCorrect) {
            "Recorded: Participant correctly named the color"
        } else {
            "Recorded: Participant incorrectly named the color"
        }

        val feedbackMessage = if (isTaskTimedOut) {
            "$message (during grace period)"
        } else {
            message
        }

        Snackbar.make(binding.root, feedbackMessage, Snackbar.LENGTH_SHORT).show()

        // Update status
        if (isTaskTimedOut) {
            binding.textStroopStatus.text = "Response recorded - Task ending"
        } else {
            binding.textStroopStatus.text = "Response recorded - Waiting for next Stroop"
        }

        Log.d("TaskControl", "Response recorded. Total responses: ${stroopResponses.size}")
    }

    /**
     * Navigate to TaskSummaryActivity with raw stroop data
     */
    private fun navigateToTaskSummary(endCondition: String) {
        Log.d("TaskControl", "=== PREPARING TASK SUMMARY DATA ===")
        Log.d("TaskControl", "Total stroop responses: ${stroopResponses.size}")
        Log.d("TaskControl", "End condition: $endCondition")

        // Calculate time on task (exclude countdown)
        val taskEndTime = System.currentTimeMillis()
        val timeOnTaskMs = if (taskActualStartTime > 0) {
            taskEndTime - taskActualStartTime
        } else {
            // Fallback: use total time minus countdown
            taskEndTime - activityOpenTime - countdownDuration
        }

        Log.d("TaskControl", "Time on task: ${timeOnTaskMs}ms")

        // Create intent for TaskSummaryActivity with raw data
        val intent = Intent(this, TaskSummaryActivity::class.java).apply {
            // Basic task info
            putExtra("TASK_ID", taskId)
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

        Log.d("TaskControl", "Starting TaskSummaryActivity with ${stroopResponses.size} stroop responses")
        startActivity(intent)

        // Finish this activity since user shouldn't return here
        // TaskSummaryActivity will handle proper navigation flow
        finish()
    }

    private fun showTaskReady() {
        isTaskTimedOut = false
        binding.textStroopStatus.text = "Task ready - Press START to begin"
        binding.textCurrentColor.text = "Waiting..."
        binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
        currentStroopWord = null
        isStroopActive = false
        setResponseButtonsState(false)
        binding.btnReturnToTasks.visibility = View.GONE

        // Reset data collection
        resetDataCollection()
    }

    /**
     * Reset data collection for new task
     */
    private fun resetDataCollection() {
        stroopResponses.clear()
        taskActualStartTime = 0L
        currentStroopStartTime = 0L
        currentStroopIndex = 0
        Log.d("TaskControl", "Data collection reset")
    }

    private fun showWaitingForStroop() {
        if (!isTaskTimedOut) {
            binding.textStroopStatus.text = "Task active - Waiting for next Stroop"
            binding.textCurrentColor.text = "Interval period"
            binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            currentStroopWord = null
            setResponseButtonsState(false)
        }
    }

    private fun showTaskTimeout(message: TaskTimeoutMessage) {
        Log.d("TaskControl", "Showing task timeout UI")

        binding.textStroopStatus.text = "Task Timeout Reached"
        binding.textCurrentColor.text = "Task ended"
        binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)

        binding.btnTriggerStroop.visibility = View.GONE
        binding.btnPauseTask.visibility = View.GONE
        binding.btnResumeTask.visibility = View.GONE
        binding.btnResetTask.visibility = View.GONE
        binding.btnTaskSuccess.visibility = View.GONE
        binding.btnTaskFailed.visibility = View.GONE
        binding.btnTaskGivenUp.visibility = View.GONE

        setResponseButtonsState(false)

        binding.btnReturnToTasks.visibility = View.VISIBLE
        binding.btnReturnToTasks.isEnabled = true
        binding.btnReturnToTasks.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
        binding.btnReturnToTasks.setTextColor(ContextCompat.getColor(this, android.R.color.white))

        val timeoutSeconds = message.actualDuration / 1000
        Snackbar.make(
            binding.root,
            "Task timed out after ${timeoutSeconds}s. ${message.stroopsDisplayed} Stroops displayed.",
            Snackbar.LENGTH_LONG
        ).show()

        isStroopActive = false
        currentStroopWord = null
        responseButtonsActive = false
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

    private fun showTaskTimeoutWithStroopEnded(message: TaskTimeoutMessage) {
        binding.textStroopStatus.text = "Task Timeout Reached"
        binding.textCurrentColor.text = "Task ended"
        binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)

        setResponseButtonsState(false)

        binding.btnReturnToTasks.isEnabled = true
        binding.btnReturnToTasks.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_blue_dark))
        binding.btnReturnToTasks.setTextColor(ContextCompat.getColor(this, android.R.color.white))

        val timeoutSeconds = message.actualDuration / 1000
        Snackbar.make(
            binding.root,
            "Task timed out after ${timeoutSeconds}s. ${message.stroopsDisplayed} Stroops displayed.",
            Snackbar.LENGTH_LONG
        ).show()

        isStroopActive = false
        currentStroopWord = null
        responseButtonsActive = false
    }

    private fun showNotConnected() {
        binding.textStroopStatus.text = "Not Connected to Projector"
        binding.textCurrentColor.text = "Please reconnect"
        updateButtonStates(TaskState.Error("CONNECTION_ERROR", "Not connected"))
        setResponseButtonsState(false)
    }

    private fun showSessionError() {
        binding.textStroopStatus.text = "No Session Available"
        binding.textCurrentColor.text = "Please restart from participant info"
        updateButtonStates(TaskState.Error("SESSION_ERROR", "No session"))
        setResponseButtonsState(false)

        Snackbar.make(
            binding.root,
            "No session found. Please go back and enter participant information.",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun returnToTaskSelection() {
        Log.d("TaskControl", "Returning to task selection")
        finish()
    }

    private fun startTask() {
        val taskIdValue = taskId ?: return
        val sessionIdValue = currentSessionId ?: return

        Log.d("TaskControl", "Starting task: $taskIdValue")

        // Reset data collection for new task
        resetDataCollection()
        isTaskTimedOut = false

        lifecycleScope.launch {
            val success = taskControlManager.startTask(
                taskId = taskIdValue,
                taskLabel = taskLabel ?: "Unknown Task",
                taskTimeoutMs = (taskTimeout * 1000).toLong(),
                sessionId = sessionIdValue
            )

            if (!success) {
                Snackbar.make(
                    binding.root,
                    "Failed to start task. Check connection.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun pauseTask() {
        val taskIdValue = taskId ?: return
        val sessionIdValue = currentSessionId ?: return

        lifecycleScope.launch {
            val success = taskControlManager.pauseTask(taskIdValue, sessionIdValue)
            if (!success) {
                Snackbar.make(binding.root, "Failed to pause task.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun resumeTask() {
        val taskIdValue = taskId ?: return
        val sessionIdValue = currentSessionId ?: return

        lifecycleScope.launch {
            val success = taskControlManager.resumeTask(taskIdValue, sessionIdValue)
            if (!success) {
                Snackbar.make(binding.root, "Failed to resume task.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun resetTask() {
        val taskIdValue = taskId ?: return
        val sessionIdValue = currentSessionId ?: return

        lifecycleScope.launch {
            val success = taskControlManager.resetTask(taskIdValue, sessionIdValue)
            if (success) {
                showTaskReady()
            } else {
                Snackbar.make(binding.root, "Failed to reset task.", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Enhanced task completion with raw data handoff to TaskSummaryActivity
     */
    private fun completeTask(condition: String) {
        val taskIdValue = taskId ?: return
        val sessionIdValue = currentSessionId ?: return

        Log.d("TaskControl", "Completing task: $taskIdValue with condition: $condition")

        // Show completion UI immediately
        showTaskCompletion(condition)

        // Navigate to TaskSummaryActivity with raw data - no need to wait for network
        navigateToTaskSummary(condition)

        // End task via network in background (don't wait for it)
        lifecycleScope.launch {
            val success = taskControlManager.endTask(taskIdValue, condition, sessionIdValue)

            if (!success) {
                Log.w("TaskControl", "Failed to end task via TaskControlManager (network)")
                // Don't show error to user since we've already navigated away
                // TaskSummaryActivity will handle the data saving
            }
        }
    }

    private fun showTaskCompletion(condition: String) {
        Log.d("TaskControl", "Task completing with condition: $condition - navigating to summary")

        // Just show a brief completion message
        binding.textStroopStatus.text = "Task Ending - $condition"
        binding.textCurrentColor.text = "Processing..."
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