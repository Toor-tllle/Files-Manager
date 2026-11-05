package com.rui.filesmanager

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 落ちた理由を端末内に記録し、次回起動時に画面で見せる（RuiAlertで実績のある方式）。 */
object CrashLog {
    private const val FILE_NAME = "crash_last.txt"
    private var installed = false

    fun init(context: Context) {
        if (installed) return
        installed = true
        val appCtx = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN).format(Date())
                val text = buildString {
                    append("FilesManager クラッシュ記録\n")
                    append("時刻: $time\n")
                    append("スレッド: ${thread.name}\n\n")
                    append(sw.toString().take(6000))
                }
                File(appCtx.filesDir, FILE_NAME).writeText(text)
            } catch (_: Exception) {
                // 記録に失敗しても元の処理へ
            }
            prev?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val f = File(context.filesDir, FILE_NAME)
        return if (f.exists()) {
            try { f.readText().ifBlank { null } } catch (e: Exception) { null }
        } else null
    }

    fun clear(context: Context) {
        try { File(context.filesDir, FILE_NAME).delete() } catch (_: Exception) {}
    }
}
