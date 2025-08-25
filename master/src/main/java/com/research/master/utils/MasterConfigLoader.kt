package com.research.master.utils

import android.content.Context
import android.os.Environment
import com.research.shared.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.io.Serializable as JavaSerializable

/**
 * Configuration loader for the Master app.
 * Handles loading, parsing, and validation of research configuration.
 * No longer handles sending config to Projector apps - that's done via task commands.
 */
class MasterConfigLoader(private val context: Context) {
/*
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    private val configFileName = "research_config.json"
    private val externalConfigFolder = "StroopApp"

    /**
     * Loads and validates the configuration file, prioritizing external storage over assets.
     * This is a suspend function to perform file I/O off the main thread.
     *
     * @return ConfigLoadResult with either success (containing RuntimeConfig) or specific error
     */
    suspend fun loadConfig(): ConfigLoadResult = withContext(Dispatchers.IO) {
        try {
            // Step 1: Try to load from external storage first, then fallback to assets
            val configSource = loadConfigContent()

            val jsonContent = configSource.content
                ?: return@withContext ConfigLoadResult.Error(
                    ConfigError.FileNotFound(
                        "Configuration file '$configFileName' not found in assets folder or external storage."
                    )
                )

            // Step 2: Parse JSON
            val stroopConfig = parseJsonConfig(jsonContent)
                ?: return@withContext ConfigLoadResult.Error(
                    ConfigError.InvalidJson(
                        "Configuration file contains invalid JSON format. Source: ${configSource.source}"
                    )
                )

            // Step 3: Validate structure and content
            when (val validationResult = stroopConfig.validate()) {
                is ValidationResult.Success -> {
                    ConfigLoadResult.Success(RuntimeConfig(stroopConfig), configSource.source)
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
     * Loads the raw JSON content from external storage
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
            null
        }
    }

    /**
     * Loads the raw JSON content from the assets folder
     */
    private fun loadConfigFileFromAssets(): String? {
        return try {
            context.assets.open(configFileName).use { inputStream ->
                inputStream.bufferedReader().use { reader ->
                    reader.readText()
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    /**
     * Copy default configuration from assets to external storage
     */
    suspend fun copyDefaultConfigToExternal(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Create directory if it doesn't exist
            val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val configDir = File(documentsDir, externalConfigFolder)
            configDir.mkdirs()

            val configFile = File(configDir, configFileName)

            // Copy from assets to external
            context.assets.open(configFileName).use { input ->
                configFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get the full path to the external config file
     */
    fun getExternalConfigPath(): String {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val configDir = File(documentsDir, externalConfigFolder)
        return File(configDir, configFileName).absolutePath
    }

    /**
     * Check if external config file exists
     */
    fun hasExternalConfig(): Boolean {
        val configFile = File(getExternalConfigPath())
        return configFile.exists()
    }

    /**
     * Get the directory path where config should be placed
     */
    fun getExternalConfigDirectory(): String {
        val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return File(documentsDir, externalConfigFolder).absolutePath
    }

    /**
     * Parses JSON content into StroopConfig object
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
                    throw SerializationException("Missing required section: $section")
                }
            }

            // Parse the full configuration
            json.decodeFromString<StroopConfig>(jsonContent)

        } catch (e: SerializationException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Validates that a configuration object is properly structured
     * This provides additional validation beyond JSON parsing
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
     * Provides user-friendly error messages based on validation results
     */
    fun getErrorMessage(error: ConfigError): String {
        return when (error) {
            is ConfigError.FileNotFound -> error.message
            is ConfigError.InvalidJson -> error.message
            is ConfigError.ValidationFailed -> error.message
            is ConfigError.UnexpectedError -> error.message
        }
    }
}

/**
 * Internal data class to track config source
 */
private data class ConfigSource(
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
sealed class ConfigError(val message: String) {
    data class FileNotFound(private val msg: String) : ConfigError(msg)
    data class InvalidJson(private val msg: String) : ConfigError(msg)
    data class ValidationFailed(private val msg: String) : ConfigError(msg)
    data class UnexpectedError(private val msg: String) : ConfigError(msg)
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
 * Extension function to convert StructureValidationResult to user-friendly string
 */
fun StructureValidationResult.toErrorMessage(): String? {
    return when (this) {
        is StructureValidationResult.Valid -> null
        is StructureValidationResult.MissingSection -> "Missing required configuration section: $sectionName"
        is StructureValidationResult.InvalidColors -> message
        is StructureValidationResult.InvalidTasks -> message
        is StructureValidationResult.InvalidTaskSequence -> message
        is StructureValidationResult.InvalidTiming -> message
    }*/
}