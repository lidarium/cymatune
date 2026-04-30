package com.cymatune.dialer

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log

class SecureInCallService : InCallService() {

    override fun onCallAdded(call: Call?) {
        super.onCallAdded(call)
        Log.i("SecureInCallService", "New call added: $call")
        
        // Register active call in manager
        CallManager.updateCall(call)
        
        // Route to the Secure Call UI
        val intent = Intent(this, InCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        Log.i("SecureInCallService", "Call removed: $call")
        
        // Unregister call
        val current = CallManager.currentCall.value
        if (current != null && current == call) {
            CallManager.updateCall(null)
        }
    }
}
