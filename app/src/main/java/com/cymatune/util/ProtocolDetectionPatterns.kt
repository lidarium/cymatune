package com.cymatune.util

object ProtocolDetectionPatterns {
    // GSM A5/x Ciphers
    const val GSM_A50 = "GSM_A50" // No encryption
    const val GSM_A51 = "GSM_A51" // Weak encryption
    const val GSM_A52 = "GSM_A52" // Broken encryption
    const val GSM_A53 = "GSM_A53" // Strong encryption
    
    // UMTS AKA Patterns
    const val UMTS_AKA_FAILURE = "UMTS_AKA_FAILURE"
    const val UMTS_AKA_REPLAY = "UMTS_AKA_REPLAY"
    const val UMTS_AKA_TIMEOUT = "UMTS_AKA_TIMEOUT"
    
    // LTE EEA Ciphers
    const val LTE_EEA0 = "LTE_EEA0" // Null encryption
    const val LTE_EEA1 = "LTE_EEA1" // SNOW 3G
    const val LTE_EEA2 = "LTE_EEA2" // AES
    const val LTE_EEA3 = "LTE_EEA3" // ZUC
    
    // 5G NR Security
    const val NR_NEA0 = "NR_NEA0" // Null encryption
    const val NR_NEA1 = "NR_NEA1" // SNOW 3G
    const val NR_NEA2 = "NR_NEA2" // AES
    const val NR_NEA3 = "NR_NEA3" // ZUC
    
    // Suspicion Patterns
    const val WEAK_CIPHER_NEGOTIATION = "Weak cipher negotiated"
    const val NO_ENCRYPTION = "No encryption detected"
    const val AUTHENTICATION_FAILURE = "Authentication failure"
    const val PROTOCOL_DOWNGRADE = "Protocol downgrade attack"
    const val UNEXPECTED_HANDSHAKE = "Unexpected handshake pattern"
    const val PROTOCOL_MISMATCH = "Protocol mismatch detected"
    const val HANDSHAKE_FAILURE = "Handshake failure detected"
    const val INCOMPLETE_HANDSHAKE = "Incomplete handshake detected"
    
    // Cipher strength scores (0-100)
    const val CIPHER_STRENGTH_NULL = 0
    const val CIPHER_STRENGTH_WEAK = 25
    const val CIPHER_STRENGTH_BROKEN = 50
    const val CIPHER_STRENGTH_STRONG = 100
    
    // Protocol type constants
    const val PROTOCOL_GSM = "GSM"
    const val PROTOCOL_UMTS = "UMTS"
    const val PROTOCOL_LTE = "LTE"
    const val PROTOCOL_NR = "NR"
    
    // Key exchange methods
    const val KEY_EXCHANGE_COMP128 = "COMP128"
    const val KEY_EXCHANGE_AKA = "AKA"
    const val KEY_EXCHANGE_EAP_AKA = "EAP-AKA"
    const val KEY_EXCHANGE_5G_AKA = "5G-AKA"
    
    // Integrity algorithms
    const val INTEGRITY_EIA1 = "EIA1"
    const val INTEGRITY_EIA2 = "EIA2"
    const val INTEGRITY_EIA3 = "EIA3"
    
    // Security capabilities
    const val CAPABILITY_A5_1 = "A5/1"
    const val CAPABILITY_A5_2 = "A5/2"
    const val CAPABILITY_A5_3 = "A5/3"
    const val CAPABILITY_UEA1 = "UEA1"
    const val CAPABILITY_UEA2 = "UEA2"
    const val CAPABILITY_EEA1 = "EEA1"
    const val CAPABILITY_EEA2 = "EEA2"
    const val CAPABILITY_EEA3 = "EEA3"
    const val CAPABILITY_NEA1 = "NEA1"
    const val CAPABILITY_NEA2 = "NEA2"
    const val CAPABILITY_NEA3 = "NEA3"
    
    /**
     * Get cipher strength score for a given cipher algorithm
     */
    fun getCipherStrength(cipherAlgorithm: String): Int {
        return when (cipherAlgorithm) {
            GSM_A50, LTE_EEA0, NR_NEA0 -> CIPHER_STRENGTH_NULL
            GSM_A51 -> CIPHER_STRENGTH_WEAK
            GSM_A52 -> CIPHER_STRENGTH_BROKEN
            GSM_A53, LTE_EEA1, LTE_EEA2, LTE_EEA3, NR_NEA1, NR_NEA2, NR_NEA3 -> CIPHER_STRENGTH_STRONG
            else -> CIPHER_STRENGTH_NULL
        }
    }
    
    /**
     * Check if cipher is weak (null or weak encryption)
     */
    fun isWeakCipher(cipherAlgorithm: String): Boolean {
        return cipherAlgorithm in listOf(
            GSM_A50, GSM_A51, GSM_A52,
            LTE_EEA0, NR_NEA0
        )
    }
    
    /**
     * Check if cipher is null (no encryption)
     */
    fun isNullCipher(cipherAlgorithm: String): Boolean {
        return cipherAlgorithm in listOf(
            GSM_A50, LTE_EEA0, NR_NEA0
        )
    }
    
    /**
     * Get recommended action based on protocol analysis
     */
    fun getRecommendedAction(protocolInfo: EncryptionProtocolInfo): String {
        return when {
            protocolInfo.authenticationStatus == AuthenticationStatus.FAILED -> 
                "Disconnect immediately - Authentication failure detected"
            protocolInfo.authenticationStatus == AuthenticationStatus.COMPROMISED -> 
                "Disconnect - Possible security compromise"
            isNullCipher(protocolInfo.cipherAlgorithm) -> 
                "Avoid sensitive communications - No encryption"
            isWeakCipher(protocolInfo.cipherAlgorithm) -> 
                "Use encrypted apps - Weak encryption detected"
            else -> "Normal operation - Strong encryption"
        }
    }
}