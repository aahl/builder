package cn.alone.hamitv.config

import android.content.Context
import java.io.File
import java.security.SecureRandom
import kotlinx.serialization.json.Json

/**
 * All persisted agent settings. Small enough that SharedPreferences is enough;
 * the command table is a separate JSON file so it can be edited and exported.
 */
class ConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("hamitv", Context.MODE_PRIVATE)
    private val commandsFile = File(context.filesDir, "commands.json")
    private val random = SecureRandom()

    var deviceName: String
        get() = prefs.getString(KEY_NAME, "MiTV") ?: "MiTV"
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    var webPort: Int
        get() = prefs.getInt(KEY_WEB_PORT, DEFAULT_WEB_PORT)
        set(value) = prefs.edit().putInt(KEY_WEB_PORT, value).apply()

    var espPort: Int
        get() = prefs.getInt(KEY_ESP_PORT, DEFAULT_ESP_PORT)
        set(value) = prefs.edit().putInt(KEY_ESP_PORT, value).apply()

    /** 4 digit pairing code shown on the TV. */
    val pairingCode: String
        get() = prefs.getString(KEY_CODE, null) ?: rotatePairingCode()

    fun rotatePairingCode(): String {
        val code = "%04d".format(random.nextInt(10_000))
        prefs.edit().putString(KEY_CODE, code).apply()
        return code
    }

    fun issueSession(): String {
        val token = ByteArray(32).also(random::nextBytes).toHex()
        prefs.edit()
            .putString(KEY_SESSION, token)
            .putLong(KEY_SESSION_AT, System.currentTimeMillis())
            .apply()
        return token
    }

    fun isValidSession(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val stored = prefs.getString(KEY_SESSION, null) ?: return false
        if (stored != token) return false
        val issuedAt = prefs.getLong(KEY_SESSION_AT, 0L)
        return System.currentTimeMillis() - issuedAt < SESSION_TTL_MS
    }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION).remove(KEY_SESSION_AT).apply()
    }

    fun readCommands(): String =
        if (commandsFile.exists()) commandsFile.readText() else DEFAULT_COMMANDS

    /** Returns null on success, or a human readable error when the JSON is invalid. */
    fun writeCommands(raw: String): String? {
        return try {
            Json.parseToJsonElement(raw)
            commandsFile.writeText(raw)
            null
        } catch (e: Exception) {
            e.message ?: "invalid json"
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_WEB_PORT = 8122
        const val DEFAULT_ESP_PORT = 6053
        private const val SESSION_TTL_MS = 7L * 24 * 60 * 60 * 1000

        private const val KEY_NAME = "device_name"
        private const val KEY_WEB_PORT = "web_port"
        private const val KEY_ESP_PORT = "esp_port"
        private const val KEY_CODE = "pairing_code"
        private const val KEY_SESSION = "session"
        private const val KEY_SESSION_AT = "session_at"

        private val DEFAULT_COMMANDS = """
            [
              {
                "id": "show_panel",
                "name": "控制中心",
                "type": "service",
                "action": "com.xiaomi.mitv.smartpanel.action.SHOW_PANEL",
                "component": "com.xiaomi.mitv.smartpanel/.SmartPanelService"
              },
              {
                "id": "exit_pip",
                "name": "关闭画中画",
                "type": "broadcast",
                "action": "com.xiaomi.mitv.intent.EXIT_CURRENT_PIP",
                "extras": { "from": "com.xiaomi.mitv.smartshare" }
              },
              {
                "id": "camera_fullscreen",
                "name": "摄像头全屏",
                "type": "activity",
                "action": "com.yicamera.ACTION_CAMERA_PLAYER",
                "component": "com.xiaomi.smarthome.tv/com.xiaomi.cameragroup.player.YiCamerasPlayerActivity",
                "extras": { "did": "", "model": "" }
              }
            ]
        """.trimIndent()
    }
}
