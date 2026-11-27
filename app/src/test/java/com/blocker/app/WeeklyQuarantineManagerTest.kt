package com.blocker.app

import com.blocker.app.guard.QuarantineTargetKind
import com.blocker.app.guard.ScreenContentClassifier
import com.blocker.app.guard.WeeklyQuarantineEngine
import com.blocker.app.guard.WeeklyQuarantineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyQuarantineManagerTest {

    @Test
    fun moreThanTenBlocksWithinSameWeekQuarantinesAppPermanently() {
        var state = WeeklyQuarantineState()
        val target = WeeklyQuarantineEngine.resolveTarget(
            sourcePackage = "com.reddit.frontpage",
            extractedHost = null,
            isBrowser = false
        )!!
        assertEquals("app:com.reddit.frontpage", target.key)
        assertEquals("Reddit", target.displayName)
        assertEquals(QuarantineTargetKind.APP, target.kind)

        val base = 1_700_000_000_000L
        // First 10 blocks within the week do NOT quarantine yet
        for (i in 1..10) {
            val outcome = WeeklyQuarantineEngine.recordBlock(
                state = state,
                target = target,
                reason = ScreenContentClassifier.REASON_REDDIT_NSFW,
                nowMs = base + i * 60_000L
            )
            state = outcome.newState
            assertEquals(i, outcome.weeklyCount)
            assertNull("Should not quarantine on block #$i", outcome.newlyQuarantined)
            assertNull(WeeklyQuarantineEngine.findQuarantinedApp(state, "com.reddit.frontpage"))
        }

        // 11th block (> 10 within 7 days) triggers permanent quarantine!
        val eleventh = WeeklyQuarantineEngine.recordBlock(
            state = state,
            target = target,
            reason = ScreenContentClassifier.REASON_REDDIT_NSFW,
            nowMs = base + 11 * 60_000L
        )
        state = eleventh.newState
        assertNotNull(eleventh.newlyQuarantined)
        assertEquals(11, eleventh.newlyQuarantined!!.weeklyBlockCount)
        assertFalse(eleventh.newlyQuarantined!!.reportedToGitHub)
        assertNotNull(WeeklyQuarantineEngine.findQuarantinedApp(state, "com.reddit.frontpage"))
        assertTrue(state.hasUnreportedEntries())

        // Marking reported to GitHub clears unreported flag while keeping quarantine active
        val reported = WeeklyQuarantineEngine.markReportedToGitHub(state, target.key, issueNumber = 42)
        assertFalse(reported.hasUnreportedEntries())
        assertEquals(42, WeeklyQuarantineEngine.findQuarantinedApp(reported, "com.reddit.frontpage")?.githubIssueNumber)
    }

    @Test
    fun moreThanTenBlocksInBrowserQuarantinesSiteNotBrowserAndBlocksAllSubdomains() {
        var state = WeeklyQuarantineState()
        val target = WeeklyQuarantineEngine.resolveTarget(
            sourcePackage = "com.android.chrome",
            extractedHost = "https://old.reddit.com/r/nsfw/comments/123",
            isBrowser = true
        )!!
        assertEquals("site:reddit.com", target.key)
        assertEquals(QuarantineTargetKind.SITE, target.kind)

        val base = 1_700_000_000_000L
        for (i in 1..11) {
            state = WeeklyQuarantineEngine.recordBlock(
                state = state,
                target = target,
                reason = ScreenContentClassifier.REASON_BROWSER_URL,
                nowMs = base + i * 10_000L
            ).newState
        }

        // Chrome itself is NOT quarantined; reddit.com + every subdomain IS completely blocked
        assertNull(WeeklyQuarantineEngine.findQuarantinedApp(state, "com.android.chrome"))
        assertNotNull(WeeklyQuarantineEngine.findQuarantinedSite(state, "reddit.com"))
        assertNotNull(WeeklyQuarantineEngine.findQuarantinedSite(state, "https://www.reddit.com/r/programming"))
        assertNotNull(WeeklyQuarantineEngine.findQuarantinedSite(state, "m.reddit.com"))
    }

    @Test
    fun blocksOlderThanSevenDaysExpireAndDoNotTriggerQuarantine() {
        var state = WeeklyQuarantineState()
        val target = WeeklyQuarantineEngine.resolveTarget("com.twitter.android", null, isBrowser = false)!!
        val base = 1_700_000_000_000L

        // 8 blocks in week 1
        for (i in 1..8) {
            state = WeeklyQuarantineEngine.recordBlock(
                state = state,
                target = target,
                reason = ScreenContentClassifier.REASON_X_NSFW,
                nowMs = base + i * 60_000L
            ).newState
        }

        // 8 days later, old 8 blocks expire -> 5 new blocks should only count as 5/10
        val nextWeek = base + 8L * 24L * 60L * 60L * 1000L
        var lastCount = 0
        for (i in 1..5) {
            val outcome = WeeklyQuarantineEngine.recordBlock(
                state = state,
                target = target,
                reason = ScreenContentClassifier.REASON_X_NSFW,
                nowMs = nextWeek + i * 60_000L
            )
            state = outcome.newState
            lastCount = outcome.weeklyCount
            assertNull(outcome.newlyQuarantined)
        }
        assertEquals(5, lastCount)
        assertNull(WeeklyQuarantineEngine.findQuarantinedApp(state, "com.twitter.android"))
    }

    @Test
    fun rapidDuplicateAccessibilityEventsWithinThreeSecondsCountOnce() {
        var state = WeeklyQuarantineState()
        val target = WeeklyQuarantineEngine.resolveTarget("com.reddit.frontpage", null, isBrowser = false)!!
        val t0 = 1_700_000_000_000L

        state = WeeklyQuarantineEngine.recordBlock(state, target, "r", t0).newState
        state = WeeklyQuarantineEngine.recordBlock(state, target, "r", t0 + 1_000L).newState
        val third = WeeklyQuarantineEngine.recordBlock(state, target, "r", t0 + 2_500L)
        assertEquals(1, third.weeklyCount)

        val nextPage = WeeklyQuarantineEngine.recordBlock(third.newState, target, "r", t0 + 4_000L)
        assertEquals(2, nextPage.weeklyCount)
    }

    @Test
    fun compiledUpdateUnblockGrantClearsQuarantineOnlyWhenNewerThanQuarantineTime() {
        var state = WeeklyQuarantineState()
        val target = WeeklyQuarantineEngine.resolveTarget("com.reddit.frontpage", null, isBrowser = false)!!
        val base = 1_700_000_000_000L
        for (i in 1..11) {
            state = WeeklyQuarantineEngine.recordBlock(state, target, "nsfw", base + i * 10_000L).newState
        }
        val quarantinedAt = state.quarantined[target.key]!!.quarantinedAtMs

        // Older grant does NOT unblock
        val stillBlocked = WeeklyQuarantineEngine.applyUnblockGrants(
            state,
            mapOf(target.key to quarantinedAt - 1_000L)
        )
        assertNotNull(WeeklyQuarantineEngine.findQuarantinedApp(stillBlocked, "com.reddit.frontpage"))

        // Newer grant compiled into a Blocker update DOES unblock cleanly
        val unblocked = WeeklyQuarantineEngine.applyUnblockGrants(
            state,
            mapOf(target.key to quarantinedAt + 1_000L)
        )
        assertNull(WeeklyQuarantineEngine.findQuarantinedApp(unblocked, "com.reddit.frontpage"))
        assertTrue(unblocked.quarantined.isEmpty())
    }

    @Test
    fun immuneAppsSystemAppsAndSearchEnginesAreNeverQuarantined() {
        assertNull(WeeklyQuarantineEngine.resolveTarget("com.discord", null, isBrowser = false))
        assertNull(WeeklyQuarantineEngine.resolveTarget("com.zhiliaoapp.musically", null, isBrowser = false))
        assertNull(WeeklyQuarantineEngine.resolveTarget("com.android.settings", null, isBrowser = false))
        assertNull(WeeklyQuarantineEngine.resolveTarget("com.blocker.app", null, isBrowser = false))
        assertNull(WeeklyQuarantineEngine.resolveTarget("com.android.chrome", null, isBrowser = true))
        assertNull(WeeklyQuarantineEngine.resolveTarget("com.android.chrome", "https://poseidon-scan.net/manga/x", isBrowser = true))
        assertNull(WeeklyQuarantineEngine.resolveTarget("com.android.chrome", "https://discord.com/channels/1/2", isBrowser = true))
        assertNull(WeeklyQuarantineEngine.resolveTarget("com.android.chrome", "https://www.google.com/search?q=x", isBrowser = true))
        assertNull(WeeklyQuarantineEngine.resolveTarget("com.android.chrome", "https://github.com/Papa-Ndiasse/Blocker", isBrowser = true))
    }

    @Test
    fun jsonRoundTripPreservesStrikesAndQuarantinedEntries() {
        var state = WeeklyQuarantineState()
        val reddit = WeeklyQuarantineEngine.resolveTarget("com.reddit.frontpage", null, isBrowser = false)!!
        val site = WeeklyQuarantineEngine.resolveTarget("com.android.chrome", "example-nsfw.com", isBrowser = true)!!
        val base = 1_700_000_000_000L

        for (i in 1..11) {
            state = WeeklyQuarantineEngine.recordBlock(state, reddit, "Reddit NSFW", base + i * 10_000L).newState
        }
        state = WeeklyQuarantineEngine.markReportedToGitHub(state, reddit.key, 7)
        for (i in 1..3) {
            state = WeeklyQuarantineEngine.recordBlock(state, site, "URL", base + i * 10_000L).newState
        }

        val serialized = WeeklyQuarantineEngine.serialize(state)
        val restored = WeeklyQuarantineEngine.deserialize(serialized)

        assertEquals(state, restored)
        assertEquals(7, restored.quarantined[reddit.key]?.githubIssueNumber)
        assertEquals(3, restored.weeklyStrikesByKey[site.key]?.size)
    }

    @Test
    fun officialRedditFrontpagePackageIsRecognizedByClassifier() {
        assertTrue(ScreenContentClassifier.isRedditPackage("com.reddit.frontpage"))
        assertNotNull(
            ScreenContentClassifier.classifyAppText(
                packageName = "com.reddit.frontpage",
                textSamples = listOf("r/gonewild", "18+"),
                isSealed = true
            )
        )
    }
}
