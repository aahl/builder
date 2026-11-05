package cn.alone.hamitv.esp

import cn.alone.hamitv.tv.CommandSpec
import java.util.zip.CRC32

data class ButtonEntity(
    val objectId: String,
    val key: Int,
    val name: String,
    val command: CommandSpec,
)

data class TextSensorEntity(
    val objectId: String,
    val key: Int,
    val name: String,
)

/**
 * Entities are derived from the command table, so adding a command is a config
 * edit rather than a rebuild. Keys must stay stable across restarts because HA
 * uses them to match entities to their registry entries.
 */
class EntityRegistry(commands: List<CommandSpec>) {

    val buttons: List<ButtonEntity> = commands.map { command ->
        val objectId = "mitv_" + command.id.lowercase().replace(Regex("[^a-z0-9_]"), "_")
        ButtonEntity(objectId = objectId, key = keyOf(objectId), name = command.name, command = command)
    }

    val lastCommand = TextSensorEntity(
        objectId = "mitv_last_command",
        key = keyOf("mitv_last_command"),
        name = "最后执行的命令",
    )

    fun button(key: Int): ButtonEntity? = buttons.firstOrNull { it.key == key }

    companion object {
        fun keyOf(objectId: String): Int =
            CRC32().apply { update(objectId.toByteArray()) }.value.toInt()
    }
}
