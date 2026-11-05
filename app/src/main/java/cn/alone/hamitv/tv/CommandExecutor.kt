package cn.alone.hamitv.tv

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * Executes a [CommandSpec] with the permissions of a normal app: Intents for
 * activities, services and broadcasts, plus the Xiaomi camera binder for
 * picture-in-picture. Anything that needs a shell (force-stop, key injection)
 * is deliberately out of scope.
 */
class CommandExecutor(private val context: Context) {

    private val camera by lazy { CameraBinder(context) }

    fun run(spec: CommandSpec): Result<String> = runCatching {
        when (spec.type) {
            "activity" -> {
                context.startActivity(
                    intent(spec).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "started ${spec.action}"
            }
            "service" -> {
                context.startService(intent(spec))
                "service ${spec.action}"
            }
            "broadcast" -> {
                context.sendBroadcast(intent(spec))
                "broadcast ${spec.action}"
            }
            "camera_pip" -> {
                val did = spec.extras["did"].orEmpty()
                camera.startFloatWindow(did)
                "pip $did"
            }
            "camera_close" -> {
                camera.closeFloatWindow()
                "pip closed"
            }
            else -> throw IllegalArgumentException("unknown type ${spec.type}")
        }
    }

    private fun intent(spec: CommandSpec): Intent {
        val intent = Intent(spec.action)
        spec.component?.let { intent.component = component(it) }
        spec.extras.forEach { (key, value) -> intent.putExtra(key, value) }
        return intent
    }

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
