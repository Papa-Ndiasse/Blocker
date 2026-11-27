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

    @Test
    fun `protects Samsung Galaxy A55 One UI Accessibility, Device Care Deep Sleep, Secure Folder, and allows self-update`() {
        val samsungA11yTamper = ScreenContentClassifier.classifyAppText(
            "com.samsung.accessibility",
            listOf("Applications installées", "Blocker", "Activé"),
            isSealed = true
        )
        assertNotNull(samsungA11yTamper)
        assertTrue(samsungA11yTamper!!.isSettingsTamper)

        val samsungDeepSleepTamper = ScreenContentClassifier.classifyAppText(
            "com.samsung.android.lool",
            listOf("Batterie", "Mise en veille profonde", "Blocker"),
            isSealed = true
        )
        assertNotNull(samsungDeepSleepTamper)
        assertTrue(samsungDeepSleepTamper!!.isSettingsTamper)

        assertNull(
            ScreenContentClassifier.classifyLaunchedPackage(
                ScreenContentClassifier.SAMSUNG_SECURE_FOLDER_PKG,
                lockSamsungSecureFolder = false
            )
        )
        val secureFolderLocked = ScreenContentClassifier.classifyLaunchedPackage(
            ScreenContentClassifier.SAMSUNG_SECURE_FOLDER_PKG,
            lockSamsungSecureFolder = true
        )
        assertNotNull(secureFolderLocked)
        assertTrue(secureFolderLocked!!.goHomeImmediately)

        // Samsung PackageInstaller must allow updating Blocker (for auto-updates & emergency recovery!)
        assertNull(
            ScreenContentClassifier.classifyAppText(
                "com.samsung.android.packageinstaller",
                listOf("Blocker", "Voulez-vous mettre à jour cette application ?", "Mettre à jour"),
                isSealed = true
            )
        )
        // But uninstalling Blocker via Samsung PackageInstaller must remain blocked when sealed
        val uninstallTamper = ScreenContentClassifier.classifyAppText(
            "com.samsung.android.packageinstaller",
            listOf("Blocker", "Voulez-vous désinstaller cette application ?", "OK"),
            isSealed = true
        )
        assertNotNull(uninstallTamper)
        assertTrue(uninstallTamper!!.isSettingsTamper)
    }

    @Test
    fun `blocks websites on browser pages even without URL bar and blocks Keiyoushi 18+ in Mihon and PackageInstaller while keeping Poseidon Scan immune`() {
        val keiyoushiNames = setOf("toongod", "manytoon", "hentaifox", "comick")

        // 1. Domain extraction from noisy toolbar text
        val extracted = ScreenContentClassifier.extractDomainsFromText("pornhub.com — Connexion sécurisée")
        assertTrue("pornhub.com" in extracted)

        // 2. General browser page guard catches adult / Comick pages even when URL bar is hidden
        val browserPage = ScreenContentClassifier.classifyAppText(
            "com.sec.android.app.sbrowser",
            listOf("Comick - Read Manga & Manhwa", "Latest chapters"),
            isSealed = true
        )
        assertNotNull(browserPage)

        // Poseidon Scan stays 100% immune on browsers
        val poseidonBrowser = ScreenContentClassifier.classifyAppText(
            "com.sec.android.app.sbrowser",
            listOf("poseidon-scan.net", "Poseidon Scan", "Chapitre 42"),
            isSealed = true
        )
        assertNull(poseidonBrowser)

        // 3. Mihon blocks Keiyoushi 18+ extensions by display name (including forks like app.mihon.beta)
        val mihonExt = ScreenContentClassifier.classifyAppText(
            "app.mihon",
            listOf("Extensions", "ToonGod (EN)", "Installer"),
            isSealed = true,
            blockedKeiyoushiNames = keiyoushiNames
        )
        assertNotNull(mihonExt)
        assertEquals(ScreenContentClassifier.REASON_MIHON_NSFW, mihonExt!!.reason)

        // 4. Samsung PackageInstaller blocks installing Keiyoushi 18+ extension APKs
        val installerExt = ScreenContentClassifier.classifyAppText(
            "com.samsung.android.packageinstaller",
            listOf("Tachiyomi: ManyToon", "Voulez-vous installer cette application ?", "Installer"),
            isSealed = true,
            blockedKeiyoushiNames = keiyoushiNames
        )
        assertNotNull(installerExt)
        assertEquals(ScreenContentClassifier.REASON_MIHON_NSFW, installerExt!!.reason)

        // Poseidon Scan extension install stays 100% immune
        val installerPoseidon = ScreenContentClassifier.classifyAppText(
            "com.samsung.android.packageinstaller",
            listOf("Tachiyomi: Poseidon Scan", "Voulez-vous installer cette application ?", "Installer"),
            isSealed = true,
            blockedKeiyoushiNames = keiyoushiNames
        )
        assertNull(installerPoseidon)

        // Discord, TikTok, Snapchat, Instagram, YouTube & normal French slang comments never false-positive
        assertNull(
            ScreenContentClassifier.classifyAppText(
                "com.discord",
                listOf("#nsfw-memes", "18+", "adult content"),
                isSealed = true
            )
        )
        assertNull(
            ScreenContentClassifier.classifyAppText(
                "com.zhiliaoapp.musically",
                listOf("Emilietalk", "Oui ça doit être ça #fyp", "elle est trop chaude", "18+"),
                isSealed = true
            )
        )
        assertNull(
            ScreenContentClassifier.classifyAppText(
                "com.android.chrome",
                listOf("Actualités France", "Une journée chaude d'été", "escorte policière"),
                isSealed = true
            )
        )
        assertNull(ScreenContentClassifier.classifyUrl("https://discord.com/channels/12345/nsfw"))
        assertNull(ScreenContentClassifier.classifyUrl("https://discord.gg/nsfw123"))

        // Reddit borderline subreddits & French 18+ warnings must be caught
        assertNotNull(
            ScreenContentClassifier.classifyAppText(
                "com.reddit.android",
                listOf("r/frenchgonewild", "Rejoindre"),
                isSealed = true
            )
        )
        assertNotNull(
            ScreenContentClassifier.classifyAppText(
                "com.reddit.android",
                listOf("r/kpopfap", "Top posts"),
                isSealed = true
            )
        )
        assertNotNull(
            ScreenContentClassifier.classifyAppText(
                "com.reddit.android",
                listOf("Communauté 18+", "Oui, j'ai plus de 18 ans"),
                isSealed = true
            )
        )
        assertNotNull(
            ScreenContentClassifier.classifyAppText(
                "com.reddit.android",
                listOf("u/onlyfans_bella", "Link in bio"),
                isSealed = true
            )
        )
        assertNotNull(
            ScreenContentClassifier.classifyAppText(
                "com.reddit.android",
                listOf("Photo post", "__viewid_nsfw__"),
                isSealed = true
            )
        )
        assertNotNull(
            ScreenContentClassifier.classifyAppText(
                "com.reddit.android",
                listOf("First time posting [F]", "Rate my body"),
                isSealed = true
            )
        )
        // SFW photography subreddits like r/FoodPorn and r/EarthPorn must NOT false-positive
        assertNull(
            ScreenContentClassifier.classifyAppText(
                "com.reddit.android",
                listOf("r/FoodPorn", "Homemade ramen bowl", "1.2k upvotes"),
                isSealed = true
            )
        )
        assertNull(
            ScreenContentClassifier.classifyAppText(
                "com.reddit.android",
                listOf("r/EarthPorn", "Sunrise over Mont Blanc", "4.5k upvotes"),
                isSealed = true
            )
        )
    }

    @Test
    fun packageInstallerAllowsUninstallingTorButBlocksInstallingTorAndUninstallingBlocker() {
        // Uninstalling Tor MUST be allowed in Samsung/Google PackageInstaller
        assertNull(
            ScreenContentClassifier.classifyAppText(
                "com.samsung.android.packageinstaller",
                listOf("Tor Browser", "Voulez-vous désinstaller cette application ?", "Annuler", "OK"),
                isSealed = true
            )
        )
        // Installing Tor MUST remain blocked
        assertNotNull(
            ScreenContentClassifier.classifyAppText(
                "com.samsung.android.packageinstaller",
                listOf("Tor Browser", "Voulez-vous installer cette application ?", "Installer"),
                isSealed = true
            )
        )
        // Uninstalling Blocker MUST remain blocked once sealed
        val blockerUninstall = ScreenContentClassifier.classifyAppText(
            "com.samsung.android.packageinstaller",
            listOf("Blocker", "Voulez-vous désinstaller cette application ?", "OK"),
            isSealed = true
        )
        assertNotNull(blockerUninstall)
        assertTrue(blockerUninstall!!.isSettingsTamper)
    }
}
