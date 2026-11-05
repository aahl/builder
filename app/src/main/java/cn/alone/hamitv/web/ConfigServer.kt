package cn.alone.hamitv.web

import android.content.Context
import cn.alone.hamitv.config.ConfigStore
import cn.alone.hamitv.net.localIpv4
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
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
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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
) {

    private var engine: ApplicationEngine? = null
    private val limiter = PairingLimiter()
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
                store.deviceName = payload.deviceName.take(32).ifBlank { "MiTV" }
                store.espPort = payload.espPort.coerceIn(1024, 65535)
                if (portChanged) store.webPort = payload.webPort.coerceIn(1024, 65535)
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
                    call.respond(OkResponse())
                } else {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(error))
                }
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

    private fun status() = StatusResponse(
        name = store.deviceName,
        ip = localIpv4(),
        webPort = store.webPort,
        espPort = store.espPort,
        pairingCode = store.pairingCode,
        uptimeSeconds = (System.currentTimeMillis() - startedAt) / 1000,
        android = android.os.Build.VERSION.RELEASE ?: "?",
        model = android.os.Build.MODEL ?: "?",
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
)

@Serializable
data class CodeResponse(val code: String)

@Serializable
data class OkResponse(val ok: Boolean = true)

@Serializable
data class ErrorResponse(val error: String)
