package cn.alone.hamitv.web

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import cn.alone.hamitv.config.ConfigStore
import cn.alone.hamitv.esp.EspServer
import cn.alone.hamitv.net.localIpv4
import cn.alone.hamitv.system.AdbKeeper
import cn.alone.hamitv.tv.CameraBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Embedded configuration UI. Everything the agent needs to know is edited from
 * a browser; the TV only shows the address and the 4 digit pairing code.
 */
class ConfigServer(
    private val context: Context,
    private val store: ConfigStore,
    private val esp: EspServer? = null,
) {

    private var engine: ApplicationEngine? = null
    private val limiter = PairingLimiter()
    private val camera = CameraBinder(context.applicationContext)
    private val wallpapers = WallpaperStore(context.applicationContext)
    private val startedAt = System.currentTimeMillis()

    @Synchronized
    fun start() {
        if (engine != null) return
        val port = store.webPort
        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") { installModule() }
            .also { it.start(wait = false) }
    }

    @Synchronized
    fun stop() {
        engine?.stop(gracePeriodMillis = 300, timeoutMillis = 800)
        engine = null
    }

    fun restartAsync() {
        Thread {
            Thread.sleep(400)
            stop()
            esp?.restart()
            start()
        }.apply { isDaemon = true }.start()
    }

    private fun Application.installModule() {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(cause.message ?: "error"))
            }
        }

        routing {
            get("/") {
                call.respondText(asset("web/index.html"), ContentType.Text.Html)
            }
            get("/wallpapers") {
                call.respondText(asset("web/wallpapers.html"), ContentType.Text.Html)
            }
            get("/health") {
                call.respondText("ok")
            }
            post("/api/pair") { handlePair(call) }

            get("/api/status") {
                if (!call.authorized()) return@get
                call.respond(status())
            }
            get("/api/config") {
                if (!call.authorized()) return@get
                call.respond(
                    ConfigPayload(
                        deviceName = store.deviceName,
                        webPort = store.webPort,
                        espPort = store.espPort,
                    ),
                )
            }
            put("/api/config") {
                if (!call.authorized()) return@put
                if (!call.ensureCsrf()) return@put
                val payload = call.receive<ConfigPayload>()
                val portChanged = payload.webPort != store.webPort
                val previousName = store.deviceName
                store.deviceName = payload.deviceName.take(32).ifBlank { "MiTV" }
                store.espPort = payload.espPort.coerceIn(1024, 65535)
                if (portChanged) store.webPort = payload.webPort.coerceIn(1024, 65535)
                // A rename changes the mDNS record and the advertised node name.
                if (store.deviceName != previousName) esp?.reload()
                call.respond(ConfigResult(restartRequired = portChanged))
            }

            get("/api/commands") {
                if (!call.authorized()) return@get
                call.respondText(store.readCommands(), ContentType.Application.Json)
            }
            put("/api/commands") {
                if (!call.authorized()) return@put
                if (!call.ensureCsrf()) return@put
                val error = store.writeCommands(call.receiveText())
                if (error == null) {
                    esp?.reload()
                    call.respond(OkResponse())
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                }
            }

            get("/api/wallpapers") {
                if (!call.authorized()) return@get
                call.respond(withContext(Dispatchers.IO) { wallpapers.state() })
            }
            put("/api/wallpapers") {
                if (!call.authorized()) return@put
                if (!call.ensureCsrf()) return@put
                val payload = call.receive<WallpaperSettings>()
                val state = withContext(Dispatchers.IO) { wallpapers.saveSettings(payload) }
                call.respond(state)
            }
            post("/api/wallpapers") {
                if (!call.authorized()) return@post
                if (!call.ensureCsrf()) return@post
                val result = withContext(Dispatchers.IO) {
                    val added = mutableListOf<String>()
                    val errors = mutableListOf<String>()
                    call.receiveMultipart().forEachPart { part ->
                        if (part is PartData.FileItem) {
                            when (val saved = wallpapers.save(part.originalFileName, part.streamProvider())) {
                                is WallpaperStore.SaveResult.Saved -> added += saved.name
                                is WallpaperStore.SaveResult.Rejected ->
                                    errors += "${part.originalFileName ?: "文件"}: ${saved.reason}"
                            }
                        }
                    }
                    WallpaperUpload(added = added, errors = errors)
                }
                if (result.added.isNotEmpty()) wallpapers.reloadHome()
                call.respond(result)
            }
            get("/api/wallpapers/{name}") {
                if (!call.authorized()) return@get
                val file = withContext(Dispatchers.IO) { wallpapers.file(call.parameters["name"]) }
                if (file == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("没有这张壁纸"))
                } else {
                    call.respondFile(file)
                }
            }
            delete("/api/wallpapers/{name}") {
                if (!call.authorized()) return@delete
                if (!call.ensureCsrf()) return@delete
                val removed = withContext(Dispatchers.IO) { wallpapers.delete(call.parameters["name"]) }
                if (!removed) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("没有这张壁纸"))
                } else {
                    wallpapers.reloadHome()
                    call.respond(OkResponse())
                }
            }

            get("/api/pip") {
                if (!call.authorized()) return@get
                call.respond(withContext(Dispatchers.IO) { pipState() })
            }
            post("/api/pip/start") {
                if (!call.authorized()) return@post
                if (!call.ensureCsrf()) return@post
                val did = runCatching { call.receive<PipRequest>().did }.getOrDefault("")
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        camera.startFloatWindow(did)
                        pipState(expectFloating = true)
                    }.getOrElse { error -> PipState(available = true, error = error.message) }
                }
                call.respond(result)
            }
            post("/api/pip/close") {
                if (!call.authorized()) return@post
                if (!call.ensureCsrf()) return@post
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        camera.closeFloatWindow()
                        pipState(expectFloating = false)
                    }.getOrElse { error -> PipState(available = true, error = error.message) }
                }
                call.respond(result)
            }

            get("/api/noise") {
                if (!call.authorized()) return@get
                call.respond(noiseState())
            }
            put("/api/noise") {
                if (!call.authorized()) return@put
                if (!call.ensureCsrf()) return@put
                val payload = call.receive<NoisePayload>()
                payload.psk?.let { psk ->
                    val error = store.setNoisePsk(psk)
                    if (error != null) {
                        call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                        return@put
                    }
                }
                store.noiseEnabled = payload.enabled
                // The listener decides plaintext vs noise per connection, so a
                // restart is what actually applies the change.
                esp?.restart()
                call.respond(noiseState())
            }
            post("/api/noise/rotate") {
                if (!call.authorized()) return@post
                if (!call.ensureCsrf()) return@post
                store.rotateNoisePsk()
                esp?.restart()
                call.respond(noiseState())
            }

            get("/api/adb") {
                if (!call.authorized()) return@get
                call.respond(adbState())
            }
            put("/api/adb") {
                if (!call.authorized()) return@put
                if (!call.ensureCsrf()) return@put
                store.keepAdb = call.receive<AdbPayload>().keep
                if (store.keepAdb) AdbKeeper.ensure(context)
                call.respond(adbState())
            }

            post("/api/code/rotate") {
                if (!call.authorized()) return@post
                if (!call.ensureCsrf()) return@post
                store.clearSession()
                call.respond(CodeResponse(code = store.rotatePairingCode()))
            }

            post("/api/restart") {
                if (!call.authorized()) return@post
                if (!call.ensureCsrf()) return@post
                call.respond(OkResponse())
                restartAsync()
            }
        }
    }

    private suspend fun handlePair(call: ApplicationCall) {
        val host = call.request.origin.remoteHost
        val locked = limiter.lockedFor(host)
        if (locked > 0) {
            call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("locked:${locked / 1000}s"))
            return
        }

        val code = runCatching { call.receive<PairRequest>().code.trim() }.getOrNull().orEmpty()
        if (code != store.pairingCode) {
            limiter.recordFailure(host)
            call.respond(HttpStatusCode.Unauthorized, ErrorResponse("wrong code"))
            return
        }

        limiter.recordSuccess(host)
        val token = store.issueSession()
        call.response.cookies.append(
            Cookie(
                name = SESSION_COOKIE,
                value = token,
                path = "/",
                httpOnly = true,
                maxAge = SESSION_TTL_SECONDS,
                extensions = mapOf("SameSite" to "Lax"),
            ),
        )
        call.respond(OkResponse())
    }

    /** Binds to the Xiaomi camera service and reports what it offers. */
    private fun pipState(expectFloating: Boolean? = null): PipState = runCatching {
        val cameras = camera.cameras()
        var floating = camera.isFloatWindowVisible()
        // The window is created and torn down asynchronously, so poll briefly.
        val deadline = System.currentTimeMillis() + 4000
        while (expectFloating != null && floating != expectFloating && System.currentTimeMillis() < deadline) {
            Thread.sleep(200)
            floating = camera.isFloatWindowVisible()
        }
        PipState(
            available = true,
            apiLevel = camera.apiLevel(),
            floating = floating,
            cameras = cameras.map { PipCamera(did = it.did, roomName = it.roomName) },
        )
    }.getOrElse { error ->
        camera.unbind()
        PipState(available = false, error = error.message ?: error.javaClass.simpleName)
    }

    /** Whether the console switch is on, whether the grant exists, and what the TV says now. */
    private fun adbState() = AdbState(
        keep = store.keepAdb,
        granted = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED,
        enabled = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false),
    )

    private fun noiseState() = NoiseResponse(
        enabled = store.noiseEnabled,
        psk = store.noisePsk,
        connections = esp?.connectionCount ?: 0,
    )

    private fun status() = StatusResponse(
        name = store.deviceName,
        ip = localIpv4(),
        webPort = store.webPort,
        espPort = store.espPort,
        pairingCode = store.pairingCode,
        uptimeSeconds = (System.currentTimeMillis() - startedAt) / 1000,
        android = android.os.Build.VERSION.RELEASE ?: "?",
        model = android.os.Build.MODEL ?: "?",
        espRunning = esp?.running ?: false,
        espConnections = esp?.connectionCount ?: 0,
        espEntities = esp?.registry?.buttons?.size ?: 0,
        mdnsRegistered = esp?.mdnsRegistered ?: false,
        encryption = store.noiseEnabled,
    )

    private suspend fun ApplicationCall.authorized(): Boolean {
        if (store.isValidSession(request.cookies[SESSION_COOKIE])) return true
        respond(HttpStatusCode.Unauthorized, ErrorResponse("unauthorized"))
        return false
    }

    /** State changing routes require a header that cross-site forms cannot set. */
    private suspend fun ApplicationCall.ensureCsrf(): Boolean {
        if (request.headers[CSRF_HEADER] == "hamitv") return true
        respond(HttpStatusCode.Forbidden, ErrorResponse("missing csrf header"))
        return false
    }

    private fun asset(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        const val SESSION_COOKIE = "hamitv_session"
        const val CSRF_HEADER = "X-Requested-With"
        private const val SESSION_TTL_SECONDS = 7 * 24 * 60 * 60
    }
}

