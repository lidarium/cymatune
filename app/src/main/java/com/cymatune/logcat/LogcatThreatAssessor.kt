package com.cymatune.logcat

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Analyzes RIL events and calculates threat scores based on logcat data.
 * This is the main integration point between logcat parsing and the detection system.
 * 
 * POST-SILENT-SMS WATCH MODE:
 * After detecting a silent SMS, the assessor enters a heightened monitoring state
 * for 60 seconds. Any suspicious events during this window are escalated to CRITICAL
 * and correlated as potential attack patterns.
 */
class LogcatThreatAssessor {
    
    companion object {
        private const val TAG = "LogcatThreatAssessor"
        
        // Threat weights
        private const val ENCRYPTION_NONE_WEIGHT = 0.95f     // No encryption = almost certain threat
        private const val ENCRYPTION_DOWNGRADE_WEIGHT = 0.7f // Downgrade = high threat
        private const val SILENT_SMS_TYPE0_WEIGHT = 0.85f    // Type 0 SMS = tracking
        private const val SILENT_SMS_OTHER_WEIGHT = 0.5f     // Other silent SMS
        private const val IMSI_REQUEST_WEIGHT = 0.9f         // IMSI grab = very high threat
        private const val IMEI_REQUEST_WEIGHT = 0.6f         // IMEI request = medium
        private const val AUTH_FAIL_WEIGHT = 0.5f            // Auth failure = suspicious
        private const val FORCED_HANDOVER_WEIGHT = 0.65f     // Forced handover = suspicious
        
        // Time windows for correlation (ms)
        private const val EVENT_CORRELATION_WINDOW = 30_000L // 30 seconds
        private const val THREAT_DECAY_TIME = 300_000L       // 5 minutes
        
        // POST-SILENT-SMS WATCH MODE
        private const val POST_SMS_WATCH_WINDOW = 60_000L    // 60 seconds heightened monitoring
    }
    
    // Recent events for correlation analysis
    private val recentEncryptionEvents = mutableListOf<RilEvent.EncryptionEvent>()
    private val recentSilentSmsEvents = mutableListOf<RilEvent.SilentSmsEvent>()
    private val recentIdentityEvents = mutableListOf<RilEvent.IdentityRequestEvent>()
    private val recentHandoverEvents = mutableListOf<RilEvent.HandoverEvent>()
    private val recentAuthEvents = mutableListOf<RilEvent.AuthenticationEvent>()
    
    // Post-Silent-SMS Watch Mode tracking
    private var lastSilentSmsTimestamp: Long = 0L
    private var watchModeActive: Boolean = false
    private var watchModeTriggerType: String = ""
    
    // Attack Chain Tracking (Timestamps)
    private var lastDowngradeTimestamp: Long = 0L
    private var lastAuthFailureTimestamp: Long = 0L
    private var lastPagingAbuseTimestamp: Long = 0L
    
    // Current threat assessment
    private var currentAssessment = LogcatThreatScore()
    
    // Flow for observers
    private val _threatFlow = MutableSharedFlow<LogcatThreatScore>(replay = 1)
    val threatFlow: Flow<LogcatThreatScore> = _threatFlow.asSharedFlow()
    
    // Flow for individual threat alerts
    private val _alertFlow = MutableSharedFlow<ThreatAlert>(replay = 10)
    val alertFlow: Flow<ThreatAlert> = _alertFlow.asSharedFlow()
    
    // Maintenance mode state (for classifying system noise correctly)
    private var maintenanceExpiry: Long = 0L
    private var maintenanceReason: String = ""
    
    /**
     * Set maintenance mode for a specific duration.
     * Events during this period are classified as system logs (LOW severity) rather than attacks.
     */
    fun setMaintenanceMode(durationMs: Long, reason: String) {
        maintenanceExpiry = System.currentTimeMillis() + durationMs
        maintenanceReason = reason
        Log.i(TAG, "🔧 Maintenance Mode ENABLED for ${durationMs}ms: $reason")
    }
    
    private fun isMaintenanceMode(now: Long): Boolean {
        return now < maintenanceExpiry
    }
    
    /**
     * Process a parsed RIL event and update threat assessment
     */
    suspend fun processEvent(event: RilEvent) {
        val now = System.currentTimeMillis()
        
        // Clean old events first
        cleanOldEvents(now)
        
        when (event) {
// ... (omitted for brevity, assume lines 93-114 match existing)


            is RilEvent.EncryptionEvent -> processEncryptionEvent(event, now)
            is RilEvent.SilentSmsEvent -> processSilentSmsEvent(event, now)
            is RilEvent.IdentityRequestEvent -> processIdentityEvent(event, now)
            is RilEvent.HandoverEvent -> processHandoverEvent(event, now)
            is RilEvent.AuthenticationEvent -> processAuthEvent(event, now)
            // Radio attack events
            is RilEvent.CallStateEvent -> processCallStateEvent(event, now)
            is RilEvent.BasebandCrashEvent -> processBasebandCrashEvent(event, now)
            is RilEvent.ImsAnomalyEvent -> processImsAnomalyEvent(event, now)
            is RilEvent.SmsInjectionEvent -> processSmsInjectionEvent(event, now)
            is RilEvent.SignalJammingEvent -> processSignalJammingEvent(event, now)
            is RilEvent.Ss7IndicatorEvent -> processSs7IndicatorEvent(event, now)
            is RilEvent.RogueBtsEvent -> processRogueBtsEvent(event, now)
            // Advanced radio attack events
            is RilEvent.FakeEmergencyAlertEvent -> processFakeEmergencyAlertEvent(event, now)
            is RilEvent.NetworkDowngradeEvent -> processNetworkDowngradeEvent(event, now)
            is RilEvent.WifiCallingAnomalyEvent -> processWifiCallingAnomalyEvent(event, now)
            is RilEvent.TmsiExposureEvent -> processTmsiExposureEvent(event, now)
            is RilEvent.PagingAbuseEvent -> processPagingAbuseEvent(event, now)
            is RilEvent.CellManipulationEvent -> processCellManipulationEvent(event, now)
            is RilEvent.NetworkRedirectEvent -> processNetworkRedirectEvent(event, now)
            is RilEvent.VoLteDoSEvent -> processVoLteDoSEvent(event, now)
            is RilEvent.InvalidPlmnEvent -> processInvalidPlmnEvent(event, now)
            is RilEvent.StingrayPatternEvent -> processStingrayPatternEvent(event, now)
            // Post-attack behavior events
            is RilEvent.ContentAccessEvent -> processContentAccessEvent(event, now)
            is RilEvent.AppLaunchEvent -> processAppLaunchEvent(event, now)
            is RilEvent.NetworkActivityEvent -> processNetworkActivityEvent(event, now)
            is RilEvent.SettingsChangeEvent -> processSettingsChangeEvent(event, now)
            is RilEvent.LocationUpdateEvent -> processLocationUpdateEvent(event, now)
            is RilEvent.PrivacyLeakEvent -> processPrivacyLeakEvent(event, now)
            is RilEvent.SensorAbuseEvent -> processSensorAbuseEvent(event, now)
            else -> { /* Other events don't directly affect threat score */ }
        }
        
        // Recalculate and emit
        recalculateThreatScore(now)
        _threatFlow.emit(currentAssessment)
    }
    
