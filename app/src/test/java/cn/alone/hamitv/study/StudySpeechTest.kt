package cn.alone.hamitv.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StudySpeechTest {

    @Test
    fun readsArithmeticInsteadOfSymbols() {
        val question = Question(id = "q", q = "56 - 28 = ?", options = listOf("18", "28", "32", "38"), answer = 1)
        val text = StudySpeech.text(question, 0, 5)
        assertTrue(text.startsWith("第 1 题，共 5 题。"))
        assertTrue(text.contains("56 减 28 等于"))
        assertTrue(text.contains("A，18"))
        assertTrue(text.contains("D，38"))
    }

    @Test
    fun leavesPlainTextUntouched() {
        val question = Question(
            id = "q",
            q = "「床前明月光」的下一句是？",
            options = listOf("疑是地上霜", "低头思故乡"),
            answer = 0,
        )
        assertTrue(StudySpeech.text(question, 1, 2).contains("「床前明月光」的下一句是？"))
    }

    @Test
    fun aSlashBetweenWordsIsNotReadAsDivision() {
        val question = Question(id = "q", q = "「图书馆」用英语怎么说？", options = listOf("library", "H2O/H/O"), answer = 0)
        val text = StudySpeech.text(question, 0, 1)
        assertTrue(text.contains("H2O/H/O"))
        assertTrue(!text.contains("H2O 除以 H"))
    }
}