@Serializable
data class PairRequest(val code: String = "")

@Serializable
data class ConfigPayload(
    val deviceName: String = "MiTV",
    val webPort: Int = ConfigStore.DEFAULT_WEB_PORT,
    val espPort: Int = ConfigStore.DEFAULT_ESP_PORT,
)

@Serializable
data class ConfigResult(val ok: Boolean = true, val restartRequired: Boolean = false)

@Serializable
data class StatusResponse(
    val name: String,
    val ip: String,
    val webPort: Int,
    val espPort: Int,
    val pairingCode: String,
    val uptimeSeconds: Long,
    val android: String,
    val model: String,
    val espRunning: Boolean = false,
    val espConnections: Int = 0,
    val espEntities: Int = 0,
    val mdnsRegistered: Boolean = false,
    val encryption: Boolean = false,
)

@Serializable
data class NoiseResponse(
    val enabled: Boolean = false,
    val psk: String = "",
    val connections: Int = 0,
)

@Serializable
data class NoisePayload(val enabled: Boolean = false, val psk: String? = null)

@Serializable
data class AdbPayload(val keep: Boolean = false)

@Serializable
data class AdbState(
    val keep: Boolean = false,
    val granted: Boolean = false,
    val enabled: Boolean = false,
)

@Serializable
data class WallpaperFile(val name: String, val size: Long = 0, val usable: Boolean = true)

@Serializable
data class WallpaperState(
    val enabled: Boolean = true,
    val rotationMinutes: Int = 0,
    val order: String = WallpaperStore.ORDER_SHUFFLE,
    val dim: Int = 0,
    val files: List<WallpaperFile> = emptyList(),
    val maxFiles: Int = WallpaperStore.MAX_FILES,
    val maxBytes: Long = WallpaperStore.MAX_BYTES,
    /** A wallpaper picked on the TV ("none" included) overrides the collection. */
    val onTvChoice: String? = null,
)

@Serializable
data class WallpaperSettings(
    val enabled: Boolean = true,
    val rotationMinutes: Int = 60,
    val order: String = WallpaperStore.ORDER_SHUFFLE,
    val dim: Int = 0,
)

@Serializable
data class WallpaperUpload(
    val added: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

@Serializable
data class CodeResponse(val code: String)

@Serializable
data class PipRequest(val did: String = "")

@Serializable
data class PipCamera(val did: String, val roomName: String)

@Serializable
data class PipState(
    val available: Boolean = false,
    val apiLevel: Int = 0,
    val floating: Boolean = false,
    val cameras: List<PipCamera> = emptyList(),
    val error: String? = null,
)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class ErrorResponse(val error: String)
