package cn.alone.hamitv.tv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One entry of the editable command table.
 *
 * `type` selects the executor:
 *  - activity / service / broadcast: build an Intent and fire it
 *  - camera_pip / camera_close: drive the Xiaomi camera binder
 *  - select: not executed directly; it becomes a select entity whose
 *    [options] are commands, one per selectable value. With `source: "cameras"`
 *    the values come from the camera binder instead of the table.
 */
data class CommandSpec(
    val id: String,
    val name: String,
    val type: String,
    val action: String = "",
    val component: String? = null,
    val icon: String? = null,
    val categories: List<String> = emptyList(),
    val extras: Map<String, Any> = emptyMap(),
    val options: List<SelectOption> = emptyList(),
    /** Only for selects: where the values come from, e.g. `cameras`. */
    val source: String? = null,
) {
    val isSelect: Boolean get() = type == "select"
}

/** A selectable value and the command it runs. [name] is the value HA shows. */
data class SelectOption(val name: String, val spec: CommandSpec)

object CommandTable {

    private val json = Json { ignoreUnknownKeys = true }

    /** Invalid entries are skipped rather than failing the whole table. */
    fun parse(raw: String): List<CommandSpec> {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return emptyList()
        return root.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            // A select without values has nothing to show, unless the values
            // are generated at runtime from a source.
            command(obj)?.takeIf { spec ->
                !spec.isSelect || spec.options.isNotEmpty() || spec.source != null
            }
        }
    }

    /** [fallbackId] lets a select option use its display name as the id. */
    private fun command(obj: JsonObject, fallbackId: String? = null): CommandSpec? {
        val id = obj.text("id")?.trim().orEmpty().ifEmpty { fallbackId.orEmpty() }
        if (id.isEmpty()) return null
        val type = obj.text("type")?.trim()?.lowercase() ?: "activity"
        val action = obj.text("action")?.trim().orEmpty()
        val component = obj.text("component")?.trim()?.takeIf { it.isNotBlank() }
        // Camera commands drive the binder, select entries are containers:
        // both may carry neither action nor component.
        val needsTarget = type != "select" && !type.startsWith("camera") && !type.startsWith("study")
        if (action.isEmpty() && component == null && needsTarget) return null

        return CommandSpec(
            id = id,
            name = obj.text("name")?.takeIf { it.isNotBlank() } ?: id,
            type = type,
            action = action,
            component = component,
            icon = obj.text("icon")?.trim()?.takeIf { it.isNotBlank() },
            categories = obj.strings("categories"),
            extras = obj.extras(),
            options = if (type == "select") obj.selectOptions() else emptyList(),
            source = if (type == "select") {
                obj.text("source")?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            } else {
                null
            },
        )
    }

    private fun JsonObject.selectOptions(): List<SelectOption> {
        val array = this["options"] as? JsonArray ?: return emptyList()
        val seen = mutableSetOf<String>()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val name = obj.text("name")?.trim().orEmpty()
            // Option names are the values HA displays and echoes back.
            if (name.isEmpty() || name.length > 255 || !seen.add(name)) return@mapNotNull null
            SelectOption(name, command(obj, fallbackId = name) ?: return@mapNotNull null)
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { value -> value.isNotBlank() } }

    /**
     * JSON types are preserved on purpose: `{"input": 23}` becomes an int extra,
     * `{"input": "23"}` a string one. Xiaomi's ExternalSourceActivity only reads
     * the int, so collapsing everything to strings silently breaks it.
     */
    private fun JsonObject.extras(): Map<String, Any> =
        (this["extras"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            key to primitive.value()
        }.toMap()

    private fun JsonPrimitive.value(): Any = when {
        isString -> content
        booleanOrNull != null -> booleanOrNull!!
        intOrNull != null -> intOrNull!!
        doubleOrNull != null -> doubleOrNull!!
        else -> content
    }
}
