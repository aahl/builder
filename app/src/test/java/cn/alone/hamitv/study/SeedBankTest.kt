package cn.alone.hamitv.study

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The packaged bank is the first thing a fresh install deals, so keep it at the
 * kindergarten-senior level: only English words and addition/subtraction within
 * 20. Regenerating the seed without re-running this is how that drifts.
 */
class SeedBankTest {

    private fun bank(): List<Question> {
        val file = listOf(
            File("src/main/assets/study/seed.json"),
            File("app/src/main/assets/study/seed.json"),
        ).firstOrNull { it.exists() }
        assumeTrue("seed.json not found from ${File(".").absolutePath}", file != null)
        val file_ = Json { ignoreUnknownKeys = true }
            .decodeFromString(StudyFile.serializer(), file!!.readText())
        return file_.bank
    }

    @Test
    fun everyQuestionIsEitherEnglishOrMathWithinTwenty() {
        val arithmetic = Regex("""^\s*(\d+)\s*([+-])\s*(\d+)\s*=\s*\?$""")
        for (question in bank()) {
            assertTrue("${question.id} is not answerable", question.isValid())
            val match = arithmetic.find(question.q)
            if (match != null) {
                val left = match.groupValues[1].toInt()
                val right = match.groupValues[3].toInt()
                val result = if (match.groupValues[2] == "+") left + right else left - right
                assertTrue("${question.id} result $result is outside 0..20", result in 0..20)
                assertTrue("${question.id} operands are not within 20", left <= 20 && right <= 20)
                assertEquals("${question.id} answer", result.toString(), question.options[question.answer])
            } else {
                assertTrue("${question.id} is not an English question", question.q.contains("英文"))
                assertTrue(
                    "${question.id} options are not English",
                    question.options.all { it.matches(Regex("[a-z ]+")) },
                )
            }
        }
    }

    @Test
    fun noMultiplicationOrDivisionLeft() {
        for (question in bank()) {
            assertFalse("${question.id} still has ×", question.q.contains("×"))
            assertFalse("${question.id} still has ÷", question.q.contains("÷"))
            assertFalse("${question.id} still has *", question.q.contains("*"))
        }
    }

    @Test
    fun optionsAreDistinct() {
        for (question in bank()) {
            assertEquals("${question.id} repeats an option", question.options.size, question.options.toSet().size)
        }
    }

    @Test
    fun thereIsEnoughVarietyForAGroup() {
        val questions = bank()
        assertTrue("need at least 20 questions, found ${questions.size}", questions.size >= 20)
        assertEquals(questions.size, questions.map { it.id }.toSet().size)
    }
}
