package com.cymatune.lifecycle

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Performance monitoring and optimization system for component lifecycle management.
 * Provides real-time monitoring, performance metrics collection, and optimization suggestions.
 */
object ComponentLifecycleMonitor {
    
    private const val TAG = "ComponentLifecycleMonitor"
    
    // Performance metrics storage
    private val initializationMetrics = ConcurrentHashMap<String, InitializationMetrics>()
    private val shutdownMetrics = ConcurrentHashMap<String, ShutdownMetrics>()
    private val dependencyMetrics = ConcurrentHashMap<String, DependencyMetrics>()
    
    // Monitoring configuration
    private const val METRICS_RETENTION_MINUTES = 60
    private const val PERFORMANCE_WARNING_THRESHOLD_MS = 5000L
    private const val MEMORY_WARNING_THRESHOLD_MB = 50L
    
    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Metrics for component initialization performance
     */
    data class InitializationMetrics(
        val componentName: String,
        val startTime: Long,
        val endTime: Long,
        val durationMs: Long,
        val retryCount: Int,
        val success: Boolean,
        val memoryUsageBefore: Long,
        val memoryUsageAfter: Long
    )
    
    /**
     * Metrics for component shutdown performance
     */
    data class ShutdownMetrics(
        val componentName: String,
        val startTime: Long,
        val endTime: Long,
        val durationMs: Long,
        val success: Boolean,
        val memoryUsageBefore: Long,
        val memoryUsageAfter: Long
    )
    
    /**
     * Metrics for dependency resolution performance
     */
    data class DependencyMetrics(
        val totalComponents: Int,
        val dependencyResolutionTimeMs: Long,
        val circularDependencyDetected: Boolean,
        val longestDependencyChain: Int
    )
    
    /**
     * Performance alert types
     */
    enum class AlertType {
        SLOW_INITIALIZATION,
        SLOW_SHUTDOWN,
        HIGH_MEMORY_USAGE,
        CIRCULAR_DEPENDENCY,
        FAILED_INITIALIZATION,
        TIMEOUT_DETECTED
    }
    
    /**
     * Performance alert data
     */
    data class PerformanceAlert(
        val alertType: AlertType,
        val componentName: String?,
        val message: String,
        val timestamp: Long,
        val severity: Severity
    ) {
        enum class Severity {
            LOW, MEDIUM, HIGH, CRITICAL
        }
    }
    
    // Alert callbacks
    private val alertCallbacks = mutableListOf<(PerformanceAlert) -> Unit>()
    
    init {
        // Start background monitoring
        startBackgroundMonitoring()
    }
    
    /**
     * Record component initialization metrics
     */
    fun recordInitializationMetrics(
        componentName: String,
        startTime: Long,
        endTime: Long,
        retryCount: Int,
        success: Boolean
    ) {
        try {
            val memoryBefore = getMemoryUsage()
            val durationMs = endTime - startTime
            
            val metrics = InitializationMetrics(
                componentName = componentName,
                startTime = startTime,
                endTime = endTime,
                durationMs = durationMs,
                retryCount = retryCount,
                success = success,
                memoryUsageBefore = memoryBefore,
                memoryUsageAfter = getMemoryUsage()
            )
            
            initializationMetrics[componentName] = metrics
            
            // Check for performance issues
            checkInitializationPerformance(metrics)
            
            Log.d(TAG, "Recorded initialization metrics for $componentName: ${durationMs}ms, retries: $retryCount, success: $success")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record initialization metrics for $componentName", e)
        }
    }
    
