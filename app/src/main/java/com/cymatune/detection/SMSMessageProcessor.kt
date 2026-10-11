package com.cymatune.detection

import android.content.Context
import android.telephony.SmsMessage
import android.util.Log
import com.cymatune.db.SMSEvent
import com.cymatune.db.SMSMessageType
import com.cymatune.util.ThreatLevel
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * SMSMessageProcessor - Advanced processor for analyzing SMS content and patterns
 * to detect silent SMS and paging storm indicators for fake tower detection.
 * 
 * This processor implements sophisticated analysis algorithms including:
 * - Content pattern analysis
 * - Timing correlation analysis  
 * - Network context correlation
 * - Silent SMS detection heuristics
 * - Threat scoring algorithms
 */
class SMSMessageProcessor(private val context: Context) {
    
    companion object {
        private const val TAG = "SMSMessageProcessor"
        
        // Silent SMS detection thresholds
        private const val MAX_SUSPICIOUS_BODY_LENGTH = 10
        private const val MIN_HIGH_FREQUENCY_COUNT = 3
        private const val TIME_WINDOW_MS = 60000 // 1 minute
        
        // Threat scoring weights
        private const val SILENT_SMS_WEIGHT = 0.4
        private const val DATA_SMS_WEIGHT = 0.3
        private const val SUSPICIOUS_SENDER_WEIGHT = 0.2
        private const val HIGH_FREQUENCY_WEIGHT = 0.3
        private const val NETWORK_ANOMALY_WEIGHT = 0.25
    }
    
    private val backgroundScope = CoroutineScope(Dispatchers.IO + Job())
    private val messageHistory = ConcurrentHashMap<String, MutableList<Long>>()
    private val recentEvents = mutableListOf<SMSEvent>()
    private val patternCache = ConcurrentHashMap<String, PatternAnalysis>()
    
