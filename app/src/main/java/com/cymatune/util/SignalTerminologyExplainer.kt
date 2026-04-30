package com.cymatune.util

/**
 * Signal terminology explanations for educational purposes
 * Inspired by concepts from CellInfo repository (CC0 1.0 licensed)
 * 
 * Provides comprehensive explanations of cellular signal metrics
 * to help users understand the technical details they're seeing.
 */
object SignalTerminologyExplainer {

    /**
     * Get detailed explanation for a signal metric
     */
    fun getExplanation(term: String): String {
        return when (term.lowercase()) {
            "pci", "physical cell id" -> "Physical Cell Identity is a unique identifier (0-503) for a cell in an LTE network. It helps distinguish between different cells from the same tower sector."
            
            "enb id", "enodeb id" -> "The eNodeB Identifier identifies the hardware that communicates between mobile devices and the network. It's derived from the Cell ID (CID ÷ 256 = eNB ID)."
            
            "cell sector id" -> "Cell Sector ID identifies which sector of a cell site the connection falls under (0-255). Multi-sector sites provide better coverage and capacity."
            
            "cid", "cell id" -> "Cell ID is the unique identifier for a specific cell. For LTE, this is a 28-bit number used to calculate both eNB ID and Cell Sector ID."
            
            "band number" -> "Bands are specific frequency ranges allocated for cellular communications. Different bands have different propagation characteristics and are used in various regions."
            
            "tac", "tracking area code" -> "Tracking Area Code groups cells together for mobility management. Your device updates its location when moving between different TAC areas."
            
            "earfcn", "e-utra absolute radio frequency channel number" -> "EARFCN specifies the exact frequency, bandwidth, and duplex mode of the connected LTE channel."
            
            "bandwidth" -> "Bandwidth determines how much spectrum is allocated to a channel. Wider bandwidth allows for higher data speeds but may have different propagation characteristics."
            
            "mcc", "mobile country code" -> "Mobile Country Code identifies the country where the cell is registered. Each country has a unique MCC assigned by the ITU."
            
            "mnc", "mobile network code" -> "Mobile Network Code identifies the specific mobile network operator within a country. Combined with MCC, it uniquely identifies the carrier."
            
            "rsrp", "reference signal received power" -> "Reference Signal Received Power measures the power level of reference signals from the cell. Values range from -44 dBm (excellent) to -140 dBm (poor)."
            
            "rsrq", "reference signal received quality" -> "Reference Signal Received Quality measures signal quality as the ratio of RSRP to total received power. Values range from -3 dB (excellent) to -19.5 dB (poor)."
            
            "rssi", "received signal strength indicator" -> "Received Signal Strength Indicator measures the total received power within the channel bandwidth, including both signal and noise."
            
            "sinr", "signal to interference plus noise ratio" -> "Signal to Interference plus Noise Ratio measures how much stronger the desired signal is compared to interference and background noise. Higher values indicate better quality."
            
            "cqi", "channel quality indicator" -> "Channel Quality Indicator is reported by your device to the network to indicate the downlink channel quality, influencing modulation and coding schemes."
            
            "ta", "timing advance" -> "Timing Advance synchronizes your device's transmission timing with the network. It represents the round-trip time and can estimate distance to the tower (~78 meters per TA unit)."
            
            "ss rsrp", "synchronization signal reference signal received power" -> "Synchronization Signal RSRP is the 5G equivalent of LTE RSRP, measuring the power level of synchronization signals in 5G NR networks."
            
            "dbm", "decibel milliwatts" -> "Decibel milliwatts is a logarithmic unit for measuring power. In cellular signals: >-75 dBm (excellent), -75 to -85 dBm (good), -85 to -95 dBm (fair), <-95 dBm (poor)."
            
            "network type" -> "Indicates the radio access technology: 2G (GSM), 3G (WCDMA/UMTS), 4G (LTE), or 5G (NR). Each generation offers different capabilities and performance characteristics."
            
            "signal strength" -> "Overall measure of how strong the radio signal is from the cell tower. Stronger signals generally mean better connection quality and faster data speeds."
            
            "signal quality" -> "Measure of how clean the signal is, considering both strength and interference. Good quality means less errors and more reliable communication."
            
            "nrarfcn", "nr absolute radio frequency channel number" -> "NR ARFCN specifies the exact frequency of the connected 5G NR channel, similar to EARFCN in LTE but for 5G networks."
            
            "ssb", "synchronization signal block" -> "Synchronization Signal Blocks are transmitted by 5G cells to help devices synchronize and measure signal quality during initial access and mobility."
            
            "pci conflict" -> "PCI Conflict occurs when neighboring cells use the same Physical Cell ID, which can cause interference and handover issues in the network."
            
            "handover" -> "Handover is the process of transferring an ongoing call or data session from one cell to another as you move through the network coverage area."
            
            "cell reselection" -> "Cell Reselection is the process where idle devices choose the best available cell to camp on based on signal strength and quality measurements."
            
            "ca", "carrier aggregation" -> "Carrier Aggregation combines multiple frequency bands to increase overall bandwidth and data speeds, allowing higher peak rates than single carriers."
            
            "mimo", "multiple input multiple output" -> "Multiple Input Multiple Output uses multiple antennas at both transmitter and receiver to improve data rates and signal reliability through spatial diversity."
            
            "beamforming" -> "Beamforming focuses radio signals in specific directions rather than broadcasting omni-directionally, improving signal strength and reducing interference."
            
            else -> "This term describes a cellular network measurement or parameter. For detailed technical information, consult 3GPP specifications or network operator documentation."
        }
    }

    /**
     * Get all available terminology categories
     */
    fun getTerminologyCategories(): Map<String, List<String>> {
        return mapOf(
            "Basic Identifiers" to listOf("CID", "LAC", "MCC", "MNC", "PCI", "eNB ID", "Cell Sector ID", "TAC"),
            "Signal Metrics" to listOf("RSRP", "RSRQ", "RSSI", "SINR", "CQI", "Signal Strength", "Signal Quality"),
            "Network Technology" to listOf("Network Type", "Band Number", "Bandwidth", "EARFCN", "NR ARFCN"),
            "5G Specific" to listOf("SS RSRP", "SSB", "Beamforming", "Carrier Aggregation", "MIMO"),
            "Advanced Concepts" to listOf("Timing Advance", "Handover", "Cell Reselection", "PCI Conflict")
        )
    }

    /**
     * Check if a term has an available explanation
     */
    fun hasExplanation(term: String): Boolean {
        return getExplanation(term) != "No information available."
    }

    /**
     * Get a simplified explanation for quick tooltips
     */
    fun getShortExplanation(term: String): String {
        val fullExplanation = getExplanation(term)
        return fullExplanation.substringBefore(".") + "." // First sentence only
    }
}