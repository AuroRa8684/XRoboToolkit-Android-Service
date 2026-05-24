package com.xrobotoolkit.androidservice.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

data class InterfaceIpv4Info(
    val interfaceName: String,
    val address: Inet4Address,
    val prefixLength: Int,
    val broadcastAddress: InetAddress?,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val isVirtual: Boolean
)

data class SelectedInterface(
    val interfaceName: String,
    val localAddress: InetAddress,
    val broadcastAddress: InetAddress
)

class NetworkInterfaceSelector {
    private val deniedNameHints = listOf("docker", "veth", "lo", "tun", "tap")

    fun listAllIpv4Interfaces(): List<InterfaceIpv4Info> {
        val out = mutableListOf<InterfaceIpv4Info>()
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (ni in interfaces) {
            val interfaceAddresses = ni.interfaceAddresses ?: continue
            for (ia in interfaceAddresses) {
                val addr = ia.address as? Inet4Address ?: continue
                val prefixLength = ia.networkPrefixLength.toInt()
                val broadcast = ia.broadcast ?: computeBroadcast(addr, prefixLength)
                out.add(
                    InterfaceIpv4Info(
                        interfaceName = ni.name,
                        address = addr,
                        prefixLength = prefixLength,
                        broadcastAddress = broadcast,
                        isUp = ni.isUp,
                        isLoopback = ni.isLoopback,
                        isVirtual = ni.isVirtual
                    )
                )
            }
        }
        return out
    }

    fun selectPreferredInterface(preferredPrefix: String = "192.168.14."): SelectedInterface? {
        val candidates = mutableListOf<SelectedInterface>()
        val interfaceInfos = listAllIpv4Interfaces()
        for (info in interfaceInfos) {
            if (!info.isUp || info.isLoopback || info.isVirtual) continue
            val nameLower = info.interfaceName.lowercase()
            if (deniedNameHints.any { nameLower.contains(it) }) continue
            if (info.address.isLoopbackAddress || info.address.isLinkLocalAddress) continue
            val broadcast = info.broadcastAddress ?: continue
            candidates.add(SelectedInterface(info.interfaceName, info.address, broadcast))
        }

        if (candidates.isEmpty()) return null
        val preferred = candidates.firstOrNull { it.localAddress.hostAddress?.startsWith(preferredPrefix) == true }
        if (preferred != null) return preferred

        val siteLocal = candidates.firstOrNull { it.localAddress.isSiteLocalAddress }
        return siteLocal ?: candidates.first()
    }

    fun formatForDisplay(infos: List<InterfaceIpv4Info>): List<String> {
        return infos.map { info ->
            val nameLower = info.interfaceName.lowercase()
            val denyReason = if (deniedNameHints.any { nameLower.contains(it) }) " [filtered]" else ""
            val broadcast = info.broadcastAddress?.hostAddress ?: "-"
            "${info.interfaceName}: ${info.address.hostAddress}/${info.prefixLength} bcast=$broadcast up=${info.isUp} loop=${info.isLoopback} virtual=${info.isVirtual}$denyReason"
        }
    }

    private fun computeBroadcast(addr: Inet4Address, prefixLength: Int): InetAddress? {
        if (prefixLength !in 0..32) return null
        val ipBytes = addr.address
        var ip = 0
        for (b in ipBytes) {
            ip = (ip shl 8) or (b.toInt() and 0xFF)
        }
        val mask = if (prefixLength == 0) 0 else (-1 shl (32 - prefixLength))
        val broadcast = ip and mask or mask.inv()
        val out = byteArrayOf(
            ((broadcast ushr 24) and 0xFF).toByte(),
            ((broadcast ushr 16) and 0xFF).toByte(),
            ((broadcast ushr 8) and 0xFF).toByte(),
            (broadcast and 0xFF).toByte()
        )
        return InetAddress.getByAddress(out)
    }
}
