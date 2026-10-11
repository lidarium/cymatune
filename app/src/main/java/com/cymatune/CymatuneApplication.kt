package com.cymatune

import android.app.Application
import androidx.multidex.MultiDex
import com.cymatune.db.DatabaseManager

class CymatuneApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize crash handler
        com.cymatune.util.CrashHandler.init(this)
        
        // Initialize database
        DatabaseManager.initialize(applicationContext)
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }
}
