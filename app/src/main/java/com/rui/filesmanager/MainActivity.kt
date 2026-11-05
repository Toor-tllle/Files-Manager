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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 診断ランチャー（v0.1.8）。
 * 素の画面で記録を表示し、Compose最小テストと本編を別々に開いて切り分ける。
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
            text = "テスト画面（Compose最小）を開く"
            setOnClickListener { startActivity(Intent(this@MainActivity, HelloActivity::class.java)) }
        })
        col.addView(Button(this).apply {
            text = "本編を開く"
            setOnClickListener { startActivity(Intent(this@MainActivity, ComposeHostActivity::class.java)) }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}

/** Compose が素で動くかだけを見る最小画面。 */
class HelloActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.setStage(this, "hello-created")
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Compose OK - テスト画面は表示できています")
                    }
                }
            }
        }
        CrashLog.setStage(this, "hello-composed")
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
