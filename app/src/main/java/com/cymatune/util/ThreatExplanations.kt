package com.cymatune.util

/**
 * Provides "Learn More" explanations for all threat types
 * Each explanation includes:
 * - What the threat means
 * - Why it was detected
 * - What the user should do
 */
object ThreatExplanations {
    
    data class ThreatInfo(
        val title: String,
        val shortDescription: String,
        val detailedExplanation: String,
        val possibleCauses: List<String>,
        val recommendedActions: List<String>,
        val falsePositiveNote: String? = null,
        val aggregationNote: String? = "This alert may be verified by multiple sensors (e.g. Tower Scanner and Logcat). If so, it is marked as high confidence."
    )
    
    fun getExplanation(threatType: String): ThreatInfo {
        return when {
            threatType.contains("Protocol", ignoreCase = true) -> protocolAnomaly
            threatType.contains("IMSI", ignoreCase = true) -> imsiCatcher
            threatType.contains("Silent SMS", ignoreCase = true) -> silentSms
            threatType.contains("Radius", ignoreCase = true) -> radiusAnomaly
            threatType.contains("Cell Isolation", ignoreCase = true) -> cellIsolation
            threatType.contains("Rogue", ignoreCase = true) -> rogueTower
            threatType.contains("Location", ignoreCase = true) -> locationAnomaly
            threatType.contains("Privacy", ignoreCase = true) -> privacyWarning
            threatType.contains("Downgrade", ignoreCase = true) -> downgradeAttack
            threatType.contains("Neighbor", ignoreCase = true) -> neighborAnomaly
            else -> genericAlert
        }
    }
    
    val protocolAnomaly = ThreatInfo(
        title = "Protocol Anomaly Detected",
        shortDescription = "Your connection is using weak or no encryption",
        detailedExplanation = """
            This alert indicates that your cellular connection is using weak security protocols.
            
            The most common issue is LTE_EEA0 (also known as "null cipher") which means 
            NO ENCRYPTION is being used for your data transmission.
            
            In a normal, secure connection, your phone and the tower negotiate strong 
            encryption (EEA1 or EEA2). When EEA0 is used, anyone with the right equipment 
            can intercept your calls, texts, and data.
            
            NOTE: In some regions or carriers, null encryption (EEA0) is common policy. 
            However, this does NOT reduce the severity of the threat; your data remains 
            unprotected and easily interceptable.
        """.trimIndent(),
        possibleCauses = listOf(
            "IMSI Catcher/Stingray device forcing encryption downgrade",
            "Carrier using null cipher in certain areas (sometimes legitimate)",
            "Legacy equipment in the area",
            "Active man-in-the-middle attack"
        ),
        recommendedActions = listOf(
            "Avoid sensitive communications (banking, passwords) on this connection",
            "Switch to WiFi with VPN for sensitive activities",
            "Move to a different location to connect to another tower",
            "Contact your carrier if this persists in the same location"
        ),
        falsePositiveNote = "Some carriers legitimately use EEA0 in low-risk areas or for VoLTE calls. If you see this consistently on the same tower, it may be carrier policy."
    )
    
    val imsiCatcher = ThreatInfo(
        title = "Possible IMSI Catcher",
        shortDescription = "A device may be impersonating a cell tower",
        detailedExplanation = """
            An IMSI Catcher (also known as "Stingray") is a device that pretends to be a 
            legitimate cell tower to intercept your phone's communications.
            
            These devices can:
            - Track your physical location
            - Intercept calls and text messages
            - Collect your IMSI (unique phone identifier)
            - Force your phone to use weak encryption
        """.trimIndent(),
        possibleCauses = listOf(
            "Law enforcement surveillance equipment",
            "Corporate espionage",
            "Malicious actor targeting the area",
            "Testing/research equipment"
        ),
        recommendedActions = listOf(
            "Leave the area if possible",
            "Enable airplane mode for sensitive conversations",
            "Use end-to-end encrypted messaging apps (Signal, WhatsApp)",
            "Report suspicious activity to authorities"
        )
    )
    
    val silentSms = ThreatInfo(
        title = "Silent SMS Detected",
        shortDescription = "Your phone received an invisible 'ping' message",
        detailedExplanation = """
            Silent SMS (also called "stealth SMS" or "Type-0 SMS") is a special message 
            that reaches your phone without any notification or visible trace.
            
            These messages are often used to:
            - Track your location by seeing which tower handles the message
            - Confirm your phone is active and in a specific area
            - Wake up your phone for surveillance purposes
        """.trimIndent(),
        possibleCauses = listOf(
            "Law enforcement location tracking",
            "Carrier network testing",
            "Targeted surveillance",
            "Corporate location tracking"
        ),
        recommendedActions = listOf(
            "Note the time and location of detection",
            "If frequent, consider consulting with a security professional",
            "Use encrypted messaging apps",
            "Consider using a secondary device for sensitive communications"
        )
    )
    
    val radiusAnomaly = ThreatInfo(
        title = "Radius/Distance Anomaly",
        shortDescription = "Tower signal doesn't match expected distance",
        detailedExplanation = """
            The signal strength from this tower doesn't match what we'd expect based on 
            distance calculations. This can indicate a fake tower that's much closer than 
            a legitimate tower would be.
            
            Legitimate towers have consistent signal patterns. An unusually strong signal 
            from an apparently distant tower, or weak signal from a nearby tower, can 
            indicate tampering.
        """.trimIndent(),
        possibleCauses = listOf(
            "Mobile IMSI catcher (closer than real tower)",
            "Tower with unusual antenna configuration",
            "Environmental factors (buildings, terrain)",
            "GPS/location accuracy issues"
        ),
        recommendedActions = listOf(
            "Check if you're in an unusual location (tunnel, basement)",
            "Compare with other alerts to see if it's part of a pattern",
            "Move to a different area and observe if the anomaly persists"
        ),
        falsePositiveNote = "Signal propagation can be affected by buildings, terrain, and weather. A single radius anomaly may not indicate a threat."
    )
    
