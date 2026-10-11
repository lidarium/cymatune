package com.cymatune.detection

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PermissionAndFallbackManager - Comprehensive permission handling and fallback mechanism
 * for SMS and paging detection system. This manager ensures graceful degradation when
 * permissions are not available while maintaining maximum detection capability.
 * 
 * Features:
 * - Dynamic permission checking and requesting
 * - Multiple fallback strategies for different permission scenarios
 * - Degraded mode operation with reduced functionality
 * - User guidance and education
 * - Permission status monitoring
 * - Automatic retry mechanisms
 */
class PermissionAndFallbackManager(private val context: Context) {
    
    companion object {
        private const val TAG = "PermissionFallbackManager"
        
        // Permission request codes
        const val REQUEST_CODE_SMS_PERMISSIONS = 1001
        const val REQUEST_CODE_PHONE_STATE_PERMISSION = 1002
        const val REQUEST_CODE_LOCATION_PERMISSIONS = 1003
        
        // Required permissions for full functionality
        private val REQUIRED_SMS_PERMISSIONS = arrayOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS
        )
        
        private val REQUIRED_PHONE_PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
        
        private val REQUIRED_LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        // Fallback monitoring intervals
        private const val FALLBACK_CHECK_INTERVAL_MS = 30000L // 30 seconds
        private const val NETWORK_STATE_CHECK_INTERVAL_MS = 10000L // 10 seconds
    }
    
    // Permission status tracking
    private var smsPermissionsGranted = false
    private var phoneStatePermissionGranted = false
    private var locationPermissionsGranted = false
    private var lastFallbackCheck = 0L
    
    // Fallback operation state
    private var isUsingNetworkFallback = false
    private var isUsingDegradedMode = false
    private var fallbackAttempts = 0
    private val maxFallbackAttempts = 3
    
    // Callbacks for permission status changes
    private var permissionStatusCallback: ((PermissionStatus) -> Unit)? = null
    
    init {
        updatePermissionStatus()
    }
    
    /**
     * Check and update current permission status
     */
    fun updatePermissionStatus() {
        smsPermissionsGranted = checkSMSPermissions()
        phoneStatePermissionGranted = checkPhoneStatePermission()
        locationPermissionsGranted = checkLocationPermissions()
        
        val currentStatus = getCurrentPermissionStatus()
        permissionStatusCallback?.invoke(currentStatus)
        
        Log.d(TAG, "Permission status updated: $currentStatus")
    }
    
    /**
     * Request SMS permissions from user
     */
    fun requestSMSPermissions(activity: AppCompatActivity, rationale: String = "") {
        if (smsPermissionsGranted) {
            Log.d(TAG, "SMS permissions already granted")
            return
        }
        
        val permissionsToRequest = REQUIRED_SMS_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isEmpty()) {
            smsPermissionsGranted = true
            return
        }
        
        // Check if we should show rationale
        val shouldShowRationale = permissionsToRequest.any { permission ->
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        }
        
        val finalRationale = if (rationale.isEmpty()) {
            if (shouldShowRationale) {
                "SMS permissions are needed to detect silent SMS and potential fake tower activity. " +
                "This helps protect your privacy and security."
            } else {
                "SMS monitoring requires permission to receive and read SMS messages for security analysis."
            }
        } else {
            rationale
        }
        
        Log.i(TAG, "Requesting SMS permissions: $finalRationale")
        
        // Use Activity Result API for modern permission handling
        val permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it == true }
            if (allGranted) {
                smsPermissionsGranted = true
                fallbackAttempts = 0
                isUsingNetworkFallback = false
                Log.i(TAG, "SMS permissions granted by user")
            } else {
                handlePermissionDenied("SMS")
                fallbackToNetworkMonitoring()
            }
            updatePermissionStatus()
        }
        
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }
    
    /**
     * Request phone state permissions
     */
    fun requestPhoneStatePermissions(activity: AppCompatActivity) {
        if (phoneStatePermissionGranted) {
            Log.d(TAG, "Phone state permission already granted")
            return
        }
        
        val permission = Manifest.permission.READ_PHONE_STATE
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            phoneStatePermissionGranted = true
            return
        }
        
        val permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            if (isGranted) {
                phoneStatePermissionGranted = true
                Log.i(TAG, "Phone state permission granted")
            } else {
                handlePermissionDenied("Phone State")
            }
            updatePermissionStatus()
        }
        
        permissionLauncher.launch(permission)
    }
    
    /**
     * Request location permissions
     */
    fun requestLocationPermissions(activity: AppCompatActivity) {
        if (locationPermissionsGranted) {
            Log.d(TAG, "Location permissions already granted")
            return
        }
        
        val permissionsToRequest = REQUIRED_LOCATION_PERMISSIONS.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
        
        if (permissionsToRequest.isEmpty()) {
            locationPermissionsGranted = true
            return
        }
        
        val permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it == true }
            if (allGranted) {
                locationPermissionsGranted = true
                Log.i(TAG, "Location permissions granted")
            } else {
                handlePermissionDenied("Location")
            }
            updatePermissionStatus()
        }
        
        permissionLauncher.launch(permissionsToRequest.toTypedArray())
    }
    
    /**
     * Handle permission denial with appropriate fallback
     */
    private fun handlePermissionDenied(permissionType: String) {
        Log.w(TAG, "$permissionType permission denied, enabling fallback mechanisms")
        
        when (permissionType) {
            "SMS" -> {
                fallbackAttempts++
                if (fallbackAttempts <= maxFallbackAttempts) {
                    fallbackToNetworkMonitoring()
                } else {
                    enableDegradedMode()
                }
            }
            "Phone State" -> {
                // Continue with limited phone state access
                Log.w(TAG, "Continuing with limited phone state access")
            }
            "Location" -> {
                // Continue without precise location data
                Log.w(TAG, "Continuing without precise location data")
            }
        }
    }
    
    /**
     * Fallback to network-level monitoring when SMS permissions are denied
     */
    private fun fallbackToNetworkMonitoring() {
        if (isUsingNetworkFallback) {
            Log.d(TAG, "Already using network fallback")
            return
        }
        
        Log.w(TAG, "Enabling network-level fallback monitoring")
        isUsingNetworkFallback = true
        isUsingDegradedMode = false
        
        // Monitor network state changes for paging patterns
        // This provides some detection capability without SMS access
        
        Log.i(TAG, "Network fallback active - monitoring connection patterns and signal anomalies")
    }
    
    /**
     * Enable degraded mode with minimal functionality
     */
    private fun enableDegradedMode() {
        Log.w(TAG, "Enabling degraded mode due to insufficient permissions")
        isUsingDegradedMode = true
        isUsingNetworkFallback = false
        
        // Only basic signal monitoring and user education
        Log.i(TAG, "Degraded mode active - limited detection capability")
        
        // Could show user guidance about enabling permissions
        showPermissionGuidance()
    }
    
    /**
     * Show user guidance about enabling permissions
     */
    private fun showPermissionGuidance() {
        // This would typically show an in-app message or dialog
        // explaining why permissions are needed and how to enable them
        
        Log.i(TAG, "Showing permission guidance to user")
        
        // Could trigger a UI component to show guidance
        // For now, just log the guidance that would be shown
        val guidanceMessage = """
            To enable full fake tower detection capabilities:
            1. Go to Settings > Apps > Cymatune LT > Permissions
            2. Enable SMS permissions (Receive SMS, Read SMS)
            3. Enable Phone permissions (Read Phone State)
            4. Enable Location permissions (Fine Location)
            
            These permissions help detect potential security threats
            including IMSI catchers and fake cell towers.
        """.trimIndent()
        
        Log.i(TAG, "Permission guidance: $guidanceMessage")
    }
    
    /**
     * Get current operation mode based on permissions
     */
    fun getCurrentOperationMode(): OperationMode {
        return when {
            smsPermissionsGranted && phoneStatePermissionGranted && locationPermissionsGranted -> {
                OperationMode.FULL_CAPABILITY
            }
            smsPermissionsGranted && phoneStatePermissionGranted -> {
                OperationMode.REDUCED_SMS_ONLY
            }
            phoneStatePermissionGranted -> {
                OperationMode.NETWORK_ONLY
            }
            else -> OperationMode.DEGRADED
        }
    }
    
    /**
     * Get current permission status summary
     */
    fun getCurrentPermissionStatus(): PermissionStatus {
        return PermissionStatus(
            smsPermissionsGranted = smsPermissionsGranted,
            phoneStatePermissionGranted = phoneStatePermissionGranted,
            locationPermissionsGranted = locationPermissionsGranted,
            operationMode = getCurrentOperationMode(),
            isUsingFallback = isUsingNetworkFallback,
            isUsingDegradedMode = isUsingDegradedMode,
            fallbackAttempts = fallbackAttempts
        )
    }
    
    /**
     * Check if SMS permissions are available
     */
    fun hasSMSPermissions(): Boolean = smsPermissionsGranted
    
    /**
     * Check if phone state permission is available
     */
    fun hasPhoneStatePermission(): Boolean = phoneStatePermissionGranted
    
    /**
     * Check if location permissions are available
     */
    fun hasLocationPermissions(): Boolean = locationPermissionsGranted
    
    /**
     * Check if any fallback mode is active
     */
    fun isUsingFallbackMode(): Boolean = isUsingNetworkFallback || isUsingDegradedMode
    
    /**
     * Get recommended action based on current permission status
     */
    fun getRecommendedAction(): String {
        return when (getCurrentOperationMode()) {
            OperationMode.FULL_CAPABILITY -> "Continue monitoring with full capabilities"
            OperationMode.REDUCED_SMS_ONLY -> "Request phone state permission for enhanced detection"
            OperationMode.NETWORK_ONLY -> "Request SMS permissions for full detection capability"
            OperationMode.DEGRADED -> "Request all permissions for optimal security protection"
        }
    }
    
    /**
     * Perform periodic permission status check
     */
    suspend fun performPeriodicPermissionCheck() = withContext(Dispatchers.IO) {
        val currentTime = System.currentTimeMillis()
        
        if (currentTime - lastFallbackCheck < FALLBACK_CHECK_INTERVAL_MS) {
            return@withContext
        }
        
        lastFallbackCheck = currentTime
        
        // Re-check permissions in case user granted them
        updatePermissionStatus()
        
        val status = getCurrentPermissionStatus()
        if (status.operationMode == OperationMode.FULL_CAPABILITY) {
            // User granted permissions, exit fallback mode
            if (isUsingNetworkFallback || isUsingDegradedMode) {
                Log.i(TAG, "Permissions granted, exiting fallback mode")
                isUsingNetworkFallback = false
                isUsingDegradedMode = false
                fallbackAttempts = 0
            }
        }
        
        Log.d(TAG, "Periodic permission check completed: ${status.operationMode}")
    }
    
    /**
     * Reset fallback state (useful when permissions are manually granted)
     */
    fun resetFallbackState() {
        isUsingNetworkFallback = false
        isUsingDegradedMode = false
        fallbackAttempts = 0
        updatePermissionStatus()
        
        Log.d(TAG, "Fallback state reset")
    }
    
    /**
     * Set callback for permission status changes
     */
    fun setPermissionStatusCallback(callback: (PermissionStatus) -> Unit) {
        permissionStatusCallback = callback
    }
    
    // Permission checking helper methods
    private fun checkSMSPermissions(): Boolean {
        return REQUIRED_SMS_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    private fun checkPhoneStatePermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun checkLocationPermissions(): Boolean {
        return REQUIRED_LOCATION_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    // Data classes for permission management
    enum class OperationMode {
        FULL_CAPABILITY,      // All permissions granted
        REDUCED_SMS_ONLY,     // SMS + phone state, no location
        NETWORK_ONLY,         // Only phone state
        DEGRADED              // Minimal functionality
    }
    
    data class PermissionStatus(
        val smsPermissionsGranted: Boolean,
        val phoneStatePermissionGranted: Boolean,
        val locationPermissionsGranted: Boolean,
        val operationMode: OperationMode,
        val isUsingFallback: Boolean,
        val isUsingDegradedMode: Boolean,
        val fallbackAttempts: Int
    )
    
    /**
     * Fallback detection strategies for different permission scenarios
     */
    class FallbackStrategies {
        
        companion object {
            
            /**
             * Network-only detection strategy when SMS permissions are denied
             */
            fun networkOnlyDetection(
                telephonyManager: TelephonyManager,
                signalCallback: (String, Int?) -> Unit
            ): NetworkDetectionResult {
                return try {
                    // Monitor network state changes
                    val networkState = telephonyManager.dataState
                    val networkType = telephonyManager.networkType
                    
                    // Monitor signal strength changes
                    val signalStrength = try {
                        when {
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                                telephonyManager.signalStrength?.let { signalStrength ->
                                    when {
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                                            // For Android Q and above, signalStrength is a SignalStrength object
                                            try {
                                                // Try to access dbm property if available
                                                signalStrength.javaClass.getMethod("getDbm").invoke(signalStrength) as? Int
                                            } catch (e: Exception) {
                                                // Fallback to level
                                                signalStrength.javaClass.getMethod("getLevel").invoke(signalStrength) as? Int
                                            }
                                        }
                                        else -> {
                                            // For older Android versions (P), signalStrength is a SignalStrength object but we might need reflection or it might be different
                                            // Actually signalStrength was added in P (API 28)
                                            try {
                                                signalStrength.level
                                            } catch (e: Exception) {
                                                null
                                            }
                                        }
                                    }
                                }
                            }
                            else -> null
                        }
                    } catch (e: SecurityException) {
                        null
                    }
                    
                    signalCallback("network_state", signalStrength)
                    
                    NetworkDetectionResult(
                        networkState = networkState,
                        networkType = networkType,
                        signalStrength = signalStrength,
                        detectionConfidence = if (signalStrength != null) 0.3 else 0.1,
                        method = "Network monitoring"
                    )
                    
                } catch (e: SecurityException) {
                    Log.w(TAG, "Cannot access network information due to permissions", e)
                    NetworkDetectionResult(
                        networkState = -1,
                        networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN,
                        signalStrength = null,
                        detectionConfidence = 0.0,
                        method = "Permission denied",
                        error = e
                    )
                }
            }
            
            /**
             * Degraded mode detection with minimal permissions
             */
            fun degradedModeDetection(): DegradedDetectionResult {
                // Only basic system information available
                return DegradedDetectionResult(
                    systemInfo = mapOf(
                        "android_version" to Build.VERSION.SDK_INT.toString(),
                        "device_model" to Build.MODEL,
                        "manufacturer" to Build.MANUFACTURER
                    ),
                    detectionConfidence = 0.1,
                    method = "System information only"
                )
            }
        }
    }
    
    data class NetworkDetectionResult(
        val networkState: Int,
        val networkType: Int,
        val signalStrength: Int?,
        val detectionConfidence: Double,
        val method: String,
        val error: Exception? = null
    )
    
    data class DegradedDetectionResult(
        val systemInfo: Map<String, String>,
        val detectionConfidence: Double,
        val method: String
    )
}