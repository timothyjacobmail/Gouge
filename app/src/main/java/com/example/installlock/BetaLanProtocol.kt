package com.example.installlock

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object BetaLanProtocol {
    const val PORT = 38741
    const val MAX_FRAME = 32768
    fun privateAddress(a: InetAddress) = (a.isSiteLocalAddress || a.isLinkLocalAddress) && !a.isLoopbackAddress && !a.isMulticastAddress
    fun signature(key: ByteArray, id: String, sequence: Long, time: Long, body: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal("$id\n$sequence\n$time\n$body".toByteArray(Charsets.UTF_8))
    fun verify(key: ByteArray, id: String, sequence: Long, time: Long, body: String, mac: ByteArray, now: Long): Boolean =
        sequence > 0 && time in (now - 120_000)..(now + 120_000) && mac.size == 32 && MessageDigest.isEqual(signature(key, id, sequence, time, body), mac)
    fun write(out: DataOutputStream, body: String) { val b = body.toByteArray(Charsets.UTF_8); require(b.size <= MAX_FRAME); out.writeInt(b.size); out.write(b); out.flush() }
    fun read(input: DataInputStream): String { val n = input.readInt(); require(n in 1..MAX_FRAME); return String(ByteArray(n).also { input.readFully(it) }, Charsets.UTF_8) }
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    fun fingerprint(cert: java.security.cert.Certificate) = hex(MessageDigest.getInstance("SHA-256").digest(cert.encoded))
}
