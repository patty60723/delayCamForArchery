package com.archery.delaycam.viewer

import org.junit.Assert.*
import org.junit.Test

class LanAddressPolicyTest {
    private fun iface(name: String, vararg addresses: String) = LanAddressPolicy.Interface(name, addresses.toList())

    @Test fun cellularClatAddressIsNeverAdvertisedAheadOfHotspot() {
        val result = LanAddressPolicy.select(listOf(
            iface("v4-rmnet_data0", "10.144.217.242"),
            iface("rmnet_data1", "10.1.2.3"),
            iface("tun0", "10.9.0.1"), iface("wg0", "10.10.0.1"),
            iface("wlan1", "192.168.43.1")))
        assertEquals(listOf("192.168.43.1"), result)
    }

    @Test fun transportMetadataBlocksUnknownUpstreamNamesAndTheirAddresses() {
        val result = LanAddressPolicy.select(listOf(iface("vendor_data", "10.144.217.242"),
            iface("vendor_vpn", "10.8.0.2"), iface("shared_alias", "10.144.217.242"),
            iface("wlan0", "172.20.10.1")),
            blockedNames = setOf("vendor_data", "vendor_vpn"), blockedAddresses = setOf("10.144.217.242", "10.8.0.2"))
        assertEquals(listOf("172.20.10.1"), result)
    }

    @Test fun validTenRangeWifiIsAllowedAndHotspotComesBeforeOtherLinks() {
        val result = LanAddressPolicy.select(listOf(iface("eth0", "192.168.1.8"),
            iface("wlan0", "10.144.217.242"), iface("ap_br_wlan1", "192.168.150.1")), lanNames = setOf("eth0", "wlan0"))
        assertEquals(listOf("192.168.150.1", "192.168.1.8", "10.144.217.242"), result)
    }

    @Test fun onlyUsablePrivateIpv4AddressesAreAdvertisedOnce() {
        val result = LanAddressPolicy.select(listOf(
            iface("wlan0", "192.168.1.2", "192.168.1.2", "127.0.0.1", "169.254.1.1", "100.64.0.1", "8.8.8.8", "::1", "2001:db8::1", "172.15.0.1", "172.32.0.1", "10.999.1.1", "10.a.1.1"),
            LanAddressPolicy.Interface("ap0", listOf("192.168.44.1"), up = false),
            LanAddressPolicy.Interface("lo", listOf("10.0.0.1"), loopback = true)))
        assertEquals(listOf("192.168.1.2"), result)
    }

    @Test fun noLanReturnsEmptyRatherThanAnUpstreamFallback() {
        assertTrue(LanAddressPolicy.select(listOf(iface("v4-rmnet_data0", "10.144.217.242"))).isEmpty())
    }
}