    /**
     * Record component shutdown metrics
     */
    fun recordShutdownMetrics(
        componentName: String,
        startTime: Long,
        endTime: Long,
        success: Boolean
    ) {
        try {
            val memoryBefore = getMemoryUsage()
            val durationMs = endTime - startTime
            
            val metrics = ShutdownMetrics(
                componentName = componentName,
                startTime = startTime,
                endTime = endTime,
                durationMs = durationMs,
                success = success,
                memoryUsageBefore = memoryBefore,
                memoryUsageAfter = getMemoryUsage()
            )
            
            shutdownMetrics[componentName] = metrics
            
            // Check for performance issues
            checkShutdownPerformance(metrics)
            
            Log.d(TAG, "Recorded shutdown metrics for $componentName: ${durationMs}ms, success: $success")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record shutdown metrics for $componentName", e)
        }
    }
    
    /**
     * Record dependency resolution metrics
     */
    fun recordDependencyMetrics(
        totalComponents: Int,
        resolutionTimeMs: Long,
        circularDependencyDetected: Boolean,
        longestChain: Int
    ) {
        try {
            val metrics = DependencyMetrics(
                totalComponents = totalComponents,
                dependencyResolutionTimeMs = resolutionTimeMs,
                circularDependencyDetected = circularDependencyDetected,
                longestDependencyChain = longestChain
            )
            
            dependencyMetrics["latest"] = metrics
            
            // Check for dependency issues
            checkDependencyPerformance(metrics)
            
            Log.d(TAG, "Recorded dependency metrics: $totalComponents components, ${resolutionTimeMs}ms resolution time, circular deps: $circularDependencyDetected, longest chain: $longestChain")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record dependency metrics", e)
        }
    }
    
    /**
     * Get performance summary
     */
    fun getPerformanceSummary(): String {
        return try {
            val totalComponents = initializationMetrics.size
            val avgInitTime = initializationMetrics.values.map { it.durationMs }.average()
            val avgRetryCount = initializationMetrics.values.map { it.retryCount }.average()
            val successRate = initializationMetrics.values.count { it.success }.toDouble() / totalComponents * 100
            val totalMemoryUsed = initializationMetrics.values.sumOf { it.memoryUsageAfter - it.memoryUsageBefore }
            
            "Component Lifecycle Performance Summary:\n" +
            "- Total Components: $totalComponents\n" +
            "- Average Initialization Time: ${avgInitTime.toInt()}ms\n" +
            "- Average Retry Count: ${avgRetryCount}\n" +
            "- Success Rate: ${"%.1f".format(successRate)}%\n" +
            "- Total Memory Used: ${totalMemoryUsed / 1024 / 1024}MB\n" +
            "- Dependency Resolution Time: ${dependencyMetrics["latest"]?.dependencyResolutionTimeMs ?: 0}ms"
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate performance summary", e)
            "Performance summary unavailable"
        }
    }
    
    /**
     * Get detailed metrics for a specific component
     */
    fun getComponentMetrics(componentName: String): String {
        return try {
            val initMetrics = initializationMetrics[componentName]
            val shutdownMetrics = shutdownMetrics[componentName]
            
            val initInfo = if (initMetrics != null) {
                "Initialization: ${initMetrics.durationMs}ms, retries: ${initMetrics.retryCount}, success: ${initMetrics.success}"
            } else "No initialization data"
            
            val shutdownInfo = if (shutdownMetrics != null) {
                "Shutdown: ${shutdownMetrics.durationMs}ms, success: ${shutdownMetrics.success}"
            } else "No shutdown data"
            
            "$componentName Metrics:\n$initInfo\n$shutdownInfo"
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get metrics for $componentName", e)
            "Metrics unavailable for $componentName"
        }
    }
    
    /**
     * Add alert callback
     */
    fun addAlertCallback(callback: (PerformanceAlert) -> Unit) {
        alertCallbacks.add(callback)
    }
    
    /**
     * Remove alert callback
     */
    fun removeAlertCallback(callback: (PerformanceAlert) -> Unit) {
        alertCallbacks.remove(callback)
    }
    
    /**
     * Clear all metrics data
     */
    fun clearMetrics() {
        initializationMetrics.clear()
        shutdownMetrics.clear()
        dependencyMetrics.clear()
        Log.d(TAG, "All performance metrics cleared")
    }
    
