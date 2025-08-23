package com.research.master

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.research.master.databinding.ActivityMainSessionBinding
import com.research.master.network.NetworkManager
import com.research.master.utils.SessionManager
import com.research.master.utils.SessionData
import kotlinx.coroutines.launch

/**
 * Main session management activity
 * Appears after successful projector connection
 * Provides options for: New Session, Resume Session, Settings, Disconnect
 */
class MainSessionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainSessionBinding
    private var currentSessionData: SessionData? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainSessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        binding.toolbar.title = "Research Session Manager"

        // Initialize SessionManager
        SessionManager.initialize(this)

        // Set up click listeners
        setupClickListeners()

        // Check for existing sessions
        checkForExistingSession()

        // Handle back button - confirm disconnect
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showDisconnectConfirmation()
            }
        })

        // Update connection status
        updateConnectionStatus()
    }

    override fun onResume() {
        super.onResume()

        // Refresh session state when returning to this activity
        checkForExistingSession()
        updateConnectionStatus()
    }

    /**
     * Set up button click listeners
     */
    private fun setupClickListeners() {
        binding.btnNewSession.setOnClickListener {
            startNewSession()
        }

        binding.btnResumeSession.setOnClickListener {
            resumeExistingSession()
        }

        binding.btnSettings.setOnClickListener {
            openSettings()
        }

        binding.btnDisconnect.setOnClickListener {
            showDisconnectConfirmation()
        }
    }

    /**
     * Check for existing session and update UI accordingly
     */
    private fun checkForExistingSession() {
        lifecycleScope.launch {
            try {
                // Check for incomplete session
                val incompleteSession = SessionManager.checkForIncompleteSession()
                currentSessionData = incompleteSession

                // Update UI based on session state
                updateSessionUI(incompleteSession)

            } catch (e: Exception) {
                Log.e("MainSession", "Error checking for existing session", e)
                showError("Error checking session status: ${e.message}")
            }
        }
    }

    /**
     * Update session-related UI elements
     */
    private fun updateSessionUI(sessionData: SessionData?) {
        if (sessionData != null) {
            // Show session info
            binding.layoutExistingSession.visibility = android.view.View.VISIBLE
            binding.textExistingSessionInfo.text = buildString {
                append("Active Session Found:\n")
                append("Participant: ${sessionData.participantName}\n")
                append("ID: ${sessionData.participantId}\n")
                append("Tasks completed: ${sessionData.tasks.size}")
            }

            // Enable resume button
            binding.btnResumeSession.isEnabled = true
            binding.btnResumeSession.text = "Resume Session"

        } else {
            // No existing session
            binding.layoutExistingSession.visibility = android.view.View.GONE
            binding.btnResumeSession.isEnabled = false
            binding.btnResumeSession.text = "No Session to Resume"
        }
    }

    /**
     * Update connection status display
     */
    private fun updateConnectionStatus() {
        val isConnected = NetworkManager.isConnected()
        val sessionId = NetworkManager.getCurrentSessionId()

        if (isConnected && !sessionId.isNullOrEmpty()) {
            binding.textConnectionStatus.text = "Connected to Projector\nSession ID: ${sessionId.take(8)}..."
            binding.textConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))

            // Enable session buttons
            binding.btnNewSession.isEnabled = true

        } else {
            binding.textConnectionStatus.text = "Connection Error - Please reconnect"
            binding.textConnectionStatus.setTextColor(getColor(android.R.color.holo_red_dark))

            // Disable session buttons
            binding.btnNewSession.isEnabled = false
            binding.btnResumeSession.isEnabled = false

            // Show reconnect option
            showConnectionError()
        }
    }

    /**
     * Start a new research session
     */
    private fun startNewSession() {
        lifecycleScope.launch {
            try {
                // If there's an existing session, confirm closure
                if (currentSessionData != null) {
                    showNewSessionConfirmation()
                } else {
                    // No existing session, proceed directly
                    navigateToParticipantInfo()
                }

            } catch (e: Exception) {
                Log.e("MainSession", "Error starting new session", e)
                showError("Error starting new session: ${e.message}")
            }
        }
    }

    /**
     * Resume existing session
     */
    private fun resumeExistingSession() {
        val sessionData = currentSessionData ?: return

        lifecycleScope.launch {
            try {
                // Resume session in SessionManager
                SessionManager.resumeSession(sessionData)

                // Determine where to resume based on session state
                if (isParticipantInfoComplete(sessionData)) {
                    // Participant info is complete, go to task selection
                    Log.d("MainSession", "Resuming at task selection - participant info complete")
                    navigateToTaskSelection()
                } else {
                    // Participant info incomplete, go to participant form
                    Log.d("MainSession", "Resuming at participant info - info incomplete")
                    navigateToParticipantInfo()
                }

            } catch (e: Exception) {
                Log.e("MainSession", "Error resuming session", e)
                showError("Error resuming session: ${e.message}")
            }
        }
    }

    /**
     * Check if participant information is complete
     */
    private fun isParticipantInfoComplete(sessionData: SessionData): Boolean {
        return sessionData.participantName.isNotBlank() &&
                sessionData.participantId.isNotBlank() &&
                sessionData.participantAge > 0 &&
                sessionData.carModel.isNotBlank()
    }

    /**
     * Open settings screen (placeholder)
     */
    private fun openSettings() {
        // TODO: Create SettingsActivity
        Snackbar.make(binding.root, "Settings screen - Coming soon", Snackbar.LENGTH_SHORT).show()
    }

    /**
     * Show confirmation dialog for new session when existing session exists
     */
    private fun showNewSessionConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Start New Session")
            .setMessage("Starting a new session will end the current session and save its data to a JSON file. Continue?")
            .setPositiveButton("Continue") { _, _ ->
                lifecycleScope.launch {
                    try {
                        // End current session and save data
                        SessionManager.endSession()

                        // Clear session state
                        currentSessionData = null
                        updateSessionUI(null)

                        // Navigate to participant info
                        navigateToParticipantInfo()

                    } catch (e: Exception) {
                        Log.e("MainSession", "Error ending current session", e)
                        showError("Error saving current session: ${e.message}")
                    }
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Show disconnect confirmation dialog
     */
    private fun showDisconnectConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Disconnect from Projector")
            .setMessage("This will disconnect from the projector and return to the connection screen. Any unsaved session data will be preserved.")
            .setPositiveButton("Disconnect") { _, _ ->
                disconnectAndReturn()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Show connection error and offer reconnection
     */
    private fun showConnectionError() {
        AlertDialog.Builder(this)
            .setTitle("Connection Error")
            .setMessage("The connection to the projector has been lost. Would you like to return to the connection screen?")
            .setPositiveButton("Reconnect") { _, _ ->
                returnToConnection()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Disconnect from projector and return to device discovery
     */
    private fun disconnectAndReturn() {
        NetworkManager.disconnect()
        returnToConnection()
    }

    /**
     * Return to connection screen
     */
    private fun returnToConnection() {
        val intent = Intent(this, DeviceDiscoveryActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    /**
     * Navigate to participant information screen
     */
    private fun navigateToParticipantInfo() {
        val intent = Intent(this, ParticipantInfoActivity::class.java)
        startActivity(intent)
    }

    /**
     * Navigate to task selection screen
     */
    private fun navigateToTaskSelection() {
        val intent = Intent(this, TaskSelectionActivity::class.java)
        startActivity(intent)
    }

    /**
     * Show error message
     */
    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}