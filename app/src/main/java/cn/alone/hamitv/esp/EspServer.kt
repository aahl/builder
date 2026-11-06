package cn.alone.hamitv.esp

import android.content.Context
import android.os.Build
import android.provider.Settings
import cn.alone.hamitv.config.ConfigStore
import cn.alone.hamitv.net.MdnsAdvertiser
import cn.alone.hamitv.esp.proto.AuthenticationResponse
import cn.alone.hamitv.esp.proto.ButtonCommandRequest
import cn.alone.hamitv.esp.proto.DeviceInfoResponse
import cn.alone.hamitv.esp.proto.DisconnectResponse
import cn.alone.hamitv.esp.proto.GetTimeResponse
import cn.alone.hamitv.esp.proto.HelloRequest
import cn.alone.hamitv.esp.proto.HelloResponse
import cn.alone.hamitv.esp.proto.ListEntitiesButtonResponse
import cn.alone.hamitv.esp.proto.ListEntitiesDoneResponse
import cn.alone.hamitv.esp.proto.ListEntitiesSelectResponse
import cn.alone.hamitv.esp.proto.ListEntitiesTextSensorResponse
import cn.alone.hamitv.esp.proto.PingResponse
import cn.alone.hamitv.esp.proto.SelectCommandRequest
import cn.alone.hamitv.esp.proto.SelectStateResponse
import cn.alone.hamitv.esp.proto.TextSensorStateResponse
import cn.alone.hamitv.esp.noise.CipherState
import cn.alone.hamitv.esp.noise.NoiseHandshake
import cn.alone.hamitv.tv.CommandExecutor
import cn.alone.hamitv.tv.CommandSpec
import cn.alone.hamitv.tv.CommandTable
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
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

    /** Last option picked per select key. Empty until HA picks one. */
    private val selectStates = ConcurrentHashMap<Int, String>()
    private val mac: String by lazy { deriveMac() }

    @Volatile
    private var mdns: MdnsAdvertiser? = null

    @Volatile
    private var advertisedName: String = ""

    val connectionCount: Int get() = connections.size

    val mdnsRegistered: Boolean get() = mdns?.registered ?: false

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(store.espPort), 4)
        }
        server = socket
        running = true
        registry = buildRegistry()
        thread(name = "esp-accept", isDaemon = true) { acceptLoop(socket) }
        log(
            "esp: listening on ${store.espPort}, ${registry.buttons.size} buttons, " +
                "${registry.selects.size} selects, encryption ${if (store.noiseEnabled) "on" else "off"}",
        )
        advertise()
        probeCamerasLater(0)
    }

    /**
     * At boot the Xiaomi camera service is usually not published yet, and a
     * dynamic select with no options is dropped for the whole session
     * (see listEntities). Poll until it answers, then re-list once so HA picks
     * the select up without waiting for a reconnect.
     */
    private fun probeCamerasLater(attempt: Int) {
        if (attempt >= CAMERA_PROBE_DELAYS_MS.size) return
        thread(name = "esp-camera-probe", isDaemon = true) {
            Thread.sleep(CAMERA_PROBE_DELAYS_MS[attempt])
            if (!running) return@thread
            val live = runCatching { executor.cameras() }.getOrDefault(emptyList())
            if (live.isEmpty()) {
                probeCamerasLater(attempt + 1)
                return@thread
            }
            val announced = registry.selects
                .filter { it.source == "cameras" }
                .sumOf { it.optionNames().size }
            if (live.size == announced) return@thread
            log("esp: camera list is up (${live.size}), re-listing entities")
            reload()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        connections.forEach { it.close() }
        connections.clear()
        mdns?.stop()
        mdns = null
        advertisedName = ""
    }

    @Synchronized
    fun restart() {
        stop()
        start()
    }

    /** Rebuilds entities from the command table and drops clients so HA re-lists. */
    private fun buildRegistry(): EntityRegistry =
        EntityRegistry(CommandTable.parse(store.readCommands())) {
            // The camera service may not be up yet; an empty list just means the
            // dynamic select is skipped until a reconnect.
            runCatching { executor.cameras() }.getOrDefault(emptyList())
                .map { CameraOption(it.roomName, it.did) }
        }

    fun reload() {
        registry = buildRegistry()
        connections.forEach { it.close() }
        // The advertised name is the device name, so a rename needs a new record.
        if (store.deviceName != advertisedName) advertise()
        log("esp: reloaded, ${registry.buttons.size} buttons, ${registry.selects.size} selects")
    }

    /** Network changed: the mDNS record is bound to the old address. */
    @Synchronized
    fun refreshAdvertise() {
        if (!running) return
        log("esp: network changed, re-registering mdns")
        advertise()
    }

    private fun advertise() {
        if (!running) return
        mdns?.stop()
        advertisedName = store.deviceName
        mdns = MdnsAdvertiser(
            context = context,
            name = store.deviceName,
            port = store.espPort,
            mac = macPlain(),
            version = ESPHOME_VERSION,
            log = log,
        ).also { it.start() }
    }

    fun publishLastCommand(text: String) {
        lastCommand = text
        connections.forEach { it.pushLastCommand(text) }
    }

    internal fun deviceName(): String = store.deviceName

    internal fun macAddress(): String = mac

    /** Same string as the mDNS TXT record: HA compares it verbatim. */
    internal fun macPlain(): String = mac.replace(":", "").lowercase()

    internal fun encryptionEnabled(): Boolean = store.noiseEnabled

    /** Null when encryption is off or the stored key is unusable. */
    internal fun psk(): ByteArray? = store.noisePskBytes()

    internal fun execute(button: ButtonEntity): String = describe(button.command, run(button.command))

    internal fun run(spec: CommandSpec): Result<String> {
        val result = executor.run(spec)
        // Closing the window invalidates whatever the camera select was showing.
        if (result.isSuccess && spec.type == "camera_close") clearCameraSelects()
        return result
    }

    private fun clearCameraSelects() {
        registry.selects.filter { it.source == "cameras" }.forEach { select ->
            selectStates.remove(select.key)
            connections.forEach { it.pushSelectState(select, null) }
        }
    }

    internal fun describe(spec: CommandSpec, result: Result<String>): String = result.fold(
        onSuccess = { "${spec.name}: $it" },
        onFailure = { "${spec.name}: ${it.message ?: it.javaClass.simpleName}" },
    )

    internal fun selectState(key: Int): String? = selectStates[key]

    /** Records the pick and mirrors it to every subscribed connection. */
    internal fun publishSelect(select: SelectEntity, option: String) {
        selectStates[select.key] = option
        connections.forEach { it.pushSelectState(select, option) }
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
        const val PROJECT_VERSION = "0.2.0-a5"

        /** Backoff for the camera probe: the TV publishes that service long after we start. */
        private val CAMERA_PROBE_DELAYS_MS = longArrayOf(3_000, 8_000, 20_000, 45_000, 90_000, 180_000)
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

    @Volatile
    private var sendCipher: CipherState? = null

    @Volatile
    private var receiveCipher: CipherState? = null

    fun run() {
        try {
            if (server.encryptionEnabled()) {
                // Never fall back to plaintext: a broken key must fail closed.
                val psk = server.psk()
                if (psk == null) {
                    server.log("esp: encryption is on but the stored psk is invalid")
                    return
                }
                if (!handshake(psk)) return
            }
            while (!closed) {
                val cipher = receiveCipher
                val frame = if (cipher != null) NoiseFrame.read(input, cipher) else EspFrame.read(input)
                if (cipher == null && frame.indicator != EspFrame.INDICATOR_PLAINTEXT) {
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

    /**
     * Responder side of ESPHome's Noise handshake.
     *
     * 1. the client sends an empty noise frame; its bytes (with a length prefix)
     *    become part of the prologue, binding the handshake to this connection
     * 2. we answer with the server hello: proto 0x01, name, mac, each NUL ended
     * 3. the client sends handshake message 1, we answer with message 2
     *
     * A plaintext client fails at step 1; the reject frame carries the noise
     * indicator, which is what makes HA ask the user for an encryption key.
     */
    private fun handshake(psk: ByteArray): Boolean {
        val hello = runCatching { NoiseFrame.readRaw(input) }.getOrElse {
            server.log("esp: client did not open with a noise frame (${it.message})")
            sendHandshakeReject("Bad indicator byte")
            return false
        }
        val handshake = NoiseHandshake(psk, NoiseHandshake.prologue(hello), NoiseHandshake.Role.RESPONDER)
        NoiseFrame.writeRaw(out, serverHello())

        val payload = runCatching { NoiseFrame.readRaw(input) }.getOrElse {
            server.log("esp: handshake frame missing (${it.message})")
            return false
        }
        if (payload.isEmpty() || payload[0].toInt() != 0x00) {
            server.log("esp: client rejected the handshake")
            return false
        }
        val result = runCatching {
            handshake.readMessage1(payload.copyOfRange(1, payload.size))
            NoiseFrame.writeRaw(out, byteArrayOf(0x00) + handshake.writeMessage2())
            handshake.split()
        }
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            server.log("esp: handshake failed: ${error?.message ?: error?.javaClass?.simpleName}")
            // Matches ESPHome's reject reason, which clients map to a wrong key.
            sendHandshakeReject(if (error is javax.crypto.AEADBadTagException) "Handshake MAC failure" else "Handshake failure")
            return false
        }
        sendCipher = handshake.sendCipher
        receiveCipher = handshake.receiveCipher
        server.log("esp: noise handshake complete")
        return true
    }

    private fun serverHello(): ByteArray =
        byteArrayOf(0x01) +
            server.deviceName().toByteArray() + byteArrayOf(0x00) +
            server.macPlain().toByteArray() + byteArrayOf(0x00)

    private fun sendHandshakeReject(reason: String) {
        runCatching { NoiseFrame.writeRaw(out, byteArrayOf(0x01) + reason.toByteArray()) }
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
                server.registry.selects.forEach { pushSelectState(it, server.selectState(it.key)) }
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
            EspMessageId.SELECT_COMMAND_REQUEST -> onSelect(body)
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
        .setApiEncryptionSupported(server.encryptionEnabled())
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
                    .setIcon(button.icon)
                    .build()
                    .toByteArray(),
            )
        }
        registry.selects.forEach { select ->
            val options = select.optionNames()
            // A select with no values is rejected by HA; skip it for this session.
            if (options.isEmpty()) return@forEach
            send(
                EspMessageId.LIST_ENTITIES_SELECT_RESPONSE,
                ListEntitiesSelectResponse.newBuilder()
                    .setObjectId(select.objectId)
                    .setKey(select.key)
                    .setName(select.name)
                    .setIcon(select.icon)
                    .addAllOptions(options)
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

    private fun onSelect(body: ByteArray) {
        val request = SelectCommandRequest.parseFrom(body)
        val select = server.registry.select(request.key)
        if (select == null) {
            server.log("esp: unknown select key ${request.key}")
            return
        }
        val command = select.commandFor(request.state)
        if (command == null) {
            // HA should only send values we advertised; answer with the truth.
            server.log("esp: '${request.state}' is not an option of ${select.objectId}")
            pushSelectState(select, server.selectState(select.key))
            return
        }
        val result = server.run(command)
        server.publishLastCommand(server.describe(command, result))
        // A failed launch must not leave HA showing a source the TV never switched to.
        if (result.isSuccess) {
            server.publishSelect(select, request.state)
        } else {
            pushSelectState(select, server.selectState(select.key))
        }
    }

    fun pushSelectState(select: SelectEntity, option: String?) {
        if (!subscribed) return
        val builder = SelectStateResponse.newBuilder().setKey(select.key)
        if (option == null) {
            builder.setMissingState(true)
        } else {
            builder.setState(option)
        }
        send(EspMessageId.SELECT_STATE_RESPONSE, builder.build().toByteArray())
    }

    private fun send(typeId: Int, body: ByteArray) {
        if (closed) return
        synchronized(writeLock) {
            runCatching {
                val cipher = sendCipher
                if (cipher != null) NoiseFrame.write(out, cipher, typeId, body)
                else EspFrame.write(out, typeId, body)
            }.onFailure { close() }
        }
    }
}
