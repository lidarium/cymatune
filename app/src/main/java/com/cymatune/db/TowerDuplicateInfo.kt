package com.cymatune.db

/**
 * Data class for duplicate tower information
 */
data class TowerDuplicateInfo(
    val cid: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    val duplicate_count: Int,
    val first_seen: Long,
    val last_seen: Long
)
