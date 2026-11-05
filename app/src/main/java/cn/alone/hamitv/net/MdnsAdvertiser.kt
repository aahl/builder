package cn.alone.hamitv.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

/**
 * Advertises the ESPHome API over mDNS so Home Assistant can offer the device
 * under "Discovered" instead of asking for an IP.
 *
 * HA's zeroconf flow only requires the `mac` TXT record (it aborts without it)
 * and matches the `_esphomelib._tcp.local.` service type; the remaining records
 * mirror what ESPHome publishes so the entry looks like a normal node.
 */
class MdnsAdvertiser(
    private val context: Context,
    private val name: String,
    private val port: Int,
    private val mac: String,
    private val version: String,
    private val log: (String) -> Unit = {},
) {

    private val manager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    var registered: Boolean = false
        private set

    fun start() {
        val manager = manager ?: return
        stop()

        val info = NsdServiceInfo().apply {
            serviceName = name.take(48)
            serviceType = SERVICE_TYPE
            // Qualified: NsdServiceInfo exposes a `port` property of its own,
            // so a bare `port` here would read back the (zero) current value.
            setPort(this@MdnsAdvertiser.port)
            setAttribute("mac", mac.replace(":", "").lowercase())
            setAttribute("friendly_name", name)
            setAttribute("version", version)
            setAttribute("platform", "Android")
            setAttribute("board", Build.MODEL ?: "Android TV")
            setAttribute("network", networkKind())
        }

        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                registered = true
                log("mdns: registered '${info.serviceName}' on $port")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                registered = false
                log("mdns: registration failed ($errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                registered = false
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                registered = false
            }
        }

        listener = registration
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        }.onFailure { log("mdns: ${it.message}") }
    }

    fun stop() {
        listener?.let { runCatching { manager?.unregisterService(it) } }
        listener = null
        registered = false
    }

    private fun networkKind(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "ethernet"
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "ethernet"
        return if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "wifi" else "ethernet"
    }

    companion object {
        const val SERVICE_TYPE = "_esphomelib._tcp"
    }
}
