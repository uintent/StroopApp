package com.research.master.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.nio.charset.Charset
import com.research.shared.models.*
import kotlinx.serialization.json.*

/**
 * Centralized file management for StroopApp with task iteration support.
 * All file operations should go through this class to ensure consistency.
 *
 * ENHANCED: Now supports task iterations - multiple attempts of the same task
 * are stored as separate entries with incrementing iteration_counter values.
 *
 * Responsibilities:
 * - Session JSON file creation and updates with iteration support
 * - Configuration file reading (assets and external)
 * - SharedPreferences operations
 * - File naming conventions and validation
 * - Crash-safe incremental saves
 * - Session recovery from temp files
 * - Migration to user-selected folders
 * - External storage management
 * - Task iteration tracking and management
 */

/**
 * Participant form data for convenience storage
 */
data class ParticipantFormData(
    val name: String,
    val id: String,
    val age: String
)

/**
 * Internal data class to track config source
 */
data class ConfigSource(
    val content: String?,
    val source: String
)

/**
 * Result of configuration loading operation
 */
sealed class ConfigLoadResult {
    data class Success(val config: RuntimeConfig, val source: String) : ConfigLoadResult()
    data class Error(val error: ConfigError) : ConfigLoadResult()
}

/**
 * Specific configuration error types for better error handling
 */
sealed class ConfigError {
    data class FileNotFound(val message: String) : ConfigError()
    data class InvalidJson(val message: String) : ConfigError()
    data class ValidationFailed(val message: String) : ConfigError()
    data class UnexpectedError(val message: String) : ConfigError()
}

/**
 * Structure validation results for detailed error reporting
 */
sealed class StructureValidationResult {
    object Valid : StructureValidationResult()
    data class MissingSection(val sectionName: String) : StructureValidationResult()
    data class InvalidColors(val message: String) : StructureValidationResult()
    data class InvalidTasks(val message: String) : StructureValidationResult()
    data class InvalidTaskSequence(val listId: String, val taskId: String, val message: String) : StructureValidationResult()
    data class InvalidTiming(val message: String) : StructureValidationResult()
}

/**
 * Exception classes for better error handling
 */
abstract class FileManagerException(message: String, cause: Throwable? = null) : Exception(message, cause)
class SessionFileException(message: String, cause: Throwable? = null) : FileManagerException(message, cause)

class FileManager(private val context: Context) {

    companion object {
        private const val TAG = "FileManager"
        private const val TEMP_FILE_PREFIX = "session_"
        private const val TEMP_FILE_SUFFIX = "_temp.json"
        private const val JSON_FILE_EXTENSION = ".json"
        private const val CONFIG_FILE_NAME = "research_config.json"
        private const val EXTERNAL_CONFIG_FOLDER = "StroopApp"
        private const val PREFS_PARTICIPANT = "participant_prefs"
        private const val PREFS_SETTINGS = "app_settings"
        private const val KEY_EXPORT_FOLDER = "export_folder_path"
        // Version for future format migrations
        private const val CURRENT_FORMAT_VERSION = "1.0"
        // Add these constants to your existing companion object in FileManager.kt:
        private const val PREF_CONSOLE_LOGGING_ENABLED = "console_logging_enabled"
        private const val PREF_FILE_LOGGING_ENABLED = "file_logging_enabled"
        private const val PREF_LOG_FILE_FOLDER = "log_file_folder"
    }

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()

    // For kotlinx.serialization (used by MasterConfigLoader)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val internalStorageDir = File(context.filesDir, "sessions")
    private val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    private val externalConfigDir = File(documentsDir.absolutePath, EXTERNAL_CONFIG_FOLDER)

    init {
        // Ensure internal storage directory exists
        if (!internalStorageDir.exists()) {
            internalStorageDir.mkdirs()
        }

        // Ensure external config directory exists
        if (!externalConfigDir.exists()) {
            externalConfigDir.mkdirs()
        }
    }

    // ============================================================================
    // TASK ITERATION MANAGEMENT (NEW)
    // ============================================================================

