package com.cymatune.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ProtocolHandshake")
data class ProtocolHandshake(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val towerId: Int, // Reference to the tower this handshake belongs to
    val protocolType: String, // GSM, UMTS, LTE, NR
    val cipherAlgorithm: String, // A5/1, A5/3, EEA0, EEA1, EEA2, EEA3
    val handshakeDuration: Int, // Duration in milliseconds
    val success: Boolean, // Whether handshake was successful
    val timestamp: Long, // When the handshake occurred
    val suspicionScore: Double = 0.0, // Suspicion score (0.0-1.0)
    val details: String? = null // Additional details or error messages
)