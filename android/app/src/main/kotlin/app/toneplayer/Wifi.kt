package app.toneplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

object Wifi {
    /**
     * Route our traffic over Wi-Fi even when the phone prefers mobile data (a Wi-Fi network
     * without internet is otherwise passed over). Returns the Wi-Fi network, if any.
     */
    @Suppress("DEPRECATION")
    fun bind(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Wi-Fi Direct (P2P) also reports the Wi-Fi transport even when it is down, so require a
        // real IPv4 address: that is the network actually carrying our traffic.
        val net = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                cm.getLinkProperties(n)?.linkAddresses?.any { it.address is Inet4Address } == true
        }
        if (net != null) cm.bindProcessToNetwork(net)
        return net
    }
}
