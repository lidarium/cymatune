package com.cymatune.db

/**
 * SMSMessageType - Enum defining different types of SMS messages
 * for classification and analysis in fake tower detection.
 */
enum class SMSMessageType {
    // Standard message types
    TEXT,           // Normal text message
    DATA,           // Data message with binary content
    SILENT,         // Silent SMS (no content, used for location tracking)
    PDU,            // Protocol Data Unit message
    
    // Special message types
    EMERGENCY,      // Emergency alert message
    FLASH,          // Flash message (long text)
    
    // Unknown or malformed messages
    UNKNOWN,        // Unable to classify
    MALFORMED       // Malformed or corrupted message
}