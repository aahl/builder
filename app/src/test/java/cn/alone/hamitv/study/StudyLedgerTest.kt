package cn.alone.hamitv.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class StudyLedgerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val rules = StudyRules(enabled = true, dailyCapMin = 60, unlockMin = 10, groupSize = 5, passPercent = 60)

    private fun at(year: Int, month: Int, day: Int, hour: Int = 8, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun state(
        balanceSec: Long = 600,
        day: Int = StudyLedger.today(at(2026, 11, 13), zone),
        usedTodaySec: Long = 0,
        earnedTodaySec: Long = 0,
        paused: Boolean = false,
        lastTickMs: Long = at(2026, 11, 13),
    ) = StudyState(balanceSec, day, usedTodaySec, earnedTodaySec, paused, lastTickMs)

    @Test
    fun disabledAccountNeverDebits() {
        val next = StudyLedger.step(state(), at(2026, 11, 13, 8, 1), active = true, rules = rules.copy(enabled = false))
        assertEquals(600L, next.balanceSec)
        assertEquals(0L, next.usedTodaySec)
    }

    @Test
    fun pausedAccountDoesNotDebit() {
        val next = StudyLedger.step(state(paused = true), at(2026, 11, 13, 8, 1), active = true, rules = rules)
        assertEquals(600L, next.balanceSec)
        assertEquals(0L, next.usedTodaySec)
    }

    @Test
    fun inactiveForegroundDoesNotDebit() {
        val next = StudyLedger.step(state(), at(2026, 11, 13, 8, 1), active = false, rules = rules)
        assertEquals(600L, next.balanceSec)
        assertEquals(at(2026, 11, 13, 8, 1), next.lastTickMs)
    }

    @Test
    fun singleTickIsCappedAtOneMinute() {
        // The process was frozen for ten minutes; only one minute may be charged.
        val next = StudyLedger.step(state(), at(2026, 11, 13, 8, 10), active = true, rules = rules)
        assertEquals(540L, next.balanceSec)
        assertEquals(60L, next.usedTodaySec)
    }

    @Test
    fun balanceStopsAtZeroWhileUsageKeepsCounting() {
        val next = StudyLedger.step(state(balanceSec = 30), at(2026, 11, 13, 8, 1), active = true, rules = rules)
        assertEquals(0L, next.balanceSec)
        assertEquals(60L, next.usedTodaySec)
    }

    @Test
    fun subSecondTicksAccumulateInsteadOfBeingLost() {
        val base = at(2026, 11, 13)
        var current = StudyLedger.step(state(lastTickMs = base), base + 900, active = true, rules = rules)
        assertEquals(600L, current.balanceSec)
        // The 900 ms is kept, so the next tick crosses a whole second.
        current = StudyLedger.step(current, base + 1_900, active = true, rules = rules)
        assertEquals(599L, current.balanceSec)
        assertEquals(base + 1_900, current.lastTickMs)
    }

    @Test
    fun dayRolloverResetsCountersButKeepsBalance() {
        val yesterday = state(
            balanceSec = 600,
            day = 20261112,
            usedTodaySec = 3000,
            earnedTodaySec = 600,
            lastTickMs = at(2026, 11, 13, 0, 0) - 15_000,
        )
        val next = StudyLedger.step(yesterday, at(2026, 11, 13, 0, 0), active = true, rules = rules)
        assertEquals(20261113, next.day)
        assertEquals(585L, next.balanceSec)
        assertEquals(15L, next.usedTodaySec)
        assertEquals(0L, next.earnedTodaySec)
    }

    @Test
    fun clockGoingBackwardsDoesNotDebit() {
        val next = StudyLedger.step(state(), at(2026, 11, 13, 7, 0), active = true, rules = rules)
        assertEquals(600L, next.balanceSec)
        assertEquals(at(2026, 11, 13, 7, 0), next.lastTickMs)
    }

    @Test
    fun firstTickOnlyStartsTheClock() {
        val next = StudyLedger.step(state(lastTickMs = 0), at(2026, 11, 13, 9, 0), active = true, rules = rules)
        assertEquals(600L, next.balanceSec)
        assertEquals(0L, next.usedTodaySec)
        assertEquals(at(2026, 11, 13, 9, 0), next.lastTickMs)
    }

    @Test
    fun locksWhenBalanceIsGoneButNotWhilePausedOrDisabled() {
        val empty = state(balanceSec = 0)
        assertTrue(StudyLedger.isLocked(empty, rules))
        assertFalse(StudyLedger.isLocked(empty.copy(paused = true), rules))
        assertFalse(StudyLedger.isLocked(empty, rules.copy(enabled = false)))
    }

    @Test
    fun locksWhenDailyCapIsReachedEvenWithBalance() {
        val capped = state(balanceSec = 600, usedTodaySec = 3600)
        assertTrue(StudyLedger.isLocked(capped, rules))
        assertTrue(StudyLedger.isLocked(capped.copy(balanceSec = 0), rules))
    }

    @Test
    fun gateOnlyAppliesToCountableApps() {
        val empty = state(balanceSec = 0)
        assertTrue(StudyLedger.shouldGate(empty, rules, "com.example.video", setOf("com.ts.launcher")))
        assertFalse(StudyLedger.shouldGate(empty, rules, "com.ts.launcher", setOf("com.ts.launcher")))
        assertFalse(StudyLedger.shouldGate(empty, rules, null, setOf("com.ts.launcher")))
        assertFalse(StudyLedger.countable("", emptySet()))
    }

    @Test
    fun grantIsTrimmedByWhatIsLeftOfTodaysCap() {
        val next = StudyLedger.grant(state(earnedTodaySec = 3300, balanceSec = 0), 10, rules, at(2026, 11, 13, 9, 0))
        assertEquals(300L, next.balanceSec)
        assertEquals(3600L, next.earnedTodaySec)
    }

    @Test
    fun grantIsIgnoredWhenTheCapIsAlreadySpent() {
        val next = StudyLedger.grant(state(earnedTodaySec = 3600, balanceSec = 120), 10, rules, at(2026, 11, 13, 9, 0))
        assertEquals(120L, next.balanceSec)
        assertEquals(3600L, next.earnedTodaySec)
    }

    @Test
    fun grantIgnoresNonPositiveMinutes() {
        val next = StudyLedger.grant(state(balanceSec = 60), 0, rules, at(2026, 11, 13, 9, 0))
        assertEquals(60L, next.balanceSec)
    }

    @Test
    fun pickReturnsASizedGroupWithoutRepeats() {
        val bank = (1..12).map { Question(id = "q$it", q = "题 $it", options = listOf("A", "B"), answer = 0) }
        val group = StudyLedger.pick(bank, 5, Random(7))
        assertEquals(5, group.size)
        assertEquals(5, group.map { it.id }.toSet().size)
    }

    @Test
    fun pickSkipsInvalidQuestionsAndHandlesSmallBanks() {
        val bank = listOf(
            Question(id = "", q = "坏题", options = listOf("A", "B"), answer = 0),
            Question(id = "ok", q = "好题", options = listOf("A"), answer = 0),
            Question(id = "good", q = "好题", options = listOf("A", "B"), answer = 1),
        )
        val group = StudyLedger.pick(bank, 5, Random(1))
        assertEquals(listOf("good"), group.map { it.id })
    }
}
