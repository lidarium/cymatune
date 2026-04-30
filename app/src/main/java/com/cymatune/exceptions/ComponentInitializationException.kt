package com.cymatune.exceptions

import android.util.Log

/**
 * Exception thrown when critical component initialization fails.
 * This exception provides detailed context about initialization failures
 * and supports proper error handling and recovery mechanisms.
 */
class ComponentInitializationException : Exception {
    
    companion object {
        private const val TAG = "ComponentInitializationException"
    }
    
    val componentName: String
    val failureType: FailureType
    val recoveryAction: RecoveryAction?
    
    constructor(message: String, cause: Throwable?, componentName: String, failureType: FailureType, recoveryAction: RecoveryAction? = null) : super(message, cause) {
        this.componentName = componentName
        this.failureType = failureType
        this.recoveryAction = recoveryAction
        
        // Log the exception with context
        Log.e(TAG, buildLogMessage(message, componentName, failureType, recoveryAction), cause)
    }
    
    constructor(message: String, componentName: String, failureType: FailureType, recoveryAction: RecoveryAction? = null) : super(message) {
        this.componentName = componentName
        this.failureType = failureType
        this.recoveryAction = recoveryAction
        
        // Log the exception with context
        Log.e(TAG, buildLogMessage(message, componentName, failureType, recoveryAction))
    }
    
    /**
     * Types of initialization failures
     */
    enum class FailureType {
        DAO_DEPENDENCY_MISSING,    // Required DAO is not available
        DAO_VALIDATION_FAILED,    // DAO validation failed
        COMPONENT_CREATION_FAILED, // Component instantiation failed
        DEPENDENCY_RESOLUTION_FAILED, // Component dependency resolution failed
        TIMING_VIOLATION,         // Initialization timing requirements not met
        DATABASE_NOT_READY,       // Database not properly initialized
        UNEXPECTED_ERROR          // Other unexpected error
    }
    
    /**
     * Recommended recovery actions
     */
    enum class RecoveryAction {
        RETRY_INITIALIZATION,     // Retry component initialization
        USE_FALLBACK_COMPONENTS,  // Use fallback/mock components
        DELAY_AND_RETRY,          // Wait and retry after delay
        SKIP_COMPONENT,           // Skip this component and continue
        SHUTDOWN_SERVICE,         // Shutdown service gracefully
        MANUAL_INTERVENTION       // Requires manual intervention
    }
    
    /**
     * Build detailed log message with failure context
     */
    private fun buildLogMessage(message: String, componentName: String, failureType: FailureType, recoveryAction: RecoveryAction?): String {
        return buildString {
            append("Component Initialization Failed - ")
            append("Component: $componentName, ")
            append("Failure Type: $failureType, ")
            append("Message: $message")
            
            if (recoveryAction != null) {
                append(", Recommended Action: $recoveryAction")
            }
        }
    }
    
    /**
     * Get a user-friendly error message
     */
    fun getUserFriendlyMessage(): String {
        return when (failureType) {
            FailureType.DAO_DEPENDENCY_MISSING -> "Required database component is not available. The app will attempt to recover automatically."
            FailureType.DAO_VALIDATION_FAILED -> "Database component validation failed. The app will try to recover using fallback mechanisms."
            FailureType.COMPONENT_CREATION_FAILED -> "Failed to create a critical component. The app will continue with limited functionality."
            FailureType.DEPENDENCY_RESOLUTION_FAILED -> "Component dependencies cannot be resolved. The app will attempt alternative initialization."
            FailureType.TIMING_VIOLATION -> "Component initialization timing requirements were not met. The app will retry initialization."
            FailureType.DATABASE_NOT_READY -> "Database is not ready for component initialization. The app will wait and retry."
            FailureType.UNEXPECTED_ERROR -> "An unexpected error occurred during component initialization. The app will attempt recovery."
        }
    }
    
    /**
     * Check if this exception supports automatic recovery
     */
    fun supportsAutoRecovery(): Boolean {
        return when (failureType) {
            FailureType.DAO_DEPENDENCY_MISSING -> true
            FailureType.DAO_VALIDATION_FAILED -> true
            FailureType.COMPONENT_CREATION_FAILED -> true
            FailureType.DEPENDENCY_RESOLUTION_FAILED -> true
            FailureType.TIMING_VIOLATION -> true
            FailureType.DATABASE_NOT_READY -> true
            FailureType.UNEXPECTED_ERROR -> recoveryAction != RecoveryAction.MANUAL_INTERVENTION
        }
    }
    
    /**
     * Get suggested delay for retry operations (in milliseconds)
     */
    fun getRetryDelay(): Long {
        return when (failureType) {
            FailureType.DAO_DEPENDENCY_MISSING -> 1000L // 1 second
            FailureType.DAO_VALIDATION_FAILED -> 2000L // 2 seconds
            FailureType.COMPONENT_CREATION_FAILED -> 1500L // 1.5 seconds
            FailureType.DEPENDENCY_RESOLUTION_FAILED -> 2500L // 2.5 seconds
            FailureType.TIMING_VIOLATION -> 500L   // 0.5 seconds
            FailureType.DATABASE_NOT_READY -> 3000L // 3 seconds
            FailureType.UNEXPECTED_ERROR -> 2000L // 2 seconds
        }
    }
}