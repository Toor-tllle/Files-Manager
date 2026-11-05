package com.rui.filesmanager

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
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

    private var sessionKey: String = ""

    /** 接続＋認証（セッション確立）だけを行う。共有には触らない＝ログイン成否の切り分け用。 */
    @Synchronized
    fun ensureSession(conn: NasConnection): Session {
        val key = "${conn.host}|${conn.username}"
        val cur = session
        if (cur != null && sessionKey == key && connection?.isConnected == true) return cur
        disconnectLocked()
        val c = SMBClient()
        val cn = c.connect(conn.host)
        val s = cn.authenticate(
            AuthenticationContext(conn.username, conn.password.toCharArray(), conn.domain.ifBlank { null })
        )
        client = c; connection = cn; session = s
        sessionKey = key
        SmbStreamServer.start()
        return s
    }

    /** ログイン（認証）だけをテストする。成功すれば例外なし。 */
    fun loginTest(conn: NasConnection) {
        ensureSession(conn)
    }

    /** よくある共有名を総当たりで探し、実際に開けたものだけ返す。 */
    @Synchronized
    fun probeShares(conn: NasConnection): List<String> {
        val s = ensureSession(conn)
        val found = mutableListOf<String>()
        for (name in COMMON_SHARES) {
            try {
                val sh = s.connectShare(name) as DiskShare
                try {
                    sh.list("")
                    found.add(name)
                } finally {
                    runCatching { sh.close() }
                }
            } catch (_: Exception) {
                // その名前の共有は無い／権限なし → 次を試す
            }
        }
        return found
    }

    /**
     * 共有フォルダの探索。まず jcifs で実列挙し、取れなければ smbj の総当たりに切り替える
     * （総当たりで全滅したらセッションを作り直してもう一度だけ試す）。認証失敗は例外で返す。
     */
    /** 直近の discoverShares で共有が取れなかった理由（診断表示用）。 */
    @Volatile var lastDiscoverNote: String = ""
        private set

    fun discoverShares(conn: NasConnection): List<String> {
        var enumError: String? = null
        val viaEnum = try {
            ShareEnum.listShares(conn)
        } catch (e: Exception) {
            enumError = "${e.javaClass.simpleName}: ${e.message}"
            emptyList()
        }
        var probeError: Exception? = null
        val viaProbe = try {
            ensureSession(conn)
            var f = probeShares(conn)
            if (f.isEmpty()) {
                disconnect()
                ensureSession(conn)
                f = probeShares(conn)
            }
            f
        } catch (e: Exception) {
            probeError = e
            emptyList()
        }
        val merged = (viaEnum + viaProbe).distinct()
        lastDiscoverNote = when {
            merged.isNotEmpty() -> ""
            enumError != null -> "一覧の取得でエラー: $enumError"
            else -> "一覧も総当たりも0件でした"
        }
        // どちらからも取れず、総当たり側が認証エラー等で落ちた場合は従来通り例外にする
        if (merged.isEmpty() && probeError != null) throw probeError
        return merged
    }

    /** 指定した共有名が開けるかテストする。 */
    @Synchronized
    fun testShare(conn: NasConnection, shareName: String): Boolean {
        val s = ensureSession(conn)
        return try {
            val sh = s.connectShare(shareName) as DiskShare
            try {
                sh.list("")
                true
            } finally {
                runCatching { sh.close() }
            }
        } catch (_: Exception) {
            false
        }
    }

    val COMMON_SHARES = listOf(
        "share", "info", "public", "Public", "home", "homes", "data", "Data",
        "multimedia", "Multimedia", "Download", "download", "photo", "Photo",
        "music", "Music", "video", "Video", "disk", "volume1", "nas", "NAS",
    )

    @Synchronized
    fun ensureConnected(conn: NasConnection): DiskShare {
        // キャッシュは「接続先ID＋共有名」で引く。IDだけだと最初に開いた共有が返り続け、
        // 別の共有フォルダを開いても前のフォルダに戻ってしまうバグになる。
        val cacheKey = "${conn.id}|${conn.share}"
        val existing = shares[cacheKey]
        if (existing != null && sessionKey == "${conn.host}|${conn.username}" && connection?.isConnected == true) {
            return existing
        }
        val s = ensureSession(conn)
        val share = s.connectShare(conn.share) as DiskShare
        shares[cacheKey] = share
        connectedKey = "${conn.host}|${conn.share}|${conn.username}"
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
        sessionKey = ""
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

    /** 本体→NAS のアップロード。dirPath は共有内パス（"" = 直下）。既存ファイルは上書き。 */
    fun writeFile(conn: NasConnection, dirPath: String, name: String, input: InputStream, size: Long, onProgress: ((Long) -> Unit)? = null) {
        val share = ensureConnected(conn)
        val target = joinSmbPath(dirPath, name)
        val f = share.openFile(
            target,
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        try {
            f.getOutputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    onProgress?.invoke(done)
                }
                out.flush()
            }
        } finally {
            runCatching { f.close() }
        }
    }

    fun delete(conn: NasConnection, entry: NasEntry) {
        val share = ensureConnected(conn)
        if (entry.isDir) share.rmdir(entry.path, false) else share.rm(entry.path)
    }

    /** SMB のエラーを日本語の案内に変換する（診断用にステータスコードも併記）。 */
    fun describeError(e: Throwable): String {
        val raw = (e.message ?: e.toString())
        val upper = raw.uppercase()
        val friendly = when {
            e is java.net.UnknownHostException ->
                "ホスト名を解決できませんでした。NAS名ではなくIPアドレスで試してください（検索で見つけた機器を選ぶとIPが入ります）。"
            upper.contains("C000006D") || upper.contains("LOGON_FAILURE") || upper.contains("LOGON FAILED") ->
                "ログインできませんでした。ユーザー名かパスワードが違うか、そのアカウントがNAS側で無効化されています（Synology の「admin」は初期状態で無効なことがあります。その場合は普段使っている管理者アカウントで試してください）。"
            upper.contains("C0000064") || upper.contains("NO_SUCH_USER") ->
                "そのユーザーはNASに存在しません。ユーザー名を確認してください。"
            upper.contains("C000006A") || upper.contains("WRONG_PASSWORD") ->
                "パスワードが違います。"
            upper.contains("C0000072") || upper.contains("ACCOUNT_DISABLED") ->
                "そのアカウントはNAS側で無効化されています。NASの管理画面で有効化するか、別のアカウントを使ってください。"
            upper.contains("C0000234") || upper.contains("ACCOUNT_LOCKED") ->
                "アカウントがロックされています。しばらく待つか、NASの管理画面でロックを解除してください。"
            upper.contains("C00000CC") || upper.contains("BAD_NETWORK_NAME") ->
                "共有フォルダが見つかりません。共有名を確認してください。"
            upper.contains("C0000022") || upper.contains("ACCESS_DENIED") ->
                "アクセスが拒否されました。そのアカウントに共有フォルダへの権限がない可能性があります。"
            e is java.net.SocketTimeoutException || upper.contains("TIMED OUT") || upper.contains("TIMEOUT") ->
                "NASから応答がありません。同じWi-Fiにつながっているか、NASの電源が入っているか確認してください。"
            e is java.net.ConnectException || upper.contains("FAILED TO CONNECT") || upper.contains("CONNECTION REFUSED") || upper.contains("ECONNREFUSED") ->
                "NASに接続できませんでした。IPアドレスが合っているか、NASのSMBサービスが有効か確認してください。古いNASでSMB1のみ対応の機種には接続できません。"
            else -> "接続できませんでした。"
        }
        return friendly + "\n（詳細: " + raw.take(220) + "）"
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
        try {
            handleInner(sock)
        } catch (e: Exception) {
            // 再生側が先に切断するのは通常動作（シーク・停止・画面を閉じる）。
            // 配信スレッドで例外を漏らすとアプリ全体が落ちるので、ここで必ず握りつぶす。
            runCatching { sock.close() }
        }
    }

    private fun handleInner(sock: Socket) {
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
