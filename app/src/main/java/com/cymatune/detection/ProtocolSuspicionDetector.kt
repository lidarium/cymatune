package com.cymatune.detection

import com.cymatune.util.EncryptionProtocolInfo
import com.cymatune.util.ProtocolDetectionPatterns
import com.cymatune.util.AuthenticationStatus

/**
 * ProtocolSuspicionDetector with Movement-Aware thresholds (B09-B12 fix)
 * Adjusts detection sensitivity based on user movement state.
 */
class ProtocolSuspicionDetector {

    // B09-B12: Movement state for threshold adjustment
    private var currentMovementIntensity: MovementDetector.MovementIntensity = MovementDetector.MovementIntensity.STATIONARY

    /**
     * Set current movement intensity for threshold adjustment
     */
    fun setMovementIntensity(intensity: MovementDetector.MovementIntensity) {
        currentMovementIntensity = intensity
    }

    fun detectProtocolAnomalies(protocolInfo: EncryptionProtocolInfo): List<ProtocolAnomaly> {
        val anomalies = mutableListOf<ProtocolAnomaly>()
        
        // Check for weak ciphers
        if (ProtocolDetectionPatterns.isWeakCipher(protocolInfo.cipherAlgorithm)) {
            anomalies.add(ProtocolAnomaly(
                pattern = ProtocolDetectionPatterns.WEAK_CIPHER_NEGOTIATION,
                confidence = 0.8,
                description = "Weak cipher algorithm detected: ${protocolInfo.cipherAlgorithm}"
            ))
        }
        
        // Check for no encryption
        if (ProtocolDetectionPatterns.isNullCipher(protocolInfo.cipherAlgorithm)) {
            anomalies.add(ProtocolAnomaly(
                pattern = ProtocolDetectionPatterns.NO_ENCRYPTION,
                confidence = 0.9,
                description = "No encryption detected (null cipher)"
            ))
        }
        
        // Check authentication failures
        if (protocolInfo.authenticationStatus == AuthenticationStatus.FAILED) {
            anomalies.add(ProtocolAnomaly(
                pattern = ProtocolDetectionPatterns.AUTHENTICATION_FAILURE,
                confidence = 0.7,
                description = "Authentication failure detected"
            ))
        }
        
        // Check for compromised authentication
        if (protocolInfo.authenticationStatus == AuthenticationStatus.COMPROMISED) {
            anomalies.add(ProtocolAnomaly(
                pattern = ProtocolDetectionPatterns.AUTHENTICATION_FAILURE,
                confidence = 0.95,
                description = "Authentication compromised - possible security breach"
            ))
        }
        
        // Check for protocol downgrade attacks
        if (isProtocolDowngrade(protocolInfo)) {
            anomalies.add(ProtocolAnomaly(
                pattern = ProtocolDetectionPatterns.PROTOCOL_DOWNGRADE,
                confidence = 0.85,
                description = "Protocol downgrade attack detected"
            ))
        }
        
        // Check for unexpected handshake patterns
        // B09-B12: Movement-aware threshold - higher tolerance when traveling
        val handshakeThresholdMultiplier = when (currentMovementIntensity) {
            MovementDetector.MovementIntensity.STATIONARY -> 1.0
            MovementDetector.MovementIntensity.WALKING -> 1.1
            MovementDetector.MovementIntensity.MOVING -> 1.25
            MovementDetector.MovementIntensity.TRAVELING -> 1.5
        }
        if (isUnexpectedHandshake(protocolInfo, handshakeThresholdMultiplier)) {
            anomalies.add(ProtocolAnomaly(
                pattern = ProtocolDetectionPatterns.UNEXPECTED_HANDSHAKE,
                confidence = 0.6,
                description = "Unexpected handshake pattern detected"
            ))
        }
        
        // Check for protocol mismatches
        if (isProtocolMismatch(protocolInfo)) {
            anomalies.add(ProtocolAnomaly(
                pattern = ProtocolDetectionPatterns.PROTOCOL_MISMATCH,
                confidence = 0.85,
                description = "Protocol mismatch detected: ${protocolInfo.protocolType} using ${protocolInfo.cipherAlgorithm}"
            ))
        }
        
        // Check for failed handshakes
        if (isFailedHandshake(protocolInfo)) {
            anomalies.add(ProtocolAnomaly(
                pattern = ProtocolDetectionPatterns.HANDSHAKE_FAILURE,
                confidence = 0.9,
                description = "Handshake failure detected"
            ))
        }
        
        // Check for incomplete handshakes
        if (isIncompleteHandshake(protocolInfo)) {
            anomalies.add(ProtocolAnomaly(
                pattern = ProtocolDetectionPatterns.INCOMPLETE_HANDSHAKE,
                confidence = 0.7,
                description = "Incomplete handshake detected"
            ))
        }
        
        return anomalies
    }
    
