package com.research.master.network

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.research.master.utils.DebugLogger
import kotlinx.coroutines.flow.StateFlow
import java.lang.ref.WeakReference

/**
 * Singleton manager for network operations
 * Maintains the network connection across activities
 * FIXED: Prevents memory leaks by using WeakReference for context
 */
object NetworkManager {
    private const val TAG = "NetworkManager"

    private var networkClient: MasterNetworkClient? = null
    private var currentSessionId: String? = null
    private var preferences: SharedPreferences? = null

    // FIXED: Use WeakReference to prevent memory leak
    private var contextRef: WeakReference<Context>? = null

    /**
     * Initialize or get the network client
     * FIXED: Store context as WeakReference to prevent memory leaks
     */
    fun getNetworkClient(context: Context): MasterNetworkClient {
        // Store context reference for future use
        contextRef = WeakReference(context.applicationContext)

        if (networkClient == null) {
            networkClient = MasterNetworkClient(context.applicationContext)
            DebugLogger.d(TAG, "Created new MasterNetworkClient")
        }

        // Initialize preferences if not already done
        if (preferences == null) {
            preferences = context.applicationContext.getSharedPreferences("network_prefs", Context.MODE_PRIVATE)
            // Load saved session ID if available
            currentSessionId = preferences?.getString("current_session_id", null)
            DebugLogger.d(TAG, "Initialized preferences, loaded session ID: $currentSessionId")
        }

        return networkClient!!
    }

    /**
     * Check if connected
     */
    fun isConnected(): Boolean {
        val connected = networkClient?.connectionState?.value == MasterNetworkClient.ConnectionState.CONNECTED
        DebugLogger.d(TAG, "Connection status checked: $connected")
        return connected
    }

    /**
     * Get current session ID
     */
    fun getCurrentSessionId(): String? {
        DebugLogger.d(TAG, "Current session ID requested: $currentSessionId")
        return currentSessionId
    }

    /**
     * Set current session ID (called after successful session start)
     * Now persists across app restarts
     * FIXED: Use KTX extension for SharedPreferences.edit
     */
    fun setCurrentSessionId(sessionId: String) {
        currentSessionId = sessionId
        preferences?.edit {
            putString("current_session_id", sessionId)
        }
        DebugLogger.d(TAG, "Session ID set and persisted: $sessionId")
    }

    /**
     * Clear current session
     * FIXED: Use KTX extension for SharedPreferences.edit
     */
    fun clearCurrentSession() {
        val previousSessionId = currentSessionId
        currentSessionId = null
        preferences?.edit {
            remove("current_session_id")
        }
        DebugLogger.d(TAG, "Session cleared (was: $previousSessionId)")
    }

    /**
     * Disconnect and cleanup
     */
    fun disconnect() {
        DebugLogger.d(TAG, "Disconnecting network client")
        networkClient?.disconnect()
        clearCurrentSession()
    }

    /**
     * Stop discovery
     */
    fun stopDiscovery() {
        DebugLogger.d(TAG, "Stopping device discovery")
        networkClient?.stopDiscovery()
    }

    /**
     * Reset network client (for reconnection scenarios)
     * FIXED: Clear context reference to prevent memory leaks
     */
    fun reset() {
        DebugLogger.d(TAG, "Resetting network client")
        networkClient?.disconnect()
        networkClient = null
        contextRef?.clear()
        contextRef = null
        clearCurrentSession()
    }
}