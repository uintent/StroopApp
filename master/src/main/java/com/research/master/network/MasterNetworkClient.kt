package com.research.master.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.research.shared.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Network client for the Master app that discovers and connects to Projector devices
 */
class MasterNetworkClient(
    private val context: Context,
    private val masterDeviceId: String = "Master-${UUID.randomUUID().toString().take(8)}"
) {
    private val TAG = "MasterNetwork"

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var socket: Socket? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Discovered devices
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Current connection info
    private var currentSessionId: String? = null
    private var connectedDevice: DiscoveredDevice? = null

    // Message channels
    private val incomingMessages = Channel<NetworkMessage>(Channel.BUFFERED)
    private val outgoingMessages = Channel<NetworkMessage>(Channel.BUFFERED)

    // Pending service resolutions
    private val pendingResolutions = ConcurrentHashMap<String, NsdServiceInfo>()

    /**
     * Discovered device information
     */
    data class DiscoveredDevice(
        val serviceName: String,
        val teamId: String,
        val deviceId: String,
        val host: String? = null,
        val port: Int = 0,
        val isResolved: Boolean = false,
        val lastSeen: Long = System.currentTimeMillis()
    ) {
        val displayName: String
            get() = "$teamId - $deviceId"
    }

    /**
     * Connection states
     */
    enum class ConnectionState {
        DISCONNECTED,
        DISCOVERING,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    /**
     * Start device discovery
     */
    fun startDiscovery() {
        Log.d(TAG, "Starting device discovery")
        _connectionState.value = ConnectionState.DISCOVERING

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service found: ${serviceInfo.serviceName}")

                if (serviceInfo.serviceName.startsWith(NetworkConstants.SERVICE_NAME_PREFIX)) {
                    // Parse device info from service name
                    val parts = serviceInfo.serviceName.split("_")
                    if (parts.size >= 3) {
                        val device = DiscoveredDevice(
                            serviceName = serviceInfo.serviceName,
                            teamId = parts[1],
                            deviceId = parts[2]
                        )

                        updateDiscoveredDevices(device)

                        // Resolve the service to get IP and port
                        resolveService(serviceInfo)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                removeDiscoveredDevice(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                _connectionState.value = ConnectionState.ERROR
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(
                NetworkConstants.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start discovery", e)
            _connectionState.value = ConnectionState.ERROR
        }
    }

    /**
     * Stop device discovery
     */
    fun stopDiscovery() {
        discoveryListener?.let { listener ->
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping discovery", e)
            }
        }
        discoveryListener = null
    }

    /**
     * Connect to a discovered device
     */
    suspend fun connectToDevice(device: DiscoveredDevice): Boolean = withContext(Dispatchers.IO) {
        if (!device.isResolved) {
            Log.e(TAG, "Device not resolved: ${device.serviceName}")
            return@withContext false
        }

        _connectionState.value = ConnectionState.CONNECTING

        try {
            // Create socket and connect
            socket = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                soTimeout = NetworkConstants.SOCKET_TIMEOUT_MS
            }

            val address = InetSocketAddress(device.host, device.port)
            socket?.connect(address, NetworkConstants.SOCKET_TIMEOUT_MS)

            Log.d(TAG, "Connected to ${device.host}:${device.port}")

            connectedDevice = device
            _connectionState.value = ConnectionState.CONNECTED

            // Start message handling
            handleConnection()

            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            _connectionState.value = ConnectionState.ERROR
            disconnect()
            return@withContext false
        }
    }

    /**
     * Disconnect from current device
     */
    fun disconnect() {
        scope.coroutineContext.cancelChildren()

        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing socket", e)
        }

        socket = null
        connectedDevice = null
        currentSessionId = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Start a new session
     */
    suspend fun startSession(): String {
        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId

        // Send handshake
        val handshake = HandshakeMessage(
            sessionId = sessionId,
            masterDeviceId = masterDeviceId,
            masterVersion = "1.0.0"
        )

        sendMessage(handshake)

        return sessionId
    }

    /**
     * Send a message to the connected Projector
     */
    suspend fun sendMessage(message: NetworkMessage) {
        if (connectionState.value != ConnectionState.CONNECTED) {
            throw IllegalStateException("Not connected to any device")
        }

        outgoingMessages.send(message)
    }

    /**
     * Receive incoming messages as a Flow
     */
    fun receiveMessages(): Flow<NetworkMessage> = flow {
        for (message in incomingMessages) {
            emit(message)
        }
    }

    /**
     * Resolve a service to get its IP and port
     */
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        // Avoid resolving the same service multiple times
        if (pendingResolutions.containsKey(serviceInfo.serviceName)) {
            return
        }

        pendingResolutions[serviceInfo.serviceName] = serviceInfo

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                Log.d(TAG, "Service resolved: ${resolvedService.serviceName} -> ${resolvedService.host}:${resolvedService.port}")

                pendingResolutions.remove(resolvedService.serviceName)

                // Update device with resolved information
                val parts = resolvedService.serviceName.split("_")
                if (parts.size >= 3) {
                    val device = DiscoveredDevice(
                        serviceName = resolvedService.serviceName,
                        teamId = parts[1],
                        deviceId = parts[2],
                        host = resolvedService.host?.hostAddress,
                        port = resolvedService.port,
                        isResolved = true
                    )

                    updateDiscoveredDevices(device)
                }
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                pendingResolutions.remove(serviceInfo.serviceName)
            }
        }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve service", e)
            pendingResolutions.remove(serviceInfo.serviceName)
        }
    }

    /**
     * Update discovered devices list
     */
    private fun updateDiscoveredDevices(device: DiscoveredDevice) {
        _discoveredDevices.update { currentList ->
            val filtered = currentList.filter { it.serviceName != device.serviceName }
            filtered + device
        }
    }

    /**
     * Remove a device from discovered list
     */
    private fun removeDiscoveredDevice(serviceName: String) {
        _discoveredDevices.update { currentList ->
            currentList.filter { it.serviceName != serviceName }
        }
    }

    /**
     * Handle the socket connection
     */
    private suspend fun handleConnection() = withContext(Dispatchers.IO) {
        val currentSocket = socket ?: return@withContext

        try {
            val input = BufferedInputStream(currentSocket.getInputStream())
            val output = BufferedOutputStream(currentSocket.getOutputStream())

            // Launch coroutines for reading and writing
            val readJob = launch { readMessages(input) }
            val writeJob = launch { writeMessages(output) }
            val heartbeatJob = launch { sendHeartbeats() }

            // Wait for any job to complete
            select<Unit> {
                readJob.onJoin { }
                writeJob.onJoin { }
            }

            // Cancel other jobs
            readJob.cancel()
            writeJob.cancel()
            heartbeatJob.cancel()

        } catch (e: Exception) {
            Log.e(TAG, "Connection error", e)
        } finally {
            disconnect()
        }
    }

    /**
     * Read messages from the socket
     */
    private suspend fun readMessages(input: BufferedInputStream) {
        val lengthBuffer = ByteArray(4)

        while (socket?.isConnected == true) {
            try {
                // Read message length
                if (input.read(lengthBuffer) != 4) {
                    break
                }

                val messageLength = ByteBuffer.wrap(lengthBuffer).int
                if (messageLength <= 0 || messageLength > NetworkConstants.MAX_MESSAGE_SIZE) {
                    Log.e(TAG, "Invalid message length: $messageLength")
                    break
                }

                // Read message data
                val messageBuffer = ByteArray(messageLength)
                var totalRead = 0
                while (totalRead < messageLength) {
                    val read = input.read(messageBuffer, totalRead, messageLength - totalRead)
                    if (read < 0) break
                    totalRead += read
                }

                if (totalRead == messageLength) {
                    val jsonString = String(messageBuffer, Charsets.UTF_8)
                    val message = NetworkProtocol.deserialize(jsonString)

                    // Don't forward heartbeats to the app
                    if (message !is HeartbeatMessage) {
                        incomingMessages.send(message)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error reading message", e)
                break
            }
        }
    }

    /**
     * Write messages to the socket
     */
    private suspend fun writeMessages(output: BufferedOutputStream) {
        while (socket?.isConnected == true) {
            try {
                val message = withTimeoutOrNull(1000) { outgoingMessages.receive() }
                if (message != null) {
                    val frame = NetworkProtocol.createFrame(message)
                    output.write(frame)
                    output.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error writing message", e)
                break
            }
        }
    }

    /**
     * Send periodic heartbeats
     */
    private suspend fun sendHeartbeats() {
        while (connectionState.value == ConnectionState.CONNECTED) {
            delay(NetworkConstants.HEARTBEAT_INTERVAL_MS)

            currentSessionId?.let { sessionId ->
                try {
                    sendMessage(HeartbeatMessage(sessionId = sessionId))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send heartbeat", e)
                }
            }
        }
    }
}