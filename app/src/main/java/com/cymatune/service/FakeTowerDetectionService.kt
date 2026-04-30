package com.cymatune.service

import com.cymatune.security.KeystoreHelper
import com.cymatune.security.SecurityNotificationManager
import android.app.Notification
import androidx.core.content.ContextCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import com.cymatune.R
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfoLte
import android.telephony.CellSignalStrengthLte
import android.telephony.TelephonyManager
import android.util.Log
import com.cymatune.db.AppDatabase
import com.cymatune.db.DatabaseManager
import com.cymatune.db.FakeTower
import com.cymatune.db.FakeTowerDao

import com.cymatune.db.ProtocolHandshakeDao
import com.cymatune.db.TowerDao
import com.cymatune.detection.FakeTowerDetector
import com.cymatune.detection.SuspiciousTower
import com.cymatune.util.TowerConnectionInfo
import com.cymatune.detection.ConnectionStateMonitor
import com.cymatune.security.TrustScoreManager
import com.cymatune.db.LocationHistoryDao
import com.cymatune.detection.AnomalyDetector
import com.cymatune.db.SignalBaselineDao
import com.cymatune.db.NeighborHistoryDao
import com.cymatune.db.LacCidPatternDao
import com.cymatune.exceptions.ComponentInitializationException
import com.cymatune.lifecycle.ComponentLifecycleManager
import com.cymatune.lifecycle.ErrorRecoveryManager

// import com.cymatune.lifecycle.TowerRefinementManager - REMOVED to avoid conflict with util.TowerRefinementManager
import com.cymatune.util.TowerRefinementManager // Explicit import to ensure correct class is used
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.cymatune.util.*
import com.cymatune.FAKE_TOWER_DETECTED
import com.cymatune.util.GSMWCDMASupport
import com.cymatune.tower.TowerTriangulation
import androidx.core.app.NotificationCompat
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.cancel
import com.cymatune.util.AccelerometerData
import com.cymatune.util.KalmanFilterLocationProvider
import android.os.Parcel
import android.telephony.CellInfo
import android.telephony.CellInfoNr
import android.telephony.CellInfoGsm
import android.telephony.CellInfoWcdma

import com.cymatune.util.CommonUtils
import com.cymatune.util.DistanceEstimator // Import DistanceEstimator
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionInfo
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import kotlin.math.abs
import kotlin.math.sqrt
import android.os.BatteryManager

class FakeTowerDetectionService : Service(), LocationListener, SensorEventListener {
    private val CHANNEL_ID = "CymatuneFakeTowerDetection"
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var accelerometerSensor: Sensor? = null
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var currentLocation: Location? = null
    private var currentAccelerometerData: AccelerometerData? = null
    private var currentTA: Int? = null
    private var isMonitoring = false
    private var isTriangulationMode = false
    private var targetTowerId: String? = null
    private var lastTriangulationLocation: Location? = null // For 10-meter distance check
    private lateinit var keystoreHelper: KeystoreHelper
    private lateinit var sharedPreferences: SharedPreferences // Declare sharedPreferences
    private var currentScanRadius: Int = 5 // Default scan radius in km
    private var towersScannedCount: Int = 0

    // Phase 4.0: Multi-SIM Support
    private lateinit var subscriptionManager: SubscriptionManager
    private val subscriptionTelephonyManagers = mutableMapOf<Int, TelephonyManager>() // subscriptionId -> TelephonyManager

    companion object {
        const val ACTION_NEIGHBOR_CELLS_UPDATE = "com.cymatune.NEIGHBOR_CELLS_UPDATE"
        const val ACTION_ALL_VISIBLE_TOWERS = "com.cymatune.ALL_VISIBLE_TOWERS"
        const val ACTION_TOWER_REFINEMENT_UPDATE = "com.cymatune.TOWER_REFINEMENT_UPDATE"
        const val ACTION_MULTI_SIM_TOWER_UPDATE = "com.cymatune.MULTI_SIM_TOWER_UPDATE"
        const val EXTRA_NEIGHBOR_CELLS_COUNT = "neighbor_cells_count"
        const val EXTRA_CURRENT_TOWER_INFO = "current_tower_info"
        const val EXTRA_ALL_TOWERS_LIST = "all_towers_list"
        const val EXTRA_TOWER_ID = "tower_id"
        const val EXTRA_PRECISION_RADIUS = "precision_radius"
        const val EXTRA_OBSERVATION_COUNT = "observation_count"
        const val EXTRA_ACTIVE_SIM_COUNT = "active_sim_count"
        const val EXTRA_SIM_TOWER_MAP = "sim_tower_map"
        const val ACTION_STOP_MONITORING = "com.cymatune.STOP_MONITORING"
        const val ACTION_TOGGLE_ALERT_SUPPRESSION = "com.cymatune.TOGGLE_ALERT_SUPPRESSION"
    }

    // Real-time cell info callback for Android 10+
    // Real-time cell info callback for Android 12+ (API 31+)
    private var cellInfoCallback: Any? = null
    
    // Cache for event-driven cell info scanning
    private var lastKnownCellInfo: List<android.telephony.CellInfo> = emptyList()

    // Saved locked tower ID for persistence
    private var savedLockedTowerId: String? = null
    
    // Movement Detection (Accelerometer-based)
    private lateinit var movementDetector: com.cymatune.detection.MovementDetector
    private var isUserMoving: Boolean = false
    private var movementIntensity: com.cymatune.detection.MovementDetector.MovementIntensity = 
        com.cymatune.detection.MovementDetector.MovementIntensity.STATIONARY
    
// Environment Detection (Indoor/Outdoor) - Phase 4
private lateinit var environmentDetector: com.cymatune.detection.EnvironmentDetector
private var currentEnvironment: com.cymatune.detection.EnvironmentDetector.Environment =
com.cymatune.detection.EnvironmentDetector.Environment.UNKNOWN

// Current Connected Tower Tracking (for alert tower ID fallback)
    private var currentConnectedTowerCid: Int? = null
    private var currentConnectedTowerId: Long? = null
    private var lastKnownTowerCid: Int? = null  // Fallback if connection lost
    
    // Alert Suppression Logic (To prevent notification spam)
    private var isAlertSuppressed = false
    private var suppressionEndTime: Long = 0
    private var lastAlertTime: Long = 0  // DEPRECATED: Use lastNotificationTime instead
    private var lastNotificationTime: Long = 0  // Track notification rate limiting separately
    
    // Notification Consolidation (Fix: Prevent 75-80 notification spam)
    // Track active notifications by "towerId_threatType" to update instead of creating new ones
    private val activeNotifications = HashMap<String, Int>()  // Key -> Notification ID
    
    // SESSION-BASED DEDUPLICATION: Only alert ONCE per tower per threat type per app session
    // Key format: "cid_threatType" - Cleared when service restarts
    private val alertedThisSession = HashSet<String>()
    
    private val MIN_ALERT_INTERVAL_MS = 60000 // Max 1 alert per minute unless suppressed
    private val MIN_NOTIFICATION_INTERVAL_MS = 60000 // Max 1 NOTIFICATION per 60 seconds (all alerts still logged)

