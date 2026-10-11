#include <jni.h>
#include <string>
#include <android/log.h>
#include <vector>
#include <cmath>

#define LOG_TAG "NativeLib"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Structure to hold tower signal parameters
struct TowerSignal {
    int cid;
    int lac;
    int mcc;
    int mnc;
    int rsrp;
    int rsrq;
    int sinr;
    int ta;
};

extern "C" JNIEXPORT jstring JNICALL
Java_com_cymatune_detection_FakeTowerDetector_analyzeSignalNative(
        JNIEnv* env,
        jobject /* this */,
        jint cid,
        jint lac,
        jint mcc,
        jint mnc,
        jint rsrp,
        jint rsrq,
        jint sinr,
        jint ta) {

    TowerSignal signal = {
        .cid = static_cast<int>(cid),
        .lac = static_cast<int>(lac),
        .mcc = static_cast<int>(mcc),
        .mnc = static_cast<int>(mnc),
        .rsrp = static_cast<int>(rsrp),
        .rsrq = static_cast<int>(rsrq),
        .sinr = static_cast<int>(sinr),
        .ta = static_cast<int>(ta)
    };

    // Signal quality analysis
    float signalQuality = 0.0f;
    if (signal.rsrp > -110 && signal.sinr > 5) {
        signalQuality = 1.0f - ((-110 - signal.rsrp) / 60.0f);
    }

    // Timing Advance analysis
    float taAnomalyScore = 0.0f;
    if (signal.ta > 0 && signal.ta < 64) {
        // Normal TA range
        taAnomalyScore = 0.2f;
    } else if (signal.ta >= 64) {
        // Suspiciously large TA
        taAnomalyScore = 0.8f;
    } else if (signal.ta == 0 && signal.rsrp < -100) {
        // TA=0 with weak signal is suspicious
        taAnomalyScore = 0.7f;
    }

    // Compose analysis result
    char result[100];
    snprintf(result, sizeof(result), 
             "Quality:%.2f, TA_Anomaly:%.2f, CID:%d", 
             signalQuality, taAnomalyScore, signal.cid);

    LOGD("Native signal analysis: %s", result);
    return env->NewStringUTF(result);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_cymatune_util_DistanceEstimator_calculateDistanceNative(
        JNIEnv* env,
        jobject /* this */,
        jint ta,
        jint rsrp,
        jint rsrq,
        jint sinr) {
    
    LOGD("DIAGNOSTIC: Native distance calculation called - TA: %d, RSRP: %d, RSRQ: %d, SINR: %d", ta, rsrp, rsrq, sinr);
    
    // Validate inputs
    if (ta < 0 && ta != -2147483648) { // -2147483648 is our Int.MIN_VALUE placeholder
        LOGD("DIAGNOSTIC: Invalid TA value: %d", ta);
        ta = -1; // Mark as invalid
    }
    
    float distance = -1.0f; // Default to unknown distance
    
    // Primary method: Timing Advance based distance
    if (ta >= 0) {
        // CORRECT: 1 TA = 78.125 meters (physics-based calculation)
        distance = ta * 78.125f;
        LOGD("DIAGNOSTIC: TA-based distance calculated: %.1f meters (TA: %d)", distance, ta);
        
        // Apply signal quality corrections based on SINR
        if (sinr > 10) {
            // Excellent SINR - reduce distance slightly (higher confidence)
            distance *= 0.95f;
            LOGD("DIAGNOSTIC: Excellent SINR (%d dB) - reduced distance to %.1f m", sinr, distance);
        } else if (sinr >= 0) {
            // Good SINR - minor adjustment
            distance *= 0.98f;
            LOGD("DIAGNOSTIC: Good SINR (%d dB) - minor distance adjustment to %.1f m", sinr, distance);
        } else if (sinr >= -5) {
            // Poor SINR - increase distance (lower confidence)
            distance *= 1.05f;
            LOGD("DIAGNOSTIC: Poor SINR (%d dB) - increased distance to %.1f m", sinr, distance);
        } else if (sinr > -2147483648) {
            // Very poor SINR - significant penalty
            distance *= 1.15f;
            LOGD("DIAGNOSTIC: Very poor SINR (%d dB) - significant distance penalty to %.1f m", sinr, distance);
        }
        
    } else if (rsrp > -120 && rsrp != -2147483648) {
        // Fallback: RSRP-based distance estimation
        // Use simplified logarithmic model: distance = 10^((|RSRP| - reference) / slope)
        int absRsrp = abs(rsrp);
        float pathLoss = absRsrp - 40.0f; // Assume 40 dB reference loss at 1km
        
        if (pathLoss > 0) {
            distance = pow(10.0f, pathLoss / 30.0f) * 1000.0f; // Convert to meters
            LOGD("DIAGNOSTIC: RSRP-based distance calculated: %.1f meters (RSRP: %d, pathLoss: %.1f)", distance, rsrp, pathLoss);
            
            // Apply RSRQ quality correction
            if (rsrq > -10 && rsrq != -2147483648) {
                // Good RSRQ - reduce distance
                distance *= 0.9f;
                LOGD("DIAGNOSTIC: Good RSRQ (%d dB) - reduced distance to %.1f m", rsrq, distance);
            } else if (rsrq <= -15 && rsrq != -2147483648) {
                // Poor RSRQ - increase distance
                distance *= 1.1f;
                LOGD("DIAGNOSTIC: Poor RSRQ (%d dB) - increased distance to %.1f m", rsrq, distance);
            }
        } else {
            LOGD("DIAGNOSTIC: RSRP too strong for distance calculation (RSRP: %d)", rsrp);
        }
    }
    
    // Validate final distance
    if (distance > 0 && distance < 1000000.0f) { // Reasonable range: 1m to 1000km
        LOGD("DIAGNOSTIC: Final distance: %.1f meters (%.2f km)", distance, distance/1000.0f);
    } else {
        distance = -1.0f;
        LOGD("DIAGNOSTIC: Distance out of reasonable range, returning unknown distance");
    }
    
    // Create and return result array
    jfloatArray result = env->NewFloatArray(1);
    env->SetFloatArrayRegion(result, 0, 1, &distance);
    LOGD("DIAGNOSTIC: Native calculation complete, returning distance array");
    return result;
}