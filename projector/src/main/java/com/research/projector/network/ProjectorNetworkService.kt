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
 * Network service for the Projector app that advertises via NSD and accepts connections
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

        // Close sockets
        try {
            clientSocket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing sockets", e)
        }

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
     * Send a message to the connected Master app
     */
    suspend fun sendMessage(message: NetworkMessage) {
        if (connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send message - not connected")
            return
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
     * Start the TCP server and register NSD service
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
     */
    private suspend fun handleClientConnection(socket: Socket) = withContext(Dispatchers.IO) {
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
            _connectionState.value = ConnectionState.ADVERTISING
            currentSessionId = null
            masterDeviceId = null
            clientSocket = null
        }
    }

    /**
     * Read messages from the socket
     */
    private suspend fun readMessages(input: BufferedInputStream) {
        val lengthBuffer = ByteArray(4)

        while (isRunning.get() && clientSocket?.isConnected == true) {
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

                    // Handle special messages
                    when (message) {
                        is HandshakeMessage -> handleHandshake(message)
                        is HeartbeatMessage -> { /* Just update last received time */ }
                        else -> incomingMessages.send(message)
                    }
                }

            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error reading message", e)
                }
                break
            }
        }
    }

    /**
     * Write messages to the socket
     */
    private suspend fun writeMessages(output: BufferedOutputStream) {
        while (isRunning.get() && clientSocket?.isConnected == true) {
            try {
                val message = withTimeoutOrNull(1000) { outgoingMessages.receive() }
                if (message != null) {
                    val frame = NetworkProtocol.createFrame(message)
                    output.write(frame)
                    output.flush()
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error writing message", e)
                }
                break
            }
        }
    }

    /**
     * Handle handshake message
     */
    private suspend fun handleHandshake(message: HandshakeMessage) {
        currentSessionId = message.sessionId
        masterDeviceId = message.masterDeviceId

        // Send handshake response
        val response = HandshakeResponseMessage(
            sessionId = message.sessionId,
            projectorDeviceId = "$teamId-$deviceId",
            projectorVersion = "1.0.0",
            isReady = true
        )

        sendMessage(response)
    }

    /**
     * Send periodic heartbeats
     */
    private suspend fun sendHeartbeats() {
        while (isRunning.get() && connectionState.value == ConnectionState.CONNECTED) {
            delay(NetworkConstants.HEARTBEAT_INTERVAL_MS)

            currentSessionId?.let { sessionId ->
                sendMessage(HeartbeatMessage(sessionId = sessionId))
            }
        }
    }
}