package com.research.master

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.research.master.databinding.ActivityAsqBinding
import com.research.master.utils.SessionManager
import com.research.master.utils.TaskCompletionData
import com.research.master.utils.FileManager
import com.research.master.utils.DebugLogger
import kotlinx.coroutines.launch

/**
 * ASQActivity - After Scenario Questionnaire
 * ENHANCED: Now supports task list context navigation
 * Back navigation returns to TaskSummaryActivity, Continue navigation respects task list context
 * Displays two 7-point Likert scale questions for successful tasks
 * Updates existing task data in SessionManager with ASQ responses
 */
class ASQActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAsqBinding
    private lateinit var fileManager: FileManager

    // Task information passed from TaskSummaryActivity - UPDATED
    private var taskNumber: Int = 0               // Int task number
    private var taskId: String? = null            // Keep for display purposes
    private var iterationCounter: Int = 0         // Iteration counter
    private var taskLabel: String? = null
    private var sessionId: String? = null
    private var isIndividualTask: Boolean = true

    // Task list context - NEW
    private var taskListId: String? = null
    private var taskListLabel: String? = null
    private var isInTaskList: Boolean = false

    // ASQ responses (1-7 scale, 0 = not answered)
    private var asqEaseResponse: Int = 0  // 0 = not answered, 1-7 = selected value
    private var asqTimeResponse: Int = 0  // 0 = not answered, 1-7 = selected value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAsqBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize DebugLogger
        DebugLogger.initialize(this)

        // Initialize FileManager
        fileManager = FileManager(this)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.asq_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        DebugLogger.d("ASQActivity", "=== ASQ ACTIVITY STARTED ===")

        // Extract data from intent
        extractIntentData()

        // Set up UI
        setupUI()

        // Set up button listeners
        setupButtons()

        DebugLogger.d("ASQActivity", "ASQ setup complete")
    }

    /**
     * Extract task data from TaskSummaryActivity intent
     * ENHANCED: Now extracts task list context for proper navigation
     */
    private fun extractIntentData() {
        DebugLogger.d("ASQActivity", "=== EXTRACTING INTENT DATA ===")

        // Extract task number and iteration counter
        taskNumber = intent.getIntExtra("TASK_NUMBER", 0)
        taskId = intent.getStringExtra("TASK_ID")
        iterationCounter = intent.getIntExtra("ITERATION_COUNTER", 0)

        taskLabel = intent.getStringExtra("TASK_LABEL")
        sessionId = intent.getStringExtra("SESSION_ID")
        isIndividualTask = intent.getBooleanExtra("IS_INDIVIDUAL_TASK", true)

        // NEW: Extract task list context
        taskListId = intent.getStringExtra("TASK_LIST_ID")
        taskListLabel = intent.getStringExtra("TASK_LIST_LABEL")
        isInTaskList = taskListId != null

        DebugLogger.d("ASQActivity", "Task Number: $taskNumber")
        DebugLogger.d("ASQActivity", "Task ID: $taskId")
        DebugLogger.d("ASQActivity", "Iteration Counter: $iterationCounter")
        DebugLogger.d("ASQActivity", "Task Label: $taskLabel")
        DebugLogger.d("ASQActivity", "Session ID: $sessionId")
        DebugLogger.d("ASQActivity", "Individual Task: $isIndividualTask")
        DebugLogger.d("ASQActivity", "Task List ID: $taskListId")
        DebugLogger.d("ASQActivity", "Is In Task List: $isInTaskList")

        DebugLogger.d("ASQActivity", "=== INTENT DATA EXTRACTION COMPLETE ===")
    }

    /**
     * Set up the UI with task information and questions
     * Shows iteration information
     */
    private fun setupUI() {
        DebugLogger.d("ASQActivity", "=== SETTING UP UI ===")

        // Display task information - shows iteration info
        binding.textTaskTitle.text = taskLabel ?: getString(R.string.asq_unknown_task)

        val taskDisplayText = if (iterationCounter > 0) {
            getString(R.string.asq_task_iteration_label, taskNumber, iterationCounter + 1) // +1 for user-friendly display
        } else {
            getString(R.string.asq_task_label, taskNumber)
        }
        binding.textTaskId.text = taskDisplayText

        // Set up radio group listeners
        setupRadioGroupListener(binding.radioGroupEase) { selectedValue ->
            asqEaseResponse = selectedValue
            DebugLogger.d("ASQActivity", "ASQ Ease response: $asqEaseResponse")
        }

        setupRadioGroupListener(binding.radioGroupTime) { selectedValue ->
            asqTimeResponse = selectedValue
            DebugLogger.d("ASQActivity", "ASQ Time response: $asqTimeResponse")
        }

        // Continue button is always enabled

        DebugLogger.d("ASQActivity", "UI setup complete")
    }

    /**
     * Set up radio group listener for Likert scale responses
     */
    private fun setupRadioGroupListener(radioGroup: android.widget.RadioGroup, onResponse: (Int) -> Unit) {
        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            // Handle both radio groups by checking which group this is
            val selectedValue = when (group.id) {
                R.id.radio_group_ease -> {
                    when (checkedId) {
                        R.id.radio_1 -> 1
                        R.id.radio_2 -> 2
                        R.id.radio_3 -> 3
                        R.id.radio_4 -> 4
                        R.id.radio_5 -> 5
                        R.id.radio_6 -> 6
                        R.id.radio_7 -> 7
                        else -> 0
                    }
                }
                R.id.radio_group_time -> {
                    when (checkedId) {
                        R.id.radio_time_1 -> 1
                        R.id.radio_time_2 -> 2
                        R.id.radio_time_3 -> 3
                        R.id.radio_time_4 -> 4
                        R.id.radio_time_5 -> 5
                        R.id.radio_time_6 -> 6
                        R.id.radio_time_7 -> 7
                        else -> 0
                    }
                }
                else -> 0
            }
            onResponse(selectedValue)
        }
    }

    /**
     * Set up button listeners
     */
    private fun setupButtons() {
        // Continue button - save ASQ data and navigate (always enabled)
        binding.btnContinue.setOnClickListener {
            saveASQDataAndContinue()
        }

        // Skip button (optional - for debugging or special cases)
        binding.btnSkip.setOnClickListener {
            showSkipConfirmationDialog()
        }
    }

    /**
     * Save ASQ responses to SessionManager and continue
     * ENHANCED: Context-aware navigation after saving
     */
    private fun saveASQDataAndContinue() {
        DebugLogger.d("ASQActivity", "=== SAVING ASQ DATA ===")
        DebugLogger.d("ASQActivity", "Task Number: $taskNumber")
        DebugLogger.d("ASQActivity", "Iteration Counter: $iterationCounter")
        DebugLogger.d("ASQActivity", "Ease response: $asqEaseResponse")
        DebugLogger.d("ASQActivity", "Time response: $asqTimeResponse")

        lifecycleScope.launch {
            try {
                // Update the task with ASQ data using taskNumber and iterationCounter
                val asqData = mapOf(
                    "ease" to asqEaseResponse.toString(),
                    "time" to asqTimeResponse.toString()
                )

                SessionManager.updateTaskASQData(taskNumber, iterationCounter, asqData)

                DebugLogger.d("ASQActivity", "ASQ data saved successfully")

                Snackbar.make(
                    binding.root,
                    getString(R.string.asq_data_saved),
                    Snackbar.LENGTH_SHORT
                ).show()

                // Navigate after a brief delay with context awareness
                binding.root.postDelayed({
                    navigateToTaskSelection()
                }, 1000)

            } catch (e: Exception) {
                DebugLogger.e("ASQActivity", "Failed to save ASQ data", e)
                Snackbar.make(
                    binding.root,
                    getString(R.string.asq_save_error, e.message),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Show confirmation dialog for skipping ASQ
     * ENHANCED: Context-aware navigation after skipping
     */
    private fun showSkipConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.asq_skip_title))
            .setMessage(getString(R.string.asq_skip_message))
            .setPositiveButton(getString(R.string.asq_skip_confirm)) { _, _ ->
                DebugLogger.d("ASQActivity", "ASQ skipped by user")
                navigateToTaskSelection()
            }
            .setNegativeButton(getString(R.string.asq_skip_cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Navigate to TaskSummaryActivity (back navigation)
     * NEW: Returns user to the task summary for revision/review
     */
    private fun navigateBackToTaskSummary() {
        DebugLogger.d("ASQActivity", "Navigating back to TaskSummaryActivity")

        val intent = Intent(this, TaskSummaryActivity::class.java).apply {
            // Pass all the original task data back
            putExtra("TASK_NUMBER", taskNumber)
            putExtra("TASK_ID", taskId)
            putExtra("ITERATION_COUNTER", iterationCounter)
            putExtra("TASK_LABEL", taskLabel)
            putExtra("SESSION_ID", sessionId)
            putExtra("IS_INDIVIDUAL_TASK", isIndividualTask)

            // Pass task list context if applicable
            if (isInTaskList) {
                putExtra("TASK_LIST_ID", taskListId)
                putExtra("TASK_LIST_LABEL", taskListLabel)
            }

            // Note: We don't have the full stroop data here, so TaskSummaryActivity
            // will need to handle this case or we need to pass the data through
            // For now, we'll use a flag to indicate this is a return from ASQ
            putExtra("RETURNING_FROM_ASQ", true)
        }

        startActivity(intent)
        finish()
    }

    /**
     * Navigate to TaskSelectionActivity with context awareness
     * ENHANCED: Respects task list context for proper return navigation
     */
    private fun navigateToTaskSelection() {
        DebugLogger.d("ASQActivity", "Navigating to TaskSelectionActivity with context")

        val intent = Intent(this, TaskSelectionActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

            // If we're in task list mode, return to that specific task list
            if (isInTaskList) {
                putExtra("TASK_LIST_ID", taskListId)
                putExtra("TASK_LIST_LABEL", taskListLabel)
                DebugLogger.d("ASQActivity", "Returning to task list: $taskListId")
            } else {
                DebugLogger.d("ASQActivity", "Returning to global task selection")
            }
        }

        startActivity(intent)
        finish()
    }

    /**
     * Handle toolbar back navigation - return to TaskSummaryActivity
     * ENHANCED: Returns to task summary instead of task selection
     */
    override fun onSupportNavigateUp(): Boolean {
        DebugLogger.d("ASQActivity", "Back navigation pressed - returning to TaskSummaryActivity")
        navigateBackToTaskSummary()
        return true
    }

    /**
     * Handle Android back button - return to TaskSummaryActivity
     * ENHANCED: Returns to task summary instead of task selection
     */
    override fun onBackPressed() {
        DebugLogger.d("ASQActivity", "Back button pressed - returning to TaskSummaryActivity")
        navigateBackToTaskSummary()
        // No super call needed since navigateBackToTaskSummary() calls finish()
    }
}