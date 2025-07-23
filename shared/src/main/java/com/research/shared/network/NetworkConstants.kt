package com.research.shared.network

/**
 * Network constants shared between Master and Projector apps
 */
object NetworkConstants {
    const val SERVICE_TYPE = "_drivertest._tcp."
    const val SERVICE_NAME_PREFIX = "DriverDistraction"

    // Timeout values
    const val SOCKET_TIMEOUT_MS = 0  // 0 = no timeout for read operations
    const val SOCKET_CONNECTION_TIMEOUT_MS = 10000  // 10 seconds for initial connection
    const val HEARTBEAT_INTERVAL_MS = 5000L  // 5 seconds
    const val HEARTBEAT_TIMEOUT_MS = 15000L  // 15 seconds - miss 3 heartbeats before timeout

    // Message size limits
    const val MAX_MESSAGE_SIZE = 1024 * 1024  // 1 MB
}