package com.example.installlock

import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException

/** Bounds bytes consumed by the reader; socket/TLS buffering and headers are outside this count. */
internal class LimitedNetworkInput(input: InputStream, private val limit: Int, private val received: (Int) -> Unit) : FilterInputStream(input) {
    private var used = 0
    override fun read(): Int {
        if (used >= limit) throw IOException("Listing transfer limit reached")
        val b = `in`.read()
        if (b >= 0) { used++; received(1) }
        return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (used >= limit) throw IOException("Listing transfer limit reached")
        val n = `in`.read(b,off,minOf(len,limit-used))
        if (n > 0) { used+=n; received(n) }
        return n
    }
    override fun skip(n: Long): Long { // No bypass of byte accounting.
        val buffer=ByteArray(4096); var skipped=0L
        while (skipped<n) { val count=read(buffer,0,minOf(buffer.size.toLong(),n-skipped).toInt()); if (count<0) break; skipped+=count }
        return skipped
    }
}

internal object PlayListingReader {
    const val MAX_WIRE_BYTES = 512 * 1024
    const val MAX_HTML_BYTES = 4 * 1024 * 1024
    private val metadataType=Regex("""\btype\s*=\s*["']application/ld\+json["']""",RegexOption.IGNORE_CASE)
    fun category(input: InputStream, parse: (String) -> String?): String? {
        val buffer=ByteArray(8192)
        val pending=StringBuilder()
        var total=0
        var capturing=false
        var skipping=false
        val deadline=System.nanoTime()+10_000_000_000L
        while (total<MAX_HTML_BYTES && System.nanoTime()<deadline && !Thread.currentThread().isInterrupted) {
            val n=input.read(buffer,0,minOf(buffer.size,MAX_HTML_BYTES-total))
            if (n<0) return null
            total+=n; pending.append(String(buffer,0,n,Charsets.UTF_8))
            // Scan once and retain only structured metadata. Large inline JS/images are discarded,
            // rather than repeatedly parsing/copying a multi-megabyte HTML page on every chunk.
            while (true) {
                if (capturing || skipping) {
                    val end=pending.indexOf("</script",ignoreCase=true)
                    if (end<0) {
                        if (skipping && pending.length>10) pending.delete(0,pending.length-10)
                        if (capturing && pending.length>256*1024) return null
                        break
                    }
                    val close=pending.indexOf(">",end)
                    if (close<0) break
                    if (capturing) parse(pending.substring(0,close+1))?.let { return it }
                    pending.delete(0,close+1); capturing=false; skipping=false
                } else {
                    val start=pending.indexOf("<script",ignoreCase=true)
                    if (start<0) { if(pending.length>8) pending.delete(0,pending.length-8); break }
                    if (start>0) pending.delete(0,start)
                    val end=pending.indexOf(">")
                    if (end<0) { if(pending.length>8192) return null; break }
                    if (metadataType.containsMatchIn(pending.substring(0,end+1))) capturing=true
                    else { pending.delete(0,end+1); skipping=true }
                }
            }
        }
        return null
    }
}
