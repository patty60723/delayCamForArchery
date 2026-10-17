package com.archery.delaycam.viewer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.NetworkInterface

/** Hotspot downstream interfaces need enumeration: they may have no ConnectivityManager Network. */
object LanViewerNetwork {
    fun addresses(context: Context): List<String> {
        val blockedNames = mutableSetOf<String>()
        val blockedAddresses = mutableSetOf<String>()
        val lanNames = mutableSetOf<String>()
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching
            manager.allNetworks.forEach { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@forEach
                val properties = manager.getLinkProperties(network) ?: return@forEach
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    properties.interfaceName?.let(blockedNames::add)
                    properties.linkAddresses.mapNotNullTo(blockedAddresses) { it.address.hostAddress }
                } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    properties.interfaceName?.let(lanNames::add)
                }
            }
        }
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { network ->
                runCatching { LanAddressPolicy.Interface(network.name,
                    network.inetAddresses.toList().mapNotNull { it.hostAddress }, network.isUp, network.isLoopback) }.getOrNull()
            }
        }.getOrDefault(emptyList())
        return LanAddressPolicy.select(interfaces, blockedNames, blockedAddresses, lanNames)
    }
}
