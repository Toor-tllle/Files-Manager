package com.rui.filesmanager

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * 診断ランチャー（v0.1.6）。
 * Compose を使わない素の画面で、端末に残っているクラッシュ記録と
 * 実際にインストールされているバージョンを表示する。本編は別 Activity。
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.init(this)
        CrashLog.setStage(this, "launcher-activity")
        val crash = CrashLog.read(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 24)
        }
        fun tv(text: String, size: Float = 16f) = TextView(this).apply {
            this.text = text
            textSize = size
            setPadding(0, 10, 0, 10)
        }
        col.addView(tv("FilesManager 診断ランチャー", 20f))
        col.addView(tv("インストール中バージョン: ${Updater.currentVersion(this)}\n到達段階: ${CrashLog.readStage(this)}"))
        if (crash != null) {
            col.addView(tv("前回クラッシュ記録:", 18f))
            col.addView(tv(crash, 12f))
            col.addView(Button(this).apply {
                text = "記録を消す"
                setOnClickListener { CrashLog.clear(this@MainActivity); recreate() }
            })
        } else {
            col.addView(tv("クラッシュ記録はありません。"))
        }
        col.addView(Button(this).apply {
            text = "本編を開く"
            setOnClickListener { startActivity(Intent(this@MainActivity, ComposeHostActivity::class.java)) }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}

/** 本編（Compose UI）ホスト。 */
class ComposeHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.setStage(this, "compose-host-created")
        setContent { FilesManagerApp() }
    }
}
