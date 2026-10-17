package com.archery.delaycam.viewer

/** Select advertised LAN addresses, never an upstream cellular/VPN address. */
object LanAddressPolicy {
    data class Interface(val name: String, val addresses: List<String>, val up: Boolean = true, val loopback: Boolean = false)
    // Android CLAT interfaces can be named v4-rmnet_data0; startsWith("rmnet") misses them.
    private val upstreamName = Regex("rmnet|ccmni|ccemni|pdp|wwan|cellular|clat|ipsec|(^|[-_])(tun|tap|wg|ppp)([0-9]|$)", RegexOption.IGNORE_CASE)
    private val hotspotName = Regex("^(ap[0-9_-]|ap_br_|swlan|softap|br[-_])", RegexOption.IGNORE_CASE)

    fun select(interfaces: List<Interface>, blockedNames: Set<String> = emptySet(),
        blockedAddresses: Set<String> = emptySet(), lanNames: Set<String> = emptySet()): List<String> = interfaces
        .filter { it.up && !it.loopback && it.name !in blockedNames && !upstreamName.containsMatchIn(it.name) }
        .sortedWith(compareBy<Interface> {
            when {
                hotspotName.containsMatchIn(it.name) -> 0
                it.name in lanNames -> 1
                it.name.startsWith("wlan", ignoreCase = true) -> 2
                it.name.startsWith("eth", ignoreCase = true) -> 3
                else -> 4
            }
        }.thenBy { it.name })
        .flatMap { it.addresses }
        .filter { isPrivateIpv4(it) && it !in blockedAddresses }
        .distinct()

    private fun isPrivateIpv4(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        if (octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || octets[0] == 172 && octets[1] in 16..31 ||
            octets[0] == 192 && octets[1] == 168
    }
}
