package com.research.projector.network

import android.content.Context
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
 */
object ProjectorNetworkManager {
    private var networkService: ProjectorNetworkService? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Initialize or get the network service
     */
    fun getNetworkService(context: Context): ProjectorNetworkService {
        if (networkService == null) {
            networkService = ProjectorNetworkService(context.applicationContext)
            networkService?.start()
        }
        return networkService!!
    }

    /**
     * Get current session ID
     */
    fun getCurrentSessionId(): String? {
        // For now, return a default session ID if connected
        // In a real implementation, this would come from the handshake
        return if (isConnected()) "test-session" else null
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean {
        return networkService?.connectionState?.value == ProjectorNetworkService.ConnectionState.CONNECTED
    }

    /**
     * Send a Stroop display notification
     */
    fun sendStroopDisplay(word: String, displayColor: String, correctAnswer: String) {
        val sessionId = getCurrentSessionId() ?: return

        scope.launch {
            val message = StroopDisplayMessage(
                sessionId = sessionId,
                word = word,
                displayColor = displayColor,
                correctAnswer = correctAnswer
            )
            networkService?.sendMessage(message)
        }
    }

    /**
     * Send a Stroop hidden notification
     */
    fun sendStroopHidden() {
        val sessionId = getCurrentSessionId() ?: return

        scope.launch {
            val message = StroopHiddenMessage(
                sessionId = sessionId
            )
            networkService?.sendMessage(message)
        }
    }

    /**
     * Stop the network service
     */
    fun stop() {
        networkService?.stop()
        networkService = null
    }
}