package com.cymatune.logcat

/**
 * Sealed class representing parsed RIL events from logcat.
 * Each event type contains relevant data extracted from log lines.
 */
sealed class RilEvent {
    abstract val timestamp: Long
    abstract val rawLine: String

    /**
     * WAP Push message event - Potential Spyware Injection
     */
    data class WapPush(
        override val timestamp: Long,
        override val rawLine: String,
        val source: String,
        val content: String
    ) : RilEvent()

    /**
     * Binary SMS event - Potential Exploit Payload
     */
    data class BinarySms(
        override val timestamp: Long,
        override val rawLine: String,
        val source: String,
        val content: String
    ) : RilEvent()
    
    /**
     * Signal strength event from RIL/Radio logs
     */
    data class SignalStrengthEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val rsrp: Int?,
        val rsrq: Int?,
        val sinr: Int?,
        val rssi: Int?,
        val cid: Int?
    ) : RilEvent()
    
    /**
     * Cell change/reselection event
     */
    data class CellChangeEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val oldCid: Int?,
        val newCid: Int,
        val lac: Int?,
        val mcc: Int?,
        val mnc: Int?,
        val reason: String?
    ) : RilEvent()
    
    /**
     * Encryption/cipher change event - HIGH PRIORITY for IMSI catcher detection
     */
    data class EncryptionEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val cipherType: CipherType,
        val previousCipher: CipherType?,
        val cid: Int?
    ) : RilEvent() {
        val isDowngrade: Boolean
            get() = previousCipher != null && cipherType.strength < previousCipher.strength
        
        val isNoEncryption: Boolean
            get() = cipherType == CipherType.A5_0 || cipherType == CipherType.NONE
    }
    
    /**
     * Silent SMS (Type 0) event - indicates possible tracking
     */
    data class SilentSmsEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val smsType: SilentSmsType,
        val sender: String?,
        val messageClass: Int?
    ) : RilEvent()
    
    /**
     * Handover event between cells
     */
    data class HandoverEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val sourceCid: Int?,
        val targetCid: Int?,
        val handoverType: HandoverType,
        val reason: String?
    ) : RilEvent()
    
    /**
     * Authentication/registration event
     */
    data class AuthenticationEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val success: Boolean,
        val authType: String?,
        val rejectCause: Int?,
        val cid: Int?
    ) : RilEvent()
    
    /**
     * Network registration state change
     */
    data class RegistrationEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val state: RegistrationState,
        val lac: Int?,
        val cid: Int?,
        val rat: String?  // Radio Access Technology (LTE, GSM, etc.)
    ) : RilEvent()
    
    /**
     * Identity request from network (IMSI grab attempt indicator)
     */
    data class IdentityRequestEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val identityType: IdentityType,
        val cid: Int?
    ) : RilEvent()
    
    /**
     * LTE security mode command
     */
    data class SecurityModeEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val integrityAlgo: String?,
        val cipheringAlgo: String?,
        val cid: Int?
    ) : RilEvent()
    
    /**
     * Generic RIL event for unclassified but interesting logs
     */
    data class GenericRilEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val tag: String,
        val message: String
    ) : RilEvent()
    
    // ==========================================
    // RADIO ATTACK DETECTION EVENTS
    // ==========================================
    
    /**
     * Call state event - detects call interception setup
     * When cipher changes during/before a call, it's highly suspicious
     */
    data class CallStateEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val callState: CallState,
        val callType: String?,  // voice, video, emergency
        val cipherAtCallStart: CipherType?,
        val cid: Int?
    ) : RilEvent()
    
    /**
     * Baseband crash event - indicates possible exploit attempt
     */
    data class BasebandCrashEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val crashType: String,
        val errorCode: String?,
        val modemState: String?
    ) : RilEvent()
    
    /**
     * IMS/VoLTE anomaly event - modern attack surface
     */
    data class ImsAnomalyEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val anomalyType: ImsAnomalyType,
        val imsState: String?,
        val sipCode: Int?,
        val details: String?
    ) : RilEvent()
    
    /**
     * SMS injection event - spoofed/malformed SMS detection
     */
    data class SmsInjectionEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val injectionType: SmsInjectionType,
        val smscAddress: String?,
        val senderAddress: String?,
        val isValid: Boolean,
        val isSilentSms: Boolean = false,  // Cross-verification: Was this Silent SMS?
        val tpPid: Int? = null              // Protocol ID value (0 or 64 = Silent SMS)
    ) : RilEvent()
    
    /**
     * Signal jamming indicator - correlation of signal loss with other events
     */
    data class SignalJammingEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val signalDrop: Int,  // dB drop
        val previousSignal: Int?,
        val currentSignal: Int?,
        val hasServiceLoss: Boolean,
        val hasAuthFailure: Boolean
    ) : RilEvent()
    
    /**
     * SS7 attack indicator - network-level attack signs
     */
    data class Ss7IndicatorEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val indicatorType: Ss7IndicatorType,
        val relatedCause: String?,
        val cid: Int?,
        val forwardingNumber: String? = null  // Phone number for CF attacks
    ) : RilEvent()
    
    /**
     * Rogue BTS detection event - suspicious base station patterns
     */
    data class RogueBtsEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val suspicionType: RogueBtsSuspicionType,
        val cid: Int?,
        val lac: Int?,
        val signalStrength: Int?,
        val details: String?
    ) : RilEvent()

    // ==========================================
    // POST-ATTACK BEHAVIOR DETECTION EVENTS
    // ==========================================
    
    /**
     * Content provider access event - detects contact/call log/SMS scraping
     */
    data class ContentAccessEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val contentType: ContentType,
        val operation: String,  // query, insert, update, delete
        val callingPackage: String?
    ) : RilEvent()
    
    /**
     * App launch event - detects suspicious app activation
     */
    data class AppLaunchEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val packageName: String,
        val activityName: String?,
        val launchReason: String?
    ) : RilEvent()
    
    /**
     * Network activity event - detects data exfiltration
     */
    data class NetworkActivityEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val host: String?,
        val port: Int?,
        val protocol: String?,
        val direction: String,  // outbound, inbound
        val bytes: Long?
    ) : RilEvent()
    
    /**
     * Settings change event - detects APN/proxy hijack
     */
    data class SettingsChangeEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val settingType: SettingType,
        val oldValue: String?,
        val newValue: String?,
        val changedBy: String?
    ) : RilEvent()
    
    /**
     * Location update event - detects forced location updates
     */
    data class LocationUpdateEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val latitude: Double?,
        val longitude: Double?,
        val accuracy: Float?,
        val provider: String?,
        val requestedBy: String?
    ) : RilEvent()
    
    // ==========================================
    // ADVANCED RADIO ATTACK DETECTION EVENTS
    // ==========================================
    
    /**
     * Fake Emergency Alert (CMAS/ETWS) - Panic-inducing attack
     */
    data class FakeEmergencyAlertEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val alertType: EmergencyAlertType,
        val messageId: Int?,
        val serialNumber: Int?,
        val cellId: Int?,
        val isSuspicious: Boolean,
        val suspicionReason: String?
    ) : RilEvent()
    
    /**
     * Network Downgrade Event - 5G/LTE forced to lower generation
     */
    data class NetworkDowngradeEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val fromNetwork: String,  // NR, LTE, UMTS, GSM
        val toNetwork: String,
        val isForcedDowngrade: Boolean,
        val cid: Int?,
        val reason: String?
    ) : RilEvent()
    
    /**
     * WiFi Calling Anomaly - VoWiFi attack detection
     */
    data class WifiCallingAnomalyEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val anomalyType: WifiCallingAnomalyType,
        val ePdgAddress: String?,
        val sipError: Int?,
        val details: String?
    ) : RilEvent()
    
    /**
     * TMSI Exposure Event - Identity tracking indicator
     */
    data class TmsiExposureEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val exposureType: TmsiExposureType,
        val isNullTmsi: Boolean,
        val tmsiValue: String?,
        val cid: Int?
    ) : RilEvent()
    
    /**
     * Paging Abuse Event - Paging channel attack detection
     */
    data class PagingAbuseEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val pagingCount: Int,
        val timeWindowMs: Long,
        val isAbnormalRate: Boolean,
        val cellId: Int?
    ) : RilEvent()
    
    /**
     * Cell Manipulation Event - Forced cell reselection
     */
    data class CellManipulationEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val manipulationType: CellManipulationType,
        val fromCid: Int?,
        val toCid: Int?,
        val signalDelta: Int?,
        val isSuspicious: Boolean
    ) : RilEvent()
    
    /**
     * Network Redirect Event - LTE redirect attack
     */
    data class NetworkRedirectEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val redirectType: NetworkRedirectType,
        val targetRat: String?,  // GSM, UMTS, etc.
        val originalRat: String?,
        val reason: String?,
        val isSuspicious: Boolean
    ) : RilEvent()
    
    /**
     * VoLTE DoS Event - SIP flood/DoS indicators
     */
    data class VoLteDoSEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val dosType: VoLteDoSType,
        val sipCode: Int?,
        val errorCount: Int,
        val timeWindowMs: Long
    ) : RilEvent()
    
    /**
     * Invalid PLMN Event - Rogue network selection attempt
     */
    data class InvalidPlmnEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val plmnId: String,
        val mcc: Int?,
        val mnc: Int?,
        val rejectionType: PlmnRejectionType,
        val isSuspicious: Boolean
    ) : RilEvent()
    
    /**
     * Stingray Pattern Event - Correlation-based IMSI catcher detection
     */
    /**
     * A-GNSS Event - Potential privacy leak via A-GPS/SUPL
     */

    
    /**
     * Stingray pattern types (correlation-based)
     */
    data class StingrayPatternEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val patternType: StingrayPatternType,
        val correlatedEvents: Int,
        val confidenceScore: Float,
        val cid: Int?,
        val patternDetails: String?
    ) : RilEvent()
    
    data class PrivacyLeakEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val leakType: String, // "AGNSS_IMSI", "CLEARTEXT_TRAFFIC"
        val details: String?
    ) : RilEvent()

    data class SensorAbuseEvent(
        override val timestamp: Long,
        override val rawLine: String,
        val sensorType: String, // "AUDIO", "CAMERA", "SENSOR"
        val process: String?
    ) : RilEvent()
}

