package com.research.projector.network

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
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ENHANCED Network service for the Projector app that advertises via NSD and accepts connections
 * Built on existing proven connection logic with added Master command handling
 */
class ProjectorNetworkService(
    private val context: Context,
    private val teamId: String = "Team1",
    private val deviceId: String = "ProjectorA"
) {
    private val TAG = "ProjectorNetwork"

    private val nsdManager: NsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)

    // Message channels
    private val incomingMessages = Channel<NetworkMessage>(Channel.BUFFERED)
    private val outgoingMessages = Channel<NetworkMessage>(Channel.BUFFERED)

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Current session info
    private var currentSessionId: String? = null
    private var masterDeviceId: String? = null

    /**
     * Connection states
     */
    enum class ConnectionState {
        DISCONNECTED,
        ADVERTISING,
        CONNECTED,
        ERROR
    }

    /**
     * Start the network service
     * ENHANCED: Preserves existing proven connection logic
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Service already running")
            return
        }

        scope.launch {
            try {
                startServer()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start server", e)
                _connectionState.value = ConnectionState.ERROR
                stop()
            }
        }
    }

    /**
     * Stop the network service
     * ENHANCED: Improved cleanup with proper resource management
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        Log.d(TAG, "Stopping network service")

        // Cancel all coroutines
        scope.coroutineContext.cancelChildren()

        // Unregister NSD service
        registrationListener?.let { listener ->
            try {
                nsdManager.unregisterService(listener)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering service", e)
            }
        }
        registrationListener = null

        // Close sockets
        try {
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing sockets", e)
        }

        clientSocket = null
        serverSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
        currentSessionId = null
        masterDeviceId = null
    }

    /**
     * Get the server port number
     */
    fun getServerPort(): Int {
        return serverSocket?.localPort ?: 0
    }

    /**
     * Get current session ID for message sending
     * ENHANCED: Added null safety and logging
     */
    fun getCurrentSessionId(): String? {
        val sessionId = currentSessionId
        if (sessionId == null) {
            Log.w(TAG, "No current session ID available")
        }
        return sessionId
    }

    /**
     * Check if connected to a Master device
     * ENHANCED: More robust connection checking
     */
    fun isConnected(): Boolean {
        return connectionState.value == ConnectionState.CONNECTED &&
                currentSessionId != null &&
                clientSocket?.isConnected == true
    }

    /**
     * Send a message to the connected Master app
     * ENHANCED: Better error handling and logging
     */
    suspend fun sendMessage(message: NetworkMessage) {
        if (connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send message - not connected (state: ${connectionState.value})")
            return
        }

        if (currentSessionId == null) {
            Log.w(TAG, "Cannot send message - no session established")
            return
        }

        try {
            outgoingMessages.send(message)
            Log.d(TAG, "Queued message for sending: ${message.messageType}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue message", e)
        }
    }

    /**
     * Receive incoming messages as a Flow
     * ENHANCED: Added error handling for message flow
     */
    fun receiveMessages(): Flow<NetworkMessage> = flow {
        try {
            for (message in incomingMessages) {
                emit(message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in message flow", e)
        }
    }

    /**
     * Start the TCP server and register NSD service
     * PRESERVED: Existing proven server logic
     */
    private suspend fun startServer() = withContext(Dispatchers.IO) {
        // Create server socket
        serverSocket = ServerSocket(0).apply {
            reuseAddress = true
        }

        val port = serverSocket?.localPort ?: throw IllegalStateException("Failed to create server socket")
        Log.d(TAG, "Server started on port $port")

        // Register NSD service
        registerNsdService(port)

        // Accept connections
        while (isRunning.get()) {
            try {
                Log.d(TAG, "Waiting for connection...")
                val socket = serverSocket?.accept() ?: break

                Log.d(TAG, "Connection accepted from ${socket.inetAddress}")
                handleClientConnection(socket)

            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error accepting connection", e)
                    delay(1000) // Brief delay before retrying
                }
            }
        }
    }

    /**
     * Register the NSD service
     * PRESERVED: Existing NSD registration logic
     */
    private fun registerNsdService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "${NetworkConstants.SERVICE_NAME_PREFIX}_${teamId}_$deviceId"
            serviceType = NetworkConstants.SERVICE_TYPE
            setPort(port)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service registered: ${serviceInfo.serviceName}")
                _connectionState.value = ConnectionState.ADVERTISING
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
                _connectionState.value = ConnectionState.ERROR
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    /**
     * Handle a client connection
     * ENHANCED: Added better error handling while preserving core logic
     */
    private suspend fun handleClientConnection(socket: Socket) = withContext(Dispatchers.IO) {
        // Close existing connection if any
        clientSocket?.close()

        clientSocket = socket
        _connectionState.value = ConnectionState.CONNECTED

        try {
            socket.apply {
                tcpNoDelay = true
                keepAlive = true
                soTimeout = NetworkConstants.SOCKET_TIMEOUT_MS
            }

            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            // Launch coroutines for reading and writing
            val readJob = launch { readMessages(input) }
            val writeJob = launch { writeMessages(output) }
            val heartbeatJob = launch { sendHeartbeats() }

            // Wait for any job to complete (connection closed)
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
            // Connection ended - back to advertising
            _connectionState.value = ConnectionState.ADVERTISING
            currentSessionId = null
            masterDeviceId = null
            clientSocket = null
        }
    }

    /**
     * Read messages from the socket
     * ENHANCED: Better error handling while preserving core message reading logic
     */
    private suspend fun readMessages(input: BufferedInputStream) {
        val lengthBuffer = ByteArray(4)

        while (isRunning.get() && clientSocket?.isConnected == true) {
            try {
                // Read message length
                if (input.read(lengthBuffer) != 4) {
                    Log.d(TAG, "Connection closed by Master")
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

                    Log.d(TAG, "Received message: ${message.messageType}")

                    // Handle special messages
                    when (message) {
                        is HandshakeMessage -> handleHandshake(message)
                        is HeartbeatMessage -> {
                            // Just update last received time - heartbeats keep connection alive
                            Log.v(TAG, "Heartbeat received")
                        }
                        else -> {
                            // Forward all other messages to the application
                            incomingMessages.send(message)
                        }
                    }
                }

            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error reading message", e)
                }
                break
            }
        }

        Log.d(TAG, "Message reading loop ended")
    }

    /**
     * Write messages to the socket
     * ENHANCED: Better error handling and timeout management
     */
    private suspend fun writeMessages(output: BufferedOutputStream) {
        while (isRunning.get() && clientSocket?.isConnected == true) {
            try {
                val message = withTimeoutOrNull(1000) { outgoingMessages.receive() }
                if (message != null) {
                    val frame = NetworkProtocol.createFrame(message)
                    output.write(frame)
                    output.flush()
                    Log.d(TAG, "Sent message: ${message.messageType}")
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error writing message", e)
                }
                break
            }
        }

        Log.d(TAG, "Message writing loop ended")
    }

    /**
     * Handle handshake message
     * ENHANCED: Better session management and response handling
     */
    private suspend fun handleHandshake(message: HandshakeMessage) {
        Log.d(TAG, "Received handshake from Master: ${message.masterDeviceId}")

        currentSessionId = message.sessionId
        masterDeviceId = message.masterDeviceId

        Log.d(TAG, "Session established: $currentSessionId with Master: $masterDeviceId")

        // Send handshake response
        val response = HandshakeResponseMessage(
            sessionId = message.sessionId,
            projectorDeviceId = "$teamId-$deviceId",
            projectorVersion = "1.0.0",
            isReady = true
        )

        try {
            sendMessage(response)
            Log.d(TAG, "Handshake response sent successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send handshake response", e)
        }
    }

    /**
     * Send periodic heartbeats
     * ENHANCED: Better lifecycle management and error handling
     */
    private suspend fun sendHeartbeats() {
        Log.d(TAG, "Starting heartbeat loop")

        while (isRunning.get() && connectionState.value == ConnectionState.CONNECTED) {
            delay(NetworkConstants.HEARTBEAT_INTERVAL_MS)

            currentSessionId?.let { sessionId ->
                try {
                    val heartbeat = HeartbeatMessage(sessionId = sessionId)
                    sendMessage(heartbeat)
                    Log.v(TAG, "Heartbeat sent")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send heartbeat", e)
                    // Don't break the loop - try again next time
                }
            }
        }

        Log.d(TAG, "Heartbeat loop ended")
    }

    /**
     * Get server info for debugging
     * ENHANCED: More comprehensive server information
     */
    fun getServerInfo(): String {
        return buildString {
            append("ProjectorNetworkService Status:\n")
            append("- Running: ${isRunning.get()}\n")
            append("- Port: ${getServerPort()}\n")
            append("- State: ${connectionState.value}\n")
            append("- Session: $currentSessionId\n")
            append("- Master: $masterDeviceId\n")
            append("- Socket Connected: ${clientSocket?.isConnected}")
        }
    }
}