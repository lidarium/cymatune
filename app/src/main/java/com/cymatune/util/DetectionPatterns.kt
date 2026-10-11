package com.cymatune.util

/**
 * Centralized constants for all fake tower detection patterns
 * This eliminates code duplication and ensures consistency between
 * detection logic and explanation logic
 */
object DetectionPatterns {
    // Pattern strings used in FakeTowerDetector
    const val STATIONARY_USER_MOVING_TOWER = "Stationary User, Moving Tower"
    const val USER_MOVING_TOWER_FOLLOWING = "User Moving, Tower Following"
    const val STATIONARY_USER_INTERMITTENT_TOWER = "Stationary User, Intermittent Tower"
    const val TIMING_ADVANCE_ANOMALY = "Timing Advance Anomaly"
    const val NETWORK_PERFORMANCE_ANOMALY = "Network Performance Anomaly"
    const val NEW_UNKNOWN_TOWER = "New/Unknown Tower"
    const val UNUSUAL_SIGNAL_PATTERN = "Unusual Signal Pattern"
    const val NETWORK_ANOMALIES = "Network Anomalies"
    const val REGISTRATION_ISSUES = "Registration Issues"
    const val CONSISTENT_WITH_VEHICLE_SPEED = "Consistent with Vehicle Speed"
}