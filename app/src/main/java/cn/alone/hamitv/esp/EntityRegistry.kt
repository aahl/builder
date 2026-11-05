package cn.alone.hamitv.esp

import cn.alone.hamitv.tv.CommandSpec
import java.util.zip.CRC32

data class ButtonEntity(
    val objectId: String,
    val key: Int,
    val name: String,
    val icon: String,
    val command: CommandSpec,
)

/** One select entity; [options] are the values HA can pick, in table order. */
data class SelectEntity(
    val objectId: String,
    val key: Int,
    val name: String,
    val icon: String,
    val options: List<String>,
    val commands: Map<String, CommandSpec>,
)

data class TextSensorEntity(
    val objectId: String,
    val key: Int,
    val name: String,
)

/**
 * Entities are derived from the command table, so adding a command is a config
 * edit rather than a rebuild. Keys must stay stable across restarts because HA
 * uses them to match entities to its registry entries.
 */
class EntityRegistry(commands: List<CommandSpec>) {

    val buttons: List<ButtonEntity> = commands
        .filterNot { it.isSelect }
        .map { command ->
            ButtonEntity(
                objectId = objectId(command.id),
                key = keyOf(objectId(command.id)),
                name = command.name,
                icon = command.icon ?: "mdi:remote",
                command = command,
            )
        }

    val selects: List<SelectEntity> = commands
        .filter { it.isSelect }
        .map { command ->
            SelectEntity(
                objectId = objectId(command.id),
                key = keyOf(objectId(command.id)),
                name = command.name,
                icon = command.icon ?: "mdi:form-dropdown",
                options = command.options.map { it.name },
                commands = command.options.associate { it.name to it.spec },
            )
        }

    val lastCommand = TextSensorEntity(
        objectId = "mitv_last_command",
        key = keyOf("mitv_last_command"),
        name = "最后执行的命令",
    )

    fun button(key: Int): ButtonEntity? = buttons.firstOrNull { it.key == key }

    fun select(key: Int): SelectEntity? = selects.firstOrNull { it.key == key }

    companion object {
        fun keyOf(objectId: String): Int =
            CRC32().apply { update(objectId.toByteArray()) }.value.toInt()

        fun objectId(id: String): String = "mitv_" + id.lowercase().replace(Regex("[^a-z0-9_]"), "_")
    }
}
