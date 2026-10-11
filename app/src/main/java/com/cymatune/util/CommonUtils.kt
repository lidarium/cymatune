package com.cymatune.util

import kotlin.math.*

/**
 * Common utility functions to eliminate code duplication across the codebase
 *
 * Consolidated utilities for geographic calculations, statistical analysis, and common operations
 * used throughout the fake tower detection system.
 */
object CommonUtils {
    
    // Earth radius constant in meters
    private const val EARTH_RADIUS_METERS = 6371000.0
    
    /**
     * Calculates the great-circle distance between two points on the Earth's surface using the Haversine formula.
     *
     * @param lat1 Latitude of the first point in degrees
     * @param lon1 Longitude of the first point in degrees
     * @param lat2 Latitude of the second point in degrees
     * @param lon2 Longitude of the second point in degrees
     * @return The distance between the two points in meters
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
    
    /**
     * Calculates the distance between two geographical points represented as pairs
     *
     * @param point1 First point as Pair(latitude, longitude)
     * @param point2 Second point as Pair(latitude, longitude)
     * @return The distance between the two points in meters
     */
    fun calculateDistance(point1: Pair<Double, Double>, point2: Pair<Double, Double>): Double {
        return calculateDistance(point1.first, point1.second, point2.first, point2.second)
    }
    
    /**
     * Determines if user appears stationary based on recent locations
     *
     * @param recentLocations List of Triple(latitude, longitude, timestamp)
     * @return true if user appears stationary (movement less than 20 meters)
     */
    fun isUserStationary(recentLocations: List<Triple<Double, Double, Long>>): Boolean {
        if (recentLocations.size < 3) return false
        
        val firstLocation = recentLocations.first()
        var maxDistance = 0.0
        
        for (location in recentLocations) {
            val distance = calculateDistance(firstLocation.first, firstLocation.second, location.first, location.second)
            if (distance > maxDistance) {
                maxDistance = distance
            }
        }
        
        // Consider user stationary if movement is less than 20 meters
        return maxDistance < 20.0
    }

    /**
     * Determines if user appears stationary based on TowerInfo locations
     *
     * @param towerInfo TowerInfo object containing location data
     * @return true if user appears stationary (movement less than 20 meters)
     */
    fun isUserStationary(towerInfo: TowerInfo): Boolean {
        return isUserStationary(towerInfo.locations)
    }
    
    /**
     * Calculates standard deviation of a list of values
     *
     * @param values List of Double values
     * @return Standard deviation of the values
     */
    fun calculateStandardDeviation(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean).pow(2) }.average()
        return sqrt(variance)
    }
    
    /**
     * Calculates the bearing in degrees between two geographical points.
     *
     * @param point1 First point as Pair(latitude, longitude)
     * @param point2 Second point as Pair(latitude, longitude)
     * @return Bearing in degrees from point1 to point2
     */
    fun calculateBearing(point1: Pair<Double, Double>, point2: Pair<Double, Double>): Double {
        val lat1 = Math.toRadians(point1.first)
        val lon1 = Math.toRadians(point1.second)
        val lat2 = Math.toRadians(point2.first)
        val lon2 = Math.toRadians(point2.second)
        
        val deltaLon = lon2 - lon1
        
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        var bearing = Math.toDegrees(atan2(y, x))
        
        if (bearing < 0) {
            bearing += 360.0
        }
        return bearing
    }
    
    /**
     * Calculates the midpoint between two geographical points
     *
     * @param point1 First point as Pair(latitude, longitude)
     * @param point2 Second point as Pair(latitude, longitude)
     * @return Midpoint coordinates as Pair(latitude, longitude)
     */
    fun calculateMidpoint(point1: Pair<Double, Double>, point2: Pair<Double, Double>): Pair<Double, Double> {
        val lat1 = Math.toRadians(point1.first)
        val lon1 = Math.toRadians(point1.second)
        val lat2 = Math.toRadians(point2.first)
        val lon2 = Math.toRadians(point2.second)
        
        val deltaLon = lon2 - lon1
        
        val bx = cos(lat2) * cos(deltaLon)
        val by = cos(lat2) * sin(deltaLon)
        
        val midLat = atan2(
            sin(lat1) + sin(lat2),
            sqrt((cos(lat1) + bx).pow(2) + by.pow(2))
        )
        val midLon = lon1 + atan2(by, cos(lat1) + bx)
        
        return Pair(Math.toDegrees(midLat), Math.toDegrees(midLon))
    }
    
    /**
     * Normalizes a value to a 0-1 range based on min/max bounds
     *
     * @param value The value to normalize
     * @param min The minimum expected value
     * @param max The maximum expected value
     * @return Normalized value in range [0, 1]
     */
    fun normalizeValue(value: Double, min: Double, max: Double): Double {
        return ((value - min) / (max - min)).coerceIn(0.0, 1.0)
    }
    
    /**
     * Calculates weighted average of values with corresponding weights
     *
     * @param values List of values
     * @param weights List of weights (must be same size as values)
     * @return Weighted average
     */
    fun calculateWeightedAverage(values: List<Double>, weights: List<Double>): Double {
        require(values.size == weights.size) { "Values and weights must have same size" }
        require(values.isNotEmpty()) { "Cannot calculate average of empty list" }
        
        val totalWeight = weights.sum()
        if (totalWeight == 0.0) return values.average()
        
        var weightedSum = 0.0
        for (i in values.indices) {
            weightedSum += values[i] * weights[i]
        }
        
        return weightedSum / totalWeight
    }
}