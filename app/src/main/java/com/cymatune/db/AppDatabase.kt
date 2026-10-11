package com.cymatune.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
entities = [FakeTower::class, com.cymatune.util.TowerInfo::class, com.cymatune.util.TowerObservation::class, ProtocolHandshake::class, LocationHistory::class, SignalBaseline::class, NeighborHistory::class, LacCidPattern::class, SMSEvent::class, PagingEvent::class, PagingStorm::class],
version = 14,
exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
abstract fun fakeTowerDao(): FakeTowerDao
abstract fun towerDao(): TowerDao
abstract fun protocolHandshakeDao(): ProtocolHandshakeDao
abstract fun locationHistoryDao(): LocationHistoryDao
abstract fun signalBaselineDao(): SignalBaselineDao
abstract fun neighborHistoryDao(): NeighborHistoryDao
abstract fun lacCidPatternDao(): LacCidPatternDao
abstract fun smsEventDao(): SmsEventDao
abstract fun pagingEventDao(): PagingEventDao
abstract fun pagingStormDao(): PagingStormDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabaseBuilder(context: android.content.Context): androidx.room.RoomDatabase.Builder<AppDatabase> {
            return androidx.room.Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "fake_tower_database"
            )
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
        .fallbackToDestructiveMigration()
        }

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                android.util.Log.d("AppDatabase", "DIAGNOSTIC: Creating new AppDatabase instance")
                val instance = getDatabaseBuilder(context).build()
                INSTANCE = instance
                android.util.Log.d("AppDatabase", "DIAGNOSTIC: AppDatabase instance created successfully")
                instance
            }
        }

        fun clearInstance() {
            try {
                if (INSTANCE?.isOpen == true) {
                    INSTANCE?.close()
                }
            } catch (e: Exception) {
                android.util.Log.e("AppDatabase", "Error closing database", e)
            }
            INSTANCE = null
        }
        
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add new columns for observationCount and precisionRadius
                database.execSQL("ALTER TABLE FakeTower ADD COLUMN observationCount INTEGER DEFAULT 1")
                database.execSQL("ALTER TABLE FakeTower ADD COLUMN precisionRadius REAL DEFAULT 1000.0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add encryption protocol columns to FakeTower
                database.execSQL("ALTER TABLE FakeTower ADD COLUMN encryptionProtocol TEXT")
                database.execSQL("ALTER TABLE FakeTower ADD COLUMN cipherStrength INTEGER DEFAULT 0")
                database.execSQL("ALTER TABLE FakeTower ADD COLUMN authenticationMethod TEXT")
                database.execSQL("ALTER TABLE FakeTower ADD COLUMN securityCapabilities TEXT")
                
                // Create new table for protocol handshake details
                database.execSQL("""
                    CREATE TABLE ProtocolHandshake (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        towerId INTEGER,
                        protocolType TEXT,
                        cipherAlgorithm TEXT,
                        handshakeDuration INTEGER,
                        success BOOLEAN,
                        timestamp LONG,
                        suspicionScore REAL DEFAULT 0.0,
                        details TEXT
                    )
                """)
            }
        }
        
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create location_history table
                database.execSQL("""
                    CREATE TABLE location_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        cid INTEGER NOT NULL,
                        lac INTEGER NOT NULL,
                        mcc INTEGER NOT NULL,
                        mnc INTEGER NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        firstSeen INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL,
                        totalConnections INTEGER DEFAULT 1,
                        totalDurationMinutes INTEGER DEFAULT 0,
                        label TEXT,
                        trustBonus INTEGER DEFAULT 0
                    )
                """)
            }
        }
        
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create signal_baseline table
                database.execSQL("""
                    CREATE TABLE signal_baseline (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        cid INTEGER NOT NULL,
                        lac INTEGER NOT NULL,
                        mcc INTEGER NOT NULL,
                        mnc INTEGER NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        locationRadius REAL DEFAULT 100.0,
                        avgRsrp REAL NOT NULL,
                        avgRsrq REAL,
                        avgSinr REAL,
                        avgNeighborCount INTEGER NOT NULL,
                        stdDevRsrp REAL NOT NULL,
                        stdDevNeighborCount REAL NOT NULL,
                        sampleCount INTEGER DEFAULT 1,
                        firstSeen INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL,
                        primaryHour INTEGER
                    )
                """)
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add pci, arfcn, and timingAdvance columns to FakeTower
                database.execSQL("ALTER TABLE FakeTower ADD COLUMN pci INTEGER")
                database.execSQL("ALTER TABLE FakeTower ADD COLUMN arfcn INTEGER")
                database.execSQL("ALTER TABLE FakeTower ADD COLUMN timingAdvance INTEGER")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Make MCC and MNC nullable in FakeTower for neighbor cells without network info
                // SQLite doesn't support ALTER COLUMN, so we need to recreate the table
                
                // 1. Create new table with nullable MCC/MNC
                database.execSQL("""
                    CREATE TABLE FakeTower_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        cid INTEGER NOT NULL,
                        lac INTEGER NOT NULL,
                        mcc INTEGER NOT NULL DEFAULT 0,
                        mnc INTEGER NOT NULL DEFAULT 0,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        detectionTime INTEGER NOT NULL,
                        accuracy REAL NOT NULL,
                        reason TEXT NOT NULL,
                        signalStrength INTEGER NOT NULL,
                        networkType TEXT NOT NULL,
                        observationCount INTEGER NOT NULL,
                        precisionRadius REAL NOT NULL,
                        isTriangulated INTEGER NOT NULL,
                        isLocked INTEGER NOT NULL DEFAULT 0,
                        cipherStrength INTEGER NOT NULL DEFAULT 0,
                        authenticationMethod TEXT,
                        securityCapabilities TEXT,
                        trustScore INTEGER NOT NULL DEFAULT 100,
                        pci INTEGER,
                        arfcn INTEGER,
                        timingAdvance INTEGER
                    )
                """)
                
                // 2. Copy data from old table
                database.execSQL("""
                    INSERT INTO FakeTower_new 
                    SELECT * FROM FakeTower
                """)
                
                // 3. Drop old table
                database.execSQL("DROP TABLE FakeTower")
                
                // 4. Rename new table
                database.execSQL("ALTER TABLE FakeTower_new RENAME TO FakeTower")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create lac_cid_patterns table for LAC/CID pattern tracking
                database.execSQL("""
                    CREATE TABLE lac_cid_patterns (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        cid INTEGER NOT NULL,
                        lac INTEGER NOT NULL,
                        mcc INTEGER NOT NULL,
                        mnc INTEGER NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        locationRadius REAL DEFAULT 500.0,
                        patternType TEXT NOT NULL,
                        patternCount INTEGER DEFAULT 1,
                        firstSeen INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL,
                        avgDistanceFromCenter REAL DEFAULT 0.0,
                        maxDistanceFromCenter REAL DEFAULT 0.0,
                        minDistanceFromCenter REAL DEFAULT 1.7976931348623157E308,
                        confidenceScore REAL DEFAULT 100.0,
                        anomalyScore REAL DEFAULT 0.0,
                        networkType TEXT,
                        cellType TEXT DEFAULT 'UNKNOWN',
                        isSuspicious INTEGER DEFAULT 0,
                        detectionReasons TEXT DEFAULT '',
                        clusterId TEXT,
                        clusterSize INTEGER DEFAULT 1
                    )
                """)
                
                // Create indexes for efficient querying
                database.execSQL("CREATE INDEX IF NOT EXISTS index_lac_cid_patterns_mcc_mnc_lac ON lac_cid_patterns (mcc, mnc, lac)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_lac_cid_patterns_mcc_mnc_cid ON lac_cid_patterns (mcc, mnc, cid)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_lac_cid_patterns_mcc_mnc_lac_cid ON lac_cid_patterns (mcc, mnc, lac, cid)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_lac_cid_patterns_lat_lng ON lac_cid_patterns (latitude, longitude)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_lac_cid_patterns_pattern_type ON lac_cid_patterns (patternType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_lac_cid_patterns_suspicious ON lac_cid_patterns (isSuspicious)")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create SMS events table for silent SMS detection
                database.execSQL("""
                    CREATE TABLE sms_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        sender TEXT NOT NULL,
                        messageBody TEXT,
                        messageType TEXT NOT NULL DEFAULT 'UNKNOWN',
                        isSilent INTEGER NOT NULL DEFAULT 0,
                        cid INTEGER,
                        lac INTEGER,
                        mcc INTEGER,
                        mnc INTEGER,
                        signalStrength INTEGER,
                        threatLevel TEXT NOT NULL DEFAULT 'LOW',
                        threatConfidence REAL NOT NULL DEFAULT 0.0,
                        processed INTEGER NOT NULL DEFAULT 0,
                        analysisResult TEXT,
                        networkType TEXT,
                        timingAdvance INTEGER,
                        cellInfoJson TEXT,
                        latitude REAL,
                        longitude REAL
                    )
                """)

                // Create paging events table for paging storm detection
                database.execSQL("""
                    CREATE TABLE paging_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        cid INTEGER NOT NULL,
                        lac INTEGER NOT NULL,
                        mcc INTEGER NOT NULL,
                        mnc INTEGER NOT NULL,
                        eventType TEXT NOT NULL DEFAULT 'UNKNOWN',
                        signalStrength INTEGER NOT NULL,
                        timingAdvance INTEGER NOT NULL DEFAULT 0,
                        networkType TEXT NOT NULL,
                        isPartOfStorm INTEGER NOT NULL DEFAULT 0,
                        stormId INTEGER,
                        latitude REAL,
                        longitude REAL,
                        anomalyScore REAL NOT NULL DEFAULT 0.0,
                        confidence REAL NOT NULL DEFAULT 0.0,
                        analysisMetadata TEXT
                    )
                """)

                // Create paging storms table for tracking detected storms
                database.execSQL("""
                    CREATE TABLE paging_storms (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER,
                        eventCount INTEGER NOT NULL,
                        durationSeconds INTEGER NOT NULL DEFAULT 0,
                        affectedCid INTEGER NOT NULL,
                        affectedLac INTEGER NOT NULL,
                        affectedMcc INTEGER NOT NULL,
                        affectedMnc INTEGER NOT NULL,
                        stormLatitude REAL,
                        stormLongitude REAL,
                        stormRadius REAL NOT NULL DEFAULT 0.0,
                        threatLevel TEXT NOT NULL DEFAULT 'LOW',
                        confidence REAL NOT NULL DEFAULT 0.0,
                        anomalyScore REAL NOT NULL DEFAULT 0.0,
                        detectedPatterns TEXT NOT NULL,
                        patternDetails TEXT,
                        avgIntervalMs REAL NOT NULL DEFAULT 0.0,
                        minIntervalMs INTEGER NOT NULL DEFAULT 9223372036854775807,
                        maxIntervalMs INTEGER NOT NULL DEFAULT 0,
                        eventRatePerMinute REAL NOT NULL DEFAULT 0.0,
                        stormType TEXT NOT NULL DEFAULT 'UNKNOWN',
                        classificationReason TEXT NOT NULL DEFAULT '',
                        analysisTimestamp INTEGER NOT NULL DEFAULT 0,
                        resolved INTEGER NOT NULL DEFAULT 0,
                        resolutionTimestamp INTEGER,
                        resolutionReason TEXT,
                        relatedThreats TEXT,
                        forensicData TEXT
                    )
                """)

                // Create threat analysis results table
                database.execSQL("""
                    CREATE TABLE threat_analysis_results (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        isThreatDetected INTEGER NOT NULL,
                        threatType TEXT NOT NULL DEFAULT 'UNKNOWN',
                        confidence REAL NOT NULL DEFAULT 0.0,
                        threatLevel TEXT NOT NULL DEFAULT 'LOW',
                        severityScore REAL NOT NULL DEFAULT 0.0,
                        sourceEventType TEXT NOT NULL,
                        sourceEventId INTEGER NOT NULL,
                        relatedTowerId INTEGER,
                        timestamp INTEGER NOT NULL,
                        analyzedBy TEXT NOT NULL,
                        analysisDurationMs INTEGER NOT NULL DEFAULT 0,
                        indicators TEXT NOT NULL,
                        detectionReasons TEXT NOT NULL DEFAULT '',
                        recommendedAction TEXT NOT NULL DEFAULT 'MONITOR',
                        actionPriority TEXT NOT NULL DEFAULT 'LOW',
                        patternMatches TEXT,
                        anomalyDetails TEXT,
                        forensicData TEXT,
                        classificationModel TEXT,
                        modelConfidence REAL NOT NULL DEFAULT 0.0,
                        ruleMatches TEXT,
                        relatedThreatIds TEXT,
                        duplicateOf INTEGER,
                        analysisStatus TEXT NOT NULL DEFAULT 'COMPLETED',
                        requiresManualReview INTEGER NOT NULL DEFAULT 0,
                        reviewedBy TEXT,
                        reviewTimestamp INTEGER,
                        reviewNotes TEXT
                    )
                """)

                // Create indexes for SMS events table
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sms_events_timestamp ON sms_events (timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sms_events_sender ON sms_events (sender)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sms_events_cid_lac_mcc_mnc ON sms_events (cid, lac, mcc, mnc)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sms_events_message_type ON sms_events (messageType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sms_events_is_silent ON sms_events (isSilent)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sms_events_threat_level ON sms_events (threatLevel)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_sms_events_processed ON sms_events (processed)")

                // Create indexes for paging events table
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_events_timestamp ON paging_events (timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_events_cid_lac_mcc_mnc ON paging_events (cid, lac, mcc, mnc)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_events_event_type ON paging_events (eventType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_events_is_part_of_storm ON paging_events (isPartOfStorm)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_events_storm_id ON paging_events (stormId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_events_network_type ON paging_events (networkType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_events_timing_advance ON paging_events (timingAdvance)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_events_anomaly_score ON paging_events (anomalyScore)")

                // Create indexes for paging storms table
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_storms_start_time ON paging_storms (startTime)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_storms_end_time ON paging_storms (endTime)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_storms_affected_cid_lac_mcc_mnc ON paging_storms (affectedCid, affectedLac, affectedMcc, affectedMnc)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_storms_threat_level ON paging_storms (threatLevel)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_storms_confidence ON paging_storms (confidence)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_storms_detected_patterns ON paging_storms (detectedPatterns)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_paging_storms_resolved ON paging_storms (resolved)")

                // Create indexes for threat analysis results table
                database.execSQL("CREATE INDEX IF NOT EXISTS index_threat_analysis_timestamp ON threat_analysis_results (timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_threat_analysis_threat_type ON threat_analysis_results (threatType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_threat_analysis_is_threat_detected ON threat_analysis_results (isThreatDetected)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_threat_analysis_confidence ON threat_analysis_results (confidence)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_threat_analysis_source_event_type ON threat_analysis_results (sourceEventType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_threat_analysis_source_event_id ON threat_analysis_results (sourceEventId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_threat_analysis_related_tower_id ON threat_analysis_results (relatedTowerId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_threat_analysis_analyzed_by ON threat_analysis_results (analyzedBy)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_threat_analysis_analysis_status ON threat_analysis_results (analysisStatus)")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Add rsrq and sinr columns to FakeTower
                database.execSQL("ALTER TABLE FakeTower ADD COLUMN rsrq INTEGER")
                database.execSQL("ALTER TABLE FakeTower ADD COLUMN sinr INTEGER")
                
                // Log migration completion for debugging
                android.util.Log.d("AppDatabase", "Migration 10->11 completed: Added rsrq and sinr columns to FakeTower")
            }
        }
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create logcat_events table
                database.execSQL("""
                    CREATE TABLE logcat_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        message TEXT NOT NULL,
                        cid INTEGER,
                        timestamp INTEGER NOT NULL,
                        confidence REAL NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_logcat_events_timestamp ON logcat_events (timestamp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_logcat_events_cid ON logcat_events (cid)")
                
android.util.Log.d("AppDatabase", "Migration 11->12 completed: Added logcat_events table")
    }
}
}
}
