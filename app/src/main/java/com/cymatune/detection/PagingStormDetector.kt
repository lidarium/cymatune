package com.cymatune.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.cymatune.db.PagingEvent
import com.cymatune.db.PagingStorm
import com.cymatune.util.CommonUtils
import com.cymatune.util.SMSConstants
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * PagingStormDetector - Advanced detector for identifying paging storms
 * that may indicate fake tower activity or IMSI catchers.
 * 
 * This detector monitors:
 * - Network state changes and paging patterns
 * - Cell tower switching frequency
 * - Signal strength anomalies during paging
 * - Correlation between SMS events and network paging
 * - Abnormal connection establishment patterns
 */
class PagingStormDetector(private val context: Context) {
    
    companion object {
        private const val TAG = "PagingStormDetector"

        // Detection thresholds
        private const val PAGING_STORM_THRESHOLD = 10 // Messages per minute
        private const val PAGING_WINDOW_MS = 60000 // 1 minute window
        private const val CELL_CHANGE_THRESHOLD = 5 // Max cell changes per window
        private const val SIGNAL_ANOMALY_THRESHOLD = -80 // dBm threshold
        private const val CONNECTION_FAILURE_THRESHOLD = 0.3 // 30% failure rate

        // Network monitoring intervals
        private const val NETWORK_CHECK_INTERVAL_MS = 5000 // 5 seconds
        private const val STORM_EVALUATION_INTERVAL_MS = 30000 // 30 seconds

        // D03 FIX: Minimum event threshold to avoid false positives from background apps
        // Swiggy/social media typically generate 3-5 events per minute, we need >15 to flag
        private const val BACKGROUND_APP_FILTER_THRESHOLD = 15 // Increased from implicit 10

        // D03 FIX: Time window for burst detection (shorter to catch true storms)
        private const val BURST_DETECTION_WINDOW_MS = 30000 // 30 seconds for burst mode
    }
    
    private val backgroundScope = CoroutineScope(Dispatchers.IO + Job())
    private val networkMonitorScope = CoroutineScope(Dispatchers.Default + Job())
    
    // Tracking data
    private val pagingEvents = ConcurrentHashMap<String, MutableList<PagingEvent>>()
    private val networkStateHistory = mutableListOf<NetworkStateSnapshot>()
    private val cellChangeHistory = mutableListOf<CellChangeRecord>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkCheck = 0L
    private var lastStormEvaluation = 0L
    
    // Monitoring flags
    private var isMonitoring = false
    private var currentNetworkState: NetworkStateSnapshot? = null
    
    /**
     * Start monitoring for paging storms
     */
    fun startMonitoring() {
        if (isMonitoring) {
            Log.d(TAG, "Paging storm monitoring already active")
            return
        }
        
        Log.d(TAG, "Starting paging storm monitoring")
        isMonitoring = true
        
        backgroundScope.launch {
            try {
                initializeNetworkMonitoring()
                startPeriodicEvaluation()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting paging storm monitoring", e)
                stopMonitoring()
            }
        }
    }
    
    /**
     * Stop monitoring for paging storms
     */
    fun stopMonitoring() {
        Log.d(TAG, "Stopping paging storm monitoring")
        isMonitoring = false
        
        networkCallback?.let { callback ->
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }
        
        networkCallback = null
        backgroundScope.cancel()
        networkMonitorScope.cancel()
    }
    
    /**
     * Record a paging event for analysis
     */
    fun recordPagingEvent(
        eventType: com.cymatune.detection.DetectionPagingEventType,
        cellInfo: CellInfo? = null,
        signalStrength: Int? = null,
        timestamp: Long = System.currentTimeMillis()
    ) {
        if (!isMonitoring) return
        
        val pagingEvent = PagingEvent(
            timestamp = timestamp,
            eventType = eventType.toDatabaseType(),
            cid = extractCellId(cellInfo) ?: 0,
            lac = extractLocationAreaCode(cellInfo) ?: 0,
            mcc = extractMCC(cellInfo) ?: 0,
            mnc = extractMNC(cellInfo) ?: 0,
            signalStrength = signalStrength ?: 0,
            networkType = getCurrentNetworkType(),
            timingAdvance = extractTimingAdvance(cellInfo) ?: 0
        )
        
        // Store event
        val key = "${pagingEvent.cid}_${pagingEvent.lac}"
        val events = pagingEvents.getOrPut(key) { mutableListOf() }
        events.add(pagingEvent)
        
        // Clean old events
        val cutoffTime = System.currentTimeMillis() - PAGING_WINDOW_MS
        events.removeAll { it.timestamp < cutoffTime }
        
        Log.d(TAG, "Recorded paging event: $eventType for cell $key")
        
        // Check for immediate storm indicators
        evaluateStormIndicators(key, events)
    }
    
