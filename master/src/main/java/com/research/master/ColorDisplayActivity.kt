package com.research.master

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
 * ENHANCED: Added timeout handling with proper UI state management and TaskControlManager sync
 * FIXED: Enhanced message matching with multiple fallback approaches for TaskTimeoutMessage
 */
class ColorDisplayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityColorDisplayBinding
    private val networkClient by lazy { NetworkManager.getNetworkClient(this) }
    private lateinit var taskControlManager: TaskControlNetworkManager

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
    private var isTaskTimedOut = false  // Track timeout state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityColorDisplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize task control manager
        taskControlManager = TaskControlNetworkManager(networkClient)

        // 🎯 QUICK FIX: Debug imports immediately
        debugImports()

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
    }

    // 🎯 QUICK FIX: Debug import availability
    private fun debugImports() {
        Log.d("TaskControl", "=== IMPORT DEBUG ===")
        try {
            val timeoutClass = TaskTimeoutMessage::class.java
            Log.d("TaskControl", "✅ TaskTimeoutMessage class: ${timeoutClass.name}")
            Log.d("TaskControl", "✅ Package: ${timeoutClass.packageName}")
            Log.d("TaskControl", "✅ Constructors: ${timeoutClass.constructors.size}")

            // Check MessageType enum
            try {
                val messageTypeClass = MessageType::class.java
                Log.d("TaskControl", "✅ MessageType enum: ${messageTypeClass.name}")
                Log.d("TaskControl", "✅ TASK_TIMEOUT exists: ${MessageType.TASK_TIMEOUT}")
            } catch (e: Exception) {
                Log.e("TaskControl", "❌ MessageType enum problem!", e)
            }
        } catch (e: Exception) {
            Log.e("TaskControl", "❌ TaskTimeoutMessage import problem!", e)
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
        binding.btnTaskGivenUp.setOnClickListener { completeTask("Partial Success") }

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
                taskState?.let { updateButtonStates(it) }
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
     * Enhanced message observation with comprehensive timeout debugging and multiple fallback approaches
     */
    private fun observeNetworkMessages() {
        Log.d("TaskControl", "=== SETTING UP MESSAGE LISTENER ===")
        Log.d("TaskControl", "NetworkClient: ${networkClient::class.java.simpleName}")
        Log.d("TaskControl", "Connection state: ${NetworkManager.isConnected()}")
        Log.d("TaskControl", "Current session ID: $currentSessionId")

        lifecycleScope.launch {
            Log.d("TaskControl", "Starting message collection coroutine...")
            try {
                networkClient.receiveMessages().collectLatest { message ->
                    Log.d("TaskControl", "*** MESSAGE RECEIVED FROM PROJECTOR ***")
                    Log.d("TaskControl", "Message type: ${message::class.java.simpleName}")
                    Log.d("TaskControl", "Full class name: ${message::class.java.name}")
                    Log.d("TaskControl", "Message content: $message")
                    Log.d("TaskControl", "Activity lifecycle state: ${lifecycle.currentState}")

                    // 🎯 ENHANCED: Add explicit TaskTimeoutMessage type checking
                    Log.d("TaskControl", "Is TaskTimeoutMessage? ${message is TaskTimeoutMessage}")
                    if (message is TaskTimeoutMessage) {
                        Log.d("TaskControl", "✅ CONFIRMED: This is a TaskTimeoutMessage!")
                        Log.d("TaskControl", "TaskID: ${message.taskId}")
                        Log.d("TaskControl", "Duration: ${message.actualDuration}ms")
                        Log.d("TaskControl", "Stroops: ${message.stroopsDisplayed}")
                    }

                    // Check if TaskTimeoutMessage class is available
                    try {
                        val timeoutClass = TaskTimeoutMessage::class.java
                        Log.d("TaskControl", "TaskTimeoutMessage class available: ${timeoutClass.name}")
                        Log.d("TaskControl", "Message class matches? ${message::class.java == timeoutClass}")
                    } catch (e: Exception) {
                        Log.e("TaskControl", "TaskTimeoutMessage class not available!", e)
                    }

                    // Only process messages if activity is resumed
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        Log.d("TaskControl", "Processing message (activity resumed)")

                        // 🎯 ENHANCED: Multiple fallback approaches for message matching
                        when (message) {
                            // 🎯 PRIMARY: Try direct type matching first
                            is TaskTimeoutMessage -> {
                                Log.d("TaskControl", "🎯 MATCHED TaskTimeoutMessage via direct 'is' check!")
                                handleTaskTimeout(message)
                            }

                            else -> {
                                // 🎯 FALLBACK 1: Check by message type enum
                                if (message.messageType == MessageType.TASK_TIMEOUT) {
                                    Log.d("TaskControl", "🎯 MATCHED by MessageType.TASK_TIMEOUT!")
                                    try {
                                        val timeoutMessage = message as TaskTimeoutMessage
                                        handleTaskTimeout(timeoutMessage)
                                    } catch (e: ClassCastException) {
                                        Log.e("TaskControl", "❌ Failed to cast to TaskTimeoutMessage", e)
                                    }
                                }
                                // 🎯 FALLBACK 2: Check by class name string
                                else if (message::class.java.simpleName == "TaskTimeoutMessage") {
                                    Log.d("TaskControl", "🎯 MATCHED by class name!")
                                    try {
                                        val timeoutMessage = message as TaskTimeoutMessage
                                        handleTaskTimeout(timeoutMessage)
                                    } catch (e: ClassCastException) {
                                        Log.e("TaskControl", "❌ Failed to cast to TaskTimeoutMessage by class name", e)
                                    }
                                }
                                // 🎯 FALLBACK 3: Check by full class name
                                else if (message::class.java.name.contains("TaskTimeoutMessage")) {
                                    Log.d("TaskControl", "🎯 MATCHED by full class name!")
                                    try {
                                        val timeoutMessage = message as TaskTimeoutMessage
                                        handleTaskTimeout(timeoutMessage)
                                    } catch (e: ClassCastException) {
                                        Log.e("TaskControl", "❌ Failed to cast to TaskTimeoutMessage by full class name", e)
                                    }
                                }
                                // Handle other message types
                                else {
                                    when (message) {
                                        // Enhanced Stroop messages from updated Projector
                                        is StroopStartedMessage -> {
                                            Log.d("TaskControl", "MATCHED StroopStartedMessage")
                                            handleStroopStarted(message)
                                        }

                                        is StroopEndedMessage -> {
                                            Log.d("TaskControl", "MATCHED StroopEndedMessage")
                                            handleStroopEnded(message)
                                        }

                                        // LEGACY: Keep old message handlers for compatibility
                                        is StroopDisplayMessage -> {
                                            Log.d("TaskControl", "MATCHED StroopDisplayMessage (legacy)")
                                            handleStroopDisplay(message)
                                        }

                                        is StroopHiddenMessage -> {
                                            Log.d("TaskControl", "MATCHED StroopHiddenMessage (legacy)")
                                            handleStroopHidden()
                                        }

                                        // Connection management messages
                                        is HeartbeatMessage -> {
                                            Log.v("TaskControl", "Heartbeat received - connection alive")
                                            // Ignore heartbeats - they're just for connection monitoring
                                        }

                                        is HandshakeResponseMessage -> {
                                            Log.d("TaskControl", "Handshake response received")
                                            // Ignore handshake responses - they're handled by the connection logic
                                        }

                                        else -> {
                                            Log.w("TaskControl", "❌ UNMATCHED MESSAGE TYPE")
                                            Log.w("TaskControl", "Message class: ${message::class.java.name}")
                                            Log.w("TaskControl", "Available interfaces: ${message::class.java.interfaces.contentToString()}")
                                            Log.w("TaskControl", "Message string: $message")
                                            Log.w("TaskControl", "Message superclass: ${message::class.java.superclass?.name}")

                                            // Still forward to TaskControlManager for other message types
                                            Log.d("TaskControl", "Forwarding to TaskControlManager...")
                                            taskControlManager.handleTaskMessage(message)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Log.w("TaskControl", "Activity not resumed, ignoring message: ${message::class.simpleName}")
                        Log.w("TaskControl", "Current state: ${lifecycle.currentState}, Required: ${androidx.lifecycle.Lifecycle.State.RESUMED}")
                    }
                }
            } catch (e: Exception) {
                Log.e("TaskControl", "💥 CRITICAL ERROR in message listener", e)
                Log.e("TaskControl", "Exception type: ${e::class.java.simpleName}")
                Log.e("TaskControl", "Exception message: ${e.message}")
                Log.e("TaskControl", "Stack trace:")
                e.printStackTrace()
            }

            Log.w("TaskControl", "⚠️ Message collection coroutine ended - this should not happen!")
        }

        Log.d("TaskControl", "=== MESSAGE LISTENER SETUP COMPLETE ===")
    }

    /**
     * Enhanced task timeout handling with comprehensive state management
     */
    private fun handleTaskTimeout(message: TaskTimeoutMessage) {
        Log.d("TaskControl", "🕒 Task timed out: ${message.taskId}")
        Log.d("TaskControl", "Duration: ${message.actualDuration}ms")
        Log.d("TaskControl", "Stroops displayed: ${message.stroopsDisplayed}")
        Log.d("TaskControl", "Current Stroop active: $isStroopActive")
        Log.d("TaskControl", "Current Stroop word: $currentStroopWord")
        Log.d("TaskControl", "Task already timed out: $isTaskTimedOut")

        // Prevent duplicate timeout handling
        if (isTaskTimedOut) {
            Log.w("TaskControl", "Task already timed out, ignoring duplicate timeout message")
            return
        }

        // Handle UI updates with appropriate delay for active Stroops
        if (isStroopActive && currentStroopWord != null) {
            Log.d("TaskControl", "Stroop currently active - delaying timeout UI for 1 second")
            binding.root.postDelayed({
                if (!isTaskTimedOut) { // Double-check to prevent race conditions
                    showTaskTimeout(message)
                }
            }, 1000) // 1 second delay
        } else {
            Log.d("TaskControl", "No active Stroop - showing timeout UI immediately")
            showTaskTimeout(message)
        }

        // ENHANCED: Notify TaskControlNetworkManager for state synchronization
        try {
            Log.d("TaskControl", "Notifying TaskControlManager of timeout for state synchronization")
            taskControlManager.notifyTaskTimeout(
                message.taskId,
                message.actualDuration,
                message.stroopsDisplayed
            )
            Log.d("TaskControl", "✅ TaskControlManager notified successfully")
        } catch (e: Exception) {
            Log.e("TaskControl", "❌ Error notifying TaskControlManager of timeout", e)
            // Don't fail the UI update if state sync fails
        }
    }

    /**
     * Show task timeout state
     */
    private fun showTaskTimeout(message: TaskTimeoutMessage) {
        Log.d("TaskControl", "Showing task timeout UI")

        isTaskTimedOut = true

        // Update status
        binding.textStroopStatus.text = "Task Timeout Reached"

        // Clear current Stroop display
        binding.textCurrentColor.text = "Task Completed"
        binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)

        // Disable all task control buttons
        binding.btnTriggerStroop.isEnabled = false
        binding.btnPauseTask.isEnabled = false
        binding.btnResumeTask.isEnabled = false
        binding.btnResetTask.isEnabled = false

        // Disable task completion buttons (they're no longer relevant)
        binding.btnTaskSuccess.isEnabled = false
        binding.btnTaskFailed.isEnabled = false
        binding.btnTaskGivenUp.isEnabled = false

        // Disable response buttons
        setResponseButtonsState(false)

        // Show return button
        binding.btnReturnToTasks.visibility = View.VISIBLE
        binding.btnReturnToTasks.isEnabled = true

        // Show timeout notification
        val timeoutSeconds = message.actualDuration / 1000
        Snackbar.make(
            binding.root,
            "Task timed out after ${timeoutSeconds}s. ${message.stroopsDisplayed} Stroops displayed.",
            Snackbar.LENGTH_LONG
        ).show()

        // Reset state
        isStroopActive = false
        currentStroopWord = null
        responseButtonsActive = false

        Log.d("TaskControl", "Task timeout UI setup complete")
    }

    /**
     * Return to task selection
     */
    private fun returnToTaskSelection() {
        Log.d("TaskControl", "Returning to task selection")
        // Close this activity and return to task selection
        finish()
    }

    /**
     * Handle enhanced Stroop started message from updated Projector
     */
    private fun handleStroopStarted(message: StroopStartedMessage) {
        // Don't process new Stroops if task has timed out
        if (isTaskTimedOut) {
            Log.d("TaskControl", "Ignoring StroopStarted - task has timed out")
            return
        }

        Log.d("TaskControl", "Stroop started: index=${message.stroopIndex}, word='${message.word}', correctAnswer='${message.correctAnswer}'")

        currentStroopWord = message.correctAnswer
        isStroopActive = true

        // Display the word participant should say with optimal font size
        displayWordWithOptimalSize(message.correctAnswer)

        // Update status with enhanced information
        binding.textStroopStatus.text = "Stroop #${message.stroopIndex} Active - Listening for response"

        // Activate response buttons
        setResponseButtonsState(true)

        Log.d("TaskControl", "UI updated for enhanced Stroop display")
    }

    /**
     * Handle Stroop ended message from updated Projector
     */
    private fun handleStroopEnded(message: StroopEndedMessage) {
        // Don't process if task has timed out
        if (isTaskTimedOut) {
            Log.d("TaskControl", "Ignoring StroopEnded - task has timed out")
            return
        }

        Log.d("TaskControl", "Stroop ended: index=${message.stroopIndex}, reason=${message.endReason}, duration=${message.displayDuration}ms")

        isStroopActive = false

        // If no response was recorded, keep buttons active for 1 second grace period
        if (responseButtonsActive) {
            // Schedule deactivation after 1 second
            binding.root.postDelayed({
                if (!isStroopActive && !isTaskTimedOut) { // Only deactivate if no new stroop started and not timed out
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
     * LEGACY: Handle old Stroop display message format (for compatibility)
     */
    private fun handleStroopDisplay(message: StroopDisplayMessage) {
        // Don't process if task has timed out
        if (isTaskTimedOut) {
            Log.d("TaskControl", "Ignoring legacy StroopDisplay - task has timed out")
            return
        }

        Log.d("TaskControl", "Legacy Stroop displayed: word=${message.word}, correctAnswer=${message.correctAnswer}")

        currentStroopWord = message.correctAnswer
        isStroopActive = true

        // Display the word participant should say with optimal font size
        displayWordWithOptimalSize(message.correctAnswer)

        // Update status
        binding.textStroopStatus.text = "Stroop Active - Listening for response"

        // Activate response buttons
        setResponseButtonsState(true)

        Log.d("TaskControl", "UI updated for legacy Stroop display")
    }

    private fun handleStroopHidden() {
        // Don't process if task has timed out
        if (isTaskTimedOut) {
            Log.d("TaskControl", "Ignoring StroopHidden - task has timed out")
            return
        }

        Log.d("TaskControl", "Stroop hidden")

        isStroopActive = false

        // If no response was recorded, keep buttons active for 1 second grace period
        if (responseButtonsActive) {
            // Schedule deactivation after 1 second
            binding.root.postDelayed({
                if (!isStroopActive && !isTaskTimedOut) { // Only deactivate if no new stroop started and not timed out
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
        // Set the word
        binding.textCurrentColor.text = word

        // Calculate optimal font size after layout is complete
        binding.textCurrentColor.post {
            val optimalSize = calculateOptimalFontSize(word, binding.textCurrentColor.width, binding.textCurrentColor.height)
            binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, optimalSize)
        }

        Log.d("TaskControl", "Displaying word '$word'")
    }

    private fun calculateOptimalFontSize(text: String, availableWidth: Int, availableHeight: Int): Float {
        if (availableWidth <= 0 || availableHeight <= 0) {
            return 48f // Default size if dimensions not available yet
        }

        val paint = binding.textCurrentColor.paint
        var testSize = 100f // Start with large size
        val maxSize = 100f
        val minSize = 16f

        // Account for padding
        val usableWidth = availableWidth * 0.8f
        val usableHeight = availableHeight * 0.8f

        // Binary search for optimal size
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
        // Don't enable response buttons if task has timed out
        val shouldBeActive = active && !isTaskTimedOut

        responseButtonsActive = shouldBeActive

        binding.btnResponseCorrect.isEnabled = shouldBeActive
        binding.btnResponseIncorrect.isEnabled = shouldBeActive

        if (shouldBeActive) {
            // Set active colors
            binding.btnResponseCorrect.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.btnResponseIncorrect.setBackgroundColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.btnResponseCorrect.setTextColor(ContextCompat.getColor(this, android.R.color.white))
            binding.btnResponseIncorrect.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            // Set disabled colors
            val disabledColor = ContextCompat.getColor(this, android.R.color.darker_gray)
            binding.btnResponseCorrect.setBackgroundColor(disabledColor)
            binding.btnResponseIncorrect.setBackgroundColor(disabledColor)
            binding.btnResponseCorrect.setTextColor(ContextCompat.getColor(this, android.R.color.black))
            binding.btnResponseIncorrect.setTextColor(ContextCompat.getColor(this, android.R.color.black))
        }
    }

    private fun showTaskReady() {
        isTaskTimedOut = false // Reset timeout state
        binding.textStroopStatus.text = "Task ready - Press START to begin"
        binding.textCurrentColor.text = "Waiting..."
        binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
        currentStroopWord = null
        isStroopActive = false
        setResponseButtonsState(false)
        binding.btnReturnToTasks.visibility = View.GONE
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

    private fun showNotConnected() {
        binding.textStroopStatus.text = "Not Connected to Projector"
        binding.textCurrentColor.text = "Please reconnect"

        // Disable all control buttons
        updateButtonStates(TaskState.Error("CONNECTION_ERROR", "Not connected"))
        setResponseButtonsState(false)
    }

    private fun showSessionError() {
        binding.textStroopStatus.text = "No Session Available"
        binding.textCurrentColor.text = "Please restart from participant info"

        // Disable all control buttons
        updateButtonStates(TaskState.Error("SESSION_ERROR", "No session"))
        setResponseButtonsState(false)

        Snackbar.make(
            binding.root,
            "No session found. Please go back and enter participant information.",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun recordParticipantResponse(isCorrect: Boolean) {
        // Don't record responses if task has timed out
        if (isTaskTimedOut) {
            Log.d("TaskControl", "Ignoring response - task has timed out")
            return
        }

        val responseType = if (isCorrect) "CORRECT" else "INCORRECT"
        Log.d("TaskControl", "Recording participant response: $responseType for word: $currentStroopWord")

        // TODO: Send participant response to data collection system
        // This data will be used for calculating error rates and reaction times

        // Immediately deactivate response buttons
        setResponseButtonsState(false)

        // Show feedback
        val message = if (isCorrect) {
            "Recorded: Participant correctly named the color"
        } else {
            "Recorded: Participant incorrectly named the color"
        }

        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()

        // Update status
        binding.textStroopStatus.text = "Response recorded - Waiting for next Stroop"
    }

    private fun startTask() {
        val taskIdValue = taskId ?: return
        val sessionIdValue = currentSessionId ?: return

        Log.d("TaskControl", "Starting task: $taskIdValue")

        // Reset timeout state when starting new task
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

        Log.d("TaskControl", "Pausing task: $taskIdValue")

        lifecycleScope.launch {
            val success = taskControlManager.pauseTask(taskIdValue, sessionIdValue)

            if (!success) {
                Snackbar.make(
                    binding.root,
                    "Failed to pause task.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun resumeTask() {
        val taskIdValue = taskId ?: return
        val sessionIdValue = currentSessionId ?: return

        Log.d("TaskControl", "Resuming task: $taskIdValue")

        lifecycleScope.launch {
            val success = taskControlManager.resumeTask(taskIdValue, sessionIdValue)

            if (!success) {
                Snackbar.make(
                    binding.root,
                    "Failed to resume task.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun resetTask() {
        val taskIdValue = taskId ?: return
        val sessionIdValue = currentSessionId ?: return

        Log.d("TaskControl", "Resetting task: $taskIdValue")

        lifecycleScope.launch {
            val success = taskControlManager.resetTask(taskIdValue, sessionIdValue)

            if (success) {
                showTaskReady()
            } else {
                Snackbar.make(
                    binding.root,
                    "Failed to reset task.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun completeTask(condition: String) {
        val taskIdValue = taskId ?: return
        val sessionIdValue = currentSessionId ?: return

        Log.d("TaskControl", "Completing task: $taskIdValue with condition: $condition")

        lifecycleScope.launch {
            val success = taskControlManager.endTask(taskIdValue, condition, sessionIdValue)

            if (!success) {
                Snackbar.make(
                    binding.root,
                    "Failed to end task.",
                    Snackbar.LENGTH_LONG
                ).show()
            }
            // Success feedback is handled by TaskState.Completed in updateButtonStates
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}