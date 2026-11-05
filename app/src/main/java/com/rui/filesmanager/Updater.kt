package com.rui.filesmanager

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String = "",
)

object Updater {
    const val DEFAULT_VERSION_URL = "https://raw.githubusercontent.com/Toor-tllle/Files-Manager/main/version.json"
    const val DEFAULT_APK_URL = "https://github.com/Toor-tllle/Files-Manager/releases/latest/download/FilesManager.apk"
    private const val PREF_URL = "update_version_url"
    private const val PREF_AUTO = "update_auto_check"
    private const val PREF_DL_ID = "update_download_id"

    fun versionUrl(store: NasStore): String = store.getString(PREF_URL, DEFAULT_VERSION_URL)
    fun setVersionUrl(store: NasStore, url: String) = store.putString(PREF_URL, url)
    fun autoCheck(store: NasStore): Boolean = store.getBool(PREF_AUTO, true)
    fun setAutoCheck(store: NasStore, v: Boolean) = store.putBool(PREF_AUTO, v)

    fun currentVersion(context: Context): Pair<Int, String> {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        return (pi.longVersionCode.toInt()) to (pi.versionName ?: "")
    }

    /** GitHub の version.json を確認する。新版がなければ null、失敗時は例外。 */
    fun check(context: Context, store: NasStore): UpdateInfo? {
        val base = versionUrl(store)
        val url = if (base.contains("?")) "$base&t=${System.currentTimeMillis()}" else "$base?t=${System.currentTimeMillis()}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
        }
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        val o = JSONObject(text)
        val code = o.optInt("versionCode", 0)
        val name = o.optString("versionName", "")
        val apk = o.optString("apk_url", "").ifBlank { DEFAULT_APK_URL }
        val notes = o.optString("notes", "")
        val (curCode, _) = currentVersion(context)
        return if (code > curCode) UpdateInfo(code, name, apk, notes) else null
    }

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            context.startActivity(
                Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** DownloadManager で APK を落とす。ダウンロード状況はステータスバー（通知）にも出る。 */
    fun startDownload(context: Context, store: NasStore, info: UpdateInfo): Long {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("FilesManager 更新 v${info.versionName}")
            setDescription("更新APKをダウンロード中")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "FilesManager-update.apk")
            setAllowedOverMetered(true)
        }
        val id = dm.enqueue(req)
        store.putString(PREF_DL_ID, id.toString())
        return id
    }

    data class Progress(val downloaded: Long, val total: Long, val status: Int)

    fun queryProgress(context: Context, id: Long): Progress? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (c.moveToFirst()) {
                val dl = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val tot = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val st = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                return Progress(dl, tot, st)
            }
        }
        return null
    }

    fun savedDownloadId(store: NasStore): Long = store.getString(PREF_DL_ID, "").toLongOrNull() ?: -1L

    fun install(context: Context, id: Long): Boolean {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(id) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
