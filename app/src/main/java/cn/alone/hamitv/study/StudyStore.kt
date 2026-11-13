package cn.alone.hamitv.study

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Storage for the quiz bank and rules, and for the time account. The bank is a
 * single JSON file so it can be exported and hand-edited; the account is a few
 * primitives in SharedPreferences because it is written every 30 seconds.
 */
class StudyStore(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val file = File(context.filesDir, "study.json")
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(): StudyFile {
        val fromDisk = runCatching {
            json.decodeFromString(StudyFile.serializer(), file.readText())
        }.getOrNull()
        if (fromDisk != null) return fromDisk
        // First start on a fresh install: seed from the packaged bank so the
        // console is not empty and a group can be dealt immediately.
        val seeded = runCatching {
            val text = context.assets.open("study/seed.json").bufferedReader().use { it.readText() }
            json.decodeFromString(StudyFile.serializer(), text)
        }.getOrNull()
        if (seeded != null) {
            save(seeded)
            return seeded
        }
        return StudyFile()
    }

    @Synchronized
    fun save(data: StudyFile) {
        val clean = data.copy(rules = data.rules.sanitized(), bank = data.bank.filter { it.isValid() })
        val temp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            temp.writeText(json.encodeToString(StudyFile.serializer(), clean))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "cannot write ${file.name}", it) }
    }

    fun loadState(): StudyState = StudyState(
        balanceSec = prefs.getLong(KEY_BALANCE, 0),
        day = prefs.getInt(KEY_DAY, 0),
        usedTodaySec = prefs.getLong(KEY_USED, 0),
        earnedTodaySec = prefs.getLong(KEY_EARNED, 0),
        paused = prefs.getBoolean(KEY_PAUSED, false),
        lastTickMs = prefs.getLong(KEY_LAST_TICK, 0),
    )

    fun saveState(state: StudyState) {
        prefs.edit()
            .putLong(KEY_BALANCE, state.balanceSec)
            .putInt(KEY_DAY, state.day)
            .putLong(KEY_USED, state.usedTodaySec)
            .putLong(KEY_EARNED, state.earnedTodaySec)
            .putBoolean(KEY_PAUSED, state.paused)
            .putLong(KEY_LAST_TICK, state.lastTickMs)
            .apply()
    }

    private companion object {
        const val TAG = "StudyStore"
        const val PREFS = "study_state"
        const val KEY_BALANCE = "balance_sec"
        const val KEY_DAY = "day"
        const val KEY_USED = "used_sec"
        const val KEY_EARNED = "earned_sec"
        const val KEY_PAUSED = "paused"
        const val KEY_LAST_TICK = "last_tick_ms"
    }
}
