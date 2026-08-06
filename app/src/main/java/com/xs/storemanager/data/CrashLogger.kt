package com.xs.storemanager.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：App 崩溃时写日志到本地，并直接启动一个页面显示崩溃原因，
 * 这样不需要 adb / 翻目录就能看到具体崩溃堆栈。
 */
object CrashLogger {

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val logPath = try { saveCrash(context, throwable) } catch (_: Exception) { "" }
            // 崩溃时直接显示错误页，而不是闪退
            try {
                val intent = Intent(context, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("crash_log", buildStack(throwable))
                    putExtra("log_path", logPath)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun buildStack(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    fun saveCrash(context: Context, throwable: Throwable): String {
        val dir = File(context.filesDir, "crash_logs").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_$ts.txt")
        val content = """
            ========================================
            崩溃时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}
            线程: ${Thread.currentThread().name}
            ========================================
            ${buildStack(throwable)}
        """.trimIndent()
        file.writeText(content)
        return file.absolutePath
    }
}

/** 崩溃详情页：直接显示崩溃堆栈，方便用户截图发给我 */
class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crash = intent.getStringExtra("crash_log") ?: "未知崩溃"
        val logPath = intent.getStringExtra("log_path") ?: ""

        val text = TextView(this).apply {
            text = "App 发生崩溃，请截图发给开发者：\n\n$crash\n\n日志文件: $logPath"
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this).apply {
            addView(text)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        setContentView(scroll)
    }
}