    /**
     * Get the next iteration counter for a specific task in a session
     * @param sessionFile Current session file
     * @param taskNumber Task number to get next iteration for
     * @return Next iteration counter (0 for first attempt, 1 for second, etc.)
     */
    fun getNextIterationCounter(sessionFile: File, taskNumber: Int): Int {
        return try {
            if (!sessionFile.exists()) {
                Log.d(TAG, "Session file doesn't exist yet, returning iteration 0")
                return 0
            }

            val sessionData = readSessionData(sessionFile)

            // Find all existing iterations of this task
            val existingIterations = sessionData.tasks.filter { it.task_number == taskNumber }

            if (existingIterations.isEmpty()) {
                Log.d(TAG, "No existing iterations for task $taskNumber, returning 0")
                0
            } else {
                // Find the highest iteration counter and add 1
                val maxCounter = existingIterations.maxOfOrNull { it.iteration_counter } ?: -1
                val nextCounter = maxCounter + 1
                Log.d(TAG, "Task $taskNumber has ${existingIterations.size} existing iterations, next counter: $nextCounter")
                nextCounter
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting next iteration counter for task $taskNumber", e)
            // Fallback to 0 if we can't determine the count
            0
        }
    }

    /**
     * Add a new task iteration to the session
     * @param sessionFile Session file to update
     * @param taskExport New task iteration data
     */
    fun addTaskIteration(sessionFile: File, taskExport: TaskExport) {
        try {
            val sessionData = if (sessionFile.exists()) {
                readSessionData(sessionFile)
            } else {
                // Create new session structure if file doesn't exist
                createEmptySessionData()
            }

            // Add new task iteration to the list
            val updatedTasks = sessionData.tasks + taskExport
            val updatedSession = sessionData.copy(tasks = updatedTasks)

            // Write back to file
            updateSessionFile(sessionFile, updatedSession)

            Log.d(TAG, "Added task iteration: ${taskExport.task_number} (iteration ${taskExport.iteration_counter})")
            Log.d(TAG, "Total task entries in session: ${updatedTasks.size}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to add task iteration", e)
            throw SessionFileException("Failed to add task iteration: ${e.message}")
        }
    }

    /**
     * Update an existing task iteration (for ASQ responses or result revisions)
     * @param sessionFile Session file to update
     * @param taskNumber Task number to update
     * @param iterationCounter Specific iteration to update
     * @param updatedTaskExport Updated task data
     */
    fun updateTaskIteration(
        sessionFile: File,
        taskNumber: Int,
        iterationCounter: Int,
        updatedTaskExport: TaskExport
    ) {
        try {
            val sessionData = readSessionData(sessionFile)

            // Find and replace the specific iteration
            val updatedTasks = sessionData.tasks.map { task ->
                if (task.task_number == taskNumber && task.iteration_counter == iterationCounter) {
                    updatedTaskExport
                } else {
                    task
                }
            }

            val updatedSession = sessionData.copy(tasks = updatedTasks)
            updateSessionFile(sessionFile, updatedSession)

            Log.d(TAG, "Updated task iteration: $taskNumber (iteration $iterationCounter)")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update task iteration", e)
            throw SessionFileException("Failed to update task iteration: ${e.message}")
        }
    }

    /**
     * Get all iterations of a specific task
     * @param sessionFile Session file to read from
     * @param taskNumber Task number to get iterations for
     * @return List of all iterations for this task, ordered by iteration_counter
     */
    fun getTaskIterations(sessionFile: File, taskNumber: Int): List<TaskExport> {
        return try {
            val sessionData = readSessionData(sessionFile)
            sessionData.tasks
                .filter { it.task_number == taskNumber }
                .sortedBy { it.iteration_counter }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get task iterations for $taskNumber", e)
            emptyList()
        }
    }

    /**
     * Get the latest (highest iteration counter) attempt for a specific task
     * @param sessionFile Session file to read from
     * @param taskNumber Task number to get latest iteration for
     * @return Latest task iteration, or null if task hasn't been attempted
     */
    fun getLatestTaskIteration(sessionFile: File, taskNumber: Int): TaskExport? {
        return try {
            getTaskIterations(sessionFile, taskNumber).maxByOrNull { it.iteration_counter }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get latest task iteration for $taskNumber", e)
            null
        }
    }

    /**
     * Remove all iterations of a specific task (for complete task removal)
     * @param sessionFile Session file to update
     * @param taskNumber Task number to remove all iterations for
     */
    fun removeAllTaskIterations(sessionFile: File, taskNumber: Int) {
        try {
            val sessionData = readSessionData(sessionFile)

            // Filter out all iterations of this task
            val updatedTasks = sessionData.tasks.filter { it.task_number != taskNumber }
            val updatedSession = sessionData.copy(tasks = updatedTasks)

            updateSessionFile(sessionFile, updatedSession)

            Log.d(TAG, "Removed all iterations for task: $taskNumber")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove task iterations for $taskNumber", e)
            throw SessionFileException("Failed to remove task iterations: ${e.message}")
        }
    }

    /**
     * Get iteration statistics for a task
     * @param sessionFile Session file to analyze
     * @param taskNumber Task number to analyze
     * @return Iteration statistics
     */
    fun getTaskIterationStats(sessionFile: File, taskNumber: Int): TaskIterationStats {
        return try {
            val iterations = getTaskIterations(sessionFile, taskNumber)

            TaskIterationStats(
                taskNumber = taskNumber,
                totalIterations = iterations.size,
                successfulIterations = iterations.count { it.end_condition == "Success" },
                failedIterations = iterations.count { it.end_condition == "Failed" },
                timedOutIterations = iterations.count { it.end_condition == "Timed Out" },
                givenUpIterations = iterations.count { it.end_condition == "Given up" },
                latestIteration = iterations.maxByOrNull { it.iteration_counter }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get iteration stats for $taskNumber", e)
            TaskIterationStats(taskNumber, 0, 0, 0, 0, 0, null)
        }
    }

    /**
     * Create empty session data structure
     */
    private fun createEmptySessionData(): SessionExport {
        val currentTime = Instant.now().atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_INSTANT)

        return SessionExport(
            session_info = SessionInfo(
                participant_name = "",
                participant_number = "",
                participant_age = 0,
                car_model = "",
                ended = 0,
                discarded = 0,
                session_start_time = currentTime,
                session_end_time = null
            ),
            tasks = emptyList()
        )
    }

    // ============================================================================
    // SETTINGS MANAGEMENT
    // ============================================================================

    /**
     * Get the current export folder path
     * @return Current export folder path, or default if not set
     */
    fun getExportFolder(): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            val savedPath = prefs.getString(KEY_EXPORT_FOLDER, null)

            if (savedPath.isNullOrEmpty()) {
                // Return default path
                getDefaultExportFolder()
            } else {
                savedPath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get export folder setting", e)
            getDefaultExportFolder()
        }
    }

