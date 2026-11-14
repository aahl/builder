package cn.alone.hamitv.study

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesTheShapeTheSeedFileUses() {
        val file = json.decodeFromString(
            StudyFile.serializer(),
            """{"bank":[{"id":"q1","q":"1+1=?","options":["1","2"],"answer":1}]}""",
        )
        assertEquals(1, file.bank.size)
        assertEquals("1+1=?", file.bank.single().q)
        assertFalse(file.rules.enabled)
    }

    @Test
    fun ignoresFieldsWrittenByNewerBuilds() {
        val file = json.decodeFromString(
            StudyFile.serializer(),
            """{"rules":{"enabled":true,"futureKnob":7},"bank":[],"extra":{}}""",
        )
        assertTrue(file.rules.enabled)
    }

    @Test
    fun clampsRulesIntoUsableRanges() {
        val rules = StudyRules(
            dailyCapMin = 0,
            perCorrectMin = 999,
            groupSize = 0,
            exempt = listOf(" a ", "a", "", " b "),
        ).sanitized()
        assertEquals(1, rules.dailyCapMin)
        assertEquals(60, rules.perCorrectMin)
        assertEquals(1, rules.groupSize)
        assertEquals(listOf("a", "b"), rules.exempt)
    }

    @Test
    fun rejectsQuestionsThatCannotBeAnswered() {
        assertTrue(Question(id = "q", q = "q", options = listOf("a", "b"), answer = 1).isValid())
        assertFalse(Question(id = "", q = "q", options = listOf("a", "b"), answer = 0).isValid())
        assertFalse(Question(id = "q", q = "q", options = listOf("a"), answer = 0).isValid())
        assertFalse(Question(id = "q", q = "q", options = listOf("a", "b"), answer = 5).isValid())
    }

    @Test
    fun stateRoundTrips() {
        val state = StudyState(balanceSec = 90, day = 20261113, usedTodaySec = 30, earnedTodaySec = 120, paused = true, lastTickMs = 1_700_000_000_000)
        val text = json.encodeToString(StudyState.serializer(), state)
        assertEquals(state, json.decodeFromString(StudyState.serializer(), text))
    }

    @Test
    fun voiceFallsBackWhenTheNameOrTheRateIsWrong() {
        val voice = StudyVoice(voice = "xx-XX-NobodyNeural", rate = "+5000%", pitch = "loud").sanitized()
        assertEquals(StudyVoices.DEFAULT, voice.voice)
        assertEquals("+0%", voice.rate)
        assertEquals("+0Hz", voice.pitch)
    }

    @Test
    fun voiceKeepsAKnownNameAndRate() {
        val voice = StudyVoice(enabled = true, voice = "zh-CN-YunxiNeural", rate = "-15%").sanitized()
        assertEquals("zh-CN-YunxiNeural", voice.voice)
        assertEquals("-15%", voice.rate)
    }
}
