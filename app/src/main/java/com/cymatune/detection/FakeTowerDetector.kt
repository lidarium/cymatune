package com.cymatune.detection

import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoGsm
import android.telephony.CellInfoWcdma
import android.telephony.CellInfoCdma
import android.telephony.CellInfoNr
import android.os.Build
import android.util.Log
import com.cymatune.db.FakeTowerDao
import com.cymatune.db.FakeTower
import com.cymatune.db.ProtocolHandshakeDao
import com.cymatune.db.NeighborHistoryDao
import com.cymatune.db.LacCidPatternDao
import com.cymatune.db.LocationHistoryDao
import com.cymatune.db.TowerDao
import com.cymatune.util.EncryptionProtocolInfo
import com.cymatune.util.TowerConnectionInfo
import com.cymatune.util.DistanceEstimator
import com.cymatune.util.CommonUtils
import com.cymatune.detection.LacCidPatternAnalyzer
import com.cymatune.detection.SuddenLacChangeDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*
import java.util.concurrent.ConcurrentHashMap

data class SignalInfo(
    val signal: Int,
    val rsrp: Int?,
    val rsrq: Int?,
    val sinr: Int?
)

data class SuspiciousTower(
    val fakeTower: FakeTower,
    val suspicionReason: String,
    val confidence: Double,
    val firstDetected: Long
)

object FakeTowerDetector {
    // Track active connection anomalies: Map<CID, List<Anomaly>>
    private val activeConnectionAnomalies = ConcurrentHashMap<String, MutableList<ConnectionStateMonitor.ConnectionAnomaly>>()

    // Motion & Signal Tracking for "Follower" Detection
    private val locationHistory = java.util.LinkedList<Pair<Long, Pair<Double, Double>>>() // Timestamp -> (Lat, Lon)
    private val signalHistory = java.util.LinkedList<Int>() // Recent signal strength samples
    private var connectionStartTime: Long = 0
    private var connectionStartCid: String? = null
    
    // Internet Connectivity State
    private var lastInternetCheckTime: Long = 0
    private var isInternetReachable: Boolean = true

    fun reportConnectionAnomaly(anomaly: ConnectionStateMonitor.ConnectionAnomaly) {
        val list = activeConnectionAnomalies.getOrPut(anomaly.cid) { mutableListOf() }
        list.add(anomaly)
        // Keep only recent anomalies (last 5 mins)
        val cutoff = System.currentTimeMillis() - 300000
        // Note: Anomaly doesn't have timestamp, but we can clear old ones periodically or on access
    }
    // Load native library
    init {
        try {
            Log.i("FakeTowerDetector", "Loading native library: native-lib")
            System.loadLibrary("native-lib")
            Log.i("FakeTowerDetector", "Native library loaded successfully")
        } catch (e: Exception) {
            Log.e("FakeTowerDetector", "Failed to load native library", e)
            Log.e("FakeTowerDetector", "Native library loading error: ${e.message}")
        }
    }

    // FakeTowerDao reference for fake tower data access
    private var fakeTowerDao: FakeTowerDao? = null
    // ProtocolHandshakeDao reference for protocol analysis data access
    private var protocolHandshakeDao: ProtocolHandshakeDao? = null
    // NeighborHistoryDao reference for neighbor consistency checks
    private var neighborHistoryDao: NeighborHistoryDao? = null
    // LacCidPatternDao reference for LAC/CID pattern analysis
    private var lacCidPatternDao: LacCidPatternDao? = null
    // LocationHistoryDao reference for location-based analysis
    private var locationHistoryDao: LocationHistoryDao? = null
    // TowerDao reference for accessing tower data
    private var towerDao: TowerDao? = null

    /**
     * Initialize the detector with database access
     */
    fun initialize(
        fakeTowerDao: FakeTowerDao,
        protocolHandshakeDao: ProtocolHandshakeDao,
        neighborHistoryDao: NeighborHistoryDao,
        lacCidPatternDao: LacCidPatternDao,
        locationHistoryDao: LocationHistoryDao,
        towerDao: TowerDao
    ) {
        Log.d("FakeTowerDetector", "Initializing detector with database access")
        
        this.fakeTowerDao = fakeTowerDao
        this.protocolHandshakeDao = protocolHandshakeDao
        this.neighborHistoryDao = neighborHistoryDao
        this.lacCidPatternDao = lacCidPatternDao
        this.locationHistoryDao = locationHistoryDao
        this.towerDao = towerDao
        
        Log.d("FakeTowerDetector", "Detector initialized successfully")
        Log.d("FakeTowerDetector", "Database connections: fakeTowerDao=${fakeTowerDao != null}, protocolHandshakeDao=${protocolHandshakeDao != null}, lacCidPatternDao=${lacCidPatternDao != null}, towerDao=${towerDao != null}")
    }

    /**
     * Check if the detector is properly initialized
     */
    private fun isInitialized(): Boolean {
        val initialized = fakeTowerDao != null && protocolHandshakeDao != null && lacCidPatternDao != null && towerDao != null
        if (!initialized) {
            Log.w("FakeTowerDetector", "Detector not fully initialized. Status: fakeTowerDao=${fakeTowerDao != null}, protocolHandshakeDao=${protocolHandshakeDao != null}, lacCidPatternDao=${lacCidPatternDao != null}, towerDao=${towerDao != null}")
        } else {
            Log.d("FakeTowerDetector", "Detector fully initialized and ready for analysis")
        }
        return initialized
    }

    // Native method declarations for optimized signal analysis
    external fun analyzeSignalNative(
        cid: Int, lac: Int, mcc: Int, mnc: Int,
        rsrp: Int, rsrq: Int, sinr: Int, ta: Int
    ): String

    // Detection thresholds and constants
    private const val MIN_CUMULATIVE_CONFIDENCE_THRESHOLD = 0.7 // Higher threshold for fake-only detection

    // Signal anomaly detection thresholds (dB)
    private const val SIGNAL_ANOMALY_THRESHOLD_STRONGER = 15.0 // Signal much stronger than expected
    private const val SIGNAL_ANOMALY_THRESHOLD_WEAKER = 25.0 // Signal much weaker than expected

    // Frequency bands (MHz) - Common defaults for path loss calculations
    private const val FREQ_LTE_DEFAULT = 1800.0
    private const val FREQ_WCDMA_DEFAULT = 900.0
    private const val FREQ_GSM_DEFAULT = 900.0
    private const val FREQ_NR_DEFAULT = 2600.0

    // Timing Advance to distance conversion constants
    private const val TA_TO_METERS_CORRECT = 78.125 // CORRECT: Physics-based calculation
    private const val TA_TO_METERS_LEGACY = 550.0 // LEGACY: Old incorrect value (kept for reference)

