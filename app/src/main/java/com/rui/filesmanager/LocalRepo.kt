package com.rui.filesmanager

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.util.Size

object LocalRepo {

    private val docExts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "html", "htm", "json", "xml", "odt", "ods")
    private val archiveExts = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz")

    fun query(resolver: ContentResolver, category: Category, limit: Int = 500): List<LocalItem> {
        return when (category) {
            Category.IMAGE -> queryMedia(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, limit)
            Category.VIDEO -> queryMedia(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, limit)
            Category.AUDIO -> queryMedia(resolver, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, limit)
            Category.RECENT -> queryFiles(resolver, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC", limit = 120)
            Category.DOWNLOAD -> queryFiles(
                resolver,
                "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?",
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
                arrayOf("%Download%"), limit
            )
            Category.APP -> queryFiles(
                resolver,
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
                arrayOf("%.apk"), limit
            )
            Category.DOCUMENT -> queryFiles(resolver, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC", limit = 2000)
                .filter { it.name.substringAfterLast('.', "").lowercase() in docExts }
                .take(limit)
            Category.ARCHIVE -> queryFiles(resolver, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC", limit = 2000)
                .filter { it.name.substringAfterLast('.', "").lowercase() in archiveExts }
                .take(limit)
        }
    }

    fun search(resolver: ContentResolver, q: String, limit: Int = 200): List<LocalItem> {
        if (q.isBlank()) return emptyList()
        return queryFiles(
            resolver,
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?",
            "${MediaStore.Files.FileColumns.DATE_ADDED} DESC",
            arrayOf("%$q%"), limit
        )
    }

    private fun queryMedia(resolver: ContentResolver, uri: android.net.Uri, limit: Int): List<LocalItem> {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        val out = mutableListOf<LocalItem>()
        resolver.query(uri, proj, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) out.add(readRow(c, uri))
        }
        return out
    }

    private fun queryFiles(
        resolver: ContentResolver,
        selection: String?,
        sort: String?,
        args: Array<String>? = null,
        limit: Int,
    ): List<LocalItem> {
        val uri = MediaStore.Files.getContentUri("external")
        val proj = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        val out = mutableListOf<LocalItem>()
        try {
            resolver.query(uri, proj, selection, args, sort)?.use { c ->
                while (c.moveToNext() && out.size < limit) out.add(readRow(c, uri))
            }
        } catch (e: Exception) {
            // 端末によっては Files コレクションが制限されることがある
        }
        return out
    }

    private fun readRow(c: Cursor, base: android.net.Uri): LocalItem {
        val id = c.getLong(0)
        val name = c.getString(1) ?: "(名称なし)"
        val mime = c.getString(2) ?: guessMime(name)
        val size = if (c.isNull(3)) 0L else c.getLong(3)
        val modSec = if (c.isNull(4)) 0L else c.getLong(4)
        return LocalItem(
            id = id,
            name = name,
            uri = ContentUris.withAppendedId(base, id),
            mime = mime,
            size = size,
            modified = modSec * 1000,
        )
    }

    private fun guessMime(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "mkv", "mov", "webm" -> "video/mp4"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }

    fun thumbnail(resolver: ContentResolver, item: LocalItem, px: Int = 256): Bitmap? {
        if (!item.isImage && !item.isVideo) return null
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.loadThumbnail(item.uri, Size(px, px), null)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
