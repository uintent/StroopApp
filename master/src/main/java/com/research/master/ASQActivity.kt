package com.research.master

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.research.master.databinding.ActivityAsqBinding
import com.research.master.utils.SessionManager
import com.research.master.utils.TaskCompletionData
import com.research.master.utils.FileManager
import kotlinx.coroutines.launch

/**
 * ASQActivity - After Scenario Questionnaire
 * Displays two 7-point Likert scale questions for successful tasks
 * Updates existing task data in SessionManager with ASQ responses
 * Navigates to TaskSelectionActivity after completion
 */
class ASQActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAsqBinding
    private lateinit var fileManager: FileManager

    // Task information passed from TaskSummaryActivity
    private var taskId: String? = null
    private var taskLabel: String? = null
    private var sessionId: String? = null
    private var isIndividualTask: Boolean = true

    // ASQ responses (1-7 scale, 0 = not answered)
    private var asqEaseResponse: Int = 0  // 0 = not answered, 1-7 = selected value
    private var asqTimeResponse: Int = 0  // 0 = not answered, 1-7 = selected value

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAsqBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize FileManager
        fileManager = FileManager(this)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.asq_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        Log.d("ASQActivity", "=== ASQ ACTIVITY STARTED ===")

        // Extract data from intent
        extractIntentData()

        // Set up UI
        setupUI()

        // Set up button listeners
        setupButtons()

        Log.d("ASQActivity", "ASQ setup complete")
    }

    /**
     * Extract task data from TaskSummaryActivity intent
     */
    private fun extractIntentData() {
        Log.d("ASQActivity", "=== EXTRACTING INTENT DATA ===")

        taskId = intent.getStringExtra("TASK_ID")
        taskLabel = intent.getStringExtra("TASK_LABEL")
        sessionId = intent.getStringExtra("SESSION_ID")
        isIndividualTask = intent.getBooleanExtra("IS_INDIVIDUAL_TASK", true)

        Log.d("ASQActivity", "Task ID: $taskId")
        Log.d("ASQActivity", "Task Label: $taskLabel")
        Log.d("ASQActivity", "Session ID: $sessionId")
        Log.d("ASQActivity", "Individual Task: $isIndividualTask")

        Log.d("ASQActivity", "=== INTENT DATA EXTRACTION COMPLETE ===")
    }

    /**
     * Set up the UI with task information and questions
     */
    private fun setupUI() {
        Log.d("ASQActivity", "=== SETTING UP UI ===")

        // Display task information
        binding.textTaskTitle.text = taskLabel ?: getString(R.string.asq_unknown_task)
        binding.textTaskId.text = getString(R.string.asq_task_label, taskId ?: getString(R.string.asq_unknown_task_id))

        // Set up radio group listeners
        setupRadioGroupListener(binding.radioGroupEase) { selectedValue ->
            asqEaseResponse = selectedValue
            Log.d("ASQActivity", "ASQ Ease response: $asqEaseResponse")
        }

        setupRadioGroupListener(binding.radioGroupTime) { selectedValue ->
            asqTimeResponse = selectedValue
            Log.d("ASQActivity", "ASQ Time response: $asqTimeResponse")
        }

        // Continue button is always enabled

        Log.d("ASQActivity", "UI setup complete")
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
     */
    private fun saveASQDataAndContinue() {
        Log.d("ASQActivity", "=== SAVING ASQ DATA ===")
        Log.d("ASQActivity", "Ease response: $asqEaseResponse")
        Log.d("ASQActivity", "Time response: $asqTimeResponse")

        lifecycleScope.launch {
            try {
                // Update the task with ASQ data using SessionManager
                val asqData = mapOf(
                    "ease" to asqEaseResponse.toString(),
                    "time" to asqTimeResponse.toString()
                )

                SessionManager.updateTaskASQData(taskId ?: "", asqData)

                Log.d("ASQActivity", "ASQ data saved successfully")

                Snackbar.make(
                    binding.root,
                    getString(R.string.asq_data_saved),
                    Snackbar.LENGTH_SHORT
                ).show()

                // Navigate to task selection after a brief delay
                binding.root.postDelayed({
                    navigateToTaskSelection()
                }, 1000)

            } catch (e: Exception) {
                Log.e("ASQActivity", "Failed to save ASQ data", e)
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
     */
    private fun showSkipConfirmationDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.asq_skip_title))
            .setMessage(getString(R.string.asq_skip_message))
            .setPositiveButton(getString(R.string.asq_skip_confirm)) { _, _ ->
                Log.d("ASQActivity", "ASQ skipped by user")
                navigateToTaskSelection()
            }
            .setNegativeButton(getString(R.string.asq_skip_cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Navigate to TaskSelectionActivity
     */
    private fun navigateToTaskSelection() {
        Log.d("ASQActivity", "Navigating to TaskSelectionActivity")

        val intent = Intent(this, TaskSelectionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        // Navigate back to task selection (don't go back to TaskSummaryActivity)
        navigateToTaskSelection()
        return true
    }

    override fun onBackPressed() {
        super.onBackPressed()
        navigateToTaskSelection()
    }
}