    /**
     * Analyze current network state for paging storm indicators
     */
    suspend fun analyzeNetworkState(): NetworkAnalysisResult = withContext(backgroundScope.coroutineContext) {
        return@withContext try {
            val currentTime = System.currentTimeMillis()
            
            // Get current network state
            val currentSnapshot = captureNetworkState()
            currentNetworkState = currentSnapshot
            
            // Analyze recent paging patterns
            val pagingAnalysis = analyzePagingPatterns()
            
            // Check for cell tower anomalies
            val cellAnomalyAnalysis = analyzeCellTowerAnomalies()
            
            // Evaluate signal strength patterns
            val signalAnalysis = analyzeSignalPatterns()
            
            // Calculate overall storm probability
            val stormProbability = calculateStormProbability(
                pagingAnalysis, cellAnomalyAnalysis, signalAnalysis
            )
            
            NetworkAnalysisResult(
                timestamp = currentTime,
                currentNetworkState = currentSnapshot,
                pagingAnalysis = pagingAnalysis,
                cellAnomalyAnalysis = cellAnomalyAnalysis,
                signalAnalysis = signalAnalysis,
                stormProbability = stormProbability,
                isStormDetected = stormProbability > 0.7,
                recommendedAction = determineRecommendedAction(stormProbability)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing network state", e)
            NetworkAnalysisResult(
                timestamp = System.currentTimeMillis(),
                currentNetworkState = null,
                pagingAnalysis = null,
                cellAnomalyAnalysis = null,
                signalAnalysis = null,
                stormProbability = 0.0,
                isStormDetected = false,
                recommendedAction = "CONTINUE_MONITORING",
                error = e
            )
        }
    }
    
    /**
     * Correlate SMS events with paging patterns
     */
    suspend fun correlateSMSWithPaging(smsEvents: List<com.cymatune.db.SMSEvent>): SMSNetworkCorrelation {
        return try {
            val correlations = mutableListOf<CorrelationRecord>()
            
            smsEvents.forEach { smsEvent ->
                // Find paging events within time window of SMS
                val relevantPagingEvents = findPagingEventsInTimeWindow(
                    smsEvent.timestamp - 10000, // 10 seconds before
                    smsEvent.timestamp + 10000  // 10 seconds after
                )
                
                val correlation = CorrelationRecord(
                    smsEvent = smsEvent,
                    associatedPagingEvents = relevantPagingEvents,
                    correlationStrength = calculateCorrelationStrength(smsEvent, relevantPagingEvents),
                    isSuspicious = relevantPagingEvents.size > 3 || 
                        relevantPagingEvents.any { it.signalStrength != null && it.signalStrength!! > -70 }
                )
                
                correlations.add(correlation)
            }
            
            SMSNetworkCorrelation(
                totalCorrelations = correlations.size,
                suspiciousCorrelations = correlations.count { it.isSuspicious },
                correlations = correlations,
                overallSuspiciousness = correlations.any { it.isSuspicious }
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error correlating SMS with paging", e)
            SMSNetworkCorrelation(
                totalCorrelations = 0,
                suspiciousCorrelations = 0,
                correlations = emptyList(),
                overallSuspiciousness = false,
                error = e
            )
        }
    }
    
    private fun initializeNetworkMonitoring() {
        try {
            // Register for network connectivity changes
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available: ${network}")
                    recordNetworkEvent("NETWORK_AVAILABLE", network)
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost: ${network}")
                    recordNetworkEvent("NETWORK_LOST", network)
                }
                
                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    Log.d(TAG, "Network capabilities changed")
                    recordNetworkEvent("NETWORK_CAPABILITIES_CHANGED", network)
                }
                
                override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                    Log.d(TAG, "Link properties changed")
                    recordNetworkEvent("LINK_PROPERTIES_CHANGED", network)
                }
            }
            
