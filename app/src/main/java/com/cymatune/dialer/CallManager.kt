package com.cymatune.dialer

import android.telecom.Call
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton CallManager for Phase 2.6.
 * Bridges the background SecureInCallService and the foreground InCallActivity.
 * It holds the currently active Telecom Call object.
 */
object CallManager {

    private val _currentCall = MutableStateFlow<Call?>(null)
    val currentCall: StateFlow<Call?> = _currentCall

    fun updateCall(call: Call?) {
        _currentCall.value = call
    }

    fun acceptCall() {
        _currentCall.value?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
    }

    fun rejectCall() {
        val call = _currentCall.value
        if (call?.state == Call.STATE_RINGING) {
            call.reject(false, null)
        } else {
            call?.disconnect()
        }
    }

    fun disconnectCall() {
        _currentCall.value?.disconnect()
    }
}
