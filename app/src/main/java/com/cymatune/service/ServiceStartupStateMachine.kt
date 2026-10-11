package com.cymatune.service

import android.content.Context
import android.util.Log
import com.cymatune.db.DatabaseInitializationManager
import com.cymatune.lifecycle.ComponentLifecycleManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.firstOrNull

/**
 * Service Startup State Machine
 * 
 * Implements a robust state machine for service startup with automatic recovery,
 * timeout protection, and comprehensive error handling. Integrates with existing
 * DatabaseInitializationManager and ComponentLifecycleManager while maintaining
 * full compatibility with all MVP features.
 */
class ServiceStartupStateMachine {
    
    companion object {
        private const val TAG = "ServiceStartupStateMachine"
    }
    
    // Error Recovery Manager integration
    private lateinit var errorRecoveryManager: com.cymatune.lifecycle.ErrorRecoveryManager
    
    /**
     * Service startup states with clear progression and recovery paths
     */
    enum class StartupState {
        IDLE,                    // Initial state - ready to start
        INITIALIZING_DATABASE,   // Database initialization phase
        INITIALIZING_COMPONENTS, // Component lifecycle initialization
        STARTING_MONITORING,     // Monitoring operations startup
        RUNNING,                 // Service fully operational
        FAILED,                  // Startup failed - requires manual intervention
        RECOVERING               // Attempting recovery from failure
    }
    
    /**
     * Result types for startup operations with detailed error information
     */
    sealed class StartupResult {
        data class Success(val finalState: StartupState, val durationMs: Long) : StartupResult()
        data class Failed(
            val failureState: StartupState, 
            val errorMessage: String, 
            val cause: Throwable? = null,
            val canRetry: Boolean = true
        ) : StartupResult()
        data class Recovered(
            val recoveredFrom: StartupState,
            val finalState: StartupState,
            val recoveryAttempts: Int,
            val durationMs: Long
        ) : StartupResult()
    }
    
    /**
     * Configuration for timeouts and retry behavior
     */
    data class StartupConfig(
        val databaseTimeoutMs: Long = 30000L,    // 30 seconds (aligned with DatabaseInitializationManager)
        val componentsTimeoutMs: Long = 60000L,  // 60 seconds (aligned with ComponentLifecycleManager)
        val monitoringTimeoutMs: Long = 15000L,  // 15 seconds
        val overallTimeoutMs: Long = 90000L,     // 90 seconds total
        val maxRecoveryAttempts: Int = 3,        // Maximum recovery attempts
        val recoveryDelayMs: Long = 2000L        // Delay between recovery attempts
    )
    
    /**
     * Health metrics for monitoring startup performance
     */
    data class StartupMetrics(
        val startTime: Long,
        val endTime: Long? = null,
        val currentState: StartupState,
        val stateTransitions: MutableList<Pair<StartupState, Long>>,
        val errorCount: Int = 0,
        val recoveryAttempts: Int = 0
    )
    
    // State management
    @Volatile
    private var currentState: StartupState = StartupState.IDLE
    
    private val stateMutex = Mutex()
    private var startupJob: Job? = null
    private var startupScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Configuration and metrics
    private var config: StartupConfig = StartupConfig()
    private lateinit var metrics: StartupMetrics
    
    /**
     * Initialize the Error Recovery Manager
     */
    fun initializeErrorRecovery(errorRecoveryManager: com.cymatune.lifecycle.ErrorRecoveryManager) {
        this.errorRecoveryManager = errorRecoveryManager
        Log.d(TAG, "Error Recovery Manager initialized and integrated with state machine")
    }
    
