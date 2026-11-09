package io.github.trebronwayne.droplink.server

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.trebronwayne.droplink.core.NetworkAddresses
import io.github.trebronwayne.droplink.core.NodeIdentity
import io.github.trebronwayne.droplink.core.Qr
import io.github.trebronwayne.droplink.core.PairStatus
import io.github.trebronwayne.droplink.core.Pairing
import io.github.trebronwayne.droplink.core.ServerStats
import io.github.trebronwayne.droplink.core.SharedFolders
import io.github.trebronwayne.droplink.core.TlsIdentity
import io.ktor.http.content.OutgoingContent
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.pool.DefaultPool
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

object DropLinkServer {
    private const val COOKIE = "dl_session"
    private val RISKY_INLINE = setOf("text/html", "application/xhtml+xml", "image/svg+xml", "text/xml", "application/xml", "text/javascript", "application/javascript")

    fun create(context: Context, bindHost: String, port: Int, secureCookies: Boolean, publicPort: Int = port): EmbeddedServer<*, *> {
        System.setProperty("io.ktor.development", "false")
        val app = context.applicationContext
        val httpsPort = port
        return embeddedServer(CIO, applicationEnvironment { }, {
            // Plain HTTP engine. In HTTPS mode it listens on loopback only and TlsFrontend sits in front of it.
            connector {
                host = bindHost
                this.port = httpsPort
            }
        }) {
            routing {
                get("/health") {
                    call.json(HttpStatusCode.OK, "{\"service\":\"DropLink\",\"status\":\"online\",\"protocol\":3}")
                }
                post("/api/pair/request") {
                    val name = try { JSONObject(call.receiveText()).optString("name") } catch (_: Exception) { "" }
                    val request = Pairing.create(name)
                    if (request == null) {
                        call.json(HttpStatusCode.TooManyRequests, "{\"error\":\"too_many_requests\"}")
                    } else {
                        call.json(HttpStatusCode.OK, JSONObject().put("id", request.id).put("code", request.code).toString())
                    }
                }
                get("/api/pair/status") {
                    val id = call.request.queryParameters["id"].orEmpty()
                    when (Pairing.statusOf(id)) {
                        PairStatus.Pending -> call.json(HttpStatusCode.OK, "{\"status\":\"pending\"}")
                        PairStatus.Approved -> {
                            val token = Pairing.claim(id)
                            if (token == null) {
                                call.json(HttpStatusCode.OK, "{\"status\":\"expired\"}")
                            } else {
                                call.response.cookies.append(
                                    Cookie(
                                        COOKIE, token, path = "/", secure = secureCookies, httpOnly = true,
                                        maxAge = 31_536_000, extensions = mapOf("SameSite" to "Strict")
                                    )
                                )
                                call.json(HttpStatusCode.OK, "{\"status\":\"approved\"}")
                            }
                        }
                        PairStatus.Denied -> call.json(HttpStatusCode.OK, "{\"status\":\"denied\"}")
                        else -> call.json(HttpStatusCode.OK, "{\"status\":\"expired\"}")
                    }
                }
                get("/api/info") {
                    if (!call.authorized()) return@get
                    val id = withContext(Dispatchers.IO) { NodeIdentity.getOrCreateNodeId(app) }
                    call.json(HttpStatusCode.OK, JSONObject().put("nodeId", id).put("protocol", 3).toString())
                }
                get("/api/shares") {
                    if (!call.authorized()) return@get
                    val array = withContext(Dispatchers.IO) {
                        JSONArray().also { a -> SharedFolders.list(app).forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) } }
                    }
                    call.json(HttpStatusCode.OK, array.toString())
                }
                get("/api/browse") {
                    if (!call.authorized()) return@get
                    val share = call.request.queryParameters["share"].orEmpty()
                    val path = call.request.queryParameters["path"].orEmpty()
                    val fresh = call.request.queryParameters["fresh"] == "1"
                    val body = withContext(Dispatchers.IO) {
                        val list = SharedFolders.listDir(app, share, path, fresh) ?: return@withContext null
                        val a = JSONArray()
                        list.forEach { f ->
                            a.put(
                                JSONObject().put("name", f.name).put("isDir", f.isDir).put("size", f.size)
                                    .put("mime", if (f.isDir) "" else f.mime.ifEmpty { ContentType.defaultForFilePath(f.name).toString() })
                                    .put("modified", f.modified)
                            )
                        }
                        a.toString()
                    }
                    if (body == null) call.json(HttpStatusCode.NotFound, "{\"error\":\"not_found\"}") else {
                        call.response.header(HttpHeaders.CacheControl, "private, no-cache")
                        call.json(HttpStatusCode.OK, body)
                    }
                }
                get("/api/file") {
                    if (!call.authorized()) return@get
                    val share = call.request.queryParameters["share"].orEmpty()
                    val path = call.request.queryParameters["path"].orEmpty()
                    val download = call.request.queryParameters["download"] == "1"
                    val file = withContext(Dispatchers.IO) { SharedFolders.entry(app, share, path)?.takeIf { !it.isDir } }
                    if (file == null) {
                        call.json(HttpStatusCode.NotFound, "{\"error\":\"not_found\"}")
                        return@get
                    }
                    val name = file.name
                    val length = file.size
                    val mime = file.mime.ifEmpty { ContentType.defaultForFilePath(name).toString() }
                    val type = try { ContentType.parse(mime) } catch (_: Exception) { ContentType.Application.OctetStream }
                    val showInline = !download && mime.substringBefore(';').lowercase() !in RISKY_INLINE
                    val ascii = name.replace(Regex("[^\\x20-\\x7E]|[\"\\\\]"), "_")
                    val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        "${if (showInline) "inline" else "attachment"}; filename=\"$ascii\"; filename*=UTF-8''$encoded"
                    )
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
                    call.response.header(HttpHeaders.ETag, "\"${file.size}-${file.modified}\"")
                    call.response.header("X-Content-Type-Options", "nosniff")

                    var start = 0L
                    var end = length - 1
                    var partial = false
                    val range = call.request.headers[HttpHeaders.Range]
                    if (length > 0 && range != null && range.startsWith("bytes=") && !range.contains(',')) {
                        val parts = range.removePrefix("bytes=").trim().split('-')
                        val a = parts.getOrNull(0)?.toLongOrNull()
                        val b = parts.getOrNull(1)?.toLongOrNull()
                        if (a != null) {
                            start = a
                            end = b?.coerceAtMost(length - 1) ?: (length - 1)
                            partial = true
                        } else if (b != null) {
                            start = (length - b).coerceAtLeast(0)
                            partial = true
                        }
                        if (partial && (start > end || start >= length)) {
                            call.response.header(HttpHeaders.ContentRange, "bytes */$length")
                            call.respondText("", status = HttpStatusCode.RequestedRangeNotSatisfiable)
                            return@get
                        }
                    }
                    if (partial) call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$length")
                    val count = if (length == 0L) 0L else end - start + 1
                    call.respond(
                        if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                        DocumentContent(app, file.uri, start, count, type)
                    )
                }
                get("/api/speedtest/down") {
                    if (!call.authorized()) return@get
                    val megabytes = (call.request.queryParameters["mb"]?.toIntOrNull() ?: 25).coerceIn(1, 200)
                    val bytes = megabytes * 1024L * 1024L
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respond(
                        HttpStatusCode.OK,
                        StreamContent(LimitedInputStream(ZeroSource, bytes), bytes, ContentType.Application.OctetStream)
                    )
                }
                post("/api/speedtest/up") {
                    if (!call.authorized()) return@post
                    val limit = 200L * 1024 * 1024
                    val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                    if (declared != null && declared > limit) {
                        call.json(HttpStatusCode.PayloadTooLarge, "{\"error\":\"too_large\"}")
                        return@post
                    }
                    val started = System.nanoTime()
                    val received = call.receiveChannel().discard(limit)
                    ServerStats.add(received)
                    val millis = (System.nanoTime() - started) / 1_000_000
                    call.json(HttpStatusCode.OK, JSONObject().put("bytes", received).put("millis", millis).toString())
                }
                get("/api/addresses") {
                    if (!call.authorized()) return@get
                    val array = JSONArray()
                    NetworkAddresses.reachable().forEach {
                        array.put(JSONObject().put("url", it.url(publicPort, secureCookies)).put("label", it.kind.label).put("iface", it.iface))
                    }
                    call.json(HttpStatusCode.OK, array.toString())
                }
                get("/api/qr") {
                    if (!call.authorized()) return@get
                    val text = call.request.queryParameters["text"].orEmpty()
                    if (text.isEmpty() || text.length > 300) {
                        call.json(HttpStatusCode.BadRequest, "{\"error\":\"bad_text\"}")
                    } else {
                        call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
                        call.respondText(Qr.svg(text), ContentType.parse("image/svg+xml"))
                    }
                }
                get("/api/thumb") {
                    if (!call.authorized()) return@get
                    val share = call.request.queryParameters["share"].orEmpty()
                    val path = call.request.queryParameters["path"].orEmpty()
                    val bytes = withContext(Dispatchers.IO) { Thumbnails.get(app, share, path) }
                    if (bytes == null) {
                        call.json(HttpStatusCode.NotFound, "{\"error\":\"no_thumb\"}")
                    } else {
                        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
                        call.respondBytes(bytes, ContentType.Image.JPEG)
                    }
                }
                get("/{path...}") {
                    val rel = call.parameters.getAll("path")?.joinToString("/").orEmpty().ifEmpty { "index.html" }
                    val asset = if (rel.contains("..")) null else StaticAssets.get(app, rel)
                    if (asset == null) {
                        call.respondText("Not found", status = HttpStatusCode.NotFound)
                    } else {
                        // Hashed build files never change, so browsers keep them forever; index.html is always revalidated.
                        val immutable = rel.startsWith("assets/")
                        call.response.header(HttpHeaders.CacheControl, if (immutable) "public, max-age=31536000, immutable" else "no-cache")
                        call.response.header(HttpHeaders.ETag, asset.etag)
                        if (call.request.headers[HttpHeaders.IfNoneMatch] == asset.etag) {
                            call.respond(HttpStatusCode.NotModified)
                            return@get
                        }
                        val acceptsGzip = call.request.headers[HttpHeaders.AcceptEncoding]?.contains("gzip") == true
                        if (acceptsGzip && asset.gzip != null) {
                            call.response.header(HttpHeaders.ContentEncoding, "gzip")
                            call.response.header(HttpHeaders.Vary, "Accept-Encoding")
                            call.respondBytes(asset.gzip, ContentType.defaultForFilePath(rel))
                        } else {
                            call.respondBytes(asset.raw, ContentType.defaultForFilePath(rel))
                        }
                    }
                }
            }
        }
    }

    private suspend fun ApplicationCall.json(status: HttpStatusCode, body: String) =
        respondText(body, ContentType.Application.Json, status)

    private suspend fun ApplicationCall.authorized(): Boolean {
        val token = request.cookies[COOKIE]
        if (token != null && Pairing.isAuthorized(token)) return true
        json(HttpStatusCode.Unauthorized, "{\"error\":\"unauthorized\"}")
        return false
    }
}

