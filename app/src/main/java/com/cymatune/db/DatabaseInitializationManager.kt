package com.cymatune.db

import android.content.Context
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Robust database initialization with retry logic and fallback mechanisms.
 * Provides thread-safe database initialization with exponential backoff retry logic,
 * comprehensive error handling, and database health validation.
 *
 * This class wraps the existing DatabaseManager to provide enhanced reliability
 * without breaking existing functionality or APIs.
 *
 * Cymatune Lite: Uses unencrypted SQLite database only. SQLCipher has been removed.
 */
object DatabaseInitializationManager {
    
    private const val TAG = "DatabaseInitializationManager"
    
    // Configuration constants
    private const val MAX_RETRY_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 1000L
    private const val INITIALIZATION_TIMEOUT_MS = 30000L // 30 seconds
    private const val EXPONENTIAL_BACKOFF_BASE = 2L
    
    // State management
    private val initializationMutex = Mutex()
    private val isInitialized = AtomicBoolean(false)
    private val initializationJob = java.util.concurrent.atomic.AtomicReference<kotlinx.coroutines.Job?>(null)
    
    /**
     * Result types for database initialization with retry capabilities
     */
sealed class InitializationResult {
data class Success(val database: AppDatabase) : InitializationResult()
data class Fallback(val message: String, val database: AppDatabase? = null) : InitializationResult()
data class Error(val message: String, val cause: Throwable? = null) : InitializationResult()
data class RetryExhausted(val attempts: Int, val lastError: String) : InitializationResult()
}

/**
* Health status for database validation
*/
data class HealthStatus(
val isHealthy: Boolean,
val performanceScore: Double,
val lastCheckTime: Long,
val issues: List<String> = emptyList()
)

/**
     * Initialize database with robust retry logic and fallback mechanisms
     *
     * @param context Android context
     * @return InitializationResult indicating success, fallback, or error
     */
    suspend fun initializeDatabaseWithRetry(context: Context): InitializationResult {
        // Fast path: already initialized — skip everything
        if (isInitialized.get()) {
            Log.d(TAG, "Database already initialized, returning existing instance")
            try {
                val existingDb = com.cymatune.db.DatabaseManager.getDatabase()
                return InitializationResult.Success(existingDb)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Existing database not available, attempting reinitialization")
                isInitialized.set(false)
            }
        }
        
        return withTimeoutOrNull(INITIALIZATION_TIMEOUT_MS) {
            initializationMutex.withLock {
                // Double-check after acquiring lock
                if (isInitialized.get()) {
                    Log.d(TAG, "Database already initialized (after lock), returning existing instance")
                    try {
                        val existingDb = com.cymatune.db.DatabaseManager.getDatabase()
                        return@withTimeoutOrNull InitializationResult.Success(existingDb)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Existing database not available after lock, reinitializing")
                        isInitialized.set(false)
                    }
                }
                
                try {
                    performDatabaseInitialization(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Database initialization failed with exception", e)
                    InitializationResult.Error("Database initialization failed: ${e.message}", e)
                }
            }
        } ?: InitializationResult.Error("Database initialization timed out after ${INITIALIZATION_TIMEOUT_MS}ms")
    }
    
    /**
     * Perform the actual database initialization with retry logic
     */
    private suspend fun performDatabaseInitialization(context: Context): InitializationResult {
        var lastError: String = ""
        
        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            Log.d(TAG, "Database initialization attempt $attempt of $MAX_RETRY_ATTEMPTS")
            
            try {
                com.cymatune.db.DatabaseManager.initialize(context)
                val database = com.cymatune.db.DatabaseManager.getDatabase()
                
                if (validateDatabaseIntegrity(database)) {
                    isInitialized.set(true)
                    Log.i(TAG, "Database initialized successfully on attempt $attempt")
                    return InitializationResult.Success(database)
                } else {
                    lastError = "Database integrity validation failed"
                    if (attempt < MAX_RETRY_ATTEMPTS) {
                        delay(calculateRetryDelay(attempt))
                        continue
                    } else {
                        return InitializationResult.Error("Database integrity validation failed after $MAX_RETRY_ATTEMPTS attempts")
                    }
                }
            } catch (e: Exception) {
                lastError = "Exception on attempt $attempt: ${e.message}"
                Log.w(TAG, "Database initialization attempt $attempt failed with exception", e)
                
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    delay(calculateRetryDelay(attempt))
                    continue
                } else {
                    return InitializationResult.Error("Database initialization failed: ${e.message}", e)
                }
            }
        }
        