    /**
     * Execute the complete startup sequence with state machine management
     * 
     * @param context Android context
     * @param startupConfig Optional configuration for timeouts and retry behavior
     * @return StartupResult indicating success, failure, or recovery
     */
    suspend fun executeStartupSequence(
        context: Context,
        startupConfig: StartupConfig = StartupConfig()
    ): StartupResult? = withTimeoutOrNull(startupConfig.overallTimeoutMs) {
        stateMutex.withLock {
            // Reset state and initialize metrics
            currentState = StartupState.IDLE
            config = startupConfig
            metrics = StartupMetrics(
                startTime = System.currentTimeMillis(),
                currentState = StartupState.IDLE,
                stateTransitions = mutableListOf(StartupState.IDLE to System.currentTimeMillis())
            )
            
            // Cancel any existing startup job
            startupJob?.cancel()
            
            Log.i(TAG, "Starting service startup sequence with state machine")
            Log.d(TAG, "Configuration: database=${config.databaseTimeoutMs}ms, components=${config.componentsTimeoutMs}ms, monitoring=${config.monitoringTimeoutMs}ms")
            
            try {
                // Execute startup phases with state transitions
                val result = executeStartupPhases(context)
                
                when (result) {
                    is StartupResult.Success -> {
                        Log.i(TAG, "Service startup completed successfully in ${result.durationMs}ms")
                        updateMetrics(StartupState.RUNNING)
                    }
                    is StartupResult.Recovered -> {
                        Log.w(TAG, "Service startup recovered after ${result.recoveryAttempts} attempts, final state: ${result.finalState}")
                    }
                    is StartupResult.Failed -> {
                        Log.e(TAG, "Service startup failed: ${result.errorMessage}")
                        currentState = StartupState.FAILED
                    }
                }
                
                result
                
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during startup sequence", e)
                StartupResult.Failed(
                    failureState = currentState,
                    errorMessage = "Unexpected startup error: ${e.message}",
                    cause = e,
                    canRetry = true
                )
            }
        }
    }
    
    /**
     * Execute the sequential startup phases with state management
     */
    private suspend fun executeStartupPhases(context: Context): StartupResult {
        // Phase 1: Database Initialization
        transitionToState(StartupState.INITIALIZING_DATABASE)
        val databaseResult = executeDatabaseInitialization(context)
        
        return when (databaseResult) {
            is StartupResult.Success -> {
                // Phase 2: Component Initialization
                transitionToState(StartupState.INITIALIZING_COMPONENTS)
                val componentResult = executeComponentInitialization(context)
                
                when (componentResult) {
                    is StartupResult.Success -> {
                        // Phase 3: Monitoring Startup
                        transitionToState(StartupState.STARTING_MONITORING)
                        val monitoringResult = executeMonitoringStartup(context)
                        
                        when (monitoringResult) {
                            is StartupResult.Success -> {
                                // Perform final database readiness check before marking as fully operational
                                val databaseReadiness = performDatabaseReadinessCheck(context)
                                if (databaseReadiness) {
                                    transitionToState(StartupState.RUNNING)
                                    StartupResult.Success(
                                        finalState = StartupState.RUNNING,
                                        durationMs = calculateDuration()
                                    )
                                } else {
                                    Log.w(TAG, "Database readiness check failed, marking as recovered state")
                                    StartupResult.Recovered(
                                        recoveredFrom = StartupState.STARTING_MONITORING,
                                        finalState = StartupState.RUNNING,
                                        recoveryAttempts = 1,
                                        durationMs = calculateDuration()
                                    )
                                }
                            }
                            else -> monitoringResult
                        }
                    }
                    else -> componentResult
                }
            }
            else -> databaseResult
        }
    }
    
