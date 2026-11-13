package cn.alone.hamitv.study

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudySpeechTest {

    @Test
    fun readsArithmeticInsteadOfSymbols() {
        val question = Question(id = "q", q = "56 - 28 = ?", options = listOf("18", "28", "32", "38"), answer = 1)
        val text = StudySpeech.text(question)
        assertTrue(text.startsWith("56 减 28 等于"))
        assertTrue(text.contains("A，18"))
        assertTrue(text.contains("D，38"))
    }

    @Test
    fun doesNotAnnounceThePosition() {
        val question = Question(id = "q", q = "3 + 5 = ?", options = listOf("7", "8"), answer = 1)
        val text = StudySpeech.text(question)
        assertFalse(text.contains("第 1 题"))
        assertFalse(text.contains("共 5 题"))
    }

    @Test
    fun leavesPlainTextUntouched() {
        val question = Question(
            id = "q",
            q = "「床前明月光」的下一句是？",
            options = listOf("疑是地上霜", "低头思故乡"),
            answer = 0,
        )
        assertTrue(StudySpeech.text(question).contains("「床前明月光」的下一句是？"))
    }

    @Test
    fun aSlashBetweenWordsIsNotReadAsDivision() {
        val question = Question(id = "q", q = "「图书馆」用英语怎么说？", options = listOf("library", "H2O/H/O"), answer = 0)
        val text = StudySpeech.text(question)
        assertTrue(text.contains("H2O/H/O"))
        assertFalse(text.contains("H2O 除以 H"))
    }
}
