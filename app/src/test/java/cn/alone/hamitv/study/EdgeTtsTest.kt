package cn.alone.hamitv.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeTtsTest {

    // 2023-11-14T22:13:20Z; sits 1000 s before a five-minute bucket boundary.
    private val base = 1_700_000_000_000L

    @Test
    fun theTokenIsStableInsideOneFiveMinuteBucket() {
        assertEquals(EdgeTts.secMsGec(base), EdgeTts.secMsGec(base + 60_000))
    }

    @Test
    fun theTokenChangesAcrossBuckets() {
        assertNotEquals(EdgeTts.secMsGec(base), EdgeTts.secMsGec(base + 6 * 60_000))
    }

    @Test
    fun theTokenIsUpperHexSha256() {
        assertTrue(EdgeTts.secMsGec(base).matches(Regex("[0-9A-F]{64}")))
    }
}
