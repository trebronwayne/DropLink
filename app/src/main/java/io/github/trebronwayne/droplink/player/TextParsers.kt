package io.github.trebronwayne.droplink.player

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Reads lyric and subtitle files in the formats people actually have: LRC (also with several times on a line and
 * word timings), SRT, WebVTT, ASS/SSA, and plain text. Everything becomes a list of [Cue] with times, or plain text
 * when the file has no times.
 */
object TextParsers {
    /** File extensions that can hold lyrics or subtitles. */
    val SIDECAR_EXT = setOf("lrc", "lyr", "txt", "srt", "vtt", "ass", "ssa")
    private val SUBTITLE_EXT = setOf("srt", "vtt", "ass", "ssa")

    fun isSubtitleExt(ext: String) = ext.lowercase() in SUBTITLE_EXT

    /** Turns file bytes into text, trying the byte-order mark, then UTF-8, then the old Western encoding. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

    /** Lyrics from any supported text (a .lrc file, a .txt file, or lyrics embedded in a tag). */
    fun lyrics(raw: String, ext: String, source: String): Lyrics {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim('\uFEFF', '\u0000', ' ', '\n')
        if (text.isBlank()) return Lyrics(emptyList(), "", source)
        val cues = when {
            ext.lowercase() == "vtt" || text.startsWith("WEBVTT") -> vtt(text)
            ext.lowercase() == "srt" -> srt(text)
            ext.lowercase() in setOf("ass", "ssa") -> ass(text)
            else -> lrc(text).ifEmpty { if (SRT_TIME.containsMatchIn(text)) srt(text) else emptyList() }
        }
        return if (cues.isNotEmpty()) Lyrics(cues, "", source) else Lyrics(emptyList(), stripLrcTags(text), source)
    }

    fun subtitles(raw: String, ext: String): List<Cue> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n').trim('\uFEFF', '\u0000', ' ', '\n')
        return when {
            ext.lowercase() == "vtt" || text.startsWith("WEBVTT") -> vtt(text)
            ext.lowercase() in setOf("ass", "ssa") -> ass(text)
            else -> srt(text)
        }
    }

    private val LRC_TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val LRC_OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    private fun fraction(f: String?): Long {
        if (f.isNullOrEmpty()) return 0L
        return when (f.length) {
            1 -> f.toLong() * 100
            2 -> f.toLong() * 10
            else -> f.take(3).toLong()
        }
    }

    /** LRC: "[01:23.45] words". A line may carry several times; "[offset:+200]" shifts everything. */
    fun lrc(text: String): List<Cue> {
        val offset = LRC_OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val starts = ArrayList<Pair<Long, String>>()
        for (line in text.lines()) {
            val times = LRC_TIME.findAll(line).toList()
            if (times.isEmpty()) continue
            val words = WORD_TIME.replace(line.substring(times.last().range.last + 1), "").trim()
            for (m in times) {
                val ms = m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + fraction(m.groupValues[3].ifEmpty { null }) - offset
                starts += ms.coerceAtLeast(0) to words
            }
        }
        if (starts.isEmpty()) return emptyList()
        val sorted = starts.sortedBy { it.first }
        return sorted.mapIndexed { i, (start, words) ->
            Cue(start, if (i + 1 < sorted.size) sorted[i + 1].first else start + 6_000, words)
        }
    }

    private fun stripLrcTags(text: String): String =
        text.lines().filterNot { Regex("""^\[(ar|ti|al|by|offset|length|re|ve|au):.*]$""", RegexOption.IGNORE_CASE).matches(it.trim()) }
            .joinToString("\n") { WORD_TIME.replace(LRC_TIME.replace(it, ""), "").trimEnd() }.trim()

    private val SRT_TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val VTT_TIME = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[.,](\d{1,3})""")
    private val MARKUP = Regex("""<[^>]*>|\{\\[^}]*}""")

    private fun ms(h: String?, m: String, s: String, f: String) =
        (h?.ifEmpty { null }?.toLong() ?: 0L) * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + fraction(f)

    fun srt(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            val timeIndex = lines.indexOfFirst { SRT_TIME.containsMatchIn(it) }
            if (timeIndex < 0) continue
            val m = SRT_TIME.find(lines[timeIndex])!!.groupValues
            val body = lines.drop(timeIndex + 1).joinToString("\n") { MARKUP.replace(it, "").trim() }.trim()
            if (body.isNotEmpty()) cues += Cue(ms(m[1], m[2], m[3], m[4]), ms(m[5], m[6], m[7], m[8]), body)
        }
        return cues.sortedBy { it.startMs }
    }

    fun vtt(text: String): List<Cue> {
        val cues = ArrayList<Cue>()
        for (block in text.split(Regex("\n\\s*\n"))) {
            val lines = block.lines()
            if (lines.firstOrNull()?.startsWith("NOTE") == true || lines.firstOrNull()?.startsWith("STYLE") == true) continue
            val timeIndex = lines.indexOfFirst { VTT_TIME.containsMatchIn(it) }
            if (timeIndex < 0) continue
            val m = VTT_TIME.find(lines[timeIndex])!!.groupValues
            val body = lines.drop(timeIndex + 1).joinToString("\n") { MARKUP.replace(it, "").trim() }.trim()
            if (body.isNotEmpty()) cues += Cue(ms(m[1], m[2], m[3], m[4]), ms(m[5], m[6], m[7], m[8]), body)
        }
        return cues.sortedBy { it.startMs }
    }

    private fun assTime(t: String): Long {
        val p = t.trim().split(':', '.')
        if (p.size < 4) return 0L
        return p[0].toLong() * 3_600_000 + p[1].toLong() * 60_000 + p[2].toLong() * 1000 + p[3].padEnd(2, '0').take(2).toLong() * 10
    }

    /** ASS/SSA: reads the "Dialogue:" lines and drops the styling codes. */
    fun ass(text: String): List<Cue> {
        var start = 1
        var end = 2
        var body = 9
        var fields = 10
        val cues = ArrayList<Cue>()
        for (line in text.lines()) {
            val t = line.trim()
            if (t.startsWith("Format:", ignoreCase = true) && t.contains("Start", ignoreCase = true) && t.contains("Text", ignoreCase = true)) {
                val names = t.substringAfter(':').split(',').map { it.trim().lowercase() }
                start = names.indexOf("start").takeIf { it >= 0 } ?: start
                end = names.indexOf("end").takeIf { it >= 0 } ?: end
                body = names.indexOf("text").takeIf { it >= 0 } ?: body
                fields = names.size
            } else if (t.startsWith("Dialogue:", ignoreCase = true)) {
                val parts = t.substringAfter(':').split(',', limit = fields)
                if (parts.size <= maxOf(start, end, body)) continue
                val words = MARKUP.replace(parts[body], "").replace("\\N", "\n").replace("\\n", "\n").trim()
                if (words.isNotEmpty()) cues += Cue(assTime(parts[start]), assTime(parts[end]), words)
            }
        }
        return cues.sortedBy { it.startMs }
    }
}
