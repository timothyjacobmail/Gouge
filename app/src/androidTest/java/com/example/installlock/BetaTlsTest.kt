package com.example.installlock

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import java.net.NetworkInterface
import javax.net.ssl.SSLServerSocket

class BetaTlsTest {
    @Test fun actualAndroidKeystoreTlsHandshakeRequiresExactCertificatePin() {
        val host=NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address && BetaLanProtocol.privateAddress(it) }
        assumeTrue("LAN address needed for TLS test",host != null)
        val context=BetaLanService().tlsContext()
        val pin=BetaLanService.fingerprint
        val server=context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        server.soTimeout=10000
        val errors=java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val thread=Thread {
            repeat(2) {
                try { (server.accept() as javax.net.ssl.SSLSocket).use { socket ->
                    socket.soTimeout=5000; socket.startHandshake()
                    val r=JSONObject(BetaLanProtocol.read(java.io.DataInputStream(socket.inputStream)))
                    BetaLanProtocol.write(java.io.DataOutputStream(socket.outputStream),JSONObject().put("echo",r.getString("test")).toString())
                } } catch (e: Exception) { errors.add(e) }
            }
        }.apply { start() }
        try {
            val result=try { BetaLanClient.send(host!!.hostAddress!!,pin,JSONObject().put("test","pinned"),server.localPort) }
                catch (e: Exception) { throw AssertionError("TLS server errors: ${errors.joinToString { it.stackTraceToString() }}",e) }
            assertEquals("pinned",result.getString("echo"))
            try { BetaLanClient.send(host.hostAddress!!,"0".repeat(64),JSONObject().put("test","rejected"),server.localPort); fail("Wrong TLS pin accepted") } catch (_: javax.net.ssl.SSLException) {}
        } finally { thread.join(11000); server.close() }
        assertEquals(1,errors.size)
    }
}
