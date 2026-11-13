package cn.alone.hamitv.study

import android.content.Context

/**
 * One controller per process. The agent service creates it first in normal
 * operation; the quiz page and the watcher fall back to this when they are
 * started before it (for example right after an update).
 */
object StudyRuntime {

    @Volatile
    private var controller: StudyController? = null

    fun get(context: Context): StudyController = controller ?: synchronized(this) {
        controller ?: StudyController(context.applicationContext).also { controller = it }
    }
}