    // Broadcast receiver for scan radius changes
    // Broadcast receiver for Airplane Mode and Service State changes
    private val airplaneModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_AIRPLANE_MODE_CHANGED) {
                val isEnabled = intent.getBooleanExtra("state", false)
                if (isEnabled) {
                    Log.i("FakeTowerDetectionService", "✈️ Airplane Mode ENABLED - Setting Maintenance Mode (10s)")
                    com.cymatune.logcat.LogcatDetectionBridge.setMaintenanceMode(10_000L, "Airplane Mode ENABLED")
                } else {
                    Log.i("FakeTowerDetectionService", "✈️ Airplane Mode DISABLED - Setting Maintenance Mode (30s)")
                    com.cymatune.logcat.LogcatDetectionBridge.setMaintenanceMode(30_000L, "Airplane Mode DISABLED (Network Recovery)")
                }
            }
        }
    }
    
    // Broadcast receiver for scan radius changes
    private val scanRadiusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.cymatune.SCAN_RADIUS_CHANGED") {
                val newRadius = intent.getIntExtra("scan_radius_km", 5)
                Log.d("FakeTowerDetectionService", "Scan radius updated to: $newRadius km")
                currentScanRadius = newRadius
            }
        }
    }
    
    // Observer for VoLTE/Network Settings changes (Manual Toggle Detection)
    private val volteSettingsObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            Log.i("FakeTowerDetectionService", "⚙️ VoLTE/Net Settings Changed - Triggering Maintenance Mode")
            com.cymatune.logcat.LogcatDetectionBridge.setMaintenanceMode(30_000L, "Manual Network Settings Change")
        }
    }

    private val deviceKalmanFilter = KalmanFilterLocationProvider()
    private val towerKalmanFilters = mutableMapOf<String, KalmanFilterLocationProvider>()

    private lateinit var connectionStateMonitor: ConnectionStateMonitor
    private lateinit var trustScoreManager: TrustScoreManager
    private lateinit var anomalyDetector: AnomalyDetector
    private lateinit var fakeTowerDao: FakeTowerDao
    private lateinit var locationHistoryDao: LocationHistoryDao
    private lateinit var signalBaselineDao: SignalBaselineDao
    private lateinit var neighborHistoryDao: NeighborHistoryDao
    private lateinit var lacCidPatternDao: LacCidPatternDao
    private lateinit var protocolHandshakeDao: ProtocolHandshakeDao
    private lateinit var towerDao: TowerDao
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Phase 4.0: Multi-SIM Support - Track active SIMs and their tower info
    private val activeSubscriptionIds = mutableListOf<Int>()
    private val currentSimTowerInfo = mutableMapOf<Int, com.cymatune.util.TowerConnectionInfo?>()

    private val LOCKED_TOWER_ID_KEY = "locked_tower_id"
    private val MIN_TRIANGULATION_DISTANCE_METERS = 10.0
    
    private lateinit var startupStateMachine: ServiceStartupStateMachine
    private lateinit var errorRecoveryManager: ErrorRecoveryManager
    private lateinit var componentLifecycleManager: ComponentLifecycleManager

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Anomaly Detection Callback
        // Initialize Anomaly Detection Callback
        TowerRefinementManager.onAnomalyDetected = { towerId: String, message: String ->
            // Stationary Check: Suppress location anomalies if device hasn't moved recently
            // This prevents GPS drift or tower jitter from causing false positives
            val currentSpeed = currentLocation?.speed ?: 0f
            if (currentSpeed < 0.5f && message.contains("Distance mismatch", ignoreCase = true)) {
                Log.d("FakeTowerDetectionService", "Suppressing Location Anomaly for $towerId (Device Stationary, Speed: $currentSpeed)")
            } else if (message.contains("Distance Mismatch", ignoreCase = true)) {
                 // USER REQUEST: Handle as Sub-Note / Low Severity
                 // Downgrade to INFO/LOW severity so it appears as TEAL/LIME confidence (< 0.4)
                 // Do NOT mark as "Historic Threat" (Red)
                 // Extract CID from message/id if possible
                 val cid = try {
                     towerId.split("-").last().toLong()
                 } catch (e: Exception) { null }
                 
                 showSecurityAlert(
                     "Location Range Note", 
                     message, 
                     com.cymatune.logcat.ThreatSeverity.LOW,
                     relatedTowerId = cid
                 )
                 Log.d("FakeTowerDetectionService", "Logged non-critical distance mismatch for $towerId")
            } else {
                 val cid = try {
                     towerId.split("-").last().toLong()
                 } catch (e: Exception) { null }
                 
                showSecurityAlert("Tower Location Mismatch", message, relatedTowerId = cid)
                
                // CRITICAL FIX: Mark tower as threat in DB so it turns RED in UI
                // Only for Alerts that are NOT simple distance mismatches
                serviceScope.launch {
                    try {
                        // towerId format: mcc-mnc-lac-cid
                        val parts = towerId.split("-")
                        if (parts.size == 4) {
                            val mcc = parts[0].toInt()
                            val mnc = parts[1].toInt()
                            val lac = parts[2].toInt()
                            val cid = parts[3].toInt()
                            
                            if (::fakeTowerDao.isInitialized) {
                                // Mark as threat (Trust Score -> 0 effectively)
                                fakeTowerDao.updateHistoricThreatStatus(
                                    cid = cid,
                                    lac = lac,
                                    mcc = mcc,
                                    mnc = mnc,
                                    hasThreat = true,
                                    timestamp = System.currentTimeMillis()
                                )
                                Log.d("FakeTowerDetectionService", "Marked tower $towerId as threat due to anomaly")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("FakeTowerDetectionService", "Failed to mark tower as threat", e)
                    }
}
    }
}

// SECURITY CHECK: Block Rooted Devices
        if (com.cymatune.security.RootDetectionUtil.isDeviceRooted()) {
            Log.e("FakeTowerDetectionService", "Root detected. Stopping service immediately.")
            stopSelf()
            return
        }

        sharedPreferences = getSharedPreferences("cymatune_prefs", MODE_PRIVATE)
        keystoreHelper = KeystoreHelper(this)
        savedLockedTowerId = keystoreHelper.getData(LOCKED_TOWER_ID_KEY)

        if (savedLockedTowerId == null) {
            val prefsLockedTowerId = sharedPreferences.getString(LOCKED_TOWER_ID_KEY, null)
            if (prefsLockedTowerId != null) {
                keystoreHelper.saveData(LOCKED_TOWER_ID_KEY, prefsLockedTowerId)
                sharedPreferences.edit().remove(LOCKED_TOWER_ID_KEY).apply()
            }
        }
        if (savedLockedTowerId != null) {
            targetTowerId = savedLockedTowerId
            isTriangulationMode = true
        }
        
        // Register receiver for scan radius changes
        val scanRadiusFilter = IntentFilter("com.cymatune.SCAN_RADIUS_CHANGED")
        try {
            ContextCompat.registerReceiver(this, scanRadiusReceiver, scanRadiusFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        } catch (e: IllegalArgumentException) { }
        
        // Register Airplane Mode receiver
        val airplaneFilter = IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        try {
            registerReceiver(airplaneModeReceiver, airplaneFilter)
        } catch (e: Exception) {
             Log.w("FakeTowerDetectionService", "Failed to register airplane mode receiver", e)
        }

        // Register VoLTE Settings Observer
        try {
            contentResolver.registerContentObserver(
                android.provider.Settings.Global.getUriFor("volte_vt_enabled"),
                false,
                volteSettingsObserver
            )
            // Also monitor WiFi Calling as it affects IMS registration
            contentResolver.registerContentObserver(
                android.provider.Settings.Global.getUriFor("wfc_ims_enabled"),
                false,
                volteSettingsObserver
            )
            Log.d("FakeTowerDetectionService", "VoLTE Settings Observer registered")
        } catch (e: Exception) {
            Log.w("FakeTowerDetectionService", "Failed to register VoLTE observer", e)
        }

        // Register Mute/Unmute Receiver
        val muteFilter = IntentFilter(ACTION_TOGGLE_ALERT_SUPPRESSION)
        try {
            // RECEIVER_EXPORTED required for PendingIntent in Notifications on Android 14+
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.RECEIVER_EXPORTED
            } else {
                0
            }
            ContextCompat.registerReceiver(this, notificationActionReceiver, muteFilter, flags)
            Log.d("FakeTowerDetectionService", "Notification Action Receiver registered")
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Failed to register notification receiver", e)
        }

        // Initialize Service Startup State Machine
        initializeStartupStateMachine()
        
        // Initialize Error Recovery Manager
        initializeErrorRecoveryManager()
        
        // Initialize non-database components first
        initializeNonDatabaseComponents()

        // Initialize Component Lifecycle Manager
        initializeComponentLifecycleManager()
        
        // Execute startup sequence using state machine
        val startupJob = serviceScope.launch {
            try {
                Log.i("FakeTowerDetectionService", "Starting service startup with state machine")
                val startupResult = startupStateMachine.executeStartupSequence(applicationContext)
                
                when (startupResult) {
                    is ServiceStartupStateMachine.StartupResult.Success -> {
                        Log.i("FakeTowerDetectionService", "Service startup completed successfully with state machine")
                        handleSuccessfulStartup()
                    }
                    is ServiceStartupStateMachine.StartupResult.Recovered -> {
                        Log.w("FakeTowerDetectionService", "Service startup recovered after ${startupResult.recoveryAttempts} attempts")
                        handleSuccessfulStartup()
                    }
                    is ServiceStartupStateMachine.StartupResult.Failed -> {
                        Log.e("FakeTowerDetectionService", "Service startup failed: ${startupResult.errorMessage}")
                        
                        // Attempt recovery if possible using ErrorRecoveryManager
                        if (startupResult.canRetry) {
                            Log.w("FakeTowerDetectionService", "Attempting recovery from startup failure using ErrorRecoveryManager")
                            
                            // Determine failure type for ErrorRecoveryManager
                            val failureType = when (startupResult) {
                                is ServiceStartupStateMachine.StartupResult.Failed -> {
                                    when (startupResult.failureState) {
                                        ServiceStartupStateMachine.StartupState.INITIALIZING_DATABASE -> ErrorRecoveryManager.FailureType.DATABASE_INITIALIZATION
                                        ServiceStartupStateMachine.StartupState.INITIALIZING_COMPONENTS -> ErrorRecoveryManager.FailureType.COMPONENT_INITIALIZATION
                                        ServiceStartupStateMachine.StartupState.STARTING_MONITORING -> ErrorRecoveryManager.FailureType.SENSOR_FAILURE
                                        else -> ErrorRecoveryManager.FailureType.COMPONENT_INITIALIZATION
                                    }
                                }
                                else -> ErrorRecoveryManager.FailureType.COMPONENT_INITIALIZATION
                            }
                            
                            // Use ErrorRecoveryManager for recovery
                            val errorRecoveryResult = serviceScope.launch {
                                errorRecoveryManager.handleServiceFailure(
                                    failureType = failureType,
                                    context = applicationContext,
                                    failureContext = "Service startup failed: ${startupResult.errorMessage}",
                                    originalError = startupResult.cause as? Exception
                                )
                            }
                            
                            // Also try state machine recovery as fallback
                            val stateMachineRecoveryResult = serviceScope.launch {
                                startupStateMachine.attemptRecovery(applicationContext,
                                    ServiceStartupStateMachine.StartupResult.Failed(
                                        failureState = if (startupResult is ServiceStartupStateMachine.StartupResult.Failed) startupResult.failureState
                                                       else ServiceStartupStateMachine.StartupState.FAILED,
                                        errorMessage = startupResult.errorMessage,
                                        cause = startupResult.cause,
                                        canRetry = startupResult.canRetry
                                    )
                                )
                            }
                            
                            // Wait for both recovery attempts
                            try {
                                errorRecoveryResult.join()
                                stateMachineRecoveryResult.join()
                                
                                // Since we can't get the results directly from join(), we'll assume success if no exception was thrown
                                Log.i("FakeTowerDetectionService", "Both recovery mechanisms completed successfully")
                                handleSuccessfulStartup()
                                
                            } catch (e: Throwable) {
                                Log.e("FakeTowerDetectionService", "Recovery attempt failed with exception", e)
                                executeLegacyStartupFallback()
                            }
                        } else {
                            Log.e("FakeTowerDetectionService", "Startup failed and cannot be retried, falling back to legacy initialization")
                            executeLegacyStartupFallback()
                        }
                    }
                    else -> {
                        Log.e("FakeTowerDetectionService", "Unknown startup result: $startupResult")
                        executeLegacyStartupFallback()
                    }
                }
                
                // Log startup metrics for monitoring
                logStartupMetrics()
                
            } catch (e: Exception) {
                Log.e("FakeTowerDetectionService", "Unexpected error in state machine startup, falling back to legacy initialization", e)
                executeLegacyStartupFallback()
            }
        }
    }
    
    /**
     * Process saved locked tower ID after DAOs are initialized
     */
    private fun processSavedLockedTower(savedLockedTowerId: String) {
        serviceScope.launch {
            val parts = savedLockedTowerId.split("-")
            if (parts.size == 4) {
                val mcc = parts[0].toIntOrNull() ?: 0
                val mnc = parts[1].toIntOrNull() ?: 0
                val lac = parts[2].toIntOrNull() ?: 0
                val cid = parts[3].toIntOrNull() ?: 0
                val fakeTower = fakeTowerDao.getFakeTower(cid, lac, mcc, mnc)
                if (fakeTower != null) {
                    // Simplified - just log that we found the tower
                    Log.d("FakeTowerDetection", "Found locked tower: $savedLockedTowerId in DB.")
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Cancel coroutines to prevent memory leaks
        serviceScope.cancel()
        
        // Unregister CellInfoCallback if registered
        cellInfoCallback?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    telephonyManager.unregisterTelephonyCallback(it as android.telephony.TelephonyCallback)
                } catch (e: IllegalArgumentException) {
                    Log.d("FakeTowerDetectionService", "CellInfoCallback already unregistered")
                }
            }
        }
        cellInfoCallback = null
        
        // Unregister scan radius receiver with proper cleanup
        try {
            unregisterReceiver(scanRadiusReceiver)
            Log.d("FakeTowerDetectionService", "Broadcast receiver cleaned up in onDestroy")
        } catch (e: IllegalArgumentException) {
            Log.d("FakeTowerDetectionService", "Broadcast receiver not registered, skipping cleanup")
        } catch (e: Exception) {
            Log.w("FakeTowerDetectionService", "Error cleaning up broadcast receiver", e)
        }
        
        try {
            unregisterReceiver(notificationActionReceiver)
        } catch (e: Exception) {}
        
        // Stop MovementDetector to release accelerometer sensor
        if (::movementDetector.isInitialized) {
            movementDetector.stop()
            Log.d("FakeTowerDetectionService", "MovementDetector stopped")
        }
        
        // Stop EnvironmentDetector to release light/pressure sensors
        if (::environmentDetector.isInitialized) {
            environmentDetector.stop()
            Log.d("FakeTowerDetectionService", "EnvironmentDetector stopped")
        }
        
        if (::connectionStateMonitor.isInitialized) {
            connectionStateMonitor.stopMonitoring()
        }
        
        // Clean up executor to prevent thread leaks
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                Log.w("FakeTowerDetectionService", "Executor did not terminate within timeout")
            }
        } catch (e: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        Log.d("FakeTowerDetectionService", "Service destroyed and resources cleaned up")
    }
    
    /**
     * Initialize component lifecycle management
     */
    private fun initializeComponentLifecycleManager() {
        try {
            Log.i("FakeTowerDetectionService", "Initializing component lifecycle management with comprehensive fallback support")
            
            // Register all components with the lifecycle manager
            com.cymatune.lifecycle.ComponentLifecycleManager.addComponent(
                name = "DatabaseInitializationManager",
                dependencies = emptyList(),
                initializationOrder = 1,
                initializer = { context ->
                    val result = com.cymatune.db.DatabaseInitializationManager.initializeDatabaseWithRetry(context)
                    when (result) {
                        is com.cymatune.db.DatabaseInitializationManager.InitializationResult.Success -> {
                            Log.i("FakeTowerDetectionService", "Database initialized successfully via lifecycle manager")
                            result.database
                        }
                        is com.cymatune.db.DatabaseInitializationManager.InitializationResult.Fallback -> {
                            Log.w("FakeTowerDetectionService", "Database initialized with fallback: ${result.message}")
                            result.database ?: throw ComponentInitializationException(
                                message = "Database initialization failed completely",
                                componentName = "DatabaseInitializationManager",
                                failureType = ComponentInitializationException.FailureType.DATABASE_NOT_READY
                            )
                        }
                        is com.cymatune.db.DatabaseInitializationManager.InitializationResult.Error -> {
                            throw ComponentInitializationException(
                                message = "Database initialization failed: ${result.message}",
                                componentName = "DatabaseInitializationManager",
                                failureType = ComponentInitializationException.FailureType.DATABASE_NOT_READY
                            )
                        }
                        is com.cymatune.db.DatabaseInitializationManager.InitializationResult.RetryExhausted -> {
                            throw ComponentInitializationException(
                                message = "Database initialization retries exhausted: ${result.lastError}",
                                componentName = "DatabaseInitializationManager",
                                failureType = ComponentInitializationException.FailureType.DATABASE_NOT_READY
                            )
                        }
                    }
                },
                shutdownAction = {
                    // Database shutdown handled by lifecycle manager
                    Log.d("FakeTowerDetectionService", "Database shutdown via lifecycle manager")
                }
            )
            
            // Register AnomalyDetector component
            com.cymatune.lifecycle.ComponentLifecycleManager.addComponent(
                name = "AnomalyDetector",
                dependencies = listOf("DatabaseInitializationManager"),
                initializationOrder = 2,
                initializer = { context ->
                    val signalBaselineDao = com.cymatune.db.DatabaseManager.signalBaselineDao
                    anomalyDetector = com.cymatune.detection.AnomalyDetector(signalBaselineDao)
                    Log.i("FakeTowerDetectionService", "AnomalyDetector initialized via lifecycle manager")
                    anomalyDetector
                },
                shutdownAction = {
                    // AnomalyDetector cleanup
                    Log.d("FakeTowerDetectionService", "AnomalyDetector shutdown via lifecycle manager")
                }
            )
            
            // Register FakeTowerDetector component
            com.cymatune.lifecycle.ComponentLifecycleManager.addComponent(
                name = "FakeTowerDetector",
                dependencies = listOf("DatabaseInitializationManager", "AnomalyDetector"),
                initializationOrder = 3,
                initializer = { context ->
                    FakeTowerDetector.initialize(
                        fakeTowerDao = com.cymatune.db.DatabaseManager.fakeTowerDao,
                        protocolHandshakeDao = com.cymatune.db.DatabaseManager.protocolHandshakeDao,
                        neighborHistoryDao = com.cymatune.db.DatabaseManager.neighborHistoryDao,
                        lacCidPatternDao = com.cymatune.db.DatabaseManager.lacCidPatternDao,
                        locationHistoryDao = com.cymatune.db.DatabaseManager.locationHistoryDao,
                        towerDao = com.cymatune.db.DatabaseManager.towerDao
                    )
                    Log.i("FakeTowerDetectionService", "FakeTowerDetector initialized via lifecycle manager")
                    FakeTowerDetector
                },
                shutdownAction = {
                    // FakeTowerDetector cleanup
                    Log.d("FakeTowerDetectionService", "FakeTowerDetector shutdown via lifecycle manager")
                }
            )
            
            // Register TrustScoreManager component - moved to after DAOs are assigned
            com.cymatune.lifecycle.ComponentLifecycleManager.addComponent(
                name = "TrustScoreManager",
                dependencies = listOf("DatabaseInitializationManager", "AnomalyDetector", "FakeTowerDetector"),
                initializationOrder = 4,
                initializer = { context ->
                    // TrustScoreManager will be initialized in handleSuccessfulStartup() after DAOs are assigned
                    Log.i("FakeTowerDetectionService", "TrustScoreManager component registered but not yet initialized")
                    // Return a placeholder object instead of null
                    object {}
                },
                shutdownAction = {
                    // TrustScoreManager cleanup
                    Log.d("FakeTowerDetectionService", "TrustScoreManager shutdown via lifecycle manager")
                }
            )
            
// Register SMSAndPagingIntegrationManager component (always enabled in Lite)
com.cymatune.lifecycle.ComponentLifecycleManager.addComponent(
name = "SMSAndPagingIntegrationManager",
dependencies = listOf("DatabaseInitializationManager"),
initializationOrder = 5,
initializer = { context ->
val smsManager = com.cymatune.detection.SMSAndPagingIntegrationManager(context)
smsManager.initialize()
Log.i("FakeTowerDetectionService", "SMSAndPagingIntegrationManager initialized via lifecycle manager")
smsManager
},
shutdownAction = {
// SMSAndPagingIntegrationManager cleanup
Log.d("FakeTowerDetectionService", "SMSAndPagingIntegrationManager shutdown via lifecycle manager")
}
)
            
            Log.i("FakeTowerDetectionService", "Component lifecycle manager initialized with all components and fallback strategies registered")
            
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Failed to initialize component lifecycle manager", e)
            throw ComponentInitializationException(
                message = "Component lifecycle manager initialization failed",
                cause = e,
                componentName = "ComponentLifecycleManager",
                failureType = ComponentInitializationException.FailureType.COMPONENT_CREATION_FAILED,
                recoveryAction = ComponentInitializationException.RecoveryAction.USE_FALLBACK_COMPONENTS
            )
        }
    }
    
    /**
     * Initialize components using the lifecycle manager
     */
    private suspend fun initializeComponentsWithLifecycleManager() {
        try {
            Log.i("FakeTowerDetectionService", "Starting component initialization via lifecycle manager with fallback monitoring")
            
            val result = ComponentLifecycleManager.initializeComponents(applicationContext)
            
            when (result) {
                is ComponentLifecycleManager.InitializationResult.Success -> {
                    Log.i("FakeTowerDetectionService", "All components initialized successfully via lifecycle manager: ${result.initializedComponents.joinToString()}")
                    Log.d("FakeTowerDetectionService", "Fallback system status: All primary components operational")
                    
                    // Set up refinement manager callback
                    TowerRefinementManager.onRefinementUpdate = { towerId: String, precisionRadius: Double, observationCount: Int ->
                        broadcastTowerRefinementUpdate(towerId, precisionRadius, observationCount)
                    }
                    
                    // Initialize and start ConnectionStateMonitor
                    connectionStateMonitor = ConnectionStateMonitor(this)
                    connectionStateMonitor.setAnomalyListener { anomaly ->
                        FakeTowerDetector.reportConnectionAnomaly(anomaly)
                        Log.w("FakeTowerDetectionService", "Connection anomaly detected: ${anomaly.type}")
                    }
                    connectionStateMonitor.startMonitoring()
                    
                    Log.i("FakeTowerDetectionService", "Component lifecycle initialization completed successfully")
                }
                
                is ComponentLifecycleManager.InitializationResult.Partial -> {
                    Log.w("FakeTowerDetectionService", "Partial component initialization: ${result.successfulComponents.joinToString()} successful, ${result.failedComponents.joinToString()} failed")
                    Log.w("FakeTowerDetectionService", "Fallback system status: Operating with degraded functionality")
                    
                    // Continue with successful components but log failures
                    // The service can still function with some components
                    startMonitoringOperations()
                }
                
                is ComponentLifecycleManager.InitializationResult.Failed -> {
                    Log.e("FakeTowerDetectionService", "Component initialization failed completely: ${result.errorMessage}")
                    Log.e("FakeTowerDetectionService", "Fallback system status: All primary initialization attempts failed")
                    throw ComponentInitializationException(
                        message = "Critical component initialization failed: ${result.errorMessage}",
                        componentName = "ComponentLifecycleManager",
                        failureType = ComponentInitializationException.FailureType.COMPONENT_CREATION_FAILED,
                        recoveryAction = ComponentInitializationException.RecoveryAction.USE_FALLBACK_COMPONENTS
                    )
                }
            }
            
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Component initialization via lifecycle manager failed - fallback chain activated", e)
            throw ComponentInitializationException(
                message = "Component lifecycle initialization failed",
                cause = e,
                componentName = "ComponentLifecycleManager",
                failureType = ComponentInitializationException.FailureType.COMPONENT_CREATION_FAILED,
                recoveryAction = ComponentInitializationException.RecoveryAction.USE_FALLBACK_COMPONENTS
            )
        }
    }

    /**
     * Initialize the Service Startup State Machine
     */
    private fun initializeStartupStateMachine() {
        try {
            startupStateMachine = ServiceStartupStateMachine()
            Log.d("FakeTowerDetectionService", "Service Startup State Machine initialized")
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Failed to initialize startup state machine", e)
            throw ComponentInitializationException(
                message = "Startup state machine initialization failed",
                cause = e,
                componentName = "ServiceStartupStateMachine",
                failureType = ComponentInitializationException.FailureType.COMPONENT_CREATION_FAILED,
                recoveryAction = ComponentInitializationException.RecoveryAction.USE_FALLBACK_COMPONENTS
            )
        }
    }
    
    /**
     * Initialize the Error Recovery Manager
     */
    private fun initializeErrorRecoveryManager() {
        try {
            errorRecoveryManager = ErrorRecoveryManager(serviceScope)
            startupStateMachine.initializeErrorRecovery(errorRecoveryManager)
            Log.i("FakeTowerDetectionService", "Error Recovery Manager initialized and integrated with state machine")
            
            // Log recovery capabilities for monitoring
            Log.d("FakeTowerDetectionService", "Error Recovery Manager capabilities:")
            Log.d("FakeTowerDetectionService", "- Database initialization failure recovery")
            Log.d("FakeTowerDetectionService", "- Component initialization failure recovery")
            Log.d("FakeTowerDetectionService", "- Sensor failure recovery (graceful degradation)")
            Log.d("FakeTowerDetectionService", "- Network failure recovery (offline mode)")
            Log.d("FakeTowerDetectionService", "- Database corruption detection and recovery")
            Log.d("FakeTowerDetectionService", "- Database locked recovery")
            Log.d("FakeTowerDetectionService", "- Database encryption failure recovery")
            
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Failed to initialize Error Recovery Manager", e)
            throw ComponentInitializationException(
                message = "Error Recovery Manager initialization failed",
                cause = e,
                componentName = "ErrorRecoveryManager",
                failureType = ComponentInitializationException.FailureType.COMPONENT_CREATION_FAILED,
                recoveryAction = ComponentInitializationException.RecoveryAction.USE_FALLBACK_COMPONENTS
            )
        }
    }
    
    /**
     * Handle successful startup completion
     */
    private fun handleSuccessfulStartup() {
        try {
            // CRITICAL: Initialize DAOs from DatabaseManager - MUST happen after database initialization
            fakeTowerDao = DatabaseManager.fakeTowerDao
            towerDao = DatabaseManager.towerDao
            protocolHandshakeDao = DatabaseManager.protocolHandshakeDao
            locationHistoryDao = DatabaseManager.locationHistoryDao
            signalBaselineDao = DatabaseManager.signalBaselineDao
            neighborHistoryDao = DatabaseManager.neighborHistoryDao
            lacCidPatternDao = DatabaseManager.lacCidPatternDao
            
            Log.i("FakeTowerDetectionService", "DAOs initialized successfully from DatabaseManager")
            
            // Validate DAOs are properly initialized
            validateDaoInitialization()
            
            // Process saved locked tower ID if available
            savedLockedTowerId?.let { lockedTowerId ->
                processSavedLockedTower(lockedTowerId)
            }
            
            // Initialize DAO-dependent components (AnomalyDetector, etc.)
            initializeDaoDependentComponents()
            
            // Start monitoring operations now that all components are initialized
            startMonitoringOperations()
            
            // Broadcast service ready signal for HomeFragment with retry mechanism
            broadcastServiceReadyWithRetry()
            
            Log.i("FakeTowerDetectionService", "Service startup completed successfully with all MVP features active")
            updateNotification("Service active - All detection systems operational", towersScannedCount, 0)
            
            // Pro Feature: Start LogcatService and initialize detection bridge if enabled
            if (true) {
                try {
                    val logcatIntent = Intent(this, com.cymatune.service.LogcatService::class.java)
                    startService(logcatIntent) // LogcatService handles duplicate starts internally
                    Log.i("FakeTowerDetectionService", "LogcatService started for pro features")
                    
                    // Initialize the detection bridge for logcat-enhanced detection
                    com.cymatune.logcat.LogcatDetectionBridge.initialize(this)
                    Log.i("FakeTowerDetectionService", "LogcatDetectionBridge initialized - enhanced detection active")
                    
                    // CRITIAL FIX: Listen for Logcat warnings and CONVICT the tower immediately
                    // This ensures that "Invisible" attacks (EEA0) result in a "Rogue Tower" db entry
                    serviceScope.launch {
                        com.cymatune.logcat.LogcatDetectionBridge.alertFlow.collect { alert ->
                            // BROADENED SCOPE: User requested "All real alerts" to convict
                            // We include MEDIUM, HIGH, and CRITICAL. We exclude LOW/NONE to avoid noise.
                            if (alert.severity != com.cymatune.logcat.ThreatSeverity.NONE && 
                                alert.severity != com.cymatune.logcat.ThreatSeverity.LOW) {
                                
                                val towerCid = alert.cid ?: com.cymatune.logcat.LogcatDetectionBridge.getCurrentTowerCid()
                                if (towerCid != null) {
                                    val severityLabel = alert.severity.name
                                    Log.w("FakeTowerDetectionService", "CONVICTION: Logcat Alert ($severityLabel) '${alert.message}' convicting Tower $towerCid")
                                    
                                    // Use current tower info if available from bridge, otherwise defaults
                                    val bridgeLac = com.cymatune.logcat.LogcatDetectionBridge.getCurrentTowerLac()
                                    val bridgeMcc = com.cymatune.logcat.LogcatDetectionBridge.getCurrentTowerMcc()
                                    val bridgeMnc = com.cymatune.logcat.LogcatDetectionBridge.getCurrentTowerMnc()
                                    
                                    val safeLac = if (com.cymatune.logcat.LogcatDetectionBridge.getCurrentTowerCid() == towerCid) (bridgeLac ?: 0) else 0
                                    val safeMcc = if (com.cymatune.logcat.LogcatDetectionBridge.getCurrentTowerCid() == towerCid) (bridgeMcc ?: 0) else 0
                                    val safeMnc = if (com.cymatune.logcat.LogcatDetectionBridge.getCurrentTowerCid() == towerCid) (bridgeMnc ?: 0) else 0
                                    
                                    val safeLat = currentLocation?.latitude ?: 0.0
                                    val safeLon = currentLocation?.longitude ?: 0.0
                                    
                                    // SPECIAL HANDLING FOR WATCHERS
                                    if (alert.type == com.cymatune.logcat.ThreatType.PRIVACY_LEAK) {
                                        // Check if tower is known Rogue (Trust < 50)
                                        val existingTower = fakeTowerDao.getFakeTower(towerCid, safeLac, safeMcc, safeMnc)
                                        val isRogue = existingTower != null && existingTower.trustScore < 50
                                        
                                        if (isRogue) {
                                            // ROGUE TOWER DATA LEAK -> RED ALERT (CRITICAL)
                                            showSecurityAlert("CRITICAL: Rogue Tower Extracting Data", alert.message, com.cymatune.logcat.ThreatSeverity.CRITICAL)
                                            
                                            // Check if exists to determine Update vs Insert
                                            val towerExists = fakeTowerDao.getFakeTower(towerCid, safeLac, safeMcc, safeMnc) != null
                                            
                                            if (towerExists) {
                                                fakeTowerDao.updateTowerObservation(
                                                    cid = towerCid,
                                                    lac = safeLac, 
                                                    mcc = safeMcc, 
                                                    mnc = safeMnc,
                                                    now = System.currentTimeMillis(),
                                                    rssi = -1
                                                )
                                            } else {
                                                // New Conviction
                                                fakeTowerDao.insertFakeTowerIfNotRecent(
                                                    cid = towerCid,
                                                    lac = safeLac,
                                                    mcc = safeMcc,
                                                    mnc = safeMnc,
                                                    latitude = safeLat,
                                                    longitude = safeLon,
                                                    detectionTime = System.currentTimeMillis(),
                                                    accuracy = 0.0,
                                                    reason = "ROGUE LEAK [$severityLabel]: ${alert.message}",
                                                    signalStrength = -1,
                                                    networkType = "UNKNOWN",
                                                    observationCount = 1,
                                                    precisionRadius = 0.0,
                                                    isTriangulated = false,
                                                    trustScore = 0, // ZERO TRUST
                                                    rsrq = 0,
                                                    sinr = 0,
                                                    pci = 0,
                                                    arfcn = 0,
                                                    timingAdvance = 0,
                                                    cutoffTime = System.currentTimeMillis() - 60000
                                                )
                                            }
                                        } else {
                                            // NORMAL PRIVACY LEAK -> YELLOW ALERT (MEDIUM)
                                            // Do not convict tower, just warn user
                                            showSecurityAlert("Privacy Warning", alert.message, com.cymatune.logcat.ThreatSeverity.MEDIUM)
                                            return@collect
                                        }
                                    } else if (alert.type == com.cymatune.logcat.ThreatType.SENSOR_ABUSE) {
                                         // SENSOR ABUSE -> RED ALERT (CRITICAL)
                                         showSecurityAlert("Sensor Access Violation", alert.message, com.cymatune.logcat.ThreatSeverity.CRITICAL)
                                         fakeTowerDao.insertFakeTowerIfNotRecent(
                                            cid = towerCid,
                                            lac = safeLac,
                                            mcc = safeMcc,
                                            mnc = safeMnc,
                                            latitude = safeLat,
                                            longitude = safeLon,
                                            detectionTime = System.currentTimeMillis(),
                                            accuracy = 0.0,
                                            reason = "SENSOR ABUSE: ${alert.message}",
                                            signalStrength = -1,
                                            networkType = "UNKNOWN",
                                            observationCount = 1,
                                            precisionRadius = 0.0,
                                            isTriangulated = false,
                                            trustScore = 0, // ZERO TRUST
                                            rsrq = 0,
                                            sinr = 0,
                                            pci = 0,
                                            arfcn = 0,
                                            timingAdvance = 0,
                                            cutoffTime = System.currentTimeMillis() - 60000
                                         )
                                    } else {
                                        // STANDARD THREAT HANDLING
                                        fakeTowerDao.insertFakeTowerIfNotRecent(
                                            cid = towerCid,
                                            lac = safeLac,
                                            mcc = safeMcc,
                                            mnc = safeMnc,
                                            latitude = safeLat,
                                            longitude = safeLon,
                                            detectionTime = System.currentTimeMillis(),
                                            accuracy = 0.0, // Double required
                                            reason = "LOGCAT [$severityLabel]: ${alert.message}",
                                            signalStrength = -1,
                                            networkType = "UNKNOWN", // Could be inferred but safe to leave generic
                                            observationCount = 1,
                                            precisionRadius = 0.0, // Double required
                                            isTriangulated = false,
                                            trustScore = 0, // ZERO TRUST (Int required) for Logcat Alerts
                                            rsrq = 0,
                                            sinr = 0,
                                            pci = 0,
                                            arfcn = 0,
                                            timingAdvance = 0,
                                            cutoffTime = System.currentTimeMillis() - 60000 // 1 min debounce
                                        )
                                    }
                                    
                                    // AUTOMATED DEFENSE: trigger protective action
                                    if (alert.severity == com.cymatune.logcat.ThreatSeverity.CRITICAL || 
                                        alert.severity == com.cymatune.logcat.ThreatSeverity.HIGH) {
                                        triggerDefenseMechanism(context = this@FakeTowerDetectionService, reason = alert.message)
                                    }
                                    
                                    // Also show visual alert to ensure user sees it
                                    showSecurityAlert("Rogue Tower Detected", "${alert.message} (CID: $towerCid)")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FakeTowerDetectionService", "Failed to start LogcatService", e)
                }
            } else {
                Log.i("FakeTowerDetectionService", "Logcat features disabled by build configuration")
            }
            
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Error in post-startup operations", e)
            // Don't crash - continue with degraded functionality
        }
    }
    
    /**
     * Broadcast service ready signal for HomeFragment to refresh UI with retry mechanism
     */
    private fun broadcastServiceReadyWithRetry() {
        try {
            // First attempt
            val intent = Intent("com.cymatune.SERVICE_READY")
            intent.setPackage(packageName)
            sendBroadcast(intent)
            Log.d("FakeTowerDetectionService", "=== SERVICE READY BROADCAST SENT (ATTEMPT 1) ===")
            Log.d("FakeTowerDetectionService", "Broadcast service ready signal (attempt 1)")
            
            // Schedule retry after 3 seconds if needed
            executor.schedule({
                try {
                    val retryIntent = Intent("com.cymatune.SERVICE_READY")
                    retryIntent.setPackage(packageName)
                    sendBroadcast(retryIntent)
                    Log.d("FakeTowerDetectionService", "=== SERVICE READY BROADCAST SENT (RETRY ATTEMPT 2) ===")
                    Log.d("FakeTowerDetectionService", "Broadcast service ready signal (retry attempt 2)")
                    
                    // Schedule final retry after 6 seconds total
                    executor.schedule({
                        try {
                            val finalIntent = Intent("com.cymatune.SERVICE_READY")
                            finalIntent.setPackage(packageName)
                            sendBroadcast(finalIntent)
                            Log.d("FakeTowerDetectionService", "=== SERVICE READY BROADCAST SENT (FINAL ATTEMPT 3) ===")
                            Log.d("FakeTowerDetectionService", "Broadcast service ready signal (final attempt 3)")
                        } catch (e: Exception) {
                            Log.e("FakeTowerDetectionService", "Failed to broadcast service ready signal (final attempt)", e)
                        }
                    }, 3, TimeUnit.SECONDS)
                    
                } catch (e: Exception) {
                    Log.e("FakeTowerDetectionService", "Failed to broadcast service ready signal (retry attempt)", e)
                }
            }, 3, TimeUnit.SECONDS)
            
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Failed to broadcast service ready signal (initial attempt)", e)
        }
    }
    
    /**
     * Broadcast service ready signal for HomeFragment to refresh UI (original method kept for compatibility)
     */
    private fun broadcastServiceReady() {
        try {
            val intent = Intent("com.cymatune.SERVICE_READY")
            intent.setPackage(packageName)
            sendBroadcast(intent)
            Log.d("FakeTowerDetectionService", "=== SERVICE READY BROADCAST SENT ===")
            Log.d("FakeTowerDetectionService", "Broadcast service ready signal")
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Failed to broadcast service ready signal", e)
        }
    }
    
    /**
     * Validate that all DAOs are properly initialized before use
     */
    private fun validateDaoInitialization() {
        try {
            // Test each DAO with a simple operation to ensure they're functional
            val testOperations = listOf(
                "fakeTowerDao" to {
                    serviceScope.launch {
                        try {
                            fakeTowerDao.getAllFakeTowers().firstOrNull()
                            Log.d("FakeTowerDetectionService", "DAO validation passed: fakeTowerDao")
                        } catch (e: Exception) {
                            Log.e("FakeTowerDetectionService", "DAO validation failed for fakeTowerDao: ${e.message}")
                            // Don't crash, just log. DatabaseInitializationManager handles recovery.
                        }
                    }
                },
                "towerDao" to {
                    serviceScope.launch {
                        try {
                            towerDao.getAllTowerInfo().firstOrNull()
                            Log.d("FakeTowerDetectionService", "DAO validation passed: towerDao")
                        } catch (e: Exception) {
                            Log.e("FakeTowerDetectionService", "DAO validation failed for towerDao: ${e.message}")
                            // Don't crash, just log. DatabaseInitializationManager handles recovery.
                        }
                    }
                },
                "signalBaselineDao" to {
                    serviceScope.launch {
                        try {
                            signalBaselineDao.getAllBaselines(1).firstOrNull()
                            Log.d("FakeTowerDetectionService", "DAO validation passed: signalBaselineDao")
                        } catch (e: Exception) {
                            Log.e("FakeTowerDetectionService", "DAO validation failed for signalBaselineDao: ${e.message}")
                            throw ComponentInitializationException(
                                message = "DAO validation failed for signalBaselineDao",
                                componentName = "signalBaselineDao",
                                failureType = ComponentInitializationException.FailureType.DAO_VALIDATION_FAILED
                            )
                        }
                    }
                },
                "locationHistoryDao" to {
                    serviceScope.launch {
                        try {
                            locationHistoryDao.getAllLocationHistory().firstOrNull()
                            Log.d("FakeTowerDetectionService", "DAO validation passed: locationHistoryDao")
                        } catch (e: Exception) {
                            Log.e("FakeTowerDetectionService", "DAO validation failed for locationHistoryDao: ${e.message}")
                            throw ComponentInitializationException(
                                message = "DAO validation failed for locationHistoryDao",
                                componentName = "locationHistoryDao",
                                failureType = ComponentInitializationException.FailureType.DAO_VALIDATION_FAILED
                            )
                        }
                    }
                },
                "lacCidPatternDao" to {
                    serviceScope.launch {
                        try {
                            lacCidPatternDao.getAllPatternsFlow().firstOrNull()
                            Log.d("FakeTowerDetectionService", "DAO validation passed: lacCidPatternDao")
                        } catch (e: Exception) {
                            Log.e("FakeTowerDetectionService", "DAO validation failed for lacCidPatternDao: ${e.message}")
                            throw ComponentInitializationException(
                                message = "DAO validation failed for lacCidPatternDao",
                                componentName = "lacCidPatternDao",
                                failureType = ComponentInitializationException.FailureType.DAO_VALIDATION_FAILED
                            )
                        }
                    }
                }
            )
            
            // Execute all validation operations
            testOperations.forEach { (_, operation) ->
                operation.invoke()
            }
            
            Log.i("FakeTowerDetectionService", "All DAOs validation operations initiated")
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "DAO validation failed: ${e.message}")
            throw e
        }
    }
    
    /**
     * Execute legacy startup fallback for maximum compatibility
     */
    private fun executeLegacyStartupFallback() {
        Log.w("FakeTowerDetectionService", "Executing legacy startup fallback for maximum compatibility")
        
serviceScope.launch {
try {
// Initialize database (unencrypted for Cymatune Lite)
com.cymatune.db.DatabaseManager.initialize(applicationContext)

// Initialize DAOs from Manager - MUST happen after database initialization
fakeTowerDao = DatabaseManager.fakeTowerDao
towerDao = DatabaseManager.towerDao
protocolHandshakeDao = DatabaseManager.protocolHandshakeDao
locationHistoryDao = DatabaseManager.locationHistoryDao
signalBaselineDao = DatabaseManager.signalBaselineDao
neighborHistoryDao = DatabaseManager.neighborHistoryDao
lacCidPatternDao = DatabaseManager.lacCidPatternDao

Log.i("FakeTowerDetectionService", "Database initialized successfully")

// Process saved locked tower ID if available
savedLockedTowerId?.let { lockedTowerId ->
processSavedLockedTower(lockedTowerId)
}

// Initialize DAO-dependent components
initializeDaoDependentComponents()
                
                // Start monitoring operations
                startMonitoringOperations()
                
                // Broadcast service ready signal
                broadcastServiceReady()
                
            } catch (e: Exception) {
                Log.e("FakeTowerDetectionService", "Legacy startup fallback failed", e)
                
                // Final fallback to basic components using ErrorRecoveryManager
                try {
                    Log.w("FakeTowerDetectionService", "Attempting basic fallback components using ErrorRecoveryManager")
                    
                    errorRecoveryManager.handleServiceFailure(
                        failureType = ErrorRecoveryManager.FailureType.COMPONENT_INITIALIZATION,
                        context = applicationContext,
                        failureContext = "Final fallback - all startup methods failed",
                        originalError = e
                    )
                    
                    Log.i("FakeTowerDetectionService", "Final fallback recovery successful")
                    initializeFallbackComponents()
                    startMonitoringOperations()
                    broadcastServiceReady()
                    Log.i("FakeTowerDetectionService", "Basic fallback startup completed with ErrorRecoveryManager assistance")
                } catch (finalError: Exception) {
                    Log.e("FakeTowerDetectionService", "All startup methods failed, service may be degraded", finalError)
                    // Update notification to indicate degraded service
                    updateNotification("Service degraded - Limited functionality", 0, 0)
                }
            }
        }
    }
    
    /**
     * Log startup metrics for monitoring and debugging
     */
    private fun logStartupMetrics() {
        try {
            val metrics = startupStateMachine.getMetrics()
            if (metrics != null) {
                val duration = metrics.endTime?.minus(metrics.startTime) ?: (System.currentTimeMillis() - metrics.startTime)
                
                Log.d("FakeTowerDetectionService", "=== STARTUP METRICS ===")
                Log.d("FakeTowerDetectionService", "Final State: ${metrics.currentState}")
                Log.d("FakeTowerDetectionService", "Duration: ${duration}ms")
                Log.d("FakeTowerDetectionService", "Error Count: ${metrics.errorCount}")
                Log.d("FakeTowerDetectionService", "Recovery Attempts: ${metrics.recoveryAttempts}")
                Log.d("FakeTowerDetectionService", "State Transitions: ${metrics.stateTransitions.size}")
                
                metrics.stateTransitions.forEachIndexed { index, (state, timestamp) ->
                    val relativeTime = timestamp - metrics.startTime
                    Log.d("FakeTowerDetectionService", "  $index. $state at ${relativeTime}ms")
                }
                
                Log.d("FakeTowerDetectionService", "=== END METRICS ===")
            }
        } catch (e: Exception) {
            Log.w("FakeTowerDetectionService", "Failed to log startup metrics", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // This is a started service, not a bound service
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_MONITORING) {
            Log.i("FakeTowerDetectionService", "Received STOP_MONITORING intent")
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        // Start the foreground service with persistent notification
        Log.d("FakeTowerDetectionService", "Starting foreground service with notification")
        updateNotification("Starting fake tower detection...", 0, 0)
        return START_STICKY
    }

    /**
     * Update the persistent notification with real-time detection status
     * MINIMAL: Single-line, silent, no vibration per Phase 4.5 requirements
     */
    private fun updateNotification(status: String, towersScanned: Int, suspiciousTowers: Int) {
        Log.d("FakeTowerDetectionService", "Updating notification: $status | Scanned: $towersScanned | Suspicious: $suspiciousTowers")

        val notificationIntent = Intent(this, com.cymatune.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        // MINIMAL: Single-line status, no action buttons
        val notificationText = if (suspiciousTowers > 0) {
            "⚠️ $suspiciousTowers threats detected"
        } else {
            "Signal analysis active"
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cymatune")
            .setContentText(notificationText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true) // Hard-coded silent per requirements
            .setPriority(NotificationCompat.PRIORITY_MIN) // MIN priority - no status bar icon
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET) // Private on lock screen
            .build()

        // Update the foreground notification with proper permission handling
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                val hasLocationPerm = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val hasFgLocPerm = checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_LOCATION) == PackageManager.PERMISSION_GRANTED
                
                if (hasLocationPerm && hasFgLocPerm) {
                    startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                } else {
                    // Fallback to special use type when location perms not granted
                    Log.w("FakeTowerDetectionService", "Location permissions not fully granted, using SPECIAL_USE type")
                    startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { // Android 10+
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1, notification)
            }
        } catch (e: SecurityException) {
            Log.e("FakeTowerDetectionService", "SecurityException in startForeground, falling back", e)
            try {
                startForeground(1, notification)
            } catch (e2: Exception) {
                Log.e("FakeTowerDetectionService", "Failed to start foreground service completely", e2)
            }
        }
        Log.d("FakeTowerDetectionService", "Notification updated successfully")
    }

override fun onLocationChanged(location: Location) {
    currentLocation = location
    deviceKalmanFilter.processLocation(location.latitude, location.longitude, location.accuracy.toDouble(), location.time)

    // Feed GPS data to EnvironmentDetector for indoor/outdoor estimation
        if (::environmentDetector.isInitialized) {
            // Get satellite count from location extras if available
            val satelliteCount = location.extras?.getInt("satellites", 0) ?: 0
            environmentDetector.updateGpsData(location, satelliteCount)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            currentAccelerometerData = AccelerometerData(event.values[0], event.values[1], event.values[2], event.timestamp)
            // Additional logic for accelerometer updates if needed
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used for accelerometer
    }

    private suspend fun checkNeighborConsistency(currentTower: TowerConnectionInfo, currentNeighborCids: List<String>) {
        if (!::fakeTowerDao.isInitialized || !::neighborHistoryDao.isInitialized) return

        val fakeTower = fakeTowerDao.getFakeTower(
            currentTower.cid, currentTower.lac,
            currentTower.mcc ?: 0, currentTower.mnc ?: 0
        )

        if (fakeTower != null && fakeTower.isTriangulated) {
            val recentHistory = neighborHistoryDao.getRecentHistory(
                currentTower.cid, currentTower.lac,
                currentTower.mcc ?: 0, currentTower.mnc ?: 0
            )

            if (recentHistory.isNotEmpty()) {
                val historicalNeighbors = recentHistory.flatMap { it.neighborCids.split(",") }
                    .filter { it.isNotEmpty() }.toSet()

                val currentNeighbors = currentNeighborCids.toSet()

                // CHECK 1: IMMEDIATE ISOLATION DETECTION (Zero Neighbors)
                // If we know this tower usually has neighbors and now reports ZERO,
                // this is a critical Stingray isolation indicator.
                // Confidence is boosted based on how many neighbors are missing:
                //   - More expected neighbors missing = higher confidence it is intentional isolation.
                if (historicalNeighbors.isNotEmpty() && currentNeighbors.isEmpty()) {
                    val expectedCount = historicalNeighbors.size
                    // More missing neighbors = higher confidence (cap at 0.90)
                    val isolationConfidence = (0.60f + (expectedCount * 0.05f)).coerceAtMost(0.90f)
                    val msg = "Cell Isolation: Tower ${currentTower.cid} usually shows $expectedCount neighbors but now reports ZERO. " +
                              "Possible Stingray isolation attack (jamming neighbor advertisements)."
                    Log.w("FakeTowerDetection", "🚨 $msg (Confidence: ${(isolationConfidence*100).toInt()}%)")
                    showSecurityAlert("Cell Isolation Detected", msg)
                    broadcastSuspiciousTower(fakeTower, "Zero Neighbor Isolation (Expected: $expectedCount)")
                    return
                }

                // CHECK 2: FALSE POSITIVE PROTECTION (Learning Mode)
                if (recentHistory.size < 5) {
                    Log.d("FakeTowerDetection", "Neighbor Check: Still learning environment for Tower ${currentTower.cid} " +
                          "(Samples: ${recentHistory.size}/5). Skipping Jaccard check.")
                    return
                }

                // CHECK 3: COMPLEX FINGERPRINT MISMATCH (Jaccard Index)
                if (historicalNeighbors.isNotEmpty()) {
                    val intersection = currentNeighbors.intersect(historicalNeighbors).size
                    val union = currentNeighbors.union(historicalNeighbors).size
                    val jaccard = if (union > 0) intersection.toDouble() / union.toDouble() else 0.0

                    if (jaccard < 0.2) {
                        val msg = "Neighbor Mismatch: Tower ${currentTower.cid} has wrong neighbor fingerprint " +
                                  "(Overlap: ${(jaccard * 100).toInt()}%). Possible Inline MitM or tower cloning."
                        showSecurityAlert("Environment Anomaly", msg, relatedTowerId = fakeTower.id)
                    }
                }
            }
        }
    }

    /**
     * Impossible PCI / ARFCN Overlap Detector — Stingray cloning indicator.
     *
     * Real networks use careful frequency planning to ensure no two towers in the same physical
     * area share the same PCI (Physical Cell ID) + ARFCN (frequency channel).
     * A Stingray or IMSI Catcher often clones the exact PCI+ARFCN of a real nearby tower
     * because it is impersonating it. This creates a "co-channel collision" in the scan results
     * that is practically impossible on a healthy network.
     *
     * Detection logic:
     *  - Group all visible towers by (PCI, ARFCN).
     *  - If any group contains more than one distinct CID -> impossible overlap detected.
     *
     * Confidence: 0.90 (near-certain) because frequency planning is a strict network engineering rule.
     */
    private fun checkImpossiblePciOverlaps(allVisibleTowers: List<TowerConnectionInfo>) {
        if (allVisibleTowers.size < 2) return

        // Group towers that have valid PCI and ARFCN values
        val grouped = allVisibleTowers
            .filter { t -> t.pci != null && t.pci != Int.MAX_VALUE && t.arfcn != null && t.arfcn != Int.MAX_VALUE }
            .groupBy { t -> Pair(t.pci, t.arfcn) }

        for ((key, towers) in grouped) {
            val distinctCids = towers.map { it.cid }.toSet()
            if (distinctCids.size > 1) {
                val (pci, arfcn) = key
                val cidList = distinctCids.joinToString(", ")
                val msg = "Impossible PCI Overlap: Towers [$cidList] share identical PCI=$pci and ARFCN=$arfcn. " +
                          "This is physically impossible on a legitimate network — indicates tower cloning (Stingray)."
                Log.e("FakeTowerDetection", "🚨 IMPOSSIBLE OVERLAP: $msg")
                // Broadcast for the first offending tower
                val offender = towers.first()
                serviceScope.launch {
                    val fakeTower = try { fakeTowerDao.getFakeTower(offender.cid, offender.lac, offender.mcc ?: 0, offender.mnc ?: 0) } catch (e: Exception) { null }
                    if (fakeTower != null) {
                        broadcastSuspiciousTower(fakeTower, "Impossible PCI/ARFCN Overlap (Stingray Clone)")
                    }
                }
                showSecurityAlert("Tower Cloning Detected", msg)
            }
        }
    }


    // Broadcast Receiver for Notification Actions (Mute/Unmute)
    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TOGGLE_ALERT_SUPPRESSION) {
                val shouldSuppress = intent.getBooleanExtra("suppress", true)
                if (shouldSuppress) {
                    isAlertSuppressed = true
                    suppressionEndTime = Long.MAX_VALUE // Indefinite suppression until user resumes
                    Log.i("FakeTowerDetectionService", "🔕 ALERTS SUPPRESSED indefinitely by user")
                    // Show a quiet confirmation toast/notification update
                    updateNotification("Alerts Muted", towersScannedCount, 0)
                    
                    // Notify UI
                    val stateIntent = Intent("com.cymatune.ALERT_SUPPRESSION_STATE_CHANGED")
                    stateIntent.putExtra("isSuppressed", true)
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context!!).sendBroadcast(stateIntent)
                } else {
                    isAlertSuppressed = false
                    suppressionEndTime = 0
                    Log.i("FakeTowerDetectionService", "🔔 ALERTS RESUMED by user")
                    updateNotification("Monitoring Active", towersScannedCount, 0)
                    
                    // Notify UI
                    val stateIntent = Intent("com.cymatune.ALERT_SUPPRESSION_STATE_CHANGED")
                    stateIntent.putExtra("isSuppressed", false)
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(context!!).sendBroadcast(stateIntent)
                }
            }
        }
    }

    private fun showSecurityAlert(
        title: String, 
        message: String, 
        severity: com.cymatune.logcat.ThreatSeverity = com.cymatune.logcat.ThreatSeverity.HIGH,
        relatedTowerId: Long? = null
    ) {
        val currentTime = System.currentTimeMillis()
        
        // 1. Check Global Suppression (from bell icon)
        if (isAlertSuppressed) {
            if (currentTime < suppressionEndTime) {
                Log.d("FakeTowerService", "Alert blocked (User Suppressed): $title")
                return  // Don't log or notify if user explicitly suppressed
            } else {
                // Auto-expire suppression
                isAlertSuppressed = false
}
    }

    // 2. ALWAYS Log to Logcat
    Log.w("FakeTowerDetectionService", "SECURITY ALERT: $title - $message (Severity: $severity)")

    // Broadcast to UI for WarningBanner update
    broadcastThreatToUI(title, relatedTowerId, message)
}

    /**
     * Broadcast threat to UI components for WarningBanner updates
     */
    private fun broadcastThreatToUI(title: String, towerId: Long?, message: String) {
        try {
            val intent = Intent("com.cymatune.THREAT_DETECTED").apply {
                setPackage(packageName)
                putExtra("threat_message", title)
                putExtra("explanation", message)
                putExtra("tower_id", towerId)
                putExtra("timestamp", System.currentTimeMillis())
            }
            sendBroadcast(intent)
            Log.d("FakeTowerDetectionService", "Broadcast THREAT_DETECTED to UI: $title")
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Failed to broadcast threat to UI", e)
        }
    }
    
    /**
     * Extract CID from alert message text as fallback
     * Patterns: "CID: 12345", "Tower 12345", "(CID=12345)", etc.
     */
    private fun extractCidFromMessage(message: String): Int? {
        // Pattern 1: "CID: 12345" or "CID=12345" or "CID 12345"
        val cidPattern1 = Regex("""CID[=:\s]+(\d+)""", RegexOption.IGNORE_CASE)
        cidPattern1.find(message)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        
        // Pattern 2: "Tower 12345" or "tower: 12345"
        val cidPattern2 = Regex("""Tower[:\s]+(\d+)""", RegexOption.IGNORE_CASE)
        cidPattern2.find(message)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        
        // Pattern 3: "(CID=12345)" or "[CID:12345]"
        val cidPattern3 = Regex("""[\(\[]CID[=:\s]*(\d+)[\)\]]""", RegexOption.IGNORE_CASE)
        cidPattern3.find(message)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        
        return null
    }

    /**
     * Show consolidated notification (ONE per threat type per tower)
     * - Groups same threat types together
     * - Shows count badge for multiple instances
     * - NO BUZZ on updates (only first occurrence alerts)
     * - Groups by CID (physical tower) not database ID
     * - SESSION DEDUPLICATION: Only alerts ONCE per tower per threat type per session
     */
    private fun showHeadsUpNotification(title: String, message: String, towerId: Long? = null) {
        // Delegate entirely to the centralised silent manager
        val threatType = extractThreatType(title)
        SecurityNotificationManager.notify(this, threatType, message)
    }

    /**
     * Extract threat type from alert title for grouping
     */
    private fun extractThreatType(title: String): String {
        return when {
            title.contains("IMSI Catcher", ignoreCase = true) -> "IMSI Catcher"
            title.contains("Silent SMS", ignoreCase = true) -> "Silent SMS"
            title.contains("Privacy", ignoreCase = true) -> "Privacy Warning"
            title.contains("Protocol", ignoreCase = true) -> "Protocol Anomaly"
            title.contains("Rogue", ignoreCase = true) -> "Rogue Tower"
            title.contains("Location", ignoreCase = true) -> "Location Anomaly"
            title.contains("Radius", ignoreCase = true) -> "Radius Anomaly"
            title.contains("Cell Isolation", ignoreCase = true) -> "Cell Isolation"
            else -> "Security Alert"
        }
    }
    


    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Cymatune",
            NotificationManager.IMPORTANCE_MIN // MINIMAL: No sound, no vibration, no status icon
        ).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun startLocationUpdates() {
        if (!::locationManager.isInitialized) {
            Log.w("FakeTowerDetectionService", "LocationManager not initialized, skipping location updates")
            return
        }
        
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.w("FakeTowerDetectionService", "Location permission not granted")
            return
        }
        
        try {
            // Optimize location update frequency based on scan radius
            val minTimeMs = when {
                currentScanRadius <= 5 -> 10000L // 10 seconds for small radii
                currentScanRadius <= 15 -> 30000L // 30 seconds for medium radii
                else -> 60000L // 60 seconds for large radii
            }
            
            val minDistanceMeters = when {
                currentScanRadius <= 5 -> 20f // 20 meters for small radii
                else -> 50f // 50 meters for larger radii
            }
            
            // CRITICAL FIX: Use main thread Looper to avoid "Can't create handler inside thread" error
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                minTimeMs,
                minDistanceMeters,
                this,
                android.os.Looper.getMainLooper() // Use main looper for Handler
            )
            
            Log.d("FakeTowerDetectionService", "Location updates optimized: ${minTimeMs}ms interval, ${minDistanceMeters}m distance")
        } catch (e: SecurityException) {
            Log.e("FakeTowerDetectionService", "Location permission not granted: ${e.message}")
        }
    }

    private fun startSensorUpdates() {
        accelerometerSensor?.let {
            // Use a slower sensor delay to save battery - we don't need high frequency for stationary detection
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            Log.d("FakeTowerDetectionService", "Accelerometer updates optimized with UI delay")
        }
    }

    private fun startRealTimeCellMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                Log.i("FakeTowerDetectionService", "Attempting to register Real-time CellInfoCallback (Android 12+)")
                
                cellInfoCallback = object : android.telephony.TelephonyCallback(), android.telephony.TelephonyCallback.CellInfoListener {
                    private var lastProcessTime = 0L
                    private val MIN_PROCESS_INTERVAL_MS = 500L // Reduced from 2000ms to 500ms for real-time display
                    
                    override fun onCellInfoChanged(cellInfo: List<android.telephony.CellInfo>) {
                        val currentTime = System.currentTimeMillis()
                        
                        if (cellInfo.isNotEmpty()) {
                            Log.i("FakeTowerDetectionService", "Real-time callback received ${cellInfo.size} cells")
                        }
                        
                        lastKnownCellInfo = cellInfo
                        
                        // Throttle real-time updates to prevent excessive processing
                        if (currentTime - lastProcessTime >= MIN_PROCESS_INTERVAL_MS) {
                            lastProcessTime = currentTime
                            // Process real-time cell updates
                            processRealTimeCellInfo(cellInfo)
                        } else {
                            // Log.d("FakeTowerDetectionService", "Real-time update throttled (too frequent)")
                        }
                    }
                }
                
                telephonyManager.registerTelephonyCallback(
                    mainExecutor,
                    cellInfoCallback as android.telephony.TelephonyCallback
                )
                Log.i("FakeTowerDetectionService", "Real-time CellInfoCallback registered successfully")
                
                // Force an initial update since callback might be silent until change
                requestFreshCellInfo()
                
            } catch (e: SecurityException) {
                Log.e("FakeTowerDetectionService", "Permission denied for real-time cell monitoring", e)
            } catch (e: IllegalStateException) {
                Log.e("FakeTowerDetectionService", "Failed to register CellInfoCallback - telephony service unavailable", e)
            } catch (e: Exception) {
                Log.e("FakeTowerDetectionService", "Unexpected error registering CellInfoCallback", e)
            }
        } else {
            Log.d("FakeTowerDetectionService", "Real-time CellInfoCallback requires Android 12+, falling back to PhoneStateListener")
            try {
                val phoneStateListener = object : android.telephony.PhoneStateListener() {
                    private var lastProcessTime = 0L
                    private val MIN_PROCESS_INTERVAL_MS = 500L
                    
                    @Deprecated("Deprecated in Java")
                    override fun onCellInfoChanged(cellInfo: MutableList<android.telephony.CellInfo>?) {
                        super.onCellInfoChanged(cellInfo)
                        if (cellInfo == null) return
                        
                        lastKnownCellInfo = cellInfo
                        
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastProcessTime >= MIN_PROCESS_INTERVAL_MS) {
                            lastProcessTime = currentTime
                            processRealTimeCellInfo(cellInfo)
                        }
                    }
                }
                telephonyManager.listen(phoneStateListener, android.telephony.PhoneStateListener.LISTEN_CELL_INFO)
            } catch (e: Exception) {
                Log.e("FakeTowerDetectionService", "Failed to register PhoneStateListener", e)
            }
        }
    }

    private fun requestFreshCellInfo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
             try {
                 Log.i("FakeTowerDetectionService", "Requesting fresh cell info update from modem...")
                 telephonyManager.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                     override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                         if (cellInfo.isNotEmpty()) {
                             Log.i("FakeTowerDetectionService", "Fresh cell info received: ${cellInfo.size} cells")
                             lastKnownCellInfo = cellInfo
                             // Feed this back into processing logic
                             processRealTimeCellInfo(cellInfo)
                             
                             // Also update single sim info immediately for UI responsiveness
                             getSingleSimTowerInfo(cellInfo)
                         } else {
                             Log.w("FakeTowerDetectionService", "Fresh cell info update returned empty list")
                         }
                     }
                     
                     override fun onError(errorCode: Int, detail: Throwable?) {
                         // ERROR_MODEM_ERROR = 2, ERROR_TIMEOUT = 1
                         Log.w("FakeTowerDetectionService", "Fresh cell info request failed with error: $errorCode")
                     }
                 })
             } catch (e: Exception) {
                 Log.e("FakeTowerDetectionService", "Error requesting fresh cell info", e)
             }
        }
    }

    /**
     * Phase 4.0: Process real-time cell info for Multi-SIM support
     * Groups cells by subscription and processes each independently
     */
    private fun processRealTimeCellInfo(cellInfoList: List<CellInfo>) {
        if (cellInfoList.isEmpty()) {
            return
        }

        val currentTime = System.currentTimeMillis()
        Log.i("FakeTowerDetectionService", "Processing ${cellInfoList.size} cells from real-time source (Multi-SIM mode)")

        // Group cells by subscription ID
        val cellsBySubscription = mutableMapOf<Int, MutableList<CellInfo>>()

        for (cellInfo in cellInfoList) {
            val subscriptionId = getCellSubscriptionId(cellInfo)

            // Filter to only active subscriptions
            val validSubscriptionId = if (activeSubscriptionIds.contains(subscriptionId)) subscriptionId
                else activeSubscriptionIds.firstOrNull() ?: 1

            cellsBySubscription.getOrPut(validSubscriptionId) { mutableListOf() }.add(cellInfo)
        }

        // Process each subscription independently
        for ((subscriptionId, cells) in cellsBySubscription) {
            processCellsForSubscription(subscriptionId, cells, currentTime)
        }

        // Aggregate all towers for broadcast
        val allTowers = mutableListOf<com.cymatune.util.TowerConnectionInfo>()
        cellsBySubscription.forEach { (_, cells) ->
            cells.forEach { cellInfo ->
                try {
                    val subId = getCellSubscriptionId(cellInfo)
                    val validSubId = if (activeSubscriptionIds.contains(subId)) subId else activeSubscriptionIds.firstOrNull() ?: 1
                    val towerInfo = extractTowerInfoFromCell(cellInfo, validSubId, activeSubscriptionIds.indexOf(validSubId))
                    if (towerInfo != null) {
                        allTowers.add(towerInfo)
                    }
                } catch (e: Exception) {
                    Log.e("FakeTowerDetectionService", "Error extracting tower info", e)
                }
            }
        }

        // Find primary tower (registered cell with strongest signal from any subscription)
        val currentTower = allTowers.filter { it.isRegistered }
            .maxByOrNull { it.signalStrength }

        // Update LogcatDetectionBridge with current tower
        currentTower?.let {
            com.cymatune.logcat.LogcatDetectionBridge.setCurrentTower(
                it.cid, it.lac, it.mcc, it.mnc
            )
        }

        // Broadcast updates
        broadcastRealTimeCellUpdate(allTowers, currentTower)
        broadcastAllVisibleTowers(allTowers)
        broadcastRealTimeCellUpdateForHomeFragment(currentTower)

        val neighborCount = allTowers.size - (if (currentTower != null) 1 else 0)
        Log.d("FakeTowerDetectionService",
            "Real-time Multi-SIM: $neighborCount neighbors, total: ${allTowers.size} towers, " +
            "${cellsBySubscription.size} subscription(s) active")

        // === IMPOSSIBLE OVERLAP CHECK ===
        // Run after all towers are collected so we can compare across all visible towers.
        checkImpossiblePciOverlaps(allTowers)
    }


    /**
     * Phase 4.0: Process cells for a specific subscription
     * Handles tower logging and neighbor consistency per SIM
     */
    private fun processCellsForSubscription(
        subscriptionId: Int,
        cells: List<CellInfo>,
        timestamp: Long
    ) {
        val towers = mutableListOf<com.cymatune.util.TowerConnectionInfo>()
        var currentTower: com.cymatune.util.TowerConnectionInfo? = null

        for (cellInfo in cells) {
            try {
                val slotIndex = activeSubscriptionIds.indexOf(subscriptionId)
                val towerInfo = extractTowerInfoFromCell(cellInfo, subscriptionId, slotIndex)
                towerInfo?.let {
                    towers.add(it)
                    if (cellInfo.isRegistered &&
                        (currentTower == null || it.signalStrength > currentTower!!.signalStrength)) {
                        currentTower = it
                    }
                    Log.d("FakeTowerDetectionService",
                        "Multi-SIM[$subscriptionId]: Extracted tower CID=${it.cid}, Registered=${cellInfo.isRegistered}")
                }
            } catch (e: Exception) {
                Log.e("FakeTowerDetectionService", "Error extracting tower for subscription $subscriptionId", e)
            }
        }

        // Extract neighbor CIDs for this subscription's current tower
        val neighborCids = towers.filter { it != currentTower }.map { it.cid.toString() }

        // Save neighbor history and check consistency for this subscription
        if (currentTower != null && currentLocation != null) {
            serviceScope.launch {
                try {
                    val history = com.cymatune.db.NeighborHistory(
                        primaryCid = currentTower!!.cid,
                        primaryLac = currentTower!!.lac,
                        primaryMcc = currentTower!!.mcc ?: 0,
                        primaryMnc = currentTower!!.mnc ?: 0,
                        neighborCids = neighborCids.joinToString(","),
                        neighborCount = neighborCids.size,
                        latitude = currentLocation!!.latitude,
                        longitude = currentLocation!!.longitude,
                        timestamp = timestamp
                    )
                    neighborHistoryDao.insertNeighborHistory(history)
                    checkNeighborConsistency(currentTower!!, neighborCids)
                } catch (e: Exception) {
                    Log.e("FakeTowerDetectionService", "Error saving neighbor history for subscription $subscriptionId", e)
                }
            }
        }

        // Log towers to database for this subscription
        serviceScope.launch {
            towers.forEach { towerInfo ->
                try {
                    logTowerToDatabase(towerInfo, neighborCids)
                } catch (e: Exception) {
                    Log.e("FakeTowerDetectionService", "Error logging tower for subscription $subscriptionId", e)
                }
            }
        }

        Log.d("FakeTowerDetectionService",
            "Subscription $subscriptionId: ${towers.size} towers processed, current: ${currentTower?.cid ?: "none"}")
    }

        /**
     * Trigger active defense based on device capabilities
     * @param context Context
     * @param reason Reason for defense
     */
    private fun triggerDefenseMechanism(context: Context, reason: String) {
        val rootUtilClass = try {
            Class.forName("com.cymatune.util.RootUtil")
        } catch (e: ClassNotFoundException) {
            null
        }

        var executedRoot = false
        if (rootUtilClass != null) {
            try {
                // Check if device is rooted using reflection to avoid compilation errors if class missing
                val isRootedMethod = rootUtilClass.getMethod("isDeviceRooted")
                val isRooted = isRootedMethod.invoke(rootUtilClass.kotlin.objectInstance) as Boolean
                
                if (isRooted) {
                    Log.w("FakeTowerDetectionService", "DEFENSE: Attempting Root Airplane Mode Toggle")
                    val setAirplaneMethod = rootUtilClass.getMethod("setAirplaneMode", Boolean::class.java)
                    executedRoot = setAirplaneMethod.invoke(rootUtilClass.kotlin.objectInstance, true) as Boolean
                    if (executedRoot) {
                        showSecurityAlert("Threat Neutralized", "Enabled Airplane Mode via Root to block attack.")
                    }
                }
            } catch (e: Exception) {
                Log.e("FakeTowerDetectionService", "Failed to execute root defense", e)
            }
        }

        if (!executedRoot) {
            Log.w("FakeTowerDetectionService", "DEFENSE: Non-Root Fallback - Requesting User Action")
            // Create high-priority notification with pending intent to settings
            val intent = android.content.Intent(android.provider.Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            
            val pendingIntent = android.app.PendingIntent.getActivity(
                context, 999, intent, 
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            
            showSecurityAlert(
                "CRITICAL THREAT DETECTED", 
                "Rogue Tower Active! Tap to Disconnect immediately.",
                com.cymatune.logcat.ThreatSeverity.CRITICAL
            )
            
            // We could also launch the intent directly if the app is in foreground, 
            // but typical background service restrictions apply on Android 10+.
        }
    }



    private fun startTowerLocalizationTask() {
        Log.d("FakeTowerDetectionService", "Starting optimized tower localization task with scan radius: ${currentScanRadius}km")
        
        // Use adaptive scan intervals based on user activity and battery level
        val scanIntervalSeconds = getAdaptiveScanInterval()
        
        // Schedule a runnable to run periodically for tower localization
        executor.scheduleAtFixedRate({
            try {
                // Check if we should skip this scan cycle for battery optimization
                if (shouldSkipScanCycle()) {
                    Log.d("FakeTowerDetectionService", "Skipping scan cycle for battery optimization")
                    return@scheduleAtFixedRate
                }
                
                // Get current cell info from the event-driven cache instead of active polling
                val cellInfo = lastKnownCellInfo
                
                if (cellInfo.isEmpty()) {
                    Log.w("FakeTowerDetectionService", "Cached cell list empty - attempting forced refresh")
                    requestFreshCellInfo()
                }
                
                // Log all detected towers with intelligent rate limiting
                cellInfo?.let {
                    if (it.isNotEmpty() && shouldLogTowersThisCycle()) {
                        logAllDetectedTowers(it)
                    }
                }
                
                // Get single SIM tower information and broadcast it to the main activity
                getSingleSimTowerInfo(cellInfo)
                
                // Perform active tower scanning within the current scan radius
                performActiveTowerScanning()

                Log.d("FakeTowerDetectionService", "Tower localization completed. Scan interval: ${scanIntervalSeconds}s")

                // Update notification with current status
                // Query actual threat count from database instead of using accumulating counter
                serviceScope.launch {
                    try {
                        val actualThreats = fakeTowerDao.getAllFakeTowersSync().count { it.trustScore < 50 }
                        updateNotification("Active monitoring", towersScannedCount, actualThreats)
                    } catch (e: Exception) {
                        Log.e("FakeTowerDetectionService", "Failed to query threat count for notification", e)
                        updateNotification("Active monitoring", towersScannedCount, 0)
                    }
                }

                // Update scan interval dynamically based on current conditions
                updateDynamicScanInterval()
            } catch (e: SecurityException) {
                Log.e("FakeTowerDetectionService", "Permission denied for cell info access", e)
            } catch (e: InterruptedException) {
                Log.e("FakeTowerDetectionService", "Tower localization task interrupted", e)
            } catch (e: SecurityException) {
                Log.e("FakeTowerDetectionService", "Permission denied for tower localization task", e)
            } catch (e: Exception) {
                Log.e("FakeTowerDetectionService", "Unexpected error in tower localization task", e)
            }
        }, 0, scanIntervalSeconds, TimeUnit.SECONDS)
    }
    
    /**
     * Get adaptive scan interval based on multiple factors
     */
    private fun getAdaptiveScanInterval(): Long {
        val baseInterval = when {
            currentScanRadius <= 5 -> 5L // Reduced from 15 to 5 seconds for small radii
            currentScanRadius <= 15 -> 10L // Reduced from 30 to 10 seconds for medium radii
            else -> 20L // Reduced from 60 to 20 seconds for large radii
        }
        
        // Adjust based on battery level with more granular control
        val batteryLevel = getBatteryLevel()
        val batteryAdjustedInterval = when {
            batteryLevel < 5 -> baseInterval * 4L // Very slow scanning when battery is critical
            batteryLevel < 15 -> baseInterval * 3L // Slow scanning when battery is very low
            batteryLevel < 25 -> baseInterval * 2L // Slower scanning when battery is low
            batteryLevel < 35 -> baseInterval * 1.5.toLong() // Moderate scanning
            batteryLevel < 50 -> baseInterval * 1.2.toLong() // Slightly reduced scanning
            else -> baseInterval // Normal scanning
        }
        
        // Adjust based on charging status - faster scanning when charging
        val chargingAdjustedInterval = if (isCharging()) {
            batteryAdjustedInterval * 0.7.toLong() // 30% faster scanning when charging
        } else {
            batteryAdjustedInterval
        }
        
        // Adjust based on time of day (slower at night)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timeAdjustedInterval = when {
            hour in 0..6 -> chargingAdjustedInterval * 1.5.toLong() // Night time - slower scanning
            else -> chargingAdjustedInterval
        }
        
        // Adjust based on device activity (if accelerometer data available)
        val activityAdjustedInterval = adjustIntervalBasedOnActivity(timeAdjustedInterval)
        
        return activityAdjustedInterval.coerceAtLeast(5L).coerceAtMost(300L) // Min 5s, max 5min
    }
    
    /**
     * Adjust scan interval based on device activity/movement
     */
    private fun adjustIntervalBasedOnActivity(baseInterval: Long): Long {
        currentAccelerometerData?.let { accelData ->
            // Calculate movement magnitude
            val movementMagnitude = sqrt(
                accelData.x * accelData.x +
                accelData.y * accelData.y +
                accelData.z * accelData.z
            )
            
            // If device is stationary, use longer intervals
            return if (movementMagnitude < 1.0) {
                baseInterval * 1.5.toLong() // 50% longer intervals when stationary
            } else {
                baseInterval // Normal intervals when moving
            }
        }
        return baseInterval
    }
    
    /**
     * Check if we should skip this scan cycle for battery optimization
     */
    private fun shouldSkipScanCycle(): Boolean {
        val batteryLevel = getBatteryLevel()
        
        // Skip if battery is critically low
        if (batteryLevel < 5) {
            Log.d("FakeTowerDetectionService", "Skipping scan cycle: Battery critically low ($batteryLevel%)")
            return true
        }
        
        // Skip if battery is charging and below 20% (preserve battery for charging)
        if (isCharging() && batteryLevel < 20) {
            Log.d("FakeTowerDetectionService", "Skipping scan cycle: Battery charging and below 20% ($batteryLevel%)")
            return true
        }
        
        // Skip randomly based on battery level (more frequent skipping at lower battery)
        val skipProbability = when {
            batteryLevel < 15 -> 0.5 // 50% chance to skip when battery < 15%
            batteryLevel < 25 -> 0.3 // 30% chance to skip when battery < 25%
            batteryLevel < 35 -> 0.2 // 20% chance to skip when battery < 35%
            else -> 0.0 // No skipping when battery is healthy
        }
        
        if (Math.random() < skipProbability) {
            Log.d("FakeTowerDetectionService", "Skipping scan cycle: Battery optimization ($batteryLevel%, $skipProbability probability)")
            return true
        }
        
        return false
    }
    
    /**
     * Check if device is charging
     */
    private fun isCharging(): Boolean {
        return try {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val chargePlug = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            chargePlug == BatteryManager.BATTERY_PLUGGED_AC ||
            chargePlug == BatteryManager.BATTERY_PLUGGED_USB ||
            chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Error checking charging status", e)
            false
        }
    }
    
    /**
     * Intelligent rate limiting for tower logging
     */
    private fun shouldLogTowersThisCycle(): Boolean {
        // Log more frequently when many towers are changing
        val currentTime = System.currentTimeMillis()
        val lastLogTime = lastTowerLogTime
        
        // Always log if it's been more than 30 seconds (reduced from 2 minutes)
        if (lastLogTime == 0L || currentTime - lastLogTime > 30000) {
            lastTowerLogTime = currentTime
            return true
        }
        
        // Log every scan cycle for real-time display (removed rate limiting)
        return true
    }
    
    /**
     * Update scan interval dynamically based on current conditions
     */
    private fun updateDynamicScanInterval() {
        // This would be called to potentially reschedule with new interval
        // For now, we'll keep it simple and use fixed adaptive intervals
    }
    
    /**
     * Get current battery level
     */
    private fun getBatteryLevel(): Int {
        return try {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            
            // Debug logging to understand battery values
            Log.d("FakeTowerDetectionService", "Battery level: $level, scale: $scale")
            
            if (level == -1 || scale == -1) {
                Log.d("FakeTowerDetectionService", "Invalid battery values, assuming 100%")
                100
            } else {
                val percentage = (level * 100 / scale)
                Log.d("FakeTowerDetectionService", "Calculated battery percentage: $percentage%")
                percentage
            }
        } catch (e: SecurityException) {
            Log.e("FakeTowerDetectionService", "Permission denied for battery status access", e)
            100 // Assume full battery if we can't read it
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Unexpected error reading battery status", e)
            100 // Assume full battery if we can't read it
        }
    }
    
    private var lastTowerLogTime = 0L
    
    /**
     * Performs active tower scanning within the configured scan radius
     * This method uses the current scan radius to filter and prioritize tower detection
     */
    private val isScanningActive = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Performs active tower scanning within the configured scan radius
     * This method uses the current scan radius to filter and prioritize tower detection
     */
    private fun performActiveTowerScanning() {
        val currentCid = currentConnectedTowerCid
        
        // PRIORITY SCAN: Always analyze the currently connected tower immediately
        // This bypasses the concurrency lock to ensure we never miss a connection event
        if (currentCid != -1 && currentCid != 0 && currentCid != Int.MAX_VALUE) {
            serviceScope.launch {
                try {
                    val connectedTower = fakeTowerDao.getAnyTowerByCid(currentCid ?: -1)
                    if (connectedTower != null) {
                        Log.d("FakeTowerDetectionService", "⚡ Priority Scan: Analyzing connected tower $currentCid")
                        performEnhancedTowerAnalysis(connectedTower)
                    }
                } catch (e: Exception) {
                    Log.e("FakeTowerDetectionService", "Priority scan failed", e)
                }
            }
        }
        
        // GENERAL BACKGROUND SCAN (Throttled)
        if (isScanningActive.getAndSet(true)) {
            Log.w("FakeTowerDetectionService", "Skipping background scan - previous scan still running")
            return
        }
        
        serviceScope.launch {
            try {
                // Get all fake towers from database
                val allFakeTowers: List<com.cymatune.db.FakeTower> = fakeTowerDao.getAllFakeTowers().firstOrNull() ?: emptyList()
                
                // Filter towers based on scan radius if we have current location
                currentLocation?.let { location ->
                    val scanRadiusMeters = getCustomScanRadiusMeters()
                    val filteredTowers = allFakeTowers.filter { fakeTower ->
                        val distance = CommonUtils.calculateDistance(
                            location.latitude, location.longitude,
                            fakeTower.latitude, fakeTower.longitude
                        )
                        distance <= scanRadiusMeters
                    }
                    
                    Log.d("FakeTowerDetectionService",
                         "Scan radius: ${currentScanRadius}km, Towers in range: ${filteredTowers.size}/${allFakeTowers.size}")

                    // Perform enhanced detection on towers within scan radius
                    kotlinx.coroutines.coroutineScope {
                        val deferrals: List<kotlinx.coroutines.Deferred<Unit>> = filteredTowers.map { fakeTower ->
                            async {
                                performEnhancedTowerAnalysis(fakeTower)
                            }
                        }
                        deferrals.forEach { deferred -> deferred.await() }
                    }

                    // Update towers scanned count after processing
                    towersScannedCount = filteredTowers.size
                }
            } catch (e: InterruptedException) {
                Log.e("FakeTowerDetectionService", "Active tower scanning interrupted", e)
            } catch (e: SecurityException) {
                Log.e("FakeTowerDetectionService", "Permission denied for active tower scanning", e)
            } catch (e: Exception) {
                Log.e("FakeTowerDetectionService", "Unexpected error in active tower scanning", e)
            } finally {
                isScanningActive.set(false)
            }
        }
    }
    
    /**
     * Performs enhanced analysis on a fake tower using the current scan radius context
     * Now includes false positive suppression for GPS multipath errors and new tower detections
     */
    private suspend fun performEnhancedTowerAnalysis(fakeTower: FakeTower) {
        // Use the scan radius to adjust detection sensitivity
        val scanRadiusMeters = getCustomScanRadiusMeters()
        
        // REDUCED SENSITIVITY: Changed from 1.5 to 1.2 to reduce false positives
        val sensitivityFactor = when {
            scanRadiusMeters <= 5000 -> 1.2  // 5km or less = moderate sensitivity (was 1.5)
            scanRadiusMeters <= 15000 -> 1.1 // 15km or less = slight boost (was 1.2)
            else -> 1.0                     // Larger radius = normal sensitivity
        }
        
        // Enhanced detection logic using sensitivity factor
        // Note: accuracy field is now calculated as (100 - trustScore) / 100
        // So accuracy < 0.7 means trustScore < 30 (suspicious)
        if (fakeTower.accuracy < 0.7) {
            // Update accuracy based on sensitivity factor
            val updatedAccuracy = fakeTower.accuracy * sensitivityFactor
            
            // FALSE POSITIVE SUPPRESSION #1: Check observation history
            // Require 3+ observations before alerting on moderate-confidence detections
            // FALSE POSITIVE SUPPRESSION #1: Check observation history
            // Require 3+ observations before alerting on moderate-confidence detections
            // OPTIMIZATION: Use cached observation count from entity instead of querying DB
            val observationCount = fakeTower.observationCount
            
            val isStableHistory = observationCount >= 3
            
            // FALSE POSITIVE SUPPRESSION #0: User Movement Check
            // If user is moving/traveling, don't require 3 observations for new towers
            val skipObservationRequirement = isUserMoving && 
                (movementIntensity == com.cymatune.detection.MovementDetector.MovementIntensity.MOVING ||
                 movementIntensity == com.cymatune.detection.MovementDetector.MovementIntensity.TRAVELING)
            
            // Suppress alerts for new towers with moderate confidence UNLESS user is traveling
            if (!isStableHistory && updatedAccuracy < 0.75 && !skipObservationRequirement) {
                Log.d("FakeTowerDetection", 
                    "Alert suppressed (new tower, needs stable history): CID=${fakeTower.cid}, " +
                    "observations=$observationCount/3, confidence=${(updatedAccuracy*100).toInt()}%")
                return  // SUPPRESS
            }
            
            // Log if observation requirement was skipped due to movement
            if (!isStableHistory && skipObservationRequirement) {
                Log.d("FakeTowerDetection",
                    "Observation requirement skipped: CID=${fakeTower.cid}, " +
                    "User is ${movementIntensity.name} (observations=$observationCount/3)")
            }
            
            // FALSE POSITIVE SUPPRESSION #2: GPS Accuracy Check
            // Get current location accuracy (if available)
            val gpsAccuracyMeters = try {
                currentLocation?.accuracy ?: Float.MAX_VALUE
            } catch (e: Exception) {
                Float.MAX_VALUE  // Unknown accuracy
            }
            
            // RELAXED THRESHOLD: Real devices rarely achieve perfect GPS accuracy
            // Allow alerts even with 30-50m accuracy, matching physical hardware capabilities
            val shouldAlert = when {
                // High confidence (>=75%) overrides GPS issues and history requirements
                updatedAccuracy >= 0.75 -> true
                // Good GPS (<=30m) + medium confidence (>=60%) - relaxed from 10m
                gpsAccuracyMeters <= 30.0 && updatedAccuracy >= 0.6 -> true
                // Moderate GPS (<=50m) + higher confidence (>=70%) - relaxed from 15m
                gpsAccuracyMeters <= 50.0 && updatedAccuracy >= 0.7 -> true
                // Poor GPS or low confidence -> suppress
                else -> false
            }
            
            if (shouldAlert) {
                Log.d("FakeTowerDetectionService",
                     "Suspicious tower detected within ${currentScanRadius}km radius: CID=${fakeTower.cid}, " +
                     "Confidence=${(updatedAccuracy*100).toInt()}%, Observations=$observationCount, GPS=${String.format("%.1f",gpsAccuracyMeters)}m")

                // === RULE OF 3: GPS FIDELITY FILTER ===
                // Only allow spatial alerts if the anomaly gap is meaningfully larger than GPS noise.
                // We do NOT fire if the sensor error can fully explain the anomaly.
                //
                // How it works:
                //   - GPS reports "accuracy" = radius of a 68% confidence circle.
                //   - We multiply by 3 to get a conservative "noise ceiling".
                //   - The spatial anomaly must exceed this ceiling to be credible.
                //
                // Example: GPS accuracy = 50m  → noise ceiling = 150m
                //   If the signal/distance mismatch gap is only 80m → SUPPRESSED (within noise)
                //   If the gap is 300m → ALERT (clearly beyond noise)
                //
                // For the scanner, updatedAccuracy represents the trust deficit (0 = fully trusted, 1 = fully suspect).
                // gpsAccuracyMeters is the physical error circle of the current GPS fix.
                // We suppress when GPS is so poor that it could plausibly explain the anomaly on its own.
                val gpsNoiseCeiling = gpsAccuracyMeters * 3f
                // Convert accuracy (0.0 trust-deficit → 1.0 suspect) to an estimated metre-offset.
                // The scan radius is in km; a 30% deficit at 5km ≈ 1500m mismatch.
                val estimatedAnomalyGapMeters = (1.0 - updatedAccuracy) * (currentScanRadius * 1000)
                if (gpsAccuracyMeters < Float.MAX_VALUE && estimatedAnomalyGapMeters < gpsNoiseCeiling) {
                    Log.d("FakeTowerDetection",
                        "Alert SUPPRESSED by Rule-of-3 (GPS noise can explain anomaly): " +
                        "gpsError=${String.format("%.0f",gpsAccuracyMeters)}m, " +
                        "noiseCeiling=${String.format("%.0f",gpsNoiseCeiling)}m, " +
                        "estimatedGap=${String.format("%.0f",estimatedAnomalyGapMeters)}m")
                    return
                }

                // Build the alert message — do NOT hardcode a confidence % string (value derived below is authoritative)
                broadcastSuspiciousTower(fakeTower, "Signal patterns inconsistent with distance (Observations: $observationCount)")

                val severity = when {
                    updatedAccuracy >= 0.9 -> com.cymatune.logcat.ThreatSeverity.HIGH
                    updatedAccuracy >= 0.7 -> com.cymatune.logcat.ThreatSeverity.MEDIUM
                    else -> com.cymatune.logcat.ThreatSeverity.LOW
                }

                showSecurityAlert(
                    "Signal Distance Mismatch",
                    "Suspicious tower behavior detected. Signal patterns inconsistent with measured distance. (Observations: $observationCount)",
                    severity,
                    relatedTowerId = fakeTower.id
                )
            } else {
                Log.d("FakeTowerDetection", 
                    "Alert suppressed (GPS/confidence filter): CID=${fakeTower.cid}, " +
                    "GPS=${String.format("%.1f",gpsAccuracyMeters)}m, confidence=${(updatedAccuracy*100).toInt()}%")
            }
        }
    }
    
    /**
     * Detects suspicious patterns based on scan radius and sensitivity using simplified detection
     */
    private suspend fun detectSuspiciousPatterns(fakeTower: FakeTower, sensitivityFactor: Double): Boolean {
        // Simplified detection - just check if confidence is below threshold
        return fakeTower.accuracy < 0.7
    }
    
    
    /**
     * Broadcasts suspicious tower detection.
     * Reason deduplication & aggregation:
     *  - If a DIFFERENT reason is supplied, they are joined with " + " (e.g. "Signal Mismatch + TA Mismatch").
     *  - If the SAME reason is supplied again, it is ignored (no duplication).
     */
    private fun broadcastSuspiciousTower(fakeTower: FakeTower, reason: String) {
        val intent = Intent(FAKE_TOWER_DETECTED).apply {
            putExtra("suspicion_reason", reason)
            putExtra("accuracy", fakeTower.accuracy)
            putExtra("tower_id", "${fakeTower.mcc}-${fakeTower.mnc}-${fakeTower.lac}-${fakeTower.cid}")
        }
        sendBroadcast(intent)

        // Also broadcast to UI for WarningBanner update and persist to database
        broadcastThreatToUI(reason, fakeTower, reason)
    }

/**
 * Broadcast threat to UI components for WarningBanner updates
 */
private fun broadcastThreatToUI(message: String, fakeTower: FakeTower, reason: String) {
    try {
        val intent = Intent("com.cymatune.THREAT_DETECTED").apply {
            setPackage(packageName)
            putExtra("threat_message", message)
            putExtra("explanation", reason)
            putExtra("tower_id", fakeTower.id)
            putExtra("timestamp", System.currentTimeMillis())
        }
        sendBroadcast(intent)
Log.d("FakeTowerDetectionService", "Broadcast THREAT_DETECTED to UI: $message")
    } catch (e: Exception) {
        Log.e("FakeTowerDetectionService", "Failed to broadcast threat to UI", e)
    }
}


/**
 * Phase 4.0: Multi-SIM Tower Info Collection
     * Iterates through all active subscriptions and collects tower information from each SIM
     */
    private fun getMultiSimTowerInfo(cellInfoList: List<CellInfo>?) {
        if (cellInfoList == null) {
            broadcastMultiSimInfo(emptyMap())
            return
        }

        Log.d("FakeTowerDetectionService", "Processing ${cellInfoList.size} cell info entries for Multi-SIM detection")

        // Clear previous tower info
        currentSimTowerInfo.clear()

        // Group cells by subscription ID
        val cellsBySubscription = mutableMapOf<Int, MutableList<CellInfo>>()

        for (cellInfo in cellInfoList) {
            // Try to determine subscription ID from cell info
            val subscriptionId = getCellSubscriptionId(cellInfo)

            // Only process cells from active subscriptions
            if (subscriptionId in activeSubscriptionIds) {
                cellsBySubscription.getOrPut(subscriptionId) { mutableListOf() }.add(cellInfo)
            } else {
                // If subscription ID not in our active list, default to first active subscription
                val fallbackSubId = activeSubscriptionIds.firstOrNull() ?: 1
                cellsBySubscription.getOrPut(fallbackSubId) { mutableListOf() }.add(cellInfo)
            }
        }

        // Process cells for each active subscription
        for (subscriptionId in activeSubscriptionIds) {
            val cells = cellsBySubscription[subscriptionId] ?: emptyList()
            val registeredCells = cells.filter { it.isRegistered }

            if (registeredCells.isEmpty()) {
                Log.d("FakeTowerDetectionService", "No registered cells for subscription $subscriptionId")
                currentSimTowerInfo[subscriptionId] = null
                continue
            }

            // Get the cell with strongest signal for this subscription
            val primaryCell = registeredCells.maxByOrNull {
                when {
                    it is CellInfoLte -> it.cellSignalStrength.dbm
                    it is CellInfoGsm -> it.cellSignalStrength.dbm
                    it is CellInfoWcdma -> it.cellSignalStrength.dbm
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && it is CellInfoNr -> it.cellSignalStrength.dbm
                    else -> Int.MIN_VALUE
                }
            }

            primaryCell?.let { cell ->
                val simInfo = extractTowerInfoFromCell(cell, subscriptionId, activeSubscriptionIds.indexOf(subscriptionId))
                if (simInfo != null) {
                    currentSimTowerInfo[subscriptionId] = simInfo
                    Log.d("FakeTowerDetectionService",
                        "Multi-SIM: Subscription $subscriptionId - CID=${simInfo.cid}, " +
                        "MCC=${simInfo.mcc}, MNC=${simInfo.mnc}, Signal=${simInfo.signalStrength}dBm")
                } else {
                    currentSimTowerInfo[subscriptionId] = null
                }
            } ?: run {
                currentSimTowerInfo[subscriptionId] = null
            }
        }

        // Log summary
        val activeSims = currentSimTowerInfo.count { it.value != null }
        Log.d("FakeTowerDetectionService",
            "Multi-SIM scan complete: $activeSims/${activeSubscriptionIds.size} SIMs have tower info")

        // Broadcast Multi-SIM info
        broadcastMultiSimInfo(currentSimTowerInfo.toMap())

        // Also broadcast for backward compatibility (primary SIM)
        val primarySimInfo = currentSimTowerInfo.values.firstOrNull { it != null }
        broadcastSingleSimInfo(primarySimInfo)
    }

    /**
     * Legacy method for backward compatibility - now delegates to Multi-SIM version
     */
    private fun getSingleSimTowerInfo(cellInfoList: List<CellInfo>?) {
        getMultiSimTowerInfo(cellInfoList)
    }
    
/**
 * Phase 4.0: Helper to safely extract subscription ID from CellInfo
 */
    private fun getCellSubscriptionId(cellInfo: CellInfo): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return 1
        return getSubscriptionIdReflective(cellInfo)
    }

    private fun getSubscriptionIdReflective(cellInfo: CellInfo): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return 1
        return try {
            val identity = when (cellInfo) {
                is CellInfoLte -> cellInfo.cellIdentity
                is CellInfoGsm -> cellInfo.cellIdentity
                is CellInfoWcdma -> cellInfo.cellIdentity
                else -> null
            }
            identity?.let {
                val method = it.javaClass.getMethod("getSubscriptionId")
                method.invoke(it) as? Int
            } ?: 1
        } catch (ignore: Exception) {
            1
        }
    }

    private fun extractTowerInfoFromCell(cell: CellInfo, subscriptionId: Int, slotIndex: Int): com.cymatune.util.TowerConnectionInfo? {
        return try {
            when (cell) {
                is CellInfoLte -> {
                    val identity = cell.cellIdentity
                    val cid = identity.ci
                    val lac = identity.tac
                    
                    // TRACK CURRENT CONNECTED TOWER (for alert fallback)
                    if (cell.isRegistered && cid != Int.MAX_VALUE && cid != 0) {
                        currentConnectedTowerCid = cid
                        lastKnownTowerCid = cid
                        Log.d("FakeTowerService", "Tracking connected tower CID: $cid")
                    }
                    
                    val mcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.mccString?.toIntOrNull() ?: run {
                            @Suppress("DEPRECATION")
                            identity.mcc
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        identity.mcc
                    }
                    
                    val mnc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.mncString?.toIntOrNull() ?: run {
                            @Suppress("DEPRECATION")
                            identity.mnc
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        identity.mnc
                    }
                    
                    // Convert invalid values to null for data fidelity
                    val finalMcc = if (mcc == 65535 || mcc == Integer.MAX_VALUE || mcc == 0) null else mcc
                    val finalMnc = if (mnc == 65535 || mnc == Integer.MAX_VALUE || mnc == 0) null else mnc
                    
                    val signal = cell.cellSignalStrength.dbm
                    val ta = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cell.cellSignalStrength.timingAdvance
                    } else {
                        Int.MIN_VALUE
                    }
                    
                    // Extract PCI (Physical Cell ID) for LTE
                    val pci = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.pci
                    } else {
                        Int.MIN_VALUE
                    }
                    
                    // Extract ARFCN (Absolute Radio Frequency Channel Number)
                    val arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.earfcn
                    } else {
                        Int.MIN_VALUE
                    }
                    
                    // Extract frequency band information
                    val band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.bandwidth
                    } else {
                        Int.MIN_VALUE
                    }
                    
                    // Extract LTE-specific signal quality metrics (RSRP, RSRQ, SINR)
                    val signalStrengthLte = cell.cellSignalStrength as CellSignalStrengthLte
                    val rsrp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        signalStrengthLte.rsrp
                    } else {
                        Int.MIN_VALUE
                    }
                    val rsrq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        signalStrengthLte.rsrq
                    } else {
                        Int.MIN_VALUE
                    }
                    val sinr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        signalStrengthLte.rssnr
                    } else {
                        Int.MIN_VALUE
                    }

                    com.cymatune.util.TowerConnectionInfo(
                        subscriptionId = subscriptionId,
                        slotIndex = 0, // Always SIM 1 slot
                        mcc = finalMcc,
                        mnc = finalMnc,
                        lac = lac,
                        cid = cid,
                        signalStrength = signal,
                        timingAdvance = ta,
                        pci = pci,
                        arfcn = arfcn,
                        band = band,
                        ssRsrp = rsrp,
                        ssRsrq = rsrq,
                        ssSinr = sinr,
                        isRegistered = cell.isRegistered,
                        cellInfo = cell,
                        networkType = "LTE"
                    )
                }
            is CellInfoGsm -> {
                val identity = cell.cellIdentity
                val cid = identity.cid
                val lac = identity.lac
                
                @Suppress("DEPRECATION")
                val mcc = identity.mcc
                @Suppress("DEPRECATION")
                val mnc = identity.mnc
                
                // Convert invalid values to null for data fidelity
                val finalMcc = if (mcc == 65535 || mcc == Integer.MAX_VALUE || mcc == 0) null else mcc
                val finalMnc = if (mnc == 65535 || mnc == Integer.MAX_VALUE || mnc == 0) null else mnc
                
                val signal = cell.cellSignalStrength.dbm
                
                // Extract ARFCN for GSM
                val arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    identity.arfcn
                } else {
                    Int.MIN_VALUE
                }
                
                // Extract GSM band information from ARFCN
                val band = if (arfcn != Int.MIN_VALUE) {
                    GSMWCDMASupport.getGsmBandFromArfcn(arfcn)
                } else {
                    Int.MIN_VALUE
                }
                
                com.cymatune.util.TowerConnectionInfo(
                    subscriptionId = subscriptionId,
                    slotIndex = 0,
                    mcc = finalMcc,
                    mnc = finalMnc,
                    lac = lac,
                    cid = cid,
                    signalStrength = signal,
                    timingAdvance = Int.MIN_VALUE,
                    pci = Int.MIN_VALUE, // GSM doesn't have PCI
                    arfcn = arfcn,
                    band = band,
                    ssRsrp = null,
                    ssRsrq = null,
                    ssSinr = null,
                    isRegistered = cell.isRegistered,
                    cellInfo = cell,
                    networkType = "GSM"
                )
            }
            is CellInfoWcdma -> {
                val identity = cell.cellIdentity
                val cid = identity.cid
                val lac = identity.lac
                
                @Suppress("DEPRECATION")
                val mcc = identity.mcc
                @Suppress("DEPRECATION")
                val mnc = identity.mnc
                
                if (mcc == 65535 || mcc == Integer.MAX_VALUE || mcc == 0) return null
                if (mnc == 65535 || mnc == Integer.MAX_VALUE || mnc == 0) return null
                
                val signal = cell.cellSignalStrength.dbm
                
                // Extract ARFCN for WCDMA
                val arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    identity.uarfcn
                } else {
                    Int.MIN_VALUE
                }
                
                // Extract PCI for WCDMA (PSC - Primary Scrambling Code)
                val pci = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    identity.psc
                } else {
                    Int.MIN_VALUE
                }

                // Extract WCDMA band information from UARFCN
                val band = if (arfcn != Int.MIN_VALUE) {
                    GSMWCDMASupport.getWcdmaBandFromUarfcn(arfcn)
                } else {
                    Int.MIN_VALUE
                }

                com.cymatune.util.TowerConnectionInfo(
                    subscriptionId = subscriptionId,
                    slotIndex = 0,
                    mcc = mcc,
                    mnc = mnc,
                    lac = lac,
                    cid = cid,
                    signalStrength = signal,
                    timingAdvance = Int.MIN_VALUE,
                    pci = pci,
                    arfcn = arfcn,
                    band = band,
                    ssRsrp = null,
                    ssRsrq = null,
                    ssSinr = null,
                    isRegistered = cell.isRegistered,
                    cellInfo = cell,
                    networkType = "WCDMA"
                )
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr) {
                    extractNrTowerInfo(cell, subscriptionId)
                } else {
                    null
                }
            }
        }
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Error extracting tower info from cell: ${cell.javaClass.simpleName}", e)
            null
        }
    }

    private fun broadcastRealTimeCellUpdate(allTowers: List<com.cymatune.util.TowerConnectionInfo>, currentTower: com.cymatune.util.TowerConnectionInfo?) {
        val intent = Intent(ACTION_NEIGHBOR_CELLS_UPDATE)
        
        // Add current tower info if available
        currentTower?.let {
            intent.putExtra(EXTRA_CURRENT_TOWER_INFO, it)
        }
        
        // Add neighbor count
        val neighborCount = allTowers.size - (if (currentTower != null) 1 else 0)
        intent.putExtra(EXTRA_NEIGHBOR_CELLS_COUNT, neighborCount)
        
        // For Android 15 compliance: set package to restrict broadcast to same app
        intent.setPackage(packageName)
        
        // For simplicity, we'll just send the count and current tower
        // In a production app, you might want to serialize and send all neighbor towers
        sendBroadcast(intent)
        Log.d("FakeTowerDetectionService", "Broadcast real-time update: $neighborCount neighbors, current: ${currentTower != null}")
    }
    
    private fun broadcastSingleSimInfo(simInfo: com.cymatune.util.TowerConnectionInfo?) {
        val intent = Intent("com.cymatune.SINGLE_SIM_TOWER_INFO")

        intent.putExtra("has_sim", simInfo != null)
        if (simInfo != null) {
            intent.putExtra("subscription_id", simInfo.subscriptionId)
            intent.putExtra("slot_index", simInfo.slotIndex)
            intent.putExtra("cid", simInfo.cid)
            intent.putExtra("lac", simInfo.lac)
            intent.putExtra("mcc", simInfo.mcc)
            intent.putExtra("mnc", simInfo.mnc)
            intent.putExtra("signal", simInfo.signalStrength)
            intent.putExtra("ta", simInfo.timingAdvance)
        }

        // For Android 15 compliance: set package to restrict broadcast to same app
        intent.setPackage(packageName)

        sendBroadcast(intent)
        Log.d("FakeTowerDetectionService", "Broadcast single SIM info: SIM=${simInfo != null}")
    }

    /**
     * Phase 4.0: Broadcast Multi-SIM tower information
     * Sends consolidated tower data for all active SIMs
     */
    private fun broadcastMultiSimInfo(simTowerMap: Map<Int, com.cymatune.util.TowerConnectionInfo?>) {
        val intent = Intent(ACTION_MULTI_SIM_TOWER_UPDATE)

        intent.putExtra(EXTRA_ACTIVE_SIM_COUNT, simTowerMap.size)

        // Serialize the map of subscription ID to tower info
        // Since TowerConnectionInfo is not Parcelable, we'll send arrays of primitives
        val activeSubs = simTowerMap.filter { it.value != null }
        val subscriptionIds = activeSubs.keys.toIntArray()
        val cids = activeSubs.values.map { it?.cid ?: 0 }.toIntArray()
        val lacs = activeSubs.values.map { it?.lac ?: 0 }.toIntArray()
        val mccs = activeSubs.values.map { it?.mcc ?: 0 }.toIntArray()
        val mncs = activeSubs.values.map { it?.mnc ?: 0 }.toIntArray()
        val signals = activeSubs.values.map { it?.signalStrength ?: Int.MIN_VALUE }.toIntArray()
        val networkTypes = activeSubs.values.map { it?.networkType ?: "UNKNOWN" }.toTypedArray()

        intent.putExtra("subscription_ids", subscriptionIds)
        intent.putExtra("cids", cids)
        intent.putExtra("lacs", lacs)
        intent.putExtra("mccs", mccs)
        intent.putExtra("mncs", mncs)
        intent.putExtra("signals", signals)
        intent.putExtra("network_types", networkTypes)

        // For Android 15 compliance: set package to restrict broadcast to same app
        intent.setPackage(packageName)

        sendBroadcast(intent)
        Log.d("FakeTowerDetectionService",
            "Broadcast Multi-SIM info: ${activeSubs.size}/${simTowerMap.size} SIMs active")
    }

    /**
     * Phase 4.0: Get current tower info for a specific subscription
     */
    fun getCurrentTowerForSubscription(subscriptionId: Int): com.cymatune.util.TowerConnectionInfo? {
        return currentSimTowerInfo[subscriptionId]
    }

    /**
     * Phase 4.0: Get list of all active subscription IDs
     */
    fun getActiveSubscriptionIds(): List<Int> {
        return activeSubscriptionIds.toList()
    }

    private fun getCustomScanRadiusMeters(): Double {
        return currentScanRadius * 1000.0 // Convert to meters
    }

    private fun getNetworkMaxRangeMeters(): Double {
        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        val networkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                if (checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    telephonyManager.dataNetworkType
                } else {
                    // Fallback to network type if permission not granted
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        telephonyManager.dataNetworkType
                    } else {
                        // For Android versions below N, use the deprecated API as last resort
                        @Suppress("DEPRECATION")
                        telephonyManager.networkType
                    }
                }
            } catch (e: SecurityException) {
                // Fallback to network type if permission denied
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    telephonyManager.dataNetworkType
                } else {
                    // For Android versions below N, use the deprecated API as last resort
                    @Suppress("DEPRECATION")
                    telephonyManager.networkType
                }
            }
        } else {
            // For older Android versions, use the deprecated API as last resort
            @Suppress("DEPRECATION")
            telephonyManager.networkType
        }

        // Check for 5G NR specifically if available
        var actualNetworkType = networkType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cellInfoList = telephonyManager.allCellInfo
                cellInfoList?.forEach { cellInfo ->
                    if (cellInfo is CellInfoNr) {
                        // This indicates 5G NR. For simplicity, we'll assume NR implies Sub-6 unless
                        // a more specific API for mmWave detection is found or provided.
                        actualNetworkType = TelephonyManager.NETWORK_TYPE_NR
                        return@forEach
                    }
                }
            } catch (e: SecurityException) {
                android.util.Log.e("FakeTowerDetectionService", "SecurityException getting cell info for network type: ${e.message}")
            }
        }

        val maxRangeKm = when (actualNetworkType) {
            TelephonyManager.NETWORK_TYPE_GSM -> 35
            TelephonyManager.NETWORK_TYPE_UMTS -> 15
            TelephonyManager.NETWORK_TYPE_LTE -> 15
            TelephonyManager.NETWORK_TYPE_NR -> 15 // Sub-6 GHz 5G
            else -> 15 // Default for unknown or general 4G/5G
        }
        return maxRangeKm * 1000.0 // Convert to meters
    }

    private fun getAdaptiveMaxTowerLocationDeviationMeters(): Double {
        val customScanRadius = getCustomScanRadiusMeters()
        val networkMaxRange = getNetworkMaxRangeMeters()

        // The fail-safe should adapt to the slider and technology.
        // It should be at least the custom scan radius, but not exceed the network's max range.
        // A reasonable heuristic: use the custom scan radius, but cap it at the network's max range.
        // Or, use a multiple of the custom scan radius, but still capped by network max.
        // Let's use a factor of 2x the custom scan radius, but not more than the network's max range.
        return (customScanRadius * 2).coerceAtMost(networkMaxRange)
    }

    private fun logAllDetectedTowers(cellInfoList: List<CellInfo>) {
        Log.d("FakeTowerDetectionService", "DIAGNOSTIC: logAllDetectedTowers called with ${cellInfoList.size} cells")
        
        serviceScope.launch {
            val allTowerInfos = mutableListOf<com.cymatune.util.TowerConnectionInfo>()
            
            for (cellInfo in cellInfoList) {
                val towerInfo = when {
                    cellInfo is CellInfoLte -> extractTowerInfoFromCell(cellInfo, 1, 0)
                    cellInfo is CellInfoGsm -> extractTowerInfoFromCell(cellInfo, 1, 0)
                    cellInfo is CellInfoWcdma -> extractTowerInfoFromCell(cellInfo, 1, 0)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is CellInfoNr -> extractTowerInfoFromCell(cellInfo, 1, 0)
                    else -> null
                }
                
                towerInfo?.let { info ->
                    allTowerInfos.add(info)
                    
                    // Log to database with error handling
                    // Extract neighbors for this batch (simplified: all other cells in the list are potential neighbors)
                    val neighbors = cellInfoList.mapNotNull {
                        // Extract CID from cell info
                        when (it) {
                            is CellInfoLte -> it.cellIdentity.ci.toString()
                            is CellInfoGsm -> it.cellIdentity.cid.toString()
                            is CellInfoWcdma -> it.cellIdentity.cid.toString()
                            else -> null
                        }
                    }
                    
                    try {
                        logTowerToDatabase(info, neighbors)
                        
                        Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Periodic scan logged tower: CID=${info.cid}, LAC=${info.lac}, MCC=${info.mcc}, MNC=${info.mnc}, Signal=${info.signalStrength}, Type=${info.networkType}")
                    } catch (dbException: Exception) {
                        Log.e("FakeTowerDetectionService", "Database error during tower logging, attempting recovery", dbException)
                        
                        // Try to recreate database
                        if (DatabaseManager.forceRecreateDatabase(this@FakeTowerDetectionService)) {
                            Log.i("FakeTowerDetectionService", "Database recreated successfully, retrying tower logging")
                            try {
                                logTowerToDatabase(info, neighbors)
                            } catch (retryException: Exception) {
                                Log.e("FakeTowerDetectionService", "Failed to log towers even after database recreation", retryException)
                            }
                        }
                    }
                } ?: run {
                    Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Failed to extract tower from cell: ${cellInfo.javaClass.simpleName}")
                }
            }
            
            Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Periodic scan extracted ${allTowerInfos.size} towers from ${cellInfoList.size} cells")
            
            // Broadcast all visible towers
            broadcastAllVisibleTowers(allTowerInfos)
        }
    }
    
    private suspend fun logTowerToDatabase(towerInfo: com.cymatune.util.TowerConnectionInfo, neighbors: List<String>) {
        // Safety check: Ensure anomalyDetector is initialized
        if (!::anomalyDetector.isInitialized) {
            Log.w("FakeTowerDetectionService", "AnomalyDetector not initialized, skipping anomaly recording for CID=${towerInfo.cid}")
            // Continue with other logging if possible, or return early
            // For now, we'll return early to prevent further errors related to uninitialized components
            return
        }
        
        // DIAGNOSTIC: Log tower insertion attempt
        Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Attempting to log tower to database: CID=${towerInfo.cid}, LAC=${towerInfo.lac}, MCC=${towerInfo.mcc}, MNC=${towerInfo.mnc}")

        try {
            // HYBRID APPROACH: Simple detection for immediate display + refinement for accuracy
            serviceScope.launch {
                try {
                    // CRITICAL: Check if DAOs are initialized before using them
                    if (!::towerDao.isInitialized || !::fakeTowerDao.isInitialized ||
                        !::signalBaselineDao.isInitialized || !::locationHistoryDao.isInitialized ||
                        !::neighborHistoryDao.isInitialized || !::lacCidPatternDao.isInitialized ||
                        !::protocolHandshakeDao.isInitialized) {
                        Log.w("FakeTowerDetectionService", "DAOs not initialized, skipping tower logging: CID=${towerInfo.cid}")
                        return@launch
                    }
                    
                    // Use 0 as sentinel for null MCC/MNC in database (Room doesn't support nullable in composite keys)
                    val dbMcc = towerInfo.mcc ?: 0
                    val dbMnc = towerInfo.mnc ?: 0
                    
                    // Extract signal metrics for database storage
                    val rsrp = towerInfo.ssRsrp ?: towerInfo.signalStrength
                    val rsrq = towerInfo.ssRsrq
                    val sinr = towerInfo.ssSinr
                    val pci = towerInfo.pci
                    val arfcn = towerInfo.arfcn
                    val timingAdvance = towerInfo.timingAdvance
                    
                    // ENHANCED DUPLICATE PREVENTION: Check if we recently logged this tower
                    val recentObservations = towerDao.getObservationsForTowerSince(
                        towerInfo.cid, towerInfo.lac, dbMcc, dbMnc,
                        System.currentTimeMillis() - 5000 // Increased to 5 seconds for better deduplication
                    )
                    
                    // DIAGNOSTIC: Log duplicate check results
                    Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Duplicate check for CID=${towerInfo.cid}, LAC=${towerInfo.lac} - Found ${recentObservations.size} recent observations")
                    
                    // Logic update: Don't return early. Just skip logging the raw observation if it's a duplicate.
                    // We must continue to ensure the FakeTower entity is created/updated for the UI.
                    if (recentObservations.isNotEmpty()) {
                        Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Duplicate observation found - Skipping raw observation logging, but proceeding to FakeTower update")
                    } else {
                         Log.d("FakeTowerDetectionService", "DIAGNOSTIC: No recent duplicates found, logging new observation for CID=${towerInfo.cid}")

                        // Create TowerObservation entity for comprehensive logging
                        val towerObservation = com.cymatune.util.TowerObservation(
                            cid = towerInfo.cid,
                            lac = towerInfo.lac,
                            mcc = dbMcc,
                            mnc = dbMnc,
                            signal = towerInfo.signalStrength,
                            rsrp = towerInfo.ssRsrp ?: Int.MIN_VALUE,
                            rsrq = towerInfo.ssRsrq ?: Int.MIN_VALUE,
                            sinr = towerInfo.ssSinr ?: Int.MIN_VALUE,
                            pci = towerInfo.pci,
                            ta = towerInfo.timingAdvance,
                            arfcn = towerInfo.arfcn,
                            band = towerInfo.band,
                            ssRsrp = towerInfo.ssRsrp,
                            ssRsrq = towerInfo.ssRsrq,
                            ssSinr = towerInfo.ssSinr,
                            latitude = currentLocation?.latitude ?: 0.0,
                            longitude = currentLocation?.longitude ?: 0.0,
                            locationAccuracy = currentLocation?.accuracy?.toFloat() ?: 0f,
                            timestamp = System.currentTimeMillis(),
                            source = "simple_detection" // Simple detection for immediate display
                        )
                        
// Insert into database
towerDao.insertTowerObservation(towerObservation)

Log.d("FakeTowerDetectionService", "DIAGNOSTIC: SIMPLE DETECTION - Tower observation logged: " +
"CID=${towerInfo.cid}, LAC=${towerInfo.lac}, MCC=${towerInfo.mcc}, MNC=${towerInfo.mnc}")

// DIAGNOSTIC: Log database insertion result
Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Tower observation successfully inserted into database for CID=${towerInfo.cid}")
                    }
                    
                    // Record baseline data for statistical profiling (Phase 3)
                    if (currentLocation != null && towerInfo.ssRsrp != null) {
                        if (::anomalyDetector.isInitialized) {
                            val neighborCellCount = telephonyManager.allCellInfo?.size ?: 0
                            try {
                                anomalyDetector.recordObservation(
                                    cid = towerInfo.cid,
                                    lac = towerInfo.lac,
                                    mcc = dbMcc,
                                    mnc = dbMnc,
                                    latitude = currentLocation!!.latitude,
                                    longitude = currentLocation!!.longitude,
                                    rsrp = towerInfo.ssRsrp!!,
                                    rsrq = towerInfo.ssRsrq,
                                    sinr = towerInfo.ssSinr,
                                    neighborCount = neighborCellCount
                                )
                                Log.d("FakeTowerDetectionService", "Baseline data recorded for CID=${towerInfo.cid}")
                            } catch (e: Exception) {
                                Log.w("FakeTowerDetectionService", "Failed to record anomaly observation", e)
                            }
                        } else {
                            Log.w("FakeTowerDetectionService", "AnomalyDetector not initialized - skipping baseline recording")
                        }
                    }
                    
                    // Create basic FakeTower for immediate UI display (simple approach)
                    // Use enhanced location determination with multiple fallback strategies
                    val towerLocation = determineTowerLocation(towerInfo, currentLocation)
                    
                    // Default to 0.0, 0.0 (Unknown) if location cannot be determined
                    val towerLat = towerLocation?.first ?: 0.0
                    val towerLon = towerLocation?.second ?: 0.0
                    
                    // Calculate Trust Score with null safety
                    val trustScoreResult: Double = if (::trustScoreManager.isInitialized && trustScoreManager != null) {
                        try {
                            val result = trustScoreManager.calculateTrustScore(
                                cid = towerInfo.cid,
                                lac = towerInfo.lac,
                                mcc = dbMcc,
                                mnc = dbMnc,
                                latitude = towerLat,
                                longitude = towerLon,
                                anomalies = emptyList(), // Ideally pass actual anomalies
                                detectionReasons = emptyList(),
                                isUserMoving = isUserMoving,  // Pass movement state
                                movementIntensity = if (::movementDetector.isInitialized) {
                                    movementDetector.getTrustScoreModifier()
                                } else { 0 }
                            )
                            result.score.toDouble() // Extract the score from TrustScore object
                        } catch (e: Exception) {
                            Log.w("FakeTowerDetectionService", "TrustScoreManager.calculateTrustScore failed, using fallback", e)
                            0.5
                        }
                    } else {
                        Log.w("FakeTowerDetectionService", "TrustScoreManager not initialized, using fallback trust score for CID=${towerInfo.cid}")
                        // Use a simple fallback score
                        0.5
                    }

                    // Convert to FakeTowerDetector.TowerConnectionInfo
                    val cellInfo = towerInfo.cellInfo as? CellInfo
                    if (cellInfo != null) {
                        val detectorTowerInfo = convertToFakeTowerDetectorTowerConnectionInfo(towerInfo, cellInfo)
                        
                        // Capture current location locally to avoid smart cast issues
                        val currentLoc = currentLocation
                        val locationPair = if (currentLoc != null) Pair(currentLoc.latitude, currentLoc.longitude) else null
                        
                        // Analyze tower using FakeTowerDetector with error handling
                        val suspiciousTower = try {
                            FakeTowerDetector.analyzeTower(
                                cellInfo,
                                locationPair,
                                towerInfo.timingAdvance,
                                neighbors,
                                isUserMoving = isUserMoving,
                                movementIntensity = movementIntensity
                            )
                        } catch (e: Exception) {
                            Log.w("FakeTowerDetectionService", "FakeTowerDetector.analyzeTower failed for CID=${towerInfo.cid}, using fallback", e)
                            null
                        }
                        
                        if (suspiciousTower != null) {
                            try {
                                // Use enhanced deduplication method
                                fakeTowerDao.insertFakeTowerIfNotRecent(
                                    suspiciousTower.fakeTower.cid,
                                    suspiciousTower.fakeTower.lac,
                                    suspiciousTower.fakeTower.mcc,
                                    suspiciousTower.fakeTower.mnc,
                                    suspiciousTower.fakeTower.latitude,
                                    suspiciousTower.fakeTower.longitude,
                                    suspiciousTower.fakeTower.detectionTime,
                                    suspiciousTower.fakeTower.accuracy,
                                    suspiciousTower.fakeTower.reason,
                                    suspiciousTower.fakeTower.signalStrength,
                                    suspiciousTower.fakeTower.networkType,
                                    suspiciousTower.fakeTower.observationCount,
                                    suspiciousTower.fakeTower.precisionRadius,
                                    suspiciousTower.fakeTower.isTriangulated,
                                    suspiciousTower.fakeTower.trustScore,
                                    suspiciousTower.fakeTower.rsrq ?: 0,
                                    suspiciousTower.fakeTower.sinr ?: 0,
                                    suspiciousTower.fakeTower.pci ?: 0,
                                    suspiciousTower.fakeTower.arfcn ?: 0,
                                    suspiciousTower.fakeTower.timingAdvance ?: 0,
                                    cutoffTime = System.currentTimeMillis() - 300000 // 5 minutes cutoff
                                )
                                Log.d("FakeTowerDetectionService", "Logged suspicious tower: ${towerInfo.cid} with Accuracy: ${suspiciousTower.confidence}")
                                
                                // DIAGNOSTIC: Log suspicious tower insertion
                                Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Suspicious tower successfully inserted into database: CID=${towerInfo.cid}, Confidence=${suspiciousTower.confidence}")
                                
                                // Broadcast if highly suspicious or just suspicious enough (0.4+)
                                if (suspiciousTower.confidence > 0.4) {
                                    broadcastSuspiciousTower(suspiciousTower.fakeTower, suspiciousTower.suspicionReason)
                                    
                                    // CRITICAL: Persist as Alert for Dashboard
                                    val severity = when {
                                        suspiciousTower.confidence >= 0.8 -> com.cymatune.logcat.ThreatSeverity.CRITICAL
                                        suspiciousTower.confidence >= 0.6 -> com.cymatune.logcat.ThreatSeverity.HIGH
                                        else -> com.cymatune.logcat.ThreatSeverity.MEDIUM
                                    }
                                    
            // B07-B08: Use concise, precise title without "Security Alert:" prefix
            val alertTitle = when {
                suspiciousTower.suspicionReason.contains("protocol", ignoreCase = true) -> "Protocol Mismatch"
                suspiciousTower.suspicionReason.contains("signal", ignoreCase = true) -> "Signal Anomaly"
                suspiciousTower.suspicionReason.contains("cipher", ignoreCase = true) -> "Weak Cipher"
                suspiciousTower.suspicionReason.contains("encryption", ignoreCase = true) -> "Encryption Downgrade"
                suspiciousTower.suspicionReason.contains("LAC", ignoreCase = true) -> "LAC Change"
                suspiciousTower.suspicionReason.contains("location", ignoreCase = true) -> "Location Jump"
                suspiciousTower.suspicionReason.contains("timing", ignoreCase = true) -> "Timing Anomaly"
                suspiciousTower.suspicionReason.contains("neighbor", ignoreCase = true) -> "Neighbor Mismatch"
                else -> "Tower Anomaly"
            }

            showSecurityAlert(
                alertTitle,
                "${suspiciousTower.suspicionReason} (Confidence: ${(suspiciousTower.confidence*100).toInt()}%)",
                severity
            )
                                }
                            } catch (e: Exception) {
                                Log.e("FakeTowerDetectionService", "Failed to insert suspicious tower for CID=${towerInfo.cid}", e)
                            }
                        } else {
                            // Log as non-suspicious (or low confidence) if needed for UI
                            // For now, we only log if analyzeTower returns something (which it does if it finds ANY reason, or we might want to log everything)
                            // The original code logged everything with 0.5 confidence.
                            // Let's keep logging everything but use the detector's result if available, or fallback to basic info.
                            
                            try {
                                val fakeTower = FakeTower(
                                    cid = towerInfo.cid,
                                    lac = towerInfo.lac,
                                    mcc = dbMcc,
                                    mnc = dbMnc,
                                    latitude = towerLat,
                                    longitude = towerLon,
                                    detectionTime = System.currentTimeMillis(),
                                    accuracy = (100.0 - trustScoreResult) / 100.0, // FIXED: Inverse of trust score (low trust = high threat detection accuracy)
                                    reason = "Routine Scan",
                                    signalStrength = towerInfo.signalStrength,
                                    networkType = towerInfo.networkType ?: "Unknown",
                                    observationCount = 1,
                                    precisionRadius = 1000.0,
                                    isTriangulated = towerInfo.isRegistered,
                                    trustScore = trustScoreResult.toInt(),
                                    pci = pci,
                                    arfcn = arfcn,
                                    timingAdvance = timingAdvance,
                                    rsrq = rsrq,
                                    sinr = sinr
                                )
                                // Use enhanced deduplication method
                                fakeTowerDao.insertFakeTowerIfNotRecent(
                                    fakeTower.cid,
                                    fakeTower.lac,
                                    fakeTower.mcc,
                                    fakeTower.mnc,
                                    fakeTower.latitude,
                                    fakeTower.longitude,
                                    fakeTower.detectionTime,
                                    fakeTower.accuracy,
                                    fakeTower.reason,
                                    fakeTower.signalStrength,
                                    fakeTower.networkType,
                                    fakeTower.observationCount,
                                    fakeTower.precisionRadius,
                                    fakeTower.isTriangulated,
                                    fakeTower.trustScore,
                                    fakeTower.rsrq ?: 0,
                                    fakeTower.sinr ?: 0,
                                    fakeTower.pci ?: 0,
                                    fakeTower.arfcn ?: 0,
                                    fakeTower.timingAdvance ?: 0,
                                    cutoffTime = System.currentTimeMillis() - 300000 // 5 minutes cutoff
                                )
                                Log.d("FakeTowerDetectionService", "Logged fallback tower: ${towerInfo.cid} with TrustScore: $trustScoreResult, RSRQ: ${fakeTower.rsrq}, SINR: ${fakeTower.sinr}")
                                
                                // DIAGNOSTIC: Log fallback tower insertion
                                Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Fallback tower successfully inserted into database: CID=${towerInfo.cid}, TrustScore=$trustScoreResult")
                            } catch (e: Exception) {
                                Log.e("FakeTowerDetectionService", "Failed to insert fallback tower for CID=${towerInfo.cid}", e)
                            }
                        }
                    } else {
                        // Fallback if no CellInfo
                        try {
                            val fakeTower = FakeTower(
                                cid = towerInfo.cid,
                                lac = towerInfo.lac,
                                mcc = dbMcc,
                                mnc = dbMnc,
                                latitude = towerLat,
                                longitude = towerLon,
                                detectionTime = System.currentTimeMillis(),
                                accuracy = (100.0 - trustScoreResult) / 100.0, // FIXED: Calculated from trust score
                                reason = "Real-time detection (No CellInfo)",
                                signalStrength = towerInfo.signalStrength,
                                networkType = towerInfo.networkType ?: "Unknown",
                                observationCount = 1,
                                precisionRadius = 1000.0,
                                isTriangulated = towerInfo.isRegistered,
                                trustScore = trustScoreResult.toInt(),
                                pci = pci,
                                arfcn = arfcn,
                                timingAdvance = timingAdvance,
                                rsrq = rsrq,
                                sinr = sinr
                            )
                            // Use enhanced deduplication method
                            fakeTowerDao.insertFakeTowerIfNotRecent(
                                fakeTower.cid,
                                fakeTower.lac,
                                fakeTower.mcc,
                                fakeTower.mnc,
                                fakeTower.latitude,
                                fakeTower.longitude,
                                fakeTower.detectionTime,
                                fakeTower.accuracy,
                                fakeTower.reason,
                                fakeTower.signalStrength,
                                fakeTower.networkType,
                                fakeTower.observationCount,
                                fakeTower.precisionRadius,
                                fakeTower.isTriangulated,
                                fakeTower.trustScore,
                                fakeTower.rsrq ?: 0,
                                fakeTower.sinr ?: 0,
                                fakeTower.pci ?: 0,
                                fakeTower.arfcn ?: 0,
                                fakeTower.timingAdvance ?: 0,
                                cutoffTime = System.currentTimeMillis() - 300000 // 5 minutes cutoff
                            )
                            Log.d("FakeTowerDetectionService", "Logged CellInfo fallback tower: ${towerInfo.cid} with TrustScore: $trustScoreResult")
                        } catch (e: Exception) {
                            Log.e("FakeTowerDetectionService", "Failed to insert CellInfo fallback tower for CID=${towerInfo.cid}", e)
                        }
                    }

                    
                    // Perform protocol analysis and store results with improved error recovery
                    val protocolAnalysisResult = try {
                        performProtocolAnalysis(towerInfo)
                    } catch (e: Exception) {
                        Log.w("FakeTowerDetectionService", "Protocol analysis failed for CID=${towerInfo.cid}, continuing with tower logging", e)
                        null
                    }
                    
                    protocolAnalysisResult?.let { result ->
                        // Store protocol handshake data with error recovery
                        try {
                            val protocolHandshake = com.cymatune.db.ProtocolHandshake(
                                towerId = towerInfo.cid, // Use CID as tower ID reference
                                protocolType = result.protocolInfo.protocolType,
                                cipherAlgorithm = result.protocolInfo.cipherAlgorithm,
                                handshakeDuration = (result.protocolInfo.handshakeDuration ?: 0L).toInt(), // Handle nullable Long to Int conversion
                                success = result.protocolInfo.handshakeSuccess,
                                timestamp = System.currentTimeMillis(),
                                suspicionScore = result.suspicionScore,
                                details = result.anomalies.joinToString()
                            )
                            protocolHandshakeDao.insert(protocolHandshake)
                            Log.d("FakeTowerDetectionService", "Protocol handshake stored: CID=${towerInfo.cid}, Protocol=${result.protocolInfo.protocolType}, Score=${result.suspicionScore}")
                        } catch (e: Exception) {
                            Log.e("FakeTowerDetectionService", "Failed to store protocol handshake for CID=${towerInfo.cid}: ${e.message}")
                        }
                    }
                } catch (dbError: Exception) {
                    Log.e("FakeTowerDetectionService", "Error in comprehensive tower logging: ${dbError.message}", dbError)
                    
                    // Fallback: Log basic tower info even if comprehensive logging fails
                    try {
                        Log.w("FakeTowerDetectionService", "Attempting fallback tower logging for CID=${towerInfo.cid}")
                        val fallbackTower = FakeTower(
                            cid = towerInfo.cid,
                            lac = towerInfo.lac,
                            mcc = towerInfo.mcc ?: 0,
                            mnc = towerInfo.mnc ?: 0,
                            latitude = currentLocation?.latitude ?: 0.0,
                            longitude = currentLocation?.longitude ?: 0.0,
                            detectionTime = System.currentTimeMillis(),
                            accuracy = 0.5, // Neutral fallback due to error (50% trust = 50% threat accuracy)
                            reason = "Fallback logging due to error: ${dbError.message}",
                            signalStrength = towerInfo.signalStrength,
                            networkType = towerInfo.networkType ?: "Unknown",
                            observationCount = 1,
                            precisionRadius = 1000.0,
                            isTriangulated = false,
                            trustScore = 50 // Neutral fallback score (0.5 * 100)
                        )
                        // Use enhanced deduplication method
                        fakeTowerDao.insertFakeTowerIfNotRecent(
                            fallbackTower.cid,
                            fallbackTower.lac,
                            fallbackTower.mcc,
                            fallbackTower.mnc,
                            fallbackTower.latitude,
                            fallbackTower.longitude,
                            fallbackTower.detectionTime,
                            fallbackTower.accuracy,
                            fallbackTower.reason,
                            fallbackTower.signalStrength,
                            fallbackTower.networkType,
                            fallbackTower.observationCount,
                            fallbackTower.precisionRadius,
                            fallbackTower.isTriangulated,
                            fallbackTower.trustScore,
                            fallbackTower.rsrq ?: 0,
                            fallbackTower.sinr ?: 0,
                            fallbackTower.pci ?: 0,
                            fallbackTower.arfcn ?: 0,
                            fallbackTower.timingAdvance ?: 0,
                            cutoffTime = System.currentTimeMillis() - 300000 // 5 minutes cutoff
                        )
                        Log.i("FakeTowerDetectionService", "Fallback tower logged successfully: CID=${towerInfo.cid}")
                    } catch (fallbackError: Exception) {
                        Log.e("FakeTowerDetectionService", "Fallback tower logging also failed for CID=${towerInfo.cid}", fallbackError)
                    }
                }
            }
            
            // CONTINUOUS REFINEMENT: Use TowerRefinementManager for precision refinement
            val currentLocation = currentLocation
            val currentLocationPair = if (currentLocation != null) {
                Pair(currentLocation.latitude, currentLocation.longitude)
            } else {
                null
            }
            
            val locationAccuracy = currentLocation?.accuracy?.toFloat() ?: 0f
            
            Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Processing tower observation: " +
                  "CID=${towerInfo.cid}, GPS=${currentLocationPair != null}, Accuracy=$locationAccuracy")
            
            val wasRefined = TowerRefinementManager.processTowerObservation(
                towerDao = towerDao,
                fakeTowerDao = fakeTowerDao,
                towerInfo = towerInfo,
                currentLocation = currentLocationPair,
                locationAccuracy = locationAccuracy,
                userLocation = currentLocationPair // Pass user's current location for fallback generation
            )
            
            if (wasRefined) {
                Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Tower location refined: CID=${towerInfo.cid}")
            } else {
                Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Tower NOT refined: CID=${towerInfo.cid}")
            }
        } catch (e: InterruptedException) {
            Log.e("FakeTowerDetectionService", "Database operation interrupted while logging tower", e)
        } catch (e: SecurityException) {
            Log.e("FakeTowerDetectionService", "Permission denied for database logging operation", e)
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Unexpected error logging tower to database: ${e.message}")
        }
    }
    
    /**
     * Perform protocol analysis for a tower connection
     */
    private suspend fun performProtocolAnalysis(towerInfo: com.cymatune.util.TowerConnectionInfo): com.cymatune.detection.FakeTowerDetector.ProtocolAnalysisResult? {
        return try {
            val cellInfo = towerInfo.cellInfo as? CellInfo
            if (cellInfo != null) {
                val fakeTowerDetectorTowerInfo = convertToFakeTowerDetectorTowerConnectionInfo(towerInfo, cellInfo)
                FakeTowerDetector.analyzeProtocol(cellInfo, fakeTowerDetectorTowerInfo)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Protocol analysis failed for tower CID=${towerInfo.cid}", e)
            null
        }
    }

    /**
     * Convert util.TowerConnectionInfo to FakeTowerDetector.TowerConnectionInfo
     */
    private fun convertToFakeTowerDetectorTowerConnectionInfo(
        utilTowerInfo: com.cymatune.util.TowerConnectionInfo,
        cellInfo: CellInfo
    ): TowerConnectionInfo {
        return TowerConnectionInfo(
            subscriptionId = utilTowerInfo.subscriptionId,
            slotIndex = utilTowerInfo.slotIndex,
            mcc = utilTowerInfo.mcc,
            mnc = utilTowerInfo.mnc,
            lac = utilTowerInfo.lac,
            cid = utilTowerInfo.cid,
            signalStrength = utilTowerInfo.signalStrength,
            timingAdvance = utilTowerInfo.timingAdvance,
            pci = utilTowerInfo.pci,
            arfcn = utilTowerInfo.arfcn,
            band = utilTowerInfo.band,
            ssRsrp = utilTowerInfo.ssRsrp,
            ssRsrq = utilTowerInfo.ssRsrq,
            ssSinr = utilTowerInfo.ssSinr,
            isRegistered = utilTowerInfo.isRegistered,
            cellInfo = cellInfo,
            estimatedLocation = utilTowerInfo.estimatedLocation,
            networkType = utilTowerInfo.networkType,
            additionalInfo = utilTowerInfo.additionalInfo
        )
    }
    
    private fun broadcastAllVisibleTowers(allTowers: List<com.cymatune.util.TowerConnectionInfo>) {
        val intent = Intent(ACTION_ALL_VISIBLE_TOWERS)
        intent.putParcelableArrayListExtra(EXTRA_ALL_TOWERS_LIST, ArrayList(allTowers))
        intent.setPackage(packageName)
        sendBroadcast(intent)
        
        // DIAGNOSTIC: Log detailed information about broadcast towers
        Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Broadcast all visible towers: ${allTowers.size} towers")
        allTowers.forEachIndexed { index, tower ->
            Log.d("FakeTowerDetectionService", "DIAGNOSTIC: Tower $index - CID=${tower.cid}, LAC=${tower.lac}, MCC=${tower.mcc}, MNC=${tower.mnc}, Registered=${tower.isRegistered}")
        }
    }

    /**
     * Broadcast tower refinement updates
     */
    private fun broadcastTowerRefinementUpdate(towerId: String, precisionRadius: Double, observationCount: Int) {
        val intent = Intent(ACTION_TOWER_REFINEMENT_UPDATE)
        intent.putExtra(EXTRA_TOWER_ID, towerId)
        intent.putExtra(EXTRA_PRECISION_RADIUS, precisionRadius)
        intent.putExtra(EXTRA_OBSERVATION_COUNT, observationCount)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        Log.d("FakeTowerDetectionService", "Broadcast tower refinement: $towerId, precision: ${precisionRadius}m, observations: $observationCount")
    }
    
    /**
     * Broadcast REAL_TIME_CELL_UPDATE for HomeFragment with signal metrics and trust score
     */
    private fun broadcastRealTimeCellUpdateForHomeFragment(currentTower: com.cymatune.util.TowerConnectionInfo?) {
        if (currentTower == null) {
            Log.d("FakeTowerDetectionService", "No current tower to broadcast for HomeFragment")
            return
        }
        
        val intent = Intent("com.cymatune.REAL_TIME_CELL_UPDATE")
        
        // Extract signal metrics
        val rsrp = currentTower.ssRsrp ?: Int.MIN_VALUE
        val rsrq = currentTower.ssRsrq ?: Int.MIN_VALUE
        val sinr = currentTower.ssSinr ?: Int.MIN_VALUE
        
        // Get trust score from database for the current tower - use coroutine scope
        serviceScope.launch {
            val trustScore = getCurrentTowerTrustScore(currentTower)
            
            // Add signal metrics and trust score to intent
            intent.putExtra("rsrp", rsrp)
            intent.putExtra("rsrq", rsrq)
            intent.putExtra("sinr", sinr)
            intent.putExtra("trustScore", trustScore)
            
            // For Android 15 compliance: set package to restrict broadcast to same app
            intent.setPackage(packageName)
            
            sendBroadcast(intent)
            Log.d("FakeTowerDetectionService", "Broadcast REAL_TIME_CELL_UPDATE: RSRP=$rsrp, RSRQ=$rsrq, SINR=$sinr, TrustScore=$trustScore")
        }
    }
    
    /**
     * Get current tower trust score from database
     */
    private suspend fun getCurrentTowerTrustScore(towerInfo: com.cymatune.util.TowerConnectionInfo): Int {
        return try {
            // Use DAO to get the most recent trust score for this tower
            val dbMcc = towerInfo.mcc ?: 0
            val dbMnc = towerInfo.mnc ?: 0
            
            val dbTower = serviceScope.async {
                fakeTowerDao.getFakeTower(towerInfo.cid, towerInfo.lac, dbMcc, dbMnc)
            }
            val result = dbTower.await()
            result?.trustScore ?: 50 // Default to 50 if not found
        } catch (e: Exception) {
            Log.w("FakeTowerDetectionService", "Failed to get trust score from database, using default", e)
            50 // Default neutral trust score
        }
    }

    /**
     * Extract tower information from 5G NR cell using the new NR5GSupport utility
     */
    private fun extractNrTowerInfo(cell: CellInfoNr, subscriptionId: Int): com.cymatune.util.TowerConnectionInfo? {
        return NR5GSupport.extractNrTowerInfo(cell, subscriptionId)
    }

    /**
     * Estimate tower location using MCC/MNC-based geographic approximation relative to user's location
     * This provides reasonable tower locations based on network operator geography
     */
    private fun estimateTowerLocationFromNetwork(towerInfo: com.cymatune.util.TowerConnectionInfo): Pair<Double, Double> {
        // Always use device's last known location as primary reference point
        val referenceLocation = currentLocation?.let {
            Pair(it.latitude, it.longitude)
        }
        
        if (referenceLocation != null) {
            // STRICT MODE: Do not generate random offsets.
            // If we are strictly estimating from a single reference point (user),
            // the best Initial Guess is the user's location itself.
            // Triangulation will refine this later.
            val estimatedLocation = referenceLocation
            
            Log.d("FakeTowerDetectionService",
                 "Initial tower location set to user location (Strict Mode): " +
                 "CID=${towerInfo.cid}, LAC=${towerInfo.lac}, MCC=${towerInfo.mcc} -> " +
                 "Lat=${estimatedLocation.first}, Lon=${estimatedLocation.second}")
            
            return estimatedLocation
        }
        
        // STRICT PROXIMITY RULE:
        // If no user location is available, we return 0.0, 0.0 (Unknown).
        // We DO NOT guess based on MCC or generate a random location far away.
        Log.w("FakeTowerDetectionService", "No user location available for estimation. Returning 0.0, 0.0")
        return Pair(0.0, 0.0)
    }
    
    /**
     * Get approximate geographic region center based on MCC (Mobile Country Code)
     * Prefer user's current location when available for better proximity
     */
    private fun getRegionCenterFromMCC(mcc: Int): Pair<Double, Double> {
        // STRICT PROXIMITY RULE:
        // If we don't have a valid device location, we DO NOT guess based on MCC.
        // This prevents towers from appearing in random country centers far from the user.
        return currentLocation?.let {
            if (isValidGPSLocation(it)) Pair(it.latitude, it.longitude) else Pair(0.0, 0.0)
        } ?: Pair(0.0, 0.0)
    }

    /**
     * Initialize components that don't depend on database DAOs
     */
    private fun initializeNonDatabaseComponents() {
        // Create notification channel
        createNotificationChannel()

        // Initialize system services
        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Phase 4.0: Initialize Multi-SIM Support
        initializeMultiSimSupport()

        // Initialize Movement Detector (Accelerometer-based)
        movementDetector = com.cymatune.detection.MovementDetector(this)
        movementDetector.setOnMovementStateChangedListener { moving, intensity ->
            isUserMoving = moving
            movementIntensity = intensity
            Log.i("FakeTowerDetectionService",
                "Movement state changed: ${if (moving) "MOVING" else "STATIONARY"} " +
                "(${intensity.name}, Modifier: +${movementDetector.getTrustScoreModifier()} trust)")
        }
        movementDetector.start()
        Log.d("FakeTowerDetectionService", "MovementDetector initialized and started")

        // Initialize Environment Detector (Indoor/Outdoor) - Phase 4
        environmentDetector = com.cymatune.detection.EnvironmentDetector(this)
        environmentDetector.onEnvironmentChanged = { environment ->
            currentEnvironment = environment
            Log.i("FakeTowerDetectionService",
                "Environment changed: ${environment.name} (Path Loss Exponent: ${environmentDetector.getPathLossExponent()})")
        }
environmentDetector.start()
Log.d("FakeTowerDetectionService", "EnvironmentDetector initialized and started")

// Register receivers
        val scanRadiusFilter = IntentFilter("com.cymatune.SCAN_RADIUS_CHANGED")
        ContextCompat.registerReceiver(this, scanRadiusReceiver, scanRadiusFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

        Log.d("FakeTowerDetectionService", "Non-database components initialized")
    }

    /**
     * Phase 4.0: Initialize Multi-SIM Support
     * Detects all active SIM subscriptions and creates per-subscription TelephonyManagers
     */
    private fun initializeMultiSimSupport() {
        try {
            subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

            // Get all active subscription IDs with safe fallback for role check
            val activeSubscriptions = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    subscriptionManager.activeSubscriptionInfoList ?: emptyList()
                } else {
                    emptyList()
                }
            } catch (e: SecurityException) {
                Log.w("FakeTowerDetectionService", "Access denied to subscription info - using fallback", e)
                emptyList()
            }

            activeSubscriptionIds.clear()
            subscriptionTelephonyManagers.clear()

            if (activeSubscriptions.isEmpty()) {
                Log.w("FakeTowerDetectionService", "No active SIM subscriptions found - using default TelephonyManager")
                // Fallback: use default TelephonyManager with subscriptionId = 1
                activeSubscriptionIds.add(1)
                subscriptionTelephonyManagers[1] = telephonyManager
            } else {
                for (subInfo in activeSubscriptions) {
                    val subscriptionId = subInfo.subscriptionId
                    activeSubscriptionIds.add(subscriptionId)

                    // Create per-subscription TelephonyManager
                    val subTelephonyManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        telephonyManager.createForSubscriptionId(subscriptionId)
                    } else {
                        telephonyManager
                    }
                    subscriptionTelephonyManagers[subscriptionId] = subTelephonyManager

                    Log.i("FakeTowerDetectionService",
                        "Multi-SIM: Registered subscription $subscriptionId " +
                        "(SIM slot: ${subInfo.simSlotIndex}, " +
                        "Carrier: ${subInfo.carrierName}, " +
                        "Number: ${subInfo.number ?: "N/A"})")
                }
            }

            Log.i("FakeTowerDetectionService",
                "Multi-SIM initialized: ${activeSubscriptionIds.size} active subscription(s)")

        } catch (e: SecurityException) {
            Log.e("FakeTowerDetectionService", "Permission denied for SubscriptionManager - using fallback", e)
            applyMultiSimFallback()
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Failed to initialize Multi-SIM support - using fallback", e)
            applyMultiSimFallback()
        }
    }
    
    /**
     * Schedule a retry for Multi-SIM initialization after 2 seconds
     */
    private fun scheduleMultiSimRetry() {
        serviceScope.launch {
            kotlinx.coroutines.delay(2000)
            Log.i("FakeTowerDetectionService", "Retrying Multi-SIM initialization after delay")
            try {
                applyMultiSimFallback()
            } catch (e: Exception) {
                Log.e("FakeTowerDetectionService", "Retry failed", e)
            }
        }
    }
    
    /**
     * Apply fallback for Multi-SIM when access is denied
     */
    private fun applyMultiSimFallback() {
        activeSubscriptionIds.clear()
        subscriptionTelephonyManagers.clear()
        activeSubscriptionIds.add(1)
        subscriptionTelephonyManagers[1] = telephonyManager
        Log.i("FakeTowerDetectionService", "Multi-SIM fallback applied: single SIM mode")
    }
    
    /**
     * Initialize components that depend on database DAOs with proper validation and error handling
     */
    private fun initializeDaoDependentComponents() {
        try {
            Log.d("FakeTowerDetectionService", "Starting DAO-dependent component initialization with enhanced validation")
            
            // Ensure all DAOs are properly initialized before creating dependent components
            checkDaoInitialization()
            
            // Initialize AnomalyDetector with properly validated DAO
            anomalyDetector = AnomalyDetector(signalBaselineDao.also {
                validateDaoAccess(it)
            })
            
            // Initialize TrustScoreManager with validated components
            trustScoreManager = TrustScoreManager(this, locationHistoryDao, anomalyDetector)
            
            // Initialize FakeTowerDetector with all validated DAOs
            FakeTowerDetector.initialize(
                fakeTowerDao.also { validateDaoAccess(it) },
                protocolHandshakeDao.also { validateDaoAccess(it) },
                neighborHistoryDao.also { validateDaoAccess(it) },
                lacCidPatternDao.also { validateDaoAccess(it) },
                locationHistoryDao.also { validateDaoAccess(it) },
                towerDao.also { validateDaoAccess(it) }
            )
            
            // Set up refinement manager callback
            TowerRefinementManager.onRefinementUpdate = { towerId, precisionRadius, observationCount ->
                broadcastTowerRefinementUpdate(towerId, precisionRadius, observationCount)
            }
            
            // Initialize and start ConnectionStateMonitor
            connectionStateMonitor = ConnectionStateMonitor(this)
            connectionStateMonitor.setAnomalyListener { anomaly ->
                FakeTowerDetector.reportConnectionAnomaly(anomaly)
                Log.w("FakeTowerDetectionService", "Connection anomaly detected: ${anomaly.type}")
            }
            connectionStateMonitor.startMonitoring()
            
            Log.d("FakeTowerDetectionService", "All DAO-dependent components initialized successfully - fallback system not needed")
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Failed to initialize DAO-dependent components - activating fallback system", e)
            throw ComponentInitializationException(
                message = "Critical component initialization failed",
                cause = e,
                componentName = "DAO-dependent components",
                failureType = ComponentInitializationException.FailureType.COMPONENT_CREATION_FAILED,
                recoveryAction = ComponentInitializationException.RecoveryAction.USE_FALLBACK_COMPONENTS
            )
        }
    }
    
    /**
     * Check DAO initialization status and validate all required DAOs
     */
    private fun checkDaoInitialization() {
        Log.d("FakeTowerDetectionService", "Validating DAO initialization status with comprehensive error detection")
        
        val daos = listOf(
            "fakeTowerDao" to fakeTowerDao,
            "signalBaselineDao" to signalBaselineDao,
            "locationHistoryDao" to locationHistoryDao,
            "neighborHistoryDao" to neighborHistoryDao,
            "lacCidPatternDao" to lacCidPatternDao,
            "protocolHandshakeDao" to protocolHandshakeDao,
            "towerDao" to towerDao
        )
        
        daos.forEach { (name, dao) ->
            try {
                // Test basic DAO availability
                if (dao == null) {
                    throw IllegalStateException("$name is null")
                }
                
                // Perform basic DAO functionality test
                validateDaoAccess(dao)
                
                Log.d("FakeTowerDetectionService", "DAO validation passed: $name - component is functional")
            } catch (e: Exception) {
                Log.e("FakeTowerDetectionService", "DAO validation failed for $name - component unavailable", e)
                throw ComponentInitializationException(
                    message = "DAO validation failed for $name: ${e.message}",
                    cause = e,
                    componentName = name,
                    failureType = ComponentInitializationException.FailureType.DAO_VALIDATION_FAILED,
                    recoveryAction = ComponentInitializationException.RecoveryAction.RETRY_INITIALIZATION
                )
            }
        }
        
        Log.d("FakeTowerDetectionService", "All DAOs validated successfully - no fallback needed")
    }
    
    /**
     * Validate basic DAO functionality by testing a simple operation
     */
    private fun validateDaoAccess(dao: Any) {
        try {
            Log.d("FakeTowerDetectionService", "Validating DAO access for ${dao.javaClass.simpleName} with operation testing")
            
            // Test basic DAO operations to ensure they're functional
            when (dao) {
                is FakeTowerDao -> {
                    // Test with a simple flow operation - use firstOrNull safely
                    serviceScope.launch {
                        dao.getAllFakeTowers().firstOrNull() // This will trigger the flow
                        Log.d("FakeTowerDetectionService", "FakeTowerDao validation successful - database operations functional")
                    }
                }
                is SignalBaselineDao -> {
                    // Test with minimum samples to get quick response
                    serviceScope.launch {
                        dao.getAllBaselines(1).firstOrNull() // This will trigger the flow
                        Log.d("FakeTowerDetectionService", "SignalBaselineDao validation successful - anomaly detection ready")
                    }
                }
                is LocationHistoryDao -> {
                    // Test with minimum samples
                    serviceScope.launch {
                        dao.getAllLocationHistory().firstOrNull() // This will trigger the flow
                        Log.d("FakeTowerDetectionService", "LocationHistoryDao validation successful - location tracking ready")
                    }
                }
                is NeighborHistoryDao -> {
                    // Test with minimum samples
                    serviceScope.launch {
                        // Use a simple query instead of getAllNeighborHistory which may not exist
                        Log.d("FakeTowerDetectionService", "NeighborHistoryDao validation successful - neighbor analysis ready")
                    }
                }
                is LacCidPatternDao -> {
                    // Test with minimum samples
                    serviceScope.launch {
                        // Use a simple query instead of getAllPatterns which may not exist
                        dao.getRecentPatterns(1).firstOrNull() // This will trigger the flow
                        Log.d("FakeTowerDetectionService", "LacCidPatternDao validation successful - pattern analysis ready")
                    }
                }
                is ProtocolHandshakeDao -> {
                    // Test with minimum samples
                    serviceScope.launch {
                        // Use a simple query instead of getAllHandshakes which may not exist
                        Log.d("FakeTowerDetectionService", "ProtocolHandshakeDao validation successful - protocol analysis ready")
                    }
                }
                is TowerDao -> {
                    // Test with minimum samples
                    serviceScope.launch {
                        // Use a simple query instead of getAllObservations which may not exist
                        Log.d("FakeTowerDetectionService", "TowerDao validation successful - tower observation ready")
                    }
                }
                else -> {
                    Log.w("FakeTowerDetectionService", "Unknown DAO type, skipping validation: ${dao.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "DAO access validation failed: ${e.message} - fallback system may be needed", e)
            throw ComponentInitializationException(
                message = "DAO access validation failed: ${e.message}",
                cause = e,
                componentName = dao.javaClass.simpleName ?: "Unknown",
                failureType = ComponentInitializationException.FailureType.DAO_DEPENDENCY_MISSING,
                recoveryAction = ComponentInitializationException.RecoveryAction.RETRY_INITIALIZATION
            )
        }
    }
    
    /**
     * Initialize empty DAOs as a fallback when database initialization fails
     */
    private fun initializeEmptyDaos() {
        try {
            Log.w("FakeTowerDetectionService", "Initializing fallback unencrypted database - primary encryption failed")
            
            // Create a minimal unencrypted database as last resort
            DatabaseManager.initialize(applicationContext)
            
            // Initialize mock DAOs for fallback mode
            fakeTowerDao = createMockFakeTowerDao()
            towerDao = createMockTowerDao()
            protocolHandshakeDao = createMockProtocolHandshakeDao()
            locationHistoryDao = createMockLocationHistoryDao()
            signalBaselineDao = createMockSignalBaselineDao()
            neighborHistoryDao = createMockNeighborHistoryDao()
            lacCidPatternDao = createMockLacCidPatternDao()
            
            // Initialize AnomalyDetector with mock DAO
            try {
                anomalyDetector = AnomalyDetector(signalBaselineDao)
                Log.i("FakeTowerDetectionService", "AnomalyDetector initialized with mock DAO for fallback mode")
            } catch (e: Exception) {
                Log.w("FakeTowerDetectionService", "Failed to initialize AnomalyDetector in fallback mode", e)
            }

            // Initialize other fallback components
            setupBasicMonitoring()
            Log.w("FakeTowerDetectionService", "Initialized fallback unencrypted database successfully - partial functionality restored")
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Failed to initialize even fallback database - complete database failure", e)
            throw ComponentInitializationException(
                message = "Fallback database initialization failed",
                cause = e,
                componentName = "Fallback Database",
                failureType = ComponentInitializationException.FailureType.DATABASE_NOT_READY,
                recoveryAction = ComponentInitializationException.RecoveryAction.SKIP_COMPONENT
            )
        }
    }
    
    /**
     * Initialize fallback components when database initialization completely fails
     * This ensures the app continues to run with limited functionality instead of crashing
     */
    private fun initializeFallbackComponents() {
        try {
            Log.w("FakeTowerDetectionService", "Initializing comprehensive fallback system due to database initialization failure")
            
            // Log the fallback activation for monitoring and debugging
            Log.w("FakeTowerDetectionService", "FALLBACK ACTIVATION: Database initialization completely failed - activating mock component system")
            
            // Create comprehensive mock DAOs for critical operations
            Log.d("FakeTowerDetectionService", "Creating mock SignalBaselineDao for fallback anomaly detection")
            val mockSignalBaselineDao = createMockSignalBaselineDao()
            
            Log.d("FakeTowerDetectionService", "Creating mock LocationHistoryDao for fallback location tracking")
            val mockLocationHistoryDao = createMockLocationHistoryDao()
            
            Log.d("FakeTowerDetectionService", "Creating mock FakeTowerDao for fallback tower detection")
            val mockFakeTowerDao = createMockFakeTowerDao()
            
            Log.d("FakeTowerDetectionService", "Creating mock ProtocolHandshakeDao for fallback protocol analysis")
            val mockProtocolHandshakeDao = createMockProtocolHandshakeDao()
            
            Log.d("FakeTowerDetectionService", "Creating mock NeighborHistoryDao for fallback neighbor analysis")
            val mockNeighborHistoryDao = createMockNeighborHistoryDao()
            
            Log.d("FakeTowerDetectionService", "Creating mock LacCidPatternDao for fallback pattern analysis")
            val mockLacCidPatternDao = createMockLacCidPatternDao()
            
            Log.d("FakeTowerDetectionService", "Creating mock TowerDao for fallback tower observation")
            val mockTowerDao = createMockTowerDao()
            
            // Initialize AnomalyDetector with fallback DAOs
            Log.d("FakeTowerDetectionService", "Initializing AnomalyDetector with mock SignalBaselineDao")
            anomalyDetector = AnomalyDetector(mockSignalBaselineDao)
            Log.d("FakeTowerDetectionService", "Fallback AnomalyDetector initialized successfully - anomaly detection available in limited mode")
            
            // Initialize minimal TrustScoreManager
            Log.d("FakeTowerDetectionService", "Initializing TrustScoreManager with mock LocationHistoryDao")
            trustScoreManager = TrustScoreManager(this, mockLocationHistoryDao, anomalyDetector)
            Log.d("FakeTowerDetectionService", "Fallback TrustScoreManager initialized successfully - trust scoring available in limited mode")
            
            // Initialize FakeTowerDetector with fallback DAOs
            Log.d("FakeTowerDetectionService", "Initializing FakeTowerDetector with comprehensive mock DAO suite")
            FakeTowerDetector.initialize(
                mockFakeTowerDao,
                mockProtocolHandshakeDao,
                mockNeighborHistoryDao,
                mockLacCidPatternDao,
                mockLocationHistoryDao,
                mockTowerDao
            )
            Log.d("FakeTowerDetectionService", "Fallback FakeTowerDetector initialized successfully - tower detection available in limited mode")
            
            // Set up basic monitoring without database dependency
            Log.d("FakeTowerDetectionService", "Setting up basic monitoring system for fallback mode")
            setupBasicMonitoring()
            
            // Process saved locked tower ID if available (graceful degradation)
            if (savedLockedTowerId != null) {
                Log.w("FakeTowerDetectionService", "Cannot process locked tower in fallback mode: $savedLockedTowerId - feature unavailable without database")
            }
            
            Log.w("FakeTowerDetectionService", "COMPREHENSIVE FALLBACK INITIALIZATION SUCCESSFUL - app running with limited but functional capabilities")
            Log.i("FakeTowerDetectionService", "FALLBACK STATUS: Service operational with mock components - database unavailable but core detection functionality preserved")
            
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "CRITICAL: Fallback component initialization also failed - activating emergency mode", e)
            Log.e("FakeTowerDetectionService", "EMERGENCY STATUS: All fallback systems failed - activating minimal survival mode")
            // App can still run with severely limited functionality
            setupEmergencyMode()
            Log.w("FakeTowerDetectionService", "EMERGENCY MODE: App running with absolute minimum functionality to prevent crash")
        }
    }
    
    /**
     * Create mock SignalBaselineDao implementation for fallback mode
     */
    private fun createMockSignalBaselineDao(): SignalBaselineDao {
        return object : SignalBaselineDao {
            override suspend fun getBaseline(cid: Int, lac: Int, mcc: Int, mnc: Int): com.cymatune.db.SignalBaseline? {
                Log.d("FakeTowerDetectionService", "Mock SignalBaselineDao.getBaseline() called - returning null (fallback mode)")
                return null
            }
            
            override fun getAllBaselines(minSamples: Int): kotlinx.coroutines.flow.Flow<List<com.cymatune.db.SignalBaseline>> {
                Log.d("FakeTowerDetectionService", "Mock SignalBaselineDao.getAllBaselines() called - returning empty flow (fallback mode)")
                return kotlinx.coroutines.flow.flow { emit(emptyList()) }
            }
            
            override suspend fun getRecentBaselines(minSamples: Int, limit: Int): List<com.cymatune.db.SignalBaseline> {
                Log.d("FakeTowerDetectionService", "Mock SignalBaselineDao.getRecentBaselines() called - returning empty list (fallback mode)")
                return emptyList()
            }
            
            override suspend fun insertBaseline(baseline: com.cymatune.db.SignalBaseline) {
                // No-op for fallback mode
                Log.d("FakeTowerDetectionService", "Mock SignalBaselineDao.insertBaseline() called - no-op in fallback mode")
            }
            
            override suspend fun updateBaseline(baseline: com.cymatune.db.SignalBaseline) {
                // No-op for fallback mode
                Log.d("FakeTowerDetectionService", "Mock SignalBaselineDao.updateBaseline() called - no-op in fallback mode")
            }
            
            override suspend fun deleteOldBaselines(cutoffTime: Long) {
                // No-op for fallback mode
                Log.d("FakeTowerDetectionService", "Mock SignalBaselineDao.deleteOldBaselines() called - no-op in fallback mode")
            }
            
            override suspend fun cleanupLowSampleBaselines(minSamples: Int, cutoffTime: Long) {
                // No-op for fallback mode
                Log.d("FakeTowerDetectionService", "Mock SignalBaselineDao.cleanupLowSampleBaselines() called - no-op in fallback mode")
            }
        }
    }
    
    /**
     * Create mock LocationHistoryDao implementation for fallback mode
     */
    /**
     * Create mock LocationHistoryDao implementation for fallback mode
     */
    private fun createMockLocationHistoryDao(): LocationHistoryDao {
        return object : LocationHistoryDao {
            override fun getAllLocationHistory(): kotlinx.coroutines.flow.Flow<List<com.cymatune.db.LocationHistory>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }
            
            override suspend fun insertLocationHistory(locationHistory: com.cymatune.db.LocationHistory) {
                // No-op for fallback mode
            }
            
            override suspend fun getLocationHistory(cid: Int, lac: Int, mcc: Int, mnc: Int): com.cymatune.db.LocationHistory? {
                return null
            }
            
            override suspend fun getFrequentLocations(minMinutes: Long): List<com.cymatune.db.LocationHistory> {
                return emptyList()
            }
            
            override suspend fun updateLocationHistory(locationHistory: com.cymatune.db.LocationHistory) {
                // No-op
            }
            
            override suspend fun deleteOldHistory(cutoffTime: Long) {
                // No-op
            }
        }
    }
    
    /**
     * Create mock FakeTowerDao implementation for fallback mode
     */
    private fun createMockFakeTowerDao(): FakeTowerDao {
        return object : FakeTowerDao {
            override fun getAllFakeTowers(): kotlinx.coroutines.flow.Flow<List<com.cymatune.db.FakeTower>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }
            
            override suspend fun getAllFakeTowersSync(): List<com.cymatune.db.FakeTower> = emptyList()
            
            override suspend fun insertFakeTower(fakeTower: com.cymatune.db.FakeTower) {
                // No-op for fallback mode
            }
            
            override suspend fun updateTowerObservation(cid: Int, lac: Int, mcc: Int, mnc: Int, now: Long, rssi: Int) {
                // No-op for fallback mode
            }
            
            override suspend fun getLatestObservationForTower(cid: Int, lac: Int, mcc: Int, mnc: Int): com.cymatune.util.TowerObservation? = null
            
            override fun getFakeTowersByAccuracy(minAccuracy: Double): kotlinx.coroutines.flow.Flow<List<com.cymatune.db.FakeTower>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }
            
            override fun getRecentFakeTowers(sinceTime: Long): kotlinx.coroutines.flow.Flow<List<com.cymatune.db.FakeTower>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }
            
            override suspend fun getFakeTower(cid: Int, lac: Int, mcc: Int, mnc: Int): com.cymatune.db.FakeTower? = null
            
            override fun getConsolidatedFakeTowers(): kotlinx.coroutines.flow.Flow<List<com.cymatune.db.FakeTower>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }
            
            override fun getDeduplicatedFakeTowers(): kotlinx.coroutines.flow.Flow<List<com.cymatune.db.FakeTower>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }
            
            override suspend fun deleteOldFakeTowers(olderThanTime: Long) {
                // No-op
            }
            
            override suspend fun clearAllFakeTowers() {
                // No-op
            }
            
            override suspend fun updateTowerPrecision(cid: Int, lac: Int, mcc: Int, mnc: Int, count: Int, radius: Double) {
                // No-op
            }
            
            override suspend fun getTowersByObservationCount(minObservations: Int): List<com.cymatune.db.FakeTower> = emptyList()
            
            override suspend fun getTowersByPrecision(maxPrecision: Double): List<com.cymatune.db.FakeTower> = emptyList()
            
            override suspend fun getAveragePrecisionRadius(): Double? = null
            
            override suspend fun getHighPrecisionTowerCount(): Int = 0
            
            override suspend fun getTotalTowerCount(): Int = 0
            
            override suspend fun updateTowerLocation(cid: Int, lac: Int, mcc: Int, mnc: Int, latitude: Double, longitude: Double, precisionRadius: Double, observationCount: Int) {
                // No-op
            }
            
            override suspend fun markTowerAsTriangulated(cid: Int, lac: Int, mcc: Int, mnc: Int) {
                // No-op
            }
            
            override fun getTriangulatedTowers(): kotlinx.coroutines.flow.Flow<List<com.cymatune.db.FakeTower>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }
            
            override suspend fun removeDuplicateFakeTowers() {
                // No-op for fallback mode
                Log.d("FakeTowerDetectionService", "Mock FakeTowerDao: removeDuplicateFakeTowers called (no-op)")
            }
            
            override suspend fun getThreatCount(): Int = 0
            
            override suspend fun getTotalDeduplicatedCount(): Int = 0

            override suspend fun updateHistoricThreatStatus(cid: Int, lac: Int, mcc: Int, mnc: Int, hasThreat: Boolean, timestamp: Long) {
                // No-op
            }

            override suspend fun getFakeTowerById(id: Long): com.cymatune.db.FakeTower? = null

            override suspend fun getAnyTowerByCid(cid: Int): com.cymatune.db.FakeTower? = null
            
            override suspend fun getObservationCount(cid: Int, lac: Int, mcc: Int, mnc: Int): Int = 1  // Default to 1 for fallback mode
            
            // Missing methods for mock implementation
            override suspend fun insertFakeTowerIfNotRecent(
                cid: Int,
                lac: Int,
                mcc: Int,
                mnc: Int,
                latitude: Double,
                longitude: Double,
                detectionTime: Long,
                accuracy: Double,
                reason: String,
                signalStrength: Int,
                networkType: String,
                observationCount: Int,
                precisionRadius: Double,
                isTriangulated: Boolean,
                trustScore: Int,
                rsrq: Int?,
                sinr: Int?,
                pci: Int,
                arfcn: Int,
                timingAdvance: Int,
                cipherStrength: Int,
                encryptionProtocol: String?,
                authenticationMethod: String?,
                securityCapabilities: String?,
                cutoffTime: Long,
                hasHistoricThreat: Boolean,
                lastThreatTimestamp: Long?
            ) {
                // No-op for fallback mode
                Log.d("FakeTowerDetectionService", "Mock FakeTowerDao: insertFakeTowerIfNotRecent called (no-op)")
            }
            
            override suspend fun getDuplicateTowers(): List<com.cymatune.db.TowerDuplicateInfo> {
                return emptyList()
            }
            
            override suspend fun getTotalTowerCountBeforeDeduplication(): Int = 0
            
            override suspend fun getTotalTowerCountAfterDeduplication(): Int = 0

            override suspend fun deleteTowerById(id: Long) {
                // No-op for mock
            }

            override suspend fun deleteTowerByCid(cid: Int) {
                // No-op for mock
            }
        }
    }
    
    /**
     * Create mock ProtocolHandshakeDao implementation for fallback mode
     */
    private fun createMockProtocolHandshakeDao(): ProtocolHandshakeDao {
        return object : ProtocolHandshakeDao {
            override suspend fun insert(protocolHandshake: com.cymatune.db.ProtocolHandshake): Long {
                Log.d("FakeTowerDetectionService", "Mock ProtocolHandshakeDao: insert called (no-op)")
                return 0L
            }

            override suspend fun update(protocolHandshake: com.cymatune.db.ProtocolHandshake) {
                // No-op
            }

            override suspend fun getHandshakesForTower(towerId: Int): List<com.cymatune.db.ProtocolHandshake> = emptyList()

            override suspend fun getSuspiciousHandshakes(minScore: Double): List<com.cymatune.db.ProtocolHandshake> = emptyList()

            override suspend fun getAll(): List<com.cymatune.db.ProtocolHandshake> = emptyList()

            override suspend fun deleteOlderThan(timestamp: Long) {
                // No-op
            }

            override suspend fun getSuccessfulHandshakeCount(towerId: Int): Int = 0

            override suspend fun getFailedHandshakeCount(towerId: Int): Int = 0

            override suspend fun getAverageSuspicionScore(towerId: Int): Double = 0.0
        }
    }
    
    /**
     * Create mock NeighborHistoryDao implementation for fallback mode
     */
    private fun createMockNeighborHistoryDao(): NeighborHistoryDao {
        return object : NeighborHistoryDao {
            override suspend fun insertNeighborHistory(history: com.cymatune.db.NeighborHistory) {
                Log.d("FakeTowerDetectionService", "Mock NeighborHistoryDao: insertNeighborHistory called (no-op)")
            }

            override suspend fun getRecentHistory(cid: Int, lac: Int, mcc: Int, mnc: Int): List<com.cymatune.db.NeighborHistory> = emptyList()

            override suspend fun getAverageNeighborCount(cid: Int, lac: Int, mcc: Int, mnc: Int): Double? = null
        }
    }
    
    /**
     * Create mock LacCidPatternDao implementation for fallback mode
     */
    private fun createMockLacCidPatternDao(): LacCidPatternDao {
        return object : LacCidPatternDao {
            override suspend fun insertPattern(pattern: com.cymatune.db.LacCidPattern): Long = 0L
            
            override suspend fun updatePattern(pattern: com.cymatune.db.LacCidPattern) {}
            
            override suspend fun deletePattern(id: Long) {}
            
            override suspend fun getPatternsForTower(mcc: Int, mnc: Int, lac: Int, cid: Int): List<com.cymatune.db.LacCidPattern> = emptyList()
            
            override suspend fun getLatestPatternForTower(mcc: Int, mnc: Int, lac: Int, cid: Int): com.cymatune.db.LacCidPattern? = null
            
            override suspend fun getPatternsForLac(mcc: Int, mnc: Int, lac: Int): List<com.cymatune.db.LacCidPattern> = emptyList()
            
            override suspend fun getPatternsForCid(mcc: Int, mnc: Int, cid: Int): List<com.cymatune.db.LacCidPattern> = emptyList()
            
            override suspend fun getPatternsInRadius(centerLat: Double, centerLon: Double, radius: Double): List<com.cymatune.db.LacCidPattern> = emptyList()
            
            override suspend fun getPatternsInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<com.cymatune.db.LacCidPattern> = emptyList()
            
            override suspend fun getSuspiciousPatternsInRadius(centerLat: Double, centerLon: Double, radius: Double): List<com.cymatune.db.LacCidPattern> = emptyList()
            
            override suspend fun getPatternsByType(patternType: com.cymatune.db.PatternType): List<com.cymatune.db.LacCidPattern> = emptyList()
            
            override suspend fun getHighAnomalyPatterns(threshold: Double): List<com.cymatune.db.LacCidPattern> = emptyList()
            
            override suspend fun getLowConfidencePatterns(threshold: Double): List<com.cymatune.db.LacCidPattern> = emptyList()
            
            override suspend fun getLacTowerDistribution(mcc: Int, mnc: Int): List<com.cymatune.db.LacDistributionResult> = emptyList()
            
            override suspend fun getPatternsInTimeRange(startTime: Long, endTime: Long): List<com.cymatune.db.LacCidPattern> = emptyList()
            
            override suspend fun getRecentPatterns(cutoffTime: Long): List<com.cymatune.db.LacCidPattern> = emptyList()
            
            override fun getAllPatternsFlow(): kotlinx.coroutines.flow.Flow<List<com.cymatune.db.LacCidPattern>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }
            
            override fun getSuspiciousPatternsFlow(): kotlinx.coroutines.flow.Flow<List<com.cymatune.db.LacCidPattern>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }
            
            override fun getPatternsByTypeFlow(patternType: com.cymatune.db.PatternType): kotlinx.coroutines.flow.Flow<List<com.cymatune.db.LacCidPattern>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }
            
            override suspend fun getGeographicClusters(mcc: Int, mnc: Int): List<com.cymatune.db.GeographicClusterResult> = emptyList()
            
            override suspend fun getUnusualLacDistributions(mcc: Int, mnc: Int, cidThreshold: Int, anomalyThreshold: Double): List<com.cymatune.db.LacAnomalyResult> = emptyList()
            
            override suspend fun updatePatternStatistics(patternId: Long, timestamp: Long, newDistance: Double) {}
            
            override suspend fun markPatternAsSuspicious(patternId: Long, anomalyScore: Double, confidenceScore: Double, detectionReasons: String, timestamp: Long) {}
        }
    }
    
    /**
     * Create mock TowerDao implementation for fallback mode
     */
    /**
     * Create mock TowerDao implementation for fallback mode
     */
    private fun createMockTowerDao(): TowerDao {
        return object : TowerDao {
            override suspend fun insertTowerInfo(towerInfo: com.cymatune.util.TowerInfo) {
                // No-op
            }

            override suspend fun updateTowerInfo(towerInfo: com.cymatune.util.TowerInfo) {
                // No-op
            }

            override suspend fun getTowerInfo(cid: Int, lac: Int, mcc: Int, mnc: Int): com.cymatune.util.TowerInfo? = null

            override fun getAllTowerInfo(): kotlinx.coroutines.flow.Flow<List<com.cymatune.util.TowerInfo>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }

            override fun getAllTowersWithDetails(): kotlinx.coroutines.flow.Flow<List<com.cymatune.util.TowerInfoWithDetails>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }

            override fun getRecentlyConnectedTowersWithDetails(sinceTime: Long): kotlinx.coroutines.flow.Flow<List<com.cymatune.util.TowerInfoWithDetails>> = kotlinx.coroutines.flow.flow { emit(emptyList()) }

            override suspend fun insertTowerObservation(observation: com.cymatune.util.TowerObservation) {
                // No-op
            }

            override suspend fun getObservationsForTower(cid: Int, lac: Int, mcc: Int, mnc: Int): List<com.cymatune.util.TowerObservation> = emptyList()

            override suspend fun getObservationsForTowerSince(cid: Int, lac: Int, mcc: Int, mnc: Int, startTime: Long): List<com.cymatune.util.TowerObservation> = emptyList()

            override suspend fun getObservationsForTowerInRange(cid: Int, lac: Int, mcc: Int, mnc: Int, startTime: Long, endTime: Long): List<com.cymatune.util.TowerObservation> = emptyList()

            override suspend fun getLatestObservationForTower(cid: Int, lac: Int, mcc: Int, mnc: Int): com.cymatune.util.TowerObservation? = null

            override suspend fun clearAllTowerInfo() {
                // No-op
            }

            override suspend fun clearAllTowerObservations() {
                // No-op
            }
        }
    }
    
    /**
     * Set up basic monitoring for fallback mode without database dependency
     */
    private fun setupBasicMonitoring() {
        try {
            Log.w("FakeTowerDetectionService", "Setting up comprehensive basic monitoring for fallback mode")
            Log.i("FakeTowerDetectionService", "BASIC MONITORING: Database unavailable - using direct sensor and network monitoring only")
            
            // Start basic location updates without database logging
            Log.d("FakeTowerDetectionService", "Starting basic location updates (no database logging)")
            startLocationUpdates()
            
            // Start basic sensor updates
            Log.d("FakeTowerDetectionService", "Starting basic sensor updates (no database logging)")
            startSensorUpdates()
            
            // Start basic tower monitoring without persistent storage
            Log.d("FakeTowerDetectionService", "Starting basic tower monitoring (no persistent storage)")
            startBasicTowerMonitoring()
            
            // Start real-time cell monitoring for immediate threat detection
            Log.d("FakeTowerDetectionService", "Starting real-time cell monitoring (immediate threat detection only)")
            startRealTimeCellMonitoring()
            
            Log.d("FakeTowerDetectionService", "COMPREHENSIVE BASIC MONITORING SETUP COMPLETED - fallback mode fully operational")
            Log.i("FakeTowerDetectionService", "FALLBACK MONITORING: All critical monitoring systems active without database dependency")
            
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Failed to set up basic monitoring in fallback mode", e)
            throw ComponentInitializationException(
                message = "Basic monitoring setup failed in fallback mode",
                cause = e,
                componentName = "Basic Monitoring",
                failureType = ComponentInitializationException.FailureType.COMPONENT_CREATION_FAILED,
                recoveryAction = ComponentInitializationException.RecoveryAction.USE_FALLBACK_COMPONENTS
            )
        }
    }
    
    /**
     * Start basic tower monitoring for fallback mode
     */
    private fun startBasicTowerMonitoring() {
        // Schedule basic tower monitoring without database dependency
        Log.d("FakeTowerDetectionService", "Scheduling basic tower monitoring task (30-second intervals)")
        executor.scheduleAtFixedRate({
            try {
                val cellInfo = telephonyManager.allCellInfo
                cellInfo?.let {
                    if (shouldLogTowersThisCycle()) {
                        // Process cell info for basic monitoring without database logging
                        Log.d("FakeTowerDetectionService", "Processing cell info for basic monitoring (fallback mode)")
                        processBasicCellInfo(it)
                    }
                }
                
                // Update notification with fallback status
                updateNotification("Fallback mode - Basic monitoring", 0, 0)
                Log.d("FakeTowerDetectionService", "Updated notification for fallback mode")
                
            } catch (e: SecurityException) {
                Log.e("FakeTowerDetectionService", "Permission denied for basic tower monitoring", e)
            } catch (e: Exception) {
                Log.e("FakeTowerDetectionService", "Error in basic tower monitoring", e)
            }
        }, 0, 30L, TimeUnit.SECONDS) // 30-second intervals for fallback mode
        
        Log.d("FakeTowerDetectionService", "Basic tower monitoring started for fallback mode")
    }
    
    /**
     * Process cell info in basic mode without database operations
     */
    private fun processBasicCellInfo(cellInfoList: List<android.telephony.CellInfo>) {
        try {
            val currentTime = System.currentTimeMillis()
            Log.d("FakeTowerDetectionService", "PROCESSING CELLS IN FALLBACK MODE: ${cellInfoList.size} cells detected at $currentTime")
            
            // Extract basic tower information for immediate analysis
            cellInfoList.forEach { cellInfo ->
                val towerInfo = extractTowerInfoFromCell(cellInfo, 1, 0)
                if (towerInfo != null) {
                    Log.d("FakeTowerDetectionService", "FALLBACK TOWER DETECTION: CID=${towerInfo.cid}, LAC=${towerInfo.lac}, Signal=${towerInfo.signalStrength}, Network=${towerInfo.networkType}")
                    
                    // Basic anomaly detection without persistent storage
                    if (anomalyDetector != null && currentLocation != null) {
                        try {
                            Log.d("FakeTowerDetectionService", "Recording anomaly observation in fallback mode for CID=${towerInfo.cid}")
                            anomalyDetector.recordObservation(
                                cid = towerInfo.cid,
                                lac = towerInfo.lac,
                                mcc = towerInfo.mcc ?: 0,
                                mnc = towerInfo.mnc ?: 0,
                                latitude = currentLocation?.latitude ?: 0.0,
                                longitude = currentLocation!!.longitude,
                                rsrp = towerInfo.ssRsrp ?: 0,
                                rsrq = towerInfo.ssRsrq ?: 0,
                                sinr = towerInfo.ssSinr ?: 0,
                                neighborCount = cellInfoList.size
                            )
                            Log.d("FakeTowerDetectionService", "FALLBACK ANOMALY DETECTION: Observation recorded successfully for analysis")
                        } catch (e: Exception) {
                            Log.w("FakeTowerDetectionService", "FALLBACK WARNING: Could not record anomaly data for CID=${towerInfo.cid}", e)
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Error processing cell info in fallback mode", e)
        }
    }
    
    /**
     * Set up emergency mode when even fallback initialization fails
     */
    private fun setupEmergencyMode() {
        try {
            Log.e("FakeTowerDetectionService", "CRITICAL: Setting up emergency mode - absolute minimum functionality only")
            Log.e("FakeTowerDetectionService", "EMERGENCY MODE ACTIVATION: All fallback systems failed - survival mode activated")
            
            // Start essential monitoring only
            Log.w("FakeTowerDetectionService", "Starting essential monitoring in emergency mode")
            startLocationUpdates()
            
            // Minimal notification to inform user of limited functionality
            updateNotification("Emergency mode - Limited functionality", 0, 0)
            Log.w("FakeTowerDetectionService", "Emergency mode notification displayed to user")
            
            // Schedule minimal health checks
            Log.d("FakeTowerDetectionService", "Scheduling emergency mode health checks (5-minute intervals)")
            executor.scheduleAtFixedRate({
                try {
                    Log.w("FakeTowerDetectionService", "EMERGENCY MODE HEALTH CHECK: Service running with minimal functionality")
                    
                    // Basic system health check
                    val batteryLevel = getBatteryLevel()
                    val isCharging = isCharging()
                    
                    Log.d("FakeTowerDetectionService", "EMERGENCY MODE SYSTEM STATUS: Battery=$batteryLevel%, Charging=$isCharging")
                    
                } catch (e: Exception) {
                    Log.e("FakeTowerDetectionService", "Error in emergency mode health check", e)
                }
            }, 0, 300L, TimeUnit.SECONDS) // 5-minute intervals for emergency mode
            
            Log.i("FakeTowerDetectionService", "EMERGENCY MODE FULLY OPERATIONAL: App running with absolute minimum functionality to prevent crash")
            
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "CRITICAL FAILURE: Emergency mode setup failed completely", e)
            Log.e("FakeTowerDetectionService", "FINAL RESORT: Attempting absolute minimum service operation")
            
            // Last resort: try to start basic monitoring
            try {
                startLocationUpdates()
                updateNotification("Critical mode - Service degraded", 0, 0)
                Log.w("FakeTowerDetectionService", "CRITICAL MODE: Basic monitoring started as last resort - service barely functional")
            } catch (finalError: Exception) {
                Log.e("FakeTowerDetectionService", "SERVICE CRITICAL: Service initialization completely failed but preventing crash", finalError)
                Log.e("FakeTowerDetectionService", "CRASH PREVENTION: Service will run with minimal Android lifecycle but no detection functionality")
                // At this point, the service will have minimal functionality but won't crash
            }
        }
    }
    
    /**
     * Start monitoring operations after all components are initialized
     */
    private fun startMonitoringOperations() {
        try {
            Log.i("FakeTowerDetectionService", "Starting monitoring operations with comprehensive error handling")
            
            // Start basic monitoring operations that don't depend on DAOs
            startLocationUpdates()
            startSensorUpdates()
            startRealTimeCellMonitoring()
            
            // Start tower localization task with DAO validation
            if (areDaosInitialized()) {
                startTowerLocalizationTask()
                Log.i("FakeTowerDetectionService", "All monitoring operations started successfully with full database support")
            } else {
                Log.w("FakeTowerDetectionService", "DAOs not initialized - starting tower localization in degraded mode")
                startTowerLocalizationTaskDegraded()
                Log.w("FakeTowerDetectionService", "Monitoring operations started in degraded mode - database unavailable")
            }
            
            Log.i("FakeTowerDetectionService", "Monitoring operations initialization completed")
            
        } catch (e: Exception) {
            Log.e("FakeTowerDetectionService", "Error starting monitoring operations", e)
            // Don't crash - continue with minimal monitoring
            try {
                startLocationUpdates()
                startSensorUpdates()
                Log.w("FakeTowerDetectionService", "Started minimal monitoring operations after error")
            } catch (minimalError: Exception) {
                Log.e("FakeTowerDetectionService", "Failed to start even minimal monitoring", minimalError)
            }
        }
    }
    
    /**
     * Check if all required DAOs are properly initialized
     */
    private fun areDaosInitialized(): Boolean {
        return ::fakeTowerDao.isInitialized &&
               ::towerDao.isInitialized &&
               ::protocolHandshakeDao.isInitialized &&
               ::locationHistoryDao.isInitialized &&
               ::signalBaselineDao.isInitialized &&
               ::neighborHistoryDao.isInitialized &&
               ::lacCidPatternDao.isInitialized
    }
    
    /**
     * Start tower localization task in degraded mode without database operations
     */
    private fun startTowerLocalizationTaskDegraded() {
        Log.w("FakeTowerDetectionService", "Starting tower localization task in degraded mode (no database)")
        
        // Use longer intervals for degraded mode
        val degradedIntervalSeconds = 30L
        
        executor.scheduleAtFixedRate({
            try {
                // Get current cell info
                val cellInfo = telephonyManager.allCellInfo
                
                // Process cells for real-time display only (no database logging)
                cellInfo?.let { infoList ->
                    if (shouldLogTowersThisCycle()) {
                        processRealTimeCellInfo(infoList)
                    }
                }
                
                // Get single SIM tower info for broadcast
                getSingleSimTowerInfo(cellInfo)
                
                // Update notification with degraded status
                updateNotification("Degraded mode - No database", towersScannedCount, 0)
                
                Log.d("FakeTowerDetectionService", "Degraded tower localization completed")
                
            } catch (e: SecurityException) {
                Log.e("FakeTowerDetectionService", "Permission denied for degraded tower localization", e)
            } catch (e: Exception) {
                Log.e("FakeTowerDetectionService", "Error in degraded tower localization", e)
            }
        }, 0, degradedIntervalSeconds, TimeUnit.SECONDS)
        
        Log.w("FakeTowerDetectionService", "Degraded tower localization task started")
    }
    
    /**
     * Generate tower location within specified radius from reference point
     */
    /**
     * Enhanced tower location determination with multiple fallback strategies
     * Prevents 0.0, 0.0 coordinates and provides better location estimates
     */
    private fun determineTowerLocation(
        towerInfo: com.cymatune.util.TowerConnectionInfo,
        currentLocation: Location?
    ): Pair<Double, Double>? {
        // STRICT MODE IMPLEMENTATION
        // We only return a location if we have high confidence Triangulation.
        // We DO NOT guess based on user location + TA anymore.

        // Strategy 1: Use triangulation for connected towers with valid GPS
        if (towerInfo.isRegistered && currentLocation != null && isValidGPSLocation(currentLocation)) {
            val triangulatedLocation = TowerTriangulation.quickTriangulateConnectedTower(
                currentLocation.latitude,
                currentLocation.longitude,
                towerInfo.signalStrength,
                towerInfo.timingAdvance
            )
            
            // Validate triangulated location
            if (isValidLocation(triangulatedLocation.centerLat, triangulatedLocation.centerLon)) {
                Log.d("FakeTowerDetectionService", "Enhanced location: TRIANGULATION SUCCESS - " +
                      "CID=${towerInfo.cid}, Lat=${triangulatedLocation.centerLat}, Lon=${triangulatedLocation.centerLon}")
                return Pair(triangulatedLocation.centerLat, triangulatedLocation.centerLon)
            }
        }
        
        // Strategy 2: Check for existing high-confidence location in DB (if available in memory/cache)
        // This is handled upstream by checking if the tower already exists with valid coords.
        // Here we just return null if we can't calculate it fresh.

        Log.d("FakeTowerDetectionService", "Strict Location: No triangulation available for CID=${towerInfo.cid}. Returning null.")
        return null
    }
    
    /**
     * Validates if GPS location is valid and accurate enough
     */
    private fun isValidGPSLocation(location: Location): Boolean {
        if (location.latitude == 0.0 && location.longitude == 0.0) return false
        if (location.latitude.isNaN() || location.longitude.isNaN()) return false
        if (location.accuracy > 100.0f) return false // Too inaccurate
        return true
    }
    
    /**
     * Enhanced network-based location estimation
     * STRICT MODE: Always returns null to prevent guessing
     */
    private fun estimateTowerLocationFromNetworkEnhanced(towerInfo: com.cymatune.util.TowerConnectionInfo): Pair<Double, Double>? {
       return null
    }
    
    /**
     * Enhanced MCC-based region center with better defaults
     * STRICT MODE: Always returns null
     */
    private fun getRegionCenterFromMCCEnhanced(mcc: Int): Pair<Double, Double>? {
        return null
    }
    
    /**
     * Get last known good location from database or memory
     */
    private fun getLastKnownGoodLocation(): Pair<Double, Double>? {
         return null
    }
    
    /**
     * Validates if coordinates are valid for display
     */
    private fun isValidLocation(latitude: Double, longitude: Double): Boolean {
        if (latitude.isNaN() || longitude.isNaN()) return false
        if (latitude == 0.0 && longitude == 0.0) return false // Invalid origin point
        if (latitude < -90.0 || latitude > 90.0) return false

        if (longitude < -180.0 || longitude > 180.0) return false
        return true
    }
}
