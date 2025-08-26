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
        binding.toolbar.title = getString(R.string.main_session_title)

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
                showError(getString(R.string.main_session_error_format, getString(R.string.main_session_error_checking), e.message))
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
            binding.textExistingSessionInfo.text = getString(
                R.string.main_session_incomplete_session_info,
                sessionData.participantName,
                sessionData.participantId,
                sessionData.tasks.size,
                getSessionStatusDisplay(sessionData.sessionStatus)
            )

            // Enable resume button for incomplete sessions
            binding.btnResumeSession.isEnabled = true
            binding.btnResumeSession.text = getString(R.string.main_session_resume_incomplete)

            // Show end session button for incomplete sessions
            binding.btnEndSession.visibility = android.view.View.VISIBLE

            // Show discard session button for incomplete sessions
            binding.btnDiscardSession.visibility = android.view.View.VISIBLE

        } else {
            // No incomplete session - hide resume and end session options
            binding.layoutExistingSession.visibility = android.view.View.GONE
            binding.btnResumeSession.isEnabled = false
            binding.btnResumeSession.text = getString(R.string.main_session_no_session_resume)
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
            .setTitle(getString(R.string.main_session_end_save_title))
            .setMessage(getString(
                R.string.main_session_end_save_message,
                sessionData.participantName,
                sessionData.tasks.size
            ))
            .setPositiveButton(getString(R.string.main_session_end_save_button)) { _, _ ->
                endAndSaveSession()
            }
            .setNegativeButton(getString(R.string.asq_skip_cancel)) { dialog, _ ->
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
            .setTitle(getString(R.string.main_session_discard_title))
            .setMessage(getString(
                R.string.main_session_discard_message,
                sessionData.participantName,
                sessionData.tasks.size
            ))
            .setPositiveButton(getString(R.string.main_session_discard_button)) { _, _ ->
                discardSession()
            }
            .setNegativeButton(getString(R.string.asq_skip_cancel)) { dialog, _ ->
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
                binding.btnEndSession.text = getString(R.string.main_session_saving)

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
                    getString(R.string.main_session_saved_success),
                    Snackbar.LENGTH_LONG
                ).show()

                Log.d("MainSession", "Session ended and saved successfully")

            } catch (e: Exception) {
                Log.e("MainSession", "Error ending session", e)

                // Re-enable buttons on error
                binding.btnEndSession.isEnabled = true
                binding.btnResumeSession.isEnabled = true
                binding.btnNewSession.isEnabled = true
                binding.btnEndSession.text = getString(R.string.main_session_end_save_button)

                showError(getString(R.string.main_session_error_format, getString(R.string.main_session_error_ending), e.message))
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
                binding.btnDiscardSession.text = getString(R.string.main_session_discarding)

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
                    getString(R.string.main_session_discarded_success),
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
                binding.btnDiscardSession.text = getString(R.string.main_session_discard_button)

                showError(getString(R.string.main_session_error_format, getString(R.string.main_session_error_discarding), e.message))
            }
        }
    }

    private fun getSessionStatusDisplay(status: String): String {
        return when (status) {
            "in_progress" -> getString(R.string.main_session_status_in_progress)
            "completed_normally" -> getString(R.string.main_session_status_completed)
            "interrupted" -> getString(R.string.main_session_status_interrupted)
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
            binding.textConnectionStatus.text = getString(
                R.string.main_session_connected_format,
                sessionId.take(8) + "..."
            )
            binding.textConnectionStatus.setTextColor(getColor(android.R.color.holo_green_dark))

            // Enable session buttons
            binding.btnNewSession.isEnabled = true

        } else {
            binding.textConnectionStatus.text = getString(R.string.main_session_connection_error)
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
                showError(getString(R.string.main_session_error_format, getString(R.string.main_session_error_starting), e.message))
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
            showError(getString(R.string.main_session_session_ended_already, sessionData.ended))
            return
        }

        if (sessionData.discarded != 0) {
            showError(getString(R.string.main_session_session_discarded_already, sessionData.discarded))
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
                showError(getString(R.string.main_session_error_format, getString(R.string.main_session_error_resuming), e.message))
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
            .setTitle(getString(R.string.main_session_new_session_title))
            .setMessage(getString(
                R.string.main_session_new_session_message,
                sessionData.participantName,
                sessionData.tasks.size
            ))
            .setPositiveButton(getString(R.string.main_session_end_save_button)) { _, _ ->
                handleCurrentSessionBeforeNew(endSession = true)
            }
            .setNeutralButton(getString(R.string.main_session_discard_button)) { _, _ ->
                handleCurrentSessionBeforeNew(endSession = false)
            }
            .setNegativeButton(getString(R.string.asq_skip_cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Show disconnect confirmation dialog
     */
    private fun showDisconnectConfirmation() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.main_session_disconnect_title))
            .setMessage(getString(R.string.main_session_disconnect_message))
            .setPositiveButton(getString(R.string.main_session_disconnect_button)) { _, _ ->
                disconnectAndReturn()
            }
            .setNegativeButton(getString(R.string.asq_skip_cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Show connection error and offer reconnection
     */
    private fun showConnectionError() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.main_session_connection_lost_title))
            .setMessage(getString(R.string.main_session_connection_lost_message))
            .setPositiveButton(getString(R.string.main_session_reconnect)) { _, _ ->
                returnToConnection()
            }
            .setNegativeButton(getString(R.string.asq_skip_cancel)) { dialog, _ ->
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
                binding.btnNewSession.text = if (endSession) getString(R.string.main_session_new_session_saving) else getString(R.string.main_session_new_session_discarding)

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
                binding.btnNewSession.text = getString(R.string.main_session_btn_new_session)

                val action = if (endSession) getString(R.string.main_session_error_ending) else getString(R.string.main_session_error_discarding)
                showError(getString(R.string.main_session_error_format, action, e.message))
            }
        }
    }
}