package com.xs.storemanager.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：App 任何未捕获异常都会写入本地文件，便于排查秒退。
 * 崩溃日志存到 filesDir/crash_logs/ 下，按时间命名。
 */
object CrashLogger {

    fun install(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrash(context, throwable)
            } catch (_: Exception) {
            }
            // 交给系统默认处理（通常结束进程）
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun saveCrash(context: Context, throwable: Throwable): String {
        val dir = File(context.filesDir, "crash_logs").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "crash_$ts.txt")
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val content = """
            ========================================
            崩溃时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}
            线程: ${Thread.currentThread().name}
            ========================================
            ${sw.toString()}
        """.trimIndent()
        file.writeText(content)
        return file.absolutePath
    }
}
