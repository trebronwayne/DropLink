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
import io.github.trebronwayne.droplink.core.SiteHost
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
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

object DropLinkServer {
    private const val COOKIE = "dl_session"
    private const val SITE_COOKIE = "dl_site"
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

                    call.respondRange(app, file, type)
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
                get("/site") { call.respondRedirect("/site/") }
                get("/site/{path...}") {
                    if (!call.siteGate(app, api = false)) return@get
                    val segments = call.parameters.getAll("path").orEmpty().filter { it.isNotEmpty() }
                    // Anything under a folder named "private" needs a signed-in visitor.
                    if (segments.firstOrNull().equals("private", ignoreCase = true) && SiteHost.userFor(call.request.cookies[SITE_COOKIE]) == null) {
                        call.respondText(messagePage("Please sign in", "This page is for signed-in visitors."), ContentType.Text.Html, HttpStatusCode.Unauthorized)
                        return@get
                    }
                    val siteId = SiteHost.siteId(app)
                    val rel = segments.joinToString("/")
                    val found = if (siteId == null) null else withContext(Dispatchers.IO) {
                        val direct = if (segments.isEmpty()) null else SharedFolders.entry(app, siteId, rel)
                        if (direct != null && direct.isDir && !call.request.path().endsWith("/")) direct
                        else if (segments.isEmpty() || direct?.isDir == true) SharedFolders.entry(app, siteId, if (segments.isEmpty()) "index.html" else "$rel/index.html")
                        else direct
                    }
                    if (found == null) {
                        call.respondText(messagePage("Page not found", "There is nothing at this address."), ContentType.Text.Html, HttpStatusCode.NotFound)
                        return@get
                    }
                    if (found.isDir) { call.respondRedirect("/site/$rel/"); return@get }
                    val etag = "\"${found.size}-${found.modified}\""
                    call.response.header(HttpHeaders.ETag, etag)
                    call.response.header(HttpHeaders.CacheControl, "no-cache")
                    call.response.header("X-Content-Type-Options", "nosniff")
                    call.response.header("Referrer-Policy", "same-origin")
                    if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) { call.respond(HttpStatusCode.NotModified); return@get }
                    var type = ContentType.defaultForFilePath(found.name)
                    if (type == ContentType.Application.OctetStream && found.mime.isNotEmpty()) type = try { ContentType.parse(found.mime) } catch (_: Exception) { type }
                    if (type.contentType == "text") type = type.withCharset(Charsets.UTF_8)
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
                    call.respondRange(app, found, type)
                }
                get("/site-api/me") {
                    if (!call.siteGate(app, api = true)) return@get
                    val user = SiteHost.userFor(call.request.cookies[SITE_COOKIE])
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.json(HttpStatusCode.OK, JSONObject().put("user", user ?: JSONObject.NULL).toString())
                }
                post("/site-api/login") {
                    if (!call.siteGate(app, api = true)) return@post
                    val fields = call.readFields(8 * 1024) ?: return@post
                    val result = withContext(Dispatchers.IO) { SiteHost.verify(app, fields["username"].orEmpty(), fields["password"].orEmpty()) }
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    when (result) {
                        null -> call.json(HttpStatusCode.Unauthorized, "{\"ok\":false,\"error\":\"invalid\"}")
                        SiteHost.LOCKED -> call.json(HttpStatusCode.TooManyRequests, "{\"ok\":false,\"error\":\"too_many_attempts\"}")
                        else -> {
                            call.response.cookies.append(
                                Cookie(SITE_COOKIE, SiteHost.startSession(result), path = "/", secure = secureCookies, httpOnly = true,
                                    maxAge = 12 * 3600, extensions = mapOf("SameSite" to "Lax"))
                            )
                            call.json(HttpStatusCode.OK, JSONObject().put("ok", true).put("user", result).toString())
                        }
                    }
                }
                post("/site-api/logout") {
                    if (!call.siteGate(app, api = true)) return@post
                    SiteHost.endSession(call.request.cookies[SITE_COOKIE])
                    call.response.cookies.append(Cookie(SITE_COOKIE, "", path = "/", secure = secureCookies, httpOnly = true, maxAge = 0))
                    call.json(HttpStatusCode.OK, "{\"ok\":true}")
                }
                post("/site-api/submit/{form}") {
                    if (!call.siteGate(app, api = true)) return@post
                    val form = call.parameters["form"].orEmpty()
                    if (!Regex("[A-Za-z0-9_-]{1,40}").matches(form)) {
                        call.json(HttpStatusCode.BadRequest, "{\"ok\":false,\"error\":\"bad_form_name\"}")
                        return@post
                    }
                    val fields = call.readFields(64 * 1024) ?: return@post
                    val user = SiteHost.userFor(call.request.cookies[SITE_COOKIE])
                    val saved = withContext(Dispatchers.IO) { SiteHost.submit(app, form, fields, user) }
                    if (saved) call.json(HttpStatusCode.OK, "{\"ok\":true}")
                    else call.json(HttpStatusCode.ServiceUnavailable, "{\"ok\":false,\"error\":\"busy_or_full\"}")
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
                            call.respondBytes(asset.gzip, staticType(rel))
                        } else {
                            call.respondBytes(asset.raw, staticType(rel))
                        }
                    }
                }
            }
        }
    }


    /** Answers with the whole file or the requested byte range (needed for seeking in audio and video). */
    /** Content type for the bundled web client; Aubree's pictures are WebP. */
    private fun staticType(rel: String): ContentType =
        if (rel.endsWith(".webp")) ContentType.parse("image/webp") else ContentType.defaultForFilePath(rel)

    private suspend fun ApplicationCall.respondRange(app: Context, file: io.github.trebronwayne.droplink.core.DocEntry, type: ContentType) {
        val length = file.size
        var start = 0L
        var end = length - 1
        var partial = false
        val range = request.headers[HttpHeaders.Range]
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
                response.header(HttpHeaders.ContentRange, "bytes */$length")
                respondText("", status = HttpStatusCode.RequestedRangeNotSatisfiable)
                return
            }
        }
        if (partial) response.header(HttpHeaders.ContentRange, "bytes $start-$end/$length")
        val count = if (length == 0L) 0L else end - start + 1
        respond(
            if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
            DocumentContent(app, file.uri, start, count, type)
        )
    }

    private suspend fun ApplicationCall.json(status: HttpStatusCode, body: String) =
        respondText(body, ContentType.Application.Json, status)


    private fun ApplicationCall.isPaired(): Boolean = request.cookies[COOKIE]?.let { Pairing.isAuthorized(it) } == true

    private fun messagePage(title: String, text: String) =
        "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>$title</title>" +
            "<body style=\"font:16px system-ui;display:grid;place-items:center;min-height:100vh;margin:0;background:#e6ebf2;color:#2a3140\">" +
            "<main style=\"text-align:center;padding:24px\"><h1 style=\"margin:0 0 8px\">$title</h1><p>$text</p></main>"

    /** The hosted page is reachable only while it is published, and (if the owner chose so) only by approved devices. */
    private suspend fun ApplicationCall.siteGate(app: Context, api: Boolean): Boolean {
        val reason = when {
            !SiteHost.enabled(app) -> HttpStatusCode.NotFound to ("No page is hosted" to "The owner has not published a page on this phone.")
            !SiteHost.openToAnyone(app) && !isPaired() -> HttpStatusCode.Forbidden to ("Approved devices only" to "Open DropLink on this device and ask the owner to approve it first.")
            else -> return true
        }
        if (api) json(reason.first, "{\"ok\":false,\"error\":\"unavailable\"}")
        else respondText(messagePage(reason.second.first, reason.second.second), ContentType.Text.Html, reason.first)
        return false
    }

    /** Reads a small JSON or form-encoded body into text fields. Answers the error itself and returns null if it is unusable. */
    private suspend fun ApplicationCall.readFields(maxBytes: Int): Map<String, String>? {
        val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared == null) { json(HttpStatusCode.LengthRequired, "{\"ok\":false,\"error\":\"length_required\"}"); return null }
        if (declared > maxBytes) { json(HttpStatusCode.PayloadTooLarge, "{\"ok\":false,\"error\":\"too_large\"}"); return null }
        val text = receiveText()
        val out = LinkedHashMap<String, String>()
        try {
            if (request.contentType().match(ContentType.Application.Json)) {
                val o = JSONObject(text)
                o.keys().asSequence().take(50).forEach { k -> out[k.take(100)] = o.opt(k)?.toString().orEmpty().take(4000) }
            } else {
                text.split('&').take(50).forEach { pair ->
                    val i = pair.indexOf('=')
                    if (i > 0) out[URLDecoder.decode(pair.substring(0, i), "UTF-8").take(100)] = URLDecoder.decode(pair.substring(i + 1), "UTF-8").take(4000)
                }
            }
        } catch (_: Exception) {
            json(HttpStatusCode.BadRequest, "{\"ok\":false,\"error\":\"bad_body\"}")
            return null
        }
        return out
    }

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
