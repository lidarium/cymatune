package com.cymatune.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.cymatune.db.SMSEvent
import com.cymatune.db.SMSMessageType
import com.cymatune.util.ThreatLevel
import com.cymatune.util.CommonUtils
import kotlinx.coroutines.*
import java.util.concurrent.Executors

/**
 * SMSReceiver - BroadcastReceiver for monitoring incoming SMS messages
 * and detecting silent SMS/paging storms for fake tower detection.
 * 
 * This receiver implements comprehensive SMS monitoring with fallback mechanisms
 * for cases where SMS permissions are not granted.
 */
class SMSReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "SMSReceiver"
        private const val MAX_SMS_HANDLING_TIME_MS = 5000L
        private const val FALLBACK_CHECK_INTERVAL_MS = 30000L // 30 seconds
        
        // Intent actions to monitor
        private val SMS_INTENT_ACTIONS = setOf(
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
            com.cymatune.util.SMSConstants.SMS_DELIVERED_ACTION,
            "android.intent.action.DATA_SMS_RECEIVED"
        )
    }
    
    private val backgroundScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher() + Job())
    private var lastNetworkCheckTime = 0L
    
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Log.d(TAG, "SMS broadcast received: ${intent.action}")
            
            when (intent.action) {
                in SMS_INTENT_ACTIONS -> {
                    handleSMSIntent(context, intent)
                }
                else -> {
                    Log.d(TAG, "Unhandled intent action: ${intent.action}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS broadcast", e)
            // Continue operation even if individual SMS processing fails
        }
    }
    
    private fun handleSMSIntent(context: Context, intent: Intent) {
        when {
            hasSMSPermissions(context) -> {
                processIncomingSMS(context, intent)
            }
            shouldPerformFallbackCheck() -> {
                performFallbackNetworkMonitoring(context)
            }
            else -> {
                Log.d(TAG, "SMS permissions not available, skipping processing")
            }
        }
    }
    
    private fun hasSMSPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context, 
            android.Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_SMS  
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun processIncomingSMS(context: Context, intent: Intent) {
        try {
            // Extract SMS messages from intent
            val smsMessages = extractSMSMessages(intent) ?: run {
                Log.w(TAG, "No SMS messages extracted from intent")
                return
            }
            
            // Process each message
            smsMessages.forEach { message ->
                backgroundScope.launch {
                    try {
                        withTimeout(MAX_SMS_HANDLING_TIME_MS) {
                            processSingleSMS(context, message)
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "SMS processing timed out")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing individual SMS", e)
                    }
                }
            }
            
        } catch (e: SecurityException) {
            Log.w(TAG, "Security exception during SMS processing, falling back to network monitoring", e)
            handlePermissionFailure(context, e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during SMS processing", e)
        }
    }
    
    private fun extractSMSMessages(intent: Intent): Array<android.telephony.SmsMessage>? {
        return try {
            when (intent.action) {
                Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
                com.cymatune.util.SMSConstants.SMS_DELIVERED_ACTION -> {
                    Telephony.Sms.Intents.getMessagesFromIntent(intent)
                }
                "android.intent.action.DATA_SMS_RECEIVED" -> {
                    // Handle data SMS differently
                    val pdus = intent.extras?.get("pdus")
                    if (pdus is Array<*>) {
                        pdus.mapNotNull { pdu ->
                            if (pdu is ByteArray) {
                                android.telephony.SmsMessage.createFromPdu(pdu)
                            } else null
                        }.toTypedArray()
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract SMS messages", e)
            null
        }
    }
    
    private suspend fun processSingleSMS(context: Context, smsMessage: android.telephony.SmsMessage) {
        try {
            // Extract message details
            val sender = smsMessage.originatingAddress ?: "Unknown"
            val messageBody = smsMessage.messageBody
            val timestamp = smsMessage.timestampMillis
            val messageType = determineMessageType(smsMessage)
            
            // Check if this might be a silent SMS
            val isSilent = detectSilentSMS(smsMessage, messageBody)
            
            // Get current signal context
            val signalContext = getCurrentSignalContext(context)
            
            // Create SMS event
            val smsEvent = SMSEvent(
                timestamp = timestamp,
                senderNumber = sender,
                sender = sender,
                messageBody = messageBody ?: "",
                messageType = messageType.toString(),
                isSilent = isSilent,
                cid = signalContext.cid ?: 0,
                lac = signalContext.lac ?: 0,
                mcc = signalContext.mcc ?: 0,
                mnc = signalContext.mnc ?: 0,
                signalStrength = signalContext.signalStrength ?: 0,
                latitude = signalContext.latitude ?: 0.0,
                longitude = signalContext.longitude ?: 0.0,
                networkType = signalContext.networkType ?: "UNKNOWN",
                timingAdvance = signalContext.timingAdvance ?: 0,
                messageFormat = "UNKNOWN",
                encoding = "UNKNOWN",
                messageSize = messageBody?.length ?: 0,
                threatLevel = ThreatLevel.LOW,
                threatConfidence = 0.0,
                isSuspicious = false,
                correlationScore = 0.0,
                analysisResult = null,
                contentAnalysis = null
            )
            
            // Analyze threat level
            val threatAnalysis = analyzeSMSThreat(smsEvent, context)
            val finalEvent = smsEvent.copy(
                threatLevel = threatAnalysis.threatLevel,
                threatConfidence = threatAnalysis.confidence,
                analysisResult = threatAnalysis.analysisDetails
            )
            
            // Store event in database
            saveSMSEvent(finalEvent, context)
            
            // Report suspicious activity if needed
            if (threatAnalysis.isSuspicious) {
                reportSuspiciousSMS(context, finalEvent, threatAnalysis)
            }
            
            Log.d(TAG, "Processed SMS from $sender, type: $messageType, silent: $isSilent, threat: ${finalEvent.threatLevel}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing individual SMS message", e)
        }
    }
    
    private fun determineMessageType(smsMessage: android.telephony.SmsMessage): SMSMessageType {
        return when {
            smsMessage.messageBody.isNullOrEmpty() -> SMSMessageType.SILENT
            smsMessage.messageBody.length < 10 && smsMessage.messageBody.all { it == '\u0000' } -> SMSMessageType.SILENT
            smsMessage.messageBody.startsWith("\u0000") -> SMSMessageType.DATA
            smsMessage.pdu != null && smsMessage.pdu.size < 20 -> SMSMessageType.PDU
            smsMessage.messageBody.contains("EMERGENCY") -> SMSMessageType.EMERGENCY
            smsMessage.messageBody.length > 160 -> SMSMessageType.FLASH
            else -> SMSMessageType.TEXT
        }
    }
    
    private fun detectSilentSMS(smsMessage: android.telephony.SmsMessage, messageBody: String?): Boolean {
        return when {
            messageBody.isNullOrEmpty() -> true
            messageBody?.length ?: 0 < 5 -> true
            messageBody?.all { it == '\u0000' || it == ' ' } ?: false -> true
            smsMessage.pdu != null && smsMessage.pdu.size < 15 -> true
            messageBody?.contains(Regex("[\\x00-\\x1F\\x7F-\\x9F]")) ?: false -> true
            else -> false
        }
    }
    
    private fun getCurrentSignalContext(context: Context): SignalContext {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            // Get network information
            val networkOperator = telephonyManager.networkOperator
            val mcc = networkOperator.take(3).toIntOrNull()
            val mnc = networkOperator.drop(3).toIntOrNull()
            
            // Get cell information (API level dependent)
            val (cid, lac, timingAdvance) = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    val cellIdentity = telephonyManager.cellLocation?.let { cellLocation ->
                        when (cellLocation) {
                            is android.telephony.cdma.CdmaCellLocation -> {
                                Triple(cellLocation.baseStationId, cellLocation.networkId, null)
                            }
                            is android.telephony.gsm.GsmCellLocation -> {
                                Triple(cellLocation.cid, cellLocation.lac, null)
                            }
                            else -> Triple(null, null, null)
                            }
                    }
                    cellIdentity ?: Triple(null, null, null)
                } else {
                    Triple(null, null, null)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot access cell location due to permissions")
                Triple(null, null, null)
            }
            
            // Get signal strength (simplified)
            val signalStrength = try {
                val signalStrengths = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        telephonyManager.signalStrength
                    } else {
                        null
                    }
                } catch (e: SecurityException) {
                    null
                }
                if (signalStrengths != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        signalStrengths.level
                    } else {
                        0
                    }
                } else {
                    null
                }
            } catch (e: SecurityException) {
                null
            }
            
            // Get network type
            val networkType = if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
            
            SignalContext(
                cid = cid,
                lac = lac,
                mcc = mcc,
                mnc = mnc ?: 0,
                signalStrength = signalStrength ?: 0,
                networkType = networkType,
                timingAdvance = timingAdvance,
                latitude = null, // Would require location permissions
                longitude = null
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting signal context", e)
            SignalContext()
        }
    }
    
    private suspend fun analyzeSMSThreat(smsEvent: SMSEvent, context: Context): ThreatAnalysis {
        val threatFactors = mutableListOf<String>()
        var threatScore = 0.0
        
        // Check for silent SMS indicators
        if (smsEvent.isSilent) {
            threatFactors.add("Silent SMS detected")
            threatScore += 0.4
        }
        
        // Check message type
        when (smsEvent.messageType) {
            SMSMessageType.SILENT.toString() -> {
                threatFactors.add("Silent message type")
                threatScore += 0.3
            }
            SMSMessageType.DATA.toString() -> {
                threatFactors.add("Data message type")
                threatScore += 0.2
            }
            SMSMessageType.PDU.toString() -> {
                threatFactors.add("PDU message type")
                threatScore += 0.2
            }
            else -> {}
        }
        
        // Check for suspicious sender patterns
        if (smsEvent.senderNumber.length < 5 || smsEvent.senderNumber.all { it.isDigit() }) {
            threatFactors.add("Suspicious sender pattern")
            threatScore += 0.2
        }
        
        // Check timing patterns (if we have historical data)
        val recentCount = getRecentSMSEventCount(context, smsEvent.sender ?: "Unknown")
        if (recentCount > 5) {
            threatFactors.add("High frequency SMS from sender")
            threatScore += 0.3
        }
        
        // Determine threat level
        val threatLevel = when {
            threatScore >= 0.7 -> ThreatLevel.CRITICAL
            threatScore >= 0.5 -> ThreatLevel.HIGH
            threatScore >= 0.3 -> ThreatLevel.MEDIUM
            else -> ThreatLevel.LOW
        }
        
        val isSuspicious = threatLevel in setOf(ThreatLevel.HIGH, ThreatLevel.CRITICAL)
        
        return ThreatAnalysis(
            threatLevel = threatLevel,
            confidence = threatScore,
            isSuspicious = isSuspicious,
            analysisDetails = threatFactors.joinToString(", ")
        )
    }
    
    private suspend fun getRecentSMSEventCount(context: Context, sender: String): Int {
        return try {
            // This would require database access - simplified for now
            // In a real implementation, this would query the database
            0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recent SMS count", e)
            0
        }
    }
    
    private suspend fun saveSMSEvent(smsEvent: SMSEvent, context: Context) {
        try {
            // This would save to the database - simplified for now
            Log.d(TAG, "Would save SMS event to database: ${smsEvent.sender}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving SMS event", e)
        }
    }
    
    private fun reportSuspiciousSMS(context: Context, smsEvent: SMSEvent, threatAnalysis: ThreatAnalysis) {
        Log.w(TAG, "Suspicious SMS detected: ${smsEvent.sender} - ${threatAnalysis.analysisDetails}")
        
        // Trigger fake tower detection
        val intent = Intent().apply {
            action = "com.cymatune.SMS_THREAT_DETECTED"
            putExtra("sender", smsEvent.senderNumber)
            putExtra("threatLevel", threatAnalysis.threatLevel.name)
            putExtra("isSuspicious", threatAnalysis.isSuspicious)
        }
        
        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send broadcast", e)
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start fake tower detection service", e)
        }
    }
    
    private fun shouldPerformFallbackCheck(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastNetworkCheckTime > FALLBACK_CHECK_INTERVAL_MS) {
            lastNetworkCheckTime = now
            return true
        }
        return false
    }
    
    private fun performFallbackNetworkMonitoring(context: Context) {
        Log.d(TAG, "Performing fallback network state monitoring")
        try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            
            // Monitor network state changes that might indicate paging storms
            val networkState = telephonyManager.dataState
            val networkType = if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                telephonyManager.networkType
            } else {
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            }
            
            Log.d(TAG, "Network state: $networkState, type: $networkType")
            
            // This could trigger additional monitoring in the main detection service
            // Implementation would depend on the specific fallback strategy
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during fallback network monitoring", e)
        }
    }
    
    private fun handlePermissionFailure(context: Context, exception: Exception) {
        Log.w(TAG, "SMS permission failure, enabling degraded mode", exception)
        
        // Could trigger user notification about enabling permissions
        // or adjust detection strategy to focus on network-level monitoring
    }
    
    // Data classes for internal use
    private data class SignalContext(
        val cid: Int? = null,
        val lac: Int? = null,
        val mcc: Int? = null,
        val mnc: Int? = null,
        val signalStrength: Int? = null,
        val networkType: String? = null,
        val timingAdvance: Int? = null,
        val latitude: Double? = null,
        val longitude: Double? = null
    )
    
    private data class ThreatAnalysis(
        val threatLevel: ThreatLevel,
        val confidence: Double,
        val isSuspicious: Boolean,
        val analysisDetails: String
    )
}