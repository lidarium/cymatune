package com.cymatune.util

/**
 * Threat level enumeration for security analysis and risk assessment.
 * Used across multiple components for consistent threat classification.
 */
enum class ThreatLevel {
    LOW,        // Minimal risk, informational only
    MEDIUM,     // Moderate risk, should be monitored
    HIGH,       // Significant risk, requires attention
    CRITICAL    // Severe risk, immediate action required
}

/**
 * Extension function to get threat level description
 */
fun ThreatLevel.getDescription(): String = when (this) {
    ThreatLevel.LOW -> "Low Risk - Normal activity"
    ThreatLevel.MEDIUM -> "Medium Risk - Suspicious activity detected"
    ThreatLevel.HIGH -> "High Risk - Likely threat detected"
    ThreatLevel.CRITICAL -> "Critical Risk - Immediate threat confirmed"
}

/**
 * Extension function to get threat level color coding
 */
fun ThreatLevel.getColorCode(): Int = when (this) {
    ThreatLevel.LOW -> 0xFF4CAF50.toInt()      // Green
    ThreatLevel.MEDIUM -> 0xFFFB8C00.toInt()   // Orange
    ThreatLevel.HIGH -> 0xFFF44336.toInt()     // Red-Orange
    ThreatLevel.CRITICAL -> 0xFFD32F2F.toInt() // Dark Red
}

/**
 * Extension function to convert from string to ThreatLevel
 */
fun String.toThreatLevel(): ThreatLevel = when (this.uppercase()) {
    "LOW" -> ThreatLevel.LOW
    "MEDIUM" -> ThreatLevel.MEDIUM
    "HIGH" -> ThreatLevel.HIGH
    "CRITICAL" -> ThreatLevel.CRITICAL
    else -> ThreatLevel.LOW
}

/**
 * Extension function to convert ThreatLevel to string
 */
fun ThreatLevel.toStringValue(): String = when (this) {
    ThreatLevel.LOW -> "LOW"
    ThreatLevel.MEDIUM -> "MEDIUM"
    ThreatLevel.HIGH -> "HIGH"
    ThreatLevel.CRITICAL -> "CRITICAL"
}