    private fun isProtocolDowngrade(protocolInfo: EncryptionProtocolInfo): Boolean {
        // Check if a modern protocol is using weak/legacy ciphers
        return when (protocolInfo.protocolType) {
            "LTE", "NR" -> ProtocolDetectionPatterns.isWeakCipher(protocolInfo.cipherAlgorithm)
            else -> false
        }
    }
    
    private fun isUnexpectedHandshake(
        protocolInfo: EncryptionProtocolInfo,
        thresholdMultiplier: Double = 1.0
    ): Boolean {
        // Check for unusually long handshake durations
        // B09-B12: Apply movement-aware threshold multiplier
        return protocolInfo.handshakeDuration?.let { duration ->
            val baseThreshold = when (protocolInfo.protocolType) {
                "GSM" -> 300 // GSM handshake should be <300ms
                "UMTS" -> 400 // UMTS handshake should be <400ms
                "LTE" -> 200 // LTE handshake should be <200ms
                "NR" -> 150 // NR handshake should be <150ms
                else -> return@let false
            }
            duration > (baseThreshold * thresholdMultiplier).toInt()
        } ?: false
    }
    
    private fun isProtocolMismatch(protocolInfo: EncryptionProtocolInfo): Boolean {
        // Check if protocol type doesn't match cipher algorithm
        return when (protocolInfo.protocolType) {
            "GSM" -> protocolInfo.cipherAlgorithm.startsWith("LTE_") || protocolInfo.cipherAlgorithm.startsWith("NR_")
            "UMTS" -> protocolInfo.cipherAlgorithm.startsWith("LTE_") || protocolInfo.cipherAlgorithm.startsWith("NR_")
            "LTE" -> protocolInfo.cipherAlgorithm.startsWith("GSM_") || protocolInfo.cipherAlgorithm.startsWith("UMTS_")
            "NR" -> protocolInfo.cipherAlgorithm.startsWith("GSM_") || protocolInfo.cipherAlgorithm.startsWith("UMTS_") || protocolInfo.cipherAlgorithm.startsWith("LTE_")
            else -> false
        }
    }
    
    private fun isFailedHandshake(protocolInfo: EncryptionProtocolInfo): Boolean {
        // Check if handshake failed
        return !protocolInfo.handshakeSuccess
    }
    
    private fun isIncompleteHandshake(protocolInfo: EncryptionProtocolInfo): Boolean {
        // Check if handshake is incomplete (null duration or failed)
        return protocolInfo.handshakeDuration == null || !protocolInfo.handshakeSuccess
    }
    
    fun calculateOverallSuspicionScore(anomalies: List<ProtocolAnomaly>): Double {
        if (anomalies.isEmpty()) return 0.0
        
        // Calculate weighted average based on confidence scores
        val totalWeight = anomalies.sumOf { it.confidence }
        val averageScore = totalWeight / anomalies.size
        
        // Apply non-linear scaling to emphasize multiple anomalies
        return when (anomalies.size) {
            1 -> averageScore * 0.8
            2 -> averageScore * 0.9
            else -> averageScore.coerceAtMost(1.0)
        }
    }
    
    data class ProtocolAnomaly(
        val pattern: String,
        val confidence: Double,
        val description: String
    )
}