    /**
     * Extracts signal information from different cell types
     */
    private fun extractSignalInfo(cell: CellInfo): SignalInfo {
        return when (cell) {
            is CellInfoLte -> SignalInfo(
                signal = cell.cellSignalStrength.level,
                rsrp = cell.cellSignalStrength.rsrp,
                rsrq = cell.cellSignalStrength.rsrq,
                sinr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cell.cellSignalStrength.rssnr
                } else {
                    null
                }
            )
            is CellInfoWcdma -> SignalInfo(
                signal = cell.cellSignalStrength.level,
                rsrp = Int.MIN_VALUE,
                rsrq = null,
                sinr = null
            )
            is CellInfoGsm -> SignalInfo(
                signal = cell.cellSignalStrength.level,
                rsrp = Int.MIN_VALUE,
                rsrq = null,
                sinr = null
            )
            is CellInfoCdma -> SignalInfo(
                signal = cell.cellSignalStrength.level,
                rsrp = Int.MIN_VALUE,
                rsrq = null,
                sinr = null
            )
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr) {
                    SignalInfo(
                        signal = cell.cellSignalStrength.level,
                        rsrp = (cell.cellSignalStrength as android.telephony.CellSignalStrengthNr).csiRsrp,
                        rsrq = (cell.cellSignalStrength as android.telephony.CellSignalStrengthNr).csiRsrq,
                        sinr = (cell.cellSignalStrength as android.telephony.CellSignalStrengthNr).csiSinr
                    )
                } else {
                    SignalInfo(
                        signal = -1,
                        rsrp = Int.MIN_VALUE,
                        rsrq = null,
                        sinr = null
                    )
                }
            }
        }
    }

    /**
     * Analyzes a cell tower for suspicious patterns using comprehensive detection algorithms
     *
     * @param cell CellInfo object containing tower signal data
     * @param location Optional user location for geographic analysis
     * @param timingAdvance Optional Timing Advance value for distance calculation
     * @param neighbors List of neighboring cell IDs for consistency checking
     * @return SuspiciousTower if suspicious patterns detected, null otherwise
     */
    suspend fun analyzeTower(
        cell: CellInfo,
        location: Pair<Double, Double>?,
        currentTA: Int?,
        neighbors: List<String>,
        isUserMoving: Boolean = false,
        movementIntensity: MovementDetector.MovementIntensity = MovementDetector.MovementIntensity.STATIONARY
    ): SuspiciousTower? {
        val currentTime = System.currentTimeMillis()
        val timer = com.cymatune.util.LoggingManager.startTimer(
            com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
            "analyzeTower"
        )
        
        com.cymatune.util.LoggingManager.i(
            com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
            "Starting tower analysis",
            mapOf(
                "cellType" to cell.javaClass.simpleName,
                "locationAvailable" to (location != null),
                "taAvailable" to (currentTA != null),
                "neighborCount" to neighbors.size
            )
        )
        
        // Check initialization first
        if (!isInitialized()) {
            com.cymatune.util.LoggingManager.w(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "Analysis aborted: detector not initialized"
            )
            return null
        }
        
        // Extract signal information based on cell type
        val signalInfo = extractSignalInfo(cell)
        val signal = signalInfo.signal
        val rsrp = signalInfo.rsrp
        val rsrq = signalInfo.rsrq
        val sinr = signalInfo.sinr
        
        com.cymatune.util.LoggingManager.d(
            com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
            "Signal info extracted",
            mapOf(
                "rsrp" to (rsrp ?: 0),
                "rsrq" to (rsrq ?: 0),
                "sinr" to (sinr ?: 0),
                "signal" to signal
            )
        )

        // Treat TA=2147483647 (Integer.MAX_VALUE) as unknown
        val processedTA = if (currentTA == Int.MAX_VALUE) null else currentTA
        com.cymatune.util.LoggingManager.d(
            com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
            "Processed TA",
            mapOf("processedTA" to (processedTA ?: 0))
        )

        // Extract tower information from cell
        val towerInfo = extractTowerInfoFromCell(cell)
        if (towerInfo == null) {
            com.cymatune.util.LoggingManager.w(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "Failed to extract tower info from cell"
            )
            return null
        }
        
        com.cymatune.util.LoggingManager.d(
            com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
            "Tower info extracted",
            mapOf(
                "cid" to towerInfo.cid,
                "lac" to towerInfo.lac,
                "mcc" to (towerInfo.mcc ?: 0),
                "mnc" to (towerInfo.mnc ?: 0)
            )
        )

        // Perform native signal analysis if available
        try {
            val nativeResult = analyzeSignalNative(
                towerInfo.cid,
                towerInfo.lac,
                towerInfo.mcc ?: 0,
                towerInfo.mnc ?: 0,
                rsrp ?: Int.MIN_VALUE,
                rsrq ?: Int.MIN_VALUE,
                sinr ?: Int.MIN_VALUE,
                processedTA ?: Int.MIN_VALUE
            )
            com.cymatune.util.LoggingManager.d(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "Native analysis completed",
                mapOf("result" to nativeResult)
            )
        } catch (e: Exception) {
            com.cymatune.util.LoggingManager.e(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "Native analysis failed",
                e
            )
        }

        // Detect suspicious patterns using comprehensive analysis
        val suspicionResult = detectSuspiciousPatterns(
            towerInfo, signal, location, processedTA, rsrp, rsrq, sinr, currentTime, neighbors,
            isUserMoving, movementIntensity
        )

        return if (suspicionResult != null) {
            com.cymatune.util.LoggingManager.i(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "Suspicious tower detected",
                mapOf(
                    "reason" to suspicionResult.reason,
                    "confidence" to suspicionResult.confidence
                )
            )
            
            // Check if we have previous records for this tower
            val existingTower = fakeTowerDao?.getFakeTower(
                towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0
            )
            
            val observationCount = if (existingTower != null) {
                existingTower.observationCount + 1
            } else {
                1
            }
            
            com.cymatune.util.LoggingManager.d(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "Observation count calculated",
                mapOf("observationCount" to observationCount)
            )
            
            // Calculate precision radius based on observation count
            val precisionRadius = calculatePrecisionRadius(observationCount, location)
            
            // Create a FakeTower record
            val fakeTower = FakeTower(
                cid = towerInfo.cid,
                lac = towerInfo.lac,
                mcc = towerInfo.mcc ?: 0,
                mnc = towerInfo.mnc ?: 0,
                latitude = location?.first ?: 0.0,
                longitude = location?.second ?: 0.0,
                detectionTime = currentTime,
                accuracy = suspicionResult.confidence,
                reason = suspicionResult.reason,
                signalStrength = signal,
                networkType = towerInfo.networkType ?: "Unknown",
                observationCount = observationCount,
                precisionRadius = precisionRadius,
                pci = towerInfo.pci,
                arfcn = towerInfo.arfcn,
                timingAdvance = towerInfo.timingAdvance
            )
            
            com.cymatune.util.LoggingManager.i(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "Created suspicious tower record",
                mapOf(
                    "confidence" to suspicionResult.confidence,
                    "cid" to towerInfo.cid,
                    "lac" to towerInfo.lac
                )
            )
            
            timer.finish(
                mapOf(
                    "result" to "suspicious_tower_detected",
                    "confidence" to suspicionResult.confidence
                )
            )
            
            SuspiciousTower(
                fakeTower = fakeTower,
                suspicionReason = suspicionResult.reason,
                confidence = suspicionResult.confidence,
                firstDetected = currentTime
            )
        } else {
            com.cymatune.util.LoggingManager.d(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "No suspicious patterns detected"
            )
            
            timer.finish(mapOf("result" to "no_threat"))
            null
        }
    }

    // Data class for detailed suspicion results
    data class SuspicionResult(
        val reason: String,
        val confidence: Double,
        val patterns: List<String>
    )

    private suspend fun detectSuspiciousPatterns(
        towerInfo: TowerConnectionInfo,
        currentSignal: Int,
        location: Pair<Double, Double>?,
        currentTA: Int?,
        currentRSRP: Int?,
        currentRSRQ: Int?,
        currentSINR: Int?,
        currentTime: Long,
        neighbors: List<String>,
        isUserMoving: Boolean,
        movementIntensity: MovementDetector.MovementIntensity
    ): SuspicionResult? {
        val reasons = mutableListOf<String>()
        var cumulativeConfidence = 0.0

        // Simplified detection - just check basic patterns
        cumulativeConfidence += checkBasicTowerPatterns(towerInfo, currentSignal, reasons)
        
        val timingAdvanceResult = checkTimingAdvanceAnomaly(towerInfo, location, currentTA, currentRSRP, currentRSRQ, currentSINR, reasons)
        cumulativeConfidence += timingAdvanceResult.first
        
        // Check for geographic anomalies (conflicting coordinates for same tower)
        val geographicAnomalyResult = checkGeographicAnomaly(towerInfo, location, reasons)
        cumulativeConfidence += geographicAnomalyResult

        // Check for protocol anomalies
        val protocolResult = analyzeProtocol(towerInfo.cellInfo as? CellInfo, towerInfo)
        val protocolAnomalyResult = checkProtocolAnomalies(protocolResult, reasons)
        cumulativeConfidence += protocolAnomalyResult

        // Check for connection state anomalies (Stalling, Loops)
        val connectionAnomalyResult = checkConnectionAnomalies(towerInfo, reasons)
        cumulativeConfidence += connectionAnomalyResult

        // Check for neighbor consistency
        val neighborResult = checkNeighborConsistency(towerInfo, neighbors, reasons)
        cumulativeConfidence += neighborResult

        // Check for signal-distance consistency (physics validation)
        val signalDistanceResult = checkSignalDistanceConsistency(towerInfo, currentRSRP, currentTA, reasons)
        cumulativeConfidence += signalDistanceResult

        // Enhanced LAC/CID consistency analysis
        val lacCidAnalysis = analyzeLacCidConsistency(towerInfo, location, currentRSRP, reasons)
        cumulativeConfidence += lacCidAnalysis.first

        // D01 FIX: Pass movement state to suppress false positives during travel
        val suddenLacChangeAnalysis = analyzeSuddenLacChanges(location, reasons, isUserMoving, movementIntensity)
        cumulativeConfidence += suddenLacChangeAnalysis.first

        // PRO FEATURE: Logcat-based threat detection
        val logcatResult = checkLogcatThreats(towerInfo.cid, reasons)
        cumulativeConfidence += logcatResult

        // NEW: Moving Tower (Follower) Detection
        val followerResult = checkMovingTowerAnomaly(towerInfo.cid.toString(), currentSignal, location, currentTime, reasons, isUserMoving, movementIntensity)
        cumulativeConfidence += followerResult

        // NEW: Internet Connectivity Check (Silent)
        val internetResult = checkInternetConnectivity(towerInfo.signalStrength, reasons)
        cumulativeConfidence += internetResult

        // Simplified final decision logic
        val finalConfidence = cumulativeConfidence.coerceAtMost(1.0)
        
        // Check if we should flag based on protocol anomalies alone
        val hasProtocolAnomaly = reasons.any { it.contains("Protocol anomaly") }
        val protocolOnlyConfidence = protocolResult?.suspicionScore ?: 0.0
        
        // Also check for critical logcat threats
        val hasLogcatCriticalThreat = reasons.any { 
            it.contains("IMSI catcher") || it.contains("No encryption") || it.contains("Silent SMS")
        }
        
        return if (reasons.isNotEmpty() &&
                  (finalConfidence >= MIN_CUMULATIVE_CONFIDENCE_THRESHOLD ||
                   (hasProtocolAnomaly && protocolOnlyConfidence >= 0.8) ||
                   hasLogcatCriticalThreat)) {
            SuspicionResult(
                reason = reasons.joinToString(", "),
                confidence = finalConfidence,
                patterns = reasons
            )
        } else null
    }
    
    /**
     * Check for logcat-based threats using the LogcatDetectionBridge
     * PRO FEATURE: Requires READ_LOGS permission
     */
    private fun checkLogcatThreats(cid: Int, reasons: MutableList<String>): Double {
        var confidenceIncrease = 0.0
        
        try {
            val bridge = com.cymatune.logcat.LogcatDetectionBridge
            val threatScore = bridge.getCurrentThreatScore() ?: return 0.0
            
            // Check for encryption issues (highest weight)
            if (bridge.hasEncryptionThreat()) {
                reasons.add("⚠️ LOGCAT: No encryption or encryption downgrade detected")
                confidenceIncrease += 0.5  // Very high weight for encryption issues
                Log.w("FakeTowerDetector", "LOGCAT THREAT: Encryption issue detected for CID $cid")
            }
            
            // Check for IMSI catcher indicators
            if (bridge.hasImsiCatcherThreat()) {
                reasons.add("🚨 LOGCAT: IMSI catcher behavior detected (identity request)")
                confidenceIncrease += 0.6  // Critical weight for IMSI catcher
                Log.w("FakeTowerDetector", "LOGCAT THREAT: IMSI catcher indicator for CID $cid")
            }
            
            // Check for silent SMS (tracking indicator)
            if (bridge.hasSilentSmsThreat()) {
                reasons.add("⚠️ LOGCAT: Silent SMS detected (possible tracking)")
                confidenceIncrease += 0.35
                Log.w("FakeTowerDetector", "LOGCAT THREAT: Silent SMS detected near CID $cid")
            }
            
            // Check for active tower-specific threats
            if (bridge.hasActiveThreatForTower(cid)) {
                reasons.add("⚠️ LOGCAT: Active threat detected for this tower")
                confidenceIncrease += 0.25
            }
            
            // Add handover and protocol scores from logcat analysis
            if (threatScore.handoverScore > 0.4f) {
                reasons.add("⚠️ LOGCAT: Suspicious handover patterns detected")
                confidenceIncrease += threatScore.handoverScore * 0.2
            }
            
            if (threatScore.protocolScore > 0.3f) {
                reasons.add("⚠️ LOGCAT: Protocol anomalies detected")
                confidenceIncrease += threatScore.protocolScore * 0.15
            }
            
        } catch (e: Exception) {
            Log.e("FakeTowerDetector", "Error checking logcat threats", e)
        }
        
        return confidenceIncrease
    }

