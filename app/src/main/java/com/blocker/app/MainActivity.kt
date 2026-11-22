package com.blocker.app

import android.app.Activity
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BlockerTheme {
                BlockerRootScreen(
                    viewModel = viewModel,
                    onRequestTestVpn = { startVpnWithConsentIfNeeded() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStatus()
    }

    private fun startVpnWithConsentIfNeeded() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnConsentLauncher.launch(prepareIntent)
        } else {
            BlockerVpnService.start(this)
            viewModel.refreshStatus()
        }
    }
}
