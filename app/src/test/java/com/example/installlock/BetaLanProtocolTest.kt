package com.example.installlock

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.InetAddress

class BetaLanProtocolTest {
    private val key = ByteArray(32) { it.toByte() }
    private val body = "{\"op\":\"unlock\",\"profile\":\"study\",\"minutes\":5}"
    @Test fun commandsBindIdentitySequenceTimestampAndExactBody() {
        val mac=BetaLanProtocol.signature(key,"device",1,1000000,body)
        assertTrue(BetaLanProtocol.verify(key,"device",1,1000000,body,mac,1000000))
        assertFalse(BetaLanProtocol.verify(key,"other",1,1000000,body,mac,1000000))
        assertFalse(BetaLanProtocol.verify(key,"device",2,1000000,body,mac,1000000))
        assertFalse(BetaLanProtocol.verify(key,"device",1,1000001,body,mac,1000000))
        assertFalse(BetaLanProtocol.verify(key,"device",1,1000000,body+" ",mac,1000000))
        assertFalse(BetaLanProtocol.verify(key.reversedArray(),"device",1,1000000,body,mac,1000000))
        assertFalse(BetaLanProtocol.verify(key,"device",1,1000000,body,mac,1120001))
        assertFalse(BetaLanProtocol.verify(key,"device",0,1000000,body,mac,1000000))
    }
    @Test fun frameUsesUtf8ByteLengthAndRejectsOversizedOrTruncatedInput() {
        val out=ByteArrayOutputStream(); BetaLanProtocol.write(DataOutputStream(out), "héllo")
        assertEquals("héllo",BetaLanProtocol.read(DataInputStream(ByteArrayInputStream(out.toByteArray()))))
        for (n in listOf(-1,0,32769,Int.MAX_VALUE)) {
            val bad=ByteArrayOutputStream(); DataOutputStream(bad).writeInt(n)
            try { BetaLanProtocol.read(DataInputStream(ByteArrayInputStream(bad.toByteArray()))); fail() } catch (_: IllegalArgumentException) {}
        }
        try { BetaLanProtocol.read(DataInputStream(ByteArrayInputStream(byteArrayOf(0,0,0,2,65)))); fail() } catch (_: EOFException) {}
    }
    @Test fun lanPolicyRejectsPublicMulticastAndLoopbackClients() {
        for (s in listOf("192.168.1.10","10.0.0.1","172.16.3.4","169.254.5.6")) assertTrue(s,BetaLanProtocol.privateAddress(InetAddress.getByName(s)))
        for (s in listOf("127.0.0.1","0.0.0.0","8.8.8.8","224.0.0.1","172.32.0.1","::1")) assertFalse(s,BetaLanProtocol.privateAddress(InetAddress.getByName(s)))
    }
}
