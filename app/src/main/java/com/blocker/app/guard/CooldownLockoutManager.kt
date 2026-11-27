package com.blocker.app.guard

data class LockoutState(
    val lockoutUntilMs: Long = 0L,
    val lastLockoutEndMs: Long = 0L,
    val escalationLevel: Int = 0, // 0=5m, 1=10m, 2=20m, 3=40m, 4=60m
    val recentStrikeTimestamps: List<Long> = emptyList()
)

data class StrikeOutcome(
    val newState: LockoutState,
    val triggeredLockout: Boolean,
    val durationMinutes: Int = 0,
    val escalationLevel: Int = 0
)

/**
 * Pure-Kotlin progressive phone lockout state machine:
 *   - 3 distinct content blocks within a 10-minute sliding window -> locks phone usage for 5 minutes.
 *   - If another lockout is triggered within 1 hour of the previous lockout ending, duration doubles:
 *       5 min -> 10 min -> 20 min -> 40 min -> 60 min (1h cap).
 *   - After 1 clean hour without a new lockout, the escalation resets back to 5 min.
 *   - Debounces rapid duplicate events (< 2s apart) so a single blocked page only counts as 1 strike.
 */
object CooldownLockoutEngine {

    const val STRIKES_TO_TRIGGER = 3
    const val STRIKE_WINDOW_MS = 10L * 60L * 1000L          // 10 minutes
    const val ESCALATION_WINDOW_MS = 60L * 60L * 1000L      // 1 hour
    const val MIN_STRIKE_SPACING_MS = 2_000L                // 2 seconds (prevents 1 page = 3 strikes)

    val LOCKOUT_LADDER_MINUTES = intArrayOf(5, 10, 20, 40, 60)

    fun isLockoutActive(state: LockoutState, nowMs: Long): Boolean {
        return state.lockoutUntilMs > nowMs
    }

    fun remainingMs(state: LockoutState, nowMs: Long): Long {
        return (state.lockoutUntilMs - nowMs).coerceAtLeast(0L)
    }

    fun durationMinutesForLevel(level: Int): Int {
        val clamped = level.coerceIn(0, LOCKOUT_LADDER_MINUTES.lastIndex)
        return LOCKOUT_LADDER_MINUTES[clamped]
    }

    fun recordContentBlockStrike(state: LockoutState, nowMs: Long): StrikeOutcome {
        // Already locked out: don't stack extra strikes while lockout screen is active
        if (isLockoutActive(state, nowMs)) {
            return StrikeOutcome(
                newState = state,
                triggeredLockout = false,
                durationMinutes = durationMinutesForLevel(state.escalationLevel),
                escalationLevel = state.escalationLevel
            )
        }

        // Keep only strikes inside the last 10-minute sliding window
        val windowStart = nowMs - STRIKE_WINDOW_MS
        val activeStrikes = state.recentStrikeTimestamps
            .filter { it in windowStart..nowMs }
            .toMutableList()

        // Deduplicate rapid double-fires from the same blocked page (< 2s)
        val lastStrike = activeStrikes.lastOrNull()
        if (lastStrike != null && (nowMs - lastStrike) < MIN_STRIKE_SPACING_MS) {
            return StrikeOutcome(
                newState = state.copy(recentStrikeTimestamps = activeStrikes),
                triggeredLockout = false
            )
        }

        activeStrikes.add(nowMs)

        if (activeStrikes.size < STRIKES_TO_TRIGGER) {
            return StrikeOutcome(
                newState = state.copy(recentStrikeTimestamps = activeStrikes),
                triggeredLockout = false
            )
        }

        // 3rd strike within 10 minutes -> trigger progressive lockout!
        val withinSameHour = state.lastLockoutEndMs > 0L &&
            (nowMs - state.lastLockoutEndMs) in 0L..ESCALATION_WINDOW_MS

        val nextLevel = if (withinSameHour) {
            (state.escalationLevel + 1).coerceAtMost(LOCKOUT_LADDER_MINUTES.lastIndex)
        } else {
            0
        }

        val minutes = durationMinutesForLevel(nextLevel)
        val durationMs = minutes * 60L * 1000L
        val untilMs = nowMs + durationMs

        val updatedState = LockoutState(
            lockoutUntilMs = untilMs,
            lastLockoutEndMs = untilMs,
            escalationLevel = nextLevel,
            recentStrikeTimestamps = emptyList()
        )
        return StrikeOutcome(
            newState = updatedState,
            triggeredLockout = true,
            durationMinutes = minutes,
            escalationLevel = nextLevel
        )
    }
}
