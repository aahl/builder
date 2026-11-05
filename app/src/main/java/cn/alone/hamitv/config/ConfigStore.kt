package cn.alone.hamitv.config

import android.content.Context
import java.io.File
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
        migrate(if (commandsFile.exists()) commandsFile.readText() else DEFAULT_COMMANDS)

    /**
     * Older tables have no select entity, so the input source would silently be
     * missing after an upgrade. Fold the default select in once, then leave the
     * table alone: from then on the file is the single source of truth.
     */
    private fun migrate(raw: String): String {
        if (prefs.getInt(KEY_COMMANDS_VERSION, 1) >= COMMANDS_VERSION) return raw
        val array = runCatching { Json.parseToJsonElement(raw) as JsonArray }.getOrNull()
        if (array != null && array.none { it.isSelect() }) {
            val merged = JsonArray(listOf(DEFAULT_SELECT) + array).toString()
            runCatching { commandsFile.writeText(merged) }
            prefs.edit().putInt(KEY_COMMANDS_VERSION, COMMANDS_VERSION).apply()
            return merged
        }
        prefs.edit().putInt(KEY_COMMANDS_VERSION, COMMANDS_VERSION).apply()
        return raw
    }

    private fun kotlinx.serialization.json.JsonElement.isSelect(): Boolean =
        ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "select"

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
        private const val KEY_COMMANDS_VERSION = "commands_version"
        private const val COMMANDS_VERSION = 2

        private val DEFAULT_SELECT = Json.parseToJsonElement(
            """
              {
                "id": "input_source",
                "name": "输入源",
                "type": "select",
                "icon": "mdi:video-input-hdmi",
                "options": [
                  {
                    "name": "主页",
                    "type": "activity",
                    "action": "android.intent.action.MAIN",
                    "categories": ["android.intent.category.HOME"]
                  },
                  {
                    "name": "HDMI 1",
                    "type": "activity",
                    "component": "com.xiaomi.mitv.tvplayer/.ExternalSourceActivity",
                    "extras": { "input": 23 }
                  },
                  {
                    "name": "HDMI 2",
                    "type": "activity",
                    "component": "com.xiaomi.mitv.tvplayer/.ExternalSourceActivity",
                    "extras": { "input": 24 }
                  },
                  {
                    "name": "HDMI 3",
                    "type": "activity",
                    "component": "com.xiaomi.mitv.tvplayer/.ExternalSourceActivity",
                    "extras": { "input": 25 }
                  },
                  {
                    "name": "数字电视",
                    "type": "activity",
                    "component": "com.xiaomi.mitv.tvplayer/.dtmb.DTMBActivity"
                  }
                ]
              }
            """.trimIndent(),
        )

        private val DEFAULT_COMMANDS = """
            [
              {
                "id": "input_source",
                "name": "输入源",
                "type": "select",
                "icon": "mdi:video-input-hdmi",
                "options": [
                  {
                    "name": "主页",
                    "type": "activity",
                    "action": "android.intent.action.MAIN",
                    "categories": ["android.intent.category.HOME"]
                  },
                  {
                    "name": "HDMI 1",
                    "type": "activity",
                    "component": "com.xiaomi.mitv.tvplayer/.ExternalSourceActivity",
                    "extras": { "input": 23 }
                  },
                  {
                    "name": "HDMI 2",
                    "type": "activity",
                    "component": "com.xiaomi.mitv.tvplayer/.ExternalSourceActivity",
                    "extras": { "input": 24 }
                  },
                  {
                    "name": "HDMI 3",
                    "type": "activity",
                    "component": "com.xiaomi.mitv.tvplayer/.ExternalSourceActivity",
                    "extras": { "input": 25 }
                  },
                  {
                    "name": "数字电视",
                    "type": "activity",
                    "component": "com.xiaomi.mitv.tvplayer/.dtmb.DTMBActivity"
                  }
                ]
              },
              {
                "id": "show_panel",
                "name": "控制中心",
                "type": "service",
                "action": "com.xiaomi.mitv.smartpanel.action.SHOW_PANEL",
                "component": "com.xiaomi.mitv.smartpanel/.SmartPanelService"
              },
              {
                "id": "camera_close",
                "name": "关闭摄像头画中画",
                "type": "camera_close",
                "action": ""
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
