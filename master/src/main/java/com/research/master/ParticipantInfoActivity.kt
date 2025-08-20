package com.research.master

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.research.master.databinding.ActivityParticipantInfoBinding
import com.research.master.utils.SessionManager
import com.research.master.network.NetworkManager
import kotlinx.coroutines.launch
import android.util.Log
import androidx.appcompat.app.AlertDialog

/**
 * Activity for collecting participant information before starting tasks
 * According to FR-SM-001: Collect name, identifier, age, and car model
 */
class ParticipantInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityParticipantInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityParticipantInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        supportActionBar?.title = "Participant Information"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Set up UI
        setupClickListeners()
        loadExistingData()
    }

    private fun setupClickListeners() {
        // Continue button
        binding.btnContinue.setOnClickListener {
            validateAndProceed()
        }

        // Settings button
        binding.btnSettings.setOnClickListener {
            // TODO: Open settings screen
            Snackbar.make(binding.root, "Settings screen - Coming soon!", Snackbar.LENGTH_SHORT).show()
        }

        // Voice check button
        binding.btnVoiceCheck.setOnClickListener {
            showVoiceCheckDialog()
        }

        // Clear button
        binding.btnClear.setOnClickListener {
            clearForm()
        }
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

        // Create session with participant information
        lifecycleScope.launch {
            try {
                // Initialize SessionManager (safe to call multiple times)
                SessionManager.initialize(this@ParticipantInfoActivity)

                val sessionId = SessionManager.createSession(
                    participantName = validName,
                    participantId = validId,
                    participantAge = validAge,
                    carModel = validCarModel
                )

                Log.d("ParticipantInfo", "Session created: $sessionId")

                // Store session ID in NetworkManager for task control
                NetworkManager.setCurrentSessionId(sessionId)

                // Save form data for convenience
                saveFormData(validName, validId, ageText!!)

                // Navigate to task selection
                val intent = Intent(this@ParticipantInfoActivity, TaskSelectionActivity::class.java)
                startActivity(intent)

            } catch (e: Exception) {
                Log.e("ParticipantInfo", "Failed to create session", e)
                Snackbar.make(
                    binding.root,
                    "Failed to create session: ${e.message}",
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showVoiceCheckDialog() {
        AlertDialog.Builder(this)
            .setTitle("Voice Recognition System Check")
            .setMessage("This will test the voice recognition system on the Projector device.\n\nMake sure the Projector is connected and ready.")
            .setPositiveButton("Start Voice Check") { _, _ ->
                startVoiceRecognitionCheck()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun startVoiceRecognitionCheck() {
        // TODO: Send VOICE_CHECK command to Projector
        // This should trigger FR-ST-011 calibration process
        Log.d("ParticipantInfo", "Starting voice recognition check")

        Snackbar.make(
            binding.root,
            "TODO: Send VOICE_CHECK command to Projector",
            Snackbar.LENGTH_LONG
        ).show()
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
        // Load previously entered data for convenience
        val prefs = getSharedPreferences("participant_prefs", MODE_PRIVATE)

        binding.etParticipantName.setText(prefs.getString("last_name", ""))
        binding.etParticipantId.setText(prefs.getString("last_id", ""))
        binding.etParticipantAge.setText(prefs.getString("last_age", ""))
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}