    private suspend fun processEncryptionEvent(event: RilEvent.EncryptionEvent, now: Long) {
        recentEncryptionEvents.add(event)
        
        // Immediate alert for no encryption
        if (event.isNoEncryption) {
            // CHECK FOR ATTACK CHAIN: Downgrade -> No Encryption = Confirmed MitM
            if (now - lastDowngradeTimestamp < 60_000) {
                 Log.e(TAG, "🚨 CONFIRMED MITM: Network downgrade followed by disabled encryption!")
                 _alertFlow.emit(ThreatAlert(
                    type = ThreatType.MITM_ATTACK,
                    severity = ThreatSeverity.CRITICAL,
                    message = "🚨 CONFIRMED MITM ATTACK: Network downgraded to 2G and encryption disabled! Immediate danger.",
                    cid = event.cid,
                    timestamp = now,
                    confidence = 1.0f
                ))
            }
            
            Log.w(TAG, "⚠️ NO ENCRYPTION DETECTED: ${event.cipherType}")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.ENCRYPTION_DISABLED,
                severity = ThreatSeverity.CRITICAL,
                message = "Network connection has NO encryption (${event.cipherType.description})",
                cid = event.cid,
                timestamp = now,
                confidence = 0.95f
            ))
        }
        // Alert for downgrade
        else if (event.isDowngrade) {
            Log.w(TAG, "⚠️ ENCRYPTION DOWNGRADE: ${event.previousCipher} → ${event.cipherType}")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.ENCRYPTION_DOWNGRADE,
                severity = ThreatSeverity.HIGH,
                message = "Encryption downgraded: ${event.previousCipher?.description} → ${event.cipherType.description}",
                cid = event.cid,
                timestamp = now,
                confidence = 0.7f
            ))
        }
    }
    
    private suspend fun processSilentSmsEvent(event: RilEvent.SilentSmsEvent, now: Long) {
        recentSilentSmsEvents.add(event)
        
        // ACTIVATE POST-SILENT-SMS WATCH MODE
        lastSilentSmsTimestamp = now
        watchModeActive = true
        watchModeTriggerType = event.smsType.description
        Log.w(TAG, "🔴 WATCH MODE ACTIVATED: Monitoring for ${POST_SMS_WATCH_WINDOW/1000}s after ${event.smsType}")
        
        val severity = if (event.smsType == SilentSmsType.TYPE_0) {
            ThreatSeverity.HIGH
        } else {
            ThreatSeverity.MEDIUM
        }
        
        Log.w(TAG, "⚠️ SILENT SMS DETECTED: ${event.smsType}")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.SILENT_SMS,
            severity = severity,
            message = "Silent SMS received: ${event.smsType.description} - Watch mode activated for 60s",
            cid = null,
            timestamp = now,
            confidence = if (event.smsType == SilentSmsType.TYPE_0) 0.95f else 0.7f
        ))
    }
    
    /**
     * Check if we're in post-silent-SMS watch mode
     */
    private fun isInWatchMode(now: Long): Boolean {
        if (!watchModeActive) return false
        if (now - lastSilentSmsTimestamp > POST_SMS_WATCH_WINDOW) {
            watchModeActive = false
            Log.i(TAG, "🟢 WATCH MODE ENDED: No attack pattern detected")
            return false
        }
        return true
    }
    
    private suspend fun processIdentityEvent(event: RilEvent.IdentityRequestEvent, now: Long) {
        recentIdentityEvents.add(event)
        
        val inWatchMode = isInWatchMode(now)
        val timeSinceSms = if (inWatchMode) (now - lastSilentSmsTimestamp) / 1000 else 0
        
        if (event.identityType == IdentityType.IMSI) {
            // Escalate to attack pattern if in watch mode
            if (inWatchMode) {
                Log.e(TAG, "🚨 ATTACK PATTERN DETECTED: IMSI request ${timeSinceSms}s after Silent SMS!")
                _alertFlow.emit(ThreatAlert(
                    type = ThreatType.IMSI_CATCHER,
                    severity = ThreatSeverity.CRITICAL,
                    message = "🚨 ATTACK PATTERN: IMSI grab ${timeSinceSms}s after $watchModeTriggerType!",
                    cid = event.cid,
                    timestamp = now,
                    confidence = 0.98f  // Very high confidence when correlated
                ))
            } else {
                Log.w(TAG, "⚠️ IMSI REQUEST DETECTED - Possible IMSI catcher!")
                _alertFlow.emit(ThreatAlert(
                    type = ThreatType.IMSI_CATCHER,
                    severity = ThreatSeverity.CRITICAL,
                    message = "IMSI Identity Request detected - possible IMSI catcher!",
                    cid = event.cid,
                    timestamp = now,
                    confidence = 0.95f
                ))
            }
        } else if (event.identityType == IdentityType.IMEI) {
            val severity = if (inWatchMode) ThreatSeverity.HIGH else ThreatSeverity.MEDIUM
            val message = if (inWatchMode) {
                "⚠️ IMEI request ${timeSinceSms}s after $watchModeTriggerType - tracking attempt"
            } else {
                "IMEI Identity Request detected - device tracking possible"
            }
            Log.w(TAG, "⚠️ IMEI REQUEST DETECTED")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.IDENTITY_REQUEST,
                severity = severity,
                message = message,
                cid = event.cid,
                timestamp = now,
                confidence = if (inWatchMode) 0.8f else 0.6f
            ))
        }
    }
    
    private suspend fun processHandoverEvent(event: RilEvent.HandoverEvent, now: Long) {
        recentHandoverEvents.add(event)
        
        val inWatchMode = isInWatchMode(now)
        val timeSinceSms = if (inWatchMode) (now - lastSilentSmsTimestamp) / 1000 else 0
        
        // Check for suspicious handover patterns
        val recentCount = recentHandoverEvents.count { 
            now - it.timestamp < 60_000 // Last minute
        }
        
        // Handover during watch mode is highly suspicious - likely forced
        if (inWatchMode) {
            Log.e(TAG, "🚨 SUSPICIOUS HANDOVER ${timeSinceSms}s after Silent SMS!")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.FORCED_HANDOVER,
                severity = ThreatSeverity.CRITICAL,
                message = "🚨 ATTACK PATTERN: Cell change ${timeSinceSms}s after $watchModeTriggerType - forced handover!",
                cid = event.targetCid,
                timestamp = now,
                confidence = 0.9f
            ))
            return
        }
        
        // Rapid handovers are suspicious
        if (recentCount >= 3) {
            Log.w(TAG, "⚠️ RAPID HANDOVERS DETECTED: $recentCount in 1 minute")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.FORCED_HANDOVER,
                severity = ThreatSeverity.MEDIUM,
                message = "Rapid cell handovers detected ($recentCount in 1 minute)",
                cid = event.targetCid,
                timestamp = now,
                confidence = 0.5f + (recentCount * 0.1f).coerceAtMost(0.3f)
            ))
        }
        
        // Inter-RAT handover (LTE → 2G) is suspicious
        if (event.handoverType == HandoverType.INTER_RAT) {
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.NETWORK_DOWNGRADE,
                severity = ThreatSeverity.HIGH,
                message = "Forced network technology change detected",
                cid = event.targetCid,
                timestamp = now,
                confidence = 0.7f
            ))
        }
    }
    
    private fun processAuthEvent(event: RilEvent.AuthenticationEvent, now: Long) {
        recentAuthEvents.add(event)
        
        if (!event.success) {
            lastAuthFailureTimestamp = now // Track for correlation
            Log.w(TAG, "⚠️ AUTH FAILURE DETECTED")
            // Auth failures are logged but don't trigger immediate alerts
            // (they could be normal in some scenarios)
        }
    }
    
    // ==================== RADIO ATTACK PROCESSING ====================
    
    private suspend fun processCallStateEvent(event: RilEvent.CallStateEvent, now: Long) {
        val inWatchMode = isInWatchMode(now)
        
        // Check for call with weak/no encryption
        // Check for call with weak/no encryption
        if (event.callState == CallState.ACTIVE && event.cipherAtCallStart != null) {
            val cipher = event.cipherAtCallStart
            // Only alert if strength is explicitly weak (0..2)
            // Ignore UNKNOWN (-1) to prevent false positives when cipher cannot be determined
            if (cipher.strength in 0..2 && cipher != CipherType.UNKNOWN) { 
                Log.e(TAG, "🚨 CALL WITH WEAK ENCRYPTION: ${cipher.description}")
                _alertFlow.emit(ThreatAlert(
                    type = ThreatType.CALL_INTERCEPTION,
                    severity = ThreatSeverity.CRITICAL,
                    message = "🚨 Call active with weak encryption: ${cipher.description} - possible interception!",
                    cid = event.cid,
                    timestamp = now,
                    confidence = 0.9f
                ))
            }
        }
    }
    
    private suspend fun processBasebandCrashEvent(event: RilEvent.BasebandCrashEvent, now: Long) {
        val inWatchMode = isInWatchMode(now)
        val timeSinceSms = if (inWatchMode) (now - lastSilentSmsTimestamp) / 1000 else 0
        
        val severity = if (inWatchMode) ThreatSeverity.CRITICAL else ThreatSeverity.HIGH
        val message = if (inWatchMode) {
            "🚨 ATTACK: Baseband crash ${timeSinceSms}s after $watchModeTriggerType - possible exploit!"
        } else {
            "⚠️ Baseband crash detected: ${event.crashType} - possible exploit attempt"
        }
        
        Log.e(TAG, message)
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.BASEBAND_EXPLOIT,
            severity = severity,
            message = message,
            cid = null,
            timestamp = now,
            confidence = if (inWatchMode) 0.9f else 0.7f
        ))
    }
    
    private suspend fun processImsAnomalyEvent(event: RilEvent.ImsAnomalyEvent, now: Long) {
        val inWatchMode = isInWatchMode(now)
        
        // SRTP disabled/downgrade is always critical
        if (event.anomalyType == ImsAnomalyType.SRTP_DISABLED || 
            event.anomalyType == ImsAnomalyType.SRTP_DOWNGRADE) {
            Log.e(TAG, "🚨 VOICE ENCRYPTION DISABLED/STRIPPED!")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.VOLTE_INTERCEPTION,
                severity = ThreatSeverity.CRITICAL,
                message = "🚨 VoLTE voice encryption ${event.anomalyType.name.lowercase()} - calls can be intercepted!",
                cid = null,
                timestamp = now,
                confidence = 0.95f
            ))
        } else {
            Log.w(TAG, "⚠️ IMS anomaly: ${event.anomalyType}")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.IMS_ANOMALY,
                severity = ThreatSeverity.MEDIUM,
                message = "IMS/VoLTE anomaly: ${event.anomalyType.name} - ${event.details ?: ""}",
                cid = null,
                timestamp = now,
                confidence = 0.6f
            ))
        }
    }
    
    private suspend fun processSmsInjectionEvent(event: RilEvent.SmsInjectionEvent, now: Long) {
        
        // ENHANCED: Adjust severity based on Silent SMS cross-verification
        val severity: ThreatSeverity
        val confidence: Float
        val message: String
        
        if (event.injectionType == SmsInjectionType.MALFORMED_PDU) {
            if (event.isSilentSms) {
                // Malformed SILENT SMS = Very suspicious! Likely attack!
                severity = ThreatSeverity.CRITICAL
                confidence = 0.95f
                message = "🚨 CRITICAL: Malformed Silent SMS (Type ${event.tpPid ?: 0}) detected - possible IMSI catcher attack!"
                
                Log.e(TAG, "🚨 MALFORMED SILENT SMS DETECTED - HIGH THREAT! TP-PID=${event.tpPid}")
            } else {
                // Malformed regular SMS = Likely benign network corruption
                severity = ThreatSeverity.LOW
                confidence = 0.3f
                message = "⚠️ Malformed SMS from ${event.senderAddress ?: "unknown"} - likely network corruption"
                
                Log.w(TAG, "Malformed regular SMS - likely benign (sender: ${event.senderAddress})")
            }
        } else {
            // Other injection types (not malformed PDU)
            severity = ThreatSeverity.HIGH
            confidence = 0.75f
            message = "⚠️ Suspicious SMS: ${event.injectionType.name} from ${event.senderAddress ?: "unknown"}"
            
            Log.e(TAG, "🚨 SMS INJECTION DETECTED: ${event.injectionType}")
        }
        
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.SMS_INJECTION,
            severity = severity,
            message = message,
            cid = null,
            timestamp = now,
            confidence = confidence
        ))
    }
    
    private suspend fun processSignalJammingEvent(event: RilEvent.SignalJammingEvent, now: Long) {
        val inWatchMode = isInWatchMode(now)
        
        // Only alert on signal jamming if:
        // 1. We're in watch mode (after silent SMS) - correlation increases confidence
        // 2. OR signal drop is extremely severe (>50dB sudden drop)
        // Normal out-of-service states should NOT trigger without correlation
        val shouldAlert = when {
            inWatchMode && event.hasServiceLoss -> true
            inWatchMode && event.signalDrop > 30 -> true
            event.signalDrop > 50 -> true  // Very severe - likely intentional
            else -> false
        }
        
        // CHECK FOR ATTACK CHAIN: Paging Abuse -> Service Loss (DoS)
        if (event.hasServiceLoss && now - lastPagingAbuseTimestamp < 60_000) {
             Log.e(TAG, "🚨 DOS ATTACK: Paging flood caused service loss!")
             _alertFlow.emit(ThreatAlert(
                type = ThreatType.DOS_ATTACK,
                severity = ThreatSeverity.CRITICAL,
                message = "🚨 DoS ATTACK CONFIRMED: Service loss immediately followed paging flood!",
                cid = null,
                timestamp = now,
                confidence = 0.95f
            ))
        }
        
        if (shouldAlert) {
            Log.w(TAG, "⚠️ SIGNAL JAMMING POSSIBLE: ${event.signalDrop}dB drop (watch mode: $inWatchMode)")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.SIGNAL_JAMMING,
                severity = if (inWatchMode && event.hasServiceLoss) ThreatSeverity.HIGH else ThreatSeverity.MEDIUM,
                message = "⚠️ Possible signal jamming: ${event.signalDrop}dB drop${if (event.hasServiceLoss) " - service lost!" else ""}",
                cid = null,
                timestamp = now,
                confidence = if (inWatchMode) 0.75f else 0.5f
            ))
        }
    }
    
    private suspend fun processSs7IndicatorEvent(event: RilEvent.Ss7IndicatorEvent, now: Long) {
        Log.e(TAG, "🚨 SS7 ATTACK INDICATOR: ${event.indicatorType}${event.forwardingNumber?.let { " to: $it" } ?: ""}")
        
        // Build enhanced message for call forwarding attacks
        val message = when (event.indicatorType) {
            Ss7IndicatorType.CALL_FORWARD_ACTIVATED -> {
                val numberInfo = event.forwardingNumber?.let { " to: $it" } ?: " (number unknown)"
                "🚨 SS7 attack indicator: Call forwarding activated$numberInfo"
            }
            else -> "🚨 SS7 attack indicator: ${event.indicatorType.name}${event.relatedCause?.let { " (cause: $it)" } ?: ""}"
        }
        
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.SS7_ATTACK,
            severity = if (event.indicatorType.severity >= 4) ThreatSeverity.CRITICAL else ThreatSeverity.HIGH,
            message = message,
            cid = event.cid,
            timestamp = now,
            confidence = 0.8f
        ))
    }
    
    private suspend fun processRogueBtsEvent(event: RilEvent.RogueBtsEvent, now: Long) {
        Log.e(TAG, "🚨 ROGUE BTS INDICATOR: ${event.suspicionType}")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.ROGUE_BTS,
            severity = if (event.suspicionType.severity >= 4) ThreatSeverity.CRITICAL else ThreatSeverity.HIGH,
            message = "🚨 Rogue tower indicator: ${event.suspicionType.name} CID: ${event.cid ?: "unknown"}${event.details?.let { " - $it" } ?: ""}",
            cid = event.cid,
            timestamp = now,
            confidence = 0.75f
        ))
    }
    
    // ==================== ADVANCED RADIO ATTACK PROCESSING ====================
    
    private suspend fun processFakeEmergencyAlertEvent(event: RilEvent.FakeEmergencyAlertEvent, now: Long) {
        // Only alert if alert is flagged as suspicious
        if (!event.isSuspicious) return
        
        Log.e(TAG, "🚨 FAKE EMERGENCY ALERT: ${event.alertType} - ${event.suspicionReason}")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.FAKE_EMERGENCY_ALERT,
            severity = ThreatSeverity.CRITICAL,
            message = "🚨 Suspicious emergency alert: ${event.alertType.name} - ${event.suspicionReason ?: "anomaly detected"}",
            cid = event.cellId,
            timestamp = now,
            confidence = 0.85f
        ))
    }
    
    private suspend fun processNetworkDowngradeEvent(event: RilEvent.NetworkDowngradeEvent, now: Long) {
        val inWatchMode = isInWatchMode(now)
        
        // Only alert if forced downgrade OR in watch mode
        if (!event.isForcedDowngrade && !inWatchMode) return
        
        val severity = when {
            event.toNetwork.contains("GSM", ignoreCase = true) -> ThreatSeverity.CRITICAL
            event.toNetwork.contains("UMTS", ignoreCase = true) -> ThreatSeverity.HIGH
            inWatchMode -> ThreatSeverity.HIGH
            else -> ThreatSeverity.MEDIUM
        }
        
        // Track for attack chain correlation
        lastDowngradeTimestamp = now
        
        // CHECK FOR ATTACK CHAIN: Auth Failure -> Downgrade
        if (now - lastAuthFailureTimestamp < 60_000) {
            Log.e(TAG, "🚨 DOWNGRADE ATTACK VIA AUTH DENIAL")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.DOWNGRADE_ATTACK,
                severity = ThreatSeverity.CRITICAL,
                message = "🚨 ATTACK CHAIN: Network downgrade forced via authentication denial!",
                cid = event.cid,
                timestamp = now,
                confidence = 0.95f
            ))
        }
        
        Log.e(TAG, "🚨 NETWORK DOWNGRADE: ${event.fromNetwork} → ${event.toNetwork}")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.NETWORK_DOWNGRADE,
            severity = severity,
            message = "🚨 Network downgrade: ${event.fromNetwork} → ${event.toNetwork}${if (event.isForcedDowngrade) " (FORCED)" else ""}",
            cid = event.cid,
            timestamp = now,
            confidence = if (event.isForcedDowngrade) 0.9f else 0.6f
        ))
    }
    
    private suspend fun processWifiCallingAnomalyEvent(event: RilEvent.WifiCallingAnomalyEvent, now: Long) {
        // All WiFi calling anomalies are concerning
        val severity = if (event.anomalyType.severity >= 4) ThreatSeverity.CRITICAL else ThreatSeverity.HIGH
        
        Log.e(TAG, "🚨 WIFI CALLING ANOMALY: ${event.anomalyType}")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.WIFI_CALLING_ATTACK,
            severity = severity,
            message = "🚨 WiFi calling security issue: ${event.anomalyType.name}${event.ePdgAddress?.let { " (ePDG: $it)" } ?: ""}",
            cid = null,
            timestamp = now,
            confidence = 0.8f
        ))
    }
    
    private suspend fun processTmsiExposureEvent(event: RilEvent.TmsiExposureEvent, now: Long) {
        val severity = if (event.exposureType.severity >= 4) ThreatSeverity.CRITICAL else ThreatSeverity.HIGH
        
        Log.e(TAG, "🚨 IDENTITY EXPOSURE: ${event.exposureType}")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.IDENTITY_EXPOSURE,
            severity = severity,
            message = "🚨 Identity tracking: ${event.exposureType.name}${if (event.isNullTmsi) " (no TMSI protection)" else ""}",
            cid = event.cid,
            timestamp = now,
            confidence = 0.85f
        ))
    }
    
    private suspend fun processPagingAbuseEvent(event: RilEvent.PagingAbuseEvent, now: Long) {
        // Only process if abnormal rate detected
        if (!event.isAbnormalRate) return
        
        lastPagingAbuseTimestamp = now // Track for DoS correlation
        
        Log.e(TAG, "🚨 PAGING ABUSE: ${event.pagingCount} pages in ${event.timeWindowMs}ms")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.PAGING_ATTACK,
            severity = ThreatSeverity.HIGH,
            message = "🚨 Paging channel abuse: ${event.pagingCount} paging requests detected - possible tracking",
            cid = event.cellId,
            timestamp = now,
            confidence = 0.75f
        ))
    }
    
    private suspend fun processCellManipulationEvent(event: RilEvent.CellManipulationEvent, now: Long) {
        // Only alert if flagged as suspicious
        if (!event.isSuspicious) return
        
        val inWatchMode = isInWatchMode(now)
        val severity = if (inWatchMode) ThreatSeverity.CRITICAL else ThreatSeverity.HIGH
        
        Log.e(TAG, "🚨 CELL MANIPULATION: ${event.manipulationType}")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.CELL_MANIPULATION,
            severity = severity,
            message = "🚨 Cell manipulation: ${event.manipulationType.name}${event.signalDelta?.let { " (signal delta: ${it}dB)" } ?: ""}",
            cid = event.toCid,
            timestamp = now,
            confidence = if (inWatchMode) 0.9f else 0.7f
        ))
    }
    
    private suspend fun processNetworkRedirectEvent(event: RilEvent.NetworkRedirectEvent, now: Long) {
        // Only alert if suspicious (high severity redirect)
        if (!event.isSuspicious) return
        
        Log.e(TAG, "🚨 NETWORK REDIRECT ATTACK: ${event.redirectType}")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.NETWORK_REDIRECT,
            severity = if (event.redirectType.severity >= 5) ThreatSeverity.CRITICAL else ThreatSeverity.HIGH,
            message = "🚨 Network redirect attack: ${event.originalRat ?: "?"} → ${event.targetRat ?: "?"} (${event.redirectType.name})",
            cid = null,
            timestamp = now,
            confidence = 0.85f
        ))
    }
    
    private suspend fun processVoLteDoSEvent(event: RilEvent.VoLteDoSEvent, now: Long) {
        // Check for Maintenance Mode (Airplane Toggle, etc.)
        if (isMaintenanceMode(now)) {
            Log.i(TAG, "ℹ️ VO-LTE EVENT (MAINTENANCE): $maintenanceReason - ${event.errorCount} errors (Normal behavior)")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.VOLTE_DOS,
                severity = ThreatSeverity.LOW, // Classified as Log/Info
                message = "System Event: ${maintenanceReason} - ${event.errorCount} modem errors (Expected)",
                cid = null,
                timestamp = now,
                confidence = 0.0f // Zero confidence in "Attack", but logged for visibility
            ))
            return
        }

        Log.e(TAG, "🚨 VOLTE DOS: ${event.dosType} (${event.errorCount} errors)")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.VOLTE_DOS,
            severity = ThreatSeverity.HIGH,
            message = "🚨 VoLTE DoS attack: ${event.dosType.name} - ${event.errorCount} errors in ${event.timeWindowMs/1000}s",
            cid = null,
            timestamp = now,
            confidence = 0.8f
        ))
    }
    
    private suspend fun processInvalidPlmnEvent(event: RilEvent.InvalidPlmnEvent, now: Long) {
        // Only alert if flagged as suspicious
        if (!event.isSuspicious) return
        
        Log.e(TAG, "🚨 INVALID PLMN: ${event.plmnId} - ${event.rejectionType}")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.INVALID_NETWORK,
            severity = if (event.rejectionType.severity >= 5) ThreatSeverity.CRITICAL else ThreatSeverity.HIGH,
            message = "🚨 Suspicious network: ${event.plmnId} (${event.rejectionType.name})",
            cid = null,
            timestamp = now,
            confidence = 0.75f
        ))
    }
    
    private suspend fun processStingrayPatternEvent(event: RilEvent.StingrayPatternEvent, now: Long) {
        Log.e(TAG, "🚨 STINGRAY PATTERN DETECTED: ${event.patternType} (confidence: ${event.confidenceScore})")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.STINGRAY_PATTERN,
            severity = ThreatSeverity.CRITICAL,
            message = "🚨 STINGRAY DETECTED: ${event.patternType.name} - ${event.correlatedEvents} correlated events${event.patternDetails?.let { " - $it" } ?: ""}",
            cid = event.cid,
            timestamp = now,
            confidence = event.confidenceScore
        ))
    }

    // ==================== POST-ATTACK BEHAVIOR PROCESSING ====================
    
    private suspend fun processContentAccessEvent(event: RilEvent.ContentAccessEvent, now: Long) {
        val inWatchMode = isInWatchMode(now)
        val timeSinceSms = if (inWatchMode) (now - lastSilentSmsTimestamp) / 1000 else 0
        
        // Only alert in watch mode - otherwise normal app behavior
        if (inWatchMode) {
            val contentName = event.contentType.name.lowercase().replace("_", " ")
            Log.e(TAG, "🚨 DATA SCRAPING DETECTED: ${event.contentType} access ${timeSinceSms}s after Silent SMS!")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.MALWARE_BEHAVIOR,
                severity = ThreatSeverity.CRITICAL,
                message = "🚨 ATTACK PATTERN: $contentName ${event.operation} ${timeSinceSms}s after $watchModeTriggerType! Package: ${event.callingPackage ?: "unknown"}",
                cid = null,
                timestamp = now,
                confidence = 0.9f
            ))
        }
    }
    
    private suspend fun processAppLaunchEvent(event: RilEvent.AppLaunchEvent, now: Long) {
        val inWatchMode = isInWatchMode(now)
        val timeSinceSms = if (inWatchMode) (now - lastSilentSmsTimestamp) / 1000 else 0
        
        // Alert in watch mode for new app activity
        if (inWatchMode) {
            Log.w(TAG, "⚠️ APP LAUNCH during watch mode: ${event.packageName}")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.MALWARE_BEHAVIOR,
                severity = ThreatSeverity.HIGH,
                message = "⚠️ App started ${timeSinceSms}s after $watchModeTriggerType: ${event.packageName}",
                cid = null,
                timestamp = now,
                confidence = 0.6f
            ))
        }
    }
    
    private suspend fun processNetworkActivityEvent(event: RilEvent.NetworkActivityEvent, now: Long) {
        val inWatchMode = isInWatchMode(now)
        val timeSinceSms = if (inWatchMode) (now - lastSilentSmsTimestamp) / 1000 else 0
        
        // Network activity during watch mode is suspicious - possible exfiltration
        if (inWatchMode && event.direction == "outbound") {
            val bytesStr = event.bytes?.let { " (${it} bytes)" } ?: ""
            Log.e(TAG, "🚨 NETWORK ACTIVITY during watch mode: ${event.host}:${event.port}$bytesStr")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.DATA_EXFILTRATION,
                severity = ThreatSeverity.CRITICAL,
                message = "🚨 ATTACK PATTERN: Outbound connection to ${event.host ?: "unknown"} ${timeSinceSms}s after $watchModeTriggerType$bytesStr",
                cid = null,
                timestamp = now,
                confidence = 0.85f
            ))
        }
    }
    
    private suspend fun processSettingsChangeEvent(event: RilEvent.SettingsChangeEvent, now: Long) {
        val inWatchMode = isInWatchMode(now)
        val timeSinceSms = if (inWatchMode) (now - lastSilentSmsTimestamp) / 1000 else 0
        
        // Only alert for truly suspicious settings changes:
        // 1. Any settings change in watch mode (after silent SMS)
        // 2. Only the most critical settings outside watch mode (APN, VPN - level 4+)
        if (!inWatchMode && event.settingType.suspicionLevel < 4) {
            // Not in watch mode and not a critical setting - ignore to prevent false positives
            return
        }
        
        val severity = if (inWatchMode) ThreatSeverity.CRITICAL else ThreatSeverity.HIGH
        val settingName = event.settingType.name
        val message = if (inWatchMode) {
            "🚨 ATTACK PATTERN: $settingName changed ${timeSinceSms}s after $watchModeTriggerType! Value: ${event.newValue ?: "unknown"}"
        } else {
            "⚠️ Critical setting change: $settingName → ${event.newValue ?: "unknown"}"
        }
        
        Log.e(TAG, message)
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.SETTINGS_HIJACK,
            severity = severity,
            message = message,
            cid = null,
            timestamp = now,
            confidence = if (inWatchMode) 0.95f else 0.7f
        ))
    }
    
    private suspend fun processLocationUpdateEvent(event: RilEvent.LocationUpdateEvent, now: Long) {
        val inWatchMode = isInWatchMode(now)
        val timeSinceSms = if (inWatchMode) (now - lastSilentSmsTimestamp) / 1000 else 0
        
        // Location access during watch mode is tracking
        if (inWatchMode) {
            val coordsStr = if (event.latitude != null && event.longitude != null) {
                " at (${event.latitude}, ${event.longitude})"
            } else ""
            
            Log.e(TAG, "🚨 LOCATION TRACKING: Location access ${timeSinceSms}s after Silent SMS!$coordsStr")
            _alertFlow.emit(ThreatAlert(
                type = ThreatType.LOCATION_TRACKING,
                severity = ThreatSeverity.CRITICAL,
                message = "🚨 ATTACK PATTERN: Location accessed ${timeSinceSms}s after $watchModeTriggerType! Provider: ${event.provider ?: "unknown"}$coordsStr",
                cid = null,
                timestamp = now,
                confidence = 0.9f
            ))
        }
    }

    private suspend fun processPrivacyLeakEvent(event: RilEvent.PrivacyLeakEvent, now: Long) {
        val inWatchMode = isInWatchMode(now)
        val severity = if (inWatchMode) ThreatSeverity.HIGH else ThreatSeverity.MEDIUM
        
        Log.w(TAG, "⚠️ PRIVACY LEAK: ${event.leakType}")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.PRIVACY_LEAK,
            severity = severity,
            message = "⚠️ Privacy Leak: ${event.details} (Type: ${event.leakType})",
            cid = null,
            timestamp = now,
            confidence = 0.8f
        ))
    }

    private suspend fun processSensorAbuseEvent(event: RilEvent.SensorAbuseEvent, now: Long) {
        Log.e(TAG, "🚨 SENSOR ABUSE by Radio: ${event.sensorType}")
        _alertFlow.emit(ThreatAlert(
            type = ThreatType.SENSOR_ABUSE,
            severity = ThreatSeverity.CRITICAL,
            message = "🚨 UNAUTHORIZED SENSOR ACCESS: Radio/RIL accessed ${event.sensorType}! Process: ${event.process ?: "unknown"}",
            cid = null,
            timestamp = now,
            confidence = 0.99f
        ))
    }

    
    private fun recalculateThreatScore(now: Long) {
        var encryptionScore = 0f
        var silentSmsScore = 0f
        var identityScore = 0f
        var handoverScore = 0f
        var protocolScore = 0f
        val reasons = mutableListOf<String>()
        
        // Encryption analysis
        recentEncryptionEvents.forEach { event ->
            val age = now - event.timestamp
            val decay = 1f - (age.toFloat() / THREAT_DECAY_TIME).coerceIn(0f, 1f)
            
            if (event.isNoEncryption) {
                encryptionScore = maxOf(encryptionScore, ENCRYPTION_NONE_WEIGHT * decay)
                reasons.add("No encryption detected")
            } else if (event.isDowngrade) {
                encryptionScore = maxOf(encryptionScore, ENCRYPTION_DOWNGRADE_WEIGHT * decay)
                reasons.add("Encryption downgrade")
            }
        }
        
        // Silent SMS analysis
        recentSilentSmsEvents.forEach { event ->
            val age = now - event.timestamp
            val decay = 1f - (age.toFloat() / THREAT_DECAY_TIME).coerceIn(0f, 1f)
            
            val weight = if (event.smsType == SilentSmsType.TYPE_0) {
                SILENT_SMS_TYPE0_WEIGHT
            } else {
                SILENT_SMS_OTHER_WEIGHT
            }
            silentSmsScore = maxOf(silentSmsScore, weight * decay)
            reasons.add("Silent SMS: ${event.smsType.description}")
        }
        
        // Identity request analysis
        recentIdentityEvents.forEach { event ->
            val age = now - event.timestamp
            val decay = 1f - (age.toFloat() / THREAT_DECAY_TIME).coerceIn(0f, 1f)
            
            val weight = when (event.identityType) {
                IdentityType.IMSI -> IMSI_REQUEST_WEIGHT
                IdentityType.IMEI, IdentityType.IMEISV -> IMEI_REQUEST_WEIGHT
                else -> 0.3f
            }
            identityScore = maxOf(identityScore, weight * decay)
            reasons.add("Identity request: ${event.identityType}")
        }
        
        // Handover analysis
        val recentHandovers = recentHandoverEvents.count { now - it.timestamp < 60_000 }
        if (recentHandovers >= 3) {
            handoverScore = (recentHandovers * 0.15f).coerceAtMost(0.8f)
            reasons.add("Rapid handovers: $recentHandovers in 1 minute")
        }
        recentHandoverEvents.filter { it.handoverType == HandoverType.INTER_RAT }.forEach {
            handoverScore = maxOf(handoverScore, FORCED_HANDOVER_WEIGHT)
            reasons.add("Forced network downgrade")
        }
        
        // Auth failure analysis
        val authFailures = recentAuthEvents.count { !it.success && now - it.timestamp < 60_000 }
        if (authFailures >= 2) {
            protocolScore = (authFailures * 0.2f).coerceAtMost(0.6f)
            reasons.add("Authentication failures: $authFailures")
        }
        
        // Calculate combined score (weighted average of non-zero scores)
        val scores = listOf(encryptionScore, silentSmsScore, identityScore, handoverScore, protocolScore)
        val nonZeroScores = scores.filter { it > 0 }
        val combinedScore = if (nonZeroScores.isNotEmpty()) {
            // Use max + average of others for combined score
            val maxScore = scores.maxOrNull() ?: 0f
            val avgOthers = (scores.sum() - maxScore) / scores.size
            (maxScore * 0.7f + avgOthers * 0.3f).coerceIn(0f, 1f)
        } else {
            0f
        }
        
        currentAssessment = LogcatThreatScore(
            encryptionScore = encryptionScore,
            silentSmsScore = silentSmsScore,
            identityScore = identityScore,
            handoverScore = handoverScore,
            protocolScore = protocolScore,
            combinedScore = combinedScore,
            reasons = reasons.distinct(),
            lastUpdated = now
        )
    }
    
    private fun cleanOldEvents(now: Long) {
        val cutoff = now - THREAT_DECAY_TIME
        recentEncryptionEvents.removeAll { it.timestamp < cutoff }
        recentSilentSmsEvents.removeAll { it.timestamp < cutoff }
        recentIdentityEvents.removeAll { it.timestamp < cutoff }
        recentHandoverEvents.removeAll { it.timestamp < cutoff }
        recentAuthEvents.removeAll { it.timestamp < cutoff }
    }
    
    /**
     * Get current threat assessment
     */
    fun getCurrentAssessment(): LogcatThreatScore = currentAssessment
    
    /**
     * Reset all tracked events
     */
    fun reset() {
        recentEncryptionEvents.clear()
        recentSilentSmsEvents.clear()
        recentIdentityEvents.clear()
        recentHandoverEvents.clear()
        recentAuthEvents.clear()
        currentAssessment = LogcatThreatScore()
    }
}