/**
 * A-GNSS event types
 */


/**
 * Content types for ContentAccessEvent
 */
enum class ContentType(val uri: String, val sensitivity: Int) {
    CONTACTS("content://contacts", 3),
    CALL_LOG("content://call_log", 3),
    SMS("content://sms", 3),
    MMS("content://mms", 2),
    CALENDAR("content://calendar", 2),
    BROWSER_HISTORY("content://browser", 2),
    MEDIA("content://media", 1),
    UNKNOWN("unknown", 0)
}

/**
 * Settings types for SettingsChangeEvent
 */
enum class SettingType(val suspicionLevel: Int) {
    APN(3),           // HIGH - traffic routing
    PROXY(3),         // HIGH - traffic interception
    DNS(3),           // HIGH - DNS hijack
    VPN(2),           // MEDIUM - security bypass
    WIFI(1),          // LOW - connectivity 
    BLUETOOTH(1),     // LOW - connectivity
    NFC(1),           // LOW - payment/access
    LOCATION(2),      // MEDIUM - tracking
    AIRPLANE_MODE(2), // MEDIUM - communication blocking
    UNKNOWN(0)
}

/**
 * Cipher types used in GSM/LTE
 */
enum class CipherType(val strength: Int, val description: String) {
    NONE(0, "No encryption"),
    A5_0(0, "A5/0 - No encryption"),
    A5_1(2, "A5/1 - Weak encryption (crackable)"),
    A5_2(1, "A5/2 - Very weak encryption (deprecated)"),
    A5_3(4, "A5/3 - Strong encryption"),
    A5_4(5, "A5/4 - Enhanced encryption"),
    EEA0(0, "EEA0 - No LTE encryption"),
    EEA1(3, "EEA1 - SNOW 3G"),
    EEA2(4, "EEA2 - AES"),
    EEA3(4, "EEA3 - ZUC"),
    UNKNOWN(-1, "Unknown cipher");
    
