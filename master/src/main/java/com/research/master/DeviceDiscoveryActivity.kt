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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Activity for discovering and connecting to Projector devices
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
            startDiscovery()
        } else {
            showPermissionError()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDeviceDiscoveryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Set up toolbar
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Connect to Projector"

        // Initialize network client
        networkClient = MasterNetworkClient(this)

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
        // Set up click listeners
        binding.btnRefresh.setOnClickListener {
            refreshDiscovery()
        }

        binding.btnTestDirect.setOnClickListener {
            testDirectConnection()
        }

        // Check permissions and start discovery
        binding.root.postDelayed({
            checkPermissionsAndStartDiscovery()
        }, 2000)

        // Add permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                Log.d("MasterNetwork", "Requesting location permission")
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            } else {
                Log.d("MasterNetwork", "Location permission already granted")
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("MasterNetwork", "Location permission granted, restarting discovery")
                refreshDiscovery()
            } else {
                Log.e("MasterNetwork", "Location permission denied")
            }
        }
    }

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
    }

    private fun setupClickListeners() {
        binding.btnRefresh.setOnClickListener {
            refreshDiscovery()
        }

        binding.btnTestDirect.setOnClickListener {
            testDirectConnection()
        }
    }

    private fun updateConnectionState(state: MasterNetworkClient.ConnectionState) {
        when (state) {
            MasterNetworkClient.ConnectionState.DISCONNECTED -> {
                binding.progressBar.visibility = View.GONE
                binding.textStatus.text = "Not connected"
            }
            MasterNetworkClient.ConnectionState.DISCOVERING -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.textStatus.text = "Searching for devices..."
            }
            MasterNetworkClient.ConnectionState.CONNECTING -> {
                binding.progressBar.visibility = View.VISIBLE
                binding.textStatus.text = "Connecting..."
            }
            MasterNetworkClient.ConnectionState.CONNECTED -> {
                binding.progressBar.visibility = View.GONE
                binding.textStatus.text = "Connected"
                // Navigate to main activity
                navigateToMain()
            }
            MasterNetworkClient.ConnectionState.ERROR -> {
                binding.progressBar.visibility = View.GONE
                binding.textStatus.text = "Connection error"
                Snackbar.make(binding.root, "Connection failed", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun checkPermissionsAndStartDiscovery() {
        // On Android 12+ we need location permission for NSD
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startDiscovery()
                }
                else -> {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            }
        } else {
            startDiscovery()
        }
    }

    private fun startDiscovery() {
        networkClient.startDiscovery()
    }

    private fun refreshDiscovery() {
        networkClient.stopDiscovery()
        devicesAdapter.updateDevices(emptyList())
        startDiscovery()
    }

    private fun connectToDevice(device: MasterNetworkClient.DiscoveredDevice) {
        lifecycleScope.launch {
            val success = networkClient.connectToDevice(device)
            if (success) {
                // Start session
                val sessionId = networkClient.startSession()
                // Session ID will be used for all subsequent communication
            }
        }
    }

    private fun navigateToMain() {
        // TODO: Navigate to main Master app activity
        finish()
    }

    private fun showPermissionError() {
        Snackbar.make(
            binding.root,
            "Location permission is required for device discovery",
            Snackbar.LENGTH_LONG
        ).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkClient.stopDiscovery()
        networkClient.disconnect()
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
                    "Resolving..."
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

    private fun testDirectConnection() {
        lifecycleScope.launch {
            // First, find the port from Projector logs
            // Look for "Server started on port XXXXX" in Projector logs

            val testDevice = MasterNetworkClient.DiscoveredDevice(
                serviceName = "Direct_Test",
                teamId = "Team1",
                deviceId = "ProjectorA",
                host = "10.0.2.2", // emulator host
                port = 33995, // Replace with actual port from logs
                isResolved = true
            )

            Log.d("MasterNetwork", "Attempting direct connection to ${testDevice.host}:${testDevice.port}")

            val success = networkClient.connectToDevice(testDevice)
            if (success) {
                Log.d("MasterNetwork", "Direct connection successful!")
            } else {
                Log.e("MasterNetwork", "Direct connection failed")
            }
        }
    }
}