// Replace the TaskSummaryActivity.kt file with this fixed version:

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
import kotlinx.coroutines.launch

/**
 * TaskSummaryActivity - Display task metrics and allow revision
 * Receives raw stroop data from ColorDisplayActivity
 * Calculates aggregated metrics and handles FileManager communication
 * Shows revision options and navigates to ASQ or TaskSelection
 */
class TaskSummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTaskSummaryBinding
    private lateinit var fileManager: FileManager

    // Raw data from ColorDisplayActivity
    private var taskId: String? = null
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

        // Calculate metrics
        val metrics = calculateMetrics()

        // Display summary
        displayTaskSummary(metrics)

        // Set up button listeners
        setupButtons(metrics)

        Log.d("TaskSummary", "Task summary setup complete")
    }

    /**
     * Extract raw data from ColorDisplayActivity intent
     */
    private fun extractIntentData() {
        Log.d("TaskSummary", "=== EXTRACTING INTENT DATA ===")

        // Basic task info
        taskId = intent.getStringExtra("TASK_ID")
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

        Log.d("TaskSummary", "Task ID: $taskId")
        Log.d("TaskSummary", "Task Label: $taskLabel")
        Log.d("TaskSummary", "End Condition: $originalEndCondition")
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

        // Log first few responses for debugging
        stroopResponses.take(3).forEachIndexed { index, response ->
            Log.d("TaskSummary", "  Response $index: #${response.stroopIndex} '${response.word}' -> ${response.response} (${response.reactionTimeMs}ms)")
        }

        Log.d("TaskSummary", "=== INTENT DATA EXTRACTION COMPLETE ===")
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

        Log.d("TaskSummary", "Calculated metrics:")
        Log.d("TaskSummary", "  Mean correct RT: ${meanCorrectReactionTime?.let { "%.1f ms".format(it) } ?: "N/A"}")
        Log.d("TaskSummary", "  Mean incorrect RT: ${meanIncorrectReactionTime?.let { "%.1f ms".format(it) } ?: "N/A"}")
        Log.d("TaskSummary", "  Overall mean RT: ${overallMeanReactionTime?.let { "%.1f ms".format(it) } ?: "N/A"}")
        Log.d("TaskSummary", "  Time on task: %.1f seconds".format(timeOnTaskSeconds))

        Log.d("TaskSummary", "=== METRICS CALCULATION COMPLETE ===")
        return metrics
    }

    /**
     * Display task summary with calculated metrics
     */
    private fun displayTaskSummary(metrics: TaskMetrics) {
        Log.d("TaskSummary", "=== DISPLAYING TASK SUMMARY ===")

        // Task header
        binding.textTaskTitle.text = taskLabel ?: getString(R.string.task_summary_unknown_task)
        binding.textTaskId.text = getString(R.string.task_summary_task_label, taskId ?: getString(R.string.task_summary_end_condition_unknown))

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
     * Set up button listeners
     */
    private fun setupButtons(metrics: TaskMetrics) {
        // Continue button
        binding.btnContinue.setOnClickListener {
            continueToNextStep(metrics)
        }

        // Revise result button
        binding.btnReviseResult.setOnClickListener {
            showRevisionDialog()
        }
    }

    /**
     * Continue to next step based on end condition
     */
    private fun continueToNextStep(metrics: TaskMetrics) {
        Log.d("TaskSummary", "Continuing with end condition: $currentEndCondition")

        // First, save the task data to SessionManager
        saveTaskData(metrics)

        when (currentEndCondition) {
            getString(R.string.task_summary_end_condition_success) -> {
                // Navigate to ASQ activity
                Log.d("TaskSummary", getString(R.string.task_summary_navigating_to_asq))
                navigateToASQ()
            }
            else -> {
                // Navigate back to task selection
                Log.d("TaskSummary", getString(R.string.task_summary_navigating_to_task_selection))
                navigateToTaskSelection()
            }
        }
    }

    /**
     * Save task data to SessionManager via FileManager
     */
    private fun saveTaskData(metrics: TaskMetrics) {
        Log.d("TaskSummary", "=== SAVING TASK DATA ===")

        // Convert metrics back to percentages for SessionManager compatibility
        val totalStroops = metrics.totalStroops
        val correctPercentage = if (totalStroops > 0) (metrics.successfulStroops.toDouble() / totalStroops.toDouble()) * 100.0 else 0.0
        val incorrectPercentage = if (totalStroops > 0) (metrics.incorrectStroops.toDouble() / totalStroops.toDouble()) * 100.0 else 0.0

        // Create TaskCompletionData
        val taskCompletionData = TaskCompletionData(
            taskId = taskId ?: "unknown",
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
            asqScores = emptyMap(), // Will be filled by ASQ activity if applicable
            taskEndTime = taskEndTime
        )

        Log.d("TaskSummary", "TaskCompletionData created:")
        Log.d("TaskSummary", "  End condition: ${taskCompletionData.endCondition}")
        Log.d("TaskSummary", "  Correct rate: %.1f%%".format(correctPercentage))
        Log.d("TaskSummary", "  Incorrect rate: %.1f%%".format(incorrectPercentage))
        Log.d("TaskSummary", "  Average RT: %.1f ms".format(taskCompletionData.stroopAverageReactionTime))

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
     * Navigate to ASQ activity (placeholder)
     */
    private fun navigateToASQ() {
        // TODO: Implement ASQActivity
        Log.d("TaskSummary", "TODO: Navigate to ASQActivity")

        Snackbar.make(
            binding.root,
            getString(R.string.task_summary_asq_not_implemented),
            Snackbar.LENGTH_LONG
        ).show()

        // Fallback to task selection for now
        navigateToTaskSelection()
    }

    /**
     * Navigate back to task selection
     */
    private fun navigateToTaskSelection() {
        Log.d("TaskSummary", "Navigating back to TaskSelectionActivity")

        val intent = Intent(this, TaskSelectionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        // Navigate back to task selection (don't go back to ColorDisplayActivity)
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