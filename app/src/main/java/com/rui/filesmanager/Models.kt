package com.rui.filesmanager

import android.net.Uri

enum class Category(val label: String) {
    DOWNLOAD("ダウンロード"),
    IMAGE("画像"),
    VIDEO("動画"),
    AUDIO("音声"),
    DOCUMENT("ドキュメント"),
    APP("アプリ"),
    ARCHIVE("圧縮ファイル"),
    RECENT("最近のファイル"),
}

data class LocalItem(
    val id: Long,
    val name: String,
    val uri: Uri,
    val mime: String,
    val size: Long,
    val modified: Long, // epoch millis
    val isVideo: Boolean = mime.startsWith("video/"),
    val isImage: Boolean = mime.startsWith("image/"),
    val isAudio: Boolean = mime.startsWith("audio/"),
)

data class NasConnection(
    val id: String,
    val label: String,
    val host: String,
    val share: String,
    val username: String,
    val password: String,
    val domain: String = "",
)

data class NasEntry(
    val name: String,
    val path: String, // path inside the share, "" = root, backslash-separated as SMB expects
    val isDir: Boolean,
    val size: Long,
    val modified: Long = 0L,
) {
    val isVideo: Boolean get() {
        val e = name.substringAfterLast('.', "").lowercase()
        return e in setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "mpg", "mpeg", "wmv", "flv", "3gp")
    }
    val isImage: Boolean get() {
        val e = name.substringAfterLast('.', "").lowercase()
        return e in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    }
    val isAudio: Boolean get() {
        val e = name.substringAfterLast('.', "").lowercase()
        return e in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
    }
}

fun fmtSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[maxOf(i, 0)])
}

fun joinSmbPath(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent\\$name"

fun parentSmbPath(path: String): String =
    path.substringBeforeLast('\\', "")

/** 本体→NAS 転送の対象（ローカル/SAF 共通の表現）。 */
data class UploadSource(val uri: String, val name: String, val size: Long)

/** プレーヤーの再生キュー要素。localSource か nasPath のどちらかが入る。 */
data class PlayQueueItem(val title: String, val localSource: String? = null, val nasPath: String? = null)

/** 画像ビューアの送り先リスト要素。 */
data class ImageRef(val title: String, val source: String = "", val nasPath: String = "")
