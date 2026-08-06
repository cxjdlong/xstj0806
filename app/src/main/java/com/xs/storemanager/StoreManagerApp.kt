package com.xs.storemanager

import android.app.Application
import com.xs.storemanager.data.CrashLogger

/** 应用入口：最早阶段安装崩溃捕获，确保任何启动崩溃都能被捕获并显示 */
class StoreManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
