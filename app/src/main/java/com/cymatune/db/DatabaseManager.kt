package com.cymatune.db

import android.content.Context
import java.util.concurrent.atomic.AtomicReference
import android.util.Log
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

object DatabaseManager {
    private val dbReference = AtomicReference<AppDatabase>()

    fun initialize(context: Context) {
        if (dbReference.get() == null) {
            synchronized(this) {
                if (dbReference.get() == null) {
                    Log.d("DatabaseManager", "Creating database instance")
                    val db = AppDatabase.getDatabase(context.applicationContext)
                    dbReference.set(db)
                    Log.i("DatabaseManager", "Database initialized")
                }
            }
        }
    }

    fun getDatabase(): AppDatabase {
        val db = dbReference.get()
        if (db == null) {
            throw IllegalStateException("DatabaseManager must be initialized before use. Call initialize(context) first.")
        }
        return db
    }

    suspend fun waitForDatabase(): AppDatabase {
        var db = dbReference.get()
        if (db != null) return db

        Log.i("DatabaseManager", "Waiting for database initialization...")

        var attempts = 0
        while (db == null && attempts < 100) {
            kotlinx.coroutines.delay(100)
            db = dbReference.get()
            attempts++
        }

        if (db == null) {
            throw IllegalStateException("Database initialization timed out")
        }

        return db
    }

    fun clearDatabaseInstance() {
        try {
            val db = dbReference.get()
            if (db != null && db.isOpen) {
                db.close()
            }
        } catch (e: Exception) {
            Log.e("DatabaseManager", "Error closing database", e)
        }
        dbReference.set(null)
        AppDatabase.clearInstance()
        Log.i("DatabaseManager", "Database instance cleared")
    }

    fun validateDatabaseFile(context: Context): Boolean {
        return try {
            val dbFile = context.getDatabasePath("fake_tower_database")
            dbFile.exists()
        } catch (e: Exception) {
            false
        }
    }

    fun clearDatabaseData(context: Context): Boolean {
        return try {
            clearDatabaseInstance()
            val dbFile = context.getDatabasePath("fake_tower_database")
            if (dbFile.exists()) {
                dbFile.delete()
            }
            val journalFile = context.getDatabasePath("fake_tower_database-journal")
            journalFile.delete()
            val walFile = context.getDatabasePath("fake_tower_database-wal")
            walFile.delete()
            val shmFile = context.getDatabasePath("fake_tower_database-shm")
            shmFile.delete()
            true
        } catch (e: Exception) {
            false
        }
    }

    val fakeTowerDao: FakeTowerDao
        get() = getDatabase().fakeTowerDao()

    val towerDao: TowerDao
        get() = getDatabase().towerDao()

    val protocolHandshakeDao: ProtocolHandshakeDao
        get() = getDatabase().protocolHandshakeDao()

    val locationHistoryDao: LocationHistoryDao
        get() = getDatabase().locationHistoryDao()

    val signalBaselineDao: SignalBaselineDao
        get() = getDatabase().signalBaselineDao()

    val neighborHistoryDao: NeighborHistoryDao
        get() = getDatabase().neighborHistoryDao()

    val lacCidPatternDao: LacCidPatternDao
        get() = getDatabase().lacCidPatternDao()

    val smsEventDao: SmsEventDao
        get() = getDatabase().smsEventDao()

    val pagingEventDao: PagingEventDao
        get() = getDatabase().pagingEventDao()

val pagingStormDao: PagingStormDao
get() = getDatabase().pagingStormDao()

/**
 * Force recreate the database - clear and reinitialize.
 * Returns true if successful, false otherwise.
 */
fun forceRecreateDatabase(context: Context): Boolean {
return try {
Log.d("DatabaseManager", "Force recreating database")
clearDatabaseInstance()
// Ensure files are deleted as well
clearDatabaseData(context)
// Reinitialize
initialize(context)
true
} catch (e: Exception) {
Log.e("DatabaseManager", "Force recreate failed", e)
false
}
}
}

enum class EncryptionStatus {
    UNENCRYPTED
}