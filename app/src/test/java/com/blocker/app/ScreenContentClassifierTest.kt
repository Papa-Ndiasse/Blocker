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
    fun `detects Android Settings anti-tamper on Blocker Accessibility page`() {
        val tamper = ScreenContentClassifier.classifyAppText(
            "com.android.settings",
            listOf("Accessibilité", "Blocker", "Utiliser Blocker", "Raccourci Blocker")
        )
        assertNotNull(tamper)
        assertTrue(tamper!!.isSettingsTamper)

        val normalSettings = ScreenContentClassifier.classifyAppText(
            "com.android.settings",
            listOf("Wi-Fi", "Bluetooth", "Batterie")
        )
        assertNull(normalSettings)
    }
}
