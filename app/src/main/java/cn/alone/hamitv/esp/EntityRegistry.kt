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

/** A camera the TV knows about; [name] is the value HA shows. */
data class CameraOption(val name: String, val did: String)

/**
 * One select entity. Static selects carry their [options] and the matching
 * [commands]; `source: "cameras"` selects ask [cameraOptions] instead, because
 * the camera list only exists at runtime.
 */
data class SelectEntity(
    val objectId: String,
    val key: Int,
    val name: String,
    val icon: String,
    val options: List<String>,
    val commands: Map<String, CommandSpec>,
    val source: String? = null,
    val cameraOptions: (() -> List<CameraOption>)? = null,
) {

    /** Values HA can pick right now. */
    fun optionNames(): List<String> = cameraOptions?.invoke()?.map { it.name } ?: options

    /** Resolves a value back to the command it runs. */
    fun commandFor(option: String): CommandSpec? {
        val cameras = cameraOptions ?: return commands[option]
        val camera = cameras().firstOrNull { it.name == option } ?: return null
        return CommandSpec(
            id = "${objectId}_${camera.did}",
            name = option,
            type = "camera_pip",
            extras = mapOf("did" to camera.did),
        )
    }
}

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
class EntityRegistry(
    commands: List<CommandSpec>,
    private val cameras: () -> List<CameraOption> = { emptyList() },
) {

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
        .mapNotNull { command ->
            val dynamic = command.source == "cameras"
            if (!dynamic && command.options.isEmpty()) return@mapNotNull null
            SelectEntity(
                objectId = objectId(command.id),
                key = keyOf(objectId(command.id)),
                name = command.name,
                icon = command.icon ?: "mdi:form-dropdown",
                options = command.options.map { it.name },
                commands = command.options.associate { it.name to it.spec },
                source = command.source,
                cameraOptions = if (dynamic) cameras else null,
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
