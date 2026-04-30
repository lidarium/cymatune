package com.cymatune.dialer

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class DialerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // DialerActivity exists strictly to catch system intents (ACTION_DIAL, ACTION_CALL)
        // and route them immediately into our MainActivity's Dialer tab.
        
        val mainIntent = Intent(this, com.cymatune.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // Pass along the data URI (tel:xxx) if it exists
            data = intent.data
            action = intent.action
        }
        
        startActivity(mainIntent)
        finish() // Close this proxy activity immediately
    }
}