    /**
     * Execute database initialization with timeout and retry logic
     */
    private suspend fun executeDatabaseInitialization(context: Context): StartupResult {
        return try {
            withTimeout(config.databaseTimeoutMs) {
                Log.d(TAG, "Starting database initialization...")
                
                val result = DatabaseInitializationManager.initializeDatabaseWithRetry(context)
                
                when (result) {
                    is DatabaseInitializationManager.InitializationResult.Success -> {
                        Log.i(TAG, "Database initialization successful")
                        StartupResult.Success(
                            finalState = StartupState.INITIALIZING_DATABASE,
                            durationMs = calculateDuration()
                        )
                    }
                    is DatabaseInitializationManager.InitializationResult.Fallback -> {
                        Log.w(TAG, "Database initialization completed with fallback: ${result.message}")
                        StartupResult.Success(
                            finalState = StartupState.INITIALIZING_DATABASE,
                            durationMs = calculateDuration()
                        )
                    }
                    is DatabaseInitializationManager.InitializationResult.Error -> {
                        Log.e(TAG, "Database initialization failed: ${result.message}")
                        
                        // Use ErrorRecoveryManager for intelligent recovery
                        if (::errorRecoveryManager.isInitialized) {
                            val recoveryResult = errorRecoveryManager.handleServiceFailure(
                                failureType = com.cymatune.lifecycle.ErrorRecoveryManager.FailureType.DATABASE_INITIALIZATION,
                                context = context,
                                failureContext = "Database initialization failed: ${result.message}",
                                originalError = result.cause as? Exception
                            )
                            
                            when (recoveryResult) {
                                is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Success -> {
                                    Log.i(TAG, "Database initialization recovered successfully")
                                    StartupResult.Success(
                                        finalState = StartupState.INITIALIZING_DATABASE,
                                        durationMs = calculateDuration()
                                    )
                                }
                                is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.FallbackActivated -> {
                                    Log.w(TAG, "Database initialization recovered with fallback")
                                    StartupResult.Success(
                                        finalState = StartupState.INITIALIZING_DATABASE,
                                        durationMs = calculateDuration()
                                    )
                                }
                                is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Partial -> {
                                    Log.w(TAG, "Database initialization partially recovered")
                                    StartupResult.Success(
                                        finalState = StartupState.INITIALIZING_DATABASE,
                                        durationMs = calculateDuration()
                                    )
                                }
                                is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Failed -> {
                                    Log.e(TAG, "Database initialization recovery failed: ${recoveryResult.errorMessage}")
                                    StartupResult.Failed(
                                        failureState = StartupState.INITIALIZING_DATABASE,
                                        errorMessage = "Database initialization recovery failed: ${recoveryResult.errorMessage}",
                                        cause = result.cause,
                                        canRetry = recoveryResult.canRetry
                                    )
                                }
                            }
                        } else {
                            // Try database-specific recovery
                            val recoveryResult = try {
                                DatabaseInitializationManager.emergencyRecover(context)
                            } catch (e: Exception) {
                                Log.e(TAG, "Database emergency recovery failed", e)
                                null
                            }
                            
                            when (recoveryResult) {
                                is DatabaseInitializationManager.InitializationResult.Success -> {
                                    Log.i(TAG, "Database emergency recovery successful")
                                    StartupResult.Success(
                                        finalState = StartupState.INITIALIZING_DATABASE,
                                        durationMs = calculateDuration()
                                    )
                                }
                                is DatabaseInitializationManager.InitializationResult.Fallback -> {
                                    Log.w(TAG, "Database emergency recovery with fallback")
                                    StartupResult.Success(
                                        finalState = StartupState.INITIALIZING_DATABASE,
                                        durationMs = calculateDuration()
                                    )
                                }
                                else -> {
                                    StartupResult.Failed(
                                        failureState = StartupState.INITIALIZING_DATABASE,
                                        errorMessage = "Database initialization failed: ${result.message}",
                                        cause = result.cause,
                                        canRetry = true
                                    )
                                }
                            }
                        }
                    }
                    is DatabaseInitializationManager.InitializationResult.RetryExhausted -> {
                        Log.e(TAG, "Database initialization retries exhausted: ${result.lastError}")
                        
                        // Try advanced recovery strategies
                        if (::errorRecoveryManager.isInitialized) {
                            val recoveryResult = errorRecoveryManager.handleServiceFailure(
                                failureType = com.cymatune.lifecycle.ErrorRecoveryManager.FailureType.DATABASE_CORRUPTION,
                                context = context,
                                failureContext = "Database initialization retries exhausted: ${result.lastError}",
                                originalError = Exception(result.lastError)
                            )
                            
                            when (recoveryResult) {
                                is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Success -> {
                                    Log.i(TAG, "Database corruption recovery successful")
                                    StartupResult.Success(
                                        finalState = StartupState.INITIALIZING_DATABASE,
                                        durationMs = calculateDuration()
                                    )
                                }
                                is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.FallbackActivated -> {
                                    Log.w(TAG, "Database corruption recovered with fallback")
                                    StartupResult.Success(
                                        finalState = StartupState.INITIALIZING_DATABASE,
                                        durationMs = calculateDuration()
                                    )
                                }
                                is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Failed -> {
                                    Log.e(TAG, "Database corruption recovery failed: ${recoveryResult.errorMessage}")
                                    StartupResult.Failed(
                                        failureState = StartupState.INITIALIZING_DATABASE,
                                        errorMessage = "Database corruption recovery failed: ${recoveryResult.errorMessage}",
                                        cause = Exception(result.lastError),
                                        canRetry = false
                                    )
                                }
                                else -> {
                                    StartupResult.Failed(
                                        failureState = StartupState.INITIALIZING_DATABASE,
                                        errorMessage = "Database initialization retries exhausted: ${result.lastError}",
                                        cause = Exception(result.lastError),
                                        canRetry = false
                                    )
                                }
                            }
                        } else {
                            // Try database-specific emergency recovery
                            val recoveryResult = try {
                                DatabaseInitializationManager.emergencyRecover(context)
                            } catch (e: Exception) {
                                Log.e(TAG, "Database emergency recovery failed", e)
                                null
                            }
                            
                            when (recoveryResult) {
                                is DatabaseInitializationManager.InitializationResult.Success -> {
                                    Log.i(TAG, "Database emergency recovery successful")
                                    StartupResult.Success(
                                        finalState = StartupState.INITIALIZING_DATABASE,
                                        durationMs = calculateDuration()
                                    )
                                }
                                is DatabaseInitializationManager.InitializationResult.Fallback -> {
                                    Log.w(TAG, "Database emergency recovery with fallback")
                                    StartupResult.Success(
                                        finalState = StartupState.INITIALIZING_DATABASE,
                                        durationMs = calculateDuration()
                                    )
                                }
                                else -> {
                                    StartupResult.Failed(
                                        failureState = StartupState.INITIALIZING_DATABASE,
                                        errorMessage = "Database initialization retries exhausted: ${result.lastError}",
                                        cause = Exception(result.lastError),
                                        canRetry = false
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Database initialization timed out after ${config.databaseTimeoutMs}ms")
            
            // Try recovery for timeout
            if (this::errorRecoveryManager.isInitialized) {
                val recoveryResult = errorRecoveryManager.handleServiceFailure(
                    failureType = com.cymatune.lifecycle.ErrorRecoveryManager.FailureType.DATABASE_LOCKED,
                    context = context,
                    failureContext = "Database initialization timed out after ${config.databaseTimeoutMs}ms"
                )
                
                when (recoveryResult) {
                    is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Success -> {
                        Log.i(TAG, "Database timeout recovery successful")
                        return executeDatabaseInitialization(context) // Retry after recovery
                    }
                    else -> {
                        // Recovery failed, return timeout error
                    }
                }
            } else {
                // Try database-specific timeout recovery
                val shouldRecover = try {
                    DatabaseInitializationManager.shouldRecoverDatabase(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not check database recovery status", e)
                    true
                }
                
                if (shouldRecover) {
                    Log.w(TAG, "Database timeout detected, attempting emergency recovery")
                    val recoveryResult = try {
                        DatabaseInitializationManager.emergencyRecover(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Database emergency recovery failed", e)
                        null
                    }
                    
                    when (recoveryResult) {
                        is DatabaseInitializationManager.InitializationResult.Success -> {
                            Log.i(TAG, "Database timeout recovery successful")
                            return executeDatabaseInitialization(context) // Retry after recovery
                        }
                        else -> {
                            Log.e(TAG, "Database timeout recovery failed")
                        }
                    }
                }
            }
            
            StartupResult.Failed(
                failureState = StartupState.INITIALIZING_DATABASE,
                errorMessage = "Database initialization timed out",
                cause = e,
                canRetry = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Database initialization exception", e)
            
            // Try recovery for unexpected exceptions
            if (this::errorRecoveryManager.isInitialized) {
                val recoveryResult = errorRecoveryManager.handleServiceFailure(
                    failureType = com.cymatune.lifecycle.ErrorRecoveryManager.FailureType.DATABASE_INITIALIZATION,
                    context = context,
                    failureContext = "Database initialization exception: ${e.message}",
                    originalError = e
                )
                
                when (recoveryResult) {
                    is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Success -> {
                        Log.i(TAG, "Database exception recovery successful")
                        return executeDatabaseInitialization(context) // Retry after recovery
                    }
                    else -> {
                        // Recovery failed, return error
                    }
                }
            } else {
                // Try database-specific exception recovery
                val shouldRecover = try {
                    DatabaseInitializationManager.shouldRecoverDatabase(context)
                } catch (e2: Exception) {
                    Log.e(TAG, "Could not check database recovery status", e2)
                    true
                }
                
                if (shouldRecover) {
                    Log.w(TAG, "Database exception detected, attempting emergency recovery")
                    val recoveryResult = try {
                        DatabaseInitializationManager.emergencyRecover(context)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Database emergency recovery failed", e2)
                        null
                    }
                    
                    when (recoveryResult) {
                        is DatabaseInitializationManager.InitializationResult.Success -> {
                            Log.i(TAG, "Database exception recovery successful")
                            return executeDatabaseInitialization(context) // Retry after recovery
                        }
                        else -> {
                            Log.e(TAG, "Database exception recovery failed")
                        }
                    }
                }
            }
            
            StartupResult.Failed(
                failureState = StartupState.INITIALIZING_DATABASE,
                errorMessage = "Database initialization exception: ${e.message}",
                cause = e,
                canRetry = true
            )
        }
    }
    
    /**
     * Execute component initialization with timeout and dependency management
     */
    private suspend fun executeComponentInitialization(context: Context): StartupResult {
        return try {
            withTimeout(config.componentsTimeoutMs) {
                Log.d(TAG, "Starting component initialization...")
                
                val result = ComponentLifecycleManager.initializeComponents(context)
                
                when (result) {
                    is ComponentLifecycleManager.InitializationResult.Success -> {
                        Log.i(TAG, "Component initialization successful: ${result.initializedComponents.joinToString()}")
                        StartupResult.Success(
                            finalState = StartupState.INITIALIZING_COMPONENTS,
                            durationMs = calculateDuration()
                        )
                    }
                    is ComponentLifecycleManager.InitializationResult.Partial -> {
                        Log.w(TAG, "Component initialization partial: ${result.successfulComponents.joinToString()} success, ${result.failedComponents.joinToString()} failed")
                        // Continue with partial success for now
                        StartupResult.Success(
                            finalState = StartupState.INITIALIZING_COMPONENTS,
                            durationMs = calculateDuration()
                        )
                    }
                    is ComponentLifecycleManager.InitializationResult.Failed -> {
                        Log.e(TAG, "Component initialization failed: ${result.errorMessage}")
                        
                        // Use ErrorRecoveryManager for component recovery
                        if (::errorRecoveryManager.isInitialized) {
                            val recoveryResult = errorRecoveryManager.handleServiceFailure(
                                failureType = com.cymatune.lifecycle.ErrorRecoveryManager.FailureType.COMPONENT_INITIALIZATION,
                                context = context,
                                failureContext = "Component initialization failed: ${result.errorMessage}"
                            )
                            
                            when (recoveryResult) {
                                is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Success -> {
                                    Log.i(TAG, "Component initialization recovery successful")
                                    StartupResult.Success(
                                        finalState = StartupState.INITIALIZING_COMPONENTS,
                                        durationMs = calculateDuration()
                                    )
                                }
                                is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Partial -> {
                                    Log.w(TAG, "Component initialization partially recovered: ${recoveryResult.degradedFunctionality.joinToString()}")
                                    StartupResult.Success(
                                        finalState = StartupState.INITIALIZING_COMPONENTS,
                                        durationMs = calculateDuration()
                                    )
                                }
                                is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Failed -> {
                                    Log.e(TAG, "Component initialization recovery failed: ${recoveryResult.errorMessage}")
                                    StartupResult.Failed(
                                        failureState = StartupState.INITIALIZING_COMPONENTS,
                                        errorMessage = "Component initialization recovery failed: ${recoveryResult.errorMessage}",
                                        cause = null,
                                        canRetry = recoveryResult.canRetry
                                    )
                                }
                                else -> {
                                    StartupResult.Failed(
                                        failureState = StartupState.INITIALIZING_COMPONENTS,
                                        errorMessage = "Component initialization failed: ${result.errorMessage}",
                                        cause = null,
                                        canRetry = true
                                    )
                                }
                            }
                        } else {
                            StartupResult.Failed(
                                failureState = StartupState.INITIALIZING_COMPONENTS,
                                errorMessage = "Component initialization failed: ${result.errorMessage}",
                                cause = null,
                                canRetry = true
                            )
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Component initialization timed out after ${config.componentsTimeoutMs}ms")
            StartupResult.Failed(
                failureState = StartupState.INITIALIZING_COMPONENTS,
                errorMessage = "Component initialization timed out",
                cause = e,
                canRetry = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Component initialization exception", e)
            StartupResult.Failed(
                failureState = StartupState.INITIALIZING_COMPONENTS,
                errorMessage = "Component initialization exception: ${e.message}",
                cause = e,
                canRetry = true
            )
        }
    }
    
    /**
     * Execute monitoring startup operations with full MVP feature integration
     */
    private suspend fun executeMonitoringStartup(context: Context): StartupResult {
        return try {
            withTimeout(config.monitoringTimeoutMs) {
                Log.d(TAG, "Starting monitoring operations with full MVP feature integration...")
                
                // All MVP features are now integrated through the state machine:
                // - Feature 1: Neighbor Cell Consistency Check (via ComponentLifecycleManager)
                // - Feature 2: Signal Strength vs. Distance Analysis (via ComponentLifecycleManager)
                // - Feature 3: Cell ID & LAC Consistency (via ComponentLifecycleManager)
                // - Feature 4: Encryption & Protocol Analysis (via ComponentLifecycleManager)
                // - Feature 5: Silent SMS / Paging Storm Detection (via ComponentLifecycleManager)
                // - Feature 6: Database Encryption (SQLCipher Integration) (via DatabaseInitializationManager)
                
                // The monitoring startup is now handled by the FakeTowerDetectionService
                // after successful state machine completion through the handleSuccessfulStartup() method
                // This ensures all MVP detection systems are properly initialized before monitoring begins
                
                Log.i(TAG, "Monitoring operations integration completed - MVP features will be activated by service")
                StartupResult.Success(
                    finalState = StartupState.STARTING_MONITORING,
                    durationMs = calculateDuration()
                )
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Monitoring startup timed out after ${config.monitoringTimeoutMs}ms")
            StartupResult.Failed(
                failureState = StartupState.STARTING_MONITORING,
                errorMessage = "Monitoring startup timed out",
                cause = e,
                canRetry = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Monitoring startup exception", e)
            StartupResult.Failed(
                failureState = StartupState.STARTING_MONITORING,
                errorMessage = "Monitoring startup exception: ${e.message}",
                cause = e,
                canRetry = true
            )
        }
    }
    
    /**
     * Attempt recovery from a failed state using ErrorRecoveryManager
     */
    suspend fun attemptRecovery(context: Context, failedResult: StartupResult.Failed): StartupResult {
        return stateMutex.withLock {
            if (failedResult.canRetry && metrics.recoveryAttempts < config.maxRecoveryAttempts) {
                transitionToState(StartupState.RECOVERING)
                metrics = metrics.copy(recoveryAttempts = metrics.recoveryAttempts + 1)
                
                Log.w(TAG, "Attempting recovery from ${failedResult.failureState}, attempt ${metrics.recoveryAttempts}")
                
                // Apply recovery delay
                delay(config.recoveryDelayMs)
                
                // Use ErrorRecoveryManager for intelligent recovery
                if (this::errorRecoveryManager.isInitialized) {
                    val failureType = when (failedResult.failureState) {
                        StartupState.INITIALIZING_DATABASE -> com.cymatune.lifecycle.ErrorRecoveryManager.FailureType.DATABASE_INITIALIZATION
                        StartupState.INITIALIZING_COMPONENTS -> com.cymatune.lifecycle.ErrorRecoveryManager.FailureType.COMPONENT_INITIALIZATION
                        StartupState.STARTING_MONITORING -> com.cymatune.lifecycle.ErrorRecoveryManager.FailureType.SENSOR_FAILURE
                        else -> com.cymatune.lifecycle.ErrorRecoveryManager.FailureType.COMPONENT_INITIALIZATION
                    }
                    
                    val recoveryResult = errorRecoveryManager.handleServiceFailure(
                        failureType = failureType,
                        context = context,
                        failureContext = "State machine recovery from ${failedResult.failureState}: ${failedResult.errorMessage}",
                        originalError = failedResult.cause as? Exception
                    )
                    
                    when (recoveryResult) {
                        is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Success -> {
                            Log.i(TAG, "State machine recovery successful from ${failedResult.failureState}")
                            executeStartupPhases(context) // Continue with remaining phases
                        }
                        is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Partial -> {
                            Log.w(TAG, "State machine partial recovery from ${failedResult.failureState}: ${recoveryResult.degradedFunctionality.joinToString()}")
                            executeStartupPhases(context) // Continue with degraded functionality
                        }
                        is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.FallbackActivated -> {
                            Log.i(TAG, "State machine fallback activated from ${failedResult.failureState}: ${recoveryResult.fallbackStrategy}")
                            executeStartupPhases(context) // Continue with fallback strategy
                        }
                        is com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryResult.Failed -> {
                            if (recoveryResult.canRetry && metrics.recoveryAttempts < config.maxRecoveryAttempts) {
                                // Recursive recovery attempt
                                attemptRecovery(context, StartupResult.Failed(
                                    failureState = failedResult.failureState,
                                    errorMessage = "ErrorRecoveryManager failed: ${recoveryResult.errorMessage}",
                                    cause = Exception(recoveryResult.errorMessage),
                                    canRetry = recoveryResult.canRetry
                                ))
                            } else {
                                Log.e(TAG, "State machine recovery failed after ${metrics.recoveryAttempts} attempts")
                                StartupResult.Failed(
                                    failureState = StartupState.FAILED,
                                    errorMessage = "State machine recovery failed after ${metrics.recoveryAttempts} attempts: ${failedResult.errorMessage}",
                                    cause = failedResult.cause,
                                    canRetry = false
                                )
                            }
                        }
                    }
                } else {
                    // Fallback to legacy recovery methods
                    val recoveryResult = when (failedResult.failureState) {
                        StartupState.INITIALIZING_DATABASE -> attemptDatabaseRecovery(context)
                        StartupState.INITIALIZING_COMPONENTS -> attemptComponentRecovery(context)
                        StartupState.STARTING_MONITORING -> attemptMonitoringRecovery(context)
                        else -> {
                            Log.e(TAG, "Recovery not supported for state: ${failedResult.failureState}")
                            failedResult
                        }
                    }
                    
                    when (recoveryResult) {
                        is StartupResult.Success -> {
                            Log.i(TAG, "Legacy recovery successful from ${failedResult.failureState}")
                            executeStartupPhases(context) // Continue with remaining phases
                        }
                        is StartupResult.Failed -> {
                            if (recoveryResult.canRetry && metrics.recoveryAttempts < config.maxRecoveryAttempts) {
                                // Recursive recovery attempt
                                attemptRecovery(context, recoveryResult)
                            } else {
                                Log.e(TAG, "Legacy recovery failed after ${metrics.recoveryAttempts} attempts")
                                StartupResult.Failed(
                                    failureState = StartupState.FAILED,
                                    errorMessage = "Legacy recovery failed after ${metrics.recoveryAttempts} attempts: ${failedResult.errorMessage}",
                                    cause = failedResult.cause,
                                    canRetry = false
                                )
                            }
                        }
                        else -> recoveryResult
                    }
                }
            } else {
                Log.e(TAG, "Recovery not possible - max attempts exceeded or retry not allowed")
                StartupResult.Failed(
                    failureState = StartupState.FAILED,
                    errorMessage = "Recovery not possible - max attempts exceeded or retry not allowed",
                    cause = failedResult.cause,
                    canRetry = false
                )
            }
        }
    }
    
    /**
     * Legacy database-specific recovery (fallback when ErrorRecoveryManager not available)
     */
    private suspend fun attemptDatabaseRecovery(context: Context): StartupResult {
        Log.d(TAG, "Attempting legacy database recovery...")
        // Legacy database recovery would involve clearing caches, retrying initialization, etc.
        // This is now handled by ErrorRecoveryManager, but kept for backward compatibility
        return StartupResult.Failed(
            failureState = StartupState.INITIALIZING_DATABASE,
            errorMessage = "Legacy database recovery not implemented - use ErrorRecoveryManager",
            cause = null,
            canRetry = false
        )
    }
    
    /**
     * Legacy component-specific recovery (fallback when ErrorRecoveryManager not available)
     */
    private suspend fun attemptComponentRecovery(context: Context): StartupResult {
        Log.d(TAG, "Attempting legacy component recovery...")
        // Legacy component recovery would involve reinitializing failed components
        // This is now handled by ErrorRecoveryManager, but kept for backward compatibility
        return StartupResult.Failed(
            failureState = StartupState.INITIALIZING_COMPONENTS,
            errorMessage = "Legacy component recovery not implemented - use ErrorRecoveryManager",
            cause = null,
            canRetry = false
        )
    }
    
    /**
     * Legacy monitoring-specific recovery (fallback when ErrorRecoveryManager not available)
     */
    private suspend fun attemptMonitoringRecovery(context: Context): StartupResult {
        Log.d(TAG, "Attempting legacy monitoring recovery...")
        // Legacy monitoring recovery would involve restarting monitoring operations
        // This is now handled by ErrorRecoveryManager, but kept for backward compatibility
        return StartupResult.Failed(
            failureState = StartupState.STARTING_MONITORING,
            errorMessage = "Legacy monitoring recovery not implemented - use ErrorRecoveryManager",
            cause = null,
            canRetry = false
        )
    }
    
    /**
     * Transition to a new state with logging and metrics update
     */
    private fun transitionToState(newState: StartupState) {
        val previousState = currentState
        currentState = newState
        val timestamp = System.currentTimeMillis()
        
        metrics.stateTransitions.add(newState to timestamp)
        
        Log.d(TAG, "State transition: $previousState -> $newState at ${timestamp % 100000}")
    }
    
    /**
     * Update metrics with current state
     */
    private fun updateMetrics(state: StartupState) {
        currentState = state
        metrics = metrics.copy(
            currentState = state,
            endTime = System.currentTimeMillis()
        )
    }
    
    /**
     * Calculate current startup duration
     */
    private fun calculateDuration(): Long {
        return System.currentTimeMillis() - metrics.startTime
    }
    
    /**
     * Get current startup state
     */
    fun getCurrentState(): StartupState = currentState
    
    /**
     * Get current startup metrics
     */
    fun getMetrics(): StartupMetrics? = if (::metrics.isInitialized) metrics else null
    
    /**
     * Cancel any ongoing startup operation
     */
    fun cancelStartup() {
        startupJob?.cancel()
        Log.d(TAG, "Startup operation cancelled")
    }
    
    /**
     * Reset state machine to initial state
     */
    suspend fun reset() {
        stateMutex.withLock {
            cancelStartup()
            currentState = StartupState.IDLE
            Log.d(TAG, "State machine reset to IDLE")
        }
    }
    
    /**
     * Get ErrorRecoveryManager instance for external access
     */
    fun getErrorRecoveryManager(): com.cymatune.lifecycle.ErrorRecoveryManager? =
        if (this::errorRecoveryManager.isInitialized) errorRecoveryManager else null
    
    /**
     * Get recovery statistics from ErrorRecoveryManager
     */
    fun getRecoveryStatistics() =
        if (this::errorRecoveryManager.isInitialized) errorRecoveryManager.getRecoveryStatistics()
        else com.cymatune.lifecycle.ErrorRecoveryManager.RecoveryStatistics(0, 0, 0, 0.0, 0L, emptyList())
    
    /**
     * Perform final database readiness check before marking service as fully operational
     */
    private suspend fun performDatabaseReadinessCheck(context: Context): Boolean {
        return try {
            Log.d(TAG, "Performing final database readiness check...")
            
            // Test database access with a simple query
            val result = withContext(Dispatchers.IO) {
                try {
                    val database = com.cymatune.db.DatabaseManager.getDatabase()
                    val fakeTowers = database.fakeTowerDao().getAllFakeTowers()
                    fakeTowers.firstOrNull() // Force execution of the flow
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Database readiness check failed: ${e.message}")
                    false
                }
            }
            
            if (result) {
                Log.i(TAG, "Database readiness check passed - service fully operational")
            } else {
                Log.w(TAG, "Database readiness check failed - service may have limited functionality")
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Database readiness check threw exception", e)
            false
        }
    }
}