    /**
     * Export metrics for analysis
     */
    fun exportMetrics(): Map<String, Any> {
        return mapOf(
            "initializationMetrics" to initializationMetrics.values.toList(),
            "shutdownMetrics" to shutdownMetrics.values.toList(),
            "dependencyMetrics" to dependencyMetrics.values.toList(),
            "summary" to getPerformanceSummary()
        )
    }
    
    /**
     * Check for initialization performance issues
     */
    private fun checkInitializationPerformance(metrics: InitializationMetrics) {
        val alerts = mutableListOf<PerformanceAlert>()
        
        // Check for slow initialization
        if (metrics.durationMs > PERFORMANCE_WARNING_THRESHOLD_MS) {
            alerts.add(PerformanceAlert(
                alertType = AlertType.SLOW_INITIALIZATION,
                componentName = metrics.componentName,
                message = "${metrics.componentName} initialization took ${metrics.durationMs}ms (exceeds ${PERFORMANCE_WARNING_THRESHOLD_MS}ms threshold)",
                timestamp = System.currentTimeMillis(),
                severity = if (metrics.durationMs > PERFORMANCE_WARNING_THRESHOLD_MS * 2) PerformanceAlert.Severity.HIGH else PerformanceAlert.Severity.MEDIUM
            ))
        }
        
        // Check for high retry count
        if (metrics.retryCount > 1) {
            alerts.add(PerformanceAlert(
                alertType = AlertType.FAILED_INITIALIZATION,
                componentName = metrics.componentName,
                message = "${metrics.componentName} required ${metrics.retryCount} retries to initialize",
                timestamp = System.currentTimeMillis(),
                severity = if (metrics.retryCount > 2) PerformanceAlert.Severity.HIGH else PerformanceAlert.Severity.MEDIUM
            ))
        }
        
        // Check for memory issues
        val memoryIncrease = metrics.memoryUsageAfter - metrics.memoryUsageBefore
        if (memoryIncrease > MEMORY_WARNING_THRESHOLD_MB * 1024 * 1024) {
            alerts.add(PerformanceAlert(
                alertType = AlertType.HIGH_MEMORY_USAGE,
                componentName = metrics.componentName,
                message = "${metrics.componentName} memory usage increased by ${memoryIncrease / 1024 / 1024}MB",
                timestamp = System.currentTimeMillis(),
                severity = PerformanceAlert.Severity.MEDIUM
            ))
        }
        
        // Check for failed initialization
        if (!metrics.success) {
            alerts.add(PerformanceAlert(
                alertType = AlertType.FAILED_INITIALIZATION,
                componentName = metrics.componentName,
                message = "${metrics.componentName} initialization failed",
                timestamp = System.currentTimeMillis(),
                severity = PerformanceAlert.Severity.HIGH
            ))
        }
        
        // Notify alerts
        alerts.forEach { notifyAlert(it) }
    }
    
    /**
     * Check for shutdown performance issues
     */
    private fun checkShutdownPerformance(metrics: ShutdownMetrics) {
        if (metrics.durationMs > PERFORMANCE_WARNING_THRESHOLD_MS) {
            val alert = PerformanceAlert(
                alertType = AlertType.SLOW_SHUTDOWN,
                componentName = metrics.componentName,
                message = "${metrics.componentName} shutdown took ${metrics.durationMs}ms (exceeds ${PERFORMANCE_WARNING_THRESHOLD_MS}ms threshold)",
                timestamp = System.currentTimeMillis(),
                severity = if (metrics.durationMs > PERFORMANCE_WARNING_THRESHOLD_MS * 2) PerformanceAlert.Severity.HIGH else PerformanceAlert.Severity.MEDIUM
            )
            notifyAlert(alert)
        }
    }
    
