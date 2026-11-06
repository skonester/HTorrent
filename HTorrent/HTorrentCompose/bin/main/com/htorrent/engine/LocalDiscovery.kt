// Kotlin adaptation of rqbit's librqbit_lsd and upnp crates. Apache-2.0.
package com.htorrent.engine

import java.net.*
import java.util.Collections
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

internal class LocalDiscovery(private val session: Session) : AutoCloseable {
    private val socket = MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(6771)); timeToLive = 1 }
    private val worker = daemonPool("htorrent-lsd", 1)
    @Volatile private var closed = false
    init {
        interfaces().forEach { nic -> groups.forEach { group -> runCatching { socket.joinGroup(InetSocketAddress(group, 6771), nic) } } }
        worker.execute {
            while (!closed) try {
                val packet = DatagramPacket(ByteArray(4096), 4096)
                socket.receive(packet)
                val text = packet.data.copyOf(packet.length).toString(Charsets.US_ASCII)
                if (!text.startsWith("BT-SEARCH * HTTP/1.1\r\n")) continue
                val headers = text.lineSequence().drop(1).mapNotNull {
                    if (':' !in it) null else it.substringBefore(':').lowercase() to it.substringAfter(':').trim()
                }.toList()
                if (headers.any { it.first == "cookie" && it.second == cookie }) continue
                val port = headers.firstOrNull { it.first == "port" }?.second?.toIntOrNull() ?: continue
                if (port !in 1..65535) continue
                headers.filter { it.first == "infohash" }.forEach { (_, hash) ->
                    session.get(hash.lowercase())?.takeIf { it.running && it.metadata?.private == false }?.offerPeer(InetSocketAddress(packet.address, port), "lsd")
                }
            } catch (_: Exception) { }
        }
    }
    override fun close() { closed = true; socket.close(); worker.shutdownNow() }
    companion object {
        private val cookie = randomBytes(8).hex()
        private val groups = listOf("239.192.152.143", "ff15::efc0:988f").map(InetAddress::getByName)
        private fun interfaces() = Collections.list(NetworkInterface.getNetworkInterfaces()).filter { runCatching { it.isUp && it.supportsMulticast() && !it.isLoopback }.getOrDefault(false) }
        fun announce(hash: ByteArray, port: Int) {
            interfaces().forEach { nic -> groups.forEach { group -> runCatching {
                MulticastSocket().use { socket ->
                    socket.timeToLive = 1; socket.networkInterface = nic
                    val host = if (group is Inet6Address) "[${group.hostAddress}]:6771" else "${group.hostAddress}:6771"
                    val bytes = "BT-SEARCH * HTTP/1.1\r\nHost: $host\r\nPort: $port\r\nInfohash: ${hash.hex()}\r\ncookie: $cookie\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    socket.send(DatagramPacket(bytes, bytes.size, group, 6771))
                }
            } } }
        }
    }
}

internal class PortMapping(private val tcpPort: Int, private val udpPort: Int?) : AutoCloseable {
    private val worker = daemonScheduler("htorrent-upnp")
    @Volatile private var closed = false
    private data class Service(val url: URL, val type: String, val localIp: String)
    private var service: Service? = null
    init {
        worker.scheduleWithFixedDelay({ runCatching {
            if (service == null) service = discover()
            val found = service ?: return@runCatching
            if (!closed) {
                map(found, tcpPort, "TCP")
                udpPort?.let { map(found, it, "UDP") }
            }
        } }, 0, 30, TimeUnit.SECONDS)
    }
    private fun discover(): Service? {
        DatagramSocket().use { socket ->
            socket.soTimeout = 2000
            val request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n".toByteArray()
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
            val deadline = System.currentTimeMillis() + 2500
            while (!closed && System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(ByteArray(8192), 8192)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { return null }
                val location = packet.data.copyOf(packet.length).toString(Charsets.UTF_8).lineSequence()
                    .firstOrNull { it.startsWith("location:", true) }?.substringAfter(':')?.trim() ?: continue
                val url = URL(location)
                if (url.protocol !in listOf("http", "https") || InetAddress.getByName(url.host) != packet.address) continue
                val connection = url.openConnection().apply { connectTimeout = 2000; readTimeout = 2000 }
                val xml = connection.getInputStream().use { it.readNBytes(1024 * 1024) }
                val factory = DocumentBuilderFactory.newInstance().apply {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                    isXIncludeAware = false; isExpandEntityReferences = false
                }
                val document = factory.newDocumentBuilder().parse(xml.inputStream())
                val base = document.getElementsByTagName("URLBase").item(0)?.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let(::URL) ?: url
                val services = document.getElementsByTagName("service")
                for (index in 0 until services.length) {
                    val element = services.item(index) as org.w3c.dom.Element
                    val type = element.getElementsByTagName("serviceType").item(0)?.textContent ?: continue
                    if (!type.contains(Regex("service:WAN(IP|PPP)Connection:[12]$"))) continue
                    val control = element.getElementsByTagName("controlURL").item(0)?.textContent ?: continue
                    val target = URL(base, control)
                    if (InetAddress.getByName(target.host) != packet.address) continue
                    val local = DatagramSocket().use { route -> route.connect(packet.address, 1900); route.localAddress.hostAddress }
                    return Service(target, type, local)
                }
            }
        }
        return null
    }
    private fun map(service: Service, port: Int, protocol: String) {
        val body = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:AddPortMapping xmlns:u="${service.type}"><NewRemoteHost></NewRemoteHost><NewExternalPort>$port</NewExternalPort><NewProtocol>$protocol</NewProtocol><NewInternalPort>$port</NewInternalPort><NewInternalClient>${service.localIp}</NewInternalClient><NewEnabled>1</NewEnabled><NewPortMappingDescription>HTorrent</NewPortMappingDescription><NewLeaseDuration>60</NewLeaseDuration></u:AddPortMapping></s:Body></s:Envelope>"""
        val connection = service.url.openConnection() as HttpURLConnection
        connection.connectTimeout = 2000; connection.readTimeout = 2000; connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
        connection.setRequestProperty("SOAPAction", "\"${service.type}#AddPortMapping\"")
        try { connection.outputStream.use { it.write(body.toByteArray()) }; require(connection.responseCode in 200..299) }
        finally { connection.disconnect() }
    }
    override fun close() { closed = true; worker.shutdownNow() } // rqbit uses expiring leases; renewal stops at shutdown.
}
