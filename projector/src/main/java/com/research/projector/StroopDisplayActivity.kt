package com.research.projector

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.research.projector.databinding.ActivityStroopDisplayBinding
import com.research.projector.models.*
import com.research.projector.utils.StroopGenerator
import com.research.projector.viewmodels.*

/**
 * Activity for fullscreen Stroop stimulus display with precise timing.
 * Handles countdown, stimulus presentation, and intervals with research-grade accuracy.
 */
class StroopDisplayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStroopDisplayBinding
    private val viewModel: StroopDisplayViewModel by viewModels()

    // Display parameters
    private var displayWidth = 0
    private var displayHeight = 0

    // Event tracking to prevent multiple handling
    private var lastTaskCompletionEvent: TaskCompletionEvent? = null
    private var lastErrorEvent: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up view binding
        binding = ActivityStroopDisplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Configure for fullscreen display
        configureFullscreenDisplay()

        // Get task execution data from intent
        extractIntentData()

        // Set up observers
        setupObservers()

        // Set up click listeners
        setupClickListeners()

        // Get display dimensions and initialize
        binding.stroopContainer.post {
            displayWidth = binding.stroopContainer.width
            displayHeight = binding.stroopContainer.height
            initializeDisplay()
        }
    }

    /**
     * Configure activity for fullscreen immersive display
     */
    private fun configureFullscreenDisplay() {
        // Keep screen on during display
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hide system UI for immersive experience
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
    }

    /**
     * Extract task execution data from intent
     */
    private fun extractIntentData() {
        val taskExecution = intent.getSerializableExtra(TaskExecutionActivity.EXTRA_TASK_EXECUTION) as? TaskExecutionState
        val runtimeConfig = intent.getSerializableExtra(TaskExecutionActivity.EXTRA_RUNTIME_CONFIG) as? RuntimeConfig
        val stroopGenerator = intent.getSerializableExtra(TaskExecutionActivity.EXTRA_STROOP_GENERATOR) as? StroopGenerator

        if (taskExecution == null || runtimeConfig == null || stroopGenerator == null) {
            finishWithError("Invalid task execution data received")
            return
        }

        // Store for initialization
        this.taskExecution = taskExecution
        this.runtimeConfig = runtimeConfig
        this.stroopGenerator = stroopGenerator
    }

    // Store data for initialization
    private var taskExecution: TaskExecutionState? = null
    private var runtimeConfig: RuntimeConfig? = null
    private var stroopGenerator: StroopGenerator? = null

    /**
     * Initialize display with task execution parameters
     */
    private fun initializeDisplay() {
        val execution = taskExecution
        val config = runtimeConfig
        val generator = stroopGenerator

        if (execution != null && config != null && generator != null && displayWidth > 0 && displayHeight > 0) {
            viewModel.initialize(execution, config, generator, displayWidth, displayHeight)
        }
    }

    /**
     * Set up LiveData observers
     */
    private fun setupObservers() {
        // Observe display state
        viewModel.displayState.observe(this) { state ->
            handleDisplayState(state)
        }

        // Observe display content
        viewModel.displayContent.observe(this) { content ->
            handleDisplayContent(content)
        }

        // Observe font information
        viewModel.fontInfo.observe(this) { fontInfo ->
            updateFontSizes(fontInfo)
        }

        // Observe task completion with one-time event handling
        viewModel.taskCompletionEvent.observe(this) { event ->
            if (event != null && event != lastTaskCompletionEvent) {
                lastTaskCompletionEvent = event
                handleTaskCompletion(event)
            }
        }

        // Observe errors with one-time event handling
        viewModel.errorEvent.observe(this) { error ->
            if (error != null && error != lastErrorEvent) {
                lastErrorEvent = error
                handleError(error)
            }
        }
    }

    /**
     * Set up click listeners
     */
    private fun setupClickListeners() {
        // Emergency stop button (invisible but functional)
        binding.btnEmergencyStop.setOnClickListener {
            viewModel.emergencyStop()
        }

        // Status indicator for debugging (optional, can be hidden)
        binding.tvStatusIndicator.setOnClickListener {
            // Optional: Show debug info
            showDebugInfo()
        }
    }

    /**
     * Handle display state changes
     */
    private fun handleDisplayState(state: StimulusDisplayState) {
        when (state) {
            StimulusDisplayState.WAITING -> {
                // Should not occur in this activity
            }

            StimulusDisplayState.COUNTDOWN -> {
                showCountdownState()
            }

            StimulusDisplayState.DISPLAY -> {
                showStroopDisplayState()
            }

            StimulusDisplayState.INTERVAL -> {
                showIntervalState()
            }

            StimulusDisplayState.COMPLETED -> {
                showCompletedState()
            }
        }

        // Update status indicator
        updateStatusIndicator(state)
    }

    /**
     * Handle display content changes
     */
    private fun handleDisplayContent(content: DisplayContent) {
        when (content) {
            is DisplayContent.Countdown -> {
                showCountdownNumber(content.number)
            }

            is DisplayContent.Stroop -> {
                showStroopStimulus(content.stimulus)
            }

            is DisplayContent.Interval -> {
                showIntervalScreen()
            }

            is DisplayContent.BlackScreen -> {
                showBlackScreen()
            }
        }
    }

    /**
     * Show countdown state
     */
    private fun showCountdownState() {
        binding.tvCountdown.isVisible = true
        binding.tvStroopStimulus.isVisible = false
        binding.viewInterval.isVisible = false
        binding.viewBlackScreen.isVisible = false
    }

    /**
     * Show Stroop display state
     */
    private fun showStroopDisplayState() {
        binding.tvCountdown.isVisible = false
        binding.tvStroopStimulus.isVisible = true
        binding.viewInterval.isVisible = false
        binding.viewBlackScreen.isVisible = false
    }

    /**
     * Show interval state
     */
    private fun showIntervalState() {
        binding.tvCountdown.isVisible = false
        binding.tvStroopStimulus.isVisible = false
        binding.viewInterval.isVisible = true
        binding.viewBlackScreen.isVisible = false
    }

    /**
     * Show completed state
     */
    private fun showCompletedState() {
        binding.tvCountdown.isVisible = false
        binding.tvStroopStimulus.isVisible = false
        binding.viewInterval.isVisible = false
        binding.viewBlackScreen.isVisible = true
    }

    /**
     * Show countdown number
     */
    private fun showCountdownNumber(number: Int) {
        binding.tvCountdown.text = number.toString()
    }

    /**
     * Show Stroop stimulus
     */
    private fun showStroopStimulus(stimulus: StroopStimulus) {
        binding.tvStroopStimulus.apply {
            text = stimulus.colorWord
            setTextColor(stimulus.displayColor)
        }
    }

    /**
     * Show interval screen (white background, no content)
     */
    private fun showIntervalScreen() {
        // interval view is just a white background, no content needed
    }

    /**
     * Show black screen
     */
    private fun showBlackScreen() {
        // black screen view is just a black background, no content needed
    }

    /**
     * Update font sizes based on calculated values
     */
    private fun updateFontSizes(fontInfo: FontInfo) {
        // Update Stroop text size
        binding.tvStroopStimulus.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontInfo.stroopFontSize)

        // Update countdown text size
        binding.tvCountdown.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontInfo.countdownFontSize)
    }

    /**
     * Update status indicator for debugging
     */
    private fun updateStatusIndicator(state: StimulusDisplayState) {
        val color = when (state) {
            StimulusDisplayState.WAITING -> getColor(R.color.status_ready)
            StimulusDisplayState.COUNTDOWN -> getColor(R.color.status_active)
            StimulusDisplayState.DISPLAY -> getColor(R.color.status_active)
            StimulusDisplayState.INTERVAL -> getColor(R.color.status_ready)
            StimulusDisplayState.COMPLETED -> getColor(R.color.status_complete)
        }

        binding.tvStatusIndicator.setTextColor(color)
        binding.tvStatusIndicator.isVisible = true // Can be set to false in production
    }

    /**
     * Handle task completion
     */
    private fun handleTaskCompletion(event: TaskCompletionEvent) {
        // Return completed task execution to TaskExecutionActivity
        val resultIntent = Intent().apply {
            putExtra(TaskExecutionActivity.EXTRA_COMPLETED_TASK_EXECUTION, event.completedTaskExecution)
        }

        setResult(RESULT_OK, resultIntent)

        // Finish activity after short delay to ensure black screen is visible
        binding.root.postDelayed({
            finish()
        }, 500)
    }

    /**
     * Handle errors
     */
    private fun handleError(error: String) {
        // For fullscreen display, we should minimize disruption
        // Log error and continue or finish gracefully
        android.util.Log.e("StroopDisplay", "Error: $error")

        // Optionally finish with error
        finishWithError(error)
    }

    /**
     * Show debug information (optional, for development)
     */
    private fun showDebugInfo() {
        val stimulusCount = viewModel.getCurrentStimulusCount()
        val elapsedTime = viewModel.getCurrentElapsedTime()
        val remainingTime = viewModel.getCurrentRemainingTime()

        val debugInfo = "Stimuli: $stimulusCount, Elapsed: ${elapsedTime}ms, Remaining: ${remainingTime}ms"

        // Could show as toast or log
        android.util.Log.d("StroopDisplay", debugInfo)
    }

    /**
     * Finish activity with error
     */
    private fun finishWithError(message: String) {
        val resultIntent = Intent().apply {
            putExtra("error_message", message)
        }
        setResult(RESULT_CANCELED, resultIntent)
        finish()
    }

    /**
     * Handle system UI visibility changes
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            // Re-hide system UI if it became visible
            configureFullscreenDisplay()
        }
    }

    /**
     * Handle back button - emergency stop
     */
    override fun onBackPressed() {
        // Emergency stop task
        viewModel.emergencyStop()
    }

    /**
     * Handle display size changes (orientation, etc.)
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)

        // Recalculate display dimensions and font sizes
        binding.stroopContainer.post {
            val newWidth = binding.stroopContainer.width
            val newHeight = binding.stroopContainer.height

            if (newWidth > 0 && newHeight > 0) {
                viewModel.onDisplaySizeChanged(newWidth, newHeight)
            }
        }
    }

    /**
     * Handle activity lifecycle
     */
    override fun onPause() {
        super.onPause()

        // For research purposes, we may want to pause/stop the task
        // when activity loses focus to maintain timing accuracy
        viewModel.emergencyStop()
    }

    override fun onResume() {
        super.onResume()

        // Ensure fullscreen display is maintained
        configureFullscreenDisplay()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clear keep screen on flag
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}