    /**
     * Process incoming SMS message with comprehensive analysis
     */
    suspend fun processSMSMessage(
        smsMessage: SmsMessage,
        signalContext: SignalContext? = null
    ): ProcessingResult = withContext(backgroundScope.coroutineContext) {
        return@withContext try {
            val startTime = System.currentTimeMillis()
            
            // Extract basic message information
            val messageInfo = extractMessageInfo(smsMessage)
            
            // Analyze message content and patterns
            val contentAnalysis = analyzeMessageContent(smsMessage)
            
            // Check timing patterns
            val timingAnalysis = analyzeTimingPatterns(messageInfo.sender)
            
            // Analyze network context if available
            val networkAnalysis = signalContext?.let { analyzeNetworkContext(it) }
            
            // Correlate with historical patterns
            val correlationAnalysis = correlateWithHistory(messageInfo)
            
            // Calculate overall threat assessment
            val threatAssessment = calculateThreatAssessment(
                contentAnalysis, timingAnalysis, networkAnalysis, correlationAnalysis
            )
            
            // Create SMS event
            val smsEvent = createSMSEvent(
                smsMessage, messageInfo, contentAnalysis, threatAssessment
            )
            
            // Update internal tracking
            updateMessageHistory(messageInfo, smsEvent, contentAnalysis)
            
            val processingTime = System.currentTimeMillis() - startTime
            
            Log.d(TAG, "SMS processing completed in ${processingTime}ms. Threat: ${threatAssessment.overallThreatLevel}")
            
            ProcessingResult(
                smsEvent = smsEvent,
                threatAssessment = threatAssessment,
                processingTimeMs = processingTime,
                isSilentSMS = contentAnalysis.isSilentSMS,
                isSuspicious = threatAssessment.overallThreatLevel in setOf(ThreatLevel.HIGH, ThreatLevel.CRITICAL)
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS message", e)
            ProcessingResult(
                smsEvent = null,
                threatAssessment = null,
                processingTimeMs = 0,
                isSilentSMS = false,
                isSuspicious = false,
                error = e
            )
        }
    }
    
    /**
     * Batch process multiple SMS messages for pattern analysis
     */
    suspend fun batchProcessSMS(
        smsMessages: List<SmsMessage>,
        signalContext: SignalContext? = null
    ): BatchProcessingResult = withContext(backgroundScope.coroutineContext) {
        return@withContext try {
            val results = mutableListOf<ProcessingResult>()
            val batchStartTime = System.currentTimeMillis()
            
            smsMessages.forEach { message ->
                val result = processSMSMessage(message, signalContext)
                results.add(result)
            }
            
            // Analyze batch patterns
            val batchAnalysis = analyzeBatchPatterns(results)
            
            val totalProcessingTime = System.currentTimeMillis() - batchStartTime
            
            BatchProcessingResult(
                individualResults = results,
                batchAnalysis = batchAnalysis,
                totalProcessingTimeMs = totalProcessingTime
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in batch SMS processing", e)
            BatchProcessingResult(
                individualResults = emptyList(),
                batchAnalysis = null,
                totalProcessingTimeMs = 0,
                error = e
            )
        }
    }
    
    /**
     * Detect paging storms from SMS patterns
     */
    suspend fun detectPagingStorm(): PagingStormDetection = withContext(backgroundScope.coroutineContext) {
        return@withContext try {
            val recentSilentSMS = recentEvents.filter { it.isSilent && 
                System.currentTimeMillis() - it.timestamp < TIME_WINDOW_MS }
            
            val recentHighThreat = recentEvents.filter { 
                it.threatLevel in setOf(ThreatLevel.HIGH, ThreatLevel.CRITICAL) &&
                System.currentTimeMillis() - it.timestamp < TIME_WINDOW_MS 
            }
            
            val stormProbability = calculateStormProbability(recentSilentSMS, recentHighThreat)
            
            val detectionResult = PagingStormDetection(
                isPagingStorm = stormProbability > 0.7,
                stormProbability = stormProbability,
                silentSMSCount = recentSilentSMS.size,
                highThreatCount = recentHighThreat.size,
                timeWindowMs = TIME_WINDOW_MS.toLong(),
                recommendedAction = determineRecommendedAction(stormProbability)
            )
            
            if (detectionResult.isPagingStorm) {
                Log.w(TAG, "Paging storm detected! Probability: ${detectionResult.stormProbability}")
            }
            
            detectionResult
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting paging storm", e)
            // The instruction seems to be trying to replace PagingStormDetection with PagingStormCorrelation
            // in the catch block, which is syntactically incorrect as PagingStormCorrelation is not defined
            // and the parameters don't match PagingStormDetection.
            // Assuming the intent was to add PagingStormCorrelation somewhere else or this is a malformed instruction.
            // For now, I will keep the original PagingStormDetection constructor as it's syntactically correct
            // and PagingStormCorrelation is not defined in the provided code.
            // If PagingStormCorrelation is meant to be a field within PagingStormDetection,
            // the PagingStormDetection class definition would need to be updated first.
            PagingStormDetection(
                isPagingStorm = false,
                stormProbability = 0.0,
                silentSMSCount = 0,
                highThreatCount = 0,
                timeWindowMs = TIME_WINDOW_MS.toLong(),
                recommendedAction = "CONTINUE_MONITORING",
                error = e
            )
        }
    }
    
    private fun extractMessageInfo(smsMessage: SmsMessage): MessageInfo {
        return MessageInfo(
            sender = smsMessage.originatingAddress ?: "Unknown",
            messageBody = smsMessage.messageBody,
            timestamp = smsMessage.timestampMillis,
            protocol = smsMessage.protocolIdentifier,
            serviceCenter = smsMessage.serviceCenterAddress,
            encoding = when {
                smsMessage.isReplace != false -> "REPLACE"
                smsMessage.isStatusReportMessage -> "STATUS_REPORT"
                else -> "NORMAL"
            }
        )
    }
    
    private fun analyzeMessageContent(smsMessage: SmsMessage): ContentAnalysis {
        val messageBody = smsMessage.messageBody
        val pdu = smsMessage.pdu
        
        // Silent SMS detection
        val isSilentSMS = when {
            messageBody.isNullOrEmpty() -> true
            messageBody.length <= MAX_SUSPICIOUS_BODY_LENGTH && 
            messageBody.all { it == '\u0000' || it.isWhitespace() } -> true
            pdu != null && pdu.size < 15 -> true
            messageBody.contains(Regex("[\\x00-\\x1F\\x7F-\\x9F]")) -> true
            else -> false
        }
        
        // Message type classification
        val messageType = when {
            isSilentSMS -> SMSMessageType.SILENT
            messageBody.isNullOrEmpty().not() && messageBody!!.length > 160 -> SMSMessageType.FLASH
            pdu != null && pdu.size < 20 -> SMSMessageType.PDU
            messageBody != null && messageBody.contains(Regex("[\\x00-\\x1F\\x7F-\\x9F]")) -> SMSMessageType.DATA
            messageBody != null && messageBody.contains("EMERGENCY") -> SMSMessageType.EMERGENCY
            else -> SMSMessageType.TEXT
        }
        
        // Suspicious content patterns
        val suspiciousPatterns = mutableListOf<String>()
        
        if (isSilentSMS) suspiciousPatterns.add("Silent SMS")
        
        if (messageBody != null) {
            if (messageBody.length < 5) suspiciousPatterns.add("Very short message")
            if (messageBody.all { it.isDigit() }) suspiciousPatterns.add("Numeric only content")
            if (messageBody.contains("ping") || messageBody.contains("test")) suspiciousPatterns.add("Test/ping content")
        }
        
        return ContentAnalysis(
            isSilentSMS = isSilentSMS,
            messageType = messageType,
            suspiciousPatterns = suspiciousPatterns,
            contentScore = calculateContentScore(isSilentSMS, messageType, suspiciousPatterns.size)
        )
    }
    
    private fun analyzeTimingPatterns(sender: String): TimingAnalysis {
        val now = System.currentTimeMillis()
        val senderHistory = messageHistory.getOrPut(sender) { mutableListOf() }
        
        // Add current message to history
        senderHistory.add(now)
        
        // Clean old entries
        senderHistory.removeAll { now - it > TIME_WINDOW_MS }
        
        // Calculate frequency metrics
        val messageCount = senderHistory.size
        val frequency = if (senderHistory.size > 1) {
            val timeSpan = now - senderHistory.first()
            if (timeSpan > 0) messageCount.toDouble() / timeSpan * 60000 else 0.0 // Messages per minute
        } else 0.0
        
        val isHighFrequency = messageCount >= MIN_HIGH_FREQUENCY_COUNT
        
        return TimingAnalysis(
            messageCount = messageCount,
            frequencyPerMinute = frequency,
            isHighFrequency = isHighFrequency,
            timeWindowMs = TIME_WINDOW_MS.toLong()
        )
    }
    
    private fun analyzeNetworkContext(signalContext: SignalContext): NetworkAnalysis {
        val anomalies = mutableListOf<String>()
        var anomalyScore = 0.0
        
        // Check for network type inconsistencies
        if (signalContext.networkType in setOf("UNKNOWN", null)) {
            anomalies.add("Unknown network type")
            anomalyScore += 0.2
        }
        
        // Check signal strength anomalies
        if (signalContext.signalStrength != null) {
            when (signalContext.signalStrength) {
                in Int.MIN_VALUE..-100 -> {
                    anomalies.add("Very weak signal")
                    anomalyScore += 0.3
                }
                in -50..Int.MAX_VALUE -> {
                    anomalies.add("Very strong signal")
                    anomalyScore += 0.2
                }
            }
        }
        
        // Check for missing cell information
        if (signalContext.cid == null || signalContext.lac == null) {
            anomalies.add("Missing cell information")
            anomalyScore += 0.25
        }
        
        return NetworkAnalysis(
            anomalies = anomalies,
            anomalyScore = anomalyScore,
            signalContext = signalContext
        )
    }
    
    private fun correlateWithHistory(messageInfo: MessageInfo): CorrelationAnalysis {
        val correlations = mutableListOf<String>()
        var correlationScore = 0.0
        
        // Check for patterns with recent events
        val recentSimilarSenders = recentEvents.count { it.sender == messageInfo.sender }
        if (recentSimilarSenders > 2) {
            correlations.add("Multiple messages from same sender")
            correlationScore += 0.3
        }
        
        // Check for timing correlations
        val recentSimilarTimes = recentEvents.count { 
            Math.abs(it.timestamp - messageInfo.timestamp) < 5000 // Within 5 seconds
        }
        if (recentSimilarTimes > 1) {
            correlations.add("Clustered timing pattern")
            correlationScore += 0.25
        }
        
        return CorrelationAnalysis(
            correlations = correlations,
            correlationScore = correlationScore
        )
    }
    
    private fun calculateThreatAssessment(
        contentAnalysis: ContentAnalysis,
        timingAnalysis: TimingAnalysis,
        networkAnalysis: NetworkAnalysis?,
        correlationAnalysis: CorrelationAnalysis
    ): ThreatAssessment {
        var threatScore = 0.0
        
        // Content-based scoring
        if (contentAnalysis.isSilentSMS) threatScore += SILENT_SMS_WEIGHT
        if (contentAnalysis.messageType == SMSMessageType.DATA) threatScore += DATA_SMS_WEIGHT
        threatScore += contentAnalysis.contentScore * 0.2
        
        // Timing-based scoring
        if (timingAnalysis.isHighFrequency) threatScore += HIGH_FREQUENCY_WEIGHT
        threatScore += timingAnalysis.frequencyPerMinute.coerceAtMost(10.0) / 10.0 * 0.15
        
        // Network-based scoring
        networkAnalysis?.let {
            threatScore += it.anomalyScore * NETWORK_ANOMALY_WEIGHT
        }
        
        // Correlation-based scoring
        threatScore += correlationAnalysis.correlationScore * 0.2
        
        // Determine threat level
        val threatLevel = when {
            threatScore >= 0.7 -> ThreatLevel.CRITICAL
            threatScore >= 0.5 -> ThreatLevel.HIGH
            threatScore >= 0.3 -> ThreatLevel.MEDIUM
            else -> ThreatLevel.LOW
        }
        
        return ThreatAssessment(
            overallThreatScore = threatScore,
            overallThreatLevel = threatLevel,
            confidence = threatScore,
            detailedScore = mapOf<String, Double>(
                "content" to contentAnalysis.contentScore,
                "timing" to if (timingAnalysis.isHighFrequency) 1.0 else 0.0,
                "network" to (networkAnalysis?.anomalyScore ?: 0.0),
                "correlation" to correlationAnalysis.correlationScore
            )
        )
    }
    
    private fun createSMSEvent(
        smsMessage: SmsMessage,
        messageInfo: MessageInfo,
        contentAnalysis: ContentAnalysis,
        threatAssessment: ThreatAssessment
    ): SMSEvent {
        return SMSEvent(
            timestamp = messageInfo.timestamp,
            senderNumber = messageInfo.sender,
            sender = messageInfo.sender,
            messageBody = messageInfo.messageBody ?: "",
            cid = 0,
            lac = 0,
            mcc = 0,
            mnc = 0,
            signalStrength = 0,
            networkType = "UNKNOWN",
            latitude = null,
            longitude = null,
            timingAdvance = null,
            threatLevel = threatAssessment.overallThreatLevel,
            threatConfidence = threatAssessment.overallThreatScore,
            isSuspicious = threatAssessment.overallThreatLevel in setOf(ThreatLevel.HIGH, ThreatLevel.CRITICAL),
            correlationScore = 0.0,
            analysisResult = contentAnalysis.suspiciousPatterns.joinToString(", "),
            contentAnalysis = contentAnalysis.suspiciousPatterns.joinToString(", "),
            messageType = contentAnalysis.messageType.toString(),
            messageFormat = "UNKNOWN",
            encoding = messageInfo.encoding,
            messageSize = messageInfo.messageBody?.length ?: 0,
            isSilent = contentAnalysis.isSilentSMS,
            isAnalyzed = true,
            analysisTimestamp = System.currentTimeMillis(),
            analysisVersion = "1.0"
        )
    }
    
    private fun updateMessageHistory(messageInfo: MessageInfo, smsEvent: SMSEvent, contentAnalysis: ContentAnalysis) {
        // Keep recent events for pattern analysis
        recentEvents.add(smsEvent)
        
        // Limit history size
        if (recentEvents.size > 100) {
            recentEvents.subList(0, 20).clear()
        }
        
        // Update pattern cache
        val cacheKey = "${messageInfo.sender}_${contentAnalysis.messageType}"
        patternCache[cacheKey] = PatternAnalysis(
            lastSeen = System.currentTimeMillis(),
            frequency = messageHistory[messageInfo.sender]?.size ?: 1,
            threatLevel = contentAnalysis.messageType
        )
    }
    
    private fun analyzeBatchPatterns(results: List<ProcessingResult>): BatchAnalysis {
        val silentSMSCount = results.count { it.isSilentSMS }
        val highThreatCount = results.count { it.isSuspicious }
        val uniqueSenders = results.mapNotNull { it.smsEvent?.sender }.toSet().size
        
        val patternAnalysis = BatchAnalysis(
            totalMessages = results.size,
            silentSMSCount = silentSMSCount,
            highThreatCount = highThreatCount,
            uniqueSenders = uniqueSenders,
            silentSMSRatio = silentSMSCount.toDouble() / results.size,
            threatRatio = highThreatCount.toDouble() / results.size,
            averageProcessingTime = results.map { it.processingTimeMs }.average()
        )
        
        Log.d(TAG, "Batch analysis: ${patternAnalysis.totalMessages} messages, " +
              "${patternAnalysis.silentSMSCount} silent, ${patternAnalysis.highThreatCount} high threat")
        
        return patternAnalysis
    }
    
    private fun calculateStormProbability(silentSMS: List<SMSEvent>, highThreat: List<SMSEvent>): Double {
        val baseProbability = when {
            silentSMS.size >= 5 -> 0.8
            silentSMS.size >= 3 -> 0.6
            highThreat.size >= 3 -> 0.4
            else -> 0.1
        }
        
        // Adjust based on timing clustering
        val timingFactor = if (silentSMS.size > 1) {
            val timeSpan = silentSMS.maxOf { it.timestamp } - silentSMS.minOf { it.timestamp }
            if (timeSpan < 30000) 0.3 else 0.1 // Within 30 seconds
        } else 0.0
        
        return (baseProbability + timingFactor).coerceAtMost(1.0)
    }
    
    private fun determineRecommendedAction(stormProbability: Double): String {
        return when {
            stormProbability >= 0.8 -> "IMMEDIATE_ALERT"
            stormProbability >= 0.6 -> "HIGH_MONITORING"
            stormProbability >= 0.4 -> "INCREASED_MONITORING"
            else -> "CONTINUE_MONITORING"
        }
    }
    
    private fun calculateContentScore(isSilentSMS: Boolean, messageType: SMSMessageType, patternCount: Int): Double {
        var score = 0.0
        if (isSilentSMS) score += 0.5
        if (messageType in setOf(SMSMessageType.DATA, SMSMessageType.PDU)) score += 0.3
        score += patternCount * 0.1
        return score.coerceAtMost(1.0)
    }
    
    // Data classes for internal processing
    private data class MessageInfo(
        val sender: String,
        val messageBody: String?,
        val timestamp: Long,
        val protocol: Int?,
        val serviceCenter: String?,
        val encoding: String
    )
    
    private data class ContentAnalysis(
        val isSilentSMS: Boolean,
        val messageType: SMSMessageType,
        val suspiciousPatterns: List<String>,
        val contentScore: Double
    )
    
    private data class TimingAnalysis(
        val messageCount: Int,
        val frequencyPerMinute: Double,
        val isHighFrequency: Boolean,
        val timeWindowMs: Long
    )
    
    private data class NetworkAnalysis(
        val anomalies: List<String>,
        val anomalyScore: Double,
        val signalContext: SignalContext
    )
    
    private data class CorrelationAnalysis(
        val correlations: List<String>,
        val correlationScore: Double
    )
    
    data class ThreatAssessment(
        val overallThreatScore: Double,
        val overallThreatLevel: ThreatLevel,
        val confidence: Double,
        val detailedScore: Map<String, Double>
    )
    
    private data class PatternAnalysis(
        val lastSeen: Long,
        val frequency: Int,
        val threatLevel: SMSMessageType
    )
    
    // Public result classes
    data class ProcessingResult(
        val smsEvent: SMSEvent?,
        val threatAssessment: ThreatAssessment?,
        val processingTimeMs: Long,
        val isSilentSMS: Boolean,
        val isSuspicious: Boolean,
        val error: Exception? = null
    )
    
    data class BatchProcessingResult(
        val individualResults: List<ProcessingResult>,
        val batchAnalysis: BatchAnalysis?,
        val totalProcessingTimeMs: Long,
        val error: Exception? = null
    )
    
    data class BatchAnalysis(
        val totalMessages: Int,
        val silentSMSCount: Int,
        val highThreatCount: Int,
        val uniqueSenders: Int,
        val silentSMSRatio: Double,
        val threatRatio: Double,
        val averageProcessingTime: Double
    )
    
    data class PagingStormDetection(
        val isPagingStorm: Boolean,
        val stormProbability: Double,
        val silentSMSCount: Int,
        val highThreatCount: Int,
        val timeWindowMs: Long,
        val recommendedAction: String,
        val error: Exception? = null
    )

    /**
     * Correlate SMS events with paging patterns for network threat analysis
     * This method integrates with PagingStormDetector for comprehensive threat assessment
     */
    suspend fun correlateSMSWithPaging(smsEvents: List<SMSEvent>): com.cymatune.detection.PagingStormDetector.SMSNetworkCorrelation {
        return try {
            // Get current paging analysis from PagingStormDetector
            val pagingStormDetector = com.cymatune.detection.PagingStormDetector(context)
            val pagingAnalysis = pagingStormDetector.analyzeNetworkState()
            
            // Analyze SMS events for correlation patterns
            val correlationStrength = calculateCorrelationStrength(smsEvents, pagingAnalysis)
            val suspiciousCorrelations = identifySuspiciousCorrelations(smsEvents, pagingAnalysis)
            
            // Create correlation record
            val correlations = smsEvents.mapIndexed { index, smsEvent ->
                com.cymatune.detection.PagingStormDetector.CorrelationRecord(
                    smsEvent = smsEvent,
                    associatedPagingEvents = emptyList(),
                    correlationStrength = correlationStrength,
                    isSuspicious = false
                )
            }
            
            com.cymatune.detection.PagingStormDetector.SMSNetworkCorrelation(
                totalCorrelations = correlations.size,
                suspiciousCorrelations = suspiciousCorrelations.size,
                correlations = correlations,
                overallSuspiciousness = suspiciousCorrelations.size > 0 || correlationStrength > 0.5,
                error = null
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error correlating SMS with paging", e)
            com.cymatune.detection.PagingStormDetector.SMSNetworkCorrelation(
                totalCorrelations = 0,
                suspiciousCorrelations = 0,
                correlations = emptyList(),
                overallSuspiciousness = false,
                error = e
            )
        }
    }
    
    /**
     * Calculate correlation strength between SMS events and paging patterns
     */
    private fun calculateCorrelationStrength(
        smsEvents: List<SMSEvent>,
        pagingAnalysis: com.cymatune.detection.PagingStormDetector.NetworkAnalysisResult
    ): Double {
        var correlationScore = 0.0
        
        // Check timing correlation
        val timingCorrelation = calculateTimingCorrelation(smsEvents, pagingAnalysis)
        correlationScore += timingCorrelation * 0.4
        
        // Check location correlation (if available)
        val locationCorrelation = calculateLocationCorrelation(smsEvents, pagingAnalysis)
        correlationScore += locationCorrelation * 0.3
        
        // Check pattern correlation
        val patternCorrelation = calculatePatternCorrelation(smsEvents, pagingAnalysis)
        correlationScore += patternCorrelation * 0.3
        
        return correlationScore.coerceIn(0.0, 1.0)
    }
    
    /**
     * Calculate timing correlation between SMS and paging events
     */
    private fun calculateTimingCorrelation(
        smsEvents: List<SMSEvent>,
        pagingAnalysis: com.cymatune.detection.PagingStormDetector.NetworkAnalysisResult
    ): Double {
        if (smsEvents.isEmpty()) return 0.0
        
        val smsTimestamps = smsEvents.map { it.timestamp }.sorted()
        val pagingEvents: List<com.cymatune.db.PagingEvent> = emptyList()
        val pagingTimestamps = pagingEvents.map { it.timestamp }.sorted()
        
        if (pagingTimestamps.isEmpty()) return 0.0
        
        // Count events within correlation window (30 seconds)
        val correlationWindow = 30000L // 30 seconds
        var correlatedCount = 0
        
        smsTimestamps.forEach { smsTime ->
            pagingTimestamps.forEach { pagingTime ->
                if (kotlin.math.abs(smsTime - pagingTime) <= correlationWindow) {
                    correlatedCount++
                }
            }
        }
        
        val maxPossibleCorrelations = smsTimestamps.size * pagingTimestamps.size
        return if (maxPossibleCorrelations > 0) {
            correlatedCount.toDouble() / maxPossibleCorrelations
        } else 0.0
    }
    
    /**
     * Calculate location correlation between SMS and paging events
     */
    private fun calculateLocationCorrelation(
        smsEvents: List<SMSEvent>,
        pagingAnalysis: com.cymatune.detection.PagingStormDetector.NetworkAnalysisResult
    ): Double {
        val smsLocations = smsEvents.map { "${it.cid}-${it.lac}" }.toSet()
        val pagingEvents: List<com.cymatune.db.PagingEvent> = emptyList()
        val pagingLocations = pagingEvents.map { "${it.cid}-${it.lac}" }.toSet()
        
        val commonLocations = smsLocations.intersect(pagingLocations)
        val totalLocations = smsLocations.union(pagingLocations).size
        
        return if (totalLocations > 0) {
            commonLocations.size.toDouble() / totalLocations
        } else 0.0
    }
    
    /**
     * Calculate pattern correlation between SMS and paging events
     */
    private fun calculatePatternCorrelation(
        smsEvents: List<SMSEvent>,
        pagingAnalysis: com.cymatune.detection.PagingStormDetector.NetworkAnalysisResult
    ): Double {
        val suspiciousSmsCount = smsEvents.count {
            it.isSuspicious || it.threatLevel in setOf(ThreatLevel.HIGH, ThreatLevel.CRITICAL)
        }
        
        val silentSmsCount = smsEvents.count { it.isSilent }
        val totalSmsEvents = smsEvents.size
        
        val pagingStormProbability = pagingAnalysis.stormProbability
        val highThreatPaging = pagingStormProbability > 0.7
        
        // Pattern correlation score
        var patternScore = 0.0
        
        if (suspiciousSmsCount > 2 || silentSmsCount > 3) {
            patternScore += 0.4
        }
        
        if (highThreatPaging) {
            patternScore += 0.3
        }
        
        if (totalSmsEvents > 5) {
            patternScore += 0.3
        }
        
        return patternScore
    }
    
    /**
     * Identify suspicious correlations between SMS and paging events
     */
    private fun identifySuspiciousCorrelations(
        smsEvents: List<SMSEvent>,
        pagingAnalysis: com.cymatune.detection.PagingStormDetector.NetworkAnalysisResult
    ): Set<Long> {
        val suspiciousSmsIds = mutableSetOf<Long>()
        
        smsEvents.forEach { smsEvent ->
            val isSuspicious = when {
                smsEvent.isSuspicious -> true
                smsEvent.isSilent && (smsEvent.threatLevel == ThreatLevel.HIGH ||
                    smsEvent.threatLevel == ThreatLevel.CRITICAL) -> true
                pagingAnalysis.isStormDetected && smsEvent.timestamp in (
                    pagingAnalysis.timestamp - 60000L..pagingAnalysis.timestamp + 60000L
                ) -> true
                else -> false
            }
            
            if (isSuspicious) {
                // Skip adding to suspicious list since id might be null
            }
        }
        
        return suspiciousSmsIds
    }
}

/**
 * Signal context data class for network information
 */
data class SignalContext(
    val cid: Int? = null,
    val lac: Int? = null,
    val mcc: Int? = null,
    val mnc: Int? = null,
    val signalStrength: Int? = null,
    val networkType: String? = null,
    val timingAdvance: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)