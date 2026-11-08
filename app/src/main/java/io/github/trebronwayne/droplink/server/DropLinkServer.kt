package io.github.trebronwayne.droplink.server

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.trebronwayne.droplink.core.NodeIdentity
import io.github.trebronwayne.droplink.core.PairStatus
import io.github.trebronwayne.droplink.core.Pairing
import io.github.trebronwayne.droplink.core.SharedFolders
import io.github.trebronwayne.droplink.core.TlsIdentity
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.defaultForFilePath
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

object DropLinkServer {
    private const val COOKIE = "dl_session"
    private val RISKY_INLINE = setOf("text/html", "application/xhtml+xml", "image/svg+xml", "text/xml", "application/xml", "text/javascript", "application/javascript")

    fun create(context: Context, port: Int, tls: TlsIdentity.Material): EmbeddedServer<*, *> {
        System.setProperty("io.ktor.development", "false")
        val app = context.applicationContext
        val httpsPort = port
        return embeddedServer(CIO, configure = {
            // HTTPS only. Ktor may clear the arrays it is given, so hand out fresh copies each time.
            sslConnector(
                keyStore = tls.keyStore,
                keyAlias = TlsIdentity.ALIAS,
                keyStorePassword = { tls.password.copyOf() },
                privateKeyPassword = { tls.password.copyOf() }
            ) {
                host = "0.0.0.0"
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
                                        COOKIE, token, path = "/", secure = true, httpOnly = true,
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
                    val body = withContext(Dispatchers.IO) {
                        val dir = SharedFolders.resolve(app, share, path)
                        if (dir == null || !dir.isDirectory) return@withContext null
                        val sorted = dir.listFiles().sortedWith(
                            compareByDescending<androidx.documentfile.provider.DocumentFile> { it.isDirectory }.thenBy { it.name?.lowercase() ?: "" }
                        )
                        JSONArray().also { a ->
                            sorted.forEach { f ->
                                val name = f.name ?: return@forEach
                                a.put(
                                    JSONObject().put("name", name).put("isDir", f.isDirectory).put("size", f.length())
                                        .put("mime", if (f.isDirectory) "" else (f.type ?: ContentType.defaultForFilePath(name).toString()))
                                        .put("modified", f.lastModified())
                                )
                            }
                        }.toString()
                    }
                    if (body == null) call.json(HttpStatusCode.NotFound, "{\"error\":\"not_found\"}") else call.json(HttpStatusCode.OK, body)
                }
                get("/api/file") {
                    if (!call.authorized()) return@get
                    val share = call.request.queryParameters["share"].orEmpty()
                    val path = call.request.queryParameters["path"].orEmpty()
                    val download = call.request.queryParameters["download"] == "1"
                    val file = withContext(Dispatchers.IO) { SharedFolders.resolve(app, share, path)?.takeIf { it.isFile } }
                    if (file == null) {
                        call.json(HttpStatusCode.NotFound, "{\"error\":\"not_found\"}")
                        return@get
                    }
                    val name = file.name ?: "file"
                    val length = file.length()
                    val mime = file.type ?: ContentType.defaultForFilePath(name).toString()
                    val type = try { ContentType.parse(mime) } catch (_: Exception) { ContentType.Application.OctetStream }
                    val inline = !download && mime.substringBefore(';').lowercase() !in RISKY_INLINE
                    val ascii = name.replace(Regex("[^\\x20-\\x7E]|[\"\\\\]"), "_")
                    val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        "${if (inline) "inline" else "attachment"}; filename=\"$ascii\"; filename*=UTF-8''$encoded"
                    )
                    call.response.header(HttpHeaders.AcceptRanges, "bytes")
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
                get("/{path...}") {
                    val rel = call.parameters.getAll("path")?.joinToString("/").orEmpty().ifEmpty { "index.html" }
                    val bytes = if (rel.contains("..")) null else try {
                        app.assets.open("web/$rel").use { it.readBytes() }
                    } catch (_: IOException) { null }
                    if (bytes == null) {
                        call.respondText("Not found", status = HttpStatusCode.NotFound)
                    } else {
                        call.response.header(HttpHeaders.CacheControl, "no-cache")
                        call.respondBytes(bytes, ContentType.defaultForFilePath(rel))
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
        return LimitedInputStream(input, count).toByteReadChannel()
    }
}

private class LimitedInputStream(source: InputStream, private var remaining: Long) : FilterInputStream(source) {
    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = super.read()
        if (b >= 0) remaining--
        return b
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = super.read(buffer, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }
}
