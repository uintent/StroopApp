package com.research.master

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.research.master.databinding.ActivityTaskSummaryBinding
import com.research.master.utils.SessionManager
import com.research.master.utils.TaskCompletionData
import com.research.master.utils.FileManager
import com.research.master.utils.MasterConfigManager
import kotlinx.coroutines.launch

/**
 * TaskSummaryActivity - Display task metrics and allow revision
 * ENHANCED: Now supports task list navigation with context-aware buttons
 * Handles Success -> ASQ flow and non-Success -> Next Task / Return to List flow
 * Receives raw stroop data from ColorDisplayActivity
 * Calculates aggregated metrics and handles FileManager communication
 */
class TaskSummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskSummaryBinding
    private lateinit var fileManager: FileManager

    // Task information from ColorDisplayActivity - UPDATED
    private var taskNumber: Int = 0               // NEW: Int task number
    private var taskId: String? = null            // Keep for display purposes
    private var iterationCounter: Int = 0         // NEW: Iteration counter
    private var taskLabel: String? = null
    private var taskText: String? = null
    private var originalEndCondition: String = ""
    private var currentEndCondition: String = ""
    private var timeOnTaskMs: Long = 0
    private var countdownDurationMs: Long = 0
    private var taskStartTime: Long = 0
    private var taskEndTime: Long = 0
    private var sessionId: String? = null
    private var isIndividualTask: Boolean = true

    // Task list context - NEW
    private var taskListId: String? = null
    private var taskListLabel: String? = null
    private var isInTaskList: Boolean = false
    private var nextTaskId: String? = null
    private var isLastTaskInList: Boolean = false

    // Raw stroop response data
    private var stroopResponses: List<StroopResponseData> = emptyList()

    /**
     * Data class to hold individual stroop responses
     */
    data class StroopResponseData(
        val stroopIndex: Int,
        val word: String,
        val correctAnswer: String,
        val response: String, // "CORRECT", "INCORRECT", or "MISSED"
        val reactionTimeMs: Long,
        val startTime: Long,
        val responseTime: Long // -1 if missed
    )

    /**
     * Calculated metrics
     */
    data class TaskMetrics(
        val totalStroops: Int,
        val successfulStroops: Int,
        val meanReactionTimeSuccessful: Double?,
        val incorrectStroops: Int,
        val meanReactionTimeIncorrect: Double?,
        val meanReactionTimeOverall: Double?,
        val missedStroops: Int,
        val timeOnTaskSeconds: Double
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityTaskSummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize FileManager
        fileManager = FileManager(this)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.task_summary_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        Log.d("TaskSummary", "=== TASK SUMMARY ACTIVITY STARTED ===")

        // Extract data from intent
        extractIntentData()

        // Determine task list context and next task
        determineTaskListContext()

        // Calculate metrics
        val metrics = calculateMetrics()

        // Display summary
        displayTaskSummary(metrics)

        // Set up button listeners with context-aware behavior
        setupButtons(metrics)

        Log.d("TaskSummary", "Task summary setup complete")
    }

    /**
     * Extract raw data from ColorDisplayActivity intent
     * UPDATED: Now extracts task list context
     */
    private fun extractIntentData() {
        Log.d("TaskSummary", "=== EXTRACTING INTENT DATA ===")

        // UPDATED: Extract task number and iteration counter
        taskNumber = intent.getIntExtra("TASK_NUMBER", 0)
        taskId = intent.getStringExtra("TASK_ID")
        iterationCounter = intent.getIntExtra("ITERATION_COUNTER", 0)

        // Basic task info
        taskLabel = intent.getStringExtra("TASK_LABEL")
        taskText = intent.getStringExtra("TASK_TEXT")
        originalEndCondition = intent.getStringExtra("END_CONDITION") ?: getString(R.string.task_summary_end_condition_unknown)
        currentEndCondition = originalEndCondition
        timeOnTaskMs = intent.getLongExtra("TIME_ON_TASK_MS", 0)
        countdownDurationMs = intent.getLongExtra("COUNTDOWN_DURATION_MS", 4000)
        taskStartTime = intent.getLongExtra("TASK_START_TIME", 0)
        taskEndTime = intent.getLongExtra("TASK_END_TIME", 0)
        sessionId = intent.getStringExtra("SESSION_ID")
        isIndividualTask = intent.getBooleanExtra("IS_INDIVIDUAL_TASK", true)

        // NEW: Extract task list context
        taskListId = intent.getStringExtra("TASK_LIST_ID")
        taskListLabel = intent.getStringExtra("TASK_LIST_LABEL")
        isInTaskList = taskListId != null

        Log.d("TaskSummary", "Task Number: $taskNumber")
        Log.d("TaskSummary", "Task ID: $taskId")
        Log.d("TaskSummary", "Iteration Counter: $iterationCounter")
        Log.d("TaskSummary", "Task Label: $taskLabel")
        Log.d("TaskSummary", "End Condition: $originalEndCondition")
        Log.d("TaskSummary", "Is Individual Task: $isIndividualTask")
        Log.d("TaskSummary", "Task List ID: $taskListId")
        Log.d("TaskSummary", "Time on Task: ${timeOnTaskMs}ms")

        // Extract raw stroop data arrays
        val stroopIndices = intent.getIntArrayExtra("STROOP_INDICES") ?: intArrayOf()
        val stroopWords = intent.getStringArrayExtra("STROOP_WORDS") ?: arrayOf()
        val stroopAnswers = intent.getStringArrayExtra("STROOP_ANSWERS") ?: arrayOf()
        val stroopResponseTypes = intent.getStringArrayExtra("STROOP_RESPONSES") ?: arrayOf()
        val stroopReactionTimes = intent.getLongArrayExtra("STROOP_REACTION_TIMES") ?: longArrayOf()
        val stroopStartTimes = intent.getLongArrayExtra("STROOP_START_TIMES") ?: longArrayOf()
        val stroopResponseTimes = intent.getLongArrayExtra("STROOP_RESPONSE_TIMES") ?: longArrayOf()

        Log.d("TaskSummary", "Raw stroop data arrays:")
        Log.d("TaskSummary", "  Indices: ${stroopIndices.size} entries")
        Log.d("TaskSummary", "  Words: ${stroopWords.size} entries")
        Log.d("TaskSummary", "  Responses: ${stroopResponseTypes.size} entries")

        // Convert arrays to structured data
        stroopResponses = stroopIndices.indices.map { i ->
            StroopResponseData(
                stroopIndex = stroopIndices.getOrNull(i) ?: 0,
                word = stroopWords.getOrNull(i) ?: "unknown",
                correctAnswer = stroopAnswers.getOrNull(i) ?: "unknown",
                response = stroopResponseTypes.getOrNull(i) ?: "MISSED",
                reactionTimeMs = stroopReactionTimes.getOrNull(i) ?: -1L,
                startTime = stroopStartTimes.getOrNull(i) ?: 0L,
                responseTime = stroopResponseTimes.getOrNull(i) ?: -1L
            )
        }

        Log.d("TaskSummary", "Converted to ${stroopResponses.size} stroop response objects")
        Log.d("TaskSummary", "=== INTENT DATA EXTRACTION COMPLETE ===")
    }

    /**
     * Determine task list context and find next task
     * NEW: Analyzes task position in list and determines navigation options
     */
    private fun determineTaskListContext() {
        if (!isInTaskList || taskListId == null) {
            Log.d("TaskSummary", "Not in task list mode - individual task")
            return
        }

        lifecycleScope.launch {
            try {
                val config = MasterConfigManager.getCurrentConfig()
                if (config == null) {
                    Log.e("TaskSummary", "No config available for task list analysis")
                    return@launch
                }

                val taskListConfig = config.baseConfig.taskLists[taskListId]
                if (taskListConfig == null) {
                    Log.e("TaskSummary", "Task list '$taskListId' not found in config")
                    return@launch
                }

                val taskIds = taskListConfig.getTaskIds()
                val currentTaskIndex = taskIds.indexOf(taskId)

                if (currentTaskIndex == -1) {
                    Log.w("TaskSummary", "Current task '$taskId' not found in task list sequence")
                    isLastTaskInList = true
                    return@launch
                }

                // Check if this is the last task
                isLastTaskInList = currentTaskIndex >= taskIds.size - 1

                if (!isLastTaskInList) {
                    // Get next task ID and verify it exists in config
                    val nextTaskIdCandidate = taskIds[currentTaskIndex + 1]
                    if (config.baseConfig.tasks.containsKey(nextTaskIdCandidate)) {
                        nextTaskId = nextTaskIdCandidate
                        Log.d("TaskSummary", "Next task in list: $nextTaskId")
                    } else {
                        Log.w("TaskSummary", "Next task '$nextTaskIdCandidate' not found in config")
                        isLastTaskInList = true
                    }
                } else {
                    Log.d("TaskSummary", "This is the last task in the list")
                }

                Log.d("TaskSummary", "Task list analysis complete:")
                Log.d("TaskSummary", "  Task position: ${currentTaskIndex + 1}/${taskIds.size}")
                Log.d("TaskSummary", "  Is last task: $isLastTaskInList")
                Log.d("TaskSummary", "  Next task ID: $nextTaskId")

            } catch (e: Exception) {
                Log.e("TaskSummary", "Error analyzing task list context", e)
                isLastTaskInList = true
            }
        }
    }

    /**
     * Calculate aggregated metrics from raw stroop data
     */
    private fun calculateMetrics(): TaskMetrics {
        Log.d("TaskSummary", "=== CALCULATING METRICS ===")

        if (stroopResponses.isEmpty()) {
            Log.w("TaskSummary", getString(R.string.task_summary_no_responses_warning))
            return TaskMetrics(0, 0, null, 0, null, null, 0, timeOnTaskMs / 1000.0)
        }

        // Separate responses by type
        val correctResponses = stroopResponses.filter { it.response == "CORRECT" }
        val incorrectResponses = stroopResponses.filter { it.response == "INCORRECT" }
        val missedResponses = stroopResponses.filter { it.response == "MISSED" }

        Log.d("TaskSummary", "Response breakdown:")
        Log.d("TaskSummary", "  Correct: ${correctResponses.size}")
        Log.d("TaskSummary", "  Incorrect: ${incorrectResponses.size}")
        Log.d("TaskSummary", "  Missed: ${missedResponses.size}")
        Log.d("TaskSummary", "  Total: ${stroopResponses.size}")

        // Calculate reaction times (only for responses that weren't missed)
        val correctReactionTimes = correctResponses.filter { it.reactionTimeMs > 0 }.map { it.reactionTimeMs.toDouble() }
        val incorrectReactionTimes = incorrectResponses.filter { it.reactionTimeMs > 0 }.map { it.reactionTimeMs.toDouble() }
        val allAnsweredReactionTimes = (correctReactionTimes + incorrectReactionTimes)

        // Calculate averages
        val meanCorrectReactionTime = if (correctReactionTimes.isNotEmpty()) {
            correctReactionTimes.average()
        } else null

        val meanIncorrectReactionTime = if (incorrectReactionTimes.isNotEmpty()) {
            incorrectReactionTimes.average()
        } else null

        val overallMeanReactionTime = if (allAnsweredReactionTimes.isNotEmpty()) {
            allAnsweredReactionTimes.average()
        } else null

        val timeOnTaskSeconds = timeOnTaskMs / 1000.0

        val metrics = TaskMetrics(
            totalStroops = stroopResponses.size,
            successfulStroops = correctResponses.size,
            meanReactionTimeSuccessful = meanCorrectReactionTime,
            incorrectStroops = incorrectResponses.size,
            meanReactionTimeIncorrect = meanIncorrectReactionTime,
            meanReactionTimeOverall = overallMeanReactionTime,
            missedStroops = missedResponses.size,
            timeOnTaskSeconds = timeOnTaskSeconds
        )

        Log.d("TaskSummary", "=== METRICS CALCULATION COMPLETE ===")
        return metrics
    }

    /**
     * Display task summary with calculated metrics
     * UPDATED: Shows task number and iteration counter
     */
    private fun displayTaskSummary(metrics: TaskMetrics) {
        Log.d("TaskSummary", "=== DISPLAYING TASK SUMMARY ===")

        // Task header - UPDATED to show iteration info
        binding.textTaskTitle.text = taskLabel ?: getString(R.string.task_summary_unknown_task)
        val taskDisplayText = if (iterationCounter > 0) {
            getString(R.string.task_summary_task_iteration_label, taskNumber, iterationCounter + 1)
        } else {
            getString(R.string.task_summary_task_label, taskNumber)
        }
        binding.textTaskId.text = taskDisplayText

        // Metrics display
        binding.textSuccessfulStroops.text = metrics.successfulStroops.toString()
        binding.textMeanReactionTimeSuccessful.text = getString(
            R.string.task_summary_mean_rt_correct,
            metrics.meanReactionTimeSuccessful?.let {
                getString(R.string.task_summary_reaction_time_ms, it)
            } ?: getString(R.string.task_summary_not_available)
        )

        binding.textIncorrectStroops.text = metrics.incorrectStroops.toString()
        binding.textMeanReactionTimeIncorrect.text = getString(
            R.string.task_summary_mean_rt_incorrect,
            metrics.meanReactionTimeIncorrect?.let {
                getString(R.string.task_summary_reaction_time_ms, it)
            } ?: getString(R.string.task_summary_not_available)
        )

        binding.textMeanReactionTimeOverall.text = getString(
            R.string.task_summary_mean_rt_overall,
            metrics.meanReactionTimeOverall?.let {
                getString(R.string.task_summary_reaction_time_ms, it)
            } ?: getString(R.string.task_summary_not_available)
        )

        binding.textMissedStroops.text = metrics.missedStroops.toString()
        binding.textFinalRating.text = getLocalizedEndCondition(currentEndCondition)
        binding.textTimeOnTask.text = getString(R.string.task_summary_time_seconds, metrics.timeOnTaskSeconds)

        Log.d("TaskSummary", "Task summary displayed in UI")
    }

    /**
     * Set up button listeners with context-aware behavior
     * ENHANCED: Now provides different options based on end condition and task list context
     */
    private fun setupButtons(metrics: TaskMetrics) {
        // Continue button - behavior depends on end condition
        binding.btnContinue.setOnClickListener {
            continueToNextStep(metrics)
        }

        // Revise result button
        binding.btnReviseResult.setOnClickListener {
            showRevisionDialog()
        }

        // Update button text based on end condition
        updateContinueButtonText()
    }

    /**
     * Update continue button text based on current end condition
     * NEW: Context-aware button labeling
     */
    private fun updateContinueButtonText() {
        val isSuccess = currentEndCondition == getString(R.string.task_summary_end_condition_success)

        binding.btnContinue.text = when {
            isSuccess -> getString(R.string.task_summary_btn_to_asq)
            isInTaskList && !isLastTaskInList && nextTaskId != null -> getString(R.string.task_summary_btn_continue_next_task)
            else -> getString(R.string.task_summary_btn_continue)
        }
    }

    /**
     * Continue to next step based on end condition and task list context
     * ENHANCED: Now supports task list navigation
     */
    private fun continueToNextStep(metrics: TaskMetrics) {
        Log.d("TaskSummary", "Continuing with end condition: $currentEndCondition")

        // First, save the task data to SessionManager
        saveTaskData(metrics)

        val isSuccess = currentEndCondition == getString(R.string.task_summary_end_condition_success)

        when {
            isSuccess -> {
                // Success -> Navigate to ASQ
                Log.d("TaskSummary", "Success condition - navigating to ASQ")
                navigateToASQ()
            }
            isInTaskList && !isLastTaskInList && nextTaskId != null -> {
                // Non-success in task list with next task available -> Show options
                showNextTaskDialog()
            }
            else -> {
                // Individual task or last task in list -> Return to task selection
                Log.d("TaskSummary", "Individual task or last task - returning to task selection")
                navigateToTaskSelection()
            }
        }
    }

    /**
     * Show dialog with next task and return options
     * NEW: Provides choice between continuing with next task or returning to list
     */
    private fun showNextTaskDialog() {
        lifecycleScope.launch {
            try {
                val config = MasterConfigManager.getCurrentConfig()
                val nextTaskConfig = config?.baseConfig?.tasks?.get(nextTaskId)
                val nextTaskLabel = nextTaskConfig?.label ?: "Task $nextTaskId"

                androidx.appcompat.app.AlertDialog.Builder(this@TaskSummaryActivity)
                    .setTitle("Task Complete")
                    .setMessage("What would you like to do next?")
                    .setPositiveButton(getString(R.string.task_summary_btn_continue_next_task)) { _, _ ->
                        navigateToNextTask()
                    }
                    .setNegativeButton(getString(R.string.task_summary_btn_return_to_task_list)) { _, _ ->
                        navigateToTaskSelection()
                    }
                    .setCancelable(false)
                    .show()

            } catch (e: Exception) {
                Log.e("TaskSummary", "Error showing next task dialog", e)
                navigateToTaskSelection()
            }
        }
    }

    /**
     * Navigate to next task in the task list
     * NEW: Starts ColorDisplayActivity with next task
     */
    private fun navigateToNextTask() {
        val nextTaskIdLocal = nextTaskId
        if (nextTaskIdLocal == null) {
            Log.e("TaskSummary", "No next task ID available")
            navigateToTaskSelection()
            return
        }

        lifecycleScope.launch {
            try {
                val config = MasterConfigManager.getCurrentConfig()
                val nextTaskConfig = config?.baseConfig?.tasks?.get(nextTaskIdLocal) // <- Now safe

                if (nextTaskConfig == null) {
                    Log.e("TaskSummary", "Next task config not found for ID: $nextTaskIdLocal")
                    navigateToTaskSelection()
                    return@launch
                }

                Log.d("TaskSummary", "Navigating to next task: $nextTaskId - ${nextTaskConfig.label}")

                val intent = Intent(this@TaskSummaryActivity, ColorDisplayActivity::class.java).apply {
                    putExtra("TASK_NUMBER", nextTaskIdLocal.toIntOrNull() ?: 0)
                    putExtra("TASK_ID", nextTaskIdLocal)
                    putExtra("TASK_LABEL", nextTaskConfig.label)
                    putExtra("TASK_TEXT", nextTaskConfig.text)
                    putExtra("TASK_TIMEOUT", nextTaskConfig.timeoutSeconds)
                    putExtra("IS_INDIVIDUAL_TASK", false) // Part of task list
                    putExtra("TASK_LIST_ID", taskListId)
                    putExtra("TASK_LIST_LABEL", taskListLabel)
                }

                startActivity(intent)
                finish()

            } catch (e: Exception) {
                Log.e("TaskSummary", "Error navigating to next task", e)
                Snackbar.make(
                    binding.root,
                    "Failed to start next task: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
                navigateToTaskSelection()
            }
        }
    }

    /**
     * Save task data to SessionManager via FileManager
     * UPDATED: Now uses taskNumber and iterationCounter
     */
    private fun saveTaskData(metrics: TaskMetrics) {
        Log.d("TaskSummary", "=== SAVING TASK DATA ===")

        // Convert metrics back to percentages for SessionManager compatibility
        val totalStroops = metrics.totalStroops
        val correctPercentage = if (totalStroops > 0) (metrics.successfulStroops.toDouble() / totalStroops.toDouble()) * 100.0 else 0.0
        val incorrectPercentage = if (totalStroops > 0) (metrics.incorrectStroops.toDouble() / totalStroops.toDouble()) * 100.0 else 0.0

        // Create TaskCompletionData - UPDATED with new fields
        val taskCompletionData = TaskCompletionData(
            taskNumber = taskNumber,
            iterationCounter = iterationCounter,
            taskLabel = taskLabel ?: "Unknown Task",
            timeRequiredMs = timeOnTaskMs,
            endCondition = currentEndCondition,
            stroopErrorRateCorrect = correctPercentage,
            stroopErrorRateIncorrect = incorrectPercentage,
            stroopTotalCount = totalStroops,
            stroopAverageReactionTime = metrics.meanReactionTimeOverall ?: 0.0,
            stroopIndividualTimes = stroopResponses
                .filter { it.response != "MISSED" && it.reactionTimeMs > 0 }
                .map { it.reactionTimeMs.toDouble() },
            asqScores = emptyMap(),
            taskEndTime = taskEndTime
        )

        Log.d("TaskSummary", "TaskCompletionData created:")
        Log.d("TaskSummary", "  Task Number: ${taskCompletionData.taskNumber}")
        Log.d("TaskSummary", "  Iteration Counter: ${taskCompletionData.iterationCounter}")
        Log.d("TaskSummary", "  End condition: ${taskCompletionData.endCondition}")

        // Save to SessionManager
        lifecycleScope.launch {
            try {
                SessionManager.addTaskData(taskCompletionData)
                Log.d("TaskSummary", getString(R.string.task_summary_data_saved))
            } catch (e: Exception) {
                Log.e("TaskSummary", getString(R.string.task_summary_data_save_failed), e)
                Snackbar.make(
                    binding.root,
                    getString(R.string.task_summary_save_error, e.message),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }

        Log.d("TaskSummary", "=== TASK DATA SAVE COMPLETE ===")
    }

    /**
     * Show dialog to revise the end condition
     * UPDATED: Updates button text after revision
     */
    private fun showRevisionDialog() {
        val options = arrayOf(
            getString(R.string.task_summary_end_condition_success),
            getString(R.string.task_summary_end_condition_failed),
            getString(R.string.task_summary_end_condition_given_up),
            getString(R.string.task_summary_end_condition_timed_out)
        )
        val currentIndex = options.indexOf(currentEndCondition).let { if (it >= 0) it else 0 }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.task_summary_revise_title))
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                val newEndCondition = options[which]
                if (newEndCondition != currentEndCondition) {
                    currentEndCondition = newEndCondition

                    // Recalculate and redisplay metrics
                    val metrics = calculateMetrics()
                    displayTaskSummary(metrics)

                    // Update button text based on new end condition
                    updateContinueButtonText()

                    Log.d("TaskSummary", "End condition revised from '$originalEndCondition' to '$currentEndCondition'")

                    Snackbar.make(
                        binding.root,
                        getString(R.string.task_summary_result_changed, currentEndCondition),
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.task_summary_revise_cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Navigate to ASQ activity
     * UPDATED: Now passes task list context
     */
    private fun navigateToASQ() {
        Log.d("TaskSummary", "Navigating to ASQActivity")

        val intent = Intent(this, ASQActivity::class.java)

        // Pass task information to ASQ activity
        intent.putExtra("TASK_NUMBER", taskNumber)
        intent.putExtra("TASK_ID", taskId)
        intent.putExtra("ITERATION_COUNTER", iterationCounter)
        intent.putExtra("TASK_LABEL", taskLabel)
        intent.putExtra("SESSION_ID", sessionId)
        intent.putExtra("IS_INDIVIDUAL_TASK", isIndividualTask)

        // Pass task list context
        if (isInTaskList) {
            intent.putExtra("TASK_LIST_ID", taskListId)
            intent.putExtra("TASK_LIST_LABEL", taskListLabel)
        }

        startActivity(intent)
        finish()
    }

    /**
     * Navigate back to task selection
     * ENHANCED: Context-aware navigation (task list vs global)
     */
    private fun navigateToTaskSelection() {
        Log.d("TaskSummary", "Navigating back to TaskSelectionActivity")

        val intent = Intent(this, TaskSelectionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

        // If we're in task list mode, return to that specific task list
        if (isInTaskList) {
            intent.putExtra("TASK_LIST_ID", taskListId)
            intent.putExtra("TASK_LIST_LABEL", taskListLabel)
        }

        startActivity(intent)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        navigateToTaskSelection()
        return true
    }

    override fun onBackPressed() {
        super.onBackPressed()
        navigateToTaskSelection()
    }

    /**
     * Get localized string for end condition
     */
    private fun getLocalizedEndCondition(endCondition: String): String {
        return when (endCondition) {
            "Success" -> getString(R.string.task_summary_end_condition_success)
            "Failed" -> getString(R.string.task_summary_end_condition_failed)
            "Given up" -> getString(R.string.task_summary_end_condition_given_up)
            "Timed Out" -> getString(R.string.task_summary_end_condition_timed_out)
            else -> getString(R.string.task_summary_end_condition_unknown)
        }
    }
}