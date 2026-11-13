package cn.alone.hamitv.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MsGecTest {

    @Test
    fun matchesTheReferenceToken() {
        // Produced by rany2/edge-tts generate_sec_ms_gec_v2 at these timestamps.
        assertEquals(
            "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            MsGec.token(1_700_000_000_000L),
        )
        assertEquals(
            "AC52A45C1905AF65-DF969F5A2B9494B99E145D5BC9F36EB3FA918BDBFAD1A6B695E61FCD15F6BDD7",
            MsGec.token(1_700_003_000_000L),
        )
    }

    @Test
    fun theTokenIsStableInsideOneFiveMinuteBucket() {
        assertEquals(MsGec.token(1_700_000_000_000L), MsGec.token(1_700_000_060_000L))
    }

    @Test
    fun theTokenChangesAcrossBuckets() {
        assertNotEquals(MsGec.token(1_700_000_000_000L), MsGec.token(1_700_003_000_000L))
    }

    @Test
    fun theTokenHasTheV2Shape() {
        assertTrue(MsGec.token(1_700_000_000_000L).matches(Regex("[0-9A-F]{16}-[0-9A-F]{64}")))
    }
}
