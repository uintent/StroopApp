package com.research.master.utils

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File

/**
 * Manages research session data and JSON file creation
 * Handles participant information, task data, and file persistence
 * According to requirements FR-SM-001, FR-SM-002, FR-SM-003, FR-DP-001, FR-DP-002, FR-DP-003
 */
object SessionManager {

    private val _currentSession = MutableStateFlow<SessionData?>(null)
    val currentSession: StateFlow<SessionData?> = _currentSession.asStateFlow()

    private lateinit var appContext: Context
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Create new session with participant information
     * FR-SM-001: Record participant details
     * FR-SM-002: Record exact start time
     */
    suspend fun createSession(
        participantName: String,
        participantId: String,
        participantAge: Int,
        carModel: String
    ): String {
        val sessionId = generateSessionId()
        val startTime = System.currentTimeMillis()

        val sessionData = SessionData(
            sessionId = sessionId,
            participantName = participantName,
            participantId = participantId,
            participantAge = participantAge,
            carModel = carModel,
            interviewStartTime = startTime,
            interviewEndTime = null,
            tasks = mutableListOf(),
            sessionStatus = "in_progress"
        )

        _currentSession.value = sessionData

        // Save initial session data
        saveSessionToFile(sessionData)

        Log.d("SessionManager", "Created session: $sessionId for participant: $participantName")
        return sessionId
    }

    /**
     * End the current session
     * FR-SM-003: Record exact end time
     */
    suspend fun endSession() {
        val session = _currentSession.value ?: return

        val updatedSession = session.copy(
            interviewEndTime = System.currentTimeMillis(),
            sessionStatus = "completed_normally"
        )

        _currentSession.value = updatedSession
        saveSessionToFile(updatedSession)

        Log.d("SessionManager", "Ended session: ${session.sessionId}")
    }

    /**
     * Add task completion data to current session
     * FR-DP-002: Store task completion data
     */
    suspend fun addTaskData(taskData: TaskCompletionData) {
        val session = _currentSession.value ?: return

        val updatedTasks = session.tasks.toMutableList()
        updatedTasks.add(taskData)

        val updatedSession = session.copy(tasks = updatedTasks)
        _currentSession.value = updatedSession

        // Save incrementally for crash protection (FR-SR-001)
        saveSessionToFile(updatedSession)

        Log.d("SessionManager", "Added task data: ${taskData.taskId} to session: ${session.sessionId}")
    }

    /**
     * Generate filename according to FR-DP-003
     * Format: [CarType][ParticipantName][ParticipantAge][Date][Time].json
     */
    private fun generateFilename(sessionData: SessionData): String {
        val dateFormat = SimpleDateFormat("dd.MM.yyyy_HH:mm", Locale.GERMAN)
        val timestamp = dateFormat.format(Date(sessionData.interviewStartTime))

        // Sanitize participant name (FR-DP-003.1)
        val sanitizedName = sessionData.participantName
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace("ß", "ss")
            .replace("Ä", "Ae")
            .replace("Ö", "Oe")
            .replace("Ü", "Ue")
            .replace(Regex("[^a-zA-Z0-9]"), "_")

        return "${sessionData.carModel}_${sanitizedName}${sessionData.participantAge}_${timestamp}.json"
    }

    /**
     * Save session data to JSON file
     * FR-DP-001, FR-DP-002: JSON file structure and content
     */
    private suspend fun saveSessionToFile(sessionData: SessionData) {
        try {
            val filename = generateFilename(sessionData)
            val jsonString = json.encodeToString(sessionData)

            // Get storage folder from settings or use default
            val storageFolder = getStorageFolder()
            val file = File(storageFolder, filename)

            // Handle duplicate filenames (FR-DP-003.1)
            val finalFile = if (file.exists()) {
                generateUniqueFilename(file)
            } else {
                file
            }

            finalFile.writeText(jsonString)

            Log.d("SessionManager", "Saved session to: ${finalFile.absolutePath}")

        } catch (e: Exception) {
            Log.e("SessionManager", "Failed to save session data", e)
            throw e
        }
    }

    /**
     * Handle duplicate filenames by appending numbers
     */
    private fun generateUniqueFilename(originalFile: File): File {
        val nameWithoutExt = originalFile.nameWithoutExtension
        val extension = originalFile.extension
        val parent = originalFile.parent

        var counter = 1
        var newFile: File

        do {
            val newName = "${nameWithoutExt}_${counter.toString().padStart(3, '0')}.${extension}"
            newFile = File(parent, newName)
            counter++
        } while (newFile.exists() && counter < 1000)

        return newFile
    }

    /**
     * Get storage folder from settings or use default
     * FR-TM-019: User-selected storage folder
     */
    private fun getStorageFolder(): File {
        // TODO: Get from settings when implemented
        // For now, use app's external files directory
        val defaultFolder = File(appContext.getExternalFilesDir(null), "research_data")
        if (!defaultFolder.exists()) {
            defaultFolder.mkdirs()
        }
        return defaultFolder
    }

    /**
     * Generate unique session ID
     */
    private fun generateSessionId(): String {
        val timestamp = System.currentTimeMillis()
        val random = (1000..9999).random()
        return "session_${timestamp}_${random}"
    }

    /**
     * Check for incomplete sessions on startup
     * FR-SR-002: Session recovery
     */
    suspend fun checkForIncompleteSession(): SessionData? {
        try {
            val storageFolder = getStorageFolder()
            val files = storageFolder.listFiles { file ->
                file.extension == "json"
            } ?: return null

            // Find most recent file
            val mostRecentFile = files.maxByOrNull { it.lastModified() } ?: return null

            val sessionData = json.decodeFromString<SessionData>(mostRecentFile.readText())

            // Check if session is incomplete
            if (sessionData.sessionStatus != "completed_normally") {
                Log.d("SessionManager", "Found incomplete session: ${sessionData.sessionId}")
                return sessionData
            }

        } catch (e: Exception) {
            Log.e("SessionManager", "Error checking for incomplete session", e)
        }

        return null
    }

    /**
     * Resume incomplete session
     */
    suspend fun resumeSession(sessionData: SessionData) {
        _currentSession.value = sessionData
        Log.d("SessionManager", "Resumed session: ${sessionData.sessionId}")
    }
}

/**
 * Main session data structure
 * FR-DP-002: Required session data fields
 */
@Serializable
data class SessionData(
    val sessionId: String,
    val participantName: String,
    val participantId: String,
    val participantAge: Int,
    val carModel: String, // "old" or "new"
    val interviewStartTime: Long,
    val interviewEndTime: Long?,
    val tasks: List<TaskCompletionData>,
    val sessionStatus: String // "in_progress", "completed_normally", "interrupted"
)

/**
 * Task completion data structure
 * FR-DP-002: Required task data fields
 */
@Serializable
data class TaskCompletionData(
    val taskId: String,
    val taskLabel: String,
    val timeRequiredMs: Long, // Time from countdown end to task end
    val endCondition: String, // "Failed", "Success", "Partial Success", "Timed Out"
    val stroopErrorRateCorrect: Double, // Percentage correct
    val stroopErrorRateIncorrect: Double, // Percentage incorrect
    val stroopTotalCount: Int,
    val stroopAverageReactionTime: Double, // milliseconds
    val stroopIndividualTimes: List<Double>, // milliseconds
    val asqScores: Map<String, String>, // ASQ question ID to response ("NA" if not applicable)
    val taskEndTime: Long
)