private fun checkBasicTowerPatterns(towerInfo: TowerConnectionInfo, currentSignal: Int, reasons: MutableList<String>): Double {
    // D05 FIX: Sanitize signal inputs - return early if signal is invalid
    if (currentSignal == Int.MAX_VALUE || currentSignal == Int.MIN_VALUE) {
        Log.d("FakeTowerDetector", "Skipping checkBasicTowerPatterns: Invalid signal value $currentSignal")
        return 0.0
    }

    var confidenceIncrease = 0.0

    // D05 FIX: Sanitize RSRP - check for invalid values
    val rsrp = towerInfo.ssRsrp
    if (rsrp == null || rsrp == Int.MAX_VALUE || rsrp == Int.MIN_VALUE) {
        Log.d("FakeTowerDetector", "Skipping RSRP analysis: Missing or invalid RSRP value")
        return 0.0 // Return early if RSRP is unavailable
    }

    // Check for unusually low signal
    if (currentSignal < -110) {
        reasons.add("Unusually low signal")
        confidenceIncrease += 0.3
    }

    // PHASE 4: Strong signal anomaly detection
    // D02 FIX: Account for urban environments with small cells and boosters
    // In Indian metros, strong signals (>-50dBm) are common due to dense small cell deployments

    // Check if this might be a legitimate small cell (high density urban area)
    // Small cells typically have very short range but very strong signal
    val timingAdvance = towerInfo.timingAdvance

    // D05 FIX: Sanitize timing advance - check for invalid values
    val isLikelySmallCell = towerInfo.cid > 100000 || // CID patterns for small cells
                           (timingAdvance != null && timingAdvance != Int.MAX_VALUE && timingAdvance < 10) // Very close proximity

    // In urban environments, small cells legitimately give very strong signals
    // Only flag as suspicious if:
    // 1. Signal is extremely strong AND
    // 2. It doesn't look like a small cell AND
    // 3. There are other indicators (protocol anomalies, etc.)
    if (rsrp > -45 && !isLikelySmallCell) {
        // Extremely strong signals without small cell characteristics - still suspicious
        reasons.add("Very Strong Signal: ${rsrp} dBm (exceeds typical small cell range)")
        confidenceIncrease += 0.3 // Reduced from 0.5 due to urban small cells
        Log.w("FakeTowerDetector", "Very Strong Signal: CID=${towerInfo.cid}, RSRP=${rsrp}dBm")
    } else if (rsrp > -50) {
        // Strong signal - might be small cell, just log for info
        Log.d("FakeTowerDetector", "Strong signal detected: CID=${towerInfo.cid}, RSRP=${rsrp}dBm " +
            "(likely small cell: ${isLikelySmallCell}, TA: ${towerInfo.timingAdvance})")
        // D02 FIX: Don't add to reasons or increase confidence for >-50 signals
        // These are common in Indian metros with small cells
    } else if (rsrp > -60 && !isLikelySmallCell) {
        // Moderately strong - only flag if not a small cell
        reasons.add("Moderate Signal Strength: ${rsrp} dBm")
        confidenceIncrease += 0.1 // Reduced from 0.2
    }

    return confidenceIncrease
}

    private suspend fun checkTimingAdvanceAnomaly(towerInfo: TowerConnectionInfo, location: Pair<Double, Double>?, currentTA: Int?, currentRSRP: Int?, currentRSRQ: Int?, currentSINR: Int?, reasons: MutableList<String>): Pair<Double, Int> {
        // D05 FIX: Sanitize timing advance - check for null or invalid values
        if (currentTA == null || currentTA == Int.MAX_VALUE || currentTA == Int.MIN_VALUE) {
            return Pair(0.0, 0)
        }

        // D05 FIX: Sanitize other signal values before analysis
        if (currentRSRP == Int.MAX_VALUE || currentRSRP == Int.MIN_VALUE ||
            currentRSRQ == Int.MAX_VALUE || currentRSRQ == Int.MIN_VALUE ||
            currentSINR == Int.MAX_VALUE || currentSINR == Int.MIN_VALUE) {
            Log.d("FakeTowerDetector", "Skipping TA anomaly check: Invalid signal values detected")
            return Pair(0.0, 0)
        }

        // Simplified TA anomaly detection
        if (currentTA > 50) { // Arbitrary threshold for suspicious TA
            reasons.add("Suspicious timing advance")
            return Pair(0.3, 1)
        }
        return Pair(0.0, 0)
    }

    private suspend fun checkGeographicAnomaly(towerInfo: TowerConnectionInfo, location: Pair<Double, Double>?, reasons: MutableList<String>): Double {
        if (location == null) return 0.0
        
        // Check if we have previous records for this tower
        val existingTower = fakeTowerDao?.getFakeTower(towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0)
        
        if (existingTower != null) {
            // Fix: Ignore 0.0 coordinates which indicate invalid/uninitialized location
            if (location.first == 0.0 && location.second == 0.0) return 0.0
            if (existingTower.latitude == 0.0 && existingTower.longitude == 0.0) return 0.0

            // Calculate distance between current location and previous location
            val distance = calculateDistance(
                location.first, location.second,
                existingTower.latitude, existingTower.longitude
            )
            
        // Phase 3: Use DetectionThresholdManager for geographic anomaly distance
        val maxDistance = DetectionThresholdManager.getGeographicAnomalyDistance()
        if (distance > maxDistance) {
                reasons.add("Geographic anomaly: Tower reported from conflicting locations (${String.format(java.util.Locale.US, "%.1f", distance/1000.0)}km difference)")
                return 0.5 // High confidence for geographic spoofing
            }
        }
        
        return 0.0
    }

    /**
     * Check for protocol-level anomalies
     * Protocol anomalies are weighted between 0.3-0.5 as per requirements
     */
    private fun checkProtocolAnomalies(protocolResult: ProtocolAnalysisResult?, reasons: MutableList<String>): Double {
        var confidenceIncrease = 0.0
        
        protocolResult?.let {
            if (it.suspicionScore > 0.6) {
                reasons.add("Protocol anomaly detected: ${it.anomalies.joinToString()}")
                // Weight protocol anomalies between 0.3-0.5 based on suspicion score
                val protocolWeight = 0.3 + (it.suspicionScore * 0.2) // Range: 0.3-0.5
                confidenceIncrease += it.suspicionScore * protocolWeight
            }
        }
        
        return confidenceIncrease
    }

    private fun checkConnectionAnomalies(towerInfo: TowerConnectionInfo, reasons: MutableList<String>): Double {
        val cid = towerInfo.cid.toString()
        val anomalies = activeConnectionAnomalies[cid] ?: return 0.0
        
        var confidenceIncrease = 0.0
        val processedTypes = mutableSetOf<ConnectionStateMonitor.AnomalyType>()
        
        for (anomaly in anomalies) {
            if (processedTypes.contains(anomaly.type)) continue
            
            when (anomaly.type) {
                ConnectionStateMonitor.AnomalyType.STALL -> {
                    reasons.add("Connection Stalling Detected: ${anomaly.details}")
                    confidenceIncrease += 0.4
                }
                ConnectionStateMonitor.AnomalyType.FAILURE_LOOP -> {
                    reasons.add("Connection Failure Loop: ${anomaly.details}")
                    confidenceIncrease += 0.5
                }
                ConnectionStateMonitor.AnomalyType.SILENT_DROP -> {
                    reasons.add("Silent Connection Drop: ${anomaly.details}")
                    confidenceIncrease += 0.3
                }
            }
            processedTypes.add(anomaly.type)
        }
        
        return confidenceIncrease
    }

    private suspend fun checkNeighborConsistency(towerInfo: TowerConnectionInfo, neighbors: List<String>, reasons: MutableList<String>): Double {
        val dao = neighborHistoryDao ?: return 0.0
        
        // 1. Check for empty neighbor list with VERY strong signal (Suspicious)
        // Relaxed threshold from -85 to -55 dBm to avoid false positives on devices with poor neighbor reporting
        if (neighbors.isEmpty() && towerInfo.signalStrength > -55) {
            reasons.add("Neighbor Anomaly: No neighbors reported despite extremely strong signal")
            return 0.4
        }

        // 2. Compare with history
        val history = dao.getRecentHistory(towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0)
        if (history.isNotEmpty()) {
            val avgNeighborCount = history.map { it.neighborCount }.average()
            // If current count is significantly less than average (e.g., < 20% of average)
            if (neighbors.size < avgNeighborCount * 0.2 && avgNeighborCount > 3) {
                reasons.add("Neighbor Anomaly: Significant drop in neighbor count (Current: ${neighbors.size}, Avg: ${String.format(java.util.Locale.US,"%.1f", avgNeighborCount)})")
                return 0.3
            }
        }

        return 0.0
    }
    
    /**
     * Detects if a tower is "Following" the user (Moving Tower Attack)
     * Logic: User is Moving (> 15km/h) + Connected to ONE tower for > 120s + Signal is STABLE (Variance < 3.0)
     * Cross-referenced with Accelerometer to prevent false positives from GPS jitter.
     * 
     * ENHANCED v2: Added GPS quality validation to prevent unrealistic speed calculations
     */
    private fun checkMovingTowerAnomaly(
        cid: String,
        currentSignal: Int,
        location: Pair<Double, Double>?,
        currentTime: Long,
        reasons: MutableList<String>,
        isUserMoving: Boolean,
        movementIntensity: MovementDetector.MovementIntensity
    ): Double {
        // 1. Update Connection State
        if (connectionStartCid != cid) {
            connectionStartCid = cid
            connectionStartTime = currentTime
            signalHistory.clear()
        }
        signalHistory.add(currentSignal)
        if (signalHistory.size > 20) signalHistory.removeFirst() // Keep last 20 samples

        // 2. Update Location History with GPS quality validation
        if (location != null && (location.first != 0.0 || location.second != 0.0)) {
            locationHistory.add(currentTime to location)
            // Keep last 60 seconds of history
            while (locationHistory.isNotEmpty() && currentTime - locationHistory.first.first > 60000) {
                locationHistory.removeFirst()
            }
        }
        
        // Need at least 2 points to calculate speed
        if (locationHistory.size < 2) return 0.0
        
        // ENHANCED: Require minimum 5 seconds between first and last reading
        // This smooths out GPS jitter that occurs within short time windows
        val first = locationHistory.first
        val last = locationHistory.last
        val timeDiffMs = last.first - first.first
        
        if (timeDiffMs < 5000) {
            // Not enough time elapsed for reliable speed calculation
            return 0.0
        }
        
        // Calculate average speed over history
        val distKm = calculateDistance(first.second.first, first.second.second, last.second.first, last.second.second)
        val timeHours = timeDiffMs / 3600000.0
        val speedKmph = if (timeHours > 0) distKm / timeHours else 0.0
        
        // ENHANCED: Strict accelerometer cross-validation
        // If accelerometer says STATIONARY but GPS shows high speed, it's GPS jitter
        val accelerometerConfirmsMovement = movementIntensity != MovementDetector.MovementIntensity.STATIONARY
        
        if (speedKmph > 20.0 && !accelerometerConfirmsMovement) {
            // GPS says moving fast, but accelerometer says still - GPS JITTER!
            Log.d("FakeTowerDetector", "GPS Jitter Detected: GPS speed=${String.format(java.util.Locale.US, "%.0f", speedKmph)}km/h but accelerometer=STATIONARY. Ignoring.")
            return 0.0
        }
        
        // ENHANCED: Lower speed cap from 500 to 200 km/h
        // 200 km/h = typical high-speed train. Anything above is unrealistic for cell tower analysis
        val MAX_REALISTIC_SPEED_KMPH = 200.0
        if (speedKmph > MAX_REALISTIC_SPEED_KMPH) {
            Log.w("FakeTowerDetector", "Unrealistic speed rejected: ${String.format(java.util.Locale.US, "%.0f", speedKmph)}km/h > ${MAX_REALISTIC_SPEED_KMPH}km/h cap")
            return 0.0
        }
        
        // Logic Trigger: Speed > 20 km/h (Moving Vehicle) AND accelerometer confirms
        if (speedKmph > 20.0 && accelerometerConfirmsMovement) {
            val connectedDurationSeconds = (currentTime - connectionStartTime) / 1000
            
            // Connected to SAME tower for > 2 minutes while moving fast
            if (connectedDurationSeconds > 120) {
                
                // Calculate Signal Standard Deviation
                val meanSignal = signalHistory.average()
                val variance = signalHistory.sumOf { (it - meanSignal).pow(2) } / signalHistory.size
                val stdDev = sqrt(variance)
                
                // Normal Behavior: Moving 20km/h = Signal fluctuates significantly (StdDev > 5.0) due to path loss/fading
                // Suspicious: Signal is FLAT (StdDev < 3.0) means tower is maintaining constant distance (Following)
                
                if (stdDev < 3.0 && signalHistory.size > 10) {
                    val movementStatus = movementIntensity.name
                    
                    reasons.add("🚨 MOVING TOWER THREAT: Constant signal (${String.format(java.util.Locale.US, "%.1f", stdDev)} dev) while moving ${String.format(java.util.Locale.US, "%.0f", speedKmph)}km/h for ${connectedDurationSeconds}s [Motion: $movementStatus]")
                    Log.e("FakeTowerDetector", "FOLLOWER DETECTED: Speed=$speedKmph, Dur=$connectedDurationSeconds, SigDev=$stdDev, Motion=$movementStatus")
                    return 0.85 // High Confidence
                }
            }
        }
        
        return 0.0
    }
    
    /**
     * Silently checks internet connectivity
     * If Signal is Strong but Internet is Dead -> Potential Interception/DoS
     */
    private fun checkInternetConnectivity(signalStrength: Int, reasons: MutableList<String>): Double {
        // Only run check every 30 seconds to save battery/data
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastInternetCheckTime < 30000) return 0.0
        lastInternetCheckTime = currentTime
        
        var isSuccess = false
        try {
            // Very lightweight connectivity check (HTTP 204 from Google)
            val url = java.net.URL("http://connectivitycheck.gstatic.com/generate_204")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.useCaches = false
            connection.connect()
            val responseCode = connection.responseCode
            isSuccess = (responseCode == 204)
            connection.disconnect()
        } catch (e: Exception) {
            isSuccess = false
        }
        
        isInternetReachable = isSuccess
        
        // Analysis: Strong Signal (> -95 dBm) but NO Internet
        // D05 FIX: Ignore if signal is invalid
        if (!isSuccess && signalStrength != Int.MAX_VALUE && signalStrength != Int.MIN_VALUE && signalStrength > -95) {
            // Double check: Is mobile data actually enabled? (We assume yes if user is using app features)
            reasons.add("⚠️ DATA BLACKHOLE: Strong signal ($signalStrength dBm) but no internet connectivity (Potential DoS/Interception)")
            return 0.3
        }
        
        return 0.0
    }

    /**
     * Calculate expected Free Space Path Loss (FSPL)
     * Formula: FSPL(dB) = 20*log10(d) + 20*log10(f) + 32.45
     * where d = distance in km, f = frequency in MHz
     */
    private fun calculateExpectedPathLoss(distanceMeters: Double, frequencyMhz: Double): Double {
        if (distanceMeters <= 0.0 || frequencyMhz <= 0.0) return 0.0
        
        val distanceKm = distanceMeters / 1000.0
        return 20.0 * log10(distanceKm) + 20.0 * log10(frequencyMhz) + 32.45
    }

    /**
     * Check if signal strength is consistent with reported distance (via Timing Advance)
     * Detects physics violations that suggest spoofing using enhanced distance estimation
     */
    private fun checkSignalDistanceConsistency(
        towerInfo: TowerConnectionInfo,
        rsrp: Int?,
        ta: Int?,
        reasons: MutableList<String>
    ): Double {
        // Skip if both TA and RSRP are unavailable
        if ((ta == null || ta == Int.MAX_VALUE || ta <= 0) && (rsrp == null || rsrp == Int.MIN_VALUE)) {
            com.cymatune.util.LoggingManager.d(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "Both TA and RSRP unavailable, skipping signal-distance check"
            )
            return 0.0
        }

        // Enhanced: Try RSRP-only analysis when TA is unavailable
        if ((ta == null || ta == Int.MAX_VALUE || ta <= 0) && rsrp != null && rsrp != Int.MIN_VALUE) {
            com.cymatune.util.LoggingManager.d(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "TA unavailable, attempting enhanced RSRP-only analysis"
            )
            return analyzeRsrpOnlyDistance(towerInfo, rsrp, reasons)
        }

        // Use enhanced distance estimation from DistanceEstimator
        val estimatedDistance = DistanceEstimator.calculateDistanceFromTA(ta)
        if (estimatedDistance == null) {
            com.cymatune.util.LoggingManager.d(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "TA distance calculation failed, using RSRP fallback"
            )
            return if (rsrp != null && rsrp != Int.MIN_VALUE) {
                analyzeRsrpOnlyDistance(towerInfo, rsrp, reasons)
            } else {
                0.0
            }
        }

        // Determine frequency based on network type for path loss calculation
        val networkType = towerInfo.networkType?.uppercase() ?: "UNKNOWN"
        val frequency = when (networkType) {
            "LTE" -> FREQ_LTE_DEFAULT
            "WCDMA", "UMTS" -> FREQ_WCDMA_DEFAULT
            "GSM" -> FREQ_GSM_DEFAULT
            "NR", "5G" -> FREQ_NR_DEFAULT
            else -> {
                com.cymatune.util.LoggingManager.w(
                    com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                    "Unknown network type '$networkType', defaulting to LTE frequency"
                )
                FREQ_LTE_DEFAULT
            }
        }

        com.cymatune.util.LoggingManager.d(
            com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
            "Signal-distance analysis",
            mapOf(
                "ta" to (ta ?: 0),
                "distance" to String.format(java.util.Locale.US,"%.1f", estimatedDistance),
                "networkType" to networkType,
                "frequency" to String.format(java.util.Locale.US,"%.0f", frequency)
            )
        )

        // Calculate expected path loss using enhanced model
        val expectedPathLoss = calculateExpectedPathLoss(estimatedDistance, frequency)
        com.cymatune.util.LoggingManager.d(
            com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
            "Path loss calculation",
            mapOf(
                "expectedPathLoss" to String.format(java.util.Locale.US,"%.2f", expectedPathLoss),
                "frequency" to String.format(java.util.Locale.US,"%.0f", frequency)
            )
        )

        // Estimate expected RSRP using more realistic transmit power
        val assumedTransmitPower = when (networkType) {
            "LTE" -> 43.0 // Typical LTE macro cell
            "NR", "5G" -> 46.0 // 5G often higher power
            "WCDMA", "UMTS" -> 41.0 // 3G typically lower
            "GSM" -> 33.0 // 2G much lower power
            else -> 43.0
        }
        
        val expectedRsrp = assumedTransmitPower - expectedPathLoss
        com.cymatune.util.LoggingManager.d(
            com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
            "RSRP expectation calculation",
            mapOf(
                "expectedRsrp" to String.format(java.util.Locale.US,"%.1f", expectedRsrp),
                "assumedTransmitPower" to String.format(java.util.Locale.US,"%.1f", assumedTransmitPower)
            )
        )

        // Calculate deviation
        val actualRsrp = rsrp?.toDouble() ?: return 0.0
        
        // D05 FIX: Sanitize actual RSRP for deviation calculation
        if (actualRsrp.toInt() == Int.MAX_VALUE || actualRsrp.toInt() == Int.MIN_VALUE) {
            return 0.0
        }
        
        val deviation = actualRsrp - expectedRsrp
        
        com.cymatune.util.LoggingManager.d(
            com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
            "Signal deviation analysis",
            mapOf(
                "actualRsrp" to actualRsrp,
                "deviation" to String.format(java.util.Locale.US,"%.1f", deviation)
            )
        )

        // Check for anomalies with more realistic thresholds
        var confidenceIncrease = 0.0

        if (deviation > SIGNAL_ANOMALY_THRESHOLD_STRONGER) {
            // Signal is much stronger than physics predicts - very suspicious
            com.cymatune.util.LoggingManager.w(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "SIGNAL ANOMALY DETECTED - Signal stronger than expected",
                null,
                mapOf(
                    "deviation" to String.format(java.util.Locale.US,"%.1f", deviation),
                    "distanceKm" to String.format(java.util.Locale.US,"%.1f", estimatedDistance / 1000.0)
                )
            )
            reasons.add("Signal-Distance Anomaly: Signal ${String.format(java.util.Locale.US,"%.1f", deviation)}dB stronger than expected at ${String.format(java.util.Locale.US,"%.1f", estimatedDistance / 1000.0)}km")
            confidenceIncrease = 0.5
        } else if (deviation < -SIGNAL_ANOMALY_THRESHOLD_WEAKER) {
            // Signal is much weaker than expected - environmental interference (less suspicious)
            com.cymatune.util.LoggingManager.w(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "Signal weaker than expected",
                null,
                mapOf(
                    "deviation" to String.format(java.util.Locale.US,"%.1f", -deviation)
                )
            )
            reasons.add("Signal-Distance Warning: Signal ${String.format(java.util.Locale.US,"%.1f", -deviation)}dB weaker than expected (environmental factors)")
            confidenceIncrease = 0.2
        } else {
            com.cymatune.util.LoggingManager.d(
                com.cymatune.util.LoggingManager.Component.FAKE_TOWER_DETECTION,
                "Signal strength within expected range",
                mapOf("deviation" to String.format(java.util.Locale.US,"%.1f", deviation))
            )
        }

        return confidenceIncrease
    }

    /**
     * Enhanced RSRP-only distance analysis when TA is unavailable
     * Uses environment-aware path loss models and signal quality analysis
     */
    private fun analyzeRsrpOnlyDistance(
        towerInfo: TowerConnectionInfo,
        rsrp: Int,
        reasons: MutableList<String>
    ): Double {
        Log.d("FakeTowerDetector", "DIAGNOSTIC: Starting enhanced RSRP-only analysis")
        
        // Use enhanced environment-aware distance estimation from DistanceEstimator
        val estimatedDistance = DistanceEstimator.calculateDistanceFromRSRP(
            rsrp = rsrp,
            rsrq = towerInfo.ssRsrq,
            sinr = towerInfo.ssSinr,
            latitude = towerInfo.estimatedLocation?.first,
            longitude = towerInfo.estimatedLocation?.second
        )
        
        if (estimatedDistance == null) {
            Log.d("FakeTowerDetector", "DIAGNOSTIC: Enhanced RSRP distance estimation failed")
            return 0.0
        }
        
        Log.d("FakeTowerDetector", "DIAGNOSTIC: Enhanced RSRP distance: ${String.format(java.util.Locale.US,"%.1f", estimatedDistance)} meters")
        
        // Analyze distance for suspicious patterns
        val distanceKm = estimatedDistance / 1000.0
        
        var confidenceIncrease = 0.0
        
        // Check for unrealistic distance estimates based on network type
        val networkType = towerInfo.networkType?.uppercase() ?: "UNKNOWN"
        when {
            distanceKm > 50.0 -> {
                // Very long distances might indicate spoofing in urban areas
                reasons.add("Unrealistic Distance: Estimated ${String.format(java.util.Locale.US,"%.1f", distanceKm)}km distance suggests potential spoofing")
                confidenceIncrease = 0.3
            }
            // D05 FIX: Sanitize RSRP before proximity check
            distanceKm < 0.05 && rsrp != Int.MAX_VALUE && rsrp > -60 -> {
                // Very close distance with strong signal might be suspicious
                reasons.add("Suspicious Proximity: Very close distance (${String.format(java.util.Locale.US,"%.1f", distanceKm)}km) with strong signal (${rsrp}dBm)")
                confidenceIncrease = 0.2
            }
            distanceKm > 20.0 && networkType in setOf("GSM", "WCDMA") -> {
                // Unusually long distances for 2G/3G networks
                reasons.add("Unusual Range: ${networkType} tower at ${String.format(java.util.Locale.US,"%.1f", distanceKm)}km distance")
                confidenceIncrease = 0.25
            }
            else -> {
                Log.d("FakeTowerDetector", "DIAGNOSTIC: Distance estimate appears reasonable")
            }
        }
        
        return confidenceIncrease
    }

    /**
     * Enhanced LAC/CID consistency analysis using pattern detection
     */
    private suspend fun analyzeLacCidConsistency(
        towerInfo: TowerConnectionInfo,
        location: Pair<Double, Double>?,
        signalStrength: Int?,
        reasons: MutableList<String>
    ): Pair<Double, Int> {
        if (!isInitialized() || lacCidPatternDao == null || locationHistoryDao == null) {
            Log.d("FakeTowerDetector", "DIAGNOSTIC: LAC/CID analysis skipped - DAOs not initialized")
            return Pair(0.0, 0)
        }

        try {
            towerDao?.let { towerDaoNonNull ->
                // Convert TowerConnectionInfo to TowerInfo for LAC/CID analysis
                val towerInfoForAnalysis = com.cymatune.util.TowerInfo(
                    cid = towerInfo.cid,
                    lac = towerInfo.lac,
                    mcc = towerInfo.mcc ?: 0,
                    mnc = towerInfo.mnc ?: 0,
                    firstSeen = System.currentTimeMillis(),
                    lastSeen = System.currentTimeMillis()
                )
                
                val analyzer = LacCidPatternAnalyzer(lacCidPatternDao!!, locationHistoryDao!!, towerDaoNonNull)
                val result = analyzer.analyzeTowerForLacCidAnomalies(
                    towerInfoForAnalysis,
                    location ?: Pair(0.0, 0.0),
                    signalStrength ?: -100
                )

                if (result.isAnomalous) {
                    reasons.add("LAC/CID Pattern Anomaly: ${result.anomalyTypes?.joinToString() ?: "Unknown pattern"}")
                    Log.w("FakeTowerDetector", "LAC/CID pattern anomaly detected: ${result.anomalyTypes?.joinToString()}")
                    result.detectionReasons?.forEach { reason ->
                        Log.w("FakeTowerDetector", "  LAC/CID reason: $reason")
                    }
                    
                    // Scale anomaly score to confidence increase (0-0.5 range)
                    val confidenceIncrease = (result.anomalyScore / 100.0) * 0.5
                    return Pair(confidenceIncrease, 1)
                }
            }
        } catch (e: Exception) {
            Log.e("FakeTowerDetector", "Error in LAC/CID pattern analysis", e)
        }

        return Pair(0.0, 0)
    }

