package com.cymatune.ui

import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper

class MyLocationListener(
    private val handler: Handler,
    private val onLocationReceived: (Location) -> Unit
) : android.location.LocationListener {
    override fun onLocationChanged(location: Location) {
        handler.post {
            onLocationReceived(location)
        }
    }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
