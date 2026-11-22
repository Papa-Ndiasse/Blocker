package com.blocker.app

import com.blocker.app.guard.ScreenContentClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenContentClassifierTest {

    @Test
    fun `blocks Reddit NSFW and borderline subreddits and over-18 gates in app and browser`() {
        val gate = ScreenContentClassifier.classifyAppText(
            "com.reddit.android",
            listOf("Confirm you're over 18", "This community contains mature content", "Continue")
        )
        assertNotNull(gate)
        assertEquals(ScreenContentClassifier.REASON_REDDIT_NSFW, gate!!.reason)

        val nsfwTag = ScreenContentClassifier.classifyAppText(
            "com.reddit.android",
            listOf("r/gonewild", "NSFW", "Upvote")
        )
        assertNotNull(nsfwTag)

        val frSettings = ScreenContentClassifier.classifyAppText(
            "com.reddit.android",
            listOf("Paramètres", "Afficher le contenu pour adultes (18+)")
        )
        assertNotNull(frSettings)

        val redditUrl = ScreenContentClassifier.classifyUrl("https://www.reddit.com/r/asiansgonewild/hot/")
        assertNotNull(redditUrl)

        val safeReddit = ScreenContentClassifier.classifyAppText(
            "com.reddit.android",
            listOf("r/androiddev", "How to optimize Jetpack Compose recomposition", "42 comments")
        )
        assertNull(safeReddit)
    }

    @Test
    fun `blocks X Twitter sensitive media warnings and borderline hashtags`() {
        val sensitiveEn = ScreenContentClassifier.classifyAppText(
            "com.twitter.android",
            listOf("The following media includes potentially sensitive content.", "View")
        )
        assertNotNull(sensitiveEn)
        assertEquals(ScreenContentClassifier.REASON_X_NSFW, sensitiveEn!!.reason)

        val sensitiveFr = ScreenContentClassifier.classifyAppText(
            "com.twitter.android",
            listOf("Ce média peut contenir du contenu potentiellement sensible", "Afficher")
        )
        assertNotNull(sensitiveFr)

        val hashtag = ScreenContentClassifier.classifyAppText(
            "com.twitter.android",
            listOf("New post", "#onlyfans #18+", "Link in bio")
        )
        assertNotNull(hashtag)

        val xSearchUrl = ScreenContentClassifier.classifyUrl("https://x.com/search?q=hentai+r34&src=typed_query")
        assertNotNull(xSearchUrl)

        val safeX = ScreenContentClassifier.classifyAppText(
            "com.twitter.android",
            listOf("Kotlin 2.1 release notes are out today!", "120 reposts")
        )
        assertNull(safeX)
    }

    @Test
    fun `blocks explicit and drawn and Asian and AI searches in browsers`() {
        assertNotNull(ScreenContentClassifier.classifyUrl("https://www.google.fr/search?q=missav+jav"))
        assertNotNull(ScreenContentClassifier.classifyUrl("https://www.google.com/search?q=rule34+ecchi"))
        assertNotNull(ScreenContentClassifier.classifyUrl("https://www.google.com/search?q=nudify+ai+free"))
        assertNotNull(ScreenContentClassifier.classifyUrl("https://www.pixiv.net/en/tags/R-18/artworks"))
        assertNull(ScreenContentClassifier.classifyUrl("https://developer.android.com/reference/android/net/VpnService"))
    }

    @Test
    fun `detects Android Settings anti-tamper on Blocker Accessibility, Admin, AppInfo, VPN, and Private DNS`() {
        val a11yTamper = ScreenContentClassifier.classifyAppText(
            "com.android.settings",
            listOf("Accessibilité", "Blocker", "Utiliser Blocker", "Raccourci Blocker"),
            isSealed = true
        )
        assertNotNull(a11yTamper)
        assertTrue(a11yTamper!!.isSettingsTamper)

        val adminTamper = ScreenContentClassifier.classifyAppText(
            "com.android.settings",
            listOf("Administrateur de l'appareil", "Blocker", "Désactiver cette appli"),
            isSealed = true
        )
        assertNotNull(adminTamper)
        assertTrue(adminTamper!!.isSettingsTamper)

        val forceStopTamper = ScreenContentClassifier.classifyAppText(
            "com.android.settings",
            listOf("Infos sur l'appli", "Blocker", "Forcer l'arrêt", "Désinstaller"),
            isSealed = true
        )
        assertNotNull(forceStopTamper)
        assertTrue(forceStopTamper!!.isSettingsTamper)

        val vpnTamper = ScreenContentClassifier.classifyAppText(
            "com.android.settings",
            listOf("VPN", "Blocker DNS Firewall", "Déconnecter", "VPN toujours activé"),
            isSealed = true
        )
        assertNotNull(vpnTamper)
        assertTrue(vpnTamper!!.isSettingsTamper)

        val privateDnsTamper = ScreenContentClassifier.classifyAppText(
            "com.android.settings",
            listOf("DNS privé", "Nom d'hôte du fournisseur DNS privé", "dns.google"),
            isSealed = true
        )
        assertNotNull(privateDnsTamper)
        assertTrue(privateDnsTamper!!.isSettingsTamper)

        // Before sealing, user can still open App Info / Device Admin / VPN to finish setup
        assertNull(
            ScreenContentClassifier.classifyAppText(
                "com.android.settings",
                listOf("VPN", "Blocker DNS Firewall", "VPN toujours activé"),
                isSealed = false
            )
        )

        val normalSettings = ScreenContentClassifier.classifyAppText(
            "com.android.settings",
            listOf("Wi-Fi", "Bluetooth", "Batterie"),
            isSealed = true
        )
        assertNull(normalSettings)
    }

    @Test
    fun `blocks Tor installation in Play Store and launching Tor or locked Reddit and X without PC`() {
        val playStoreTor = ScreenContentClassifier.classifyAppText(
            "com.android.vending",
            listOf("Tor Browser", "The Tor Project", "Installer")
        )
        assertNotNull(playStoreTor)
        assertEquals(ScreenContentClassifier.REASON_TOR_OR_PROXY, playStoreTor!!.reason)

        val torLaunch = ScreenContentClassifier.classifyLaunchedPackage("org.torproject.torbrowser")
        assertNotNull(torLaunch)
        assertTrue(torLaunch!!.goHomeImmediately)

        assertNull(ScreenContentClassifier.classifyLaunchedPackage("com.reddit.android", lockRedditEntirely = false))
        assertNotNull(ScreenContentClassifier.classifyLaunchedPackage("com.reddit.android", lockRedditEntirely = true))
        assertNotNull(ScreenContentClassifier.classifyLaunchedPackage("com.twitter.android", lockXEntirely = true))
    }
}
