package com.xs.storemanager.data

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地安全存储：DeepSeek API Key / 后端地址 / 登录凭证。
 * AES-GCM 加密，密钥随机生成后 Base64 存进 SharedPreferences（不上传服务器）。
 */
object SecurePrefs {
    private const val PREFS = "store_manager_secure"
    private const val KEY_ENC_KEY = "enc_key_b64"
    private const val KEY_DEEPSEEK = "deepseek_api_key"
    private const val KEY_BASE_URL = "backend_base_url"
    private const val KEY_USERNAME = "username"
    private const val KEY_TOKEN = "token"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getOrCreateKey(ctx: Context): SecretKey {
        val prefs = prefs(ctx)
        val existing = prefs.getString(KEY_ENC_KEY, null)
        if (existing != null) {
            val raw = Base64.decode(existing, Base64.NO_WRAP)
            return javax.crypto.spec.SecretKeySpec(raw, "AES")
        }
        val kgen = KeyGenerator.getInstance("AES")
        kgen.init(256, SecureRandom())
        val key = kgen.generateKey()
        prefs.edit().putString(KEY_ENC_KEY, Base64.encodeToString(key.encoded, Base64.NO_WRAP)).apply()
        return key
    }

    private fun encrypt(ctx: Context, plain: String): String {
        val key = getOrCreateKey(ctx)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(ctx: Context, b64: String): String {
        val key = getOrCreateKey(ctx)
        val raw = Base64.decode(b64, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    fun saveDeepSeekKey(ctx: Context, key: String) =
        prefs(ctx).edit().putString(KEY_DEEPSEEK, encrypt(ctx, key)).apply()

    fun getDeepSeekKey(ctx: Context): String? {
        val b64 = prefs(ctx).getString(KEY_DEEPSEEK, null) ?: return null
        return try { decrypt(ctx, b64) } catch (e: Exception) { null }
    }

    fun hasDeepSeekKey(ctx: Context): Boolean = !getDeepSeekKey(ctx).isNullOrBlank()

    fun saveBaseUrl(ctx: Context, url: String) =
        prefs(ctx).edit().putString(KEY_BASE_URL, url.trim().trimEnd('/')).apply()

    fun getBaseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
            ?: "http://192.168.10.10:19117"

    fun saveCredential(ctx: Context, username: String, token: String) =
        prefs(ctx).edit().putString(KEY_USERNAME, username).putString(KEY_TOKEN, token).apply()

    fun getUsername(ctx: Context): String? = prefs(ctx).getString(KEY_USERNAME, null)
    fun getToken(ctx: Context): String? = prefs(ctx).getString(KEY_TOKEN, null)
    fun hasToken(ctx: Context): Boolean = !getToken(ctx).isNullOrBlank()
    fun clearCredential(ctx: Context) = prefs(ctx).edit().remove(KEY_USERNAME).remove(KEY_TOKEN).apply()
}
