package com.xs.storemanager.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条本地草稿（离线录入暂存） */
data class DraftItem(
    val id: Long = System.currentTimeMillis(),
    val text: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = DRAFT_PENDING, // pending=待补录 done=已录入 failed=失败
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("createdAt", createdAt)
        .put("status", status)

    companion object {
        const val DRAFT_PENDING = "pending"
        const val DRAFT_DONE = "done"
        const val DRAFT_FAILED = "failed"

        fun fromJson(o: JSONObject): DraftItem = DraftItem(
            id = o.optLong("id", System.currentTimeMillis()),
            text = o.optString("text", ""),
            createdAt = o.optLong("createdAt", System.currentTimeMillis()),
            status = o.optString("status", DRAFT_PENDING),
        )
    }
}

/**
 * 本地草稿仓库：多条草稿以 JSON 数组持久化在 SharedPreferences（轻量，无需数据库）。
 * 断网时录入的文字先存这里，联网后逐个补录。
 */
object DraftsRepository {
    private const val PREFS = "store_manager_drafts"
    private const val KEY_DRAFTS = "drafts"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<DraftItem> {
        val raw = prefs(ctx).getString(KEY_DRAFTS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { DraftItem.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun save(ctx: Context, drafts: List<DraftItem>) {
        val arr = JSONArray()
        drafts.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_DRAFTS, arr.toString()).apply()
    }

    /** 新增一条草稿（多条记录=多次调用，各自独立一条） */
    fun add(ctx: Context, text: String): DraftItem {
        val item = DraftItem(text = text)
        val updated = load(ctx) + item
        save(ctx, updated)
        return item
    }

    fun update(ctx: Context, item: DraftItem) {
        save(ctx, load(ctx).map { if (it.id == item.id) item else it })
    }

    fun updateStatus(ctx: Context, id: Long, status: String) {
        update(ctx, load(ctx).find { it.id == id }?.copy(status = status) ?: return)
    }

    fun remove(ctx: Context, id: Long) {
        save(ctx, load(ctx).filterNot { it.id == id })
    }

    fun pending(ctx: Context): List<DraftItem> =
        load(ctx).filter { it.status == DraftItem.DRAFT_PENDING }
}
