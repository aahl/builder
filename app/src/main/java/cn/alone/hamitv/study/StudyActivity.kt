package cn.alone.hamitv.study

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import cn.alone.hamitv.R

/**
 * The quiz the child sees when the balance runs out. Full screen in its own
 * task so the video behind is covered rather than paused mid-frame; the remote's
 * D-pad moves focus between option rows and OK picks one.
 *
 * Five BACK presses in a row is a parent escape hatch for the early builds —
 * it does not grant any time, and it is the first thing to remove once the
 * console has a working "unlock" button on the phone.
 */
class StudyActivity : Activity() {

    private lateinit var study: StudyController
    private var group: List<Question> = emptyList()
    private var index = 0
    private var correct = 0
    private var answered = false
    private var backPresses = 0
    private var lastBackAt = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.study_activity)
        study = StudyRuntime.get(this)
        if (study.bankSize == 0) {
            findViewById<TextView>(R.id.studyQuestion).text = getString(R.string.study_empty_bank)
            findViewById<TextView>(R.id.studyFeedback).text = getString(R.string.study_empty_bank_hint)
            return
        }
        startGroup()
    }

    private fun startGroup() {
        group = study.pickGroup()
        index = 0
        correct = 0
        render()
    }

    private fun render() {
        answered = false
        val question = group[index]
        findViewById<TextView>(R.id.studyProgress).text =
            getString(R.string.study_progress, index + 1, group.size)
        findViewById<TextView>(R.id.studyMeta).text =
            getString(R.string.study_meta, study.balanceMinutes())
        findViewById<TextView>(R.id.studyQuestion).text = question.q
        findViewById<TextView>(R.id.studyFeedback).text = ""

        val rows = findViewById<LinearLayout>(R.id.studyOptions)
        rows.removeAllViews()
        question.options.forEachIndexed { choice, text ->
            val row = layoutInflater.inflate(R.layout.study_option_row, rows, false) as TextView
            row.text = text
            row.setOnClickListener { answer(choice) }
            rows.addView(row)
        }
        rows.getChildAt(0)?.requestFocus()
    }

    private fun answer(choice: Int) {
        if (answered) return
        answered = true
        val question = group[index]
        val right = choice == question.answer
        if (right) correct++

        val feedback = findViewById<TextView>(R.id.studyFeedback)
        feedback.text = if (right) getString(R.string.study_correct)
        else getString(R.string.study_wrong, question.options[question.answer])
        feedback.setTextColor(getColor(if (right) R.color.study_ok else R.color.study_bad))

        val rows = findViewById<LinearLayout>(R.id.studyOptions)
        for (i in 0 until rows.childCount) rows.getChildAt(i).isEnabled = false
        rows.postDelayed({ advance() }, 1_000)
    }

    private fun advance() {
        index++
        if (index < group.size) render() else finishGroup()
    }

    private fun finishGroup() {
        val passed = study.recordGroup(correct, group.size)
        findViewById<TextView>(R.id.studyQuestion).text =
            getString(if (passed) R.string.study_passed_title else R.string.study_failed_title)
        val feedback = findViewById<TextView>(R.id.studyFeedback)
        feedback.text = if (passed) getString(R.string.study_passed, study.rules.unlockMin)
        else getString(R.string.study_failed, correct, group.size)
        feedback.setTextColor(getColor(if (passed) R.color.study_ok else R.color.study_bad))

        val rows = findViewById<LinearLayout>(R.id.studyOptions)
        rows.removeAllViews()
        if (passed) {
            rows.postDelayed({ finish() }, 1_600)
            return
        }
        val retry = layoutInflater.inflate(R.layout.study_option_row, rows, false) as TextView
        retry.text = getString(R.string.study_retry)
        retry.setOnClickListener { startGroup() }
        rows.addView(retry)
        retry.requestFocus()
    }

    /** Swallow BACK so the page cannot be dismissed with one press. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        val now = SystemClock.elapsedRealtime()
        backPresses = if (now - lastBackAt < 1_500) backPresses + 1 else 1
        lastBackAt = now
        if (backPresses >= 5) finish()
        return true
    }

    companion object {
        const val EXTRA_REASON = "reason"
    }
}
