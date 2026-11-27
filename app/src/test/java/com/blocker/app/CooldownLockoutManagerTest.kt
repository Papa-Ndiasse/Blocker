package com.blocker.app

import com.blocker.app.guard.CooldownLockoutEngine
import com.blocker.app.guard.LockoutState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CooldownLockoutManagerTest {

    private val min = 60_000L

    @Test
    fun `3 blocks within 10 min triggers 5 min lockout and doubles 5 - 10 - 20 - 40 - 60 within same hour`() {
        var state = LockoutState()
        val t0 = 1_000_000_000L

        // Strike 1 & 2 -> no lockout yet
        val s1 = CooldownLockoutEngine.recordContentBlockStrike(state, t0)
        assertFalse(s1.triggeredLockout)
        state = s1.newState

        // Rapid duplicate (<2s) does NOT count as a 2nd strike
        val dup = CooldownLockoutEngine.recordContentBlockStrike(state, t0 + 900L)
        assertFalse(dup.triggeredLockout)
        assertEquals(1, dup.newState.recentStrikeTimestamps.size)

        val s2 = CooldownLockoutEngine.recordContentBlockStrike(state, t0 + 2 * min)
        assertFalse(s2.triggeredLockout)
        state = s2.newState

        // Strike 3 within 10 min -> 5 min lockout!
        val l1 = CooldownLockoutEngine.recordContentBlockStrike(state, t0 + 5 * min)
        assertTrue(l1.triggeredLockout)
        assertEquals(5, l1.durationMinutes)
        assertEquals(0, l1.escalationLevel)
        state = l1.newState
        assertTrue(CooldownLockoutEngine.isLockoutActive(state, t0 + 7 * min))

        // Helper to trigger 3 strikes starting at [startMs]
        fun triggerThree(startMs: Long): Pair<Int, LockoutState> {
            var cur = state
            cur = CooldownLockoutEngine.recordContentBlockStrike(cur, startMs).newState
            cur = CooldownLockoutEngine.recordContentBlockStrike(cur, startMs + 3_000L).newState
            val res = CooldownLockoutEngine.recordContentBlockStrike(cur, startMs + 6_000L)
            assertTrue(res.triggeredLockout)
            return res.durationMinutes to res.newState
        }

        // Lockout #2 within 1 hour of Lockout #1 ending -> 10 min
        val (d2, st2) = triggerThree(state.lockoutUntilMs + 5 * min)
        assertEquals(10, d2)
        state = st2

        // Lockout #3 within 1 hour -> 20 min
        val (d3, st3) = triggerThree(state.lockoutUntilMs + 5 * min)
        assertEquals(20, d3)
        state = st3

        // Lockout #4 within 1 hour -> 40 min
        val (d4, st4) = triggerThree(state.lockoutUntilMs + 5 * min)
        assertEquals(40, d4)
        state = st4

        // Lockout #5 within 1 hour -> capped at 60 min (1h)
        val (d5, st5) = triggerThree(state.lockoutUntilMs + 5 * min)
        assertEquals(60, d5)
        state = st5

        // Lockout #6 still within 1 hour -> stays capped at 60 min (1h)
        val (d6, st6) = triggerThree(state.lockoutUntilMs + 5 * min)
        assertEquals(60, d6)
        state = st6

        // After > 1 full clean hour -> resets back down to 5 min!
        val (dReset, _) = triggerThree(state.lockoutUntilMs + 61 * min)
        assertEquals(5, dReset)
    }

    @Test
    fun `strikes older than 10 minutes expire and do not trigger lockout`() {
        var state = LockoutState()
        val t0 = 2_000_000_000L

        state = CooldownLockoutEngine.recordContentBlockStrike(state, t0).newState
        state = CooldownLockoutEngine.recordContentBlockStrike(state, t0 + 3 * min).newState
        // 3rd strike happens 11 min after the 1st -> 1st expired, only 2 active strikes!
        val res = CooldownLockoutEngine.recordContentBlockStrike(state, t0 + 11 * min)
        assertFalse(res.triggeredLockout)
        assertEquals(2, res.newState.recentStrikeTimestamps.size)
    }
}
