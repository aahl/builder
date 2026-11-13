package cn.alone.hamitv.study

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackHatchTest {

    @Test
    fun fiveDeliberatePressesOpenIt() {
        val hatch = BackHatch()
        var now = 1_000L
        repeat(4) {
            assertFalse(hatch.onBack(now, 0))
            now += 400
        }
        assertTrue(hatch.onBack(now, 0))
    }

    @Test
    fun keyRepeatsNeverCount() {
        val hatch = BackHatch()
        var now = 1_000L
        repeat(20) {
            assertFalse("repeat $it", hatch.onBack(now, it + 1))
            now += 50
        }
    }

    @Test
    fun aFreshPressAfterALongPauseStartsOver() {
        val hatch = BackHatch()
        assertFalse(hatch.onBack(1_000, 0))
        assertFalse(hatch.onBack(1_400, 0))
        // Ten seconds later this is a new attempt, not the third press.
        assertFalse(hatch.onBack(12_000, 0))
        assertFalse(hatch.onBack(12_400, 0))
        assertFalse(hatch.onBack(12_800, 0))
        assertFalse(hatch.onBack(13_200, 0))
        assertTrue(hatch.onBack(13_600, 0))
    }

    @Test
    fun machineGunDuplicatesDoNotCount() {
        val hatch = BackHatch()
        assertFalse(hatch.onBack(1_000, 0))
        repeat(10) { assertFalse(hatch.onBack(1_050, 0)) }
    }
}