    companion object {
        fun fromLogString(value: String): CipherType {
            return when {
                value.contains("A5/0", ignoreCase = true) || 
                value.contains("A5_0", ignoreCase = true) ||
                value == "0" -> A5_0
                
                value.contains("A5/1", ignoreCase = true) || 
                value.contains("A5_1", ignoreCase = true) ||
                value == "1" -> A5_1
                
                value.contains("A5/2", ignoreCase = true) || 
                value.contains("A5_2", ignoreCase = true) ||
                value == "2" -> A5_2
                
                value.contains("A5/3", ignoreCase = true) || 
                value.contains("A5_3", ignoreCase = true) ||
                value == "3" -> A5_3
                
                value.contains("A5/4", ignoreCase = true) -> A5_4
                
                value.contains("EEA0", ignoreCase = true) ||
                value.contains("null_ciphering", ignoreCase = true) -> EEA0
                
                value.contains("EEA1", ignoreCase = true) ||
                value.contains("SNOW", ignoreCase = true) -> EEA1
                
                value.contains("EEA2", ignoreCase = true) ||
                value.contains("AES", ignoreCase = true) -> EEA2
                
                value.contains("EEA3", ignoreCase = true) ||
                value.contains("ZUC", ignoreCase = true) -> EEA3
                
                value.contains("NONE", ignoreCase = true) ||
                value.contains("disabled", ignoreCase = true) ||
                value.contains("off", ignoreCase = true) -> NONE
                
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Silent SMS types
 */
enum class SilentSmsType(val description: String) {
    TYPE_0("Type 0 - Silent ping"),
    CLASS_0("Class 0 - Flash SMS"),
    WAP_PUSH("WAP Push message"),
    BINARY("Binary/data SMS"),
    UNKNOWN("Unknown silent SMS type")
}

/**
 * Handover types
 */
enum class HandoverType {
    INTRA_CELL,      // Within same cell
    INTER_CELL,      // Between cells, same LAC
    INTER_LAC,       // Between LACs
    INTER_RAT,       // Between RATs (e.g., LTE to GSM)
    REDIRECT,        // Network redirect
    RESELECTION,     // Cell reselection
    RRC_RELEASE,     // RRC connection release with redirect
    UNKNOWN
}

/**
 * Registration states
 */
enum class RegistrationState {
    NOT_REGISTERED,
    REGISTERED_HOME,
    SEARCHING,
    DENIED,
    UNKNOWN,
    REGISTERED_ROAMING,
    EMERGENCY_ONLY
}

/**
 * Identity types that network can request
 */
enum class IdentityType(val suspicionLevel: Int) {
    IMSI(3),        // HIGH - IMSI catchers specifically request this
    IMEI(2),        // MEDIUM - Device tracking
    IMEISV(2),      // MEDIUM - Device tracking with software version
    TMSI(1),        // LOW - Normal temporary ID
    UNKNOWN(0)
}

// ==========================================
// RADIO ATTACK DETECTION ENUMS
// ==========================================

/**
 * Call states for interception detection
 */
enum class CallState {
    IDLE,
    DIALING,
    ALERTING,
    ACTIVE,
    HOLDING,
    DISCONNECTING,
    INCOMING,
    WAITING
}

/**
 * IMS/VoLTE anomaly types
 */
enum class ImsAnomalyType(val severity: Int) {
    REGISTRATION_FAILURE(2),
    DEREGISTRATION_UNEXPECTED(3),
    SIP_INVITE_ANOMALY(3),
    SRTP_DISABLED(4),           // No voice encryption
    SRTP_DOWNGRADE(4),          // Voice encryption stripped
    IMS_ROAMING_REDIRECT(2),
    EMERGENCY_CALLBACK_MODE(1),
    SIP_FLOOD(3),               // DoS indicator
    CERTIFICATE_ERROR(4),
    UNKNOWN(0)
}

/**
 * SMS injection types
 */
enum class SmsInjectionType(val severity: Int) {
    SPOOFED_SENDER(3),          // Sender doesn't match SMSC
    INVALID_SMSC(4),            // Unknown/suspicious SMSC
    MALFORMED_PDU(3),           // Invalid SMS format
    PREMIUM_NUMBER(2),          // Premium rate SMS
    SHORTENED_URL(2),           // Links in SMS
    UNICODE_SPOOF(3),           // Unicode tricks for phishing
    BINARY_MESSAGE(2),          // Executable/binary SMS
    UNKNOWN(0)
}

/**
 * SS7 attack indicator types
 */
enum class Ss7IndicatorType(val severity: Int) {
    LOCATION_UPDATE_REJECT(3),      // HLR manipulation
    UNEXPECTED_DEREGISTRATION(4),   // Subscriber deleted attack
    CALL_FORWARD_ACTIVATED(4),      // Call interception setup
    SMS_REROUTE_DETECTED(4),        // SMS interception
    ROAMING_FRAUD(3),               // Fake roaming
    PURGE_MS_RECEIVED(4),           // Subscriber purge attack
    AUTH_INFO_RESPONSE_UNUSUAL(3),  // Triplet interception
    UNKNOWN(0)
}

/**
 * Rogue BTS suspicion types
 */
enum class RogueBtsSuspicionType(val severity: Int) {
    UNUSUALLY_STRONG_SIGNAL(3),     // Much stronger than expected
    NO_ENCRYPTION(5),               // A5/0 or EEA0
    WEAK_ENCRYPTION(4),             // A5/1 or A5/2
    IDENTITY_REQUEST_FLOOD(4),      // Multiple IMSI requests
    LAC_MISMATCH(3),                // LAC doesn't match area
    FREQUENCY_ANOMALY(3),           // Unusual ARFCN
    MISSING_NEIGHBORS(3),           // No neighboring cells
    RAPID_CELL_CHANGES(3),          // Ping-pong handovers
    DOWNGRADE_FORCE(4),             // LTE to 2G push
    UNKNOWN(0)
}

// ==========================================
// ADVANCED ATTACK DETECTION ENUMS
// ==========================================

/**
 * Emergency Alert types (CMAS/ETWS)
 */
enum class EmergencyAlertType(val severity: Int) {
    PRESIDENTIAL_ALERT(5),       // Highest priority - can't be disabled
    EXTREME_THREAT(4),           // Imminent extreme threat
    SEVERE_THREAT(3),            // Imminent severe threat
    AMBER_ALERT(3),              // Child abduction
    PUBLIC_SAFETY(2),            // Public safety message
    ETWS_EARTHQUAKE(4),          // Earthquake warning
    ETWS_TSUNAMI(4),             // Tsunami warning
    STATE_LOCAL(2),              // State/local alerts
    TEST_ALERT(1),               // Test messages
    UNKNOWN(0)
}

/**
 * WiFi Calling anomaly types
 */
enum class WifiCallingAnomalyType(val severity: Int) {
    EPDG_CERTIFICATE_INVALID(5), // Man-in-the-middle
    EPDG_ADDRESS_MISMATCH(4),    // Rogue ePDG
    SRTP_STRIPPED(5),            // Voice encryption removed
    IPSEC_DOWNGRADE(4),          // IPsec weakened
    SIP_INJECTION(4),            // Malformed SIP
    REGISTRATION_HIJACK(5),      // IMS registration attack
    UNEXPECTED_HANDOVER(3),      // WiFi to LTE forced
    UNKNOWN(0)
}

/**
 * TMSI exposure types
 */
enum class TmsiExposureType(val severity: Int) {
    NULL_TMSI_ASSIGNED(4),       // No TMSI protection
    TMSI_REUSE_FORCED(3),        // Same TMSI across sessions
    IMSI_PAGING(5),              // Paged with IMSI instead of TMSI
    GUTI_EXPOSURE(4),            // 4G/5G identity exposure
    SUPI_REQUESTED(5),           // 5G permanent ID exposure
    UNKNOWN(0)
}

/**
 * Cell manipulation types
 */
enum class CellManipulationType(val severity: Int) {
    FORCED_RESELECTION(4),       // Forced to different cell
    MEASUREMENT_MANIPULATION(3), // Fake signal measurements
    HANDOVER_COMMAND_ABUSE(4),   // Malicious handover
    CELL_BARRING_ABUSE(3),       // Legitimate cells barred
    REDIRECT_INDICATION(4),      // RRC redirect
    CONNECTION_RELEASE(3),       // Connection released
    UNKNOWN(0)
}

/**
 * Network redirect types
 */
enum class NetworkRedirectType(val severity: Int) {
    LTE_TO_GSM(5),               // LTE → 2G (most dangerous)
    LTE_TO_UMTS(4),              // LTE → 3G
    NR_TO_LTE(3),                // 5G → 4G
    NR_TO_GSM(5),                // 5G → 2G
    VOLTE_DISABLED(4),           // VoLTE → CS fallback
    CSFB_FORCED(3),              // Circuit-switched fallback
    UNKNOWN(0)
}

/**
 * VoLTE DoS types
 */
enum class VoLteDoSType(val severity: Int) {
    SIP_FLOOD(4),                // Too many SIP messages
    REGISTRATION_LOOP(3),        // Repeated registration failures
    INVITE_STORM(4),             // Call flood
    DEREGISTRATION_ATTACK(4),    // Forced deregistration
    MEDIA_PLANE_ATTACK(3),       // RTP/RTCP issues
    UNKNOWN(0)
}

/**
 * PLMN rejection types
 */
enum class PlmnRejectionType(val severity: Int) {
    FORBIDDEN_PLMN(3),           // Network in forbidden list
    UNKNOWN_PLMN(4),             // Unrecognized network
    ROAMING_NOT_ALLOWED(2),      // Normal roaming restriction
    PLMN_MISMATCH(4),            // MCC/MNC doesn't match region
    FAKE_OPERATOR(5),            // Impersonating known carrier
    UNKNOWN(0)
}

/**
 * Stingray pattern types (correlation-based)
 */
enum class StingrayPatternType(val severity: Int) {
    SILENT_SMS_THEN_HANDOVER(5), // SMS → cell change
    ENCRYPTION_THEN_IDENTITY(5), // Downgrade → IMSI request
    RAPID_EVENTS_CLUSTER(4),     // Multiple suspicious events
    LOCATION_TRACKING_PATTERN(5),// SMS → location update
    CALL_INTERCEPTION_SETUP(5),  // Downgrade → call → weak cipher
    DATA_COLLECTION_PATTERN(4),  // Identity + contacts + location
    UNKNOWN(0)
}
