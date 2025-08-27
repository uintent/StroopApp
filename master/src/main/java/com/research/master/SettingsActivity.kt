package com.research.master

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.research.master.databinding.ActivitySettingsBinding
import com.research.master.utils.FileManager
import com.research.master.utils.DebugLogger
import kotlinx.coroutines.launch
import java.io.File

/**
 * SettingsActivity - Configuration screen for app settings
 * Handles:
 * - Export folder selection for finished session JSON files
 * - Debug logging settings (console logging, file logging, log file location)
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var fileManager: FileManager

    // Export settings state
    private var currentExportFolder: String = ""
    private var originalExportFolder: String = ""

    // Debug settings state
    private var isConsoleLoggingEnabled: Boolean = true
    private var isFileLoggingEnabled: Boolean = false
    private var currentLogFileFolder: String = ""
    private var originalConsoleLoggingEnabled: Boolean = true
    private var originalFileLoggingEnabled: Boolean = false
    private var originalLogFileFolder: String = ""

    // Folder picker launchers
    private val exportFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleExportFolderSelected(it) }
    }

    private val logFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { handleLogFolderSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize FileManager
        fileManager = FileManager(this)

        // Set up action bar
        supportActionBar?.title = getString(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        DebugLogger.d("SettingsActivity", "=== SETTINGS ACTIVITY STARTED ===")

        // Load current settings
        loadCurrentSettings()

        // Set up button listeners
        setupButtonListeners()

        // Update UI with current values
        updateUI()

        DebugLogger.d("SettingsActivity", "Settings activity initialization complete")
    }

    /**
     * Load current settings from storage
     */
    private fun loadCurrentSettings() {
        DebugLogger.d("SettingsActivity", "=== LOADING CURRENT SETTINGS ===")

        try {
            // Load export folder setting
            currentExportFolder = fileManager.getExportFolder()
            originalExportFolder = currentExportFolder

            // Load debug settings
            isConsoleLoggingEnabled = fileManager.isConsoleLoggingEnabled()
            isFileLoggingEnabled = fileManager.isFileLoggingEnabled()
            currentLogFileFolder = fileManager.getLogFileFolder()

            // Store original debug settings
            originalConsoleLoggingEnabled = isConsoleLoggingEnabled
            originalFileLoggingEnabled = isFileLoggingEnabled
            originalLogFileFolder = currentLogFileFolder

            DebugLogger.d("SettingsActivity", "Current export folder: $currentExportFolder")
            DebugLogger.d("SettingsActivity", "Console logging enabled: $isConsoleLoggingEnabled")
            DebugLogger.d("SettingsActivity", "File logging enabled: $isFileLoggingEnabled")
            DebugLogger.d("SettingsActivity", "Log file folder: $currentLogFileFolder")

        } catch (e: Exception) {
            DebugLogger.e("SettingsActivity", "Error loading settings", e)

            // Use default values on error
            currentExportFolder = getDefaultExportFolder()
            originalExportFolder = currentExportFolder
            isConsoleLoggingEnabled = true
            isFileLoggingEnabled = false
            currentLogFileFolder = getDefaultLogFileFolder()
            originalConsoleLoggingEnabled = isConsoleLoggingEnabled
            originalFileLoggingEnabled = isFileLoggingEnabled
            originalLogFileFolder = currentLogFileFolder

            Snackbar.make(
                binding.root,
                getString(R.string.settings_load_error, e.message),
                Snackbar.LENGTH_LONG
            ).show()
        }

        DebugLogger.d("SettingsActivity", "=== SETTINGS LOADED ===")
    }

    /**
     * Get the default export folder path
     */
    private fun getDefaultExportFolder(): String {
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            getString(R.string.settings_default_folder_name)).absolutePath
    }

    /**
     * Get the default log file folder path
     */
    private fun getDefaultLogFileFolder(): String {
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            getString(R.string.settings_default_log_folder_name)).absolutePath
    }

    /**
     * Set up button listeners
     */
    private fun setupButtonListeners() {
        // Export folder buttons
        binding.btnBrowseFolder.setOnClickListener {
            openExportFolderPicker()
        }

        binding.btnResetFolderDefault.setOnClickListener {
            resetExportFolderToDefault()
        }

        // Debug logging switches
        binding.switchConsoleLogging.setOnCheckedChangeListener { _, isChecked ->
            isConsoleLoggingEnabled = isChecked
            DebugLogger.d("SettingsActivity", "Console logging toggled: $isConsoleLoggingEnabled")
        }

        binding.switchFileLogging.setOnCheckedChangeListener { _, isChecked ->
            isFileLoggingEnabled = isChecked
            updateLogFileControls()
            DebugLogger.d("SettingsActivity", "File logging toggled: $isFileLoggingEnabled")
        }

        // Log file folder buttons
        binding.btnBrowseLogFolder.setOnClickListener {
            openLogFolderPicker()
        }

        binding.btnResetLogFolderDefault.setOnClickListener {
            resetLogFolderToDefault()
        }

        // Main action buttons
        binding.btnResetSettings.setOnClickListener {
            resetAllSettings()
        }

        binding.btnConfirmSettings.setOnClickListener {
            confirmSettings()
        }

        binding.btnCancelSettings.setOnClickListener {
            cancelSettings()
        }
    }

    /**
     * Update UI elements with current values
     */
    private fun updateUI() {
        DebugLogger.d("SettingsActivity", "=== UPDATING UI ===")

        // Update export folder display
        binding.textCurrentExportFolder.text = currentExportFolder
        updateExportFolderInfo()

        // Update debug logging switches
        binding.switchConsoleLogging.isChecked = isConsoleLoggingEnabled
        binding.switchFileLogging.isChecked = isFileLoggingEnabled

        // Update log file folder display and controls
        binding.textCurrentLogFileFolder.text = currentLogFileFolder
        updateLogFolderInfo()
        updateLogFileControls()

        DebugLogger.d("SettingsActivity", "UI updated with current settings")
    }

    /**
     * Update export folder information display
     */
    private fun updateExportFolderInfo() {
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

        binding.textExportFolderInfo.text = folderInfo

        // Update folder status color
        val isValid = folder.exists() && folder.isDirectory && fileManager.validateExportFolder(folder)
        val color = if (isValid) {
            getColor(android.R.color.holo_green_dark)
        } else {
            getColor(android.R.color.holo_red_dark)
        }
        binding.textExportFolderInfo.setTextColor(color)
    }

    /**
     * Update log file folder information display
     */
    private fun updateLogFolderInfo() {
        if (!isFileLoggingEnabled) {
            binding.textLogFolderInfo.text = getString(R.string.settings_log_folder_disabled)
            binding.textLogFolderInfo.setTextColor(getColor(android.R.color.darker_gray))
            return
        }

        val folder = File(currentLogFileFolder)

        val folderInfo = when {
            !folder.exists() -> getString(R.string.settings_log_folder_not_exist)
            !folder.isDirectory -> getString(R.string.settings_log_folder_not_directory)
            !folder.canWrite() -> getString(R.string.settings_log_folder_not_writable)
            else -> {
                val logFileCount = folder.listFiles { file ->
                    file.name.endsWith(".log") || file.name.endsWith(".txt")
                }?.size ?: 0
                getString(R.string.settings_log_folder_valid, logFileCount)
            }
        }

        binding.textLogFolderInfo.text = folderInfo

        // Update folder status color
        val isValid = folder.exists() && folder.isDirectory && folder.canWrite()
        val color = if (isValid) {
            getColor(android.R.color.holo_green_dark)
        } else {
            getColor(android.R.color.holo_red_dark)
        }
        binding.textLogFolderInfo.setTextColor(color)
    }

    /**
     * Update log file controls based on file logging enabled state
     */
    private fun updateLogFileControls() {
        val enabled = isFileLoggingEnabled

        binding.textCurrentLogFileFolder.isEnabled = enabled
        binding.btnBrowseLogFolder.isEnabled = enabled
        binding.btnResetLogFolderDefault.isEnabled = enabled
        binding.textLogFolderInfo.isEnabled = enabled

        // Update folder info display
        updateLogFolderInfo()
    }

    /**
     * Open system folder picker for export folder
     */
    private fun openExportFolderPicker() {
        DebugLogger.d("SettingsActivity", "Opening export folder picker")
        openFolderPicker(currentExportFolder, exportFolderPickerLauncher)
    }

    /**
     * Open system folder picker for log file folder
     */
    private fun openLogFolderPicker() {
        DebugLogger.d("SettingsActivity", "Opening log folder picker")
        openFolderPicker(currentLogFileFolder, logFolderPickerLauncher)
    }

    /**
     * Generic folder picker opener
     */
    private fun openFolderPicker(currentPath: String, launcher: androidx.activity.result.ActivityResultLauncher<Uri?>) {
        try {
            val startUri: Uri? = if (currentPath.isNotEmpty() && !currentPath.startsWith("content://")) {
                val currentFolder = File(currentPath)
                if (currentFolder.exists()) {
                    Uri.fromFile(currentFolder)
                } else {
                    null
                }
            } else {
                null
            }

            launcher.launch(startUri)

        } catch (e: Exception) {
            DebugLogger.e("SettingsActivity", "Error opening folder picker", e)
            Snackbar.make(
                binding.root,
                getString(R.string.settings_folder_picker_error, e.message),
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Handle export folder selection from picker
     */
    private fun handleExportFolderSelected(uri: Uri) {
        DebugLogger.d("SettingsActivity", "Export folder selected: $uri")
        handleFolderSelected(uri) { folderPath ->
            currentExportFolder = folderPath
            updateExportFolderInfo()
            binding.textCurrentExportFolder.text = currentExportFolder
        }
    }

    /**
     * Handle log folder selection from picker
     */
    private fun handleLogFolderSelected(uri: Uri) {
        DebugLogger.d("SettingsActivity", "Log folder selected: $uri")
        handleFolderSelected(uri) { folderPath ->
            currentLogFileFolder = folderPath
            updateLogFolderInfo()
            binding.textCurrentLogFileFolder.text = currentLogFileFolder
        }
    }

    /**
     * Generic folder selection handler
     */
    private fun handleFolderSelected(uri: Uri, onSuccess: (String) -> Unit) {
        try {
            // Take persistable permission
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )

            // Convert DocumentFile to path if possible
            val documentFile = DocumentFile.fromTreeUri(this, uri)
            if (documentFile != null && documentFile.isDirectory) {

                val folderPath = getFolderPathFromUri(uri) ?: uri.toString()

                onSuccess(folderPath)

                // Test write permissions
                testFolderWritePermissions(documentFile)

                Snackbar.make(
                    binding.root,
                    getString(R.string.settings_folder_selected),
                    Snackbar.LENGTH_SHORT
                ).show()

            } else {
                DebugLogger.e("SettingsActivity", "Selected URI is not a valid directory")
                Snackbar.make(
                    binding.root,
                    getString(R.string.settings_folder_invalid),
                    Snackbar.LENGTH_LONG
                ).show()
            }

        } catch (e: Exception) {
            DebugLogger.e("SettingsActivity", "Error processing selected folder", e)
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
            val docId = uri.lastPathSegment
            if (docId?.contains(":") == true) {
                val parts = docId.split(":")
                if (parts.size == 2) {
                    val storageId = parts[0]
                    val path = parts[1]

                    when (storageId.lowercase()) {
                        "primary" -> "${Environment.getExternalStorageDirectory()}/$path"
                        else -> null
                    }
                } else null
            } else null
        } catch (e: Exception) {
            DebugLogger.w("SettingsActivity", "Could not extract path from URI: $uri", e)
            null
        }
    }

    /**
     * Test write permissions on selected folder
     */
    private fun testFolderWritePermissions(documentFile: DocumentFile) {
        try {
            val testFile = documentFile.createFile("text/plain", "test_${System.currentTimeMillis()}.txt")

            if (testFile != null) {
                contentResolver.openOutputStream(testFile.uri)?.use { output ->
                    output.write("test".toByteArray())
                }
                testFile.delete()
                DebugLogger.d("SettingsActivity", "Folder write test successful")
            } else {
                DebugLogger.w("SettingsActivity", "Could not create test file - may have permission issues")
            }

        } catch (e: Exception) {
            DebugLogger.w("SettingsActivity", "Folder write test failed", e)
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
    private fun resetExportFolderToDefault() {
        DebugLogger.d("SettingsActivity", "Resetting export folder to default")

        currentExportFolder = getDefaultExportFolder()
        binding.textCurrentExportFolder.text = currentExportFolder
        updateExportFolderInfo()

        Snackbar.make(
            binding.root,
            getString(R.string.settings_folder_reset_default),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    /**
     * Reset log folder to default location
     */
    private fun resetLogFolderToDefault() {
        DebugLogger.d("SettingsActivity", "Resetting log folder to default")

        currentLogFileFolder = getDefaultLogFileFolder()
        binding.textCurrentLogFileFolder.text = currentLogFileFolder
        updateLogFolderInfo()

        Snackbar.make(
            binding.root,
            getString(R.string.settings_log_folder_reset_default),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    /**
     * Reset all settings to their original values
     */
    private fun resetAllSettings() {
        DebugLogger.d("SettingsActivity", "Resetting all settings to original values")

        currentExportFolder = originalExportFolder
        isConsoleLoggingEnabled = originalConsoleLoggingEnabled
        isFileLoggingEnabled = originalFileLoggingEnabled
        currentLogFileFolder = originalLogFileFolder

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
        DebugLogger.d("SettingsActivity", "=== CONFIRMING SETTINGS ===")

        lifecycleScope.launch {
            try {
                if (!validateSettings()) {
                    return@launch
                }

                // Save all settings
                fileManager.setExportFolder(currentExportFolder)
                fileManager.setConsoleLoggingEnabled(isConsoleLoggingEnabled)
                fileManager.setFileLoggingEnabled(isFileLoggingEnabled)
                fileManager.setLogFileFolder(currentLogFileFolder)

                DebugLogger.d("SettingsActivity", "Settings saved successfully")
                DebugLogger.d("SettingsActivity", "Export folder: $currentExportFolder")
                DebugLogger.d("SettingsActivity", "Console logging: $isConsoleLoggingEnabled")
                DebugLogger.d("SettingsActivity", "File logging: $isFileLoggingEnabled")
                DebugLogger.d("SettingsActivity", "Log file folder: $currentLogFileFolder")

                Snackbar.make(
                    binding.root,
                    getString(R.string.settings_saved_successfully),
                    Snackbar.LENGTH_SHORT
                ).show()

                binding.root.postDelayed({
                    navigateBackToMainSession()
                }, 1000)

            } catch (e: Exception) {
                DebugLogger.e("SettingsActivity", "Error saving settings", e)
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

        // Validate log file folder if file logging is enabled
        if (isFileLoggingEnabled) {
            if (currentLogFileFolder.isBlank()) {
                Snackbar.make(
                    binding.root,
                    getString(R.string.settings_log_folder_required),
                    Snackbar.LENGTH_LONG
                ).show()
                return false
            }

            if (!currentLogFileFolder.startsWith("content://")) {
                val logFolder = File(currentLogFileFolder)
                if (!logFolder.exists() || !logFolder.isDirectory || !logFolder.canWrite()) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.settings_log_folder_invalid),
                        Snackbar.LENGTH_LONG
                    ).show()
                    return false
                }
            }
        }

        return true
    }

    /**
     * Cancel settings without saving changes
     */
    private fun cancelSettings() {
        DebugLogger.d("SettingsActivity", "Canceling settings without saving")

        // Check if there are unsaved changes
        val hasChanges = currentExportFolder != originalExportFolder ||
                isConsoleLoggingEnabled != originalConsoleLoggingEnabled ||
                isFileLoggingEnabled != originalFileLoggingEnabled ||
                currentLogFileFolder != originalLogFileFolder

        if (hasChanges) {
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
            navigateBackToMainSession()
        }
    }

    /**
     * Navigate back to MainSessionActivity
     */
    private fun navigateBackToMainSession() {
        DebugLogger.d("SettingsActivity", "Navigating back to MainSessionActivity")

        val intent = Intent(this, MainSessionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        cancelSettings()
        return true
    }

    override fun onBackPressed() {
        super.onBackPressed()
        cancelSettings()
    }
}