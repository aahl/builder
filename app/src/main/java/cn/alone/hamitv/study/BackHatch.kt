package cn.alone.hamitv.study

/**
 * The parent escape hatch: five deliberate BACK presses.
 *
 * Key repeats do not count. The first build counted every `onKeyDown`, so a
 * child holding BACK closed the quiz by accident — that is the "it exits before
 * I finish" report. Five presses also have to be spread over a few hundred
 * milliseconds each, which a leaning thumb cannot produce.
 */
class BackHatch(
    private val required: Int = 5,
    private val windowMs: Long = 4_000,
    private val minGapMs: Long = 200,
) {

    private var count = 0
    private var lastAt = 0L

    /** Returns true once the hatch should open. */
    fun onBack(now: Long, repeatCount: Int): Boolean {
        if (repeatCount > 0) return false
        if (lastAt > 0 && now - lastAt < minGapMs) return false
        count = if (lastAt > 0 && now - lastAt <= windowMs) count + 1 else 1
        lastAt = now
        if (count < required) return false
        count = 0
        lastAt = 0
        return true
    }
}