/**
     * Analyze sudden LAC changes in the current area
     * Now movement-aware: suppresses jitter penalties when user is traveling (D01 fix)
     */
    private suspend fun analyzeSuddenLacChanges(
        location: Pair<Double, Double>?,
        reasons: MutableList<String>,
        isUserMoving: Boolean = false,
        movementIntensity: com.cymatune.detection.MovementDetector.MovementIntensity =
            com.cymatune.detection.MovementDetector.MovementIntensity.STATIONARY
    ): Pair<Double, Int> {
        if (!isInitialized() || lacCidPatternDao == null || locationHistoryDao == null || location == null) {
            return Pair(0.0, 0)
        }

        // D01 FIX: Suppress LAC change detection when user is traveling
        // Fast travel triggers frequent legitimate LAC changes - don't flag these
        if (isUserMoving &&
            (movementIntensity == com.cymatune.detection.MovementDetector.MovementIntensity.MOVING ||
             movementIntensity == com.cymatune.detection.MovementDetector.MovementIntensity.TRAVELING)) {
            Log.d("FakeTowerDetector", "LAC change detection suppressed: User is ${movementIntensity.name}")
            return Pair(0.0, 0)
        }

        try {
            val detector = SuddenLacChangeDetector(lacCidPatternDao!!, locationHistoryDao!!)
            val result = detector.detectSuddenLacChanges(location, System.currentTimeMillis())

            if (result.isSuspicious) {
                reasons.add("Sudden LAC Change: ${result.suspiciousEvents.size} suspicious events detected")
                Log.w("FakeTowerDetector", "Sudden LAC change anomaly detected: ${result.anomalyScore}")
                result.detectionReasons.forEach { reason ->
                    Log.w("FakeTowerDetector", "  LAC change reason: $reason")
                }

                // Scale anomaly score to confidence increase (0-0.4 range)
                val confidenceIncrease = (result.anomalyScore / 100.0) * 0.4
                return Pair(confidenceIncrease, 1)
            }
        } catch (e: Exception) {
            Log.e("FakeTowerDetector", "Error in sudden LAC change detection", e)
        }

        return Pair(0.0, 0)
    }

    /**
     * Analyze protocol handshake for the given cell
     */
    suspend fun analyzeProtocol(cell: CellInfo?, towerInfo: TowerConnectionInfo): ProtocolAnalysisResult? {
        if (cell == null) return null
        
        return try {
            val networkType = towerInfo.networkType ?: determineNetworkType(cell)
            val analyzer = ProtocolAnalyzerFactory.createAnalyzer(networkType)
            val protocolInfo = analyzer.analyzeHandshake(cell, networkType)
            
            if (protocolInfo == null) {
                return null
            }
            
            val detector = ProtocolSuspicionDetector()
            val anomalies = detector.detectProtocolAnomalies(protocolInfo)
            
            ProtocolAnalysisResult(
                protocolInfo = protocolInfo,
                anomalies = anomalies,
                suspicionScore = detector.calculateOverallSuspicionScore(anomalies)
            )
        } catch (e: Exception) {
            Log.e("FakeTowerDetector", "Protocol analysis failed", e)
            null
        }
    }

    /**
     * Determine network type from cell info
     */
    private fun determineNetworkType(cell: CellInfo): String {
        return when (cell) {
            is CellInfoGsm -> "GSM"
            is CellInfoWcdma -> "UMTS"
            is CellInfoLte -> "LTE"
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is android.telephony.CellInfoNr) {
                    "NR"
                } else {
                    "Unknown"
                }
            }
        }
    }

    data class ProtocolAnalysisResult(
        val protocolInfo: EncryptionProtocolInfo,
        val anomalies: List<ProtocolSuspicionDetector.ProtocolAnomaly>,
        val suspicionScore: Double
    )
    
    /**
     * Calculates precision radius based on observation count and location data
     * Radius decreases as more observations are collected from different locations
     */
    private fun calculatePrecisionRadius(observationCount: Int, location: Pair<Double, Double>?): Double {
        // Base radius starts at 1km and decreases with more observations
        val baseRadius = 1000.0 // meters
        
        // If we have less than 5 observations, use conservative radius
        if (observationCount < 5) {
            return baseRadius * (5.0 / observationCount) // Larger radius for fewer observations
        }
        
        // For 5+ observations, radius decreases logarithmically
        val radius = baseRadius / kotlin.math.log10(observationCount.toDouble() + 1)
        
        // Minimum radius of 50 meters for very high precision
        return radius.coerceAtLeast(50.0)
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    /**
     * Phase 4.0: Extracts tower information from cell for simplified detection
     * Now accepts subscriptionId parameter for Multi-SIM support
     */
    private fun extractTowerInfoFromCell(cell: CellInfo, subscriptionId: Int = 1, slotIndex: Int = 0): TowerConnectionInfo? {
        return when (cell) {
            is CellInfoLte -> {
                val identity = cell.cellIdentity
                val cid = identity.ci
                val lac = identity.tac

                // Extract MCC and MNC with proper fallback handling
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

                // Skip invalid MCC/MNC values
                if (mcc == 65535 || mcc == Integer.MAX_VALUE || mcc == 0) return null
                if (mnc == 65535 || mnc == Integer.MAX_VALUE || mnc == 0) return null

                TowerConnectionInfo(
                    subscriptionId = subscriptionId,
                    slotIndex = slotIndex,
                    mcc = mcc,
                    mnc = mnc,
                    lac = lac,
                    cid = cid,
                    signalStrength = cell.cellSignalStrength.dbm,
                    timingAdvance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cell.cellSignalStrength.timingAdvance
                    } else {
                        Int.MIN_VALUE
                    },
                    pci = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.pci
                    } else {
                        Int.MIN_VALUE
                    },
                    arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.earfcn
                    } else {
                        Int.MIN_VALUE
                    },
                    band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.bandwidth
                    } else {
                        Int.MIN_VALUE
                    },
                    ssRsrp = null,
                    ssRsrq = null,
                    ssSinr = null,
                    isRegistered = cell.isRegistered,
                    cellInfo = cell,
                    estimatedLocation = null,
                    networkType = "LTE",
                    additionalInfo = null
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

                if (mcc == 65535 || mcc == Integer.MAX_VALUE || mcc == 0) return null
                if (mnc == 65535 || mnc == Integer.MAX_VALUE || mnc == 0) return null

                TowerConnectionInfo(
                    subscriptionId = subscriptionId,
                    slotIndex = slotIndex,
                    mcc = mcc,
                    mnc = mnc,
                    lac = lac,
                    cid = cid,
                    signalStrength = cell.cellSignalStrength.dbm,
                    timingAdvance = Int.MIN_VALUE,
                    pci = Int.MIN_VALUE,
                    arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.arfcn
                    } else {
                        Int.MIN_VALUE
                    },
                    band = Int.MIN_VALUE,
                    ssRsrp = null,
                    ssRsrq = null,
                    ssSinr = null,
                    isRegistered = cell.isRegistered,
                    cellInfo = cell,
                    estimatedLocation = null,
                    networkType = "GSM",
                    additionalInfo = null
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

                TowerConnectionInfo(
                    subscriptionId = subscriptionId,
                    slotIndex = slotIndex,
                    mcc = mcc,
                    mnc = mnc,
                    lac = lac,
                    cid = cid,
                    signalStrength = cell.cellSignalStrength.dbm,
                    timingAdvance = Int.MIN_VALUE,
                    pci = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.psc
                    } else {
                        Int.MIN_VALUE
                    },
                    arfcn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        identity.uarfcn
                    } else {
                        Int.MIN_VALUE
                    },
                    band = Int.MIN_VALUE,
                    ssRsrp = null,
                    ssRsrq = null,
                    ssSinr = null,
                    isRegistered = cell.isRegistered,
                    cellInfo = cell,
                    estimatedLocation = null,
                    networkType = "WCDMA",
                    additionalInfo = null
                )
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cell is CellInfoNr) {
                    extractNrTowerInfo(cell, subscriptionId, slotIndex)
                } else {
                    null
                }
            }
        }
    }

    /**
     * Phase 4.0: Extract tower information from 5G NR cell
     * Now accepts subscriptionId parameter for Multi-SIM support
     */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun extractNrTowerInfo(
        cell: android.telephony.CellInfoNr,
        subscriptionId: Int = 1,
        slotIndex: Int = 0
    ): TowerConnectionInfo? {
        // Simplified NR tower extraction - just return basic info
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val identity = cell.cellIdentity as android.telephony.CellIdentityNr
            val cid = identity.nci
            val tac = identity.tac

            val mcc = identity.mccString?.toIntOrNull() ?: 0
            val mnc = identity.mncString?.toIntOrNull() ?: 0

            if (mcc == 65535 || mcc == Integer.MAX_VALUE || mcc == 0) return null
            if (mnc == 65535 || mnc == Integer.MAX_VALUE || mnc == 0) return null

            return TowerConnectionInfo(
                subscriptionId = subscriptionId,
                slotIndex = slotIndex,
                mcc = mcc,
                mnc = mnc,
                lac = tac,
                cid = cid.toInt(),
                signalStrength = cell.cellSignalStrength.dbm,
                timingAdvance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // CellSignalStrengthNr doesn't have timingAdvance property, use default
                    Int.MIN_VALUE
                } else {
                    Int.MIN_VALUE
                },
                pci = identity.pci,
                arfcn = identity.nrarfcn,
                band = Int.MIN_VALUE,
                ssRsrp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (cell.cellSignalStrength as android.telephony.CellSignalStrengthNr).csiRsrp
                } else {
                    null
                },
                ssRsrq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (cell.cellSignalStrength as android.telephony.CellSignalStrengthNr).csiRsrq
                } else {
                    null
                },
                ssSinr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (cell.cellSignalStrength as android.telephony.CellSignalStrengthNr).csiSinr
                } else {
                    null
                },
                isRegistered = cell.isRegistered,
                cellInfo = cell,
                estimatedLocation = null,
                networkType = "NR",
                additionalInfo = null
            )
        } else {
            return null
        }
    }
}


