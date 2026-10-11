package com.cymatune.util

import android.util.Log

/**
 * Utility class to verify and test the new detection algorithms
 * This can be called from the main activity or service for testing purposes
 */
object DetectionVerifier {

    fun testSignalAnomalyDetection() {
        Log.d("DetectionVerifier", "Testing signal anomaly detection...")
        
        // Simulate normal signal pattern
        val normalSignals = listOf(-85.0, -83.0, -86.0, -84.0, -82.0)
        val normalStdDev = CommonUtils.calculateStandardDeviation(normalSignals)
        val normalAvg = normalSignals.average()
        val normalLatest = normalSignals.last()
        val normalDiff = kotlin.math.abs(normalLatest - normalAvg)
        val normalThreshold = 2.5 * normalStdDev
        
        Log.d("DetectionVerifier", "Normal signals: $normalSignals")
        Log.d("DetectionVerifier", "Avg: $normalAvg, StdDev: $normalStdDev, Diff: $normalDiff, Threshold: $normalThreshold")
        Log.d("DetectionVerifier", "Normal detection: ${normalDiff > normalThreshold}")
        
        // Simulate anomalous signal pattern
        val anomalousSignals = listOf(-85.0, -83.0, -86.0, -84.0, -120.0) // Sudden drop
        val anomalousStdDev = CommonUtils.calculateStandardDeviation(anomalousSignals)
        val anomalousAvg = anomalousSignals.average()
        val anomalousLatest = anomalousSignals.last()
        val anomalousDiff = kotlin.math.abs(anomalousLatest - anomalousAvg)
        val anomalousThreshold = 2.5 * anomalousStdDev
        
        Log.d("DetectionVerifier", "Anomalous signals: $anomalousSignals")
        Log.d("DetectionVerifier", "Avg: $anomalousAvg, StdDev: $anomalousStdDev, Diff: $anomalousDiff, Threshold: $anomalousThreshold")
        Log.d("DetectionVerifier", "Anomalous detection: ${anomalousDiff > anomalousThreshold}")
    }

    fun testTimingAdvanceAnomalyDetection() {
        Log.d("DetectionVerifier", "Testing timing advance anomaly detection...")
        
        // Simulate normal TA pattern
        val normalTA = listOf(5.0, 6.0, 5.0, 7.0, 6.0)
        val normalStdDev = CommonUtils.calculateStandardDeviation(normalTA)
        val normalAvg = normalTA.average()
        val normalLatest = normalTA.last()
        val normalDiff = kotlin.math.abs(normalLatest - normalAvg)
        val normalThreshold = 2.0 * normalStdDev
        
        Log.d("DetectionVerifier", "Normal TA: $normalTA")
        Log.d("DetectionVerifier", "Avg: $normalAvg, StdDev: $normalStdDev, Diff: $normalDiff, Threshold: $normalThreshold")
        Log.d("DetectionVerifier", "Normal detection: ${normalDiff > normalThreshold && normalStdDev > 0.5}")
        
        // Simulate anomalous TA pattern
        val anomalousTA = listOf(5.0, 6.0, 5.0, 7.0, 20.0) // Sudden jump
        val anomalousStdDev = CommonUtils.calculateStandardDeviation(anomalousTA)
        val anomalousAvg = anomalousTA.average()
        val anomalousLatest = anomalousTA.last()
        val anomalousDiff = kotlin.math.abs(anomalousLatest - anomalousAvg)
        val anomalousThreshold = 2.0 * anomalousStdDev
        
        Log.d("DetectionVerifier", "Anomalous TA: $anomalousTA")
        Log.d("DetectionVerifier", "Avg: $anomalousAvg, StdDev: $anomalousStdDev, Diff: $anomalousDiff, Threshold: $anomalousThreshold")
        Log.d("DetectionVerifier", "Anomalous detection: ${anomalousDiff > anomalousThreshold && anomalousStdDev > 0.5}")
    }


    fun runAllTests() {
        Log.d("DetectionVerifier", "=== Starting Detection Algorithm Tests ===")
        testSignalAnomalyDetection()
        testTimingAdvanceAnomalyDetection()
        Log.d("DetectionVerifier", "=== Detection Algorithm Tests Complete ===")
    }
}