/**
 * Aggregated threat score from logcat analysis
 */
data class LogcatThreatScore(
    val encryptionScore: Float = 0f,
    val silentSmsScore: Float = 0f,
    val identityScore: Float = 0f,
    val handoverScore: Float = 0f,
    val protocolScore: Float = 0f,
    val combinedScore: Float = 0f,
    val reasons: List<String> = emptyList(),
    val lastUpdated: Long = 0L
) {
    val hasThreat: Boolean
        get() = combinedScore > 0.3f
    
    val threatLevel: ThreatSeverity
        get() = when {
            combinedScore >= 0.8f -> ThreatSeverity.CRITICAL
            combinedScore >= 0.6f -> ThreatSeverity.HIGH
            combinedScore >= 0.4f -> ThreatSeverity.MEDIUM
            combinedScore > 0f -> ThreatSeverity.LOW
            else -> ThreatSeverity.NONE
        }
}

/**
 * Individual threat alert
 */
data class ThreatAlert(
    val type: ThreatType,
    val severity: ThreatSeverity,
    val message: String,
    val cid: Int?,
    val timestamp: Long,
    val confidence: Float
)

enum class ThreatType {
    ENCRYPTION_DISABLED,
    ENCRYPTION_DOWNGRADE,
    SILENT_SMS,
    IMSI_CATCHER,
    IDENTITY_REQUEST,
    FORCED_HANDOVER,
    NETWORK_DOWNGRADE,
    AUTH_FAILURE,
    PROTOCOL_ANOMALY,
    // Post-attack behavior types
    MALWARE_BEHAVIOR,
    DATA_EXFILTRATION,
    SETTINGS_HIJACK,
    LOCATION_TRACKING,
    // Radio attack types
    CALL_INTERCEPTION,
    BASEBAND_EXPLOIT,
    VOLTE_INTERCEPTION,
    IMS_ANOMALY,
    SMS_INJECTION,
    SIGNAL_JAMMING,
    SS7_ATTACK,
    ROGUE_BTS,
    // Advanced radio attack types
    FAKE_EMERGENCY_ALERT,
    WIFI_CALLING_ATTACK,
    IDENTITY_EXPOSURE,
    PAGING_ATTACK,
    CELL_MANIPULATION,
    NETWORK_REDIRECT,
    VOLTE_DOS,
    INVALID_NETWORK,

    STINGRAY_PATTERN,
    // Confirmed Attack Chains
    MITM_ATTACK,
    DOWNGRADE_ATTACK,
    DOS_ATTACK,
    PRIVACY_LEAK,
    SENSOR_ABUSE
}

enum class ThreatSeverity {
    NONE,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}
