package com.rui.filesmanager

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.ByteArrayOutputStream

/** 一覧サムネイルのメモリキャッシュ（キー： L|uri / N|connId|path / S|uri）。 */
object ThumbCache {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bmp: Bitmap) {
        cache.put(key, bmp)
    }
}

/** NASファイルのサムネイル生成。画像はSMBから読んで縮小デコード、動画はストリームURLからフレームを取る。 */
object NasThumbs {
    private const val MAX_DIM = 256
    private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

    fun load(conn: NasConnection, entry: NasEntry): Bitmap? {
        return try {
            when {
                entry.isImage -> imageThumb(conn, entry)
                entry.isVideo -> videoThumb(conn, entry)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun imageThumb(conn: NasConnection, entry: NasEntry): Bitmap? {
        val bytes = SmbRepo.openStreamAt(conn, entry.path, 0).use { ins ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = ins.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_IMAGE_BYTES) return null
                buf.write(chunk, 0, n)
            }
            buf.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DIM && bounds.outHeight / (sample * 2) >= MAX_DIM) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return scaleDown(bmp)
    }

    private fun videoThumb(conn: NasConnection, entry: NasEntry): Bitmap? {
        val url = SmbRepo.streamUrl(conn, entry)
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(url, emptyMap<String, String>())
            val frame = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            frame?.let { scaleDown(it) }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun scaleDown(b: Bitmap): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= MAX_DIM) return b
        val k = MAX_DIM.toFloat() / m
        return Bitmap.createScaledBitmap(
            b,
            (b.width * k).toInt().coerceAtLeast(1),
            (b.height * k).toInt().coerceAtLeast(1),
            true,
        )
    }
}
