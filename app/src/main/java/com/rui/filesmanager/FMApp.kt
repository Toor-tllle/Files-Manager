package com.rui.filesmanager

import android.app.Application

/** プロセス起動直後にクラッシュ記録を有効化するための Application。 */
class FMApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.init(this)
        CrashLog.setStage(this, "app-created")
    }
}
