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
     * Show error message
     */
    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
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

        binding.btnEndSession.setOnClickListener {
            showEndSessionConfirmation()
        }

        binding.btnDiscardSession.setOnClickListener {
            showDiscardSessionConfirmation()
        }
    }

    /**
     * Check for existing session and update UI accordingly
     */
    private fun checkForExistingSession() {
        lifecycleScope.launch {
            try {
                // Check for incomplete session (not properly ended)
                val incompleteSession = SessionManager.checkForIncompleteSession()
                currentSessionData = incompleteSession

                // Load session into SessionManager memory so end/discard can find it
                if (incompleteSession != null) {
                    SessionManager.resumeSession(incompleteSession)  // ADD THIS LINE
                    Log.d("MainSession", "Loaded session into SessionManager memory: ${incompleteSession.sessionId}")
                }

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
     * Only shows resume option if there's an actual incomplete session (both ended and discarded must be exactly 0)
     */
    private fun updateSessionUI(sessionData: SessionData?) {
        if (sessionData != null && sessionData.ended == 0 && sessionData.discarded == 0) {
            // Show session info for incomplete sessions only (both flags explicitly 0)
            Log.d("MainSession", "Showing session UI for: ${sessionData.sessionId} (ended=${sessionData.ended}, discarded=${sessionData.discarded})")

            binding.layoutExistingSession.visibility = android.view.View.VISIBLE
            binding.textExistingSessionInfo.text = buildString {
                append("Incomplete Session Found:\n")
                append("Participant: ${sessionData.participantName}\n")
                append("ID: ${sessionData.participantId}\n")
                append("Tasks completed: ${sessionData.tasks.size}\n")
                append("Status: ${getSessionStatusDisplay(sessionData.sessionStatus)}")
            }

            // Enable resume button for incomplete sessions
            binding.btnResumeSession.isEnabled = true
            binding.btnResumeSession.text = "Resume Incomplete Session"

            // Show end session button for incomplete sessions
            binding.btnEndSession.visibility = android.view.View.VISIBLE

            // Show discard session button for incomplete sessions
            binding.btnDiscardSession.visibility = android.view.View.VISIBLE

        } else {
            // No incomplete session - hide resume and end session options
            binding.layoutExistingSession.visibility = android.view.View.GONE
            binding.btnResumeSession.isEnabled = false
            binding.btnResumeSession.text = "No Session to Resume"
            binding.btnEndSession.visibility = android.view.View.GONE
            binding.btnDiscardSession.visibility = android.view.View.GONE

            if (sessionData != null) {
                val reason = when {
                    sessionData.ended != 0 -> "ended flag = ${sessionData.ended}"
                    sessionData.discarded != 0 -> "discarded flag = ${sessionData.discarded}"
                    else -> "unknown reason"
                }
                Log.d("MainSession", "Session not shown - $reason: ${sessionData.sessionId}")
            } else {
                Log.d("MainSession", "No session found")
            }
        }
    }

    /**
     * Show confirmation dialog for ending and saving session
     */
    private fun showEndSessionConfirmation() {
        val sessionData = currentSessionData ?: return

        AlertDialog.Builder(this)
            .setTitle("End & Save Session")
            .setMessage("This will end the current session and save all data to a JSON file.\n\nParticipant: ${sessionData.participantName}\nTasks completed: ${sessionData.tasks.size}\n\nThis action cannot be undone. Continue?")
            .setPositiveButton("End & Save Session") { _, _ ->
                endAndSaveSession()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Show confirmation dialog for discarding session without saving
     */
    private fun showDiscardSessionConfirmation() {
        val sessionData = currentSessionData ?: return

        AlertDialog.Builder(this)
            .setTitle("Discard Session")
            .setMessage("This will permanently delete the current session data without saving.\n\nParticipant: ${sessionData.participantName}\nTasks completed: ${sessionData.tasks.size}\n\n⚠️ This action cannot be undone. All session data will be lost.")
            .setPositiveButton("Discard Session") { _, _ ->
                discardSession()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * End the current session and save to JSON file
     */
    private fun endAndSaveSession() {
        lifecycleScope.launch {
            try {
                // Disable buttons during operation
                binding.btnEndSession.isEnabled = false
                binding.btnResumeSession.isEnabled = false
                binding.btnNewSession.isEnabled = false

                // Show progress in the button text
                binding.btnEndSession.text = "Saving session..."

                // End the session (this will save to JSON file)
                SessionManager.endSession()

                // Clear current session state
                currentSessionData = null
                updateSessionUI(null)

                // Re-enable new session button
                binding.btnNewSession.isEnabled = true

                // Show success message
                Snackbar.make(
                    binding.root,
                    "Session ended and saved successfully!",
                    Snackbar.LENGTH_LONG
                ).show()

                Log.d("MainSession", "Session ended and saved successfully")

            } catch (e: Exception) {
                Log.e("MainSession", "Error ending session", e)

                // Re-enable buttons on error
                binding.btnEndSession.isEnabled = true
                binding.btnResumeSession.isEnabled = true
                binding.btnNewSession.isEnabled = true
                binding.btnEndSession.text = "End & Save Session"

                showError("Error saving session: ${e.message}")
            }
        }
    }

    /**
     * Discard the current session without saving
     */
    private fun discardSession() {
        lifecycleScope.launch {
            try {
                // Disable buttons during operation
                binding.btnDiscardSession.isEnabled = false
                binding.btnResumeSession.isEnabled = false
                binding.btnEndSession.isEnabled = false
                binding.btnNewSession.isEnabled = false

                // Show progress in the button text
                binding.btnDiscardSession.text = "Discarding session..."

                // Clear the session without saving
                SessionManager.clearSession()

                // Clear current session state
                currentSessionData = null
                updateSessionUI(null)

                // Re-enable new session button
                binding.btnNewSession.isEnabled = true

                // Show success message
                Snackbar.make(
                    binding.root,
                    "Session discarded successfully",
                    Snackbar.LENGTH_LONG
                ).show()

                Log.d("MainSession", "Session discarded without saving")

            } catch (e: Exception) {
                Log.e("MainSession", "Error discarding session", e)

                // Re-enable buttons on error
                binding.btnDiscardSession.isEnabled = true
                binding.btnResumeSession.isEnabled = true
                binding.btnEndSession.isEnabled = true
                binding.btnNewSession.isEnabled = true
                binding.btnDiscardSession.text = "Discard Session (No Save)"

                showError("Error discarding session: ${e.message}")
            }
        }
    }

    private fun getSessionStatusDisplay(status: String): String {
        return when (status) {
            "in_progress" -> "In Progress"
            "completed_normally" -> "Completed"
            "interrupted" -> "Interrupted"
            else -> status.replaceFirstChar { it.uppercase() }
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
     * Only works if there's an actual incomplete session (both ended and discarded must be exactly 0)
     */
    private fun resumeExistingSession() {
        val sessionData = currentSessionData ?: return

        // Double-check the session is actually incomplete using explicit flag checks
        if (sessionData.ended != 0) {
            showError("This session has already been ended (flag=${sessionData.ended}). Please start a new session.")
            return
        }

        if (sessionData.discarded != 0) {
            showError("This session has been discarded (flag=${sessionData.discarded}). Please start a new session.")
            return
        }

        Log.d("MainSession", "Resuming session: ${sessionData.sessionId} (ended=${sessionData.ended}, discarded=${sessionData.discarded})")

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
     * Open settings screen
     */
    private fun openSettings() {
        Log.d("MainSession", "Opening settings screen")

        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    /**
     * Show confirmation dialog for new session when existing session exists
     */
    private fun showNewSessionConfirmation() {
        val sessionData = currentSessionData ?: return

        AlertDialog.Builder(this)
            .setTitle("Start New Session")
            .setMessage("You have an active session that needs to be handled first.\n\nParticipant: ${sessionData.participantName}\nTasks completed: ${sessionData.tasks.size}\n\nWhat would you like to do with the current session?")
            .setPositiveButton("End & Save Session") { _, _ ->
                handleCurrentSessionBeforeNew(endSession = true)
            }
            .setNeutralButton("Discard Session") { _, _ ->
                handleCurrentSessionBeforeNew(endSession = false)
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
        intent.putExtra("NEW_SESSION", true) // Signal this is a new session
        startActivity(intent)
    }

    /**
     * Navigate to task selection screen
     */
    private fun navigateToTaskSelection() {
        val intent = Intent(this, TaskSelectionActivity::class.java)
        intent.putExtra("RESUME_SESSION", true) // Signal this is resuming a session
        startActivity(intent)
    }

    /**
     * Handle the current session before starting a new one
     * @param endSession true to end and save, false to discard
     */
    private fun handleCurrentSessionBeforeNew(endSession: Boolean) {
        lifecycleScope.launch {
            try {
                // Disable new session button during operation
                binding.btnNewSession.isEnabled = false
                binding.btnNewSession.text = if (endSession) "Saving session..." else "Discarding session..."

                if (endSession) {
                    // End current session and save data
                    SessionManager.endSession()
                    Log.d("MainSession", "Ended current session before starting new one")
                } else {
                    // Discard current session without saving
                    SessionManager.clearSession()
                    Log.d("MainSession", "Discarded current session before starting new one")
                }

                // Clear session state
                currentSessionData = null
                updateSessionUI(null)

                // Navigate to participant info
                navigateToParticipantInfo()

            } catch (e: Exception) {
                Log.e("MainSession", "Error handling current session", e)

                // Re-enable button and restore text
                binding.btnNewSession.isEnabled = true
                binding.btnNewSession.text = "Start New Session"

                val action = if (endSession) "saving" else "discarding"
                showError("Error $action current session: ${e.message}")
            }
        }
    }
}