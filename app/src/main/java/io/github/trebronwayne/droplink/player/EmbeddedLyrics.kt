package io.github.trebronwayne.droplink.player

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Lyrics stored inside the audio file itself. Many players do this: the lyrics are a frame of the file's tag.
 * MP3 files carry them in ID3 frames: USLT (plain text) or SYLT (with a time for every line), and some taggers put a
 * whole LRC text inside USLT, which is detected too. FLAC files keep them in a LYRICS comment.
 */
object EmbeddedLyrics {
    private const val MAX_TAG = 12 * 1024 * 1024

    fun read(input: InputStream, displayName: String): Lyrics? = try {
        val head = ByteArray(10)
        val n = readFully(input, head)
        when {
            n == 10 && head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte() -> id3(input, head)
            n >= 4 && head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() && head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> flac(input, head)
            else -> null
        }
    } catch (_: Exception) { null }

    private fun readFully(input: InputStream, buf: ByteArray, offset: Int = 0, len: Int = buf.size - offset): Int {
        var total = 0
        while (total < len) {
            val r = input.read(buf, offset + total, len - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun synchsafe(b: ByteArray, o: Int) = (b[o].toInt() and 0x7F shl 21) or (b[o + 1].toInt() and 0x7F shl 14) or (b[o + 2].toInt() and 0x7F shl 7) or (b[o + 3].toInt() and 0x7F)
    private fun be32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF shl 24) or (b[o + 1].toInt() and 0xFF shl 16) or (b[o + 2].toInt() and 0xFF shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun id3(input: InputStream, head: ByteArray): Lyrics? {
        val version = head[3].toInt()
        val flags = head[5].toInt()
        val size = synchsafe(head, 6)
        if (size <= 0 || size > MAX_TAG) return null
        var tag = ByteArray(size)
        readFully(input, tag)
        if (flags and 0x80 != 0) tag = unsynchronise(tag)
        var pos = 0
        if (flags and 0x40 != 0 && version >= 3 && tag.size >= 4) {
            pos = if (version == 4) synchsafe(tag, 0) else be32(tag, 0) + 4
        }
        var plain: Lyrics? = null
        var synced: Lyrics? = null
        val idLen = if (version == 2) 3 else 4
        val headerLen = if (version == 2) 6 else 10
        while (pos + headerLen <= tag.size) {
            if (tag[pos].toInt() == 0) break
            val id = String(tag, pos, idLen, Charsets.ISO_8859_1)
            val frameSize = when (version) {
                2 -> (tag[pos + 3].toInt() and 0xFF shl 16) or (tag[pos + 4].toInt() and 0xFF shl 8) or (tag[pos + 5].toInt() and 0xFF)
                4 -> synchsafe(tag, pos + 4)
                else -> be32(tag, pos + 4)
            }
            val bodyStart = pos + headerLen
            if (frameSize <= 0 || bodyStart + frameSize > tag.size) break
            val body = tag.copyOfRange(bodyStart, bodyStart + frameSize)
            if (id == "USLT" || id == "ULT") plain = plain ?: unsynced(body)
            if (id == "SYLT" || id == "SLT") synced = synced ?: syncedFrame(body)
            pos = bodyStart + frameSize
        }
        return synced ?: plain
    }

    private fun unsynchronise(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private fun charsetOf(enc: Int) = when (enc) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    /** Index just after the terminator of a string that starts at [from]. */
    private fun skipString(b: ByteArray, from: Int, enc: Int): Int {
        var i = from
        if (enc == 1 || enc == 2) {
            while (i + 1 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i + 2
                i += 2
            }
            return b.size
        }
        while (i < b.size) {
            if (b[i].toInt() == 0) return i + 1
            i++
        }
        return b.size
    }

    private fun unsynced(b: ByteArray): Lyrics? {
        if (b.size < 5) return null
        val enc = b[0].toInt() and 0xFF
        val textStart = skipString(b, 4, enc)
        if (textStart >= b.size) return null
        val text = String(b, textStart, b.size - textStart, charsetOf(enc)).trim('\u0000', ' ', '\n', '\r')
        if (text.isBlank()) return null
        // Some taggers store a whole LRC file here. If it has times, use them.
        return TextParsers.lyrics(text, "lrc", "Lyrics inside the file")
    }

    private fun syncedFrame(b: ByteArray): Lyrics? {
        if (b.size < 7) return null
        val enc = b[0].toInt() and 0xFF
        val format = b[4].toInt() and 0xFF
        if (format != 2) return null // times in MPEG frames cannot be turned into time without decoding the file
        var pos = skipString(b, 6, enc)
        val cues = ArrayList<Pair<Long, String>>()
        while (pos < b.size) {
            val end = skipString(b, pos, enc)
            val termLen = if (enc == 1 || enc == 2) 2 else 1
            val textEnd = (end - termLen).coerceAtLeast(pos)
            val text = String(b, pos, textEnd - pos, charsetOf(enc)).trim('\n', '\r')
            if (end + 4 > b.size) break
            val time = be32(b, end).toLong() and 0xFFFFFFFFL
            if (text.isNotBlank()) cues += time to text
            pos = end + 4
        }
        if (cues.isEmpty()) return null
        val sorted = cues.sortedBy { it.first }
        return Lyrics(
            sorted.mapIndexed { i, (t, w) -> Cue(t, if (i + 1 < sorted.size) sorted[i + 1].first else t + 6_000, w) },
            "", "Synced lyrics inside the file"
        )
    }

    private fun flac(input: InputStream, head: ByteArray): Lyrics? {
        // head holds "fLaC" and the first 6 bytes of the first block header; read the rest of that header first.
        val first = ByteArray(4)
        System.arraycopy(head, 4, first, 0, 4)
        var header = first
        var consumedExtra = head.copyOfRange(8, 10)
        var last = false
        var firstBlock = true
        while (!last) {
            val h: ByteArray
            if (firstBlock) {
                h = header
                firstBlock = false
            } else {
                h = ByteArray(4)
                if (readFully(input, h) < 4) return null
            }
            last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = (h[1].toInt() and 0xFF shl 16) or (h[2].toInt() and 0xFF shl 8) or (h[3].toInt() and 0xFF)
            if (len < 0 || len > MAX_TAG) return null
            val data = ByteArray(len)
            var got = 0
            if (consumedExtra.isNotEmpty()) {
                val c = minOf(consumedExtra.size, len)
                System.arraycopy(consumedExtra, 0, data, 0, c)
                got = c
                consumedExtra = ByteArray(0)
            }
            if (len - got > 0) readFully(input, data, got, len - got)
            if (type == 4) {
                vorbis(data)?.let { return it }
            }
        }
        return null
    }

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or (b[o + 1].toInt() and 0xFF shl 8) or (b[o + 2].toInt() and 0xFF shl 16) or (b[o + 3].toInt() and 0xFF shl 24)

    private fun vorbis(d: ByteArray): Lyrics? {
        var p = 0
        if (d.size < 8) return null
        p += 4 + le32(d, 0)
        if (p + 4 > d.size) return null
        val count = le32(d, p)
        p += 4
        for (i in 0 until count) {
            if (p + 4 > d.size) break
            val len = le32(d, p)
            p += 4
            if (len < 0 || p + len > d.size) break
            val comment = String(d, p, len, Charsets.UTF_8)
            p += len
            val eq = comment.indexOf('=')
            if (eq <= 0) continue
            val key = comment.substring(0, eq).uppercase()
            if (key == "LYRICS" || key == "UNSYNCEDLYRICS" || key == "SYNCEDLYRICS") {
                val l = TextParsers.lyrics(comment.substring(eq + 1), "lrc", "Lyrics inside the file")
                if (!l.isEmpty) return l
            }
        }
        return null
    }
}
