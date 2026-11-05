package ng.anlo.hamitv.net

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** First non-loopback IPv4 address of the device, or 0.0.0.0 when offline. */
fun localIpv4(): String {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
    for (nif in Collections.list(interfaces)) {
        if (!nif.isUp || nif.isLoopback) continue
        for (address in Collections.list(nif.inetAddresses)) {
            if (address is Inet4Address && !address.isLoopbackAddress) {
                return address.hostAddress ?: continue
            }
        }
    }
    return "0.0.0.0"
}
