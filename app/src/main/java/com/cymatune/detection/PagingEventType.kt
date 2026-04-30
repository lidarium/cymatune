package com.cymatune.detection

import com.cymatune.db.PagingEventType

/**
 * Detection-specific paging event types that extend the database types.
 * These represent various types of network paging that may indicate
 * fake tower activity or IMSI catchers.
 */
enum class DetectionPagingEventType {
    // Standard paging types
    PAGE_MS_IDENTITY_REQUEST,      // Request for mobile station identity
    PAGE_AUTHENTICATION_REQUEST,   // Authentication challenge
    PAGE_LOCATION_UPDATE,          // Location area update
    PAGE_IMSI_CATCHER_DETECTION,   // Detected IMSI catcher paging
    
    // Emergency and special paging
    PAGE_EMERGENCY_CALL,           // Emergency service paging
    PAGE_PRIORITY_ACCESS,          // Priority access paging
    PAGE_SYSTEM_INFORMATION,       // System information broadcast
    
    // Suspicious paging patterns
    PAGE_FREQUENCY_SCAN,           // Frequency scanning behavior
    PAGE_UNUSUAL_TIMING,           // Abnormal timing patterns
    PAGE_DUPLICATE_PAGING,         // Duplicate paging attempts
    PAGE_UNIDIRECTIONAL,           // One-way paging only
    
    // Unknown or malformed paging
    PAGE_UNKNOWN,
    PAGE_MALFORMED,
    PAGE_TIMEOUT
}

/**
 * Map detection paging event types to database paging event types
 */
fun DetectionPagingEventType.toDatabaseType(): PagingEventType = when (this) {
    DetectionPagingEventType.PAGE_MS_IDENTITY_REQUEST -> PagingEventType.REGISTRATION_REQUEST
    DetectionPagingEventType.PAGE_AUTHENTICATION_REQUEST -> PagingEventType.AUTHENTICATION_REQUEST
    DetectionPagingEventType.PAGE_LOCATION_UPDATE -> PagingEventType.LOCATION_UPDATE
    DetectionPagingEventType.PAGE_IMSI_CATCHER_DETECTION -> PagingEventType.SERVICE_REQUEST
    DetectionPagingEventType.PAGE_EMERGENCY_CALL -> PagingEventType.SERVICE_REQUEST
    DetectionPagingEventType.PAGE_PRIORITY_ACCESS -> PagingEventType.SERVICE_REQUEST
    DetectionPagingEventType.PAGE_SYSTEM_INFORMATION -> PagingEventType.SERVICE_REQUEST
    DetectionPagingEventType.PAGE_FREQUENCY_SCAN -> PagingEventType.SERVICE_REQUEST
    DetectionPagingEventType.PAGE_UNUSUAL_TIMING -> PagingEventType.SERVICE_REQUEST
    DetectionPagingEventType.PAGE_DUPLICATE_PAGING -> PagingEventType.SERVICE_REQUEST
    DetectionPagingEventType.PAGE_UNIDIRECTIONAL -> PagingEventType.SERVICE_REQUEST
    DetectionPagingEventType.PAGE_UNKNOWN -> PagingEventType.UNKNOWN
    DetectionPagingEventType.PAGE_MALFORMED -> PagingEventType.UNKNOWN
    DetectionPagingEventType.PAGE_TIMEOUT -> PagingEventType.UNKNOWN
}