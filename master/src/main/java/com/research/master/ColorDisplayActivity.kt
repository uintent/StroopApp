package com.research.master

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.util.Log
import com.google.android.material.snackbar.Snackbar
import androidx.core.content.ContextCompat

/**
 * Task Control Screen - displays task information and provides controls for task execution
 * Shows current Stroop word from Projector and allows moderator to control task flow
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityColorDisplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize task control manager
        taskControlManager = TaskControlNetworkManager(networkClient)

        // Get task information from intent
        extractTaskInfo()

        // Set up custom toolbar (THIS FIXES THE DOUBLE TOOLBAR ISSUE)
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

        // Initial button states
        updateButtonStates(TaskState.Ready)
        setResponseButtonsState(false)
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
        when (taskState) {
            is TaskState.Ready -> {
                binding.btnTriggerStroop.isEnabled = true
                binding.btnPauseTask.isEnabled = false
                binding.btnResumeTask.isEnabled = false
                binding.btnResetTask.isEnabled = false
                binding.btnTaskSuccess.isEnabled = false
                binding.btnTaskFailed.isEnabled = false
                binding.btnTaskGivenUp.isEnabled = false
            }

            is TaskState.Starting, is TaskState.CountdownActive -> {
                binding.btnTriggerStroop.isEnabled = false
                binding.btnPauseTask.isEnabled = false
                binding.btnResumeTask.isEnabled = false
                binding.btnResetTask.isEnabled = true
                binding.btnTaskSuccess.isEnabled = false
                binding.btnTaskFailed.isEnabled = false
                binding.btnTaskGivenUp.isEnabled = false

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

                binding.textStroopStatus.text = "Task error: ${taskState.errorType}"
            }
        }
    }

    private fun observeNetworkMessages() {
        lifecycleScope.launch {
            networkClient.receiveMessages().collectLatest { message ->
                // Only process messages if activity is resumed
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    Log.d("TaskControl", "Received message: $message")

                    when (message) {
                        is StroopDisplayMessage -> {
                            handleStroopDisplay(message)
                        }
                        is StroopHiddenMessage -> {
                            handleStroopHidden()
                        }
                        is HeartbeatMessage -> {
                            // Ignore heartbeats - they're just for connection monitoring
                        }
                        is HandshakeResponseMessage -> {
                            // Ignore handshake responses - they're handled by the connection logic
                        }
                        else -> {
                            // Let task control manager handle task-related messages
                            taskControlManager.handleTaskMessage(message)
                            Log.d("TaskControl", "Message handled by TaskControlManager: ${message::class.simpleName}")
                        }
                    }
                } else {
                    Log.d("TaskControl", "Activity not resumed, ignoring message: ${message::class.simpleName}")
                }
            }
        }
    }

    private fun handleStroopDisplay(message: StroopDisplayMessage) {
        Log.d("TaskControl", "Stroop displayed: word=${message.word}, correctAnswer=${message.correctAnswer}")

        currentStroopWord = message.correctAnswer
        isStroopActive = true

        // Display the word participant should say with optimal font size
        displayWordWithOptimalSize(message.correctAnswer)

        // Update status
        binding.textStroopStatus.text = "Stroop Active - Listening for response"

        // Activate response buttons
        setResponseButtonsState(true)

        Log.d("TaskControl", "UI updated for Stroop display")
    }

    private fun handleStroopHidden() {
        Log.d("TaskControl", "Stroop hidden")

        isStroopActive = false

        // If no response was recorded, keep buttons active for 1 second grace period
        if (responseButtonsActive) {
            // Schedule deactivation after 1 second
            binding.root.postDelayed({
                if (!isStroopActive) { // Only deactivate if no new stroop started
                    setResponseButtonsState(false)
                    showWaitingForStroop()
                }
            }, 1000)
        } else {
            showWaitingForStroop()
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
        responseButtonsActive = active

        binding.btnResponseCorrect.isEnabled = active
        binding.btnResponseIncorrect.isEnabled = active

        if (active) {
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
        binding.textStroopStatus.text = "Task ready - Press START to begin"
        binding.textCurrentColor.text = "Waiting..."
        binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
        currentStroopWord = null
        isStroopActive = false
        setResponseButtonsState(false)
    }

    private fun showWaitingForStroop() {
        binding.textStroopStatus.text = "Task active - Waiting for next Stroop"
        binding.textCurrentColor.text = "Interval period"
        binding.textCurrentColor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
        currentStroopWord = null
        setResponseButtonsState(false)
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
        val responseType = if (isCorrect) "CORRECT" else "INCORRECT"
        Log.d("TaskControl", "Recording participant response: $responseType for word: $currentStroopWord")

        // TODO: Send participant response to data collection system
        // This data will be used for calculating error rates and reaction times

        // Immediately deactivate response buttons
        setResponseButtonsState(false)

        // Show feedback
        val message = if (isCorrect) {
            "✓ Recorded: Participant correctly named the color"
        } else {
            "✗ Recorded: Participant incorrectly named the color"
        }

        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()

        // Update status
        binding.textStroopStatus.text = "Response recorded - Waiting for next Stroop"
    }

    private fun startTask() {
        val taskIdValue = taskId ?: return
        val sessionIdValue = currentSessionId ?: return

        Log.d("TaskControl", "Starting task: $taskIdValue")

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