package com.cymatune.ui

import com.cymatune.db.FakeTower

data class DashboardStatistics(
    val totalScanned: Int,
    val threatsDetected: Int,
    val safeTowers: Int,
    val newTowers: Int
)

object DashboardStats {
    fun calculateStats(towers: List<FakeTower>): DashboardStatistics {
        val total = towers.size

        val threats = towers.count { it.trustScore < 50 }

        val fiveMinutesAgo = System.currentTimeMillis() - 300000
        val newTowers = towers.count { it.detectionTime > fiveMinutesAgo }

        val safe = total - threats

        return DashboardStatistics(
            totalScanned = total,
            threatsDetected = threats,
            safeTowers = safe,
            newTowers = newTowers
        )
    }

    fun getActiveThreats(towers: List<FakeTower>, timeWindowMs: Long = 300000): List<FakeTower> {
        val cutoffTime = System.currentTimeMillis() - timeWindowMs

        return towers.filter {
            (it.trustScore < 50 || it.hasHistoricThreat) && it.detectionTime > cutoffTime
        }
    }
}