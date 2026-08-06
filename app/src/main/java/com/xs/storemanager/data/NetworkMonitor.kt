package com.xs.storemanager.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * 网络状态监听。
 * - isOnline(context)：即时判断当前是否有可用网络
 * - observe(context, callback)：注册回调，网络恢复时触发（供自动补录草稿用）
 */
object NetworkMonitor {

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** 监听网络。返回的监听器需在 onDestroy 时注销。回调在任意线程。 */
    fun observe(ctx: Context, onOnline: () -> Unit): ConnectivityManager.NetworkCallback {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onOnline()
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        return cb
    }

    fun unregister(ctx: Context, cb: ConnectivityManager.NetworkCallback) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { cm.unregisterNetworkCallback(cb) }
    }
}
