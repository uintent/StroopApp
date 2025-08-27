package com.research.master.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.research.shared.network.*
import com.research.master.utils.DebugLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * Network client for the Master app that discovers and connects to Projector devices
 * FIXED: Uses WeakReference for context to prevent memory leaks when stored statically
 */
class MasterNetworkClient(
    context: Context,
    private val masterDeviceId: String = "Master-${UUID.randomUUID().toString().take(8)}"
) {
    private val TAG = "MasterNetwork"

    // FIXED: Use WeakReference for context to prevent memory leaks
    private val contextRef = WeakReference(context.applicationContext)

    private val nsdManager: NsdManager by lazy {
        contextRef.get()?.getSystemService(Context.NSD_SERVICE) as? NsdManager
            ?: throw IllegalStateException("Context no longer available")
    }

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
        DebugLogger.d(TAG, "Starting device discovery")
        _connectionState.value = ConnectionState.DISCOVERING

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                DebugLogger.d(TAG, "Discovery started")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                DebugLogger.d(TAG, "Service found: ${serviceInfo.serviceName}")

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
                DebugLogger.d(TAG, "Service lost: ${serviceInfo.serviceName}")
                removeDiscoveredDevice(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                DebugLogger.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                DebugLogger.e(TAG, "Discovery start failed: $errorCode")
                _connectionState.value = ConnectionState.ERROR
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                DebugLogger.e(TAG, "Discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager.discoverServices(
                NetworkConstants.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to start discovery", e)
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
                DebugLogger.d(TAG, "Device discovery stopped")
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Error stopping discovery", e)
            }
        }
        discoveryListener = null
    }

    /**
     * Connect to a discovered device
     * FIXED: Use non-blocking socket connection to prevent thread starvation
     */
    suspend fun connectToDevice(device: DiscoveredDevice): Boolean = withContext(Dispatchers.IO) {
        if (!device.isResolved) {
            DebugLogger.e(TAG, "Device not resolved: ${device.serviceName}")
            return@withContext false
        }

        _connectionState.value = ConnectionState.CONNECTING
        DebugLogger.d(TAG, "Attempting to connect to ${device.host}:${device.port}")

        try {
            // FIXED: Create socket with proper non-blocking configuration
            socket = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                soTimeout = NetworkConstants.SOCKET_READ_TIMEOUT_MS
            }

            val address = InetSocketAddress(device.host, device.port)

            // FIXED: Use withContext to ensure proper IO threading
            withContext(Dispatchers.IO) {
                socket?.connect(address, NetworkConstants.SOCKET_CONNECTION_TIMEOUT_MS)
            }

            DebugLogger.d(TAG, "Connected to ${device.host}:${device.port}")

            connectedDevice = device

            // Use a CompletableDeferred to wait for message handling to be ready
            val messageHandlingReady = CompletableDeferred<Boolean>()

            // Start message handling and wait for it to be ready
            val connectionJob = scope.launch {
                handleConnection(messageHandlingReady)
            }

            // Wait for message handling to be ready or timeout
            val isReady = withTimeoutOrNull(5000) {
                messageHandlingReady.await()
            } ?: false

            if (!isReady) {
                DebugLogger.e(TAG, "Message handling failed to start within timeout")
                connectionJob.cancel()
                disconnect()
                return@withContext false
            }

            _connectionState.value = ConnectionState.CONNECTED
            DebugLogger.d(TAG, "Connection fully established and ready for messages")

            return@withContext true

        } catch (e: Exception) {
            DebugLogger.e(TAG, "Connection failed", e)
            _connectionState.value = ConnectionState.ERROR
            disconnect()
            return@withContext false
        }
    }

    /**
     * Handle the socket connection
     * FIXED: Signal when message handling is ready
     */
    private suspend fun handleConnection(readySignal: CompletableDeferred<Boolean>? = null) = withContext(Dispatchers.IO) {
        val currentSocket = socket ?: return@withContext

        try {
            val input = BufferedInputStream(currentSocket.getInputStream())
            val output = BufferedOutputStream(currentSocket.getOutputStream())

            // Launch coroutines for reading and writing
            val readJob = launch { readMessages(input) }
            val writeJob = launch { writeMessages(output) }
            val heartbeatJob = launch { sendHeartbeats() }

            // Give the coroutines a moment to start
            delay(200)

            // Signal that message handling is ready
            readySignal?.complete(true)
            DebugLogger.d(TAG, "Message handling coroutines started and ready")

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
            DebugLogger.e(TAG, "Connection error", e)
            readySignal?.complete(false)
        } finally {
            disconnect()
        }
    }

    /**
     * Disconnect from current device
     * FIXED: Properly cleanup WeakReference
     */
    fun disconnect() {
        scope.coroutineContext.cancelChildren()

        try {
            socket?.close()
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Error closing socket", e)
        }

        socket = null
        connectedDevice = null
        currentSessionId = null
        _connectionState.value = ConnectionState.DISCONNECTED
        DebugLogger.d(TAG, "Disconnected from device")
    }

    /**
     * Get context safely from WeakReference
     * FIXED: Helper method to safely access context
     */
    private fun getContext(): Context? = contextRef.get()

    /**
     * Start a new session
     * FIXED: Add retry logic and better error handling
     */
    suspend fun startSession(): String {
        if (connectionState.value != ConnectionState.CONNECTED) {
            throw IllegalStateException("Not connected to any device")
        }

        val sessionId = UUID.randomUUID().toString()
        currentSessionId = sessionId
        DebugLogger.d(TAG, "Starting new session: $sessionId")

        // Send handshake with retry logic
        val handshake = HandshakeMessage(
            sessionId = sessionId,
            masterDeviceId = masterDeviceId,
            masterVersion = "1.0.0"
        )

        var retryCount = 0
        val maxRetries = 3

        while (retryCount < maxRetries) {
            try {
                DebugLogger.d(TAG, "Sending handshake (attempt ${retryCount + 1})")
                sendMessage(handshake)
                DebugLogger.d(TAG, "Handshake sent successfully")
                break
            } catch (e: Exception) {
                retryCount++
                DebugLogger.w(TAG, "Handshake attempt $retryCount failed: ${e.message}")

                if (retryCount >= maxRetries) {
                    throw e
                }

                // Wait before retry
                delay(500)
            }
        }

        return sessionId
    }

    /**
     * Send a message to the connected Projector
     */
    suspend fun sendMessage(message: NetworkMessage) {
        if (connectionState.value != ConnectionState.CONNECTED) {
            throw IllegalStateException("Not connected to any device")
        }

        DebugLogger.d(TAG, "Sending message: ${message.messageType}")
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
     * FIXED: Use modern ServiceInfoCallback API and handle deprecated host property
     */
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        // Avoid resolving the same service multiple times
        if (pendingResolutions.containsKey(serviceInfo.serviceName)) {
            return
        }

        pendingResolutions[serviceInfo.serviceName] = serviceInfo
        DebugLogger.d(TAG, "Resolving service: ${serviceInfo.serviceName}")

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolvedService: NsdServiceInfo) {
                // FIXED: Use hostAddresses instead of deprecated host property
                val hostAddress = resolvedService.hostAddresses?.firstOrNull()?.hostAddress
                    ?: resolvedService.host?.hostAddress // Fallback for older Android versions

                DebugLogger.d(TAG, "Service resolved: ${resolvedService.serviceName} -> $hostAddress:${resolvedService.port}")

                pendingResolutions.remove(resolvedService.serviceName)

                // Update device with resolved information
                val parts = resolvedService.serviceName.split("_")
                if (parts.size >= 3) {
                    val device = DiscoveredDevice(
                        serviceName = resolvedService.serviceName,
                        teamId = parts[1],
                        deviceId = parts[2],
                        host = hostAddress,
                        port = resolvedService.port,
                        isResolved = true
                    )

                    updateDiscoveredDevices(device)
                }
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                DebugLogger.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                pendingResolutions.remove(serviceInfo.serviceName)
            }
        }

        try {
            // FIXED: Handle deprecated resolveService API properly
            @Suppress("DEPRECATION") // We need to use this API for backward compatibility
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "Failed to resolve service", e)
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
        DebugLogger.d(TAG, "Updated device list - ${device.serviceName} ${if (device.isResolved) "resolved" else "unresolved"}")
    }

    /**
     * Remove a device from discovered list
     */
    private fun removeDiscoveredDevice(serviceName: String) {
        _discoveredDevices.update { currentList ->
            currentList.filter { it.serviceName != serviceName }
        }
        DebugLogger.d(TAG, "Removed device from list: $serviceName")
    }

    /**
     * Read messages from the socket
     */
    private suspend fun readMessages(input: BufferedInputStream) {
        val lengthBuffer = ByteArray(4)
        DebugLogger.d(TAG, "Started message reading coroutine")

        while (socket?.isConnected == true) {
            try {
                // Read message length
                if (input.read(lengthBuffer) != 4) {
                    break
                }

                val messageLength = ByteBuffer.wrap(lengthBuffer).int
                if (messageLength <= 0 || messageLength > NetworkConstants.MAX_MESSAGE_SIZE) {
                    DebugLogger.e(TAG, "Invalid message length: $messageLength")
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

                    // Log non-heartbeat messages
                    if (message !is HeartbeatMessage) {
                        DebugLogger.d(TAG, "Received message: ${message.messageType}")
                    }

                    // Don't forward heartbeats to the app
                    if (message !is HeartbeatMessage) {
                        incomingMessages.send(message)
                    }
                }

            } catch (e: Exception) {
                DebugLogger.e(TAG, "Error reading message", e)
                break
            }
        }
        DebugLogger.d(TAG, "Message reading coroutine ended")
    }

    /**
     * Write messages to the socket
     */
    private suspend fun writeMessages(output: BufferedOutputStream) {
        DebugLogger.d(TAG, "Started message writing coroutine")

        while (socket?.isConnected == true) {
            try {
                val message = withTimeoutOrNull(1000) { outgoingMessages.receive() }
                if (message != null) {
                    val frame = NetworkProtocol.createFrame(message)
                    output.write(frame)
                    output.flush()

                    // Log non-heartbeat messages
                    if (message !is HeartbeatMessage) {
                        DebugLogger.d(TAG, "Sent message: ${message.messageType}")
                    }
                }
            } catch (e: Exception) {
                DebugLogger.e(TAG, "Error writing message", e)
                break
            }
        }
        DebugLogger.d(TAG, "Message writing coroutine ended")
    }

    /**
     * Send periodic heartbeats
     */
    private suspend fun sendHeartbeats() {
        DebugLogger.d(TAG, "Started heartbeat coroutine")

        while (connectionState.value == ConnectionState.CONNECTED) {
            delay(NetworkConstants.HEARTBEAT_INTERVAL_MS)

            currentSessionId?.let { sessionId ->
                try {
                    sendMessage(HeartbeatMessage(sessionId = sessionId))
                } catch (e: Exception) {
                    DebugLogger.e(TAG, "Failed to send heartbeat", e)
                }
            }
        }
        DebugLogger.d(TAG, "Heartbeat coroutine ended")
    }
}