package com.cymatune.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add new columns to tower_observations table
        database.execSQL("ALTER TABLE tower_observations ADD COLUMN rsrp INTEGER NOT NULL DEFAULT -2147483648")
        database.execSQL("ALTER TABLE tower_observations ADD COLUMN rsrq INTEGER")
        database.execSQL("ALTER TABLE tower_observations ADD COLUMN sinr INTEGER")
        database.execSQL("ALTER TABLE tower_observations ADD COLUMN pci INTEGER")
        database.execSQL("ALTER TABLE tower_observations ADD COLUMN locationAccuracy REAL")
        database.execSQL("ALTER TABLE tower_observations ADD COLUMN source TEXT NOT NULL DEFAULT 'normal'")

        // Add new columns to TowerInfo table
        database.execSQL("ALTER TABLE TowerInfo ADD COLUMN estimatedTowerLocation TEXT")
        database.execSQL("ALTER TABLE TowerInfo ADD COLUMN suspicionReason TEXT")
        database.execSQL("ALTER TABLE TowerInfo ADD COLUMN suspicionConfidence REAL")
    }
}
