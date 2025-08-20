package com.research.master

import android.graphics.Color
import android.os.Bundle
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

/**
 * Task Control Screen - displays task information and provides controls for task execution
 * Shows current Stroop color from Projector and allows moderator to control task flow
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
    private var currentStroopColor: String? = null
    private var currentSessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityColorDisplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize task control manager
        taskControlManager = TaskControlNetworkManager(networkClient)

        // Get task information from intent
        extractTaskInfo()

        // Set up toolbar
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
        // Use new task info views
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
        hideResponseButtons()
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
        Log.d("TaskControl", "Stroop displayed: word=${message.word}, color=${message.displayColor}")

        try {
            val color = Color.parseColor(message.displayColor)
            currentStroopColor = message.displayColor

            // Update color display using new view IDs
            binding.viewCurrentColor.setBackgroundColor(color)

            // Show what color the participant should say (the correct answer)
            binding.textCurrentColor.text = message.correctAnswer
            binding.textStroopStatus.text = "Stroop Active - Listening for response"

            // Show response buttons
            showResponseButtons()

            Log.d("TaskControl", "UI updated for Stroop display")

        } catch (e: Exception) {
            Log.e("TaskControl", "Error parsing color: ${message.displayColor}", e)
            binding.textStroopStatus.text = "Error: Invalid color format"
        }
    }

    private fun handleStroopHidden() {
        Log.d("TaskControl", "Stroop hidden")
        hideResponseButtons()
        showWaitingForStroop()
    }

    private fun showTaskReady() {
        binding.viewCurrentColor.setBackgroundColor(Color.LTGRAY)
        binding.textStroopStatus.text = "Task ready - Press START to begin"
        binding.textCurrentColor.text = "Waiting..."
        currentStroopColor = null
        hideResponseButtons()
    }

    private fun showWaitingForStroop() {
        binding.viewCurrentColor.setBackgroundColor(Color.DKGRAY)
        binding.textStroopStatus.text = "Task active - Waiting for next Stroop"
        binding.textCurrentColor.text = "Interval period"
        currentStroopColor = null
        hideResponseButtons()
    }

    private fun showNotConnected() {
        binding.viewCurrentColor.setBackgroundColor(Color.RED)
        binding.textStroopStatus.text = "Not Connected to Projector"
        binding.textCurrentColor.text = "Please reconnect"

        // Disable all control buttons
        updateButtonStates(TaskState.Error("CONNECTION_ERROR", "Not connected"))
        hideResponseButtons()
    }

    private fun showSessionError() {
        binding.viewCurrentColor.setBackgroundColor(Color.RED)
        binding.textStroopStatus.text = "No Session Available"
        binding.textCurrentColor.text = "Please restart from participant info"

        // Disable all control buttons
        updateButtonStates(TaskState.Error("SESSION_ERROR", "No session"))
        hideResponseButtons()

        Snackbar.make(
            binding.root,
            "No session found. Please go back and enter participant information.",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun showResponseButtons() {
        binding.layoutResponseButtons.visibility = android.view.View.VISIBLE
    }

    private fun hideResponseButtons() {
        binding.layoutResponseButtons.visibility = android.view.View.GONE
    }

    private fun recordParticipantResponse(isCorrect: Boolean) {
        val responseType = if (isCorrect) "CORRECT" else "INCORRECT"
        Log.d("TaskControl", "Recording participant response: $responseType for color: $currentStroopColor")

        // TODO: Send participant response to data collection system
        // This data will be used for calculating error rates and reaction times

        // Hide response buttons after recording
        hideResponseButtons()

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