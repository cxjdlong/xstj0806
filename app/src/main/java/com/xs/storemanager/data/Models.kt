package com.xs.storemanager.data

import org.json.JSONObject

/** 销售概览数据（对应后端 /api/stats/dashboard 返回字段） */
data class DashboardData(
    val todayRevenue: Double = 0.0,
    val todayProfit: Double = 0.0,
    val weekProfit: Double = 0.0,
    val monthProfit: Double = 0.0,
    val yearProfit: Double = 0.0,
    val monthRevenue: Double = 0.0,
    val yearRevenue: Double = 0.0,
) {
    companion object {
        fun fromJson(o: JSONObject): DashboardData {
            fun d(k: String): Double = o.optDouble(k, 0.0)
            return DashboardData(
                todayRevenue = d("today_revenue"),
                todayProfit = d("today_profit"),
                weekProfit = d("week_profit"),
                monthProfit = d("month_profit"),
                yearProfit = d("year_profit"),
                monthRevenue = d("month_revenue"),
                yearRevenue = d("year_revenue"),
            )
        }
    }
}

/** 结构化录入结果（由 DeepSeek 从自然语言中提取） */
data class StructuredEntry(
    val productName: String = "",
    val quantity: Double = 1.0,
    val costPrice: Double = 0.0,
    val salePrice: Double = 0.0,
    val paymentMethod: String = "",
    val remark: String = "",
) {
    fun toRequestBody(): JSONObject {
        val obj = JSONObject()
        obj.put("product_name", productName)
        obj.put("quantity", quantity)
        obj.put("cost_price", costPrice)
        obj.put("sale_price", salePrice)
        if (paymentMethod.isNotBlank()) obj.put("payment_method", paymentMethod)
        if (remark.isNotBlank()) obj.put("remark", remark)
        return obj
    }
}
