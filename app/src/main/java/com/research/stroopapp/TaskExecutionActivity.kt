package com.research.stroopapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import com.research.stroopapp.databinding.ActivityTaskExecutionBinding
import com.research.stroopapp.models.RuntimeConfig
import com.research.stroopapp.models.SessionState
import com.research.stroopapp.models.TaskExecutionState
import com.research.stroopapp.utils.StroopGenerator
import com.research.stroopapp.viewmodels.*

/**
 * Activity for managing task execution and between-tasks controls.
 * Handles task flow, session management, and coordination with Stroop display.
 */
class TaskExecutionActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityTaskExecutionBinding
    private val viewModel: TaskExecutionViewModel by viewModels()
    
    // Session data
    private var sessionState: SessionState? = null
    private var runtimeConfig: RuntimeConfig? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set up view binding
        binding = ActivityTaskExecutionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Get session data from intent
        extractIntentData()
        
        // Initialize ViewModel with session data
        initializeViewModel()
        
        // Set up observers
        setupObservers()
        
        // Set up click listeners
        setupClickListeners()
    }
    
    /**
     * Extract session data from intent extras
     */
    private fun extractIntentData() {
        sessionState = intent.getSerializableExtra(TaskSelectionActivity.EXTRA_SESSION_STATE) as? SessionState
        runtimeConfig = intent.getSerializableExtra(TaskSelectionActivity.EXTRA_RUNTIME_CONFIG) as? RuntimeConfig
        
        if (sessionState == null || runtimeConfig == null) {
            showErrorAndFinish("Invalid session data received")
            return
        }
    }
    
    /**
     * Initialize ViewModel with session data
     */
    private fun initializeViewModel() {
        val session = sessionState
        val config = runtimeConfig
        
        if (session != null && config != null) {
            viewModel.initialize(session, config)
        }
    }
    
    /**
     * Set up LiveData observers
     */
    private fun setupObservers() {
        // Observe UI state
        viewModel.uiState.observe(this) { state ->
            handleUIState(state)
        }
        
        // Observe navigation events
        viewModel.navigationEvent.observe(this) { event ->
            event?.let { handleNavigationEvent(it) }
        }
        
        // Observe dialog events
        viewModel.dialogEvent.observe(this) { event ->
            event?.let { handleDialogEvent(it) }
        }
        
        // Observe session state changes
        viewModel.sessionState.observe(this) { state ->
            updateSessionInfo(state)
        }
    }
    
    /**
     * Set up click listeners
     */
    private fun setupClickListeners() {
        // Primary action button (Start Task / Next Task)
        binding.btnPrimaryAction.setOnClickListener {
            val uiState = viewModel.uiState.value
            when (uiState) {
                is TaskExecutionUIState.ReadyToStart -> {
                    viewModel.startCurrentTask()
                }
                is TaskExecutionUIState.TaskCompleted -> {
                    if (uiState.hasNextTask) {
                        viewModel.moveToNextTask()
                    }
                }
                else -> {
                    // Button should not be clickable in other states
                }
            }
        }
        
        // Secondary action button (Restart Task)
        binding.btnSecondaryAction.setOnClickListener {
            viewModel.restartCurrentTask()
        }
        
        // Cancel session button
        binding.btnCancelSession.setOnClickListener {
            viewModel.requestCancelSession()
        }
        
        // Session complete buttons
        binding.btnRestartSession.setOnClickListener {
            viewModel.restartEntireSession()
        }
        
        binding.btnRestartLastTask.setOnClickListener {
            viewModel.restartCurrentTask()
        }
        
        binding.btnReturnToMain.setOnClickListener {
            viewModel.returnToMainMenu()
        }
    }
    
    /**
     * Handle UI state changes
     */
    private fun handleUIState(state: TaskExecutionUIState) {
        when (state) {
            is TaskExecutionUIState.ReadyToStart -> {
                showReadyToStartState(state)
            }
            
            is TaskExecutionUIState.TaskInProgress -> {
                showTaskInProgressState(state)
            }
            
            is TaskExecutionUIState.TaskCompleted -> {
                showTaskCompletedState(state)
            }
            
            is TaskExecutionUIState.SessionComplete -> {
                showSessionCompleteState(state)
            }
            
            is TaskExecutionUIState.Error -> {
                showErrorState(state.message)
            }
        }
    }
    
    /**
     * Show ready to start state
     */
    private fun showReadyToStartState(state: TaskExecutionUIState.ReadyToStart) {
        // Update progress indicator
        binding.tvProgress.text = state.progressText
        
        // Configure stroop area
        binding.layoutStroopContent.isVisible = true
        binding.ivTaskStatus.setImageResource(R.drawable.ic_task_waiting)
        binding.tvTaskStatus.text = "Ready to start ${state.taskId}"
        
        // Configure control buttons
        binding.btnPrimaryAction.text = getString(R.string.start_task)
        binding.btnPrimaryAction.isVisible = true
        binding.btnPrimaryAction.isEnabled = true
        binding.btnSecondaryAction.isVisible = false
        binding.btnCancelSession.isVisible = true
        
        // Hide session complete overlay
        binding.layoutSessionComplete.isVisible = false
        
        // Show status message
        binding.tvStatusMessage.text = "Press 'Start Task' when ready to begin"
        binding.tvStatusMessage.isVisible = true
    }
    
    /**
     * Show task in progress state (should be brief before navigating to Stroop display)
     */
    private fun showTaskInProgressState(state: TaskExecutionUIState.TaskInProgress) {
        // Update progress
        binding.tvProgress.text = state.progressText
        
        // Update status
        binding.ivTaskStatus.setImageResource(R.drawable.ic_task_active)
        binding.tvTaskStatus.text = "Task in progress..."
        
        // Disable controls (task is running)
        binding.btnPrimaryAction.isEnabled = false
        binding.btnSecondaryAction.isVisible = false
        binding.btnCancelSession.isVisible = false
        
        // Hide status message
        binding.tvStatusMessage.isVisible = false
    }
    
    /**
     * Show task completed state
     */
    private fun showTaskCompletedState(state: TaskExecutionUIState.TaskCompleted) {
        // Update progress
        binding.tvProgress.text = state.progressText
        
        // Update stroop area
        binding.ivTaskStatus.setImageResource(R.drawable.ic_task_complete)
        binding.tvTaskStatus.text = getString(R.string.task_completed)
        
        // Configure control buttons
        if (state.hasNextTask) {
            binding.btnPrimaryAction.text = getString(R.string.next_task)
            binding.btnPrimaryAction.isVisible = true
        } else {
            binding.btnPrimaryAction.isVisible = false
        }
        
        binding.btnSecondaryAction.text = getString(R.string.restart_task)
        binding.btnSecondaryAction.isVisible = true
        binding.btnCancelSession.isVisible = true
        
        // Enable all visible buttons
        binding.btnPrimaryAction.isEnabled = true
        binding.btnSecondaryAction.isEnabled = true
        binding.btnCancelSession.isEnabled = true
        
        // Show completion info
        val durationSeconds = state.duration / 1000
        val statusText = "Completed in ${durationSeconds}s with ${state.stimulusCount} stimuli"
        binding.tvStatusMessage.text = statusText
        binding.tvStatusMessage.isVisible = true
    }
    
    /**
     * Show session complete state
     */
    private fun showSessionCompleteState(state: TaskExecutionUIState.SessionComplete) {
        // Update progress
        binding.tvProgress.text = state.progressText
        
        // Hide main controls
        binding.btnPrimaryAction.isVisible = false
        binding.btnSecondaryAction.isVisible = false
        binding.btnCancelSession.isVisible = false
        
        // Show session complete overlay
        binding.layoutSessionComplete.isVisible = true
        
        // Update stroop area
        binding.ivTaskStatus.setImageResource(R.drawable.ic_task_complete)
        binding.tvTaskStatus.text = getString(R.string.session_complete)
        
        // Hide status message
        binding.tvStatusMessage.isVisible = false
    }
    
    /**
     * Show error state
     */
    private fun showErrorState(message: String) {
        // Show error in stroop area
        binding.ivTaskStatus.setImageResource(R.drawable.ic_error)
        binding.tvTaskStatus.text = "Error"
        
        // Show error message
        binding.tvStatusMessage.text = message
        binding.tvStatusMessage.isVisible = true
        
        // Disable all controls except cancel
        binding.btnPrimaryAction.isEnabled = false
        binding.btnSecondaryAction.isEnabled = false
        binding.btnCancelSession.isVisible = true
        binding.btnCancelSession.isEnabled = true
        
        // Show error snackbar
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
    
    /**
     * Handle navigation events
     */
    private fun handleNavigationEvent(event: TaskExecutionNavigationEvent) {
        when (event) {
            is TaskExecutionNavigationEvent.NavigateToStroopDisplay -> {
                startStroopDisplay(
                    event.taskExecution,
                    event.runtimeConfig,
                    event.stroopGenerator
                )
            }
            
            is TaskExecutionNavigationEvent.NavigateToTaskSelection -> {
                returnToTaskSelection()
            }
        }
        
        // Clear the event after handling
        viewModel.clearNavigationEvent()
    }
    
    /**
     * Handle dialog events
     */
    private fun handleDialogEvent(event: DialogEvent) {
        when (event) {
            is DialogEvent.ConfirmCancelSession -> {
                showCancelSessionDialog()
            }
            
            is DialogEvent.ShowError -> {
                showErrorDialog(event.message)
            }
        }
        
        // Clear the event after handling
        viewModel.clearDialogEvent()
    }
    
    /**
     * Update session information display
     */
    private fun updateSessionInfo(session: SessionState) {
        // This is handled in the UI state updates
        // Could add additional session-level info display here if needed
    }
    
    /**
     * Start Stroop display activity
     */
    private fun startStroopDisplay(
        taskExecution: TaskExecutionState,
        runtimeConfig: RuntimeConfig,
        stroopGenerator: StroopGenerator
    ) {
        val intent = Intent(this, StroopDisplayActivity::class.java).apply {
            putExtra(EXTRA_TASK_EXECUTION, taskExecution)
            putExtra(EXTRA_RUNTIME_CONFIG, runtimeConfig)
            putExtra(EXTRA_STROOP_GENERATOR, stroopGenerator)
        }
        startActivityForResult(intent, REQUEST_STROOP_DISPLAY)
    }
    
    /**
     * Return to task selection
     */
    private fun returnToTaskSelection() {
        finish() // This will return to TaskSelectionActivity
    }
    
    /**
     * Show cancel session confirmation dialog
     */
    private fun showCancelSessionDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cancel_session_title))
            .setMessage(getString(R.string.cancel_session_message))
            .setPositiveButton(getString(R.string.dialog_confirm)) { _, _ ->
                viewModel.confirmCancelSession()
            }
            .setNegativeButton(getString(R.string.dialog_cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    /**
     * Show error dialog
     */
    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
    
    /**
     * Show error and finish activity
     */
    private fun showErrorAndFinish(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * Handle returning from Stroop display
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_STROOP_DISPLAY) {
            // Get completed task execution from result
            val completedExecution = data?.getSerializableExtra(EXTRA_COMPLETED_TASK_EXECUTION) as? TaskExecutionState
            
            if (completedExecution != null) {
                viewModel.onTaskCompleted(completedExecution)
            } else {
                // Handle case where Stroop display didn't return proper result
                showErrorDialog("Task execution result not received properly")
            }
        }
    }
    
    /**
     * Handle back button press
     */
    override fun onBackPressed() {
        // Show cancel session confirmation
        viewModel.requestCancelSession()
    }
    
    /**
     * Handle lifecycle events
     */
    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        viewModel.onPause()
    }
    
    companion object {
        // Intent extras
        const val EXTRA_TASK_EXECUTION = "extra_task_execution"
        const val EXTRA_RUNTIME_CONFIG = "extra_runtime_config"
        const val EXTRA_STROOP_GENERATOR = "extra_stroop_generator"
        const val EXTRA_COMPLETED_TASK_EXECUTION = "extra_completed_task_execution"
        
        // Request codes
        private const val REQUEST_STROOP_DISPLAY = 2001
    }
}