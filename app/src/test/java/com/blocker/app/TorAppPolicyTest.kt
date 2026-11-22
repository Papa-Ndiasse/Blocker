package com.blocker.app

import com.blocker.app.admin.TorAppPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorAppPolicyTest {

    @Test
    fun `detects official Tor packages and common Tor clones`() {
        assertTrue(TorAppPolicy.isTorPackage("org.torproject.torbrowser"))
        assertTrue(TorAppPolicy.isTorPackage("org.torproject.torbrowser_alpha"))
        assertTrue(TorAppPolicy.isTorPackage("org.torproject.android"))
        assertTrue(TorAppPolicy.isTorPackage("info.guardianproject.orfox"))
        assertTrue(TorAppPolicy.isTorPackage("pan.alexander.tordnscrypt.gp"))
        assertTrue(TorAppPolicy.isTorPackage("com.random.dev.torbrowser.free"))
        assertTrue(TorAppPolicy.isTorPackage("com.example.unknown", "Tor Browser Private"))
        assertTrue(TorAppPolicy.isTorPackage("com.example.unknown", "Onion Browser VPN"))
        assertTrue(TorAppPolicy.isTorPackage("com.example.unknown", "Orbot Proxy"))
        assertTrue(TorAppPolicy.isTorPackage("com.example.unknown", "Dark Web Browser"))
    }

    @Test
    fun `does not false-positive on legitimate apps containing tor substring`() {
        assertFalse(TorAppPolicy.isTorPackage("com.blocker.app", "Blocker"))
        assertFalse(TorAppPolicy.isTorPackage("com.google.android.calculator", "Calculator"))
        assertFalse(TorAppPolicy.isTorPackage("com.android.vending", "Google Play Store"))
        assertFalse(TorAppPolicy.isTorPackage("com.google.android.apps.translate", "Translator"))
        assertFalse(TorAppPolicy.isTorPackage("com.example.photoeditor", "Photo Editor"))
        assertFalse(TorAppPolicy.isTorPackage("com.example.monitor", "System Monitor"))
        assertFalse(TorAppPolicy.isTorPackage("com.android.chrome", "Chrome"))
        assertFalse(TorAppPolicy.isTorPackage("org.mozilla.firefox", "Firefox"))
    }
}
