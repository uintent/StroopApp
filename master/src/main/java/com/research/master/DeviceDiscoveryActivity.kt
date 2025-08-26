package com.research.master

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.research.master.databinding.ActivityDeviceDiscoveryBinding
import com.research.master.network.MasterNetworkClient
import com.research.master.network.NetworkManager
import com.research.master.utils.MasterConfigManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.content.Intent
import androidx.appcompat.app.AlertDialog

/**
 * Activity for discovering and connecting to Projector devices
 * Loads configuration on startup and manages device connections
 * UPDATED: Now navigates to MainSessionActivity after successful connection
 */
class DeviceDiscoveryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeviceDiscoveryBinding
    private lateinit var networkClient: MasterNetworkClient
    private lateinit var devicesAdapter: DevicesAdapter

    // Permission launcher for location (required for NSD on some Android versions)
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("MasterNetwork", "Location permission granted")
        } else {
            showPermissionError()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDeviceDiscoveryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        binding.toolbar.title = getString(R.string.device_discovery_title)

        // Initialize configuration manager
        MasterConfigManager.initialize(this)

        // Load configuration on startup
        loadConfiguration()

        // Initialize network client using singleton
        networkClient = NetworkManager.getNetworkClient(this)

        // Set up RecyclerView
        devicesAdapter = DevicesAdapter { device ->
            connectToDevice(device)
        }

        binding.recyclerDevices.apply {
            layoutManager = LinearLayoutManager(this@DeviceDiscoveryActivity)
            adapter = devicesAdapter
        }

        // Set up observers
        setupObservers()

        // Set up click listeners
        setupClickListeners()

        // Load last connection immediately
        loadLastConnection()

        // Check permissions but don't auto-start discovery
        checkPermissions()
    }

    override fun onResume() {
        super.onResume()

        // Update connection state UI
        lifecycleScope.launch {
            networkClient.connectionState.collect { state ->
                updateConnectionState(state)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop discovery when activity is paused to free up resources
        networkClient.stopDiscovery()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MasterNetwork", "Location permission granted")
            } else {
                Log.e("MasterNetwork", "Location permission denied")
            }
        }
    }

    /**
     * Load configuration on startup
     */
    private fun loadConfiguration() {
        lifecycleScope.launch {
            val configLoaded = MasterConfigManager.loadConfiguration()
            if (!configLoaded) {
                showConfigurationError()
            } else {
                Log.d("MasterConfig", "Configuration loaded successfully")
                updateConfigStatus(getString(R.string.device_discovery_config_loaded))
            }
        }
    }

    /**
     * Set up observers for network state and configuration
     */
    private fun setupObservers() {
        // Observe discovered devices
        lifecycleScope.launch {
            networkClient.discoveredDevices.collectLatest { devices ->
                devicesAdapter.updateDevices(devices)

                // Update UI based on device count
                if (devices.isEmpty()) {
                    binding.textNoDevices.visibility = View.VISIBLE
                    binding.recyclerDevices.visibility = View.GONE
                } else {
                    binding.textNoDevices.visibility = View.GONE
                    binding.recyclerDevices.visibility = View.VISIBLE
                }
            }
        }

        // Observe connection state
        lifecycleScope.launch {
            networkClient.connectionState.collectLatest { state ->
                updateConnectionState(state)
            }
        }

        // Observe configuration state
        lifecycleScope.launch {
            MasterConfigManager.configState.collectLatest { configState ->
                when (configState) {
                    is MasterConfigManager.ConfigState.NotLoaded -> {
                        updateConfigStatus("Configuration not loaded")
                    }
                    is MasterConfigManager.ConfigState.Loading -> {
                        updateConfigStatus("Loading configuration...")
                    }
                    is MasterConfigManager.ConfigState.Loaded -> {
                        updateConfigStatus(getString(R.string.device_discovery_config_ready))
                    }
                    is MasterConfigManager.ConfigState.Error -> {
                        updateConfigStatus("Config error: ${configState.message}")
                    }
                }
            }
        }
    }

    /**
     * Set up click listeners for UI elements
     */
    private fun setupClickListeners() {
        // Refresh button
        binding.btnRefresh.setOnClickListener {
            refreshDiscovery()
        }

        // Manual connect button
        binding.btnManualConnect.setOnClickListener {
            attemptManualConnection()
        }

        // Enter key on port field
        binding.etPort.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptManualConnection()
                true
            } else {
                false
            }
        }
    }

    /**
     * Attempt manual connection
     * UPDATED: Now navigates to MainSessionActivity after successful connection
     */
    private fun attemptManualConnection() {
        // First check if configuration is ready
        if (!MasterConfigManager.isConfigurationReady()) {
            showConfigurationNotReadyDialog()
            return
        }

        val ipAddress = binding.etIpAddress.text?.toString()?.trim()
        val portText = binding.etPort.text?.toString()?.trim()

        // Validate input
        if (ipAddress.isNullOrEmpty()) {
            binding.layoutIpAddress.error = getString(R.string.device_discovery_ip_required)
            return
        }

        if (portText.isNullOrEmpty()) {
            binding.layoutPort.error = getString(R.string.device_discovery_port_required)
            return
        }

        // Clear any previous errors
        binding.layoutIpAddress.error = null
        binding.layoutPort.error = null

        // Validate IP address format
        if (!isValidIpAddress(ipAddress)) {
            binding.layoutIpAddress.error = getString(R.string.device_discovery_ip_invalid)
            return
        }

        // Parse port
        val port = try {
            portText.toInt()
        } catch (e: NumberFormatException) {
            binding.layoutPort.error = getString(R.string.device_discovery_port_invalid)
            return
        }

        // Validate port range
        if (port !in 1..65535) {
            binding.layoutPort.error = getString(R.string.device_discovery_port_range_invalid)
            return
        }

        // Hide keyboard
        currentFocus?.let { view ->
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(view.windowToken, 0)
        }

        // Stop discovery to free up resources
        networkClient.stopDiscovery()

        // Create manual device entry
        val manualDevice = MasterNetworkClient.DiscoveredDevice(
            serviceName = getString(R.string.device_discovery_manual_device_format, ipAddress, port),
            teamId = getString(R.string.device_discovery_manual_team_id),
            deviceId = getString(R.string.device_discovery_manual_device_id),
            host = ipAddress,
            port = port,
            isResolved = true
        )

        Log.d("MasterNetwork", "Attempting manual connection to $ipAddress:$port")

        // Attempt connection
        lifecycleScope.launch {
            try {
                val success = networkClient.connectToDevice(manualDevice)
                if (success) {
                    Log.d("MasterNetwork", "Manual connection successful - starting session")

                    // ✅ CRITICAL FIX: Start session to send handshake
                    val sessionId = networkClient.startSession()
                    NetworkManager.setCurrentSessionId(sessionId)

                    Log.d("MasterNetwork", "Session started: $sessionId")

                    // Save last successful connection
                    saveLastConnection(ipAddress, port)

                    // Wait for handshake to be processed by Projector
                    kotlinx.coroutines.delay(500)

                    // Show success message
                    Snackbar.make(
                        binding.root,
                        getString(R.string.device_discovery_connected_success),
                        Snackbar.LENGTH_SHORT
                    ).show()

                    // UPDATED: Navigate to Main Session Manager after a brief delay
                    kotlinx.coroutines.delay(1000)
                    startActivity(Intent(this@DeviceDiscoveryActivity, MainSessionActivity::class.java))

                } else {
                    Log.e("MasterNetwork", "Manual connection failed")
                    Snackbar.make(
                        binding.root,
                        getString(R.string.device_discovery_connection_failed_format, ipAddress, port),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            } catch (e: Exception) {
                Log.e("MasterNetwork", "Connection attempt failed", e)
                Snackbar.make(
                    binding.root,
                    getString(R.string.device_discovery_connection_error_format, e.message),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Validate IP address format
     */
    private fun isValidIpAddress(ip: String): Boolean {
        return try {
            val parts = ip.split(".")
            if (parts.size != 4) return false

            parts.all { part ->
                val num = part.toIntOrNull() ?: return false
                num in 0..255
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Save last successful connection
     */
    private fun saveLastConnection(ip: String, port: Int) {
        getSharedPreferences(getString(R.string.settings_connection_prefs_key), MODE_PRIVATE).edit().apply {
            putString(getString(R.string.settings_last_ip_key), ip)
            putInt(getString(R.string.settings_last_port_key), port)
            apply()
        }
    }

    /**
     * Load last connection from preferences
     */
    private fun loadLastConnection() {
        val prefs = getSharedPreferences(getString(R.string.settings_connection_prefs_key), MODE_PRIVATE)
        val lastIp = prefs.getString(getString(R.string.settings_last_ip_key), "")
        val lastPort = prefs.getInt(getString(R.string.settings_last_port_key), 0)

        if (!lastIp.isNullOrEmpty() && lastPort > 0) {
            binding.etIpAddress.setText(lastIp)
            binding.etPort.setText(lastPort.toString())
        }
    }

    /**
     * Update connection state UI
     */
    private fun updateConnectionState(state: MasterNetworkClient.ConnectionState) {
        when (state) {
            MasterNetworkClient.ConnectionState.DISCONNECTED -> {
                binding.progressBar.visibility = View.GONE
                binding.textStatus.text = getString(R.string.device_discovery_not_connected)
                binding.btnManualConnect.isEnabled = MasterConfigManager.isConfigurationReady()
            }
            MasterNetworkClient.ConnectionState.DISCOVERING -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.textStatus.text = getString(R.string.device_discovery_searching)
                binding.btnManualConnect.isEnabled = MasterConfigManager.isConfigurationReady()
            }
            MasterNetworkClient.ConnectionState.CONNECTING -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.textStatus.text = getString(R.string.device_discovery_connecting)
                binding.btnManualConnect.isEnabled = false
            }
            MasterNetworkClient.ConnectionState.CONNECTED -> {
                binding.progressBar.visibility = View.GONE
                binding.textStatus.text = getString(R.string.device_discovery_connected)
                binding.btnManualConnect.isEnabled = false
            }
            MasterNetworkClient.ConnectionState.ERROR -> {
                binding.progressBar.visibility = View.GONE
                binding.textStatus.text = getString(R.string.device_discovery_connection_error)
                binding.btnManualConnect.isEnabled = MasterConfigManager.isConfigurationReady()
                Snackbar.make(binding.root, getString(R.string.device_discovery_connection_failed), Snackbar.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Update configuration status in UI
     */
    private fun updateConfigStatus(status: String) {
        Log.d("MasterConfig", "Config status: $status")
    }

    /**
     * Check permissions
     */
    private fun checkPermissions() {
        // On Android 12+ we need location permission for NSD
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d("MasterNetwork", "Location permission already granted")
                }
                else -> {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        }
    }

    /**
     * Start device discovery
     */
    private fun startDiscovery() {
        networkClient.startDiscovery()
    }

    /**
     * Refresh discovery
     */
    private fun refreshDiscovery() {
        networkClient.stopDiscovery()
        devicesAdapter.updateDevices(emptyList())
        startDiscovery()
    }

    /**
     * Connect to discovered device
     * UPDATED: Now navigates to MainSessionActivity after successful connection
     */
    private fun connectToDevice(device: MasterNetworkClient.DiscoveredDevice) {
        if (!MasterConfigManager.isConfigurationReady()) {
            showConfigurationNotReadyDialog()
            return
        }

        lifecycleScope.launch {
            try {
                val success = networkClient.connectToDevice(device)
                if (success) {
                    Log.d("MasterNetwork", "NSD connection successful - starting session")

                    // ✅ CRITICAL FIX: Start session to send handshake
                    val sessionId = networkClient.startSession()
                    NetworkManager.setCurrentSessionId(sessionId)

                    Log.d("MasterNetwork", "Session started: $sessionId")

                    // Wait for handshake to be processed by Projector
                    kotlinx.coroutines.delay(500)

                    navigateToMainSessionManager()
                }
            } catch (e: Exception) {
                Log.e("MasterNetwork", "Connection failed", e)
                Snackbar.make(
                    binding.root,
                    getString(R.string.device_discovery_connection_error_format, e.message),
                    Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Navigate to main session manager after successful connection
     * UPDATED: Now goes to MainSessionActivity instead of ParticipantInfoActivity
     */
    private fun navigateToMainSessionManager() {
        val intent = Intent(this, MainSessionActivity::class.java)
        startActivity(intent)
    }

    /**
     * Navigate to participant information screen
     * @deprecated Use navigateToMainSessionManager() instead for proper session management
     */
    @Deprecated("Use navigateToMainSessionManager() instead")
    private fun navigateToParticipantInfo() {
        val intent = Intent(this, MainSessionActivity::class.java)
        startActivity(intent)
    }

    /**
     * Navigate to main activity after successful connection
     * @deprecated Use navigateToMainSessionManager() instead for proper session management
     */
    @Deprecated("Use navigateToMainSessionManager() instead")
    private fun navigateToMain() {
        val intent = Intent(this, MainSessionActivity::class.java)
        startActivity(intent)
    }

    /**
     * Show configuration error dialog
     */
    private fun showConfigurationError() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.device_discovery_config_error_title))
            .setMessage(getString(R.string.device_discovery_config_error_message))
            .setPositiveButton(getString(R.string.device_discovery_retry)) { _, _ ->
                reloadConfiguration()
            }
            .setNegativeButton(getString(R.string.device_discovery_exit)) { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Show configuration not ready dialog
     */
    private fun showConfigurationNotReadyDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.device_discovery_config_not_ready_title))
            .setMessage(getString(R.string.device_discovery_config_not_ready_message))
            .setPositiveButton(getString(R.string.device_discovery_retry_config)) { _, _ ->
                reloadConfiguration()
            }
            .setNegativeButton(getString(R.string.device_discovery_cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Reload configuration
     */
    private fun reloadConfiguration() {
        lifecycleScope.launch {
            updateConfigStatus(getString(R.string.device_discovery_reloading_config))
            val success = MasterConfigManager.reloadConfiguration()
            if (success) {
                updateConfigStatus(getString(R.string.device_discovery_config_reloaded))
            } else {
                showConfigurationError()
            }
        }
    }

    /**
     * Show permission error
     */
    private fun showPermissionError() {
        Snackbar.make(
            binding.root,
            getString(R.string.device_discovery_permission_required),
            Snackbar.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Don't disconnect here - let the next activity handle it
        // Only stop discovery
        networkClient.stopDiscovery()
    }

    /**
     * Adapter for displaying discovered devices
     */
    private class DevicesAdapter(
        private val onDeviceClick: (MasterNetworkClient.DiscoveredDevice) -> Unit
    ) : RecyclerView.Adapter<DevicesAdapter.DeviceViewHolder>() {

        private var devices = listOf<MasterNetworkClient.DiscoveredDevice>()

        fun updateDevices(newDevices: List<MasterNetworkClient.DiscoveredDevice>) {
            devices = newDevices
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_device, parent, false)
            return DeviceViewHolder(view, onDeviceClick)
        }

        override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
            holder.bind(devices[position])
        }

        override fun getItemCount() = devices.size

        class DeviceViewHolder(
            itemView: View,
            private val onClick: (MasterNetworkClient.DiscoveredDevice) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {

            private val textName: TextView = itemView.findViewById(R.id.text_device_name)
            private val textInfo: TextView = itemView.findViewById(R.id.text_device_info)

            fun bind(device: MasterNetworkClient.DiscoveredDevice) {
                textName.text = device.displayName
                textInfo.text = if (device.isResolved) {
                    "${device.host}:${device.port}"
                } else {
                    itemView.context.getString(R.string.device_discovery_resolving)
                }

                itemView.setOnClickListener {
                    if (device.isResolved) {
                        onClick(device)
                    }
                }

                itemView.isEnabled = device.isResolved
                itemView.alpha = if (device.isResolved) 1.0f else 0.5f
            }
        }
    }
}