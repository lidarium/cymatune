package com.cymatune.dialer.models

/**
 * Data class representing a single call log entry.
 */
data class CallLogItem(
    val id: Long,
    val number: String,
    val name: String?,
    val type: Int, // CallLog.Calls.INCOMING_TYPE, OUTGOING_TYPE, MISSED_TYPE
    val date: Long,
    val duration: Long,
    val formattedDate: String,
    val label: String? = null // Mobile, Home, etc.
)
