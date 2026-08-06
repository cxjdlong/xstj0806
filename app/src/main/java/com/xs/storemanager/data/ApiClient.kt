package com.xs.storemanager.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 统一的业务异常，message 可直接展示给用户 */
class ApiException(message: String) : Exception(message)

/**
 * 后端「多门店销售系统」客户端。
 * 所有方法走协程，自动带 token，返回解析后的结果。
 */
object ApiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun baseUrl(ctx: Context) = SecurePrefs.getBaseUrl(ctx)

    private fun tokenHeader(ctx: Context): String? =
        SecurePrefs.getToken(ctx)?.let { "Bearer $it" }

    private fun jsonBody(json: JSONObject): okhttp3.RequestBody =
        json.toString().toRequestBody(JSON)

    private suspend fun exec(
        ctx: Context,
        method: String,
        path: String,
        body: JSONObject? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val url = baseUrl(ctx) + path
        val builder = Request.Builder().url(url).method(method, body?.let { jsonBody(it) })
        tokenHeader(ctx)?.let { builder.header("Authorization", it) }
        val resp = client.newCall(builder.build()).execute()
        val text = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
            val msg = runCatching { JSONObject(text).optString("msg") }.getOrDefault("HTTP ${resp.code}")
            throw ApiException(msg.ifBlank { "请求失败(${resp.code})" })
        }
        val obj = runCatching { JSONObject(text) }.getOrElse { throw IOException("响应格式错误") }
        if (obj.optInt("code", -1) != 0) {
            throw ApiException(obj.optString("msg", "操作失败"))
        }
        obj
    }

    /** 登录，成功后保存 token 与用户名 */
    suspend fun login(ctx: Context, username: String, password: String) {
        val body = JSONObject().put("username", username).put("password", password)
        val obj = exec(ctx, "POST", "/api/auth/login", body)
        val data = obj.optJSONObject("data") ?: throw ApiException("登录响应缺少数据")
        val token = data.optString("token", "")
        if (token.isBlank()) throw ApiException("登录未返回 token")
        SecurePrefs.saveCredential(ctx, username, token)
    }

    /** 销售概览 */
    suspend fun dashboard(ctx: Context): DashboardData {
        val obj = exec(ctx, "GET", "/api/stats/dashboard")
        val data = obj.optJSONObject("data") ?: return DashboardData()
        return DashboardData.fromJson(data)
    }

    /** 录入销售，返回后端 msg（成功提示） */
    suspend fun createSale(ctx: Context, entry: StructuredEntry): String {
        val obj = exec(ctx, "POST", "/api/sales", entry.toRequestBody())
        return obj.optString("msg", "录入成功")
    }

    /** 纯文字录入：把自然语言文字 + AI地址提交给后端，后端调AI结构化并入库 */
    suspend fun createTextEntry(ctx: Context, text: String, aiBase: String = "", apiKey: String = ""): String {
        val body = JSONObject().put("text", text)
        if (aiBase.isNotBlank()) body.put("ai_base", aiBase)
        if (apiKey.isNotBlank()) body.put("api_key", apiKey)
        val obj = exec(ctx, "POST", "/api/text-entries", body)
        return obj.optString("msg", "录入成功")
    }
}
