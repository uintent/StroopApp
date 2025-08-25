package com.research.master

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.research.master.databinding.ActivitySettingsBinding
import com.research.master.utils.FileManager
import kotlinx.coroutines.launch
import java.io.File

/**
 * SettingsActivity - Configuration screen for app settings
 * Currently handles:
 * - Export folder selection for finished session JSON files
 * - Future: Other app settings can be added here
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var fileManager: FileManager

    // Settings state
    private var currentExportFolder: String = ""
    private var originalExportFolder: String = ""

    // Folder picker launcher
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleFolderSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize FileManager
        fileManager = FileManager(this)

        // Set up action bar (using the default one provided by theme)
        supportActionBar?.title = getString(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        Log.d("SettingsActivity", "=== SETTINGS ACTIVITY STARTED ===")

        // Load current settings
        loadCurrentSettings()

        // Set up button listeners
        setupButtonListeners()

        // Update UI with current values
        updateUI()

        Log.d("SettingsActivity", "Settings activity initialization complete")
    }

    /**
     * Load current settings from storage
     */
    private fun loadCurrentSettings() {
        Log.d("SettingsActivity", "=== LOADING CURRENT SETTINGS ===")

        try {
            // Load export folder setting
            currentExportFolder = fileManager.getExportFolder()
            originalExportFolder = currentExportFolder

            Log.d("SettingsActivity", "Current export folder: $currentExportFolder")
            Log.d("SettingsActivity", "Original export folder (for reset): $originalExportFolder")

        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error loading settings", e)

            // Use default values on error
            currentExportFolder = getDefaultExportFolder()
            originalExportFolder = currentExportFolder

            Snackbar.make(
                binding.root,
                getString(R.string.settings_load_error, e.message),
                Snackbar.LENGTH_LONG
            ).show()
        }

        Log.d("SettingsActivity", "=== SETTINGS LOADED ===")
    }

    /**
     * Get the default export folder path
     */
    private fun getDefaultExportFolder(): String {
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "StroopApp_Sessions").absolutePath
    }

    /**
     * Set up button listeners
     */
    private fun setupButtonListeners() {
        // Browse folder button
        binding.btnBrowseFolder.setOnClickListener {
            openFolderPicker()
        }

        // Reset folder to default button
        binding.btnResetFolderDefault.setOnClickListener {
            resetFolderToDefault()
        }

        // Reset all settings button (revert to original values)
        binding.btnResetSettings.setOnClickListener {
            resetAllSettings()
        }

        // Confirm button (save and exit)
        binding.btnConfirmSettings.setOnClickListener {
            confirmSettings()
        }

        // Cancel button (exit without saving changes)
        binding.btnCancelSettings.setOnClickListener {
            cancelSettings()
        }
    }

    /**
     * Update UI elements with current values
     */
    private fun updateUI() {
        Log.d("SettingsActivity", "=== UPDATING UI ===")

        // Update export folder display
        binding.textCurrentExportFolder.text = currentExportFolder

        // Update folder info
        updateFolderInfo()

        Log.d("SettingsActivity", "UI updated with current settings")
    }

    /**
     * Update folder information display
     */
    private fun updateFolderInfo() {
        val folder = File(currentExportFolder)

        val folderInfo = when {
            !folder.exists() -> getString(R.string.settings_folder_not_exist)
            !folder.isDirectory -> getString(R.string.settings_folder_not_directory)
            !fileManager.validateExportFolder(folder) -> getString(R.string.settings_folder_not_writable)
            else -> {
                val fileCount = folder.listFiles { file ->
                    file.name.endsWith(".json")
                }?.size ?: 0
                getString(R.string.settings_folder_valid, fileCount)
            }
        }

        binding.textFolderInfo.text = folderInfo

        // Update folder status icon/color
        val isValid = folder.exists() && folder.isDirectory && fileManager.validateExportFolder(folder)
        if (isValid) {
            binding.textFolderInfo.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            binding.textFolderInfo.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }

    /**
     * Open system folder picker
     */
    private fun openFolderPicker() {
        Log.d("SettingsActivity", "Opening folder picker")

        try {
            // For OpenDocumentTree contract, we can pass a Uri to suggest starting location
            val startUri: Uri? = if (currentExportFolder.isNotEmpty() && !currentExportFolder.startsWith("content://")) {
                val currentFolder = File(currentExportFolder)
                if (currentFolder.exists()) {
                    Uri.fromFile(currentFolder)
                } else {
                    null
                }
            } else {
                null
            }

            folderPickerLauncher.launch(startUri)

        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error opening folder picker", e)
            Snackbar.make(
                binding.root,
                getString(R.string.settings_folder_picker_error, e.message),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Handle folder selection from picker
     */
    private fun handleFolderSelected(uri: Uri) {
        Log.d("SettingsActivity", "Folder selected: $uri")

        try {
            // Take persistable permission
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            // Convert DocumentFile to path if possible
            val documentFile = DocumentFile.fromTreeUri(this, uri)
            if (documentFile != null && documentFile.isDirectory) {

                // For Android's document tree URIs, we need to handle this specially
                // Try to get a real path, or use the URI directly
                val folderPath = getFolderPathFromUri(uri) ?: uri.toString()

                currentExportFolder = folderPath
                Log.d("SettingsActivity", "Export folder updated to: $currentExportFolder")

                // Update UI
                updateUI()

                // Test write permissions
                testFolderWritePermissions(documentFile)

                Snackbar.make(
                    binding.root,
                    getString(R.string.settings_folder_selected),
                    Snackbar.LENGTH_SHORT
                ).show()

            } else {
                Log.e("SettingsActivity", "Selected URI is not a valid directory")
                Snackbar.make(
                    binding.root,
                    getString(R.string.settings_folder_invalid),
                    Snackbar.LENGTH_LONG
                ).show()
            }

        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error processing selected folder", e)
            Snackbar.make(
                binding.root,
                getString(R.string.settings_folder_process_error, e.message),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Try to get a real file path from a content URI
     */
    private fun getFolderPathFromUri(uri: Uri): String? {
        return try {
            // For some URIs we can extract the real path
            val docId = uri.lastPathSegment
            if (docId?.contains(":") == true) {
                val parts = docId.split(":")
                if (parts.size == 2) {
                    val storageId = parts[0]
                    val path = parts[1]

                    // Try to map to real storage paths
                    when (storageId.lowercase()) {
                        "primary" -> "${Environment.getExternalStorageDirectory()}/$path"
                        else -> null // External SD cards are harder to map
                    }
                } else null
            } else null
        } catch (e: Exception) {
            Log.w("SettingsActivity", "Could not extract path from URI: $uri", e)
            null
        }
    }

    /**
     * Test write permissions on selected folder
     */
    private fun testFolderWritePermissions(documentFile: DocumentFile) {
        try {
            // Try to create a test file
            val testFile = documentFile.createFile("application/json", "test_${System.currentTimeMillis()}.json")

            if (testFile != null) {
                // Write test content
                contentResolver.openOutputStream(testFile.uri)?.use { output ->
                    output.write("{\"test\": true}".toByteArray())
                }

                // Delete test file
                testFile.delete()

                Log.d("SettingsActivity", "Folder write test successful")
            } else {
                Log.w("SettingsActivity", "Could not create test file - may have permission issues")
            }

        } catch (e: Exception) {
            Log.w("SettingsActivity", "Folder write test failed", e)
            Snackbar.make(
                binding.root,
                getString(R.string.settings_folder_write_test_failed),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Reset export folder to default location
     */
    private fun resetFolderToDefault() {
        Log.d("SettingsActivity", "Resetting folder to default")

        currentExportFolder = getDefaultExportFolder()
        updateUI()

        Snackbar.make(
            binding.root,
            getString(R.string.settings_folder_reset_default),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    /**
     * Reset all settings to their original values (when screen was opened)
     */
    private fun resetAllSettings() {
        Log.d("SettingsActivity", "Resetting all settings to original values")

        currentExportFolder = originalExportFolder
        updateUI()

        Snackbar.make(
            binding.root,
            getString(R.string.settings_reset_original),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    /**
     * Confirm and save settings
     */
    private fun confirmSettings() {
        Log.d("SettingsActivity", "=== CONFIRMING SETTINGS ===")

        lifecycleScope.launch {
            try {
                // Validate settings before saving
                if (!validateSettings()) {
                    return@launch
                }

                // Save export folder setting
                fileManager.setExportFolder(currentExportFolder)

                Log.d("SettingsActivity", "Settings saved successfully")
                Log.d("SettingsActivity", "Export folder: $currentExportFolder")

                // Show success message
                Snackbar.make(
                    binding.root,
                    getString(R.string.settings_saved_successfully),
                    Snackbar.LENGTH_SHORT
                ).show()

                // Return to main session activity after a brief delay
                binding.root.postDelayed({
                    navigateBackToMainSession()
                }, 1000)

            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error saving settings", e)
                Snackbar.make(
                    binding.root,
                    getString(R.string.settings_save_error, e.message),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Validate current settings before saving
     */
    private fun validateSettings(): Boolean {
        // Validate export folder
        if (currentExportFolder.isBlank()) {
            Snackbar.make(
                binding.root,
                getString(R.string.settings_export_folder_required),
                Snackbar.LENGTH_LONG
            ).show()
            return false
        }

        // If it's a file path (not URI), check if writable
        if (!currentExportFolder.startsWith("content://")) {
            val folder = File(currentExportFolder)
            if (!fileManager.validateExportFolder(folder)) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.settings_export_folder_invalid),
                    Snackbar.LENGTH_LONG
                ).show()
                return false
            }
        }

        return true
    }

    /**
     * Cancel settings without saving changes
     */
    private fun cancelSettings() {
        Log.d("SettingsActivity", "Canceling settings without saving")

        // Check if there are unsaved changes
        if (currentExportFolder != originalExportFolder) {
            // Show confirmation dialog
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.settings_unsaved_changes_title))
                .setMessage(getString(R.string.settings_unsaved_changes_message))
                .setPositiveButton(getString(R.string.settings_discard_changes)) { _, _ ->
                    navigateBackToMainSession()
                }
                .setNegativeButton(getString(R.string.settings_keep_editing)) { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        } else {
            // No changes, close directly
            navigateBackToMainSession()
        }
    }

    /**
     * Navigate back to MainSessionActivity
     */
    private fun navigateBackToMainSession() {
        Log.d("SettingsActivity", "Navigating back to MainSessionActivity")

        val intent = Intent(this, MainSessionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    /**
     * Handle toolbar back navigation
     */
    override fun onSupportNavigateUp(): Boolean {
        cancelSettings()
        return true
    }

    /**
     * Handle Android back button
     */
    override fun onBackPressed() {
        super.onBackPressed()
        cancelSettings()
    }
}