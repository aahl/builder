package cn.alone.hamitv.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import cn.alone.hamitv.study.StudyRuntime
import cn.alone.hamitv.study.StudyWatcherService

/**
 * Executes a [CommandSpec] with the permissions of a normal app: Intents for
 * activities, services and broadcasts, plus the Xiaomi camera binder for
 * picture-in-picture. Anything that needs a shell (force-stop, key injection)
 * is deliberately out of scope.
 */
class CommandExecutor(private val context: Context) {

    private val camera by lazy { CameraBinder(context) }

    /** Cameras the Xiaomi service knows about; empty when it is not running. */
    fun cameras(): List<CameraBinder.Camera> = camera.cameras()

    fun run(spec: CommandSpec): Result<String> = runCatching {
        when (spec.type) {
            "activity" -> {
                context.startActivity(
                    intent(spec).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "started ${spec.target()}"
            }
            "service" -> {
                context.startService(intent(spec))
                "service ${spec.target()}"
            }
            "broadcast" -> {
                context.sendBroadcast(intent(spec))
                "broadcast ${spec.target()}"
            }
            "camera_pip" -> {
                val did = spec.extras["did"]?.toString().orEmpty()
                camera.startFloatWindow(did)
                "pip $did"
            }
            "camera_close" -> {
                camera.closeFloatWindow()
                "pip closed"
            }
            // Study gate actions, so both can be dragged onto the TV remote or
            // called from HA without a separate entity type.
            "study_lock" -> {
                if (!StudyWatcherService.launch(context, "command")) {
                    throw IllegalStateException("学习管控的无障碍服务未开启")
                }
                "quiz opened"
            }
            "study_unlock" -> {
                val study = StudyRuntime.get(context)
                val minutes = (spec.extras["minutes"] as? Number)?.toInt() ?: study.rules.unlockMin
                study.unlock(minutes)
                "unlocked $minutes min"
            }
            "select" -> throw IllegalArgumentException("select entries run one of their options")
            else -> throw IllegalArgumentException("unknown type ${spec.type}")
        }
    }

    private fun intent(spec: CommandSpec): Intent {
        // A bare component is a legal target (the Xiaomi player activities are
        // launched that way), so the action stays unset when the table omits it.
        val intent = Intent()
        if (spec.action.isNotEmpty()) intent.action = spec.action
        spec.component?.let { intent.component = component(it) }
        spec.categories.forEach { intent.addCategory(it) }
        spec.extras.forEach { (key, value) -> intent.putTypedExtra(key, value) }
        return intent
    }

    /** Keeps the JSON type, so `23` stays an int and `"23"` stays a string. */
    private fun Intent.putTypedExtra(key: String, value: Any) {
        when (value) {
            is Int -> putExtra(key, value)
            is Boolean -> putExtra(key, value)
            is Double -> putExtra(key, value)
            is Float -> putExtra(key, value)
            is Long -> putExtra(key, value)
            else -> putExtra(key, value.toString())
        }
    }

    private fun CommandSpec.target(): String = action.ifEmpty { component.orEmpty() }

    /** Accepts "pkg/.Cls", "pkg/Cls" and a bare "pkg.Cls". */
    private fun component(raw: String): ComponentName {
        val slash = raw.indexOf('/')
        if (slash < 0) {
            val dot = raw.lastIndexOf('.')
            require(dot > 0) { "bad component $raw" }
            return ComponentName(raw.substring(0, dot), raw)
        }
        val pkg = raw.substring(0, slash)
        var cls = raw.substring(slash + 1)
        if (cls.startsWith(".")) cls = pkg + cls
        return ComponentName(pkg, cls)
    }
}
