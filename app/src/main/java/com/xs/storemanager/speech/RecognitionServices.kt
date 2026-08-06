package com.xs.storemanager.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.speech.RecognitionService

/** 一个可选的语音识别服务（由某输入法/App 提供） */
data class RecognitionServiceOption(
    val label: String,       // 显示名，如 "搜狗输入法 语音识别"
    val packageName: String, // 包名
    val className: String,   // 服务组件类名
    val isDefault: Boolean,
) {
    val componentName: ComponentName get() = ComponentName(packageName, className)
}

/**
 * 枚举手机里所有已注册的语音识别服务（通常就是各输入法提供的引擎）。
 * 供用户在设置里选择：App 语音按钮用哪个输入法引擎做识别。
 */
object RecognitionServices {

    /** 枚举所有可用的语音识别服务 */
    fun list(context: Context): List<RecognitionServiceOption> {
        val pm = context.packageManager
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        val infos: List<ResolveInfo> = try {
            pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            emptyList()
        }

        // 系统当前默认的语音识别服务
        val default = try {
            val def = pm.resolveService(intent, PackageManager.GET_META_DATA)
            def?.serviceInfo?.let { it.packageName to it.name }
        } catch (e: Exception) {
            null
        }

        return infos.map { info ->
            val si = info.serviceInfo
            val label = try {
                si.loadLabel(pm).toString()
            } catch (e: Exception) {
                si.packageName
            }
            RecognitionServiceOption(
                label = label,
                packageName = si.packageName,
                className = si.name,
                isDefault = default != null && si.packageName == default.first && si.name == default.second,
            )
        }.distinctBy { it.packageName to it.className }
    }

    /** 保存客户选择的识别服务（包名+类名），用于指定调用 */
    fun saveSelection(ctx: Context, pkg: String, cls: String) {
        ctx.getSharedPreferences("voice_engine", Context.MODE_PRIVATE)
            .edit().putString("pkg", pkg).putString("cls", cls).apply()
    }

    fun getSelection(ctx: Context): Pair<String, String>? {
        val p = ctx.getSharedPreferences("voice_engine", Context.MODE_PRIVATE)
        val pkg = p.getString("pkg", null) ?: return null
        val cls = p.getString("cls", null) ?: return null
        return pkg to cls
    }

    fun clearSelection(ctx: Context) {
        ctx.getSharedPreferences("voice_engine", Context.MODE_PRIVATE).edit().clear().apply()
    }
}
