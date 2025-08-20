package com.research.master

import android.graphics.Color
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.research.master.databinding.ActivityColorDisplayBinding
import com.research.master.network.NetworkManager
import com.research.shared.network.StroopDisplayMessage
import com.research.shared.network.StroopHiddenMessage
import com.research.shared.network.HeartbeatMessage
import com.research.shared.network.HandshakeResponseMessage
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Activity that displays the current Stroop color being shown on the Projector
 * This is a test activity to verify data transfer between apps
 *
 * Note: This will be replaced with proper task selection and management UI
 */
class ColorDisplayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityColorDisplayBinding
    private val networkClient by lazy { NetworkManager.getNetworkClient(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityColorDisplayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Remove toolbar setup - use default action bar instead
        supportActionBar?.title = "Stroop Color Monitor"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Check connection status
        if (!NetworkManager.isConnected()) {
            showNotConnected()
            return
        }

        // Start observing messages
        observeNetworkMessages()

        // Set initial state
        showWaitingForStroop()

        // Test button to trigger Stroop on Projector
        // TODO: Replace this with proper task selection UI
        binding.btnTriggerStroop.setOnClickListener {
            triggerTestStroop()
        }
    }

    private fun observeNetworkMessages() {
        lifecycleScope.launch {
            networkClient.receiveMessages().collectLatest { message ->
                // Only process messages if activity is resumed
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    Log.d("ColorDisplay", "Received message: $message")

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
                            Log.d("ColorDisplay", "Unhandled message type: ${message::class.simpleName}")
                        }
                    }
                } else {
                    Log.d("ColorDisplay", "Activity not resumed, ignoring message: ${message::class.simpleName}")
                }
            }
        }
    }

    private fun handleStroopDisplay(message: StroopDisplayMessage) {
        Log.d("ColorDisplay", "Stroop displayed: word=${message.word}, color=${message.displayColor}")

        try {
            val color = Color.parseColor(message.displayColor)
            Log.d("ColorDisplay", "Parsed color: $color")

            binding.viewColorDisplay.setBackgroundColor(color)
            Log.d("ColorDisplay", "Set background color")

            // Update text information
            binding.tvColorInfo.text = "Word: ${message.word}\nColor: ${message.displayColor}"
            binding.tvStatus.text = "Stroop Active"

            // Show correct answer (what participant should say)
            binding.tvCorrectAnswer.text = "Correct Answer: ${message.correctAnswer}"

            Log.d("ColorDisplay", "UI update complete")

        } catch (e: Exception) {
            Log.e("ColorDisplay", "Error parsing color: ${message.displayColor}", e)
            binding.tvStatus.text = "Error: Invalid color format"
        }
    }

    private fun showWaitingForStroop() {
        binding.viewColorDisplay.setBackgroundColor(Color.LTGRAY)
        binding.tvStatus.text = "Waiting for Stroop..."
        binding.tvColorInfo.text = "No Stroop active"
        binding.tvCorrectAnswer.text = ""
    }

    private fun showNotConnected() {
        binding.viewColorDisplay.setBackgroundColor(Color.DKGRAY)
        binding.tvStatus.text = "Not Connected"
        binding.tvColorInfo.text = "Please connect to Projector first"
        binding.btnTriggerStroop.isEnabled = false
    }

    /**
     * Temporary test function to trigger Stroop display
     * TODO: Replace with proper task-based commands
     */
    private fun triggerTestStroop() {
        lifecycleScope.launch {
            try {
                Log.d("ColorDisplay", "Triggering test Stroop...")
                binding.tvStatus.text = "Triggering Stroop..."

                // Get current session ID from NetworkManager
                val sessionId = NetworkManager.getCurrentSessionId()
                if (sessionId == null) {
                    binding.tvStatus.text = "Error: No active session"
                    Log.e("ColorDisplay", "No session ID available")
                    return@launch
                }

                // TODO: Replace this with new task command system
                // For now, this is just a placeholder that will be removed

                binding.tvStatus.text = "TODO: Replace with task commands"
                Log.d("ColorDisplay", "TODO: Implement new task-based command system")

            } catch (e: Exception) {
                Log.e("ColorDisplay", "Error triggering Stroop", e)
                binding.tvStatus.text = "Error: ${e.message}"
            }
        }
    }

    private fun handleStroopHidden() {
        Log.d("ColorDisplay", "Stroop hidden")
        showWaitingForStroop()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}