            connectivityManager.registerNetworkCallback(networkRequest, callback)
            networkCallback = callback
            
            Log.d(TAG, "Network monitoring initialized successfully")
            
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot register network callback due to permissions", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing network monitoring", e)
        }
    }
    
    private fun startPeriodicEvaluation() {
        backgroundScope.launch {
            while (isMonitoring) {
                try {
                    kotlinx.coroutines.delay(STORM_EVALUATION_INTERVAL_MS.toLong())
                    if (isMonitoring) {
                        evaluateStormConditions()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic storm evaluation", e)
                }
            }
        }
    }
    
    private suspend fun evaluateStormConditions() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastStormEvaluation < STORM_EVALUATION_INTERVAL_MS) {
            return
        }
        lastStormEvaluation = currentTime
        
        val analysisResult = analyzeNetworkState()
        
        if (analysisResult.isStormDetected) {
            Log.w(TAG, "Paging storm detected! Probability: ${analysisResult.stormProbability}")
            // Trigger alert or action
        }
    }
    
    private fun recordNetworkEvent(eventType: String, network: Network) {
        if (!isMonitoring) return
        
        try {
            val snapshot = captureNetworkState()
            networkStateHistory.add(snapshot)
            
            // Keep only recent history
            if (networkStateHistory.size > 100) {
                networkStateHistory.subList(0, 20).clear()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error recording network event", e)
        }
    }
    
    
    private suspend fun analyzePagingPatterns(): PagingAnalysis {
        return try {
            val allEvents = pagingEvents.values.flatten()
            val recentEvents = allEvents.filter {
                System.currentTimeMillis() - it.timestamp < PAGING_WINDOW_MS
            }

            val eventCount = recentEvents.size
            val uniqueCells = recentEvents.mapNotNull { it.cid }.toSet().size
            val eventFrequency = if (recentEvents.size > 1) {
                val timeSpan = recentEvents.maxOf { it.timestamp } - recentEvents.minOf { it.timestamp }
                if (timeSpan > 0) eventCount.toDouble() / timeSpan * 60000 else 0.0
            } else 0.0

        // Phase 3: Use DetectionThresholdManager for paging storm threshold
        val pagingThreshold = DetectionThresholdManager.getPagingStormThreshold()
        val isHighFrequency = eventFrequency > pagingThreshold &&
                              eventCount >= BACKGROUND_APP_FILTER_THRESHOLD

            // Additional burst detection for short-duration storms
            val recentBurstEvents = allEvents.filter {
                System.currentTimeMillis() - it.timestamp < BURST_DETECTION_WINDOW_MS
            }
            val burstCount = recentBurstEvents.size
            val isBurstMode = burstCount > (BACKGROUND_APP_FILTER_THRESHOLD / 2) &&
                             burstCount.toDouble() / BURST_DETECTION_WINDOW_MS * 60000 > PAGING_STORM_THRESHOLD * 1.5

            PagingAnalysis(
                totalEvents = eventCount,
                uniqueCells = uniqueCells,
                frequencyPerMinute = eventFrequency,
                isHighFrequency = isHighFrequency || isBurstMode,
                timeWindowMs = PAGING_WINDOW_MS.toLong(),
                mostActiveCell = recentEvents.groupBy { it.cid }.maxByOrNull { it.value.size }?.key
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing paging patterns", e)
            PagingAnalysis()
        }
    }
    
    private suspend fun analyzeCellTowerAnomalies(): CellTowerAnalysis {
        return try {
            val recentChanges = cellChangeHistory.filter { 
                System.currentTimeMillis() - it.timestamp < PAGING_WINDOW_MS 
            }
            
            val changeCount = recentChanges.size
            val uniqueTowers = recentChanges.map { it.newCellId }.toSet().size
            val changeFrequency = changeCount.toDouble() / PAGING_WINDOW_MS * 60000L
            
            val anomalies = mutableListOf<String>()
            var anomalyScore = 0.0
            
            if (changeCount > CELL_CHANGE_THRESHOLD) {
                anomalies.add("Excessive cell changes")
                anomalyScore += 0.4
            }
            
            if (uniqueTowers > 10) {
                anomalies.add("Too many unique towers")
                anomalyScore += 0.3
            }
            
            // Check for rapid tower switching
            val rapidSwitches = recentChanges.count { 
                it.switchTimeMs < 5000 // Less than 5 seconds between switches
            }
            if (rapidSwitches > 3) {
                anomalies.add("Rapid tower switching")
                anomalyScore += 0.35
            }
            
            CellTowerAnalysis(
                cellChanges = changeCount,
                uniqueTowers = uniqueTowers,
                changeFrequency = changeFrequency,
                rapidSwitches = rapidSwitches,
                anomalies = anomalies,
                anomalyScore = anomalyScore
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing cell tower anomalies", e)
            CellTowerAnalysis()
        }
    }
    
    private suspend fun analyzeSignalPatterns(): SignalAnalysis {
        return try {
            val recentSignals = networkStateHistory.filter { 
                System.currentTimeMillis() - it.timestamp < PAGING_WINDOW_MS 
            }
            
            val signalValues = recentSignals.mapNotNull { it.signalStrength }
            val avgSignal = if (signalValues.isNotEmpty()) signalValues.average() else 0.0
            val signalVariance = if (signalValues.size > 1) {
                val mean = signalValues.average()
                signalValues.map { (it - mean) * (it - mean) }.average()
            } else 0.0
            
            val anomalies = mutableListOf<String>()
            var anomalyScore = 0.0
            
            if (avgSignal < SIGNAL_ANOMALY_THRESHOLD) {
                anomalies.add("Poor average signal strength")
                anomalyScore += 0.3
            }
            
            if (signalVariance > 100) {
                anomalies.add("High signal variance")
                anomalyScore += 0.25
            }
            
            // Check for signal drops during network events
            val signalDrops = countSignalDrops(recentSignals)
            if (signalDrops > 5) {
                anomalies.add("Frequent signal drops")
                anomalyScore += 0.3
            }
            
            SignalAnalysis(
                averageSignal = avgSignal,
                signalVariance = signalVariance,
                signalDrops = signalDrops,
                anomalies = anomalies,
                anomalyScore = anomalyScore
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing signal patterns", e)
            SignalAnalysis()
        }
    }
    
    private fun calculateStormProbability(
        pagingAnalysis: PagingAnalysis,
        cellAnalysis: CellTowerAnalysis,
        signalAnalysis: SignalAnalysis
    ): Double {
        var probability = 0.0
        
        // Base probability from paging frequency
        if (pagingAnalysis.isHighFrequency) probability += 0.4
        
        // Cell tower anomaly contribution
        probability += cellAnalysis.anomalyScore * 0.3
        
        // Signal anomaly contribution
        probability += signalAnalysis.anomalyScore * 0.3
        
        return probability.coerceIn(0.0, 1.0)
    }
    
    private fun determineRecommendedAction(stormProbability: Double): String {
        return when {
            stormProbability >= 0.8 -> "IMMEDIATE_ALERT"
            stormProbability >= 0.6 -> "HIGH_MONITORING"
            stormProbability >= 0.4 -> "INCREASED_MONITORING"
            else -> "CONTINUE_MONITORING"
        }
    }
    
    private fun evaluateStormIndicators(cellKey: String, events: List<PagingEvent>) {
        val recentEvents = events.filter { 
            System.currentTimeMillis() - it.timestamp < PAGING_WINDOW_MS 
        }
        
        if (recentEvents.size >= PAGING_STORM_THRESHOLD) {
            Log.w(TAG, "Paging storm indicators detected for cell $cellKey: ${recentEvents.size} events")
        }
    }
    
    private fun findPagingEventsInTimeWindow(startTime: Long, endTime: Long): List<PagingEvent> {
        return pagingEvents.values.flatten().filter { 
            it.timestamp in startTime..endTime 
        }
    }
    
    private fun calculateCorrelationStrength(
        smsEvent: com.cymatune.db.SMSEvent, 
        pagingEvents: List<PagingEvent>
    ): Double {
        var correlation = 0.0
        
        // Temporal correlation
        if (pagingEvents.isNotEmpty()) {
            correlation += 0.4
        }
        
        // Same cell tower correlation
        val sameCellPaging = pagingEvents.count { 
            it.cid == smsEvent.cid && it.lac == smsEvent.lac 
        }
        if (sameCellPaging > 0) {
            correlation += sameCellPaging.toDouble() / pagingEvents.size * 0.4
        }
        
        // Signal strength correlation
        val signalCorrelation = pagingEvents.mapNotNull { it.signalStrength }.let { list ->
            if (list.isNotEmpty()) list.average() else null
        }
        if (signalCorrelation != null && signalCorrelation > -70) {
            correlation += 0.2
        }
        
        return correlation.coerceAtMost(1.0)
    }
    
    private fun countSignalDrops(signals: List<NetworkStateSnapshot>): Int {
        var drops = 0
        for (i in 1 until signals.size) {
            val prev = signals[i-1].signalStrength
            val curr = signals[i].signalStrength
            if (prev != null && curr != null && curr - prev < -10) {
                drops++
            }
        }
        return drops
    }
    
    // Helper methods for extracting cell information
    private fun extractCellId(cellInfo: CellInfo?): Int? {
        return try {
            when (cellInfo) {
                is android.telephony.CellInfoLte -> {
                    try {
                        cellInfo.cellIdentity.ci
                    } catch (e: SecurityException) {
                        null
                    }
                }
                is android.telephony.CellInfoGsm -> cellInfo.cellIdentity.cid
                is android.telephony.CellInfoWcdma -> {
                    try {
                        cellInfo.cellIdentity.cid
                    } catch (e: SecurityException) {
                        null
                    }
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is android.telephony.CellInfoNr) {
                        try {
                            // NR cell identity extraction for API 29+
                            (cellInfo.cellIdentity as? android.telephony.CellIdentityNr)?.nci?.toInt()
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun captureNetworkState(): NetworkStateSnapshot {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        
        return try {
            val cellInfoList = if (ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                telephonyManager.allCellInfo
            } else {
                null
            }
            
            // Use public APIs instead of reflection to avoid hidden API access
            val signalStrength = cellInfoList?.firstOrNull()?.let { cellInfo ->
                when (cellInfo) {
                    is android.telephony.CellInfoLte -> cellInfo.cellSignalStrength.dbm
                    is android.telephony.CellInfoGsm -> cellInfo.cellSignalStrength.dbm
                    is android.telephony.CellInfoWcdma -> cellInfo.cellSignalStrength.dbm
                    else -> null
                }
            }
            
            val networkType = if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                telephonyManager.networkType.toString()
            } else {
                "UNKNOWN"
            }

            NetworkStateSnapshot(
                timestamp = System.currentTimeMillis(),
                networkType = networkType,
                signalStrength = signalStrength
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing network state", e)
            NetworkStateSnapshot(
                timestamp = System.currentTimeMillis(),
                networkType = "UNKNOWN",
                signalStrength = null
            )
        }
    }
    
    private fun extractLocationAreaCode(cellInfo: CellInfo?): Int? {
        return try {
            when (cellInfo) {
                is android.telephony.CellInfoLte -> {
                    try {
                        cellInfo.cellIdentity.tac
                    } catch (e: SecurityException) {
                        null
                    }
                }
                is android.telephony.CellInfoGsm -> cellInfo.cellIdentity.lac
                is android.telephony.CellInfoWcdma -> {
                    try {
                        cellInfo.cellIdentity.lac
                    } catch (e: SecurityException) {
                        null
                    }
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is android.telephony.CellInfoNr) {
                        try {
                            try {
                                cellInfo.cellIdentity.javaClass.getMethod("getTac").invoke(cellInfo.cellIdentity) as Int
                            } catch (e: Exception) {
                                null
                            }
                        } catch (e: SecurityException) {
                            null
                        }
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun extractMCC(cellInfo: CellInfo?): Int? {
        return try {
            when (cellInfo) {
                is android.telephony.CellInfoLte -> {
                    try {
                        cellInfo.cellIdentity.mcc
                    } catch (e: SecurityException) {
                        null
                    }
                }
                is android.telephony.CellInfoGsm -> cellInfo.cellIdentity.mcc
                is android.telephony.CellInfoWcdma -> {
                    try {
                        cellInfo.cellIdentity.mcc
                    } catch (e: SecurityException) {
                        null
                    }
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is android.telephony.CellInfoNr) {
                        try {
                            try {
                                cellInfo.cellIdentity.javaClass.getMethod("getMcc").invoke(cellInfo.cellIdentity) as Int
                            } catch (e: Exception) {
                                null
                            }
                        } catch (e: SecurityException) {
                            null
                        }
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun extractMNC(cellInfo: CellInfo?): Int? {
        return try {
            when (cellInfo) {
                is android.telephony.CellInfoLte -> {
                    try {
                        cellInfo.cellIdentity.mnc
                    } catch (e: SecurityException) {
                        null
                    }
                }
                is android.telephony.CellInfoGsm -> cellInfo.cellIdentity.mnc
                is android.telephony.CellInfoWcdma -> {
                    try {
                        cellInfo.cellIdentity.mnc
                    } catch (e: SecurityException) {
                        null
                    }
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is android.telephony.CellInfoNr) {
                        try {
                            try {
                                cellInfo.cellIdentity.javaClass.getMethod("getMnc").invoke(cellInfo.cellIdentity) as Int
                            } catch (e: Exception) {
                                null
                            }
                        } catch (e: SecurityException) {
                            null
                        }
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun extractTimingAdvance(cellInfo: CellInfo?): Int? {
        return try {
            when (cellInfo) {
                is android.telephony.CellInfoLte -> {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                            try {
                                cellInfo.javaClass.getMethod("getTimingAdvance").invoke(cellInfo) as Int
                            } catch (e: Exception) {
                                null
                            }
                        } else {
                            null
                        }
                    } catch (e: SecurityException) {
                        null
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getCurrentNetworkType(): String {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                when (telephonyManager.networkType) {
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
                    TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
                    TelephonyManager.NETWORK_TYPE_NR -> "NR"
                    else -> "UNKNOWN"
                }
            } else {
                "UNKNOWN"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }
    
    private fun Double?.averageOrNull(): Double? = this?.takeIf { !it.isNaN() && !it.isInfinite() }
    
    // Data classes
    data class NetworkStateSnapshot(
        val timestamp: Long,
        val networkType: String? = null,
        val dataState: Int? = null,
        val callState: Int? = null,
        val signalStrength: Int? = null,
        val cid: Int? = null,
        val lac: Int? = null,
        val mcc: Int? = null,
        val mnc: Int? = null,
        val isRoaming: Boolean? = null
    )
    
    private data class CellChangeRecord(
        val timestamp: Long,
        val oldCellId: Int? = null,
        val newCellId: Int? = null,
        val switchTimeMs: Long = 0,
        val signalStrength: Int? = null
    )
    
    data class NetworkAnalysisResult(
        val timestamp: Long,
        val currentNetworkState: NetworkStateSnapshot?,
        val pagingAnalysis: PagingAnalysis?,
        val cellAnomalyAnalysis: CellTowerAnalysis?,
        val signalAnalysis: SignalAnalysis?,
        val stormProbability: Double,
        val isStormDetected: Boolean,
        val recommendedAction: String,
        val error: Exception? = null
    )
    
    data class PagingAnalysis(
        val totalEvents: Int = 0,
        val uniqueCells: Int = 0,
        val frequencyPerMinute: Double = 0.0,
        val isHighFrequency: Boolean = false,
        val timeWindowMs: Long = PAGING_WINDOW_MS.toLong(),
        val mostActiveCell: Int? = null
    )
    
    data class CellTowerAnalysis(
        val cellChanges: Int = 0,
        val uniqueTowers: Int = 0,
        val changeFrequency: Double = 0.0,
        val rapidSwitches: Int = 0,
        val anomalies: List<String> = emptyList(),
        val anomalyScore: Double = 0.0
    )
    
    data class SignalAnalysis(
        val averageSignal: Double = 0.0,
        val signalVariance: Double = 0.0,
        val signalDrops: Int = 0,
        val anomalies: List<String> = emptyList(),
        val anomalyScore: Double = 0.0
    )
    
    data class CorrelationRecord(
        val smsEvent: com.cymatune.db.SMSEvent,
        val associatedPagingEvents: List<PagingEvent>,
        val correlationStrength: Double,
        val isSuspicious: Boolean
    )
    
    data class SMSNetworkCorrelation(
        val totalCorrelations: Int,
        val suspiciousCorrelations: Int,
        val correlations: List<CorrelationRecord>,
        val overallSuspiciousness: Boolean,
        val error: Exception? = null
    )
}