        return InitializationResult.Error("Database initialization failed after $MAX_RETRY_ATTEMPTS attempts")
    }
    
    /**
     * Calculate exponential backoff delay
     */
    private fun calculateRetryDelay(attempt: Int): Long {
        val delay = RETRY_DELAY_MS * Math.pow(EXPONENTIAL_BACKOFF_BASE.toDouble(), (attempt - 1).toDouble()).toLong()
        val maxDelay = INITIALIZATION_TIMEOUT_MS / 4 // Don't delay more than 25% of timeout
        return minOf(delay, maxDelay)
    }
    
    /**
     * Perform comprehensive health check on database
     */
    suspend fun performHealthCheck(database: AppDatabase): HealthStatus {
        return withContext(Dispatchers.IO) {
            val issues = mutableListOf<String>()
            var score = 100.0
            
            try {
                // Check if database is open
                if (!database.isOpen) {
                    issues.add("Database is not open")
                    score -= 30.0
                }
                
                // Test basic query performance
                try {
                    val start = System.currentTimeMillis()
                    database.fakeTowerDao().getAllFakeTowers()
                    val elapsed = System.currentTimeMillis() - start
                    
                    if (elapsed > 5000) {
                        issues.add("Slow query: ${elapsed}ms")
                        score -= 25.0
                    } else if (elapsed > 1000) {
                        issues.add("Moderate query: ${elapsed}ms")
                        score -= 10.0
                    }
                } catch (e: Exception) {
                    issues.add("Query failed: ${e.message}")
                    score -= 50.0
                }
                
                if (!validateDatabaseIntegrity(database)) {
                    issues.add("Integrity validation failed")
                    score -= 25.0
                }
                
                val healthy = score >= 70.0 && issues.isEmpty()
                HealthStatus(
                    isHealthy = healthy,
                    performanceScore = score,
                    lastCheckTime = System.currentTimeMillis(),
                    issues = issues
                )
            } catch (e: Exception) {
                Log.e(TAG, "Health check exception", e)
                HealthStatus(
                    isHealthy = false,
                    performanceScore = 0.0,
                    lastCheckTime = System.currentTimeMillis(),
                    issues = listOf("Exception: ${e.message}")
                )
            }
        }
    }
    
    /**
     * Validate database integrity after initialization
     */
    fun validateDatabaseIntegrity(database: AppDatabase): Boolean {
        return try {
            Log.d(TAG, "Validating database integrity...")
            
            var allPassed = true
            
            try {
                database.fakeTowerDao().getAllFakeTowers()
            } catch (e: Exception) {
                Log.e(TAG, "FakeTowerDao validation failed", e)
                allPassed = false
            }
            
            if (!database.isOpen) {
                Log.e(TAG, "Database is not open")
                allPassed = false
            }
            
            Log.i(TAG, "Integrity validation: ${if (allPassed) "passed" else "failed"}")
            allPassed
        } catch (e: Exception) {
            Log.e(TAG, "Integrity check exception", e)
            false
        }
    }
    
    /**
     * Check if database is currently initialized
     */
    fun isDatabaseInitialized(): Boolean {
        return isInitialized.get()
    }
    
    /**
     * Force reinitialization of database (useful for testing or recovery)
     */
    suspend fun forceReinitialize(context: Context): InitializationResult {
        return withContext(Dispatchers.IO) {
            initializationJob.get()?.cancel()
            isInitialized.set(false)
            
            try {
                com.cymatune.db.DatabaseManager.clearDatabaseInstance()
            } catch (e: Exception) {
                Log.w(TAG, "Could not clear database instance: ${e.message}")
            }
            
            performDatabaseInitialization(context)
        }
    }
    
/**
* Get initialization status for monitoring
*/
fun getInitializationStatus(): String {
return "DatabaseInitializationManager Status:\n" +
"- Initialized: ${isInitialized.get()}\n" +
"- Max Retry Attempts: $MAX_RETRY_ATTEMPTS\n" +
"- Retry Delay: ${RETRY_DELAY_MS}ms\n" +
"- Timeout: ${INITIALIZATION_TIMEOUT_MS}ms\n" +
"- Current Job Active: ${initializationJob.get()?.isActive}"
}

/**
* Check if database should attempt recovery (always true for Lite version - simple retry)
*/
fun shouldRecoverDatabase(context: Context): Boolean {
return try {
// For Lite unencrypted database, always attempt recovery on corruption
// since there's no encryption key issues
val db = com.cymatune.db.DatabaseManager.getDatabase()
db.isOpen
} catch (e: Exception) {
true // Always allow recovery attempt if database unavailable
}
}

/**
* Emergency database recovery - clears and reinitializes database
*/
suspend fun emergencyRecover(context: Context): InitializationResult {
return try {
Log.w(TAG, "Performing emergency database recovery")
com.cymatune.db.DatabaseManager.clearDatabaseInstance()
delay(500) // Brief pause to ensure file handles released
performDatabaseInitialization(context)
} catch (e: Exception) {
Log.e(TAG, "Emergency recovery failed", e)
InitializationResult.Error("Emergency recovery failed: ${e.message}", e)
}
}
}
