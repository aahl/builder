package cn.alone.hamitv.tv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One entry of the editable command table.
 *
 * `type` selects the executor:
 *  - activity / service / broadcast: build an Intent and fire it
 *  - camera_pip / camera_close: drive the Xiaomi camera binder
 */
data class CommandSpec(
    val id: String,
    val name: String,
    val type: String,
    val action: String,
    val component: String? = null,
    val extras: Map<String, String> = emptyMap(),
)

object CommandTable {

    private val json = Json { ignoreUnknownKeys = true }

    /** Invalid entries are skipped rather than failing the whole table. */
    fun parse(raw: String): List<CommandSpec> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.text("id")?.trim().orEmpty()
            val action = obj.text("action")?.trim().orEmpty()
            val type = obj.text("type")?.trim()?.lowercase() ?: "activity"
            // Camera commands drive the binder and carry no intent action.
            if (id.isEmpty() || (action.isEmpty() && !type.startsWith("camera"))) return@mapNotNull null
            CommandSpec(
                id = id,
                name = obj.text("name")?.takeIf { it.isNotBlank() } ?: id,
                type = type,
                action = action,
                component = obj.text("component")?.takeIf { it.isNotBlank() },
                extras = (obj["extras"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                    (value as? JsonPrimitive)?.content ?: value.toString()
                },
            )
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
}
