package cn.alone.hamitv.config

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.util.Base64
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

    /**
     * Noise encryption for the ESPHome API. The key is stored base64 encoded
     * because that is exactly what HA asks the user to paste.
     */
    var noiseEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOISE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOISE_ENABLED, value).apply()

    /** Never empty: the console can always show a key to copy. */
    val noisePsk: String
        get() = prefs.getString(KEY_NOISE_PSK, null) ?: rotateNoisePsk()

    fun rotateNoisePsk(): String {
        val key = ByteArray(32).also(random::nextBytes)
        val encoded = Base64.getEncoder().encodeToString(key)
        prefs.edit().putString(KEY_NOISE_PSK, encoded).apply()
        return encoded
    }

    /** Returns null when the stored key is not a 32-byte base64 value. */
    fun noisePskBytes(): ByteArray? = runCatching {
        Base64.getDecoder().decode(noisePsk)
    }.getOrNull()?.takeIf { it.size == PSK_BYTES }

    /** Validates a key pasted into the console; returns an error message or null. */
    fun setNoisePsk(encoded: String): String? {
        val trimmed = encoded.trim()
        val bytes = runCatching { Base64.getDecoder().decode(trimmed) }.getOrNull()
            ?: return "不是合法的 base64"
        if (bytes.size != PSK_BYTES) return "PSK 必须是 32 字节（base64 后 44 个字符）"
        prefs.edit().putString(KEY_NOISE_PSK, trimmed).apply()
        return null
    }

    /**
     * Re-open network ADB on every boot. Xiaomi's security centre closes it at
     * boot, and this needs WRITE_SECURE_SETTINGS, which only `pm grant` can give
     * an app that is not signed as a system app.
     */
    var keepAdb: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ADB, false)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ADB, value).apply()

    /** Last camera list we could read, as JSON, so the select survives a slow boot. */
    var cachedCameras: String?
        get() = prefs.getString(KEY_CAMERA_CACHE, null)
        set(value) = prefs.edit().putString(KEY_CAMERA_CACHE, value).apply()

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
     * New default entities would silently be missing after an upgrade, so each
     * one is folded in once, keyed by the table version. After that the file is
     * the single source of truth and is never rewritten again.
     */
    private fun migrate(raw: String): String {
        val version = prefs.getInt(KEY_COMMANDS_VERSION, 1)
        if (version >= COMMANDS_VERSION) return raw
        val array = runCatching { Json.parseToJsonElement(raw) as JsonArray }.getOrNull()
        var out = raw
        if (array != null) {
            val additions = buildList {
                if (version < 2 && array.none { it.isSelect() }) add(DEFAULT_SELECT)
                if (version < 3 && array.none { it.isCameraSelect() }) add(DEFAULT_CAMERA_SELECT)
            }
            if (additions.isNotEmpty()) {
                out = JsonArray(additions + array).toString()
                runCatching { commandsFile.writeText(out) }
            }
        }
        prefs.edit().putInt(KEY_COMMANDS_VERSION, COMMANDS_VERSION).apply()
        return out
    }

    private fun kotlinx.serialization.json.JsonElement.isSelect(): Boolean =
        ((this as? JsonObject)?.get("type") as? JsonPrimitive)?.content == "select"

    private fun kotlinx.serialization.json.JsonElement.isCameraSelect(): Boolean {
        val obj = this as? JsonObject ?: return false
        if (((obj["type"] as? JsonPrimitive)?.content) != "select") return false
        return ((obj["source"] as? JsonPrimitive)?.content) == "cameras"
    }

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
        private const val KEY_NOISE_ENABLED = "noise_enabled"
        private const val KEY_KEEP_ADB = "keep_adb"
        private const val KEY_CAMERA_CACHE = "camera_cache"
        private const val KEY_NOISE_PSK = "noise_psk"
        private const val PSK_BYTES = 32
        private const val KEY_COMMANDS_VERSION = "commands_version"
        private const val COMMANDS_VERSION = 3

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

        /** Values come from the camera binder, so no options are listed here. */
        private val DEFAULT_CAMERA_SELECT = Json.parseToJsonElement(
            """
              {
                "id": "camera_pip",
                "name": "摄像头画中画",
                "type": "select",
                "source": "cameras",
                "icon": "mdi:cctv"
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
                "id": "camera_pip",
                "name": "摄像头画中画",
                "type": "select",
                "source": "cameras",
                "icon": "mdi:cctv"
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
