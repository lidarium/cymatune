package com.cymatune.dialer.models

/**
 * Data class representing a single contact entry.
 */
data class ContactItem(
    val id: Long,
    val name: String,
    val number: String,
    val initial: String,
    val photoUri: String? = null
)
