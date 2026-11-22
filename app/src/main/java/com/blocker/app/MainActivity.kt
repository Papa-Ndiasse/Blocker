package com.blocker.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.blocker.app.admin.BlockerDeviceAdminReceiver
import com.blocker.app.admin.EmergencyKillSwitch
import com.blocker.app.ui.BlockerRootScreen
import com.blocker.app.ui.MainViewModel
import com.blocker.app.ui.theme.BlockerTheme
import com.blocker.app.vpn.BlockerVpnService

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            BlockerVpnService.start(this)
        }
        viewModel.refreshStatus()
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (EmergencyKillSwitch.isActive()) {
            EmergencyKillSwitch.executeIfActive(this)
        }
        setContent {
            BlockerTheme {
                BlockerRootScreen(
                    viewModel = viewModel,
                    onRequestStartVpn = { startVpnWithConsentIfNeeded() },
                    onRequestDeviceAdmin = { requestStandardDeviceAdmin() },
                    onRequestBatteryUnrestricted = { requestIgnoreBatteryOptimizations() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStatus()
    }

    private fun startVpnWithConsentIfNeeded() {
        if (EmergencyKillSwitch.isActive()) return
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnConsentLauncher.launch(prepareIntent)
        } else {
            BlockerVpnService.start(this)
            viewModel.refreshStatus()
        }
    }

    private fun requestStandardDeviceAdmin() {
        if (EmergencyKillSwitch.isActive()) return
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                BlockerDeviceAdminReceiver.componentName(this@MainActivity)
            )
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_description)
            )
        }
        deviceAdminLauncher.launch(intent)
    }

    /**
     * Prevents Samsung Galaxy A55 One UI Device Care from putting Blocker into Deep Sleep
     * and killing the local DNS VPN or Accessibility Guard in the background.
     */
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }
    }
}
