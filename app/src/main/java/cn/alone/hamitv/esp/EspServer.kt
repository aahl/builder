package cn.alone.hamitv.esp

import android.content.Context
import android.os.Build
import android.provider.Settings
import cn.alone.hamitv.config.ConfigStore
import cn.alone.hamitv.esp.proto.AuthenticationResponse
import cn.alone.hamitv.esp.proto.ButtonCommandRequest
import cn.alone.hamitv.esp.proto.DeviceInfoResponse
import cn.alone.hamitv.esp.proto.DisconnectResponse
import cn.alone.hamitv.esp.proto.GetTimeResponse
import cn.alone.hamitv.esp.proto.HelloRequest
import cn.alone.hamitv.esp.proto.HelloResponse
import cn.alone.hamitv.esp.proto.ListEntitiesButtonResponse
import cn.alone.hamitv.esp.proto.ListEntitiesDoneResponse
import cn.alone.hamitv.esp.proto.ListEntitiesTextSensorResponse
import cn.alone.hamitv.esp.proto.PingResponse
import cn.alone.hamitv.esp.proto.TextSensorStateResponse
import cn.alone.hamitv.tv.CommandExecutor
import cn.alone.hamitv.tv.CommandTable
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * ESPHome native API server (plaintext).
 *
 * HA opens two connections (one for commands, one for state subscriptions) and
 * both run the same handshake, so every socket gets its own session.
 */
class EspServer(
    private val context: Context,
    private val store: ConfigStore,
    private val executor: CommandExecutor,
    internal val log: (String) -> Unit = {},
) {

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var registry = EntityRegistry(emptyList())
        private set

    @Volatile
    var lastCommand: String = ""
        private set

    private val connections = CopyOnWriteArrayList<EspConnection>()
    private val mac: String by lazy { deriveMac() }

    val connectionCount: Int get() = connections.size

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(store.espPort), 4)
        }
        server = socket
        running = true
        registry = EntityRegistry(CommandTable.parse(store.readCommands()))
        thread(name = "esp-accept", isDaemon = true) { acceptLoop(socket) }
        log("esp: listening on ${store.espPort}, ${registry.buttons.size} buttons")
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        connections.forEach { it.close() }
        connections.clear()
    }

    @Synchronized
    fun restart() {
        stop()
        start()
    }

    /** Rebuilds entities from the command table and drops clients so HA re-lists. */
    fun reload() {
        registry = EntityRegistry(CommandTable.parse(store.readCommands()))
        connections.forEach { it.close() }
        log("esp: reloaded, ${registry.buttons.size} buttons")
    }

    fun publishLastCommand(text: String) {
        lastCommand = text
        connections.forEach { it.pushLastCommand(text) }
    }

    internal fun deviceName(): String = store.deviceName

    internal fun macAddress(): String = mac

    internal fun execute(button: ButtonEntity): String {
        val result = executor.run(button.command)
        return result.fold(
            onSuccess = { "${button.command.name}: $it" },
            onFailure = { "${button.command.name}: ${it.message ?: it.javaClass.simpleName}" },
        )
    }

    internal fun forget(connection: EspConnection) {
        connections.remove(connection)
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running && !socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            log("esp: client ${client.inetAddress.hostAddress}")
            val connection = EspConnection(client, this)
            connections.add(connection)
            thread(name = "esp-conn", isDaemon = true) { connection.run() }
        }
    }

    private fun deriveMac(): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty().ifBlank { "ha-mitv" }
        val digest = MessageDigest.getInstance("MD5").digest(androidId.toByteArray())
        val bytes = ByteArray(6)
        bytes[0] = 0x02
        System.arraycopy(digest, 0, bytes, 1, 5)
        return bytes.joinToString(":") { "%02X".format(it.toInt() and 0xFF) }
    }

    companion object {
        const val API_VERSION_MAJOR = 1
        const val API_VERSION_MINOR = 13
        const val ESPHOME_VERSION = "2025.12.0"
        const val PROJECT_VERSION = "0.1.0"
    }
}

