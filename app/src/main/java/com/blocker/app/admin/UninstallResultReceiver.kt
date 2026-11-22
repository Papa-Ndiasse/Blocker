package com.blocker.app.admin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives silent uninstall status callbacks from PackageInstaller.uninstall()
 * when Blocker (as Device Owner) removes Tor or Tor-based apps automatically.
 */
class UninstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: "unknown"
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i(TAG, "Successfully purged Tor package: $pkg")
        } else {
            Log.w(TAG, "Silent uninstall status=$status for $pkg ($message) — package remains hidden & suspended")
        }
    }

    companion object {
        private const val TAG = "UninstallResultReceiver"
    }
}
