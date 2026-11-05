package com.rui.filesmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 明るいテーマなので、ステータスバー/ナビバーのアイコンは濃い色で表示する
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = true
        insetsController.isAppearanceLightNavigationBars = true
        CrashLog.init(this)
        CrashLog.setStage(this, "activity-created")
        setContent { FilesManagerApp() }
    }
}
