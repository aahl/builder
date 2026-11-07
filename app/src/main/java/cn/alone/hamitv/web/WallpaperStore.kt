package cn.alone.hamitv.web

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * The pictures CleanYourTV rotates, in <external files>/wallpapers, and the
 * config.json keys that decide whether Home shows them.
 *
 * The limits mirror WallpaperRotation.images() in the launcher: 100 pictures at
 * most, 10 MB each, jpg/jpeg/png/webp. A file accepted here is one Home will
 * actually pick up; anything else is rejected instead of sitting in the folder
 * looking configured.
 */
class WallpaperStore(private val context: Context) {

    sealed interface SaveResult {
        data class Saved(val name: String) : SaveResult
        data class Rejected(val reason: String) : SaveResult
    }

    private val root: File? get() = context.getExternalFilesDir(null)
    private val dir: File? get() = root?.let { File(it, WALLPAPERS) }
    private val config: File? get() = root?.let { File(it, CONFIG_NAME) }

    /** The collection as Home sees it, sorted by name. */
    fun list(): List<WallpaperFile> {
        val entries = dir?.listFiles() ?: return emptyList()
        return entries.asSequence()
            .filter { it.isFile && it.length() > 0 && allowed(it.name) }
            .sortedBy { it.name }
            .take(MAX_FILES)
            .map { WallpaperFile(it.name, it.length(), usable = it.length() <= MAX_BYTES) }
            .toList()
    }

    /** The file behind /api/wallpapers/{name}, or null when the name is not one we wrote. */
    fun file(name: String?): File? {
        val folder = dir ?: return null
        if (name == null || !allowed(name)) return null
        val file = File(folder, name)
        val parent = file.canonicalFile.parentFile ?: return null
        return if (parent == folder.canonicalFile) file.takeIf { it.isFile } else null
    }

