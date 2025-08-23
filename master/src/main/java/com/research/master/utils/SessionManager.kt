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
     * Marks session as ended successfully
     * Also cleans up any other incomplete sessions
     */
    suspend fun endSession() {
        val session = _currentSession.value ?: return

        Log.d("SessionManager", "=== STARTING END SESSION ===")
        Log.d("SessionManager", "Current session ID: ${session.sessionId}")

        val updatedSession = session.copy(
            interviewEndTime = System.currentTimeMillis(),
            sessionStatus = "completed_normally",
            ended = 1 // Mark as ended successfully
        )

        _currentSession.value = updatedSession
        Log.d("SessionManager", "Updated current session in memory: ended=${updatedSession.ended}")

        saveSessionToFile(updatedSession)
        Log.d("SessionManager", "Saved current session to file")

        // Clean up any other incomplete sessions
        Log.d("SessionManager", "Starting cleanup of other incomplete sessions...")
        cleanupOtherIncompleteSessions(session.sessionId, markAsEnded = true)

        Log.d("SessionManager", "=== END SESSION COMPLETE ===")
        Log.d("SessionManager", "Ended session: ${session.sessionId}")
    }

    /**
     * Clear the current session without saving
     * Used when discarding session data
     * Marks session as discarded and saves to file
     * Also cleans up any other incomplete sessions
     */
    suspend fun clearSession() {
        val session = _currentSession.value
        if (session != null) {
            Log.d("SessionManager", "=== STARTING CLEAR SESSION ===")
            Log.d("SessionManager", "Current session ID: ${session.sessionId}")

            try {
                // Mark session as discarded
                val discardedSession = session.copy(
                    discarded = 1,
                    sessionStatus = "discarded"
                    // Note: ended remains 0 since session was not ended successfully
                )

                Log.d("SessionManager", "Updated current session in memory: discarded=${discardedSession.discarded}")

                // Save the updated session with discarded flag
                saveSessionToFile(discardedSession)
                Log.d("SessionManager", "Saved current session to file")

                // Clean up any other incomplete sessions
                Log.d("SessionManager", "Starting cleanup of other incomplete sessions...")
                cleanupOtherIncompleteSessions(session.sessionId, markAsEnded = false)

                // Clear the current session from memory
                _currentSession.value = null

                Log.d("SessionManager", "=== CLEAR SESSION COMPLETE ===")
                Log.d("SessionManager", "Session marked as discarded and saved")

            } catch (e: Exception) {
                Log.e("SessionManager", "Error marking session as discarded", e)
                // Still clear from memory even if saving fails
                _currentSession.value = null
                throw e
            }
        } else {
            Log.d("SessionManager", "No session to clear")
        }
    }

    /**
     * Clean up other incomplete sessions (temporary fix for existing files)
     * @param currentSessionId The session ID to skip (don't modify current session)
     * @param markAsEnded If true, mark as ended=1; if false, mark as discarded=1
     */
    private suspend fun cleanupOtherIncompleteSessions(currentSessionId: String, markAsEnded: Boolean) {
        try {
            Log.d("SessionManager", "--- CLEANUP START ---")
            Log.d("SessionManager", "Current session ID to skip: $currentSessionId")
            Log.d("SessionManager", "Action: ${if (markAsEnded) "mark as ended" else "mark as discarded"}")

            val storageFolder = getStorageFolder()
            Log.d("SessionManager", "Storage folder: ${storageFolder.absolutePath}")

            val files = storageFolder.listFiles { file ->
                file.extension == "json"
            } ?: return

            Log.d("SessionManager", "Found ${files.size} JSON files to check")

            var cleanedCount = 0
            var skippedCount = 0

            files.forEachIndexed { index, file ->
                try {
                    Log.d("SessionManager", "Processing file ${index + 1}/${files.size}: ${file.name}")
                    val sessionData = json.decodeFromString<SessionData>(file.readText())

                    Log.d("SessionManager", "  Session ID: ${sessionData.sessionId}")
                    Log.d("SessionManager", "  Current ended: ${sessionData.ended}, discarded: ${sessionData.discarded}")

                    // Skip the current session and already processed sessions
                    if (sessionData.sessionId == currentSessionId) {
                        Log.d("SessionManager", "  SKIP: Current session")
                        skippedCount++
                        return@forEachIndexed
                    }

                    if (sessionData.ended != 0) {
                        Log.d("SessionManager", "  SKIP: Already ended (${sessionData.ended})")
                        skippedCount++
                        return@forEachIndexed
                    }

                    if (sessionData.discarded != 0) {
                        Log.d("SessionManager", "  SKIP: Already discarded (${sessionData.discarded})")
                        skippedCount++
                        return@forEachIndexed
                    }

                    // This is an incomplete session that needs cleanup
                    Log.d("SessionManager", "  CLEANUP NEEDED: Session ${sessionData.sessionId}")

                    val cleanedSession = if (markAsEnded) {
                        sessionData.copy(
                            ended = 1,
                            sessionStatus = "completed_automatically_cleanup",
                            interviewEndTime = System.currentTimeMillis()
                        )
                    } else {
                        sessionData.copy(
                            discarded = 1,
                            sessionStatus = "discarded_automatically_cleanup"
                        )
                    }

                    Log.d("SessionManager", "  Updated session: ended=${cleanedSession.ended}, discarded=${cleanedSession.discarded}")

                    // Save the cleaned session
                    val jsonString = json.encodeToString(cleanedSession)
                    file.writeText(jsonString)

                    cleanedCount++
                    val action = if (markAsEnded) "ended" else "discarded"
                    Log.d("SessionManager", "  SUCCESS: Marked session ${sessionData.sessionId} as $action and saved to ${file.name}")

                } catch (e: Exception) {
                    Log.e("SessionManager", "  ERROR: Could not cleanup file ${file.name}: ${e.message}", e)
                }
            }

            Log.d("SessionManager", "--- CLEANUP SUMMARY ---")
            Log.d("SessionManager", "Files processed: ${files.size}")
            Log.d("SessionManager", "Sessions cleaned: $cleanedCount")
            Log.d("SessionManager", "Sessions skipped: $skippedCount")

            if (cleanedCount > 0) {
                val action = if (markAsEnded) "ended" else "discarded"
                Log.d("SessionManager", "Cleanup complete: Marked $cleanedCount incomplete sessions as $action")
            } else {
                Log.d("SessionManager", "Cleanup: No other incomplete sessions found")
            }
            Log.d("SessionManager", "--- CLEANUP END ---")

        } catch (e: Exception) {
            Log.e("SessionManager", "Error during cleanup of incomplete sessions", e)
        }
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
            .replace("Ö",  "Oe")
            .replace("Ü", "Ue")
            .replace(Regex("[^a-zA-Z0-9]"), "_")

        return "${sessionData.carModel}_${sanitizedName}${sessionData.participantAge}_${timestamp}.json"
    }

    /**
     * Save session data to JSON file
     * FR-DP-001, FR-DP-002: JSON file structure and content
     * Updates existing file for the same session instead of creating duplicates
     */
    private suspend fun saveSessionToFile(sessionData: SessionData) {
        try {
            val storageFolder = getStorageFolder()

            // First, try to find existing file for this session
            var existingFile: File? = null
            val files = storageFolder.listFiles { file ->
                file.extension == "json"
            }

            // Look for existing file with the same session ID
            files?.forEach { file ->
                try {
                    val existingSessionData = json.decodeFromString<SessionData>(file.readText())
                    if (existingSessionData.sessionId == sessionData.sessionId) {
                        existingFile = file
                        Log.d("SessionManager", "Found existing file for session ${sessionData.sessionId}: ${file.name}")
                        return@forEach
                    }
                } catch (e: Exception) {
                    Log.w("SessionManager", "Could not parse file ${file.name} while looking for existing session: ${e.message}")
                }
            }

            val finalFile = if (existingFile != null) {
                // Update existing file
                Log.d("SessionManager", "Updating existing session file: ${existingFile!!.name}")
                existingFile!!
            } else {
                // Create new file with proper naming
                val filename = generateFilename(sessionData)
                val file = File(storageFolder, filename)

                // Handle duplicate filenames (FR-DP-003.1) only for truly new sessions
                if (file.exists()) {
                    generateUniqueFilename(file)
                } else {
                    file
                }
            }

            // Save the session data
            val jsonString = json.encodeToString(sessionData)
            finalFile.writeText(jsonString)

            Log.d("SessionManager", "Saved session ${sessionData.sessionId} (ended=${sessionData.ended}, discarded=${sessionData.discarded}) to: ${finalFile.absolutePath}")

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
     * Only returns sessions that are explicitly neither ended nor discarded (both flags must be exactly 0)
     */
    suspend fun checkForIncompleteSession(): SessionData? {
        try {
            val storageFolder = getStorageFolder()
            val files = storageFolder.listFiles { file ->
                file.extension == "json"
            } ?: return null

            // Find most recent file that is explicitly not ended and not discarded
            val activeFiles = files.mapNotNull { file ->
                try {
                    val sessionData = json.decodeFromString<SessionData>(file.readText())
                    // Only consider files where both flags are explicitly 0
                    if (sessionData.ended == 0 && sessionData.discarded == 0) {
                        Log.d("SessionManager", "Valid incomplete session found: ${sessionData.sessionId} (ended=${sessionData.ended}, discarded=${sessionData.discarded})")
                        Pair(file, sessionData)
                    } else {
                        val reason = when {
                            sessionData.ended != 0 -> "ended flag = ${sessionData.ended}"
                            sessionData.discarded != 0 -> "discarded flag = ${sessionData.discarded}"
                            else -> "unknown reason"
                        }
                        Log.d("SessionManager", "Ignoring session (${reason}): ${sessionData.sessionId}")
                        null
                    }
                } catch (e: Exception) {
                    Log.w("SessionManager", "Could not parse session file ${file.name}: ${e.message}")
                    null
                }
            }

            // Find most recent active session
            val mostRecentActiveSession = activeFiles
                .maxByOrNull { it.first.lastModified() }
                ?.second

            if (mostRecentActiveSession != null) {
                Log.d("SessionManager", "Found incomplete session: ${mostRecentActiveSession.sessionId}")
                return mostRecentActiveSession
            } else {
                Log.d("SessionManager", "No incomplete sessions found")
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
    val sessionStatus: String, // "in_progress", "completed_normally", "interrupted", "discarded"
    val ended: Int = 0, // 0 = not ended, 1 = ended successfully
    val discarded: Int = 0 // 0 = not discarded, 1 = discarded
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