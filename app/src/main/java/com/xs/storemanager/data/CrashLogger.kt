package com.xs.storemanager.data

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：App 崩溃时把堆栈写入本地文件（filesDir/crash_logs/）。
 * 崩溃处理器只负责落盘 + 弹出系统级崩溃提示，不做页面跳转（避免主线程崩溃时
 * 启动 Activity 造成死循环/白屏卡死）。
 */
object CrashLogger {

    fun install(context: Context) {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { saveCrash(context, throwable) } catch (_: Exception) {}
        }
    }

    fun buildStack(throwable: Throwable): String {
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

    /** 读取最近一次崩溃日志内容（供设置页/诊断显示） */
    fun latestCrash(context: Context): String? {
        val dir = File(context.filesDir, "crash_logs") ?: return null
        val files = dir.listFiles()?.filter { it.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() } ?: return null
        return files.firstOrNull()?.readText()
    }
}
