package com.research.master

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.research.master.databinding.ActivityParticipantInfoBinding
import com.research.master.utils.SessionManager
import com.research.master.utils.SessionData
import com.research.master.network.NetworkManager
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Activity for collecting participant information before starting tasks
 * According to FR-SM-001: Collect name, identifier, age, and car model
 * UPDATED: Removed pre-session options, added session cleanup logic
 */
class ParticipantInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParticipantInfoBinding
    private var isNewSession: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityParticipantInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar with back navigation
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Participant Information"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Initialize SessionManager
        SessionManager.initialize(this)

        // Check if this is a new session from intent (synchronous)
        isNewSession = intent.getBooleanExtra("NEW_SESSION", false)
        Log.d("ParticipantInfo", "Intent NEW_SESSION flag: $isNewSession")

        // Set up UI
        setupClickListeners()

        // Handle session state and data loading based on intent flag
        if (isNewSession) {
            // For new sessions, clear the form immediately and don't load any data
            clearForm()
            Log.d("ParticipantInfo", "New session - form cleared, not loading any previous data")
        } else {
            // For resume sessions, check existing session data first
            checkForExistingSessionData()
            // Then load convenience data if no session data was found
            loadExistingData()
        }
    }

    /**
     * Check for existing session data when resuming (not for new sessions)
     */
    private fun checkForExistingSessionData() {
        lifecycleScope.launch {
            try {
                val currentSession = SessionManager.currentSession.value

                if (currentSession != null) {
                    // Resuming session - load participant data
                    Log.d("ParticipantInfo", "Resuming session - loading participant data")
                    loadSessionData(currentSession)
                } else {
                    Log.d("ParticipantInfo", "No current session found for resume")
                }

            } catch (e: Exception) {
                Log.e("ParticipantInfo", "Error checking for existing session data", e)
            }
        }
    }

    /**
     * Check if we're coming from "New Session" button
     * This is now determined by intent extras from MainSessionActivity
     * NOTE: This method is kept for backward compatibility but is no longer actively used
     */
    private fun isComingFromNewSession(): Boolean {
        return intent.getBooleanExtra("NEW_SESSION", false)
    }

    /**
     * Load participant data from existing session
     */
    private fun loadSessionData(session: SessionData) {
        binding.etParticipantName.setText(session.participantName)
        binding.etParticipantId.setText(session.participantId)
        binding.etParticipantAge.setText(session.participantAge.toString())

        when (session.carModel) {
            "old" -> binding.radioGroupCarModel.check(R.id.radio_car_old)
            "new" -> binding.radioGroupCarModel.check(R.id.radio_car_new)
        }

        Log.d("ParticipantInfo", "Loaded session data for: ${session.participantName}")
    }

    private fun setupClickListeners() {
        // Continue button
        binding.btnContinue.setOnClickListener {
            validateAndProceed()
        }

        // Clear button
        binding.btnClear.setOnClickListener {
            showClearConfirmation()
        }
    }

    /**
     * Show confirmation before clearing form
     */
    private fun showClearConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Clear Form")
            .setMessage("This will clear all participant information. Continue?")
            .setPositiveButton("Clear") { _, _ ->
                clearForm()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun validateAndProceed() {
        // Clear previous errors
        binding.layoutParticipantName.error = null
        binding.layoutParticipantId.error = null
        binding.layoutParticipantAge.error = null

        // Get input values
        val name = binding.etParticipantName.text?.toString()?.trim()
        val id = binding.etParticipantId.text?.toString()?.trim()
        val ageText = binding.etParticipantAge.text?.toString()?.trim()
        val carModel = when (binding.radioGroupCarModel.checkedRadioButtonId) {
            R.id.radio_car_old -> "old"
            R.id.radio_car_new -> "new"
            else -> null
        }

        var hasError = false

        // Validate participant name
        if (name.isNullOrEmpty()) {
            binding.layoutParticipantName.error = "Please enter participant name"
            hasError = true
        }

        // Validate participant ID (alphanumeric)
        if (id.isNullOrEmpty()) {
            binding.layoutParticipantId.error = "Please enter participant identifier"
            hasError = true
        } else if (!id.matches(Regex("^[a-zA-Z0-9]+$"))) {
            binding.layoutParticipantId.error = "Identifier must be alphanumeric only"
            hasError = true
        }

        // Validate age
        val age = if (ageText.isNullOrEmpty()) {
            binding.layoutParticipantAge.error = "Please enter participant age"
            hasError = true
            null
        } else {
            try {
                val ageValue = ageText.toInt()
                if (ageValue < 1 || ageValue > 150) {
                    binding.layoutParticipantAge.error = "Please enter a valid age (1-150)"
                    hasError = true
                    null
                } else {
                    ageValue
                }
            } catch (e: NumberFormatException) {
                binding.layoutParticipantAge.error = "Please enter a valid number"
                hasError = true
                null
            }
        }

        // Validate car model selection
        if (carModel == null) {
            Snackbar.make(binding.root, "Please select a car model", Snackbar.LENGTH_LONG).show()
            hasError = true
        }

        // If validation fails, don't proceed
        if (hasError) {
            return
        }

        // At this point, all values are validated and non-null
        val validName = name!! // Safe because we checked for null/empty above
        val validId = id!!     // Safe because we checked for null/empty above
        val validAge = age!!   // Safe because we checked for null above
        val validCarModel = carModel!! // Safe because we checked for null above

        // Create or update session with participant information
        lifecycleScope.launch {
            try {
                val sessionId = if (isNewSession) {
                    // Create new session
                    Log.d("ParticipantInfo", "Creating new session")
                    SessionManager.createSession(
                        participantName = validName,
                        participantId = validId,
                        participantAge = validAge,
                        carModel = validCarModel
                    )
                } else {
                    // Update existing session (if resuming)
                    val currentSession = SessionManager.currentSession.value
                    if (currentSession != null) {
                        Log.d("ParticipantInfo", "Updating existing session participant data")
                        // TODO: Add updateSessionParticipantInfo method to SessionManager
                        // For now, create new session with existing tasks
                        SessionManager.createSession(
                            participantName = validName,
                            participantId = validId,
                            participantAge = validAge,
                            carModel = validCarModel
                        )
                    } else {
                        // Fallback: create new session
                        SessionManager.createSession(
                            participantName = validName,
                            participantId = validId,
                            participantAge = validAge,
                            carModel = validCarModel
                        )
                    }
                }

                Log.d("ParticipantInfo", "Session ready: $sessionId")

                // Store session ID in NetworkManager for task control
                NetworkManager.setCurrentSessionId(sessionId)

                // Save form data for convenience
                saveFormData(validName, validId, ageText!!)

                // Navigate to task selection
                val intent = Intent(this@ParticipantInfoActivity, TaskSelectionActivity::class.java)
                startActivity(intent)

            } catch (e: Exception) {
                Log.e("ParticipantInfo", "Failed to create/update session", e)
                Snackbar.make(
                    binding.root,
                    "Failed to save participant information: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * PLACEHOLDER: Save previous session data to JSON file
     * This will be called when a new session is started while a previous session exists
     * TODO: Implement when JSON export functionality is ready
     */
    private suspend fun savePreviousSessionToFile(previousSession: SessionData) {
        try {
            Log.d("ParticipantInfo", "TODO: Save previous session to JSON file")
            // TODO: Implement JSON export logic
            // This should:
            // 1. Generate filename according to FR-DP-003 format
            // 2. Export session data to JSON
            // 3. Save to user-configured location
            // 4. Handle file conflicts and errors

            // Placeholder implementation:
            Log.d("ParticipantInfo", "Previous session data:")
            Log.d("ParticipantInfo", "  Participant: ${previousSession.participantName}")
            Log.d("ParticipantInfo", "  Tasks completed: ${previousSession.tasks.size}")
            Log.d("ParticipantInfo", "  Status: ${previousSession.sessionStatus}")

        } catch (e: Exception) {
            Log.e("ParticipantInfo", "Failed to save previous session", e)
            // Don't block the new session creation if export fails
        }
    }

    private fun clearForm() {
        binding.etParticipantName.text?.clear()
        binding.etParticipantId.text?.clear()
        binding.etParticipantAge.text?.clear()
        binding.radioGroupCarModel.clearCheck()

        // Clear errors
        binding.layoutParticipantName.error = null
        binding.layoutParticipantId.error = null
        binding.layoutParticipantAge.error = null

        Log.d("ParticipantInfo", "Form cleared completely")
    }

    private fun saveFormData(name: String, id: String, age: String) {
        // Save for convenience in case user navigates back
        getSharedPreferences("participant_prefs", MODE_PRIVATE).edit().apply {
            putString("last_name", name)
            putString("last_id", id)
            putString("last_age", age)
            apply()
        }
    }

    private fun loadExistingData() {
        // Only load convenience data if this is NOT a new session
        // and no session data was already loaded from an existing session
        if (!isNewSession && binding.etParticipantName.text.isNullOrEmpty()) {
            val prefs = getSharedPreferences("participant_prefs", MODE_PRIVATE)

            binding.etParticipantName.setText(prefs.getString("last_name", ""))
            binding.etParticipantId.setText(prefs.getString("last_id", ""))
            binding.etParticipantAge.setText(prefs.getString("last_age", ""))

            Log.d("ParticipantInfo", "Loaded convenience data from preferences")
        } else if (isNewSession) {
            Log.d("ParticipantInfo", "New session - NOT loading convenience data, form stays clear")
        }
    }

    /**
     * Handle back navigation - return to MainSessionActivity
     */
    override fun onSupportNavigateUp(): Boolean {
        // Navigate back to MainSessionActivity
        val intent = Intent(this, MainSessionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
        return true
    }

    /**
     * Handle Android back button - same behavior as up navigation
     */
    override fun onBackPressed() {
        super.onBackPressed()
        onSupportNavigateUp()
    }
}