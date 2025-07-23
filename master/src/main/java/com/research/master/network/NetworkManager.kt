package com.research.master.network

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton manager for network operations
 * Maintains the network connection across activities
 */
object NetworkManager {
    private var networkClient: MasterNetworkClient? = null

    /**
     * Initialize or get the network client
     */
    fun getNetworkClient(context: Context): MasterNetworkClient {
        if (networkClient == null) {
            networkClient = MasterNetworkClient(context.applicationContext)
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
     * Disconnect and cleanup
     */
    fun disconnect() {
        networkClient?.disconnect()
    }

    /**
     * Stop discovery
     */
    fun stopDiscovery() {
        networkClient?.stopDiscovery()
    }
}