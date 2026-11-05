package com.rui.filesmanager

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * SMB (NAS) アクセス。
 * - 保存済みアカウントで自動ログインし、セッションを保持する（次回タップで即一覧）。
 * - 動画/音声はローカルHTTPプロキシ経由で ExoPlayer に渡す（Range 対応＝シーク可能、
 *   ダウンロードせずストリーミング）。
 */
object SmbRepo {
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var shares = ConcurrentHashMap<String, DiskShare>()
    @Volatile private var connectedKey: String = ""

    @Synchronized
    fun ensureConnected(conn: NasConnection): DiskShare {
        val key = "${conn.host}|${conn.share}|${conn.username}"
        val existing = shares[conn.id]
        if (existing != null && connectedKey.startsWith("${conn.host}|") && connection?.isConnected == true) {
            return existing
        }
        disconnectLocked()
        val c = SMBClient()
        val cn = c.connect(conn.host)
        val s = cn.authenticate(
            AuthenticationContext(conn.username, conn.password.toCharArray(), conn.domain.ifBlank { null })
        )
        val share = s.connectShare(conn.share) as DiskShare
        client = c; connection = cn; session = s
        shares[conn.id] = share
        connectedKey = key
        SmbStreamServer.start()
        return share
    }

    @Synchronized
    fun disconnect() = disconnectLocked()

    private fun disconnectLocked() {
        try { shares.values.forEach { runCatching { it.close() } } } catch (_: Exception) {}
        shares.clear()
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        session = null; connection = null; client = null
        connectedKey = ""
    }

    fun list(conn: NasConnection, path: String): List<NasEntry> {
        val share = ensureConnected(conn)
        val items = share.list(path.ifEmpty { "" })
        val out = mutableListOf<NasEntry>()
        for (info in items) {
            val name = info.fileName ?: continue
            if (name == "." || name == "..") continue
            val isDir = (info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            val mod = try { info.lastWriteTime.toEpochMillis() } catch (e: Exception) { 0L }
            out.add(
                NasEntry(
                    name = name,
                    path = joinSmbPath(path, name),
                    isDir = isDir,
                    size = if (isDir) 0L else info.endOfFile,
                    modified = mod,
                )
            )
        }
        return out.sortedWith(compareByDescending<NasEntry> { it.isDir }.thenBy { it.name.lowercase() })
    }

    fun fileSize(conn: NasConnection, path: String): Long {
        val share = ensureConnected(conn)
        return share.getFileInformation(path).standardInformation.endOfFile
    }

    /** offset から読む InputStream（プロキシと画像読み込み用） */
    fun openStreamAt(conn: NasConnection, path: String, offset: Long): InputStream {
        val share = ensureConnected(conn)
        val file = share.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        return object : InputStream() {
            private var pos = offset
            private val one = ByteArray(1)
            override fun read(): Int {
                val n = read(one, 0, 1)
                return if (n <= 0) -1 else one[0].toInt() and 0xFF
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                val n = file.read(b, pos, off, len)
                if (n <= 0) return -1
                pos += n
                return n
            }
            override fun close() {
                runCatching { file.close() }
            }
        }
    }

    fun copyTo(conn: NasConnection, path: String, out: OutputStream) =
        copyToProgress(conn, path, out, 0L, null)

    fun copyToProgress(
        conn: NasConnection,
        path: String,
        out: OutputStream,
        total: Long,
        onProgress: ((Long) -> Unit)?,
    ) {
        openStreamAt(conn, path, 0).use { input ->
            val buf = ByteArray(256 * 1024)
            var done = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                done += n
                onProgress?.invoke(done)
            }
        }
    }

    fun delete(conn: NasConnection, entry: NasEntry) {
        val share = ensureConnected(conn)
        if (entry.isDir) share.rmdir(entry.path, false) else share.rm(entry.path)
    }

    /** 動画・音声を ExoPlayer で再生するためのローカルURLを発行する */
    fun streamUrl(conn: NasConnection, entry: NasEntry): String {
        val size = fileSize(conn, entry.path)
        return SmbStreamServer.register(conn, entry.path, size, entry.name)
    }
}

/** 127.0.0.1 専用の簡易HTTPサーバー。Range リクエストに応えて SMB から読む。 */
object SmbStreamServer {
    private var server: ServerSocket? = null
    private var port: Int = 0
    private data class Target(val conn: NasConnection, val path: String, val size: Long, val name: String)
    private val targets = ConcurrentHashMap<String, Target>()

    @Synchronized
    fun start() {
        if (server != null) return
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server = ss
        port = ss.localPort
        thread(isDaemon = true, name = "smb-http") {
            while (!ss.isClosed) {
                try {
                    val sock = ss.accept()
                    thread(isDaemon = true) { handle(sock) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    fun register(conn: NasConnection, path: String, size: Long, name: String): String {
        start()
        val token = UUID.randomUUID().toString()
        targets[token] = Target(conn, path, size, name)
        return "http://127.0.0.1:$port/$token"
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val token = parts[1].trimStart('/')
            var range: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
            }
            val target = targets[token] ?: run {
                s.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }
            val total = target.size
            var start = 0L
            var end = total - 1
            var partial = false
            if (range != null && range.startsWith("bytes=")) {
                val spec = range.removePrefix("bytes=").split("-")
                if (spec[0].isNotEmpty()) start = spec[0].toLongOrNull() ?: 0L
                if (spec.size > 1 && spec[1].isNotEmpty()) end = spec[1].toLongOrNull() ?: end
                if (end >= total) end = total - 1
                partial = true
            }
            val length = maxOf(0L, end - start + 1)
            val out = s.getOutputStream()
            val head = buildString {
                append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/octet-stream\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $length\r\n")
                if (partial) append("Content-Range: bytes $start-$end/$total\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray())
            out.flush()
            if (length > 0) {
                SmbRepo.openStreamAt(target.conn, target.path, start).use { input ->
                    val buf = ByteArray(256 * 1024)
                    var remain = length
                    while (remain > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), remain).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        remain -= n
                    }
                    out.flush()
                }
            }
        }
    }
}
