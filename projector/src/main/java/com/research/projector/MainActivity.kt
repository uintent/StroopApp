package com.research.projector

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.research.projector.databinding.ActivityMainBinding
import com.research.projector.network.ProjectorNetworkService
import com.research.projector.viewmodels.ConfigState
import com.research.projector.viewmodels.TaskSelectionViewModel
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Main entry point activity for the Stroop Research app.
 * Handles initial configuration loading and navigation to task selection.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TaskSelectionViewModel by viewModels()
    private lateinit var networkService: ProjectorNetworkService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        android.util.Log.d("StroopApp", "MainActivity onCreate started")

        // Set up view binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        android.util.Log.d("StroopApp", "ViewBinding set up, setting up observers")

        // Initialize and start network service
        initializeNetworkService()

        // Set up observers
        setupObservers()

        // Set up click listeners
        setupClickListeners()

        android.util.Log.d("StroopApp", "MainActivity onCreate completed")
        // TEMPORARY TEST - Force success state after a delay
        binding.root.postDelayed({
            android.util.Log.d("StroopApp", "FORCING success state for test")
            binding.layoutLoading.isVisible = false
            binding.layoutReady.isVisible = true
            binding.layoutError.isVisible = false
            android.util.Log.d("StroopApp", "Forced - layout_ready visibility: ${binding.layoutReady.isVisible}")
        }, 2000) // Wait 2 seconds then force it
    }

    /**
     * Get the device's IP address
     */
    private fun getDeviceIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is InetAddress) {
                        val hostAddress = address.hostAddress
                        // Check if it's IPv4 (not IPv6)
                        if (hostAddress?.contains(':') == false) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ProjectorApp", "Error getting IP address", e)
        }
        return "Unknown"
    }

    /**
     * Initialize and start the network service
     */
    private fun initializeNetworkService() {
        android.util.Log.d("ProjectorApp", "Initializing network service")

        // Create network service
        networkService = ProjectorNetworkService(this)
        networkService.start()

        // Observe connection state
        lifecycleScope.launch {
            networkService.connectionState.collect { state ->
                android.util.Log.d("ProjectorApp", "Network state changed: $state")

                // Update UI on main thread
                runOnUiThread {
                    when (state) {
                        ProjectorNetworkService.ConnectionState.DISCONNECTED -> {
                            binding.cardConnectionInfo.visibility = View.GONE
                            // Don't show toast for initial disconnected state
                        }
                        ProjectorNetworkService.ConnectionState.ADVERTISING -> {
                            // Get the port from the service
                            val port = networkService.getServerPort()
                            val ipAddress = getDeviceIpAddress()

                            binding.tvConnectionInfo.text = "Connect to:\nIP: $ipAddress\nPort: $port"
                            binding.cardConnectionInfo.visibility = View.VISIBLE

                            Log.d("ProjectorApp", "Showing connection info - IP: $ipAddress, Port: $port")
                            Toast.makeText(this@MainActivity, "Network: Ready for connections", Toast.LENGTH_SHORT).show()
                        }
                        ProjectorNetworkService.ConnectionState.CONNECTED -> {
                            binding.tvConnectionInfo.text = "Connected to Master!"
                            Toast.makeText(this@MainActivity, "Connected to Master!", Toast.LENGTH_SHORT).show()
                        }
                        ProjectorNetworkService.ConnectionState.ERROR -> {
                            binding.cardConnectionInfo.visibility = View.GONE
                            Toast.makeText(this@MainActivity, "Network Error", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        android.util.Log.d("ProjectorApp", "Network service started")
    }

    /**
     * Set up LiveData observers
     */
    private fun setupObservers() {
        android.util.Log.d("StroopApp", "Setting up observers")

        // Observe configuration state
        viewModel.configState.observe(this) { state ->
            android.util.Log.d("StroopApp", "Config state changed: $state")
            handleConfigState(state)
        }
    }

    /**
     * Set up click listeners for UI elements
     */
    private fun setupClickListeners() {
        // Continue button (when config loaded successfully)
        binding.btnContinue.setOnClickListener {
            navigateToTaskSelection()
        }

        // Retry button (when config loading failed)
        binding.btnRetry.setOnClickListener {
            viewModel.retryConfiguration()
        }

        // Exit button (when config loading failed)
        binding.btnExit.setOnClickListener {
            showExitConfirmation()
        }
    }

    /**
     * Handle configuration state changes
     */
    private fun handleConfigState(state: ConfigState) {
        android.util.Log.d("StroopApp", "Config state: $state")
        when (state) {
            is ConfigState.Loading -> {
                android.util.Log.d("StroopApp", "Showing loading state")
                showLoadingState()
            }

            is ConfigState.Success -> {
                android.util.Log.d("StroopApp", "Showing success state")
                showSuccessState()
            }

            is ConfigState.Error -> {
                android.util.Log.d("StroopApp", "Showing error state: ${state.message}")
                showErrorState(state.message)
            }
        }
    }

    /**
     * Show loading state UI
     */
    private fun showLoadingState() {
        binding.layoutLoading.isVisible = true
        binding.layoutReady.isVisible = false
        binding.layoutError.isVisible = false
    }

    /**
     * Show success state UI
     */
    private fun showSuccessState() {
        android.util.Log.d("StroopApp", "showSuccessState called")

        binding.layoutLoading.isVisible = false
        binding.layoutReady.isVisible = true
        binding.layoutError.isVisible = false

        android.util.Log.d("StroopApp", "layout_ready visibility set to: ${binding.layoutReady.isVisible}")
        android.util.Log.d("StroopApp", "layout_loading visibility set to: ${binding.layoutLoading.isVisible}")
    }

    /**
     * Show error state UI
     */
    private fun showErrorState(errorMessage: String) {
        binding.layoutLoading.isVisible = false
        binding.layoutReady.isVisible = false
        binding.layoutError.isVisible = true

        // Set error message
        binding.tvErrorMessage.text = errorMessage
    }

    /**
     * Navigate to task selection activity
     */
    private fun navigateToTaskSelection() {
        val intent = Intent(this, TaskSelectionActivity::class.java)
        startActivity(intent)

        // Optionally finish this activity so user can't return with back button
        // finish()
    }

    /**
     * Show exit confirmation dialog
     */
    private fun showExitConfirmation() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Exit Application")
            .setMessage("Are you sure you want to exit the Stroop Research app?")
            .setPositiveButton("Exit") { _, _ ->
                finishAffinity() // Close all activities and exit app
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Handle back button press
     */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // On main activity, back button should show exit confirmation
        // NOT calling super.onBackPressed() because we want to show dialog first
        showExitConfirmation()
    }

    /**
     * Handle activity lifecycle - refresh config state when returning
     */
    override fun onResume() {
        super.onResume()

        // If we're returning from another activity and there was an error,
        // we might want to retry configuration loading
        val currentState = viewModel.configState.value
        if (currentState is ConfigState.Error) {
            // Optionally auto-retry or show retry option
        }
    }

    /**
     * Clean up network service when activity is destroyed
     */
    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("ProjectorApp", "Stopping network service")
        networkService.stop()
    }
}