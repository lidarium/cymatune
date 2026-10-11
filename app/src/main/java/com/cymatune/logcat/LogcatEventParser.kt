package com.cymatune.logcat

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.regex.Pattern

/**
 * Parses raw logcat lines and extracts structured RIL events.
 * Supports multiple vendor-specific log formats (Qualcomm, Samsung, MediaTek, generic).
 */
class LogcatEventParser {
    
    companion object {
        private const val TAG = "LogcatEventParser"
        
        // Timestamp formats commonly seen in logcat
        private val LOGCAT_TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        
        // ==================== ENCRYPTION PATTERNS ====================
        
        // Qualcomm QCRIL patterns
        private val QCRIL_CIPHER_PATTERN = Pattern.compile(
            "ciphering_algorithm[=:]\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Generic cipher patterns
        private val CIPHER_MODE_PATTERN = Pattern.compile(
            "cipher(?:ing)?[\\s_]?(?:mode|algo(?:rithm)?)[=:\\s]+(\\w+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // LTE NAS security patterns
        private val NAS_SECURITY_PATTERN = Pattern.compile(
            "(?:EEA|EIA|5G-EA|5G-IA)[=:\\s]*(\\d+|\\w+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Encryption enabled/disabled
        private val ENCRYPTION_STATE_PATTERN = Pattern.compile(
            "encryption[\\s_]?(enabled|disabled|on|off|none)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Security mode command
        private val SECURITY_MODE_PATTERN = Pattern.compile(
            "SecurityMode(?:Command|Complete).*(?:integrity|cipher)[=:\\s]*(\\w+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // ==================== SILENT SMS PATTERNS ====================
        // NOTE: These patterns are intentionally strict to prevent false positives
        
        // Type 0 SMS - TP-PID = 0 or 64 indicates silent SMS
        // Only match when TP-PID is explicitly set to these values
        private val TYPE0_SMS_PATTERN = Pattern.compile(
            "(?:TP-PID|tp_pid|protocolId)[=:\\s]*(0|64)(?:[^0-9]|$)|(?:SMS|RIL).*(?:TYPE_0|type0|silent.*ping)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Silent/stealth SMS - explicit silent SMS keywords in SMS-related context
        // Must have clear silent SMS indicator, not just "ping" or "silent" alone
        private val SILENT_SMS_PATTERN = Pattern.compile(
            "(?:received|incoming|dispatch).*(?:silent|stealth|type[\\s_]?0)\\s*(?:sms|message)|(?:sms|message).*(?:class[=:\\s]*0.*silent|silent.*class[=:\\s]*0)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Class 0 Flash SMS - Now only alerts if combined with suspicious indicators
        // Normal flash SMS (carrier alerts) should NOT trigger alerts
        private val CLASS0_SMS_PATTERN = Pattern.compile(
            "(?:suspicious|unknown|blocked).*(?:flash|class[=:\\s]*0)|(?:message|sms).*class[=:\\s]*0.*(?:no.*body|empty|hidden)",
            Pattern.CASE_INSENSITIVE
        )
        
        // WAP Push - Only match suspicious WAP push (not normal MMS)
        // Exclude normal MMS notifications which contain WAP_PUSH
        private val WAP_PUSH_PATTERN = Pattern.compile(
            "(?:suspicious|malicious|blocked).*WAP.*PUSH|WAP.*PUSH.*(?:exploit|attack|injection)",
            Pattern.CASE_INSENSITIVE
        )
        
        // ==================== HANDOVER PATTERNS ====================
        
        // Generic handover
        private val HANDOVER_PATTERN = Pattern.compile(
            "(?:handover|HO|handoff).*(?:from|source)[=:\\s]*(\\d+).*(?:to|target)[=:\\s]*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Cell reselection
        private val RESELECTION_PATTERN = Pattern.compile(
            "(?:cell)?\\s*reselection.*(?:to|new)[=:\\s]*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // RRC redirect
        private val REDIRECT_PATTERN = Pattern.compile(
            "(?:RRC|redirect).*(?:to|target).*(?:ARFCN|cell|CID)[=:\\s]*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // ==================== AUTHENTICATION PATTERNS ====================
        
        // Auth failure
        private val AUTH_FAIL_PATTERN = Pattern.compile(
            "(?:AUTH|authentication).*(?:FAIL|failure|reject)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Auth success
        private val AUTH_SUCCESS_PATTERN = Pattern.compile(
            "(?:AUTH|authentication).*(?:success|complete|accept)",
            Pattern.CASE_INSENSITIVE
        )
        
        // ==================== IDENTITY REQUEST PATTERNS ====================
        
        // IMSI request (strong indicator of IMSI catcher)
        private val IMSI_REQUEST_PATTERN = Pattern.compile(
            "(?:IDENTITY|identity).*(?:REQUEST|request).*(?:IMSI|1)",
            Pattern.CASE_INSENSITIVE
        )
        
        // IMEI request
        private val IMEI_REQUEST_PATTERN = Pattern.compile(
            "(?:IDENTITY|identity).*(?:REQUEST|request).*(?:IMEI|2|3)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Generic identity request
        private val IDENTITY_REQUEST_PATTERN = Pattern.compile(
            "(?:IDENTITY|identity)[_\\s]?(?:REQUEST|request)",
            Pattern.CASE_INSENSITIVE
        )
        
        // ==================== REGISTRATION PATTERNS ====================
        
        // Registration state
        private val REG_STATE_PATTERN = Pattern.compile(
            "(?:registration|reg)[_\\s]?state[=:\\s]*(\\d+|\\w+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // LAC/CID from registration
        private val LAC_CID_PATTERN = Pattern.compile(
            "(?:LAC|lac)[=:\\s]*(\\d+).*(?:CID|cid|CI|ci)[=:\\s]*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Just CID
        private val CID_PATTERN = Pattern.compile(
            "(?:CID|cid|CI|ci|cellId)[=:\\s]*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // ==================== SIGNAL PATTERNS ====================
        
        private val RSRP_PATTERN = Pattern.compile(
            "(?:rsrp|RSRP)[=:\\s]*(-?\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val RSRQ_PATTERN = Pattern.compile(
            "(?:rsrq|RSRQ)[=:\\s]*(-?\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val SINR_PATTERN = Pattern.compile(
            "(?:sinr|SINR|snr|SNR)[=:\\s]*(-?\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // ==================== POST-ATTACK BEHAVIOR PATTERNS ====================
        
        // ContentProvider access patterns
        private val CONTENT_ACCESS_PATTERN = Pattern.compile(
            "ContentResolver.*(?:query|insert|update|delete).*content://(?:contacts|call_log|sms|mms|calendar|browser)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val CONTACTS_ACCESS_PATTERN = Pattern.compile(
            "(?:content://contacts|ContactsContract|ContactsProvider)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val CALL_LOG_ACCESS_PATTERN = Pattern.compile(
            "(?:content://call_log|CallLog|CallLogProvider)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val SMS_ACCESS_PATTERN = Pattern.compile(
            "(?:content://sms|SmsProvider|MmsProvider)",
            Pattern.CASE_INSENSITIVE
        )
        
        // App launch patterns
        private val ACTIVITY_START_PATTERN = Pattern.compile(
            "(?:ActivityManager|ActivityTaskManager).*(?:START|startActivity).*(?:cmp|component)=([\\w.]+)/([\\w.$]+)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val PROCESS_START_PATTERN = Pattern.compile(
            "(?:Start proc|ActivityManager).*for.*([\\w.]+).*pid=(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Network activity patterns
        private val SOCKET_CONNECT_PATTERN = Pattern.compile(
            "(?:connected to|connect).*(?:host|addr)[=:\\s]*([\\w.]+)(?:.*port[=:\\s]*(\\d+))?",
            Pattern.CASE_INSENSITIVE
        )
        
        private val DATA_TRANSFER_PATTERN = Pattern.compile(
            "(?:NetworkStats|TrafficStats).*(?:tx|rx|bytes)[=:\\s]*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val HTTP_REQUEST_PATTERN = Pattern.compile(
            "(?:OkHttp|HttpURLConnection|Volley).*(?:GET|POST|PUT|DELETE).*(?:https?://[\\w./-]+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Settings change patterns
        private val APN_CHANGE_PATTERN = Pattern.compile(
            "(?:setApn|APN.*changed|apn_settings)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val PROXY_CHANGE_PATTERN = Pattern.compile(
            "(?:http_proxy|https_proxy|global_proxy|network.*proxy).*(?:set|changed|enabled|=)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val VPN_CHANGE_PATTERN = Pattern.compile(
            "(?:VpnService|startVpn|vpn.*(?:connect|disconnect))",
            Pattern.CASE_INSENSITIVE
        )
        
        // Location access patterns
        private val LOCATION_REQUEST_PATTERN = Pattern.compile(
            "(?:LocationManager|FusedLocation|requestLocationUpdates).*(?:provider|client)[=:\\s]*([\\w.]+)?",
            Pattern.CASE_INSENSITIVE
        )
        
        private val LOCATION_UPDATE_PATTERN = Pattern.compile(
            "(?:onLocationChanged|LocationResult).*(?:lat|latitude)[=:\\s]*(-?[\\d.]+).*(?:lon|longitude)[=:\\s]*(-?[\\d.]+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // ==================== RADIO ATTACK PATTERNS ====================
        
        // Call state patterns
        // Call state patterns - REQUIRED: Context + State
        private val CALL_STATE_PATTERN = Pattern.compile(
            "(?:CallState|PhoneState|CallManager).*(?:state|changed)[=:\\s]*(\\w+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Call start patterns - REQUIRED: Explicit call context
        // Exclude "Data", "Dc" (DataConnection), "Pdp" to avoid false positives from data calls
        private val CALL_START_PATTERN = Pattern.compile(
            "(?:Call|Telephony|RIL)(?!.*(?:Data|Dc|Pdp|Web)).*(?:DIALING|ALERTING|ACTIVE|INCOMING|onCallAdded)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Baseband crash patterns
        private val MODEM_CRASH_PATTERN = Pattern.compile(
            "(?:modem.*(?:crash|reset|restart)|RILD.*(?:died|restart)|baseband.*(?:error|crash|panic))",
            Pattern.CASE_INSENSITIVE
        )
        
        private val RIL_ERROR_PATTERN = Pattern.compile(
            "(?:RIL.*(?:FATAL|died)|QCRIL.*(?:crash|fatal)|radio.*not.*available)",
            Pattern.CASE_INSENSITIVE
        )
        
        // IMS/VoLTE patterns
        private val IMS_REGISTRATION_PATTERN = Pattern.compile(
            "(?:IMS|VoLTE).*(?:registration|reg).*(?:state|status)[=:\\s]*(\\w+)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val SIP_ERROR_PATTERN = Pattern.compile(
            "SIP.*(?:error|fail|reject).*(?:code)[=:\\s]*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val SRTP_PATTERN = Pattern.compile(
            "(?:SRTP|voice.*encrypt|audio.*cipher).*(?:enabled|disabled|none)",
            Pattern.CASE_INSENSITIVE
        )
        
        // SMS injection patterns
        private val SMSC_PATTERN = Pattern.compile(
            "(?:SMSC|service.*center)[=:\\s]*([+\\d]+)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val SMS_PDU_ERROR_PATTERN = Pattern.compile(
            "(?:SMS|PDU).*(?:malformed|bad.*format|header.*invalid|length.*mismatch|structure.*error)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Signal/Jamming patterns - STRICT to prevent false positives
        // Normal out-of-service states should NOT trigger alerts
        // Only match explicit jamming/interference indicators
        private val SIGNAL_LOSS_PATTERN = Pattern.compile(
            "(?:jamming.*detected|rf.*interference|abnormal.*signal.*loss|sudden.*signal.*drop|interference.*detected)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Only alert on very large, sudden drops (not gradual changes)
        private val RAPID_SIGNAL_DROP_PATTERN = Pattern.compile(
            "(?:sudden|rapid|abnormal).*(?:signal|rsrp).*(?:drop|lost).*(-?\\d+).*dB",
            Pattern.CASE_INSENSITIVE
        )
        
        // SS7 indicator patterns
        private val REGISTRATION_REJECT_PATTERN = Pattern.compile(
            "(?:registration|attach).*(?:reject|denied).*cause[=:\\s]*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val CALL_FORWARD_PATTERN = Pattern.compile(
            "(?:call.*forward|CFU|CFB|CFNR).*(?:active|set|enabled)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Rogue BTS patterns
        private val UNUSUAL_SIGNAL_PATTERN = Pattern.compile(
            "(?:rsrp|signal)[=:\\s]*(-?\\d+).*(?:unusually|abnormal|unexpected)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val LAC_ANOMALY_PATTERN = Pattern.compile(
            "(?:LAC|lac).*(?:changed|mismatch|unexpected)[=:\\s]*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // ==========================================
        // ADVANCED ATTACK DETECTION PATTERNS
        // ==========================================
        
        // Emergency Alert (CMAS/ETWS) patterns
        private val EMERGENCY_ALERT_PATTERN = Pattern.compile(
            "(?:CellBroadcast|CMAS|ETWS|Emergency.*Alert|Presidential.*Alert).*(?:received|delivered|message)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val ETWS_PATTERN = Pattern.compile(
            "(?:ETWS|earthquake|tsunami).*(?:warning|alert|primary|secondary)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Network Downgrade patterns (5G→LTE→3G→2G)
        private val NETWORK_DOWNGRADE_PATTERN = Pattern.compile(
            "(?:network|RAT|service).*(?:changed|switch|fallback).*(?:from|to).*(?:NR|LTE|UMTS|GSM|WCDMA|EDGE|GPRS)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val RAT_CHANGE_PATTERN = Pattern.compile(
            "(?:RAT|RadioAccessTec|networkType).*(?:changed|=).*(?:from\\s*)(\\w+).*(?:to\\s*)(\\w+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // WiFi Calling (VoWiFi) patterns
        private val WIFI_CALLING_PATTERN = Pattern.compile(
            "(?:VoWiFi|WiFiCalling|ePDG|IWLAN).*(?:error|failed|disconnect|handover)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val EPDG_PATTERN = Pattern.compile(
            "(?:ePDG|tunnel|IPsec).*(?:cert|certificate|address|server)[=:\\s]*([\\w.]+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // TMSI/Identity exposure patterns
        private val TMSI_PATTERN = Pattern.compile(
            "(?:TMSI|GUTI|SUPI|SUCI).*(?:null|empty|reuse|exposed|assigned)[=:\\s]*([0-9a-fA-F]*)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val IMSI_PAGING_PATTERN = Pattern.compile(
            "(?:paging|page).*(?:IMSI|identity).*(?:instead|direct)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Paging abuse patterns
        private val PAGING_PATTERN = Pattern.compile(
            "(?:paging|page).*(?:received|request|count)[=:\\s]*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Cell manipulation patterns
        private val CELL_RESELECTION_PATTERN = Pattern.compile(
            "(?:cell|serving).*(?:reselect|change|redirect).*(?:from|to).*(?:CID|cellId)[=:\\s]*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val CELL_BARRED_PATTERN = Pattern.compile(
            "(?:cell|serving).*(?:barred|blocked|rejected)",
            Pattern.CASE_INSENSITIVE
        )
        
        // LTE Redirect patterns
        private val LTE_REDIRECT_PATTERN = Pattern.compile(
            "(?:redirect|RRC.*redirect|inter.*RAT).*(?:to|target)[=:\\s]*(\\w+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // VoLTE DoS patterns
        private val SIP_ERROR_FLOOD_PATTERN = Pattern.compile(
            "(?:SIP|IMS).*(?:error|failure|timeout).*(?:count|consecutive)[=:\\s]*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val DEREGISTRATION_PATTERN = Pattern.compile(
            "(?:IMS|VoLTE).*(?:deregist|unregist|registration.*?terminated|unexpected.*?removed)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Invalid PLMN patterns - REQUIRE digits to avoid "unknown" matches
        private val PLMN_REJECT_PATTERN = Pattern.compile(
            "(?:PLMN|network).*(?:forbidden|reject|invalid).*(?:MCC|MNC)[=:\\s]*(\\d{3})[-]?(\\d{2,3})",
            Pattern.CASE_INSENSITIVE
        )
        
        private val PLMN_SELECT_PATTERN = Pattern.compile(
            "(?:PLMN|operator).*(?:select|chose|attach).*(?:MCC|MNC)[=:\\s]*(\\d{3}).*?(\\d{2,3})?",
            Pattern.CASE_INSENSITIVE
        )

        

    
    }
    
        // ==================== PRIVACY & SENSOR PATTERNS ====================
        
        // A-GNSS IMSI Leak
        private val AGNSS_IMSI_PATTERN = Pattern.compile(
            "(?:AGnssRil|GnssLocation).*(?:setSetId|setId).*(?:type|id)[=:\\s]*(?:IMSI|1)",
            Pattern.CASE_INSENSITIVE
        )

        // Sensor Access by System/Radio (Microphone/Camera)
        // Look for AudioPolicyManager starting input for uid 1001 (Radio) or similar context
        private val RADIO_AUDIO_ACCESS_PATTERN = Pattern.compile(
            "(?:AudioPolicyManager|AudioFlinger).*(?:startInput|update).*uid[=:\\s]*(?:1001|radio|rild)",
            Pattern.CASE_INSENSITIVE
        )
        
        private val UNKNOWN_SENSOR_ACCESS_PATTERN = Pattern.compile(
            "(?:SensorService|SensorManager).*(?:activate|enable).*(?:uid|package)[=:\\s]*(?:1001|com\\.android\\.phone|rild)",
            Pattern.CASE_INSENSITIVE
        )
        
        // DNS/HTTP Leaks (Cleartext)
        private val CLEARTEXT_TRAFFIC_PATTERN = Pattern.compile(
            "(?:NetworkSecurityConfig|CleartextTraffic).*(?:cleartext|unencrypted).*(?:blocked|allowed).*(?:domain|host)",
            Pattern.CASE_INSENSITIVE
        )
    
    // Track previous cipher to detect downgrades
    private var lastKnownCipher: CipherType? = null
    private var lastKnownCid: Int? = null
    
    // Radio attack tracking
    private var lastKnownSignal: Int? = null
    private var currentCallState: CallState = CallState.IDLE
    private var cipherAtCallStart: CipherType? = null
    
    /**
     * Parse a raw logcat line and extract any RIL events
     */
    fun parse(line: String): RilEvent? {
        val timestamp = extractTimestamp(line)
        
        // Try each parser in order of priority/likelihood
        return parseEncryptionEvent(line, timestamp)
            ?: parseSilentSmsEvent(line, timestamp)
            ?: parseIdentityRequestEvent(line, timestamp)
            ?: parseHandoverEvent(line, timestamp)
            ?: parseAuthenticationEvent(line, timestamp)
            ?: parseRegistrationEvent(line, timestamp)
            ?: parseSignalEvent(line, timestamp)
            // Radio attack detection
            ?: parseCallStateEvent(line, timestamp)
            ?: parseBasebandCrashEvent(line, timestamp)
            ?: parseImsAnomalyEvent(line, timestamp)
            ?: parseSmsInjectionEvent(line, timestamp)
            ?: parseSignalJammingEvent(line, timestamp)
            ?: parseSs7IndicatorEvent(line, timestamp)
            ?: parseRogueBtsEvent(line, timestamp)
            // Advanced radio attack detection
            ?: parseFakeEmergencyAlertEvent(line, timestamp)
            ?: parseNetworkDowngradeEvent(line, timestamp)
            ?: parseWifiCallingAnomalyEvent(line, timestamp)
            ?: parseTmsiExposureEvent(line, timestamp)
            ?: parsePagingAbuseEvent(line, timestamp)
            ?: parseCellManipulationEvent(line, timestamp)
            ?: parseNetworkRedirectEvent(line, timestamp)
            ?: parseVoLteDoSEvent(line, timestamp)
            ?: parseInvalidPlmnEvent(line, timestamp)
            // Post-attack behavior detection
            ?: parseContentAccessEvent(line, timestamp)
            ?: parseAppLaunchEvent(line, timestamp)
            ?: parseNetworkActivityEvent(line, timestamp)
            ?: parseSettingsChangeEvent(line, timestamp)
            ?: parseLocationUpdateEvent(line, timestamp)
            ?: parsePrivacyOrSensorEvent(line, timestamp)

    }
    
    /**
     * Parse encryption-related events
     */
    private fun parseEncryptionEvent(line: String, timestamp: Long): RilEvent.EncryptionEvent? {
        // Check Qualcomm QCRIL cipher
        var matcher = QCRIL_CIPHER_PATTERN.matcher(line)
        if (matcher.find()) {
            val cipherValue = matcher.group(1) ?: return null
            val cipher = CipherType.fromLogString(cipherValue)
            val cid = extractCid(line)
            return createEncryptionEvent(timestamp, line, cipher, cid)
        }
        
        // Check generic cipher mode
        matcher = CIPHER_MODE_PATTERN.matcher(line)
        if (matcher.find()) {
            val cipherValue = matcher.group(1) ?: return null
            val cipher = CipherType.fromLogString(cipherValue)
            val cid = extractCid(line)
            return createEncryptionEvent(timestamp, line, cipher, cid)
        }
        
        // Check NAS security (LTE)
        matcher = NAS_SECURITY_PATTERN.matcher(line)
        if (matcher.find()) {
            val cipherValue = matcher.group(1) ?: return null
            val cipher = CipherType.fromLogString(cipherValue)
            val cid = extractCid(line)
            return createEncryptionEvent(timestamp, line, cipher, cid)
        }
        
        // Check encryption enabled/disabled
        matcher = ENCRYPTION_STATE_PATTERN.matcher(line)
        if (matcher.find()) {
            val state = matcher.group(1)?.lowercase() ?: return null
            val cipher = if (state in listOf("disabled", "off", "none")) {
                CipherType.NONE
            } else {
                CipherType.UNKNOWN
            }
            val cid = extractCid(line)
            return createEncryptionEvent(timestamp, line, cipher, cid)
        }
        
        return null
    }
    
    private fun createEncryptionEvent(
        timestamp: Long, 
        line: String, 
        cipher: CipherType, 
        cid: Int?
    ): RilEvent.EncryptionEvent {
        val prevCipher = lastKnownCipher
        lastKnownCipher = cipher
        if (cid != null) lastKnownCid = cid
        
        return RilEvent.EncryptionEvent(
            timestamp = timestamp,
            rawLine = line,
            cipherType = cipher,
            previousCipher = prevCipher,
            cid = cid ?: lastKnownCid
        )
    }
    
    /**
     * Parse silent SMS events
     */
    private fun parseSilentSmsEvent(line: String, timestamp: Long): RilEvent.SilentSmsEvent? {
        // Type 0 SMS
        if (TYPE0_SMS_PATTERN.matcher(line).find()) {
            return RilEvent.SilentSmsEvent(
                timestamp = timestamp,
                rawLine = line,
                smsType = SilentSmsType.TYPE_0,
                sender = extractSender(line),
                messageClass = 0
            )
        }
        
        // Silent SMS keyword
        if (SILENT_SMS_PATTERN.matcher(line).find()) {
            return RilEvent.SilentSmsEvent(
                timestamp = timestamp,
                rawLine = line,
                smsType = SilentSmsType.TYPE_0,
                sender = extractSender(line),
                messageClass = null
            )
        }
        
        // Class 0 flash SMS
        if (CLASS0_SMS_PATTERN.matcher(line).find()) {
            return RilEvent.SilentSmsEvent(
                timestamp = timestamp,
                rawLine = line,
                smsType = SilentSmsType.CLASS_0,
                sender = extractSender(line),
                messageClass = 0
            )
        }
        
        // WAP Push
        if (WAP_PUSH_PATTERN.matcher(line).find()) {
            return RilEvent.SilentSmsEvent(
                timestamp = timestamp,
                rawLine = line,
                smsType = SilentSmsType.WAP_PUSH,
                sender = extractSender(line),
                messageClass = null
            )
        }
        
        return null
    }
    
    /**
     * Parse identity request events (IMSI catcher indicator)
     */
    private fun parseIdentityRequestEvent(line: String, timestamp: Long): RilEvent.IdentityRequestEvent? {
        // IMSI request - highest suspicion
        if (IMSI_REQUEST_PATTERN.matcher(line).find()) {
            return RilEvent.IdentityRequestEvent(
                timestamp = timestamp,
                rawLine = line,
                identityType = IdentityType.IMSI,
                cid = extractCid(line) ?: lastKnownCid
            )
        }
        
        // IMEI request
        if (IMEI_REQUEST_PATTERN.matcher(line).find()) {
            return RilEvent.IdentityRequestEvent(
                timestamp = timestamp,
                rawLine = line,
                identityType = IdentityType.IMEI,
                cid = extractCid(line) ?: lastKnownCid
            )
        }
        
        // Generic identity request
        if (IDENTITY_REQUEST_PATTERN.matcher(line).find()) {
            return RilEvent.IdentityRequestEvent(
                timestamp = timestamp,
                rawLine = line,
                identityType = IdentityType.UNKNOWN,
                cid = extractCid(line) ?: lastKnownCid
            )
        }
        
        return null
    }
    
    /**
     * Parse handover events
     */
    private fun parseHandoverEvent(line: String, timestamp: Long): RilEvent.HandoverEvent? {
        // Generic handover with source/target
        var matcher = HANDOVER_PATTERN.matcher(line)
        if (matcher.find()) {
            return RilEvent.HandoverEvent(
                timestamp = timestamp,
                rawLine = line,
                sourceCid = matcher.group(1)?.toIntOrNull(),
                targetCid = matcher.group(2)?.toIntOrNull(),
                handoverType = determineHandoverType(line),
                reason = extractReason(line)
            )
        }
        
        // Cell reselection
        matcher = RESELECTION_PATTERN.matcher(line)
        if (matcher.find()) {
            return RilEvent.HandoverEvent(
                timestamp = timestamp,
                rawLine = line,
                sourceCid = lastKnownCid,
                targetCid = matcher.group(1)?.toIntOrNull(),
                handoverType = HandoverType.RESELECTION,
                reason = "Cell reselection"
            )
        }
        
        // RRC redirect
        matcher = REDIRECT_PATTERN.matcher(line)
        if (matcher.find()) {
            return RilEvent.HandoverEvent(
                timestamp = timestamp,
                rawLine = line,
                sourceCid = lastKnownCid,
                targetCid = matcher.group(1)?.toIntOrNull(),
                handoverType = HandoverType.REDIRECT,
                reason = "RRC redirect"
            )
        }
        
        return null
    }
    
    /**
     * Parse authentication events
     */
    private fun parseAuthenticationEvent(line: String, timestamp: Long): RilEvent.AuthenticationEvent? {
        // Auth failure
        if (AUTH_FAIL_PATTERN.matcher(line).find()) {
            return RilEvent.AuthenticationEvent(
                timestamp = timestamp,
                rawLine = line,
                success = false,
                authType = extractAuthType(line),
                rejectCause = extractRejectCause(line),
                cid = extractCid(line) ?: lastKnownCid
            )
        }
        
        // Auth success
        if (AUTH_SUCCESS_PATTERN.matcher(line).find()) {
            return RilEvent.AuthenticationEvent(
                timestamp = timestamp,
                rawLine = line,
                success = true,
                authType = extractAuthType(line),
                rejectCause = null,
                cid = extractCid(line) ?: lastKnownCid
            )
        }
        
        return null
    }
    
    /**
     * Parse registration events
     */
    private fun parseRegistrationEvent(line: String, timestamp: Long): RilEvent.RegistrationEvent? {
        val matcher = REG_STATE_PATTERN.matcher(line)
        if (matcher.find()) {
            val stateValue = matcher.group(1) ?: return null
            val state = parseRegistrationState(stateValue)
            
            // Extract LAC/CID if present
            val lacCidMatcher = LAC_CID_PATTERN.matcher(line)
            val lac = if (lacCidMatcher.find()) lacCidMatcher.group(1)?.toIntOrNull() else null
            val cid = extractCid(line)
            
            if (cid != null) lastKnownCid = cid
            
            return RilEvent.RegistrationEvent(
                timestamp = timestamp,
                rawLine = line,
                state = state,
                lac = lac,
                cid = cid,
                rat = extractRat(line)
            )
        }
        
        return null
    }
    
    /**
     * Parse signal strength events
     */
    private fun parseSignalEvent(line: String, timestamp: Long): RilEvent.SignalStrengthEvent? {
        // Only parse if there's signal-related content
        if (!line.contains("signal", ignoreCase = true) && 
            !line.contains("rsrp", ignoreCase = true) &&
            !line.contains("rsrq", ignoreCase = true)) {
            return null
        }
        
        val rsrp = RSRP_PATTERN.matcher(line).let { if (it.find()) it.group(1)?.toIntOrNull() else null }
        val rsrq = RSRQ_PATTERN.matcher(line).let { if (it.find()) it.group(1)?.toIntOrNull() else null }
        val sinr = SINR_PATTERN.matcher(line).let { if (it.find()) it.group(1)?.toIntOrNull() else null }
        
        // Only create event if we got at least one value
        if (rsrp != null || rsrq != null || sinr != null) {
            return RilEvent.SignalStrengthEvent(
                timestamp = timestamp,
                rawLine = line,
                rsrp = rsrp,
                rsrq = rsrq,
                sinr = sinr,
                rssi = null,
                cid = extractCid(line) ?: lastKnownCid
            )
        }
        
        return null
    }
    
    // ==================== HELPER METHODS ====================
    
    private fun extractTimestamp(line: String): Long {
        return try {
            // Try to parse logcat timestamp (MM-dd HH:mm:ss.SSS)
            val match = Regex("^(\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3})").find(line)
            if (match != null) {
                LOGCAT_TIME_FORMAT.parse(match.groupValues[1])?.time ?: System.currentTimeMillis()
            } else {
                System.currentTimeMillis()
            }
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
    
    private fun extractCid(line: String): Int? {
        val matcher = CID_PATTERN.matcher(line)
        return if (matcher.find()) matcher.group(1)?.toIntOrNull() else null
    }
    
    private fun extractSender(line: String): String? {
        // Try to extract phone number or sender ID
        val match = Regex("(?:from|sender|originator)[=:\\s]*([+\\d]+|\\w+)").find(line)
        return match?.groupValues?.get(1)
    }
    
    private fun extractReason(line: String): String? {
        val match = Regex("(?:reason|cause)[=:\\s]*(\\w+)").find(line)
        return match?.groupValues?.get(1)
    }
    
    private fun extractAuthType(line: String): String? {
        val match = Regex("(?:auth_type|type)[=:\\s]*(\\w+)").find(line)
        return match?.groupValues?.get(1)
    }
    
    private fun extractRejectCause(line: String): Int? {
        val match = Regex("(?:cause|reject)[=:\\s]*(\\d+)").find(line)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun extractRat(line: String): String? {
        return when {
            line.contains("LTE", ignoreCase = true) -> "LTE"
            line.contains("NR", ignoreCase = true) || line.contains("5G", ignoreCase = true) -> "NR"
            line.contains("WCDMA", ignoreCase = true) || line.contains("UMTS", ignoreCase = true) -> "WCDMA"
            line.contains("GSM", ignoreCase = true) -> "GSM"
            line.contains("CDMA", ignoreCase = true) -> "CDMA"
            else -> null
        }
    }
    
    private fun determineHandoverType(line: String): HandoverType {
        return when {
            line.contains("inter_rat", ignoreCase = true) || 
            line.contains("IRAT", ignoreCase = true) -> HandoverType.INTER_RAT
            
            line.contains("inter_lac", ignoreCase = true) -> HandoverType.INTER_LAC
            line.contains("redirect", ignoreCase = true) -> HandoverType.REDIRECT
            line.contains("reselect", ignoreCase = true) -> HandoverType.RESELECTION
            line.contains("release", ignoreCase = true) -> HandoverType.RRC_RELEASE
            else -> HandoverType.INTER_CELL
        }
    }
    
    private fun parseRegistrationState(value: String): RegistrationState {
        return when (value.lowercase()) {
            "0", "not_registered", "none" -> RegistrationState.NOT_REGISTERED
            "1", "registered", "home" -> RegistrationState.REGISTERED_HOME
            "2", "searching", "search" -> RegistrationState.SEARCHING
            "3", "denied", "reject" -> RegistrationState.DENIED
            "5", "roaming" -> RegistrationState.REGISTERED_ROAMING
            "8", "emergency" -> RegistrationState.EMERGENCY_ONLY
            else -> RegistrationState.UNKNOWN
        }
    }
    
    // ==================== RADIO ATTACK PARSING ====================
    
    /**
     * Parse call state events - detects call interception setup
     */
    private fun parseCallStateEvent(line: String, timestamp: Long): RilEvent.CallStateEvent? {
        val matcher = CALL_STATE_PATTERN.matcher(line)
        if (matcher.find()) {
            val stateStr = matcher.group(1)?.uppercase() ?: return null
            val newState = parseCallState(stateStr)
            
            // Track call starting - record cipher at start
            if (newState != CallState.IDLE && currentCallState == CallState.IDLE) {
                cipherAtCallStart = lastKnownCipher
            }
            
            val previousState = currentCallState
            currentCallState = newState
            
            return RilEvent.CallStateEvent(
                timestamp = timestamp,
                rawLine = line,
                callState = newState,
                callType = extractCallType(line),
                cipherAtCallStart = cipherAtCallStart,
                cid = extractCid(line) ?: lastKnownCid
            )
        }
        
        // Also check for call start patterns
        if (CALL_START_PATTERN.matcher(line).find()) {
            cipherAtCallStart = lastKnownCipher
            currentCallState = CallState.ACTIVE
            
            return RilEvent.CallStateEvent(
                timestamp = timestamp,
                rawLine = line,
                callState = CallState.ACTIVE,
                callType = extractCallType(line),
                cipherAtCallStart = cipherAtCallStart,
                cid = extractCid(line) ?: lastKnownCid
            )
        }
        
        return null
    }
    
    private fun parseCallState(value: String): CallState {
        return when (value.uppercase()) {
            "IDLE", "0" -> CallState.IDLE
            "DIALING", "1" -> CallState.DIALING
            "ALERTING", "2" -> CallState.ALERTING
            "ACTIVE", "3" -> CallState.ACTIVE
            "HOLDING", "4" -> CallState.HOLDING
            "DISCONNECTING", "5" -> CallState.DISCONNECTING
            "INCOMING", "6" -> CallState.INCOMING
            "WAITING", "7" -> CallState.WAITING
            else -> CallState.IDLE
        }
    }
    
    private fun extractCallType(line: String): String? {
        return when {
            line.contains("emergency", ignoreCase = true) -> "emergency"
            line.contains("video", ignoreCase = true) -> "video"
            line.contains("voice", ignoreCase = true) -> "voice"
            else -> null
        }
    }
    
    /**
     * Parse baseband crash events - indicates possible exploit attempt
     */
    private fun parseBasebandCrashEvent(line: String, timestamp: Long): RilEvent.BasebandCrashEvent? {
        if (MODEM_CRASH_PATTERN.matcher(line).find() || RIL_ERROR_PATTERN.matcher(line).find()) {
            val crashType = extractCrashType(line)
            
            // FALSE POSITIVE CONTROL: Ignore "unknown" crash types.
            // If we can't identify it as a panic, reset, or restart, it's likely just a noisy log.
            if (crashType == "unknown") {
                return null
            }
            
            return RilEvent.BasebandCrashEvent(
                timestamp = timestamp,
                rawLine = line,
                crashType = crashType,
                errorCode = extractErrorCode(line),
                modemState = extractModemState(line)
            )
        }
        return null
    }
    
    private fun extractCrashType(line: String): String {
        return when {
            line.contains("panic", ignoreCase = true) -> "kernel_panic"
            line.contains("crash", ignoreCase = true) -> "crash"
            line.contains("died", ignoreCase = true) -> "process_died"
            line.contains("restart", ignoreCase = true) -> "restart"
            line.contains("reset", ignoreCase = true) -> "reset"
            else -> "unknown"
        }
    }
    
    private fun extractErrorCode(line: String): String? {
        val match = Regex("(?:error|code)[=:\\s]*(\\w+)").find(line)
        return match?.groupValues?.get(1)
    }
    
    private fun extractModemState(line: String): String? {
        val match = Regex("(?:state|status)[=:\\s]*(\\w+)").find(line)
        return match?.groupValues?.get(1)
    }
    
    /**
     * Parse IMS/VoLTE anomaly events
     */
    private fun parseImsAnomalyEvent(line: String, timestamp: Long): RilEvent.ImsAnomalyEvent? {
        // IMS registration issues
        val regMatcher = IMS_REGISTRATION_PATTERN.matcher(line)
        if (regMatcher.find()) {
            val state = regMatcher.group(1)?.lowercase()
            if (state in listOf("failed", "error", "reject", "timeout")) {
                return RilEvent.ImsAnomalyEvent(
                    timestamp = timestamp,
                    rawLine = line,
                    anomalyType = ImsAnomalyType.REGISTRATION_FAILURE,
                    imsState = state,
                    sipCode = null,
                    details = "IMS registration failed"
                )
            }
        }
        
        // SIP errors
        val sipMatcher = SIP_ERROR_PATTERN.matcher(line)
        if (sipMatcher.find()) {
            return RilEvent.ImsAnomalyEvent(
                timestamp = timestamp,
                rawLine = line,
                anomalyType = ImsAnomalyType.SIP_INVITE_ANOMALY,
                imsState = null,
                sipCode = sipMatcher.group(1)?.toIntOrNull(),
                details = "SIP error detected"
            )
        }
        
        // SRTP (voice encryption) issues
        if (SRTP_PATTERN.matcher(line).find() && 
            (line.contains("disabled", ignoreCase = true) || line.contains("none", ignoreCase = true))) {
            return RilEvent.ImsAnomalyEvent(
                timestamp = timestamp,
                rawLine = line,
                anomalyType = if (currentCallState != CallState.IDLE) 
                    ImsAnomalyType.SRTP_DOWNGRADE else ImsAnomalyType.SRTP_DISABLED,
                imsState = null,
                sipCode = null,
                details = "Voice encryption disabled"
            )
        }
        
        return null
    }
    
    /**
     * Parse SMS injection events - spoofed/malformed SMS
     */
    private fun parseSmsInjectionEvent(line: String, timestamp: Long): RilEvent.SmsInjectionEvent? {
        // Malformed PDU
        if (SMS_PDU_ERROR_PATTERN.matcher(line).find()) {
            
            // CROSS-VERIFY: Check if this malformed PDU was Silent SMS
            val isSilentSms = checkIfSilentSms(line)
            val tpPid = extractTpPid(line)
            
            return RilEvent.SmsInjectionEvent(
                timestamp = timestamp,
                rawLine = line,
                injectionType = SmsInjectionType.MALFORMED_PDU,
                smscAddress = extractSmsc(line),
                senderAddress = extractSender(line),
                isValid = false,
                isSilentSms = isSilentSms,  // Cross-verification result
                tpPid = tpPid
            )
        }
        
        return null
    }
    
    /**
     * Check if SMS was Silent SMS by examining TP-PID and keywords
     */
    private fun checkIfSilentSms(line: String): Boolean {
        // Method 1: Check for explicit TP-PID=0 or 64 (most reliable)
        if (TYPE0_SMS_PATTERN.matcher(line).find()) {
            return true
        }
        
        // Method 2: Check for "silent", "type0" keywords
        if (SILENT_SMS_PATTERN.matcher(line).find()) {
            return true
        }
        
        // Method 3: Check for Class 0 indicators
        if (CLASS0_SMS_PATTERN.matcher(line).find()) {
            return true
        }
        
        return false
    }
    
    /**
     * Extract TP-PID value if available
     */
    private fun extractTpPid(line: String): Int? {
        val pidPattern = Pattern.compile("(?:TP-PID|tp_pid|protocolId)[=:\\s]*(\\d+)")
        val matcher = pidPattern.matcher(line)
        return if (matcher.find()) {
            matcher.group(1)?.toIntOrNull()
        } else {
            null
        }
    }
    
    private fun extractSmsc(line: String): String? {
        val matcher = SMSC_PATTERN.matcher(line)
        return if (matcher.find()) matcher.group(1) else null
    }
    
    /**
     * Parse signal jamming events - rapid signal loss correlation
     */
    private fun parseSignalJammingEvent(line: String, timestamp: Long): RilEvent.SignalJammingEvent? {
        // Signal loss
        if (SIGNAL_LOSS_PATTERN.matcher(line).find()) {
            return RilEvent.SignalJammingEvent(
                timestamp = timestamp,
                rawLine = line,
                signalDrop = 100, // Complete loss
                previousSignal = lastKnownSignal,
                currentSignal = -140, // No signal
                hasServiceLoss = true,
                hasAuthFailure = false
            )
        }
        
        // Rapid signal drop
        val dropMatcher = RAPID_SIGNAL_DROP_PATTERN.matcher(line)
        if (dropMatcher.find()) {
            val drop = dropMatcher.group(1)?.toIntOrNull() ?: return null
            return RilEvent.SignalJammingEvent(
                timestamp = timestamp,
                rawLine = line,
                signalDrop = kotlin.math.abs(drop),
                previousSignal = lastKnownSignal,
                currentSignal = lastKnownSignal?.minus(kotlin.math.abs(drop)),
                hasServiceLoss = false,
                hasAuthFailure = false
            )
        }
        
        return null
    }
    
    /**
     * Parse SS7 attack indicators
     */
    private fun parseSs7IndicatorEvent(line: String, timestamp: Long): RilEvent.Ss7IndicatorEvent? {
        // Registration reject
        val rejectMatcher = REGISTRATION_REJECT_PATTERN.matcher(line)
        if (rejectMatcher.find()) {
            return RilEvent.Ss7IndicatorEvent(
                timestamp = timestamp,
                rawLine = line,
                indicatorType = Ss7IndicatorType.LOCATION_UPDATE_REJECT,
                relatedCause = rejectMatcher.group(1),
                cid = extractCid(line) ?: lastKnownCid
            )
        }
        
        // Call forwarding activation
        if (CALL_FORWARD_PATTERN.matcher(line).find()) {
            return RilEvent.Ss7IndicatorEvent(
                timestamp = timestamp,
                rawLine = line,
                indicatorType = Ss7IndicatorType.CALL_FORWARD_ACTIVATED,
                relatedCause = null,
                cid = extractCid(line) ?: lastKnownCid,
                forwardingNumber = extractForwardingNumber(line)
            )
        }
        
        return null
    }
    
    /**
     * Extract the phone number that call forwarding was set to.
     * Matches various formats: +91-9876543210, +919876543210, 9876543210, +1 234 567 8901
     */
    private fun extractForwardingNumber(line: String): String? {
        // Pattern matches phone numbers following keywords like "to", "number", "dest", "forward"
        val phonePattern = Pattern.compile(
            "(?:to|number|dest|forward(?:ed)?(?:\\s*to)?)[=:\\s]*([+]?\\d[\\d\\s-]{6,}\\d)",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = phonePattern.matcher(line)
        if (matcher.find()) {
            // Normalize: remove spaces and dashes
            return matcher.group(1)?.replace(Regex("[\\s-]"), "")
        }
        
        // Fallback: try to find any phone number pattern in the line
        val fallbackPattern = Pattern.compile("([+]?\\d{10,15})")
        val fallbackMatcher = fallbackPattern.matcher(line)
        return if (fallbackMatcher.find()) {
            fallbackMatcher.group(1)
        } else null
    }
    
    /**
     * Parse rogue BTS detection events
     */
    private fun parseRogueBtsEvent(line: String, timestamp: Long): RilEvent.RogueBtsEvent? {
        // Unusual signal strength
        val signalMatcher = UNUSUAL_SIGNAL_PATTERN.matcher(line)
        if (signalMatcher.find()) {
            return RilEvent.RogueBtsEvent(
                timestamp = timestamp,
                rawLine = line,
                suspicionType = RogueBtsSuspicionType.UNUSUALLY_STRONG_SIGNAL,
                cid = extractCid(line),
                lac = extractLac(line),
                signalStrength = signalMatcher.group(1)?.toIntOrNull(),
                details = "Unusually strong signal detected"
            )
        }
        
        // LAC anomaly
        if (LAC_ANOMALY_PATTERN.matcher(line).find()) {
            return RilEvent.RogueBtsEvent(
                timestamp = timestamp,
                rawLine = line,
                suspicionType = RogueBtsSuspicionType.LAC_MISMATCH,
                cid = extractCid(line),
                lac = extractLac(line),
                signalStrength = null,
                details = "LAC mismatch detected"
            )
        }
        
        return null
    }
    
    private fun extractLac(line: String): Int? {
        val match = Regex("(?:LAC|lac)[=:\\s]*(\\d+)").find(line)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    // ==================== ADVANCED RADIO ATTACK PARSING ====================
    
    /**
     * Parse fake emergency alert events (CMAS/ETWS)
     * FALSE POSITIVE CONTROL: Only flag as suspicious if source doesn't match carrier,
     * or if alert structure is anomalous
     */
    private fun parseFakeEmergencyAlertEvent(line: String, timestamp: Long): RilEvent.FakeEmergencyAlertEvent? {
        if (EMERGENCY_ALERT_PATTERN.matcher(line).find() || ETWS_PATTERN.matcher(line).find()) {
            val alertType = extractEmergencyAlertType(line)
            // Only flag as suspicious if there are indicators of spoofing
            val isSuspicious = checkEmergencyAlertSuspicion(line)
            
            return RilEvent.FakeEmergencyAlertEvent(
                timestamp = timestamp,
                rawLine = line,
                alertType = alertType,
                messageId = extractMessageId(line),
                serialNumber = extractSerialNumber(line),
                cellId = extractCid(line),
                isSuspicious = isSuspicious,
                suspicionReason = if (isSuspicious) extractSuspicionReason(line) else null
            )
        }
        return null
    }
    
    private fun extractEmergencyAlertType(line: String): EmergencyAlertType {
        return when {
            line.contains("presidential", ignoreCase = true) -> EmergencyAlertType.PRESIDENTIAL_ALERT
            line.contains("extreme", ignoreCase = true) -> EmergencyAlertType.EXTREME_THREAT
            line.contains("severe", ignoreCase = true) -> EmergencyAlertType.SEVERE_THREAT
            line.contains("amber", ignoreCase = true) -> EmergencyAlertType.AMBER_ALERT
            line.contains("earthquake", ignoreCase = true) -> EmergencyAlertType.ETWS_EARTHQUAKE
            line.contains("tsunami", ignoreCase = true) -> EmergencyAlertType.ETWS_TSUNAMI
            line.contains("test", ignoreCase = true) -> EmergencyAlertType.TEST_ALERT
            else -> EmergencyAlertType.UNKNOWN
        }
    }
    
    private fun checkEmergencyAlertSuspicion(line: String): Boolean {
        // Suspicious if: malformed, from unexpected source, or during known attack window
        return line.contains("invalid", ignoreCase = true) ||
               line.contains("malformed", ignoreCase = true) ||
               line.contains("error", ignoreCase = true) ||
               (line.contains("presidential", ignoreCase = true) && line.contains("test", ignoreCase = true))
    }
    
    private fun extractMessageId(line: String): Int? {
        val match = Regex("(?:messageId|msgId)[=:\\s]*(\\d+)").find(line)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun extractSerialNumber(line: String): Int? {
        val match = Regex("(?:serialNumber|serial)[=:\\s]*(\\d+)").find(line)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun extractSuspicionReason(line: String): String {
        return when {
            line.contains("invalid", ignoreCase = true) -> "Invalid alert structure"
            line.contains("malformed", ignoreCase = true) -> "Malformed message"
            else -> "Anomalous alert"
        }
    }
    
    /**
     * Parse network downgrade events (5G→LTE→3G→2G)
     * FALSE POSITIVE CONTROL: Only flag if downgrade is forced (no signal reason)
     */
    private fun parseNetworkDowngradeEvent(line: String, timestamp: Long): RilEvent.NetworkDowngradeEvent? {
        val ratMatcher = RAT_CHANGE_PATTERN.matcher(line)
        if (ratMatcher.find()) {
            val fromNetwork = ratMatcher.group(1)?.uppercase() ?: return null
            val toNetwork = ratMatcher.group(2)?.uppercase() ?: return null
            
            // Determine if this is a legitimate downgrade
            val isDowngrade = isNetworkDowngrade(fromNetwork, toNetwork)
            
            // Check for correlation with recent barring event
            val isCorrelatedWithBarring = (timestamp - lastBarringTime) < BARRING_CORRELATION_WINDOW_MS
            
            val isForced = (!line.contains("signal", ignoreCase = true) && 
                          !line.contains("coverage", ignoreCase = true)) || 
                          isCorrelatedWithBarring
            
            val baseReason = extractDowngradeReason(line)
            val finalReason = if (isCorrelatedWithBarring) {
                "${baseReason ?: "unknown"} (Correlation: Preceded by Cell Barring)"
            } else {
                baseReason
            }
            
            // Only return event if it's actually a downgrade
            if (isDowngrade) {
                return RilEvent.NetworkDowngradeEvent(
                    timestamp = timestamp,
                    rawLine = line,
                    fromNetwork = fromNetwork,
                    toNetwork = toNetwork,
                    isForcedDowngrade = isForced,
                    cid = extractCid(line),
                    reason = finalReason
                )
            }
        }
        
        if (NETWORK_DOWNGRADE_PATTERN.matcher(line).find()) {
            val isCorrelatedWithBarring = (timestamp - lastBarringTime) < BARRING_CORRELATION_WINDOW_MS
            val baseReason = extractDowngradeReason(line)
            val finalReason = if (isCorrelatedWithBarring) {
                "${baseReason ?: "unknown"} (Correlation: Preceded by Cell Barring)"
            } else {
                baseReason
            }

            return RilEvent.NetworkDowngradeEvent(
                timestamp = timestamp,
                rawLine = line,
                fromNetwork = extractNetworkType(line, "from") ?: "UNKNOWN",
                toNetwork = extractNetworkType(line, "to") ?: "UNKNOWN",
                isForcedDowngrade = !line.contains("signal", ignoreCase = true) || isCorrelatedWithBarring,
                cid = extractCid(line),
                reason = finalReason
            )
        }
        return null
    }
    
    private fun isNetworkDowngrade(from: String, to: String): Boolean {
        val hierarchy = listOf("NR", "LTE", "UMTS", "WCDMA", "EDGE", "GPRS", "GSM")
        val fromIndex = hierarchy.indexOfFirst { from.contains(it, ignoreCase = true) }
        val toIndex = hierarchy.indexOfFirst { to.contains(it, ignoreCase = true) }
        return fromIndex >= 0 && toIndex >= 0 && toIndex > fromIndex
    }
    
    private fun extractNetworkType(line: String, direction: String): String? {
        val pattern = Regex("$direction\\s*(\\w+)", RegexOption.IGNORE_CASE)
        return pattern.find(line)?.groupValues?.get(1)
    }
    
    private fun extractDowngradeReason(line: String): String? {
        return when {
            line.contains("signal", ignoreCase = true) -> "weak_signal"
            line.contains("coverage", ignoreCase = true) -> "coverage_loss"
            line.contains("fallback", ignoreCase = true) -> "fallback"
            line.contains("redirect", ignoreCase = true) -> "redirect"
            else -> null
        }
    }
    
    /**
     * Parse WiFi Calling anomaly events
     */
    private fun parseWifiCallingAnomalyEvent(line: String, timestamp: Long): RilEvent.WifiCallingAnomalyEvent? {
        if (WIFI_CALLING_PATTERN.matcher(line).find()) {
            val anomalyType = extractWifiCallingAnomalyType(line)
            val ePdgMatcher = EPDG_PATTERN.matcher(line)
            
            return RilEvent.WifiCallingAnomalyEvent(
                timestamp = timestamp,
                rawLine = line,
                anomalyType = anomalyType,
                ePdgAddress = if (ePdgMatcher.find()) ePdgMatcher.group(1) else null,
                sipError = extractSipError(line),
                details = extractWifiCallingDetails(line)
            )
        }
        return null
    }
    
    private fun extractWifiCallingAnomalyType(line: String): WifiCallingAnomalyType {
        return when {
            line.contains("certificate", ignoreCase = true) -> WifiCallingAnomalyType.EPDG_CERTIFICATE_INVALID
            line.contains("srtp", ignoreCase = true) -> WifiCallingAnomalyType.SRTP_STRIPPED
            line.contains("ipsec", ignoreCase = true) -> WifiCallingAnomalyType.IPSEC_DOWNGRADE
            line.contains("handover", ignoreCase = true) -> WifiCallingAnomalyType.UNEXPECTED_HANDOVER
            else -> WifiCallingAnomalyType.UNKNOWN
        }
    }
    
    private fun extractWifiCallingDetails(line: String): String? {
        return when {
            line.contains("error", ignoreCase = true) -> "WiFi calling error"
            line.contains("failed", ignoreCase = true) -> "WiFi calling failure"
            else -> null
        }
    }
    
    private fun extractSipError(line: String): Int? {
        val match = Regex("(?:SIP|sip).*(?:error|code|status)[=:\\s]*(\\d{3})").find(line)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    /**
     * Parse TMSI exposure events - identity tracking indicators
     */
    private fun parseTmsiExposureEvent(line: String, timestamp: Long): RilEvent.TmsiExposureEvent? {
        if (TMSI_PATTERN.matcher(line).find() || IMSI_PAGING_PATTERN.matcher(line).find()) {
            val exposureType = extractTmsiExposureType(line)
            val isNull = line.contains("null", ignoreCase = true) || 
                        line.contains("empty", ignoreCase = true)
            
            return RilEvent.TmsiExposureEvent(
                timestamp = timestamp,
                rawLine = line,
                exposureType = exposureType,
                isNullTmsi = isNull,
                tmsiValue = extractTmsiValue(line),
                cid = extractCid(line)
            )
        }
        return null
    }
    
    private fun extractTmsiExposureType(line: String): TmsiExposureType {
        return when {
            line.contains("null", ignoreCase = true) -> TmsiExposureType.NULL_TMSI_ASSIGNED
            line.contains("reuse", ignoreCase = true) -> TmsiExposureType.TMSI_REUSE_FORCED
            line.contains("SUPI", ignoreCase = true) -> TmsiExposureType.SUPI_REQUESTED
            line.contains("GUTI", ignoreCase = true) -> TmsiExposureType.GUTI_EXPOSURE
            line.contains("paging", ignoreCase = true) && line.contains("IMSI", ignoreCase = true) -> 
                TmsiExposureType.IMSI_PAGING
            else -> TmsiExposureType.UNKNOWN
        }
    }
    
    private fun extractTmsiValue(line: String): String? {
        val match = Regex("(?:TMSI|GUTI)[=:\\s]*([0-9a-fA-F]+)").find(line)
        return match?.groupValues?.get(1)
    }
    
    /**
     * Parse paging abuse events - excessive paging indicates tracking
     * FALSE POSITIVE CONTROL: Only flag if rate is abnormally high
     */
    private var pagingCount = 0
    private var lastPagingTime = 0L
    private val PAGING_WINDOW_MS = 60000L // 1 minute
    private val PAGING_THRESHOLD = 10 // More than 10 pages/minute is suspicious
    
    private fun parsePagingAbuseEvent(line: String, timestamp: Long): RilEvent.PagingAbuseEvent? {
        // ONLY match RIL/RRC paging patterns - NOT generic "paging" word
        // Must have network/RIL context to avoid UI paging false positives
        val isRilPaging = PAGING_PATTERN.matcher(line).find() && 
            (line.contains("RIL", ignoreCase = true) || 
             line.contains("RRC", ignoreCase = true) || 
             line.contains("NAS", ignoreCase = true) ||
             line.contains("Radio", ignoreCase = true))
        
        if (!isRilPaging) return null
        
        // Reset counter if window expired
        if (timestamp - lastPagingTime > PAGING_WINDOW_MS) {
            pagingCount = 0
        }
        pagingCount++
        lastPagingTime = timestamp
            
            val isAbnormal = pagingCount > PAGING_THRESHOLD
            
            // Only return event if rate is abnormal
            if (isAbnormal) {
                return RilEvent.PagingAbuseEvent(
                    timestamp = timestamp,
                    rawLine = line,
                    pagingCount = pagingCount,
                    timeWindowMs = PAGING_WINDOW_MS,
                    isAbnormalRate = true,
                    cellId = extractCid(line)
                )
            }

        return null
    }
    
    // State for correlation analysis
    private var lastBarringTime = 0L
    private val BARRING_CORRELATION_WINDOW_MS = 30000L // 30 seconds (increased from 15s for slower re-camping)

    /**
     * Parse cell manipulation events
     * FALSE POSITIVE CONTROL: Only flag if signal delta is suspicious or PART OF CORRELATION
     */
    private fun parseCellManipulationEvent(line: String, timestamp: Long): RilEvent.CellManipulationEvent? {
        if (CELL_RESELECTION_PATTERN.matcher(line).find() || CELL_BARRED_PATTERN.matcher(line).find()) {
            val manipType = extractCellManipulationType(line)
            val signalDelta = extractSignalDelta(line)
            
            // Check for barring specific logic
            var isSuspicious = signalDelta != null && signalDelta > 20
            
            if (manipType == CellManipulationType.CELL_BARRING_ABUSE) {
                // Record timestamp for future downgrade correlation
                lastBarringTime = timestamp
                
                // Do NOT flag standalone barring as suspicious anymore
                // It requires a subsequent downgrade to be considered an attack
                // Exception: if log specifically says "blocked" which is harsher than "barred"
                if (line.contains("blocked", ignoreCase = true)) {
                    isSuspicious = true
                }
            } else if (line.contains("barred", ignoreCase = true)) {
                 // Fallback for reselection that mentions barring
                 lastBarringTime = timestamp
            }
            
            return RilEvent.CellManipulationEvent(
                timestamp = timestamp,
                rawLine = line,
                manipulationType = manipType,
                fromCid = extractFromCid(line),
                toCid = extractCid(line),
                signalDelta = signalDelta,
                isSuspicious = isSuspicious
            )
        }
        return null
    }
    
    private fun extractCellManipulationType(line: String): CellManipulationType {
        return when {
            line.contains("reselect", ignoreCase = true) -> CellManipulationType.FORCED_RESELECTION
            line.contains("barred", ignoreCase = true) -> CellManipulationType.CELL_BARRING_ABUSE
            line.contains("redirect", ignoreCase = true) -> CellManipulationType.REDIRECT_INDICATION
            line.contains("handover", ignoreCase = true) -> CellManipulationType.HANDOVER_COMMAND_ABUSE
            line.contains("release", ignoreCase = true) -> CellManipulationType.CONNECTION_RELEASE
            else -> CellManipulationType.UNKNOWN
        }
    }
    
    private fun extractFromCid(line: String): Int? {
        val match = Regex("from.*(?:CID|cellId)[=:\\s]*(\\d+)").find(line)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun extractSignalDelta(line: String): Int? {
        val match = Regex("(?:delta|diff|change)[=:\\s]*(\\d+)").find(line)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    /**
     * Parse network redirect events (LTE redirect attack)
     * FALSE POSITIVE CONTROL: Only flag dangerous downgrades (to 2G)
     */
    private fun parseNetworkRedirectEvent(line: String, timestamp: Long): RilEvent.NetworkRedirectEvent? {
        val redirectMatcher = LTE_REDIRECT_PATTERN.matcher(line)
        if (redirectMatcher.find()) {
            val targetRat = redirectMatcher.group(1)?.uppercase()
            
            val redirectType = when {
                targetRat?.contains("GSM") == true || targetRat?.contains("EDGE") == true -> 
                    NetworkRedirectType.LTE_TO_GSM
                targetRat?.contains("UMTS") == true || targetRat?.contains("WCDMA") == true -> 
                    NetworkRedirectType.LTE_TO_UMTS
                else -> NetworkRedirectType.UNKNOWN
            }
            
            // Only flag high-severity redirects (to 2G)
            val isSuspicious = redirectType.severity >= 4
            
            return RilEvent.NetworkRedirectEvent(
                timestamp = timestamp,
                rawLine = line,
                redirectType = redirectType,
                targetRat = targetRat,
                originalRat = extractOriginalRat(line),
                reason = extractRedirectReason(line),
                isSuspicious = isSuspicious
            )
        }
        return null
    }
    
    private fun extractOriginalRat(line: String): String? {
        val match = Regex("from\\s*(\\w+)", RegexOption.IGNORE_CASE).find(line)
        return match?.groupValues?.get(1)
    }
    
    private fun extractRedirectReason(line: String): String? {
        return when {
            line.contains("congestion", ignoreCase = true) -> "congestion"
            line.contains("load", ignoreCase = true) -> "load_balancing"
            else -> null
        }
    }
    
    /**
     * Parse VoLTE DoS events
     * FALSE POSITIVE CONTROL: Only flag if error count is high (>5 in window)
     */
    private var sipErrorCount = 0
    private var lastSipErrorTime = 0L
    private val SIP_ERROR_WINDOW_MS = 30000L // 30 seconds
    private val SIP_ERROR_THRESHOLD = 5
    
    private fun parseVoLteDoSEvent(line: String, timestamp: Long): RilEvent.VoLteDoSEvent? {
        val sipMatcher = SIP_ERROR_FLOOD_PATTERN.matcher(line)
        if (sipMatcher.find() || DEREGISTRATION_PATTERN.matcher(line).find()) {
            // Track error rate
            if (timestamp - lastSipErrorTime > SIP_ERROR_WINDOW_MS) {
                sipErrorCount = 0
            }
            sipErrorCount++
            lastSipErrorTime = timestamp
            
            // Only flag if error threshold exceeded
            if (sipErrorCount >= SIP_ERROR_THRESHOLD || 
                DEREGISTRATION_PATTERN.matcher(line).find()) {
                return RilEvent.VoLteDoSEvent(
                    timestamp = timestamp,
                    rawLine = line,
                    dosType = extractVoLteDoSType(line),
                    sipCode = extractSipError(line),
                    errorCount = sipErrorCount,
                    timeWindowMs = SIP_ERROR_WINDOW_MS
                )
            }
        }
        return null
    }
    
    private fun extractVoLteDoSType(line: String): VoLteDoSType {
        return when {
            line.contains("deregist", ignoreCase = true) -> VoLteDoSType.DEREGISTRATION_ATTACK
            line.contains("unregist", ignoreCase = true) -> VoLteDoSType.DEREGISTRATION_ATTACK
            line.contains("terminated", ignoreCase = true) -> VoLteDoSType.DEREGISTRATION_ATTACK
            line.contains("removed", ignoreCase = true) -> VoLteDoSType.DEREGISTRATION_ATTACK
            line.contains("flood", ignoreCase = true) -> VoLteDoSType.SIP_FLOOD
            line.contains("invite", ignoreCase = true) -> VoLteDoSType.INVITE_STORM
            line.contains("loop", ignoreCase = true) -> VoLteDoSType.REGISTRATION_LOOP
            else -> VoLteDoSType.UNKNOWN
        }
    }
    
    /**
     * Parse invalid PLMN events
     * FALSE POSITIVE CONTROL: Check if PLMN is actually suspicious
     */
    private fun parseInvalidPlmnEvent(line: String, timestamp: Long): RilEvent.InvalidPlmnEvent? {
        val plmnMatcher = PLMN_REJECT_PATTERN.matcher(line)
        if (plmnMatcher.find()) {
            val mcc = plmnMatcher.group(1)?.toIntOrNull()
            val mnc = plmnMatcher.group(2)?.toIntOrNull()
            val rejectionType = extractPlmnRejectionType(line)
            
            // Only flag if truly suspicious (not normal roaming rejection)
            val isSuspicious = rejectionType.severity >= 4
            
            return RilEvent.InvalidPlmnEvent(
                timestamp = timestamp,
                rawLine = line,
                plmnId = "${mcc ?: "?"}-${mnc ?: "?"}",
                mcc = mcc,
                mnc = mnc,
                rejectionType = rejectionType,
                isSuspicious = isSuspicious
            )
        }
        return null
    }
    
    private fun extractPlmnRejectionType(line: String): PlmnRejectionType {
        return when {
            line.contains("forbidden", ignoreCase = true) -> PlmnRejectionType.FORBIDDEN_PLMN
            line.contains("unknown", ignoreCase = true) -> PlmnRejectionType.UNKNOWN_PLMN
            line.contains("roaming", ignoreCase = true) -> PlmnRejectionType.ROAMING_NOT_ALLOWED
            line.contains("mismatch", ignoreCase = true) -> PlmnRejectionType.PLMN_MISMATCH
            else -> PlmnRejectionType.UNKNOWN
        }
    }


    


    // ==================== POST-ATTACK BEHAVIOR PARSING ====================

    
    /**
     * Parse ContentProvider access events (contact/call log scraping)
     */
    private fun parseContentAccessEvent(line: String, timestamp: Long): RilEvent.ContentAccessEvent? {
        // Contacts access
        if (CONTACTS_ACCESS_PATTERN.matcher(line).find()) {
            return RilEvent.ContentAccessEvent(
                timestamp = timestamp,
                rawLine = line,
                contentType = ContentType.CONTACTS,
                operation = extractOperation(line),
                callingPackage = extractPackage(line)
            )
        }
        
        // Call log access
        if (CALL_LOG_ACCESS_PATTERN.matcher(line).find()) {
            return RilEvent.ContentAccessEvent(
                timestamp = timestamp,
                rawLine = line,
                contentType = ContentType.CALL_LOG,
                operation = extractOperation(line),
                callingPackage = extractPackage(line)
            )
        }
        
        // SMS access
        if (SMS_ACCESS_PATTERN.matcher(line).find()) {
            return RilEvent.ContentAccessEvent(
                timestamp = timestamp,
                rawLine = line,
                contentType = ContentType.SMS,
                operation = extractOperation(line),
                callingPackage = extractPackage(line)
            )
        }
        
        return null
    }
    
    /**
     * Parse app launch events
     */
    private fun parseAppLaunchEvent(line: String, timestamp: Long): RilEvent.AppLaunchEvent? {
        // Activity start
        var matcher = ACTIVITY_START_PATTERN.matcher(line)
        if (matcher.find()) {
            return RilEvent.AppLaunchEvent(
                timestamp = timestamp,
                rawLine = line,
                packageName = matcher.group(1) ?: "",
                activityName = matcher.group(2),
                launchReason = extractLaunchReason(line)
            )
        }
        
        // Process start
        matcher = PROCESS_START_PATTERN.matcher(line)
        if (matcher.find()) {
            return RilEvent.AppLaunchEvent(
                timestamp = timestamp,
                rawLine = line,
                packageName = matcher.group(1) ?: "",
                activityName = null,
                launchReason = "process_start"
            )
        }
        
        return null
    }
    
    /**
     * Parse network activity events
     */
    private fun parseNetworkActivityEvent(line: String, timestamp: Long): RilEvent.NetworkActivityEvent? {
        // Socket connections
        var matcher = SOCKET_CONNECT_PATTERN.matcher(line)
        if (matcher.find()) {
            return RilEvent.NetworkActivityEvent(
                timestamp = timestamp,
                rawLine = line,
                host = matcher.group(1),
                port = matcher.group(2)?.toIntOrNull(),
                protocol = "TCP",
                direction = "outbound",
                bytes = null
            )
        }
        
        // HTTP requests
        matcher = HTTP_REQUEST_PATTERN.matcher(line)
        if (matcher.find()) {
            return RilEvent.NetworkActivityEvent(
                timestamp = timestamp,
                rawLine = line,
                host = extractHost(line),
                port = 443, // Assume HTTPS
                protocol = "HTTP",
                direction = "outbound",
                bytes = null
            )
        }
        
        // Data transfer stats
        matcher = DATA_TRANSFER_PATTERN.matcher(line)
        if (matcher.find()) {
            return RilEvent.NetworkActivityEvent(
                timestamp = timestamp,
                rawLine = line,
                host = null,
                port = null,
                protocol = null,
                direction = if (line.contains("tx", ignoreCase = true)) "outbound" else "inbound",
                bytes = matcher.group(1)?.toLongOrNull()
            )
        }
        
        return null
    }
    
    /**
     * Parse settings change events
     */
    private fun parseSettingsChangeEvent(line: String, timestamp: Long): RilEvent.SettingsChangeEvent? {
        // APN changes
        if (APN_CHANGE_PATTERN.matcher(line).find()) {
            return RilEvent.SettingsChangeEvent(
                timestamp = timestamp,
                rawLine = line,
                settingType = SettingType.APN,
                oldValue = null,
                newValue = extractSettingValue(line),
                changedBy = extractPackage(line)
            )
        }
        
        // Proxy changes
        if (PROXY_CHANGE_PATTERN.matcher(line).find()) {
            return RilEvent.SettingsChangeEvent(
                timestamp = timestamp,
                rawLine = line,
                settingType = SettingType.PROXY,
                oldValue = null,
                newValue = extractSettingValue(line),
                changedBy = extractPackage(line)
            )
        }
        
        // VPN changes
        if (VPN_CHANGE_PATTERN.matcher(line).find()) {
            return RilEvent.SettingsChangeEvent(
                timestamp = timestamp,
                rawLine = line,
                settingType = SettingType.VPN,
                oldValue = null,
                newValue = if (line.contains("connect", ignoreCase = true)) "connected" else "disconnected",
                changedBy = extractPackage(line)
            )
        }
        
        return null
    }
    
    /**
     * Parse location update events
     */
    private fun parseLocationUpdateEvent(line: String, timestamp: Long): RilEvent.LocationUpdateEvent? {
        // Location update with coordinates
        var matcher = LOCATION_UPDATE_PATTERN.matcher(line)
        if (matcher.find()) {
            return RilEvent.LocationUpdateEvent(
                timestamp = timestamp,
                rawLine = line,
                latitude = matcher.group(1)?.toDoubleOrNull(),
                longitude = matcher.group(2)?.toDoubleOrNull(),
                accuracy = extractAccuracy(line),
                provider = extractProvider(line),
                requestedBy = extractPackage(line)
            )
        }
        
        // Location request
        matcher = LOCATION_REQUEST_PATTERN.matcher(line)
        if (matcher.find()) {
            return RilEvent.LocationUpdateEvent(
                timestamp = timestamp,
                rawLine = line,
                latitude = null,
                longitude = null,
                accuracy = null,
                provider = matcher.group(1),
                requestedBy = extractPackage(line)
            )
        }
        
        return null
    }

    private fun parsePrivacyOrSensorEvent(line: String, timestamp: Long): RilEvent? {
        // Privacy Leaks - A-GNSS IMSI
        if (AGNSS_IMSI_PATTERN.matcher(line).find()) {
            return RilEvent.PrivacyLeakEvent(
                timestamp = timestamp,
                rawLine = line,
                leakType = "AGNSS_IMSI_LEAK",
                details = "Device sent IMSI to SUPL server"
            )
        }
        
        // Privacy Leaks - Cleartext Traffic
        if (CLEARTEXT_TRAFFIC_PATTERN.matcher(line).find()) {
            val domain = extractHost(line) ?: "unknown host"
            return RilEvent.PrivacyLeakEvent(
                timestamp = timestamp,
                rawLine = line,
                leakType = "CLEARTEXT_TRAFFIC",
                details = "Unencrypted traffic allowed to $domain"
            )
        }
        
        // Sensor Abuse - Microphone
        if (RADIO_AUDIO_ACCESS_PATTERN.matcher(line).find()) {
            return RilEvent.SensorAbuseEvent(
                timestamp = timestamp,
                rawLine = line,
                sensorType = "MICROPHONE",
                process = "Radio/RIL"
            )
        }
        
        // Sensor Abuse - Unknown/Other
        if (UNKNOWN_SENSOR_ACCESS_PATTERN.matcher(line).find()) {
            return RilEvent.SensorAbuseEvent(
                timestamp = timestamp,
                rawLine = line,
                sensorType = "UNKNOWN_SENSOR",
                process = "Radio/RIL"
            )
        }
        
        return null
    }
    
    // ==================== HELPER METHODS FOR POST-ATTACK PARSING ====================
    
    private fun extractOperation(line: String): String {
        return when {
            line.contains("query", ignoreCase = true) -> "query"
            line.contains("insert", ignoreCase = true) -> "insert"
            line.contains("update", ignoreCase = true) -> "update"
            line.contains("delete", ignoreCase = true) -> "delete"
            else -> "access"
        }
    }
    
    private fun extractPackage(line: String): String? {
        val match = Regex("(?:package|pkg|caller|from)[=:\\s]*([\\w.]+)").find(line)
        return match?.groupValues?.get(1)
    }
    
    private fun extractLaunchReason(line: String): String? {
        val match = Regex("(?:reason|flag)[=:\\s]*(\\w+)").find(line)
        return match?.groupValues?.get(1)
    }
    
    private fun extractHost(line: String): String? {
        val match = Regex("https?://([\\w.]+)").find(line)
        return match?.groupValues?.get(1)
    }
    
    private fun extractSettingValue(line: String): String? {
        val match = Regex("(?:value|to)[=:\\s]*([\\w.:-]+)").find(line)
        return match?.groupValues?.get(1)
    }
    
    private fun extractAccuracy(line: String): Float? {
        val match = Regex("(?:accuracy|acc)[=:\\s]*([\\d.]+)").find(line)
        return match?.groupValues?.get(1)?.toFloatOrNull()
    }
    
    private fun extractProvider(line: String): String? {
        return when {
            line.contains("gps", ignoreCase = true) -> "gps"
            line.contains("network", ignoreCase = true) -> "network"
            line.contains("fused", ignoreCase = true) -> "fused"
            line.contains("passive", ignoreCase = true) -> "passive"
            else -> null
        }
    }
    
    /**
     * Reset parser state (call when switching cells or on app restart)
     */
    fun reset() {
        lastKnownCipher = null
        lastKnownCid = null
    }
}