    /** Streams one uploaded picture into the collection, never overwriting an existing name. */
    fun save(originalName: String?, input: InputStream): SaveResult {
        val folder = dir ?: return SaveResult.Rejected("外部存储不可用")
        if (!folder.isDirectory && !folder.mkdirs()) return SaveResult.Rejected("无法创建壁纸目录")
        if (list().size >= MAX_FILES) return SaveResult.Rejected("最多 $MAX_FILES 张壁纸")

        val parts = splitName(originalName) ?: return SaveResult.Rejected("只支持 jpg/jpeg/png/webp")
        val name = uniqueName(folder, parts.first, parts.second)
        val temp = File(folder, ".upload-${System.nanoTime()}.tmp")
        var total = 0L
        try {
            temp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) return SaveResult.Rejected("单张不能超过 10MB")
                    out.write(buffer, 0, read)
                }
            }
            if (total == 0L) return SaveResult.Rejected("文件是空的")
            val target = File(folder, name)
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
            }
            return SaveResult.Saved(name)
        } catch (error: Exception) {
            return SaveResult.Rejected(error.message ?: "写入失败")
        } finally {
            temp.delete()
        }
    }

    fun delete(name: String?): Boolean = file(name)?.delete() == true

    fun state(): WallpaperState {
        val json = readConfig()
        return WallpaperState(
            enabled = json.bool("wallpaper") ?: true,
            rotationMinutes = (json.int("wallpaper_rotation_minutes") ?: 0).coerceIn(0, 1440),
            order = json.string("wallpaper_order")
                ?.takeIf { it == ORDER_SEQUENTIAL || it == ORDER_SHUFFLE }
                ?: ORDER_SHUFFLE,
            dim = (json.int("wallpaper_dim") ?: 0).coerceIn(0, 90),
            files = list(),
            maxFiles = MAX_FILES,
            maxBytes = MAX_BYTES,
            onTvChoice = context.getSharedPreferences(LAUNCHER_SETTINGS, Context.MODE_PRIVATE)
                .getString(WALLPAPER_CHOICE, null),
        )
    }

    /**
     * Writes only the wallpaper keys, keeping everything else in a theme's
     * config.json (colours, dock, festival) untouched.
     */
    fun saveSettings(payload: WallpaperSettings): WallpaperState {
        val target = config ?: throw IllegalStateException("外部存储不可用")
        val current = readConfig() ?: JsonObject(emptyMap())
        val updated = JsonObject(
            current + mapOf(
                "wallpaper" to JsonPrimitive(payload.enabled),
                "wallpaper_rotation_minutes" to JsonPrimitive(payload.rotationMinutes.coerceIn(0, 1440)),
                "wallpaper_order" to JsonPrimitive(
                    if (payload.order == ORDER_SEQUENTIAL) ORDER_SEQUENTIAL else ORDER_SHUFFLE,
                ),
                "wallpaper_dim" to JsonPrimitive(payload.dim.coerceIn(0, 90)),
            ),
        )
        val temp = File(target.parentFile, ".config-${System.nanoTime()}.tmp")
        temp.writeText(JSON.encodeToString(JsonObject.serializer(), updated), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        reloadHome(rememberTheme = true)
        return state()
    }

    /**
     * Home reads Config once in onCreate and recreates itself in onResume when
     * this counter moved, so the next time it comes back to the front it picks
     * the new pictures up. The key belongs to the launcher (Config's
     * SETTINGS_REVISION_KEY); it is duplicated here because that class is
     * package-private.
     *
     * Writing config.json also changes the stamp the launcher treats as "a
     * theme was pushed", which drops every on-TV choice (columns, animations,
     * wallpaper, dock opacity, clock format). Remember the new stamp in the
     * same edit so our own write does not look like a theme push.
     */
    fun reloadHome(rememberTheme: Boolean = false) {
        val prefs = context.getSharedPreferences(LAUNCHER_SETTINGS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putLong(SETTINGS_REVISION, prefs.getLong(SETTINGS_REVISION, 0) + 1)
        if (rememberTheme) editor.putString(THEME_SIGNATURE, themeSignature())
        editor.apply()
    }

    /** Config.fileStamp() + ":" + Config.revisionToken(), byte for byte. */
    private fun themeSignature(): String {
        val target = config
        val stamp = if (target != null && target.isFile) {
            "${target.length()}@${target.lastModified()}"
        } else {
            "absent"
        }
        val revision = root?.let { File(it, THEME_REVISION) }
        val token = if (revision != null && revision.isFile && revision.length() in 1..64) {
            runCatching { revision.readText(Charsets.US_ASCII) }
                .getOrDefault("")
                .ifEmpty { "absent" }
        } else {
            "absent"
        }
        return "$stamp:$token"
    }

    private fun readConfig(): JsonObject? {
        val target = config ?: return null
        if (!target.isFile) return null
        return runCatching {
            Json.parseToJsonElement(target.readText(Charsets.UTF_8)) as? JsonObject
        }.getOrNull()
    }

    /** "My Photo.JPG" -> ("My-Photo", "jpg"); null for anything Home would skip. */
    private fun splitName(original: String?): Pair<String, String>? {
        val raw = original.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val dot = raw.lastIndexOf('.')
        if (dot <= 0) return null
        val extension = raw.substring(dot + 1).lowercase(Locale.ROOT)
        if (extension !in EXTENSIONS) return null
        val base = raw.substring(0, dot)
            .replace(Regex("[^A-Za-z0-9_-]+"), "-")
            .trim('-')
            .take(48)
            .ifEmpty { "wallpaper" }
        return base to extension
    }

    private fun uniqueName(folder: File, base: String, extension: String): String {
        var name = "$base.$extension"
        var suffix = 2
        while (File(folder, name).exists()) {
            name = "$base-$suffix.$extension"
            suffix++
        }
        return name
    }

    private fun allowed(name: String): Boolean =
        !name.startsWith(".") && NAME.matches(name)

    private fun JsonObject?.bool(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject?.int(key: String): Int? =
        (this?.get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject?.string(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    companion object {
        const val WALLPAPERS = "wallpapers"
        const val CONFIG_NAME = "config.json"
        const val MAX_FILES = 100
        const val MAX_BYTES = 10L * 1024 * 1024
        const val ORDER_SHUFFLE = "shuffle"
        const val ORDER_SEQUENTIAL = "sequential"

        /** Same names the launcher uses; see Config.SETTINGS_NAME and SETTINGS_REVISION_KEY. */
        private const val LAUNCHER_SETTINGS = "launcher_settings"
        private const val SETTINGS_REVISION = "settings_revision"
        private const val THEME_SIGNATURE = "theme_signature"
        private const val THEME_REVISION = "theme_revision"

        /** An on-TV pick (a file name, or "none") overrides everything here. */
        private const val WALLPAPER_CHOICE = "wallpaper_choice"

        private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        /** What ThemeFiles and WallpaperRotation accept: plain ASCII names with a known suffix. */
        private val NAME = Regex("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE)

        private val JSON = Json { prettyPrint = true }
    }
}
