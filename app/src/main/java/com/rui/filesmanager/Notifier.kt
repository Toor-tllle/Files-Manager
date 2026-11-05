package com.rui.filesmanager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** ダウンロード状況をステータスバー（通知）に出すためのヘルパー。 */
object Notifier {
    private const val CHANNEL = "downloads"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "ダウンロード", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "ファイルや更新APKのダウンロード進捗"
                    }
                )
            }
        }
    }

    fun progress(context: Context, id: Int, title: String, percent: Int?, text: String = "") {
        ensureChannel(context)
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (percent != null) {
            b.setProgress(100, percent, false)
            b.setContentText(if (text.isNotEmpty()) text else "$percent%")
        } else {
            b.setProgress(0, 0, true)
            if (text.isNotEmpty()) b.setContentText(text)
        }
        try {
            NotificationManagerCompat.from(context).notify(id, b.build())
        } catch (e: SecurityException) {
            // 通知権限がない場合はアプリ内表示のみ
        }
    }

    fun done(context: Context, id: Int, title: String, text: String) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) { }
    }

    fun failed(context: Context, id: Int, title: String) {
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText("ダウンロードに失敗しました")
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) { }
    }
}