// Large read buffers matter: the default 4 KB buffer makes big transfers CPU-bound on a phone.
private val BigBufferPool = object : DefaultPool<ByteArray>(16) {
    override fun produceInstance(): ByteArray = ByteArray(256 * 1024)
}

/** Streams a byte range of a shared document without loading it into memory. */
private class DocumentContent(
    private val context: Context,
    private val uri: Uri,
    private val start: Long,
    private val count: Long,
    private val type: ContentType
) : OutgoingContent.ReadChannelContent() {
    override val contentType: ContentType? = type
    override val contentLength: Long? = count

    override fun readFrom(): ByteReadChannel {
        if (count == 0L) return ByteReadChannel(ByteArray(0))
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (start > 0) {
            try { input.channel.position(start) } catch (_: Exception) { input.skip(start) }
        }
        return LimitedInputStream(input, count).toByteReadChannel(Dispatchers.IO, BigBufferPool)
    }
}

private class StreamContent(
    private val stream: InputStream,
    private val length: Long,
    private val type: ContentType
) : OutgoingContent.ReadChannelContent() {
    override val contentType: ContentType? = type
    override val contentLength: Long? = length

    override fun readFrom(): ByteReadChannel = stream.toByteReadChannel(Dispatchers.IO, BigBufferPool)
}

/** Endless zero bytes, used for the speed test so storage speed is not part of the measurement. */
private object ZeroSource : InputStream() {
    override fun read(): Int = 0

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        java.util.Arrays.fill(buffer, off, off + len, 0.toByte())
        return len
    }
}

/** Limits a stream to a byte count and reports progress to the live server statistics. */
private class LimitedInputStream(source: InputStream, private var remaining: Long) : FilterInputStream(source) {
    private val closed = AtomicBoolean(false)

    init {
        ServerStats.begin()
    }

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) {
            remaining--
            ServerStats.add(1)
        }
        return b
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = super.read(buffer, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) {
            remaining -= n
            ServerStats.add(n.toLong())
        }
        return n
    }

    override fun close() {
        try {
            super.close()
        } finally {
            if (closed.compareAndSet(false, true)) ServerStats.end()
        }
    }
}
