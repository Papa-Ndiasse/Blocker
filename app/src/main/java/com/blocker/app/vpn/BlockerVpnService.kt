package com.blocker.app.vpn

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local VPN service has been permanently removed to prevent One UI / Samsung Galaxy A55 crashes.
 * Kept as a harmless no-op shim so existing references remain compile-safe.
 */
object BlockerVpnService {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun start(context: Context) {
        // Intentionally disabled — VPN removed.
    }

    fun stopForEmergency(context: Context) {
        // Intentionally disabled — VPN removed.
    }
}