    val cellIsolation = ThreatInfo(
        title = "Cell Isolation Attack",
        shortDescription = "Your phone is being forced to connect to only one tower",
        detailedExplanation = """
            Normally, your phone can see multiple cell towers and will switch between them 
            for optimal signal. Cell isolation is when your phone is forced to connect to 
            a single tower, often through jamming other frequencies.
            
            This is a technique used by IMSI catchers to ensure your phone connects only 
            to the malicious device.
        """.trimIndent(),
        possibleCauses = listOf(
            "IMSI catcher jamming other frequencies",
            "Very rural area with only one tower",
            "Building interference blocking other signals",
            "Carrier network issues"
        ),
        recommendedActions = listOf(
            "Check if you normally see multiple towers in this area",
            "Try restarting your phone",
            "Move to a different location",
            "Enable airplane mode if conducting sensitive activities"
        )
    )
    
    val rogueTower = ThreatInfo(
        title = "Rogue Tower Detected",
        shortDescription = "This tower shows signs of being illegitimate",
        detailedExplanation = """
            A rogue tower is a cell tower that doesn't match the expected characteristics 
            of legitimate infrastructure. This could be a mobile IMSI catcher or an 
            incorrectly configured piece of equipment.
            
            Signs include unusual identifiers, unexpected location, missing from known 
            tower databases, or suspicious behavior patterns.
        """.trimIndent(),
        possibleCauses = listOf(
            "IMSI Catcher/Stingray",
            "Femtocell (small personal cell) misconfigured",
            "New tower not yet in databases",
            "Carrier equipment testing"
        ),
        recommendedActions = listOf(
            "Avoid making calls or sending texts",
            "Don't access banking or sensitive accounts",
            "Move away from the area",
            "Report to your carrier"
        )
    )
    
    val locationAnomaly = ThreatInfo(
        title = "Location Anomaly",
        shortDescription = "Tower location doesn't match expected position",
        detailedExplanation = """
            The reported location of this cell tower doesn't match our database records 
            or logical expectations. A legitimate tower doesn't move, so this could 
            indicate a mobile surveillance device.
        """.trimIndent(),
        possibleCauses = listOf(
            "Mobile IMSI catcher",
            "Database inaccuracy",
            "New tower installation",
            "GPS precision issues"
        ),
        recommendedActions = listOf(
            "Check other alert indicators",
            "If traveling, this may be expected",
            "Report persistent issues"
        )
    )
    
    val privacyWarning = ThreatInfo(
        title = "Privacy Warning",
        shortDescription = "Your connection privacy may be compromised",
        detailedExplanation = """
            This is a general privacy warning that indicates something about your 
            connection isn't private. This could be encryption issues, unusual tower 
            behavior, or detected surveillance attempts.
        """.trimIndent(),
        possibleCauses = listOf(
            "Weak encryption",
            "Unusual network behavior",
            "Possible eavesdropping"
        ),
        recommendedActions = listOf(
            "Use VPN for sensitive activities",
            "Prefer encrypted messaging apps",
            "Check other specific alerts for details"
        )
    )
    
    val downgradeAttack = ThreatInfo(
        title = "Protocol Downgrade Attack",
        shortDescription = "Someone is forcing your phone to use weaker security",
        detailedExplanation = """
            Your phone attempted to use strong 4G/LTE security, but was forced to 
            downgrade to weaker 2G/3G protocols. This is a common attack technique 
            because older protocols are easier to break.
            
            Attackers can force this downgrade by jamming 4G signals, making your phone 
            fall back to less secure networks.
            
            NOTE: In some regions/carriers, 2G fallback might occur genuinely due to poor 
            coverage. However, it still significantly compromises your connection security.
        """.trimIndent(),
        possibleCauses = listOf(
            "IMSI catcher forcing 2G fallback",
            "Poor 4G coverage in the area",
            "Building interference",
            "Network congestion"
        ),
        recommendedActions = listOf(
            "Enable 4G/LTE only in phone settings if available",
            "Avoid sensitive communications until resolved",
            "Move to an area with better coverage"
        )
    )
    
    val neighborAnomaly = ThreatInfo(
        title = "Neighbor Tower Anomaly",
        shortDescription = "Unusual changes in nearby towers detected",
        detailedExplanation = """
            Your phone regularly sees multiple neighboring towers. When these suddenly 
            disappear or change dramatically, it could indicate jamming or an isolation 
            attack where you're being forced to connect to a specific tower.
        """.trimIndent(),
        possibleCauses = listOf(
            "Selective jamming attack",
            "Network maintenance",
            "Moving through an area with poor coverage",
            "Environmental interference"
        ),
        recommendedActions = listOf(
            "Check if nearby towers return to normal",
            "Correlate with other alerts",
            "Move to a different location to test"
        )
    )
    
    val genericAlert = ThreatInfo(
        title = "Security Alert",
        shortDescription = "A potential security issue was detected",
        detailedExplanation = """
            This is a general security alert that something unusual was detected in 
            your cellular connection. Check the specific details in the alert message 
            for more information.
        """.trimIndent(),
        possibleCauses = listOf(
            "Various network anomalies",
            "Unusual tower behavior",
            "Security protocol issues"
        ),
        recommendedActions = listOf(
            "Review the specific alert details",
            "Use encrypted apps for sensitive communication",
            "Monitor for additional alerts"
        )
    )
}
