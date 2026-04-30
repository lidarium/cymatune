package com.cymatune.util

import android.content.Context
import com.cymatune.R

/**
 * Provides educational explanations for fake tower detection patterns
 * Helps users understand what each detection pattern means and why it's suspicious
 */
object DetectionExplanationProvider {

    /**
     * Get detailed explanation for a detection pattern
     */
    fun getExplanationForPattern(pattern: String, confidence: Double, context: Context): String {
        return when {
            pattern.contains(DetectionPatterns.STATIONARY_USER_MOVING_TOWER) -> context.getString(
                R.string.explanation_stationary_user_moving_tower,
                formatConfidenceLevel(confidence)
            )
            pattern.contains(DetectionPatterns.USER_MOVING_TOWER_FOLLOWING) -> context.getString(
                R.string.explanation_user_moving_tower_following,
                formatConfidenceLevel(confidence)
            )
            pattern.contains(DetectionPatterns.STATIONARY_USER_INTERMITTENT_TOWER) -> context.getString(
                R.string.explanation_stationary_user_intermittent_tower,
                formatConfidenceLevel(confidence)
            )
            pattern.contains(DetectionPatterns.TIMING_ADVANCE_ANOMALY) -> context.getString(
                R.string.explanation_timing_advance_anomaly,
                formatConfidenceLevel(confidence)
            )
            pattern.contains(DetectionPatterns.NETWORK_PERFORMANCE_ANOMALY) -> context.getString(
                R.string.explanation_network_performance_anomaly,
                formatConfidenceLevel(confidence)
            )
            pattern.contains(DetectionPatterns.NEW_UNKNOWN_TOWER) -> context.getString(
                R.string.explanation_new_unknown_tower,
                formatConfidenceLevel(confidence)
            )
            pattern.contains(DetectionPatterns.UNUSUAL_SIGNAL_PATTERN) -> context.getString(
                R.string.explanation_unusual_signal_pattern,
                formatConfidenceLevel(confidence)
            )
            pattern.contains(DetectionPatterns.NETWORK_ANOMALIES) -> context.getString(
                R.string.explanation_network_anomalies,
                formatConfidenceLevel(confidence)
            )
            pattern.contains(DetectionPatterns.REGISTRATION_ISSUES) -> context.getString(
                R.string.explanation_registration_issues,
                formatConfidenceLevel(confidence)
            )
            else -> context.getString(
                R.string.explanation_general_suspicion,
                pattern,
                formatConfidenceLevel(confidence)
            )
        }
    }

    /**
     * Get severity level description based on confidence
     */
    fun getSeverityLevel(confidence: Double): String {
        return when {
            confidence >= 0.8 -> "High Confidence"
            confidence >= 0.6 -> "Medium Confidence"
            confidence >= 0.4 -> "Low Confidence"
            else -> "Very Low Confidence"
        }
    }

    /**
     * Get recommended actions based on confidence level
     */
    fun getRecommendedActions(confidence: Double, context: Context): String {
        return when {
            confidence >= 0.8 -> context.getString(R.string.actions_high_accuracy)
            confidence >= 0.6 -> context.getString(R.string.actions_medium_accuracy)
            confidence >= 0.4 -> context.getString(R.string.actions_low_accuracy)
            else -> context.getString(R.string.actions_very_low_accuracy)
        }
    }

    /**
     * Format confidence level for display
     */
    private fun formatConfidenceLevel(confidence: Double): String {
        return "${(confidence * 100).toInt()}% confidence"
    }

    /**
     * Get educational content about fake towers in general
     */
    fun getGeneralEducationalContent(context: Context): String {
        return context.getString(R.string.educational_content_general)
    }

    /**
     * Get explanation for multiple detection patterns
     */
    fun getCombinedExplanation(patterns: List<String>, confidence: Double, context: Context): String {
        if (patterns.isEmpty()) return ""
        
        val explanations = patterns.map { pattern ->
            getExplanationForPattern(pattern, confidence, context)
        }
        
        return if (explanations.size == 1) {
            explanations.first()
        } else {
            context.getString(R.string.multiple_detection_patterns) + "\n\n" +
            explanations.joinToString("\n\n") { "• $it" }
        }
    }
}