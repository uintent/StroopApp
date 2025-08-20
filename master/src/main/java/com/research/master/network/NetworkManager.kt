package com.research.master.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton manager for network operations
 * Maintains the network connection across activities
 */
object NetworkManager {
    private var networkClient: MasterNetworkClient? = null
    private var currentSessionId: String? = null
    private var preferences: SharedPreferences? = null

    /**
     * Initialize or get the network client
     */
    fun getNetworkClient(context: Context): MasterNetworkClient {
        if (networkClient == null) {
            networkClient = MasterNetworkClient(context.applicationContext)
        }

        // Initialize preferences if not already done
        if (preferences == null) {
            preferences = context.applicationContext.getSharedPreferences("network_prefs", Context.MODE_PRIVATE)
            // Load saved session ID if available
            currentSessionId = preferences?.getString("current_session_id", null)
        }

        return networkClient!!
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean {
        return networkClient?.connectionState?.value == MasterNetworkClient.ConnectionState.CONNECTED
    }

    /**
     * Get current session ID
     */
    fun getCurrentSessionId(): String? = currentSessionId

    /**
     * Set current session ID (called after successful session start)
     * Now persists across app restarts
     */
    fun setCurrentSessionId(sessionId: String) {
        currentSessionId = sessionId
        preferences?.edit()?.putString("current_session_id", sessionId)?.apply()
    }

    /**
     * Clear current session
     */
    fun clearCurrentSession() {
        currentSessionId = null
        preferences?.edit()?.remove("current_session_id")?.apply()
    }

    /**
     * Disconnect and cleanup
     */
    fun disconnect() {
        networkClient?.disconnect()
        clearCurrentSession()
    }

    /**
     * Stop discovery
     */
    fun stopDiscovery() {
        networkClient?.stopDiscovery()
    }

    /**
     * Reset network client (for reconnection scenarios)
     */
    fun reset() {
        networkClient?.disconnect()
        networkClient = null
        clearCurrentSession()
    }
}