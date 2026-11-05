package com.rui.filesmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.init(this)
        CrashLog.setStage(this, "activity-created")
        val crash = CrashLog.read(this)
        if (crash != null) {
            showCrashNatively(crash)
            return
        }
        setContent {
            FilesManagerApp()
        }
    }

    /** Compose を使わずにクラッシュ記録を見せる（Compose自体が落ちる場合の保険）。 */
    private fun showCrashNatively(text: String) {
        val scroll = android.widget.ScrollView(this)
        val col = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val tv = android.widget.TextView(this).apply {
            this.text = text
            textSize = 12f
            setPadding(32, 32, 32, 16)
        }
        val cont = android.widget.Button(this).apply {
            this.text = "記録を消して通常起動"
            setOnClickListener {
                CrashLog.clear(this@MainActivity)
                recreate()
            }
        }
        val close = android.widget.Button(this).apply {
            this.text = "閉じる"
            setOnClickListener { finish() }
        }
        col.addView(tv); col.addView(cont); col.addView(close)
        scroll.addView(col)
        setContentView(scroll)
    }
}