    /**
     * Set the export folder path
     * @param folderPath New export folder path
     */
    fun setExportFolder(folderPath: String) {
        try {
            context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_EXPORT_FOLDER, folderPath)
                .apply()

            Log.d(TAG, "Export folder setting saved: $folderPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save export folder setting", e)
            throw SessionFileException("Failed to save export folder setting: ${e.message}")
        }
    }

    /**
     * Get the default export folder path
     * @return Default export folder path
     */
    fun getDefaultExportFolder(): String {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(documentsDir, "StroopApp_Sessions").absolutePath
    }

    /**
     * Reset export folder to default
     */
    fun resetExportFolderToDefault() {
        setExportFolder(getDefaultExportFolder())
        Log.d(TAG, "Export folder reset to default")
    }

    /**
     * Clear all app settings (for debugging/reset purposes)
     */
    fun clearAllSettings() {
        try {
            context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()

            Log.d(TAG, "All app settings cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear app settings", e)
        }
    }

    /**
     * Get all current settings as a map (for debugging/export)
     */
    fun getAllSettings(): Map<String, String> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            mapOf(
                "export_folder" to getExportFolder(),
                "default_export_folder" to getDefaultExportFolder()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get all settings", e)
            emptyMap()
        }
    }

    // ============================================================================
    // CONFIGURATION FILE OPERATIONS (replacing MasterConfigLoader functionality)
    // ============================================================================

    /**
     * Loads configuration with priority: external storage -> assets
     * Replicates MasterConfigLoader.loadConfig() functionality
     */
    suspend fun loadConfiguration(): ConfigLoadResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: Try external storage first, then fallback to assets
            val configSource = loadConfigContent()

            val jsonContent = configSource.content
                ?: return@withContext ConfigLoadResult.Error(
                    ConfigError.FileNotFound(
                        "Configuration file '$CONFIG_FILE_NAME' not found in assets folder or external storage."
                    )
                )

            // Step 2: Parse JSON using kotlinx.serialization (same as MasterConfigLoader)
            val stroopConfig = parseJsonConfig(jsonContent)
                ?: return@withContext ConfigLoadResult.Error(
                    ConfigError.InvalidJson(
                        "Configuration file contains invalid JSON format. Source: ${configSource.source}"
                    )
                )

            // Step 3: Validate structure and content
            when (val validationResult = stroopConfig.validate()) {
                is ValidationResult.Success -> {
                    ConfigLoadResult.Success(RuntimeConfig(baseConfig = stroopConfig), configSource.source)
                }
                is ValidationResult.Error -> {
                    ConfigLoadResult.Error(
                        ConfigError.ValidationFailed("${validationResult.message} Source: ${configSource.source}")
                    )
                }
            }

        } catch (e: Exception) {
            ConfigLoadResult.Error(
                ConfigError.UnexpectedError("Unexpected error loading configuration: ${e.message}")
            )
        }
    }

    /**
     * Load configuration content from external storage or assets
     * Replicates MasterConfigLoader.loadConfigContent()
     */
    private fun loadConfigContent(): ConfigSource {
        // Try external storage first
        val externalContent = loadConfigFileFromExternal()
        if (externalContent != null) {
            return ConfigSource(externalContent, "External Storage: ${getExternalConfigPath()}")
        }

        // Fallback to assets
        val assetsContent = loadConfigFileFromAssets()
        return ConfigSource(assetsContent, "App Assets (default)")
    }

    /**
     * Loads raw JSON content from external storage
     * Replicates MasterConfigLoader.loadConfigFileFromExternal()
     */
    private fun loadConfigFileFromExternal(): String? {
        return try {
            val configFile = File(getExternalConfigPath())
            if (configFile.exists()) {
                configFile.readText(Charset.forName("UTF-8"))
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read external config", e)
            null
        }
    }

    /**
     * Loads raw JSON content from assets folder
     * Replicates MasterConfigLoader.loadConfigFileFromAssets()
     */
    private fun loadConfigFileFromAssets(): String? {
        return try {
            context.assets.open(CONFIG_FILE_NAME).use { inputStream ->
                inputStream.bufferedReader().use { reader ->
                    reader.readText()
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read config from assets", e)
            null
        }
    }

    /**
     * Copy default configuration from assets to external storage
     * Replicates MasterConfigLoader.copyDefaultConfigToExternal()
     */
    suspend fun copyDefaultConfigToExternal(): Boolean = withContext(Dispatchers.IO) {
        try {
            externalConfigDir.mkdirs()
            val configFile = File(externalConfigDir, CONFIG_FILE_NAME)

            context.assets.open(CONFIG_FILE_NAME).use { input ->
                configFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            Log.i(TAG, "Default config copied to external storage")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy default config to external storage", e)
            false
        }
    }

    /**
     * Get full path to external config file
     * Replicates MasterConfigLoader.getExternalConfigPath()
     */
    fun getExternalConfigPath(): String {
        return File(externalConfigDir, CONFIG_FILE_NAME).absolutePath
    }

    /**
     * Check if external config file exists
     * Replicates MasterConfigLoader.hasExternalConfig()
     */
    fun hasExternalConfig(): Boolean {
        val configFile = File(getExternalConfigPath())
        return configFile.exists()
    }

    /**
     * Get external config directory path
     * Replicates MasterConfigLoader.getExternalConfigDirectory()
     */
    fun getExternalConfigDirectory(): String {
        return externalConfigDir.absolutePath
    }

    /**
     * Parse JSON content into StroopConfig using kotlinx.serialization
     * Replicates MasterConfigLoader.parseJsonConfig()
     */
    private fun parseJsonConfig(jsonContent: String): StroopConfig? {
        return try {
            // First check if JSON has required top-level sections
            val jsonElement = json.parseToJsonElement(jsonContent)
            val jsonObject = jsonElement.jsonObject

            // Check for required sections
            val requiredSections = listOf("stroop_colors", "tasks", "task_lists", "timing")
            requiredSections.forEach { section ->
                if (!jsonObject.containsKey(section)) {
                    throw kotlinx.serialization.SerializationException("Missing required section: $section")
                }
            }

            // Parse the full configuration
            json.decodeFromString<StroopConfig>(jsonContent)

        } catch (e: SerializationException) {
            Log.e(TAG, "JSON parsing error", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected parsing error", e)
            null
        }
    }

    /**
     * Validates configuration structure
     * Replicates MasterConfigLoader.validateConfigStructure()
     */
    fun validateConfigStructure(config: StroopConfig): StructureValidationResult {
        // Check stroop colors
        if (config.stroopColors.isEmpty()) {
            return StructureValidationResult.MissingSection("stroop_colors")
        }

        if (config.stroopColors.size < 2) {
            return StructureValidationResult.InvalidColors(
                "Invalid color configuration. At least 2 colors required and all must be valid hex codes."
            )
        }

        // Validate hex color format
        config.stroopColors.forEach { (colorName, hexValue) ->
            if (!isValidHexColor(hexValue)) {
                return StructureValidationResult.InvalidColors(
                    "Invalid hex color '$hexValue' for color '$colorName'. Must be valid hex format (e.g., #FF0000)."
                )
            }
        }

        // Check tasks
        if (config.tasks.isEmpty()) {
            return StructureValidationResult.InvalidTasks(
                "Invalid task configuration. All tasks must have positive timeout values."
            )
        }

        config.tasks.forEach { (taskId, taskConfig) ->
            if (taskConfig.timeoutSeconds <= 0) {
                return StructureValidationResult.InvalidTasks(
                    "Task '$taskId' has invalid timeout: ${taskConfig.timeoutSeconds}. Must be positive."
                )
            }
        }

        // Check task lists
        if (config.taskLists.isEmpty()) {
            return StructureValidationResult.MissingSection("task_lists")
        }

        config.taskLists.forEach { (listId, taskList) ->
            val taskIds = taskList.getTaskIds()

            if (taskIds.isEmpty()) {
                return StructureValidationResult.InvalidTaskSequence(
                    listId, "", "Task sequence '$listId' is empty or contains no valid tasks."
                )
            }

            taskIds.forEach { taskId ->
                if (taskId !in config.tasks.keys) {
                    return StructureValidationResult.InvalidTaskSequence(
                        listId, taskId, "Invalid task sequence '$listId'. Task ID '$taskId' does not exist in tasks configuration."
                    )
                }
            }
        }

        // Check timing
        val timing = config.timing
        if (timing.stroopDisplayDuration <= 0 || timing.minInterval <= 0 ||
            timing.maxInterval <= 0 || timing.countdownDuration <= 0) {
            return StructureValidationResult.InvalidTiming(
                "Invalid timing configuration. All timing values must be positive numbers."
            )
        }

        if (timing.minInterval > timing.maxInterval) {
            return StructureValidationResult.InvalidTiming(
                "Invalid timing configuration. Minimum interval (${timing.minInterval}) cannot be greater than maximum interval (${timing.maxInterval})."
            )
        }

        return StructureValidationResult.Valid
    }

    /**
     * Helper method to validate hex color format
     */
    private fun isValidHexColor(hexValue: String): Boolean {
        return try {
            val normalizedHex = if (hexValue.startsWith("#")) hexValue else "#$hexValue"
            normalizedHex.matches(Regex("^#[0-9A-Fa-f]{6}$"))
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get user-friendly error messages
     * Replicates MasterConfigLoader.getErrorMessage()
     */
    fun getErrorMessage(error: ConfigError): String {
        return when (error) {
            is ConfigError.FileNotFound -> error.message
            is ConfigError.InvalidJson -> error.message
            is ConfigError.ValidationFailed -> error.message
            is ConfigError.UnexpectedError -> error.message
        }
    }

    // ============================================================================
    // SHARED PREFERENCES OPERATIONS (replacing ParticipantInfoActivity functionality)
    // ============================================================================

    /**
     * Save participant form data for convenience
     * Replicates ParticipantInfoActivity.saveFormData()
     */
    fun saveParticipantFormData(name: String, id: String, age: String) {
        try {
            context.getSharedPreferences(PREFS_PARTICIPANT, Context.MODE_PRIVATE).edit().apply {
                putString("last_name", name)
                putString("last_id", id)
                putString("last_age", age)
                apply()
            }
            Log.d(TAG, "Participant form data saved")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save participant form data", e)
        }
    }

    /**
     * Load existing participant form data
     * Replicates ParticipantInfoActivity.loadExistingData()
     */
    fun loadParticipantFormData(): ParticipantFormData {
        return try {
            val prefs = context.getSharedPreferences(PREFS_PARTICIPANT, Context.MODE_PRIVATE)
            ParticipantFormData(
                name = prefs.getString("last_name", "") ?: "",
                id = prefs.getString("last_id", "") ?: "",
                age = prefs.getString("last_age", "") ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load participant form data", e)
            ParticipantFormData("", "", "")
        }
    }

    /**
     * Clear participant form data
     */
    fun clearParticipantFormData() {
        try {
            context.getSharedPreferences(PREFS_PARTICIPANT, Context.MODE_PRIVATE).edit().clear().apply()
            Log.d(TAG, "Participant form data cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear participant form data", e)
        }
    }

    // ============================================================================
    // SESSION FILE OPERATIONS
    // ============================================================================

    /**
     * Creates a new session file in internal storage
     * @param sessionId Unique session identifier
     * @param initialData Initial session data to write
     * @return File object for the created session file
     */
    fun createSessionFile(sessionId: String, initialData: SessionExport): File {
        val fileName = "$TEMP_FILE_PREFIX${sessionId}$TEMP_FILE_SUFFIX"
        val sessionFile = File(internalStorageDir, fileName)

        try {
            writeSessionData(sessionFile, initialData)
            Log.i(TAG, "Created new session file: ${sessionFile.absolutePath}")
            return sessionFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session file", e)
            throw SessionFileException("Failed to create session file: ${e.message}")
        }
    }

    /**
     * Updates an existing session file with new data
     * @param sessionFile The session file to update
     * @param sessionData Updated session data
     */
    fun updateSessionFile(sessionFile: File, sessionData: SessionExport) {
        try {
            writeSessionData(sessionFile, sessionData)
            Log.d(TAG, "Updated session file: ${sessionFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update session file: ${sessionFile.name}", e)
            throw SessionFileException("Failed to update session file: ${e.message}")
        }
    }

    /**
     * Finalizes a session by moving it to the user-selected folder
     * @param sessionFile Current temp session file
     * @param sessionData Final session data (with ended/discarded flags)
     * @param destinationFolder User-selected folder
     * @return Final file in user folder
     */
    fun finalizeSession(
        sessionFile: File,
        sessionData: SessionExport,
        destinationFolder: File
    ): File {
        try {
            // Update with final data
            writeSessionData(sessionFile, sessionData)
            Log.d(TAG, "Updated session file with final data: ${sessionFile.absolutePath}")

            // Generate final filename
            val finalFileName = generateFinalFileName(sessionData.session_info)
            val finalFile = File(destinationFolder, finalFileName)
            Log.d(TAG, "Target final file: ${finalFile.absolutePath}")

            // Ensure destination directory exists
            if (!destinationFolder.exists()) {
                val created = destinationFolder.mkdirs()
                Log.d(TAG, "Destination directory created: $created at ${destinationFolder.absolutePath}")
            } else {
                Log.d(TAG, "Destination directory already exists: ${destinationFolder.absolutePath}")
            }

            // Check source file exists
            Log.d(TAG, "Source file exists: ${sessionFile.exists()}, size: ${sessionFile.length()}")

            // Copy to final location
            sessionFile.copyTo(finalFile, overwrite = true)
            Log.d(TAG, "File copied successfully")

            // Verify final file
            Log.d(TAG, "Final file exists: ${finalFile.exists()}, size: ${finalFile.length()}")
            Log.d(TAG, "Final file path: ${finalFile.absolutePath}")
            Log.d(TAG, "Final file parent exists: ${finalFile.parentFile?.exists()}")

            // Clean up temp file
            if (sessionFile.delete()) {
                Log.i(TAG, "Session finalized: ${finalFile.absolutePath}")
            } else {
                Log.w(TAG, "Could not delete temp file: ${sessionFile.absolutePath}")
            }

            return finalFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize session", e)
            throw SessionFileException("Failed to finalize session: ${e.message}")
        }
    }

    /**
     * Check which tasks in a list have been completed (have latest iteration with end condition)
     * @param sessionFile Session file to check
     * @param taskNumbers List of task numbers to check
     * @return Map of taskNumber to completion status
     */
    fun getTaskListCompletionStatus(sessionFile: File, taskNumbers: List<Int>): Map<Int, TaskCompletionStatus> {
        return try {
            taskNumbers.associateWith { taskNumber ->
                val latestIteration = getLatestTaskIteration(sessionFile, taskNumber)
                when {
                    latestIteration == null -> TaskCompletionStatus.NotStarted
                    latestIteration.end_condition.isBlank() -> TaskCompletionStatus.InProgress
                    latestIteration.end_condition == "Success" -> TaskCompletionStatus.Successful
                    else -> TaskCompletionStatus.CompletedOther(latestIteration.end_condition)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get task completion status", e)
            taskNumbers.associateWith { TaskCompletionStatus.NotStarted }
        }
    }

    sealed class TaskCompletionStatus {
        object NotStarted : TaskCompletionStatus()
        object InProgress : TaskCompletionStatus()  // Has iterations but no end condition
        object Successful : TaskCompletionStatus()
        data class CompletedOther(val endCondition: String) : TaskCompletionStatus() // Failed, Given up, Timed out
    }

    /**
     * Reads session data from a file
     * @param sessionFile File to read from
     * @return Parsed session data
     */
    fun readSessionData(sessionFile: File): SessionExport {
        try {
            if (!sessionFile.exists()) {
                throw FileNotFoundException("Session file not found: ${sessionFile.absolutePath}")
            }

            val jsonString = sessionFile.readText()
            val sessionData = gson.fromJson(jsonString, SessionExport::class.java)

            Log.d(TAG, "Read session data from: ${sessionFile.name}")
            return sessionData
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read session file: ${sessionFile.name}", e)
            throw SessionFileException("Failed to read session file: ${e.message}")
        }
    }

    // ============================================================================
    // SESSION RECOVERY AND BROWSING
    // ============================================================================

    /**
     * Finds incomplete session files that can be recovered
     * @return List of recoverable session files
     */
    fun findRecoverableSessions(): List<File> {
        return try {
            internalStorageDir.listFiles { file ->
                file.name.startsWith(TEMP_FILE_PREFIX) &&
                        file.name.endsWith(TEMP_FILE_SUFFIX) &&
                        file.length() > 0
            }?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan for recoverable sessions", e)
            emptyList()
        }
    }

    /**
     * Scans a directory for completed session files
     * @param directory Directory to scan (user-selected export folder)
     * @return List of completed session files found
     */
    fun findCompletedSessions(directory: File): List<File> {
        return try {
            if (!directory.exists() || !directory.isDirectory) {
                return emptyList()
            }

            directory.listFiles { file ->
                file.name.endsWith(JSON_FILE_EXTENSION) &&
                        !file.name.contains("_temp") &&
                        file.length() > 0
            }?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan directory for sessions: ${directory.absolutePath}", e)
            emptyList()
        }
    }

    /**
     * Gets summary information about a session file without full parsing
     * @param sessionFile File to inspect
     * @return Basic session info for display in lists
     */
    fun getSessionSummary(sessionFile: File): SessionSummary? {
        return try {
            val sessionData = readSessionData(sessionFile)
            SessionSummary(
                file = sessionFile,
                participantName = sessionData.session_info.participant_name,
                participantNumber = sessionData.session_info.participant_number,
                participantAge = sessionData.session_info.participant_age,
                sessionStartTime = sessionData.session_info.session_start_time,
                sessionEndTime = sessionData.session_info.session_end_time,
                taskCount = sessionData.tasks.size,
                isCompleted = sessionData.session_info.ended == 1,
                isDiscarded = sessionData.session_info.discarded == 1,
                fileSize = sessionFile.length(),
                lastModified = sessionFile.lastModified()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get session summary: ${sessionFile.name}", e)
            null
        }
    }

    /**
     * Reads multiple session files and returns summaries
     * @param sessionFiles List of files to process
     * @return List of session summaries
     */
    fun getSessionSummaries(sessionFiles: List<File>): List<SessionSummary> {
        return sessionFiles.mapNotNull { file ->
            getSessionSummary(file)
        }
    }

    /**
     * Checks if a session file represents an incomplete session
     * @param sessionFile File to check
     * @return True if session is incomplete (not ended or discarded)
     */
    fun isSessionIncomplete(sessionFile: File): Boolean {
        return try {
            val sessionData = readSessionData(sessionFile)
            sessionData.session_info.ended == 0 && sessionData.session_info.discarded == 0
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine session status: ${sessionFile.name}", e)
            false
        }
    }



    /**
     * Deletes a session file (for cleanup or discard operations)
     * @param sessionFile File to delete
     */
    fun deleteSessionFile(sessionFile: File) {
        try {
            if (sessionFile.delete()) {
                Log.i(TAG, "Deleted session file: ${sessionFile.name}")
            } else {
                Log.w(TAG, "Could not delete session file: ${sessionFile.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting session file: ${sessionFile.name}", e)
        }
    }

    // ============================================================================
    // GENERIC JSON OPERATIONS
    // ============================================================================

    /**
     * Reads any JSON file and parses it to specified type
     * @param file JSON file to read
     * @param clazz Class to parse JSON into
     * @return Parsed object of type T
     */
    fun <T> readJsonFile(file: File, clazz: Class<T>): T {
        return try {
            if (!file.exists()) {
                throw FileNotFoundException("File not found: ${file.absolutePath}")
            }

            val jsonString = file.readText()
            val result = gson.fromJson(jsonString, clazz)

            Log.d(TAG, "Read JSON file: ${file.name}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read JSON file: ${file.name}", e)
            throw SessionFileException("Failed to read JSON file: ${e.message}")
        }
    }

    /**
     * Writes any object to JSON file
     * @param file Target file
     * @param data Object to serialize
     */
    fun writeJsonFile(file: File, data: Any) {
        try {
            val jsonString = gson.toJson(data)
            file.writeText(jsonString)

            Log.d(TAG, "Wrote JSON file: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write JSON file: ${file.name}", e)
            throw SessionFileException("Failed to write JSON file: ${e.message}")
        }
    }

    /**
     * Reads JSON from assets folder
     * @param fileName Name of file in assets folder
     * @param clazz Class to parse JSON into
     * @return Parsed object of type T
     */
    fun <T> readJsonFromAssets(fileName: String, clazz: Class<T>): T {
        return try {
            val inputStream = context.assets.open(fileName)
            val jsonString = inputStream.bufferedReader().use { it.readText() }
            val result = gson.fromJson(jsonString, clazz)

            Log.d(TAG, "Read JSON from assets: $fileName")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read JSON from assets: $fileName", e)
            throw SessionFileException("Failed to read JSON from assets: ${e.message}")
        }
    }

    // ============================================================================
    // LEGACY FILE MIGRATION (For future use)
    // ============================================================================

    /**
     * Migrates old session files to new format
     * This method can be extended when file format changes
     */
    fun migrateOldSessionFiles(): Int {
        var migratedCount = 0

        try {
            // Scan for files that might be in old format
            val allFiles = context.filesDir.listFiles { file ->
                file.name.endsWith(".json") && !file.name.contains("_temp")
            } ?: return 0

            for (file in allFiles) {
                try {
                    // Try to read as current format
                    val sessionData = readSessionData(file)

                    // If successful but missing newer fields, migrate
                    if (needsMigration(sessionData)) {
                        val migratedData = performMigration(sessionData)
                        writeSessionData(file, migratedData)
                        migratedCount++
                        Log.i(TAG, "Migrated file: ${file.name}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not migrate file: ${file.name}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during file migration", e)
        }

        return migratedCount
    }

    // ============================================================================
    // FILE SYSTEM UTILITIES
    // ============================================================================

    /**
     * Copies a file from one location to another
     * @param source Source file
     * @param destination Target file
     * @param overwrite Whether to overwrite existing files
     * @return True if copy was successful
     */
    fun copyFile(source: File, destination: File, overwrite: Boolean = false): Boolean {
        return try {
            if (destination.exists() && !overwrite) {
                Log.w(TAG, "Destination exists and overwrite=false: ${destination.absolutePath}")
                return false
            }

            // Ensure destination directory exists
            destination.parentFile?.mkdirs()

            source.copyTo(destination, overwrite)
            Log.d(TAG, "Copied file: ${source.name} -> ${destination.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file: ${source.name}", e)
            false
        }
    }

    /**
     * Gets file information for display purposes
     * @param file File to inspect
     * @return File information object
     */
    fun getFileInfo(file: File): FileInfo? {
        return try {
            if (!file.exists()) return null

            FileInfo(
                name = file.name,
                path = file.absolutePath,
                size = file.length(),
                lastModified = file.lastModified(),
                isDirectory = file.isDirectory,
                canRead = file.canRead(),
                canWrite = file.canWrite()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get file info: ${file.absolutePath}", e)
            null
        }
    }



// Add these methods to your FileManager class:

// ============================================================================
// DEBUG LOGGING SETTINGS
// ============================================================================

    /**
     * Get whether console logging is enabled
     */
    fun isConsoleLoggingEnabled(): Boolean {
        return try {
            context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(PREF_CONSOLE_LOGGING_ENABLED, true) // Default: enabled
        } catch (e: Exception) {
            Log.e(TAG, "Error getting console logging setting", e)
            true // Default on error
        }
    }

    /**
     * Set console logging enabled state
     */
    fun setConsoleLoggingEnabled(enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_CONSOLE_LOGGING_ENABLED, enabled)
                .apply()
            Log.d(TAG, "Console logging setting updated: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving console logging setting", e)
            throw SessionFileException("Failed to save console logging setting: ${e.message}")
        }
    }

    /**
     * Get whether file logging is enabled
     */
    fun isFileLoggingEnabled(): Boolean {
        return try {
            context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(PREF_FILE_LOGGING_ENABLED, false) // Default: disabled
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file logging setting", e)
            false // Default on error
        }
    }

    /**
     * Set file logging enabled state
     */
    fun setFileLoggingEnabled(enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_FILE_LOGGING_ENABLED, enabled)
                .apply()
            Log.d(TAG, "File logging setting updated: $enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving file logging setting", e)
            throw SessionFileException("Failed to save file logging setting: ${e.message}")
        }
    }

    /**
     * Get log file folder path
     */
    fun getLogFileFolder(): String {
        return try {
            val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
            val saved = prefs.getString(PREF_LOG_FILE_FOLDER, null)
            if (saved.isNullOrEmpty()) {
                getDefaultLogFileFolder()
            } else {
                saved
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting log file folder setting", e)
            getDefaultLogFileFolder()
        }
    }

    /**
     * Set log file folder path
     */
    fun setLogFileFolder(folderPath: String) {
        try {
            context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_LOG_FILE_FOLDER, folderPath)
                .apply()
            Log.d(TAG, "Log file folder setting saved: $folderPath")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving log file folder setting", e)
            throw SessionFileException("Failed to save log file folder setting: ${e.message}")
        }
    }

    /**
     * Get the default log file folder path
     */
    fun getDefaultLogFileFolder(): String {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(documentsDir, "StroopApp_Logs").absolutePath
    }

    /**
     * Reset log file folder to default
     */
    fun resetLogFileFolderToDefault() {
        setLogFileFolder(getDefaultLogFileFolder())
        Log.d(TAG, "Log file folder reset to default")
    }

    /**
     * Validates that a folder is writable for session export
     * @param folder Folder to validate
     * @return True if folder is writable
     */
    fun validateExportFolder(folder: File): Boolean {
        return try {
            if (!folder.exists()) {
                folder.mkdirs()
            }

            if (!folder.isDirectory) {
                return false
            }

            // Test write permissions
            val testFile = File(folder, "test_write_${System.currentTimeMillis()}.tmp")
            testFile.writeText("test")
            val canWrite = testFile.exists() && testFile.readText() == "test"
            testFile.delete()

            canWrite
        } catch (e: Exception) {
            Log.e(TAG, "Folder validation failed: ${folder.absolutePath}", e)
            false
        }
    }

    /**
     * Gets the current internal storage usage for session files
     * @return Size in bytes
     */
    fun getStorageUsage(): Long {
        return try {
            internalStorageDir.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Could not calculate storage usage", e)
            0L
        }
    }

    // ============================================================================
    // PRIVATE HELPER METHODS
    // ============================================================================

    private fun writeSessionData(file: File, sessionData: SessionExport) {
        val jsonString = gson.toJson(sessionData)
        file.writeText(jsonString)
    }

    private fun generateFinalFileName(sessionInfo: SessionInfo): String {
        val timestamp = Instant.now().atZone(ZoneId.systemDefault())
        val dateStr = timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))

        // Sanitize participant name for filename
        val sanitizedName = sessionInfo.participant_name
            .replace(Regex("[^a-zA-Z0-9]"), "_")
            .take(20) // Limit length

        return "${sanitizedName}_${sessionInfo.participant_number}_${dateStr}$JSON_FILE_EXTENSION"
    }

    private fun needsMigration(sessionData: SessionExport): Boolean {
        // Add logic here to detect old format versions
        // For now, assume current format is correct
        return false
    }

    private fun performMigration(sessionData: SessionExport): SessionExport {
        // Add migration logic here when format changes
        return sessionData
    }
}

// ============================================================================
// DATA CLASSES FOR JSON EXPORT (UPDATED WITH ITERATION SUPPORT)
// ============================================================================

/**
 * Main session export structure
 */
data class SessionExport(
    val session_info: SessionInfo,
    val tasks: List<TaskExport>  // Each iteration is a separate TaskExport entry
)

/**
 * Session metadata
 */
data class SessionInfo(
    val participant_name: String,
    val participant_number: String,
    val participant_age: Int,
    val car_model: String,  // "old" or "new"
    val ended: Int,         // 0 = not ended, 1 = ended
    val discarded: Int,     // 0 = not discarded, 1 = discarded
    val session_start_time: String,
    val session_end_time: String?
)

/**
 * Individual task iteration export
 * UPDATED: Now includes iteration_counter for tracking multiple attempts
 */
data class TaskExport(
    val task_number: Int,           // Task number as integer (e.g., 1, 2, 3)
    val iteration_counter: Int,     // 0 for first attempt, 1 for second, etc.
    val task_start_time: String,
    val task_metrics: TaskMetrics,
    val asq_responses: AsqResponses,
    val end_condition: String       // "Success", "Failed", "Timed Out", "Given up"
)

/**
 * Task performance metrics for each iteration
 */
data class TaskMetrics(
    val task_label: String,
    val successful_stroops: Int,
    val mean_reaction_time_successful: Double?,
    val incorrect_stroops: Int,
    val mean_reaction_time_incorrect: Double?,
    val mean_reaction_time_overall: Double?,
    val missed_stroops: Int,
    val time_on_task: Long,         // Time in milliseconds
    val countdown_duration: Long    // Countdown duration in milliseconds
)

/**
 * After Scenario Questionnaire responses
 */
data class AsqResponses(
    val asq_ease: Int,  // 1-7 scale, 0 = unanswered
    val asq_time: Int   // 1-7 scale, 0 = unanswered
)

// ============================================================================
// SUPPORTING DATA CLASSES
// ============================================================================

/**
 * Summary information about a session file for display in lists
 */
data class SessionSummary(
    val file: File,
    val participantName: String,
    val participantNumber: String,
    val participantAge: Int,
    val sessionStartTime: String,
    val sessionEndTime: String?,
    val taskCount: Int,              // Total number of task iterations
    val isCompleted: Boolean,
    val isDiscarded: Boolean,
    val fileSize: Long,
    val lastModified: Long
)

/**
 * Statistics about task iterations (NEW)
 */
data class TaskIterationStats(
    val taskNumber: Int,
    val totalIterations: Int,
    val successfulIterations: Int,
    val failedIterations: Int,
    val timedOutIterations: Int,
    val givenUpIterations: Int,
    val latestIteration: TaskExport?
) {
    /**
     * Get success rate as percentage
     */
    fun getSuccessRate(): Double {
        return if (totalIterations > 0) {
            (successfulIterations.toDouble() / totalIterations) * 100.0
        } else {
            0.0
        }
    }

    /**
     * Get summary string for display
     */
    fun getSummary(): String {
        return "Task $taskNumber: $totalIterations attempts, $successfulIterations successful (${String.format("%.1f", getSuccessRate())}%)"
    }
}

/**
 * File system information for any file
 */
data class FileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean
)

/**
 * Research configuration structure (matches research_config.json)
 */
data class ResearchConfig(
    val version: String,
    val stroop_colors: Map<String, String>,
    val display_colors: Map<String, String>,
    val text_only_colors: List<String>,
    val tasks: Map<String, TaskConfig>,
    val task_lists: Map<String, TaskListConfig>,
    val timing_defaults: TimingConfig,
    val asq_questions: Map<String, String>? = null,
    val asq_scale: AsqScaleConfig? = null
)

data class TaskConfig(
    val label: String,
    val text: String,
    val timeout_seconds: Int
)

data class TaskListConfig(
    val label: String,
    val task_sequence: String
)

data class TimingConfig(
    val stroop_display_duration: Long,
    val min_interval: Long,
    val max_interval: Long,
    val countdown_duration: Long
)

data class AsqScaleConfig(
    val min_label: String,
    val max_label: String,
    val min_value: Int,
    val max_value: Int
)