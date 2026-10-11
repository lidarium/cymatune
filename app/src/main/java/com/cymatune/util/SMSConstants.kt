package com.cymatune.util

/**
 * SMS-related constants for broadcast actions and intent filtering.
 */
object SMSConstants {
    // SMS delivery actions
    const val SMS_DELIVERED_ACTION = "android.provider.Telephony.SMS_DELIVERED"
    const val SMS_RECEIVED_ACTION = "android.provider.Telephony.SMS_RECEIVED"
    const val SMS_RECEIVED_WAP_PUSH_ACTION = "android.provider.Telephony.WAP_PUSH_RECEIVED"
    
    // SMS status codes
    const val SMS_STATUS_PENDING = 0
    const val SMS_STATUS_DELIVERED = 1
    const val SMS_STATUS_FAILED = 2
    const val SMS_STATUS_READ = 3
    
    // SMS types
    const val SMS_TYPE_INCOMING = "INCOMING"
    const val SMS_TYPE_OUTGOING = "OUTGOING"
    const val SMS_TYPE_MWI = "MWI" // Message Waiting Indicator
    const val SMS_TYPE_EMERGENCY = "EMERGENCY"
    
    // SMS threat levels
    const val SMS_THREAT_LEVEL_LOW = "LOW"
    const val SMS_THREAT_LEVEL_MEDIUM = "MEDIUM"
    const val SMS_THREAT_LEVEL_HIGH = "HIGH"
    const val SMS_THREAT_LEVEL_CRITICAL = "CRITICAL"
    
    // SMS analysis constants
    const val SMS_ANALYSIS_WINDOW_MS = 300000 // 5 minutes
    const val SMS_SUSPICIOUS_THRESHOLD = 10 // Messages per window
    const val SMS_CORRELATION_THRESHOLD = 0.7 // Correlation strength
}