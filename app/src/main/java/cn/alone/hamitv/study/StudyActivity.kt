package cn.alone.hamitv.study

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
    private val speaker by lazy { StudySpeaker.get(this) }
    private var group: List<Question> = emptyList()
    private var index = 0
    private var correct = 0
    private var answered = false
    private var finished = false
    private val hatch = BackHatch()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.study_activity)
        study = StudyRuntime.get(this)
        current = this
        Log.i(TAG, "quiz opened")
        if (study.bankSize == 0) {
            findViewById<TextView>(R.id.studyQuestion).text = getString(R.string.study_empty_bank)
            findViewById<TextView>(R.id.studyFeedback).text = getString(R.string.study_empty_bank_hint)
            return
        }
        study.onQuizShown()
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
        findViewById<TextView>(R.id.studyMeta).text = if (study.isLocked()) {
            getString(R.string.study_meta_locked, study.rules.unlockMin)
        } else {
            getString(R.string.study_meta, study.balanceMinutes())
        }
        findViewById<TextView>(R.id.studyQuestion).text = question.q
        findViewById<TextView>(R.id.studyFeedback).text = ""

        val rows = findViewById<LinearLayout>(R.id.studyOptions)
        rows.removeAllViews()
        question.options.forEachIndexed { choice, text ->
            val row = layoutInflater.inflate(R.layout.study_option_row, rows, false) as TextView
            // The same letter the voice reads, so a child can match sound to row.
            row.text = "${('A' + choice)}、$text"
            row.setOnClickListener { answer(choice) }
            rows.addView(row)
        }
        rows.getChildAt(0)?.requestFocus()
        speaker.speak(StudySpeech.text(question), study.voice)
    }

    private fun answer(choice: Int) {
        if (answered) return
        answered = true
        speaker.stop()
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
        speaker.stop()
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
            finished = true
            study.onQuizFinished()
            rows.postDelayed({ if (!isFinishing) finish() }, 1_600)
            return
        }
        val retry = layoutInflater.inflate(R.layout.study_option_row, rows, false) as TextView
        retry.text = getString(R.string.study_retry)
        retry.setOnClickListener { startGroup() }
        rows.addView(retry)
        retry.requestFocus()
    }

    /**
     * BACK is swallowed, except for the parent hatch: five separate presses.
     * Held-down repeats never count, so leaning on the button cannot end the
     * quiz by accident.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // The remote's MENU key repeats the question, for a child who missed it.
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (group.isNotEmpty()) {
                speaker.speak(StudySpeech.text(group[index]), study.voice)
            }
            return true
        }
        if (keyCode != KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        if (hatch.onBack(SystemClock.elapsedRealtime(), event?.repeatCount ?: 0)) {
            Log.i(TAG, "parent hatch used")
            abandon()
        }
        return true
    }

    /** Newer back gestures never reach onKeyDown, so they get the same treatment. */
    override fun onBackPressed() {
        if (hatch.onBack(SystemClock.elapsedRealtime(), 0)) {
            Log.i(TAG, "parent hatch used from onBackPressed")
            abandon()
        }
    }

    private fun abandon() {
        finished = true
        study.onQuizFinished()
        speaker.stop()
        finish()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        Log.i(TAG, "quiz resumed")
    }

    override fun onPause() {
        visible = false
        speaker.stop()
        // Leaving without passing (HOME, a system dialog) puts the quiz back.
        if (!finished) study.onQuizPaused()
        Log.i(TAG, "quiz paused, finished=$finished")
        super.onPause()
    }

    override fun onDestroy() {
        Log.i(TAG, "quiz destroyed, finishing=$isFinishing finished=$finished")
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REASON = "reason"
        private const val TAG = "StudyActivity"

        @Volatile
        private var current: StudyActivity? = null

        /** Switched off from the console or HA: the page must not stay up. */
        fun closeIfOpen() {
            val activity = current ?: return
            activity.runOnUiThread {
                activity.finished = true
                activity.study.onQuizFinished()
                if (!activity.isFinishing) activity.finish()
            }
        }

        /** The gate must not charge (or re-open) time while the child is answering. */
        @Volatile
        var visible: Boolean = false
            private set
    }
}