/** One client session. HA opens at least two, so nothing here is shared. */
internal class EspConnection(
    private val socket: Socket,
    private val server: EspServer,
) {

    private val out: OutputStream = socket.getOutputStream()
    private val input = BufferedInputStream(socket.getInputStream())
    private val writeLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var subscribed = false

    fun run() {
        try {
            while (!closed) {
                val frame = EspFrame.read(input)
                if (frame.indicator != EspFrame.INDICATOR_PLAINTEXT) {
                    server.log("esp: refusing an encrypted frame on a plaintext session")
                    break
                }
                server.log("esp: <- ${frame.typeId} (${frame.body.size}B)")
                handle(frame.typeId, frame.body)
            }
        } catch (e: Exception) {
            server.log("esp: session ended: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            close()
            server.forget(this)
        }
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
    }

    fun pushLastCommand(text: String) {
        if (!subscribed) return
        send(
            EspMessageId.TEXT_SENSOR_STATE_RESPONSE,
            TextSensorStateResponse.newBuilder()
                .setKey(server.registry.lastCommand.key)
                .setState(text)
                .build()
                .toByteArray(),
        )
    }

    private fun handle(typeId: Int, body: ByteArray) {
        when (typeId) {
            EspMessageId.HELLO_REQUEST -> onHello(body)
            EspMessageId.AUTHENTICATION_REQUEST -> send(
                EspMessageId.AUTHENTICATION_RESPONSE,
                AuthenticationResponse.newBuilder().setInvalidPassword(false).build().toByteArray(),
            )
            EspMessageId.DEVICE_INFO_REQUEST -> send(
                EspMessageId.DEVICE_INFO_RESPONSE,
                deviceInfo().toByteArray(),
            )
            EspMessageId.LIST_ENTITIES_REQUEST -> listEntities()
            EspMessageId.SUBSCRIBE_STATES_REQUEST -> {
                subscribed = true
                pushLastCommand(server.lastCommand)
            }
            EspMessageId.PING_REQUEST -> send(
                EspMessageId.PING_RESPONSE,
                PingResponse.getDefaultInstance().toByteArray(),
            )
            EspMessageId.DISCONNECT_REQUEST -> {
                send(EspMessageId.DISCONNECT_RESPONSE, DisconnectResponse.getDefaultInstance().toByteArray())
                close()
            }
            EspMessageId.BUTTON_COMMAND_REQUEST -> onButton(body)
            EspMessageId.GET_TIME_REQUEST -> send(
                EspMessageId.GET_TIME_RESPONSE,
                GetTimeResponse.newBuilder()
                    .setEpochSeconds((System.currentTimeMillis() / 1000).toInt())
                    .setTimezone(TimeZone.getDefault().id)
                    .build()
                    .toByteArray(),
            )
            else -> server.log("esp: ignoring message $typeId")
        }
    }

    private fun onHello(body: ByteArray) {
        val request = HelloRequest.parseFrom(body)
        server.log(
            "esp: hello from '${request.clientInfo}' " +
                "api ${request.apiVersionMajor}.${request.apiVersionMinor}",
        )
        send(
            EspMessageId.HELLO_RESPONSE,
            HelloResponse.newBuilder()
                .setApiVersionMajor(EspServer.API_VERSION_MAJOR)
                .setApiVersionMinor(EspServer.API_VERSION_MINOR)
                .setServerInfo("ha-mitv ${EspServer.PROJECT_VERSION}")
                .setName(server.deviceName())
                .build()
                .toByteArray(),
        )
    }

    private fun deviceInfo(): DeviceInfoResponse = DeviceInfoResponse.newBuilder()
        .setUsesPassword(false)
        .setName(server.deviceName())
        .setFriendlyName(server.deviceName())
        .setMacAddress(server.macAddress())
        .setEsphomeVersion(EspServer.ESPHOME_VERSION)
        .setModel(Build.MODEL ?: "Android TV")
        .setManufacturer(Build.MANUFACTURER ?: "Android")
        .setProjectName("cn.alone.hamitv")
        .setProjectVersion(EspServer.PROJECT_VERSION)
        .setApiEncryptionSupported(false)
        .build()

    private fun listEntities() {
        val registry = server.registry
        registry.buttons.forEach { button ->
            send(
                EspMessageId.LIST_ENTITIES_BUTTON_RESPONSE,
                ListEntitiesButtonResponse.newBuilder()
                    .setObjectId(button.objectId)
                    .setKey(button.key)
                    .setName(button.name)
                    .setIcon("mdi:remote")
                    .build()
                    .toByteArray(),
            )
        }
        val sensor = registry.lastCommand
        send(
            EspMessageId.LIST_ENTITIES_TEXT_SENSOR_RESPONSE,
            ListEntitiesTextSensorResponse.newBuilder()
                .setObjectId(sensor.objectId)
                .setKey(sensor.key)
                .setName(sensor.name)
                .setIcon("mdi:console-line")
                .build()
                .toByteArray(),
        )
        send(
            EspMessageId.LIST_ENTITIES_DONE_RESPONSE,
            ListEntitiesDoneResponse.getDefaultInstance().toByteArray(),
        )
    }

    private fun onButton(body: ByteArray) {
        val request = ButtonCommandRequest.parseFrom(body)
        val button = server.registry.button(request.key)
        if (button == null) {
            server.log("esp: unknown button key ${request.key}")
            return
        }
        server.publishLastCommand(server.execute(button))
    }

    private fun send(typeId: Int, body: ByteArray) {
        if (closed) return
        synchronized(writeLock) {
            runCatching { EspFrame.write(out, typeId, body) }
                .onFailure { close() }
        }
    }
}
