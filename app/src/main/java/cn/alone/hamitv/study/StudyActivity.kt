package cn.alone.hamitv.study

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
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
    private var sessionGained = 0
    private var advanceTask: Runnable? = null
    private var pinVisible = false
    private var pinEntry = ""
    private var pinAttempts = 0
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
        cancelAdvance()
        group = study.pickGroup()
        index = 0
        correct = 0
        sessionGained = 0
        render()
    }

    private fun render() {
        answered = false
        val question = group[index]
        findViewById<TextView>(R.id.studyProgress).text =
            getString(R.string.study_progress, index + 1, group.size)
        findViewById<TextView>(R.id.studyMeta).text =
            getString(R.string.study_meta_per, study.rules.perCorrectMin)
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
        val gained = if (right) study.creditAnswer(true) else 0
        if (right) {
            correct++
            sessionGained += gained
        }

        val feedback = findViewById<TextView>(R.id.studyFeedback)
        feedback.text = when {
            right && gained > 0 -> getString(R.string.study_answer_right_gain, gained)
            right -> getString(R.string.study_answer_right_capped)
            else -> getString(R.string.study_wrong, question.options[question.answer])
        }
        feedback.setTextColor(getColor(if (right) R.color.study_ok else R.color.study_bad))

        val rows = findViewById<LinearLayout>(R.id.studyOptions)
        for (i in 0 until rows.childCount) rows.getChildAt(i).isEnabled = false

        if (right) {
            scheduleAdvance(RIGHT_DELAY_MS)
        } else if (study.voice.enabled) {
            // Read the question and the right answer, then move on when it ends.
            // The long timer is only a fallback in case playback never reports back.
            scheduleAdvance(WRONG_MAX_WAIT_MS)
            speaker.speak(StudySpeech.wrongAnswer(question), study.voice) {
                // The fallback may already have moved on while this was playing.
                if (answered) scheduleAdvance(WRONG_AFTER_SPEECH_MS)
            }
        } else {
            scheduleAdvance(WRONG_NO_VOICE_MS)
        }
    }

    private fun scheduleAdvance(delayMs: Long) {
        cancelAdvance()
        val view = findViewById<LinearLayout>(R.id.studyOptions)
        val task = Runnable {
            if (!isFinishing && !finished) advance()
        }
        advanceTask = task
        view.postDelayed(task, delayMs)
    }

    private fun cancelAdvance() {
        advanceTask?.let { findViewById<LinearLayout>(R.id.studyOptions).removeCallbacks(it) }
        advanceTask = null
    }

    private fun advance() {
        index++
        if (index < group.size) render() else finishGroup()
    }

    /** The time was credited per answer; this is just the summary, then the page closes. */
    private fun finishGroup() {
        speaker.stop()
        study.noteGroup(correct, group.size, sessionGained)
        findViewById<TextView>(R.id.studyQuestion).text =
            getString(R.string.study_summary, correct, group.size, sessionGained)
        val feedback = findViewById<TextView>(R.id.studyFeedback)
        feedback.text = ""
        findViewById<LinearLayout>(R.id.studyOptions).removeAllViews()

        finished = true
        study.onQuizFinished()
        findViewById<TextView>(R.id.studyHint).postDelayed({
            if (!isFinishing) finish()
        }, 1_600)
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
            promptExit()
        }
        return true
    }

    /** Newer back gestures never reach onKeyDown, so they get the same treatment. */
    override fun onBackPressed() {
        if (hatch.onBack(SystemClock.elapsedRealtime(), 0)) {
            Log.i(TAG, "parent hatch used from onBackPressed")
            promptExit()
        }
    }

    /** While the gate is up every key belongs to it, including the arrows. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!pinVisible) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) handlePinKey(event.keyCode)
        return true
    }

    /**
     * Five BACK presses do not close the page any more; they open the parent gate.
     * The password is a sequence of arrows, stored as 1=up 2=down 3=left 4=right.
     */
    private fun promptExit() {
        pinVisible = true
        pinEntry = ""
        findViewById<TextView>(R.id.studyPinDigits).text = ""
        findViewById<TextView>(R.id.studyPinError).text = ""
        findViewById<View>(R.id.studyPinOverlay).visibility = View.VISIBLE
        cancelAdvance()
        speaker.stop()
    }

    private fun hidePin() {
        pinVisible = false
        findViewById<View>(R.id.studyPinOverlay).visibility = View.GONE
        pinEntry = ""
    }

    private fun handlePinKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> appendPin('1')
            KeyEvent.KEYCODE_DPAD_DOWN -> appendPin('2')
            KeyEvent.KEYCODE_DPAD_LEFT -> appendPin('3')
            KeyEvent.KEYCODE_DPAD_RIGHT -> appendPin('4')
            KeyEvent.KEYCODE_BACK -> {
                hidePin()
                findViewById<LinearLayout>(R.id.studyOptions).getChildAt(0)?.requestFocus()
            }
        }
    }

    private fun appendPin(digit: Char) {
        val pin = study.rules.exitPin
        if (pinEntry.length >= pin.length) return
        pinEntry += digit
        findViewById<TextView>(R.id.studyPinDigits).text = pinEntry.map {
            when (it) {
                '1' -> "↑"
                '2' -> "↓"
                '3' -> "←"
                else -> "→"
            }
        }.joinToString(" ")

        if (pinEntry.length < pin.length) return
        if (pinEntry == pin) {
            Log.i(TAG, "parent gate passed")
            hidePin()
            abandon()
            return
        }

        pinAttempts++
        pinEntry = ""
        findViewById<TextView>(R.id.studyPinDigits).text = ""
        if (pinAttempts >= PIN_ATTEMPTS) {
            Log.i(TAG, "parent gate locked after $pinAttempts tries")
            findViewById<TextView>(R.id.studyPinError).text = getString(R.string.study_pin_locked)
            pinAttempts = 0
            hidePin()
        } else {
            findViewById<TextView>(R.id.studyPinError).text = getString(R.string.study_pin_wrong)
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
        // Came back in the middle of the correction: finish moving on.
        if (answered && !finished) scheduleAdvance(800)
        Log.i(TAG, "quiz resumed")
    }

    override fun onPause() {
        visible = false
        cancelAdvance()
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

        private const val RIGHT_DELAY_MS = 1_000L
        private const val WRONG_AFTER_SPEECH_MS = 350L
        private const val WRONG_MAX_WAIT_MS = 15_000L
        private const val WRONG_NO_VOICE_MS = 2_500L
        private const val PIN_ATTEMPTS = 3

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
