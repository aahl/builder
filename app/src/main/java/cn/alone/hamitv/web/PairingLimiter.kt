package cn.alone.hamitv.web

/**
 * Throttles pairing attempts. A 4 digit code is only 10k combinations, so the
 * lockout is what keeps it from being trivially brute forced on a LAN.
 */
class PairingLimiter {

    private class State(
        var fails: Int = 0,
        var lockedUntil: Long = 0,
        var lockMs: Long = 0,
    )

    private val attempts = HashMap<String, State>()

    @Synchronized
    fun lockedFor(host: String): Long {
        val state = attempts[host] ?: return 0
        return (state.lockedUntil - System.currentTimeMillis()).coerceAtLeast(0)
    }

    @Synchronized
    fun recordFailure(host: String) {
        val state = attempts.getOrPut(host) { State() }
        state.fails++
        if (state.fails >= FAILS_PER_LOCK) {
            state.fails = 0
            state.lockMs = if (state.lockMs == 0L) FIRST_LOCK_MS else (state.lockMs * 2).coerceAtMost(MAX_LOCK_MS)
            state.lockedUntil = System.currentTimeMillis() + state.lockMs
        }
    }

    @Synchronized
    fun recordSuccess(host: String) {
        attempts.remove(host)
    }

    companion object {
        private const val FAILS_PER_LOCK = 5
        private const val FIRST_LOCK_MS = 60_000L
        private const val MAX_LOCK_MS = 60L * 60 * 1000
    }
}
