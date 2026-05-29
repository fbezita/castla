package com.castla.mirror.network

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class CastlaVpnService : VpnService() {

    companion object {
        private const val TAG = "CastlaVpnService"
        
        // Virtual VPN Configurations
        private const val VPN_ADDRESS = "100.99.9.9"
        private const val DNS_SERVER = "1.1.1.1"
        // Keep the virtual IP for internal/self tests.
        private const val TARGET_DOMAIN_IP = "100.99.9.9"

        // TEST MODE: answer relay.castla.fbezita.com with the phone's hotspot/LAN IP.
        // Set this to "" to auto-detect a non-loopback, non-tun IPv4 address.
        // Current confirmed working address from PC: https://192.168.50.34:9090
        private const val FORCED_RELAY_DNS_IP = "192.168.50.34"

        private const val ROUTE_SUBNET = "100.99.9.9"
        
        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17
        
        // Public IP Route Hijack Configurations
        private const val USE_ROUTE_HIJACK_MODE = false
        private const val PRIMARY_RELAY_DOMAIN = "relay.castla.fbezita.com"
        private val FALLBACK_RELAY_IPS = listOf("100.99.9.9")
        
        // Option to capture hotspot traffic using a full-tunnel fallback
        private const val USE_FULL_TUNNEL = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tcpRelay: TunTcpRelay? = null
    private var vpnThread: Thread? = null

    // Standalone DNS server for hotspot clients.
    // This is separate from VpnService TUN DNS interception.
    // PC/Tesla can query Android phone directly on UDP/53.
    private var standaloneDnsSocket: DatagramSocket? = null
    private var standaloneDnsThread: Thread? = null
    
    // Active hijacked public relay IPs for matching TCP packets in Route Hijack Mode
    private val activeHijackedIps = java.util.concurrent.CopyOnWriteArraySet<String>()
    
    @Volatile
    private var running = false

    private val dnsExecutorCounter = AtomicInteger(0)
    private val dnsExecutor: ExecutorService = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "dns-proxy-pool-${dnsExecutorCounter.incrementAndGet()}").apply { isDaemon = true }
    }

    // Dynamic DNS diagnostic counters
    private val dnsReceivedCount = AtomicInteger(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand() - Starting Castla VPN Service")
        
        if (vpnInterface != null) {
            Log.i(TAG, "VPN is already running. Re-arming interfaces.")
            stopVpn()
        }

        startVpn()
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy() - Stopping Castla VPN Service")
        stopVpn()
        dnsExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startVpn() {
        try {
            // Dynamic DNS resolution of target domains to hijack public IP routes
            activeHijackedIps.clear()
            if (USE_ROUTE_HIJACK_MODE) {
                val resolveThread = thread(start = true) {
                    try {
                        val addrs = java.net.InetAddress.getAllByName(PRIMARY_RELAY_DOMAIN)
                        addrs.forEach { activeHijackedIps.add(it.hostAddress) }
                        Log.i(TAG, "🌍 Dynamic DNS resolve success for primary domain $PRIMARY_RELAY_DOMAIN: $activeHijackedIps")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Failed dynamic DNS resolution for domain $PRIMARY_RELAY_DOMAIN. Using fallback IPs.")
                    }
                }
                resolveThread.join(2000) // Bound DNS resolution to maximum 2 seconds to prevent interface freeze
            }

            if (activeHijackedIps.isEmpty()) {
                activeHijackedIps.addAll(FALLBACK_RELAY_IPS)
            }

            val builder = Builder()
                .setSession("Castla VPN")
                .setMtu(1500)
                .addAddress(VPN_ADDRESS, 32)
                .addDnsServer(DNS_SERVER)

            // Exclude our own application from the VPN to prevent network loops and allow cert synchronization
            try {
                builder.addDisallowedApplication(packageName)
                Log.i(TAG, "🚫 Disallowed our own application ($packageName) from VPN to prevent deadlock")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to disallow application: ${e.message}")
            }

            // Route configuration according to Full Tunnel Option
            if (USE_FULL_TUNNEL) {
                try {
                    builder.addRoute("0.0.0.0", 0)
                    builder.addRoute("::", 0) // Block/route IPv6 traffic as well
                    Log.i(TAG, "🌐 [FULL TUNNEL] Enforcing global routing (0.0.0.0/0 and ::/0)")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to set Full Tunnel routes: ${e.message}")
                    // Fallback to local subnet if full tunnel establishment fails
                    builder.addRoute("100.99.9.0", 24)
                }
            } else {
                // Route the virtual IP subnet to local interface to safely pull traffic into TUN
                try {
                    builder.addRoute("100.99.9.0", 24)
                    builder.addRoute("192.168.0.0", 16) // Capture Wi-Fi LAN DNS/tethering routes
                    builder.addRoute("10.0.0.0", 8)     // Capture Hotspot DNS/AP routes
                    builder.addRoute("172.16.0.0", 12)  // Capture standard private network scopes
                    Log.i(TAG, "🎯 Added private subnet routes to intercept all tethering/DNS forwarding packets")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to set subnet route: ${e.message}")
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface (ParcelFileDescriptor is null)")
                return
            }

            val fd = vpnInterface!!.fileDescriptor
            tcpRelay = TunTcpRelay(fd, 9090)
            startStandaloneDnsServer()
            
            running = true
            vpnThread = thread(name = "CastlaVpnReader", isDaemon = true) {
                runVpnLoop(fd)
            }

            Log.i(TAG, "🚀 VPN established and single reader thread started successfully in WebAA/TeslAA mode with local IP $VPN_ADDRESS")
        } catch (e: Exception) {
            Log.e(TAG, "Error establishing Castla VPN service", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        running = false
        stopStandaloneDnsServer()
        vpnThread?.interrupt()
        vpnThread = null

        tcpRelay?.stop()
        tcpRelay = null

        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing VPN interface descriptor", e)
        }
        vpnInterface = null
        Log.i(TAG, "VPN service successfully dismantled")
    }

    /**
     * Single Reader Loop for VPN TUN FileDescriptor.
     * Sole reader of raw IP packets to guarantee zero race conditions and single-packet dispatching.
     */
    private fun runVpnLoop(fd: FileDescriptor) {
        val input = FileInputStream(fd)
        val buf = ByteArray(32767)
        
        Log.i(TAG, "Entering Single Reader Packet Dispatch Loop")
        
        try {
            while (running) {
                val len = input.read(buf)
                if (len < 0) {
                    Log.i(TAG, "TUN interface reached EOF")
                    break
                }
                if (len >= 40) { // Minimum IPv4 header (20) + transport header (20)
                    try {
                        processIpPacket(buf, len)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error processing dispatched packet: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e(TAG, "Critical failure in VPN single reader thread", e)
            }
        } finally {
            try { input.close() } catch (_: Exception) {}
        }
    }

    private fun processIpPacket(buf: ByteArray, len: Int) {
        val ver = (buf[0].toInt() ushr 4) and 0xF
        if (ver != 4) return // IPv4 only

        val ihl = (buf[0].toInt() and 0xF) * 4
        if (ihl < 20 || ihl > len) return

        val proto = buf[9].toInt() and 0xFF
        val srcIp = i32(buf, 12)
        val dstIp = i32(buf, 16)

        // 9 & 10. Log all raw IP packets for hotspot/tethering diagnostic verification (supports 192.168.43.x / 192.168.49.x / 192.168.50.x)
        Log.d(TAG, "TUN packet: src=${ipStr(srcIp)} dst=${ipStr(dstIp)} proto=$proto")

        if (proto == PROTO_UDP) {
            val t = ihl
            if (t + 8 > len) return
            val srcPort = u16(buf, t)
            val dstPort = u16(buf, t + 2)
            val udpLen = u16(buf, t + 4)

            // DNS intercept: UDP, Destination Port is 53 (Intercept all DNS queries for dynamic local redirection)
            if (dstPort == 53) {
                // 1. Counting received UDP 53 DNS packets for Private DNS / Chrome Secure DNS diagnostic purposes
                val count = dnsReceivedCount.incrementAndGet()
                Log.d(TAG, "UDP 53 DNS packet received: count=$count src=${ipStr(srcIp)} dst=${ipStr(dstIp)}")

                val payStart = t + 8
                val payLen = minOf(udpLen - 8, len - payStart).coerceAtLeast(0)
                if (payLen > 0) {
                    handleDnsQuery(buf, payStart, payLen, srcIp, srcPort, dstIp)
                }
            }
        } else if (proto == PROTO_TCP) {
            val t = ihl
            var srcPort = -1
            var dstPort = -1
            var flagsStr = "UNKNOWN"

            // Safe parsing of TCP ports
            if (t + 4 <= len) {
                srcPort = u16(buf, t)
                dstPort = u16(buf, t + 2)
            }

            // Safe parsing of TCP flags
            if (t + 14 <= len) {
                val flagsVal = buf[t + 13].toInt() and 0x3F
                val flagList = mutableListOf<String>()
                if (flagsVal and 0x02 != 0) flagList += "SYN"
                if (flagsVal and 0x10 != 0) flagList += "ACK"
                if (flagsVal and 0x01 != 0) flagList += "FIN"
                if (flagsVal and 0x04 != 0) flagList += "RST"
                if (flagsVal and 0x08 != 0) flagList += "PSH"
                flagsStr = flagList.joinToString("|")
            }

            // Unconditionally log all TCP packets entering the TUN with requested format
            Log.i(
                "CastlaVpnService",
                "TCP packet src=${ipStr(srcIp)}:$srcPort dst=${ipStr(dstIp)}:$dstPort flags=$flagsStr"
            )

            // TCP Redirect: matches virtual IP (100.99.9.9) on port 9090 (MirrorServer)
            if (dstPort == 9090 && ipStr(dstIp) == VPN_ADDRESS) {
                tcpRelay?.injectPacket(buf, len)
            }
        }
    }

    private fun handleDnsQuery(buf: ByteArray, off: Int, len: Int, clientIp: Int, clientPort: Int, dstIp: Int) {
        val dnsPayload = buf.copyOfRange(off, off + len)
        
        // Dispatch DNS processing to a concurrent threadpool to keep the main TUN reader unblocked
        dnsExecutor.execute {
            try {
                val qNamePair = parseDomainName(dnsPayload, 0)
                if (qNamePair != null) {
                    val qName = qNamePair.first
                    val qNameEnd = qNamePair.second
                    
                    // Parse QTYPE and QCLASS from DNS payload to determine query record type
                    var qType = 1
                    if (qNameEnd + 2 <= dnsPayload.size) {
                        qType = ((dnsPayload[qNameEnd].toInt() and 0xFF) shl 8) or (dnsPayload[qNameEnd + 1].toInt() and 0xFF)
                    }
                    
                    val qTypeStr = when (qType) {
                        1 -> "A"
                        12 -> "PTR"
                        28 -> "AAAA"
                        65 -> "HTTPS"
                        else -> qType.toString()
                    }
                    
                    Log.i(TAG, "DNS query: domain=$qName, qtype=$qTypeStr ($qType) from=${ipStr(clientIp)}:$clientPort dst=${ipStr(dstIp)}")
                    
                    val isReverseDns = qName.endsWith("in-addr.arpa", ignoreCase = true) ||
                                       qName.endsWith("ip6.arpa", ignoreCase = true)
                    
                    // Intercept and poison DoH (DNS-over-HTTPS) domains to force standard UDP 53 DNS fallback
                    val isDohDomain = qName.equals("dns.google", ignoreCase = true) ||
                                      qName.equals("cloudflare-dns.com", ignoreCase = true) ||
                                      qName.endsWith("dns.google", ignoreCase = true) ||
                                      qName.endsWith("cloudflare-dns.com", ignoreCase = true) ||
                                      qName.contains("dns-query", ignoreCase = true) ||
                                      qName.equals("dns.quad9.net", ignoreCase = true) ||
                                      qName.equals("doh.opendns.com", ignoreCase = true) ||
                                      qName.equals("doh.pub", ignoreCase = true) ||
                                      qName.equals("dns.alidns.com", ignoreCase = true)

                    if (isDohDomain) {
                        Log.i(TAG, "🔒 [DoH Block] Intercepted DoH domain: $qName -> Sending NXDOMAIN to force standard UDP 53 fallback!")
                        val responsePayload = buildDnsResponseNodata(dnsPayload, rcode = 3)
                        sendUdpPacket(ipStr(dstIp), 53, ipStr(clientIp), clientPort, responsePayload)
                        return@execute
                    }

                    // Intercept ONLY the exact relay target domain
                    val isTargetDomain = qName.equals("relay.castla.fbezita.com", ignoreCase = true)

                    if (isTargetDomain) {
                        val responseIp = getRelayDnsResponseIp()
                        if (qType == 1) { // A (IPv4) query -> return target IPv4 (Dynamic Local/Virtual IP)
                            Log.i(TAG, "DNS override hit: $qName -> $responseIp (A)")
                            Log.i(TAG, "DNS override hit! qname=$qName, qtype=A (1), responseIp=$responseIp")
                            val responsePayload = buildDnsResponseA(dnsPayload, responseIp)
                            sendUdpPacket(ipStr(dstIp), 53, ipStr(clientIp), clientPort, responsePayload)
                        } else if (qType == 28 || qType == 65) { // AAAA (IPv6) or HTTPS query -> return NODATA response immediately
                            Log.i(TAG, "DNS override hit: $qName -> NODATA (qtype=$qTypeStr)")
                            Log.i(TAG, "DNS override hit! qname=$qName, qtype=$qTypeStr ($qType), responseIp=NODATA")
                            Log.i(TAG, "DNS override: Sending NODATA response for $qName (qtype=$qTypeStr, $qType) to force client IPv4 fallback")
                            val responsePayload = buildDnsResponseNodata(dnsPayload)
                            sendUdpPacket(ipStr(dstIp), 53, ipStr(clientIp), clientPort, responsePayload)
                        } else {
                            proxyDnsRequest(dnsPayload, clientIp, clientPort, dstIp)
                        }
                    } else if (isReverseDns || qType == 12) {
                        // Immediately respond with NXDOMAIN to local private reverse DNS queries to prevent public DNS lookup timeouts and delays
                        Log.i(TAG, "DNS reverse-lookup override hit: $qName (qtype=$qTypeStr, $qType) -> NXDOMAIN")
                        val responsePayload = buildDnsResponseNodata(dnsPayload, rcode = 3)
                        sendUdpPacket(ipStr(dstIp), 53, ipStr(clientIp), clientPort, responsePayload)
                    } else {
                        // Standard Proxy fallback: redirect non-target queries to public DNS (8.8.8.8)
                        proxyDnsRequest(dnsPayload, clientIp, clientPort, dstIp)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DNS resolution exception", e)
            }
        }
    }

    private fun proxyDnsRequest(dnsPayload: ByteArray, clientIp: Int, clientPort: Int, originalDstIp: Int = ipToInt(DNS_SERVER)) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            protect(socket) // Shield socket to bypass VPN routing loop back to the actual WAN interface
            socket.soTimeout = 3000

            val dnsServerAddr = InetAddress.getByName("8.8.8.8")
            val sendPacket = DatagramPacket(dnsPayload, dnsPayload.size, dnsServerAddr, 53)
            socket.send(sendPacket)

            val recvBuf = ByteArray(2048)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)

            val responsePayload = recvPacket.data.copyOfRange(0, recvPacket.length)
            sendUdpPacket(ipStr(originalDstIp), 53, ipStr(clientIp), clientPort, responsePayload)
        } catch (e: Exception) {
            Log.w(TAG, "Failed proxying DNS query: ${e.message}")
        } finally {
            socket?.close()
        }
    }

    private fun parseDomainName(dns: ByteArray, offset: Int): Pair<String, Int>? {
        if (dns.size < offset + 12) return null
        var pos = offset + 12 // QNAME starts after the 12-byte DNS Header
        val sb = StringBuilder()
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) {
                pos++
                break
            }
            if (pos + 1 + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append(".")
            sb.append(String(dns, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
        }
        return Pair(sb.toString(), pos)
    }

    /**
     * Builds standard DNS response payload mapping requested QNAME to Target IPv4.
     * Uses classic DNS compression pointers.
     */
    private fun buildDnsResponseA(query: ByteArray, targetIp: String): ByteArray {
        val qNamePair = parseDomainName(query, 0) ?: return ByteArray(0)
        val qNameEnd = qNamePair.second
        
        // DNS Payload Size: Query Question Section Size + 16 bytes for Answer Resource Record
        val questionSize = qNameEnd
        val response = ByteArray(questionSize + 16)
        
        // Copy Header & Question Sections directly from Query
        System.arraycopy(query, 0, response, 0, questionSize)
        
        // Modify DNS Header flags for response
        // Keep original RD, Opcode, CD, AD flags intact by utilizing precise bitwise masking
        response[2] = (response[2].toInt() or 0x80).toByte()
        response[3] = ((response[3].toInt() and 0xF0) or 0x80).toByte()
        
        // QDCOUNT = 1, ANCOUNT = 1
        response[4] = 0x00.toByte()
        response[5] = 0x01.toByte() // 1 question
        response[6] = 0x00.toByte()
        response[7] = 0x01.toByte() // 1 answer
        
        // Build Answer Record Section (starts at questionSize)
        var p = questionSize
        
        // Compression pointer back to QNAME in Question Section (always starts at 12)
        response[p++] = 0xC0.toByte()
        response[p++] = 0x0C.toByte()
        
        // TYPE = A (0x0001)
        response[p++] = 0x00.toByte()
        response[p++] = 0x01.toByte()
        
        // CLASS = IN (0x0001)
        response[p++] = 0x00.toByte()
        response[p++] = 0x01.toByte()
        
        // TTL = 60 seconds (0x0000003C)
        response[p++] = 0x00.toByte()
        response[p++] = 0x00.toByte()
        response[p++] = 0x00.toByte()
        response[p++] = 0x3C.toByte()
        
        // RDLENGTH = 4 bytes (IPv4)
        response[p++] = 0x00.toByte()
        response[p++] = 0x04.toByte()
        
        // RDATA (IP Bytes mapping)
        val ipParts = targetIp.split(".")
        for (i in 0..3) {
            response[p++] = (ipParts[i].toInt() and 0xFF).toByte()
        }
        
        return response
    }

    /**
     * Builds a standard DNS response with No Error and No Answers (NODATA).
     * Clones the entire query payload to preserve EDNS0 OPT RR and additional sections exactly,
     * ensuring strict OS DNS client stacks (e.g., Windows/dnsmasq) accept it without dropping.
     */
    private fun buildDnsResponseNodata(query: ByteArray, rcode: Int = 0): ByteArray {
        val response = query.copyOf()
        if (response.size < 12) return response
        
        // Modify DNS Header flags for response (Response, standard query, recursion available, no error)
        // Keep original RD, Opcode, CD, AD flags intact by utilizing precise bitwise masking
        response[2] = (response[2].toInt() or 0x80).toByte()
        response[3] = ((response[3].toInt() and 0xF0) or 0x80 or (rcode and 0x0F)).toByte()
        
        // ANCOUNT = 0, NSCOUNT = 0 (Clear answers and authority counts, but keep QDCOUNT and ARCOUNT for EDNS0 compliance)
        response[6] = 0x00.toByte()
        response[7] = 0x00.toByte()
        response[8] = 0x00.toByte()
        response[9] = 0x00.toByte()
        
        return response
    }

    private fun sendUdpPacket(srcIp: String, srcPort: Int, dstIp: String, dstPort: Int, payload: ByteArray) {
        val ipTotal = 20 + 8 + payload.size
        val pkt = ByteArray(ipTotal)

        // 1. IP Header (20 bytes)
        pkt[0] = 0x45.toByte() // Version 4, IHL 5
        pkt[1] = 0x00.toByte() // TOS
        w16(pkt, 2, ipTotal)
        w16(pkt, 4, (Math.random() * 0xFFFF).toInt())
        pkt[6] = 0x40.toByte() // DF (Don't Fragment)
        pkt[7] = 0x00.toByte()
        pkt[8] = 64.toByte() // TTL
        pkt[9] = PROTO_UDP.toByte()
        
        val srcIpInt = ipToInt(srcIp)
        val dstIpInt = ipToInt(dstIp)
        w32(pkt, 12, srcIpInt)
        w32(pkt, 16, dstIpInt)
        w16(pkt, 10, ipCksum(pkt, 0, 20))

        // 2. UDP Header (8 bytes)
        val t = 20
        w16(pkt, t, srcPort)
        w16(pkt, t + 2, dstPort)
        val udpLen = 8 + payload.size
        w16(pkt, t + 4, udpLen)

        // 3. Payload (Must copy payload before checksum calculation)
        System.arraycopy(payload, 0, pkt, 20 + 8, payload.size)

        // Calculate and inject correct UDP Checksum for PC tethering environments (Windows OS and driver compliance)
        w16(pkt, t + 6, udpCksum(srcIpInt, dstIpInt, pkt, t, udpLen))

        // Delegate UDP write task to the same shared thread-safe writer in tcpRelay
        tcpRelay?.writePacket(pkt)
    }


    // --- Standalone UDP/53 DNS Server for hotspot clients ---

    private fun startStandaloneDnsServer() {
        if (standaloneDnsThread != null) {
            Log.i(TAG, "Standalone DNS server is already running")
            return
        }

        standaloneDnsThread = thread(name = "CastlaStandaloneDns53", isDaemon = true) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", 53))
                    soTimeout = 1000
                }
                standaloneDnsSocket = socket
                Log.i(TAG, "✅ Standalone DNS server bound on UDP 0.0.0.0:53")

                val buf = ByteArray(1500)
                while (running || vpnInterface != null) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }

                    val dnsPayload = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    handleStandaloneDnsPacket(socket, packet, dnsPayload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to bind/run standalone UDP 53 DNS server", e)
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                if (standaloneDnsSocket === socket) standaloneDnsSocket = null
                Log.i(TAG, "Standalone DNS server stopped")
            }
        }
    }

    private fun stopStandaloneDnsServer() {
        try { standaloneDnsSocket?.close() } catch (_: Exception) {}
        standaloneDnsSocket = null
        standaloneDnsThread?.interrupt()
        standaloneDnsThread = null
    }

    private fun handleStandaloneDnsPacket(
        serverSocket: DatagramSocket,
        clientPacket: DatagramPacket,
        dnsPayload: ByteArray
    ) {
        try {
            val qNamePair = parseDomainName(dnsPayload, 0)
            if (qNamePair == null) {
                Log.w(TAG, "Standalone DNS: failed to parse query from ${clientPacket.address.hostAddress}:${clientPacket.port}")
                return
            }

            val qName = qNamePair.first
            val qNameEnd = qNamePair.second
            val qType = if (qNameEnd + 2 <= dnsPayload.size) {
                ((dnsPayload[qNameEnd].toInt() and 0xFF) shl 8) or
                    (dnsPayload[qNameEnd + 1].toInt() and 0xFF)
            } else 1

            val qTypeStr = when (qType) {
                1 -> "A"
                12 -> "PTR"
                28 -> "AAAA"
                65 -> "HTTPS"
                else -> qType.toString()
            }

            Log.i(
                TAG,
                "Standalone DNS query: domain=$qName qtype=$qTypeStr from=${clientPacket.address.hostAddress}:${clientPacket.port}"
            )

            val responsePayload: ByteArray = when {
                qName.equals(PRIMARY_RELAY_DOMAIN, ignoreCase = true) && qType == 1 -> {
                    val hotspotIp = getActivePhysicalIp()
                    Log.i(TAG, "Standalone DNS override hit: $qName -> $hotspotIp")
                    buildDnsResponseA(dnsPayload, hotspotIp)
                }

                qName.equals(PRIMARY_RELAY_DOMAIN, ignoreCase = true) && (qType == 28 || qType == 65) -> {
                    Log.i(TAG, "Standalone DNS override hit: $qName -> NODATA for qtype=$qTypeStr")
                    buildDnsResponseNodata(dnsPayload)
                }

                qName.endsWith("in-addr.arpa", ignoreCase = true) ||
                    qName.endsWith("ip6.arpa", ignoreCase = true) ||
                    qType == 12 -> {
                    buildDnsResponseNodata(dnsPayload, rcode = 3)
                }

                else -> {
                    proxyDnsPayloadToUpstream(dnsPayload) ?: buildDnsResponseNodata(dnsPayload, rcode = 2)
                }
            }

            val response = DatagramPacket(
                responsePayload,
                responsePayload.size,
                clientPacket.address,
                clientPacket.port
            )
            serverSocket.send(response)
        } catch (e: Exception) {
            Log.w(TAG, "Standalone DNS handling error", e)
        }
    }

    private fun proxyDnsPayloadToUpstream(dnsPayload: ByteArray): ByteArray? {
        var upstream: DatagramSocket? = null
        return try {
            upstream = DatagramSocket()
            protect(upstream)
            upstream.soTimeout = 3000

            val upstreamAddr = InetAddress.getByName("8.8.8.8")
            upstream.send(DatagramPacket(dnsPayload, dnsPayload.size, upstreamAddr, 53))

            val recvBuf = ByteArray(2048)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            upstream.receive(recvPacket)
            recvPacket.data.copyOfRange(0, recvPacket.length)
        } catch (e: Exception) {
            Log.w(TAG, "Standalone DNS upstream proxy failed: ${e.message}")
            null
        } finally {
            try { upstream?.close() } catch (_: Exception) {}
        }
    }

    // --- Packet Crafting Byte Helpers ---

    /**
     * Calculates the standard UDP Checksum over the IPv4 pseudo-header and UDP payload.
     * Complies with RFC 768 to avoid packet drop on strict OS firewall stacks (e.g., Windows).
     */
    private fun udpCksum(srcIp: Int, dstIp: Int, buf: ByteArray, udpOff: Int, udpLen: Int): Int {
        var sum = 0L
        // Pseudo-header elements - enforce unsigned 32-bit conversion to bypass sign extension bugs
        val sIp = srcIp.toLong() and 0xFFFFFFFFL
        val dIp = dstIp.toLong() and 0xFFFFFFFFL
        sum += (sIp ushr 16) and 0xFFFF
        sum += sIp and 0xFFFF
        sum += (dIp ushr 16) and 0xFFFF
        sum += dIp and 0xFFFF
        sum += PROTO_UDP.toLong()
        sum += udpLen.toLong()
        // UDP Segment parsing
        var i = 0
        while (i < udpLen - 1) { sum += u16(buf, udpOff + i); i += 2 }
        if (udpLen % 2 == 1) sum += (buf[udpOff + udpLen - 1].toInt() and 0xFF).toLong() shl 8
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
        val ck = (sum.toInt().inv()) and 0xFFFF
        return if (ck == 0) 0xFFFF else ck
    }
    /**
     * Calculates the standard TCP Checksum over the IPv4 pseudo-header and TCP payload.
     * Complies with RFC 793 to generate valid checksum for PC client IP stack.
     */
    private fun tcpCksum(srcIp: Int, dstIp: Int, buf: ByteArray, tcpOff: Int, tcpLen: Int): Int {
        var sum = 0L
        val sIp = srcIp.toLong() and 0xFFFFFFFFL
        val dIp = dstIp.toLong() and 0xFFFFFFFFL
        sum += (sIp ushr 16) and 0xFFFF
        sum += sIp and 0xFFFF
        sum += (dIp ushr 16) and 0xFFFF
        sum += dIp and 0xFFFF
        sum += PROTO_TCP.toLong()
        sum += tcpLen.toLong()
        var i = 0
        while (i < tcpLen - 1) { sum += u16(buf, tcpOff + i); i += 2 }
        if (tcpLen % 2 == 1) sum += (buf[tcpOff + tcpLen - 1].toInt() and 0xFF).toLong() shl 8
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
        val ck = (sum.toInt().inv()) and 0xFFFF
        return if (ck == 0) 0xFFFF else ck
    }

    /**
     * Crafts and sends a raw TCP RST|ACK packet to force connection reset
     * on DoH servers, which triggers immediate browser fallback to standard UDP 53.
     */
    private fun sendTcpReset(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, seq: Long, ack: Long) {
        val ipTotal = 20 + 20
        val pkt = ByteArray(ipTotal)

        // 1. IP Header (20 bytes)
        pkt[0] = 0x45.toByte()
        w16(pkt, 2, ipTotal)
        w16(pkt, 4, (Math.random() * 0xFFFF).toInt())
        pkt[6] = 0x40.toByte() // DF
        pkt[8] = 64 // TTL
        pkt[9] = PROTO_TCP.toByte()
        w32(pkt, 12, srcIp)
        w32(pkt, 16, dstIp)
        w16(pkt, 10, ipCksum(pkt, 0, 20))

        // 2. TCP Header (20 bytes)
        val t = 20
        w16(pkt, t, srcPort)
        w16(pkt, t + 2, dstPort)
        w32(pkt, t + 4, seq.toInt())
        w32(pkt, t + 8, ack.toInt())
        pkt[t + 12] = 0x50.toByte() // Data Offset = 5 (20 bytes)
        pkt[t + 13] = 0x14.toByte() // Flags = RST | ACK (0x04 | 0x10 = 0x14)
        w16(pkt, t + 14, 0) // Window Size = 0

        w16(pkt, t + 16, tcpCksum(srcIp, dstIp, pkt, t, 20))

        tcpRelay?.writePacket(pkt)
    }

    private fun ipToInt(ip: String): Int {
        val p = ip.split(".")
        return ((p[0].toInt() and 0xFF) shl 24) or
               ((p[1].toInt() and 0xFF) shl 16) or
               ((p[2].toInt() and 0xFF) shl 8) or
               (p[3].toInt() and 0xFF)
    }

    private fun ipCksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = 0
        while (i < len - 1) { sum += u16(buf, off + i); i += 2 }
        if (len % 2 == 1) sum += (buf[off + len - 1].toInt() and 0xFF).toLong() shl 8
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.toInt().inv()) and 0xFFFF
    }

    private fun i32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
        ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or
        (b[o + 3].toInt() and 0xFF)

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or
        (b[o + 1].toInt() and 0xFF)

    private fun w32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte(); b[o+1] = (v ushr 16).toByte()
        b[o+2] = (v ushr 8).toByte(); b[o+3] = v.toByte()
    }

    private fun w16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte(); b[o+1] = v.toByte()
    }

    private fun ipStr(ip: Int) =
        "${ip ushr 24 and 0xFF}.${ip ushr 16 and 0xFF}.${ip ushr 8 and 0xFF}.${ip and 0xFF}"

    private fun getRelayDnsResponseIp(): String {
        if (FORCED_RELAY_DNS_IP.isNotBlank()) {
            Log.i(TAG, "🌐 DNS test mode: forcing relay.castla.fbezita.com -> $FORCED_RELAY_DNS_IP")
            return FORCED_RELAY_DNS_IP
        }
        return getActivePhysicalIp()
    }

    /**
     * Resolves the active physical IP of the device (Wi-Fi or Hotspot interface).
     * Excludes local loopback, VPN tunnel, and virtual interfaces.
     */
    private fun getActivePhysicalIp(): String {
        val preferred = mutableListOf<Pair<String, String>>()
        val fallback = mutableListOf<Pair<String, String>>()

        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val name = iface.name ?: continue
                if (iface.isLoopback || !iface.isUp) continue

                val lower = name.lowercase()
                if (lower.contains("tun") || lower.contains("ppp") || lower.contains("dummy") ||
                    lower.contains("rmnet") || lower.contains("v4-") || lower.contains("v6-")) {
                    continue
                }

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    val ip = addr.hostAddress ?: continue
                    if (ip.indexOf(':') >= 0) continue // IPv4 only
                    if (ip == VPN_ADDRESS || ip == "127.0.0.1") continue
                    if (ip.startsWith("169.254.")) continue

                    val item = name to ip
                    // Samsung hotspot / Wi-Fi interfaces are commonly wlan*, swlan*, ap*, p2p*.
                    if (lower.startsWith("wlan") || lower.startsWith("swlan") ||
                        lower.startsWith("ap") || lower.startsWith("p2p")) {
                        preferred += item
                    } else {
                        fallback += item
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving active physical IP", e)
        }

        val selected = (preferred.firstOrNull() ?: fallback.firstOrNull())
        if (selected != null) {
            Log.i(TAG, "🔍 Selected relay DNS response IP [${selected.first}]: ${selected.second}")
            return selected.second
        }

        Log.w(TAG, "⚠️ No active physical IP found. Falling back to VPN virtual IP: $VPN_ADDRESS")
        return VPN_ADDRESS
    }
}
