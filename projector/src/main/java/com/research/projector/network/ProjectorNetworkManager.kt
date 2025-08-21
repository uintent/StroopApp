package com.research.projector.network

import android.content.Context
import android.util.Log
import com.research.shared.network.NetworkMessage
import com.research.shared.network.StroopDisplayMessage
import com.research.shared.network.StroopHiddenMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Singleton manager for network operations in the Projector app
 * Maintains the network service across activities
 * FIXED: Now properly delegates to the real service instance
 * DEBUG VERSION: Added extensive logging to trace the issue
 */
object ProjectorNetworkManager {
    private var networkService: ProjectorNetworkService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val TAG = "ProjectorNetworkManager"

    /**
     * Initialize or get the network service
     * ENHANCED: Added extensive debugging to trace instance creation
     */
    fun getNetworkService(context: Context): ProjectorNetworkService {
        val appContext = context.applicationContext

        Log.d(TAG, "=== getNetworkService() called ===")
        Log.d(TAG, "Context type: ${context::class.java.simpleName}")
        Log.d(TAG, "App context type: ${appContext::class.java.simpleName}")
        Log.d(TAG, "Current service instance: ${networkService?.hashCode()}")
        Log.d(TAG, "Service is null: ${networkService == null}")

        if (networkService == null) {
            Log.d(TAG, "🔥 CREATING NEW SERVICE INSTANCE")
            networkService = ProjectorNetworkService(appContext)
            Log.d(TAG, "🔥 NEW SERVICE CREATED: ${networkService?.hashCode()}")
            networkService?.start()
            Log.d(TAG, "🔥 SERVICE STARTED, PORT: ${networkService?.getServerPort()}")
        } else {
            Log.d(TAG, "✅ RETURNING EXISTING SERVICE: ${networkService?.hashCode()}")
            Log.d(TAG, "✅ SERVICE PORT: ${networkService?.getServerPort()}")
            Log.d(TAG, "✅ SERVICE CONNECTION STATE: ${networkService?.connectionState?.value}")
        }

        val service = networkService!!
        Log.d(TAG, "=== Returning service instance: ${service.hashCode()} ===")
        return service
    }

    /**
     * Get current session ID
     * ENHANCED: Added extensive debugging to trace session ID retrieval
     */
    fun getCurrentSessionId(): String? {
        Log.d(TAG, "=== getCurrentSessionId() called ===")
        Log.d(TAG, "Service instance: ${networkService?.hashCode()}")
        Log.d(TAG, "Service is null: ${networkService == null}")

        if (networkService == null) {
            Log.e(TAG, "❌ NO SERVICE INSTANCE - this should never happen!")
            return null
        }

        val sessionId = networkService?.getCurrentSessionId()
        Log.d(TAG, "Session ID from service: $sessionId")
        Log.d(TAG, "Service connection state: ${networkService?.connectionState?.value}")
        Log.d(TAG, "Service is connected: ${networkService?.isConnected()}")

        return sessionId
    }

    /**
     * Check if connected
     * ENHANCED: Added debugging to trace connection state
     */
    fun isConnected(): Boolean {
        Log.d(TAG, "=== isConnected() called ===")
        Log.d(TAG, "Service instance: ${networkService?.hashCode()}")

        val connected = networkService?.isConnected() ?: false
        Log.d(TAG, "Connection state: $connected")

        if (networkService != null) {
            Log.d(TAG, "Service connection state: ${networkService?.connectionState?.value}")
            Log.d(TAG, "Service session ID: ${networkService?.getCurrentSessionId()}")
        }

        return connected
    }

    /**
     * Send a Stroop display notification
     * ENHANCED: Added debugging to trace message sending
     */
    fun sendStroopDisplay(word: String, displayColor: String, correctAnswer: String) {
        Log.d(TAG, "=== sendStroopDisplay() called ===")
        Log.d(TAG, "Service instance: ${networkService?.hashCode()}")

        val sessionId = getCurrentSessionId()

        if (sessionId == null) {
            Log.e(TAG, "❌ Cannot send StroopDisplay - no session ID")
            return
        }

        Log.d(TAG, "✅ Sending StroopDisplay with session: $sessionId")

        scope.launch {
            val message = StroopDisplayMessage(
                sessionId = sessionId,
                word = word,
                displayColor = displayColor,
                correctAnswer = correctAnswer
            )

            Log.d(TAG, "Calling networkService.sendMessage() for StroopDisplay")
            networkService?.sendMessage(message)
            Log.d(TAG, "StroopDisplay message sent")
        }
    }

    /**
     * Send a Stroop hidden notification
     * ENHANCED: Added debugging to trace message sending
     */
    fun sendStroopHidden() {
        Log.d(TAG, "=== sendStroopHidden() called ===")
        Log.d(TAG, "Service instance: ${networkService?.hashCode()}")

        val sessionId = getCurrentSessionId()

        if (sessionId == null) {
            Log.e(TAG, "❌ Cannot send StroopHidden - no session ID")
            return
        }

        Log.d(TAG, "✅ Sending StroopHidden with session: $sessionId")

        scope.launch {
            val message = StroopHiddenMessage(
                sessionId = sessionId
            )

            Log.d(TAG, "Calling networkService.sendMessage() for StroopHidden")
            networkService?.sendMessage(message)
            Log.d(TAG, "StroopHidden message sent")
        }
    }

    /**
     * Stop the network service
     * ENHANCED: Added debugging
     */
    fun stop() {
        Log.d(TAG, "=== stop() called ===")
        Log.d(TAG, "Service instance: ${networkService?.hashCode()}")

        networkService?.stop()
        networkService = null

        Log.d(TAG, "Service stopped and nullified")
    }

    /**
     * Debug method to get detailed service info
     */
    fun getDebugInfo(): String {
        return buildString {
            append("ProjectorNetworkManager Debug Info:\n")
            append("- Service instance: ${networkService?.hashCode()}\n")
            append("- Service is null: ${networkService == null}\n")
            if (networkService != null) {
                append("- Connection state: ${networkService?.connectionState?.value}\n")
                append("- Session ID: ${networkService?.getCurrentSessionId()}\n")
                append("- Is connected: ${networkService?.isConnected()}\n")
                append("- Server port: ${networkService?.getServerPort()}\n")
                append("- Service info:\n${networkService?.getServerInfo()}\n")
            }
        }
    }
}