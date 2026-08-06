package com.xs.storemanager.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DeepSeek 结构化分析客户端。
 * 把用户口述/手输的自然语言销售记录，解析成 StructuredEntry。
 * API Key 存本地（SecurePrefs），仅发给 DeepSeek，不上传我们的服务器。
 */
object DeepSeekClient {
    private const val BASE = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val SYSTEM_PROMPT = """
        你是门店销售数据录入助手。用户会给你一句自然语言描述一笔（或几笔）销售记录，
        请把它解析成结构化 JSON，只输出 JSON 对象，不要任何额外文字。
        输出格式：
        {
          "product_name": "商品名称(字符串，必填)",
          "quantity": 数量(数字，缺省1),
          "cost_price": 成本价(数字，缺省0),
          "sale_price": 售价(数字，必填，若只给一个价格就作为售价),
          "payment_method": "收款方式(店铺收款/备用金/其他，不确定填空)",
          "remark": "备注(可选，原话里顾客/维修等补充信息)"
        }
        规则：
        - 若用户说"进价xx/成本xx/拿货xx"则填 cost_price，售价 sale_price 按"卖/售/收/卖了xx"判断；
        - 只提到一个价格时默认是售价；
        - 金额单位是元；
        - 一句话可能含多笔，但一次只解析最重要的那一笔；若明显是分多笔，则解析第一笔并在 remark 注明其余。
    """.trimIndent()

    private fun buildBody(text: String): JSONObject {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        messages.put(JSONObject().put("role", "user").put("content", text))
        return JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("temperature", 0.2)
            .put("max_tokens", 512)
    }

    /** 把自然语言解析为结构化录入对象；失败抛 ApiException */
    suspend fun analyze(ctx: Context, text: String): StructuredEntry = withContext(Dispatchers.IO) {
        val apiKey = SecurePrefs.getDeepSeekKey(ctx)
            ?: throw ApiException("未配置 DeepSeek API Key，请到设置里填写")

        val req = Request.Builder()
            .url(BASE)
            .header("Authorization", "Bearer $apiKey")
            .post(buildBody(text).toString().toRequestBody(JSON))
            .build()

        val resp = client.newCall(req).execute()
        val body = resp.body?.string() ?: ""
        if (!resp.isSuccessful) {
            val errMsg = runCatching {
                val o = JSONObject(body)
                o.optJSONObject("error")?.optString("message") ?: o.optString("message", "")
            }.getOrDefault("")
            throw ApiException("DeepSeek 调用失败(${resp.code}) ${errMsg}".trim())
        }

        val obj = JSONObject(body)
        val content = obj.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content", "")
            ?: throw ApiException("DeepSeek 返回异常")

        parseJsonContent(content)
    }

    /** DeepSeek 可能返回带 ```json 代码块 或 前后说明文字，这里做容错提取 */
    private fun parseJsonContent(content: String): StructuredEntry {
        var s = content.trim()
        // 去掉 ```json ... ``` 围栏
        s = s.replace(Regex("```(?:json)?\\s*", RegexOption.IGNORE_CASE), "").replace("```", "").trim()
        // 提取第一个 { ... } 块
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) s = s.substring(start, end + 1)
        val o = runCatching { JSONObject(s) }
            .getOrElse { throw ApiException("DeepSeek 未能解析为有效结构，请重试或手动录入") }

        fun num(k: String, def: Double): Double {
            val v = o.opt(k)
            return when (v) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull() ?: def
                else -> def
            }
        }

        val productName = o.optString("product_name", "").trim()
        if (productName.isBlank()) throw ApiException("未能识别商品名称，请补充或手动录入")

        return StructuredEntry(
            productName = productName,
            quantity = num("quantity", 1.0).takeIf { it > 0 } ?: 1.0,
            costPrice = num("cost_price", 0.0),
            salePrice = num("sale_price", 0.0),
            paymentMethod = o.optString("payment_method", "").trim(),
            remark = o.optString("remark", "").trim(),
        )
    }
}