    /**
     * Check for dependency performance issues
     */
    private fun checkDependencyPerformance(metrics: DependencyMetrics) {
        if (metrics.circularDependencyDetected) {
            val alert = PerformanceAlert(
                alertType = AlertType.CIRCULAR_DEPENDENCY,
                componentName = null,
                message = "Circular dependency detected in component graph",
                timestamp = System.currentTimeMillis(),
                severity = PerformanceAlert.Severity.CRITICAL
            )
            notifyAlert(alert)
        }
        
        if (metrics.dependencyResolutionTimeMs > 1000L) {
            val alert = PerformanceAlert(
                alertType = AlertType.TIMEOUT_DETECTED,
                componentName = null,
                message = "Dependency resolution took ${metrics.dependencyResolutionTimeMs}ms (exceeds 1000ms threshold)",
                timestamp = System.currentTimeMillis(),
                severity = PerformanceAlert.Severity.MEDIUM
            )
            notifyAlert(alert)
        }
    }
    
    /**
     * Notify alert to all registered callbacks
     */
    private fun notifyAlert(alert: PerformanceAlert) {
        try {
            alertCallbacks.forEach { callback ->
                try {
                    callback(alert)
                } catch (e: Exception) {
                    Log.e(TAG, "Alert callback failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify alert", e)
        }
    }
    
    /**
     * Start background monitoring tasks
     */
    private fun startBackgroundMonitoring() {
        monitorScope.launch {
            while (isActive) {
                try {
                    // Clean up old metrics periodically
                    cleanupOldMetrics()
                    
                    // Log periodic performance summary
                    if (initializationMetrics.isNotEmpty()) {
                        Log.d(TAG, getPerformanceSummary())
                    }
                    
                    // Wait before next monitoring cycle
                    delay(5000) // Check every 5 seconds
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Background monitoring error", e)
                    delay(10000) // Wait longer on error
                }
            }
        }
    }
    
    /**
     * Clean up old metrics data
     */
    private fun cleanupOldMetrics() {
        try {
            val cutoffTime = System.currentTimeMillis() - (METRICS_RETENTION_MINUTES * 60 * 1000)
            
            // Clean initialization metrics
            initializationMetrics.entries.removeIf { (_, metrics) ->
                metrics.endTime < cutoffTime
            }
            
            // Clean shutdown metrics
            shutdownMetrics.entries.removeIf { (_, metrics) ->
                metrics.endTime < cutoffTime
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cleanup old metrics", e)
        }
    }
    
    /**
     * Get current memory usage in bytes
     */
    private fun getMemoryUsage(): Long {
        try {
            Runtime.getRuntime().run {
                return totalMemory() - freeMemory()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get memory usage", e)
            return 0L
        }
    }
    
    /**
     * Get optimization suggestions based on collected metrics
     */
    fun getOptimizationSuggestions(): List<String> {
        val suggestions = mutableListOf<String>()
        
        try {
            val slowComponents = initializationMetrics.filter { it.value.durationMs > 2000L }
            if (slowComponents.isNotEmpty()) {
                suggestions.add("Consider optimizing initialization for components: ${slowComponents.keys.joinToString()}")
            }
            
            val highRetryComponents = initializationMetrics.filter { it.value.retryCount > 1 }
            if (highRetryComponents.isNotEmpty()) {
                suggestions.add("Investigate reliability issues with components: ${highRetryComponents.keys.joinToString()}")
            }
            
            val avgInitTime = initializationMetrics.values.map { it.durationMs }.average()
            if (avgInitTime > 1000) {
                suggestions.add("Consider lazy loading for non-critical components to improve startup time")
            }
            
            if (dependencyMetrics["latest"]?.longestDependencyChain ?: 0 > 5) {
                suggestions.add("Consider reducing dependency chain depth to improve initialization reliability")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate optimization suggestions", e)
        }
        
        return suggestions
    }
    
    /**
     * Stop monitoring (for cleanup)
     */
    fun stopMonitoring() {
        monitorScope.cancel()
        Log.d(TAG, "Component lifecycle monitoring stopped")
    }
}