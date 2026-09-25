package com.xs.repair

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「手机维修」App —— 全屏 WebView 壳。
 *
 * 服务器地址 + 用户名 + 密码**在同一个界面**填写：填完点「登录并进入」，
 * App 直接调后端登录接口拿登录态注入网页，不用再在网页上登一次。
 * 需要换服务器/换账号时，从错误页点「修改服务器地址」回到这个界面。
 *
 * 其它：登录态持久化；支持网页里的传照片（相册多选 / 直接拍照）；下拉刷新；返回键回退。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorMsg: TextView
    private lateinit var urlNote: TextView
    private lateinit var setupBox: LinearLayout
    private lateinit var serverInput: EditText
    private lateinit var userInput: EditText
    private lateinit var pwdInput: EditText

    /** 登录成功后待注入网页的登录态（token to userJson），在页面加载完时塞进 localStorage */
    private var pendingAuth: Pair<String, String>? = null

    /** 用户填的服务器地址（形如 http://192.168.10.10:19117），空表示还没填 */
    private var serverUrl: String = ""

    private val prefs by lazy { getSharedPreferences("app", Context.MODE_PRIVATE) }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback
        val uris: Array<Uri>? = when {
            result.resultCode != Activity.RESULT_OK -> null
            result.data?.clipData != null -> {
                val clip = result.data!!.clipData!!
                Array(clip.itemCount) { clip.getItemAt(it).uri }
            }
            result.data?.data != null -> arrayOf(result.data!!.data!!)
            cameraUri != null -> arrayOf(cameraUri!!)
            else -> null
        }
        cb?.onReceiveValue(uris)
        filePathCallback = null
        cameraUri = null
    }

    /** 点「拍照」但还没拿到相机权限 → 先申请，授权后接着开相机 */
    private var pendingCamera = false

    private val cameraPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingCamera) {
            pendingCamera = false
            if (granted) {
                val cam = buildCameraIntent()
                if (cam != null) {
                    try {
                        fileChooser.launch(cam)
                    } catch (e: Exception) {
                        filePathCallback?.onReceiveValue(null)
                        filePathCallback = null
                        toast("相机打不开，请用「🖼 相册」选照片")
                    }
                } else {
                    fallbackToAlbum("相机不可用，已切换到相册")
                }
            } else {
                // 用户拒绝相机权限：别让这次点击白点，退回相册让他至少能选图
                fallbackToAlbum("未开启相机权限，已切换到相册；要拍照请在系统设置里允许「手机维修」使用相机")
            }
        }
    }

    /** 退回相册选择（保证点击不落空） */
    private fun fallbackToAlbum(msg: String) {
        toast(msg)
        openAlbumChooserInternal()
    }

    private fun openAlbumChooserInternal(): Boolean {
        val content = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val chooser = Intent.createChooser(content, "选择照片")
        return try {
            fileChooser.launch(chooser)
            true
        } catch (e: Exception) {
            filePathCallback = null
            false
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        web = WebView(this)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        swipe = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(0xFF2563EB.toInt())
            setOnRefreshListener { if (serverUrl.isNotBlank()) web.reload() }
        }

        // 打不开时的提示页
        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(60, 0, 60, 0)
        }
        errorMsg = TextView(this).apply {
            text = "打不开，请检查网络"
            setTextColor(0xFF6B7280.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
        }
        urlNote = TextView(this).apply {
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 20)
        }
        val retry = Button(this).apply {
            text = "重新加载"
            setOnClickListener { if (serverUrl.isNotBlank()) loadServer(serverUrl) }
        }
        val editServer = Button(this).apply {
            text = "修改服务器地址"
            setOnClickListener { showSetup() }
        }
        errorBox.addView(errorMsg)
        errorBox.addView(urlNote)
        errorBox.addView(retry)
        errorBox.addView(editServer)

        // 服务器地址填写页（第一次打开时显示）
        setupBox = buildSetupView()

        swipe.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(swipe, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress, FrameLayout.LayoutParams(-1, 8))
        root.addView(errorBox, FrameLayout.LayoutParams(-1, -1))
        root.addView(setupBox, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        setupWebView()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPerm.launch(Manifest.permission.CAMERA)
        }

        serverUrl = prefs.getString(KEY_SERVER, "").orEmpty()
        if (serverUrl.isBlank()) {
            showSetup()          // 第一次用：先填服务器地址
        } else {
            loadServer(serverUrl)
        }
    }

    /** 服务器地址填写界面（服务器地址 + 用户名 + 密码，一个界面填完直接登录） */
    private fun buildSetupView(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(70, 0, 70, 0)
            visibility = View.GONE
        }
        val title = TextView(this).apply {
            text = "手机维修"
            textSize = 24f
            setTextColor(0xFF2563EB.toInt())
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        val sub = TextView(this).apply {
            text = "填服务器地址和账号，登录后直接进系统"
            textSize = 14f
            setTextColor(0xFF6B7280.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 22)
        }
        serverInput = EditText(this).apply {
            hint = "服务器地址，例如 192.168.10.10:19117"
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setPadding(28, 30, 28, 30)
        }
        userInput = EditText(this).apply {
            hint = "用户名"
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(28, 30, 28, 30)
        }
        pwdInput = EditText(this).apply {
            hint = "密码"
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(28, 30, 28, 30)
        }
        val tip = TextView(this).apply {
            text = "在店里填内网地址（如 192.168.10.10:19117）；\n在外面填外网域名（如 xs.dx66.top:8888）。\n登录成功后自动记住，下次打开直接进系统。"
            textSize = 12f
            setTextColor(0xFF9AA1AE.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        }
        val save = Button(this).apply {
            text = "登录并进入"
            setOnClickListener {
                val v = serverInput.text.toString().trim()
                val u = userInput.text.toString().trim()
                val p = pwdInput.text.toString()
                if (v.isBlank()) {
                    toast("请填写服务器地址")
                    return@setOnClickListener
                }
                if (u.isBlank() || p.isBlank()) {
                    toast("请填写用户名和密码")
                    return@setOnClickListener
                }
                doLogin(normalize(v), u, p)
            }
        }
        box.addView(title)
        box.addView(sub)
        box.addView(serverInput, LinearLayout.LayoutParams(-1, -2))
        box.addView(userInput, LinearLayout.LayoutParams(-1, -2))
        box.addView(pwdInput, LinearLayout.LayoutParams(-1, -2))
        box.addView(tip)
        box.addView(save, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    /** 用填的地址 + 账号调后端登录接口，成功就把登录态注入网页（不用再在网页上登一次） */
    private fun doLogin(base: String, username: String, password: String) {
        loginBtnEnabled(false)
        toast("正在登录…")
        Thread {
            var ok = false
            var errMsg = "登录失败"
            var token = ""
            var userJson = ""
            try {
                val conn = (URL("$base/api/auth/login").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10000
                    readTimeout = 20000
                    setRequestProperty("Content-Type", "application/json")
                }
                val body = JSONObject().put("username", username).put("password", password).toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = JSONObject(text)
                if (code in 200..299 && json.optInt("code", -1) == 0) {
                    val data = json.getJSONObject("data")
                    token = data.getString("token")
                    userJson = data.get("user").toString()
                    ok = true
                } else {
                    errMsg = json.optString("msg", "用户名或密码不对")
                }
            } catch (e: Exception) {
                errMsg = "连不上服务器：" + (e.message ?: "网络错误")
            }
            runOnUiThread {
                loginBtnEnabled(true)
                if (ok) {
                    prefs.edit()
                        .putString(KEY_SERVER, base)
                        .putString(KEY_USER, username)
                        .apply()
                    serverUrl = base
                    pendingAuth = token to userJson
                    toast("登录成功")
                    loadServer(base)
                } else {
                    toast(errMsg)
                }
            }
        }.start()
    }

    private fun loginBtnEnabled(enabled: Boolean) {
        setupBox.getChildAt(setupBox.childCount - 1)?.let { it.isEnabled = enabled }
    }

    private fun showSetup() {
        serverInput.setText(serverUrl)
        serverInput.setSelection(serverInput.text.length)
        userInput.setText(prefs.getString(KEY_USER, "").orEmpty())
        pwdInput.setText("")
        setupBox.visibility = View.VISIBLE
        swipe.visibility = View.GONE
        errorBox.visibility = View.GONE
    }

    /** 补全协议头并去掉结尾斜杠 */
    private fun normalize(raw: String): String {
        var s = raw.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s.trimEnd('/')
    }

    private fun loadServer(raw: String) {
        val base = normalize(raw)
        serverUrl = base
        prefs.edit().putString(KEY_SERVER, base).apply()
        setupBox.visibility = View.GONE
        errorBox.visibility = View.GONE
        swipe.visibility = View.VISIBLE
        web.loadUrl(base + HOME_PATH)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportZoom(false)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = true
        // 让 window.open / target=_blank 在同一 WebView 内打开
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        // 给网页暴露原生的「存相册 / 分享到微信」能力（WebView 里没有 navigator.share）
        web.addJavascriptInterface(XsBridge(), "XsBridge")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (!url.startsWith("http")) {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true
                    } catch (e: Exception) {
                        true
                    }
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                swipe.isRefreshing = false
                // 刚在 App 界面登录成功 → 把登录态写进网页（省掉再登一次）
                val auth = pendingAuth
                if (auth != null && view != null) {
                    pendingAuth = null
                    val js = "localStorage.setItem('token', ${JSONObject.quote(auth.first)});" +
                        "localStorage.setItem('user', ${JSONObject.quote(auth.second)});" +
                        "location.replace('/#/m/home');"
                    view.evaluateJavascript(js, null)
                }
            }

            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    swipe.isRefreshing = false
                    swipe.visibility = View.GONE
                    errorMsg.text = "打不开，请检查网络"
                    urlNote.text = "当前服务器：" + serverUrl
                    errorBox.visibility = View.VISIBLE
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                // 网页写了 capture="environment"（点了「📷 拍照」）→ 直接开相机
                if (params?.isCaptureEnabled == true) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.CAMERA
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        // 还没授权：先申请，授权后接着开相机（不要静默退化成相册）
                        pendingCamera = true
                        cameraPerm.launch(Manifest.permission.CAMERA)
                        return true
                    }
                    val cam = buildCameraIntent()
                    if (cam != null) {
                        return try {
                            fileChooser.launch(cam)
                            true
                        } catch (e: Exception) {
                            filePathCallback = null
                            openAlbumChooserInternal()
                        }
                    }
                    toast("相机不可用，已切换到相册")
                }
                return openAlbumChooserInternal()
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }

        web.setDownloadListener(DownloadListener { url, _, _, _, _ ->
            try {
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(
                    DownloadManager.Request(Uri.parse(url)).apply {
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "下载文件")
                    }
                )
                toast("已开始下载")
            } catch (e: Exception) {
                toast("下载失败")
            }
        })
    }

    /* ================= 给网页用的原生桥 =================
     * WebView 里既没有 navigator.share（Web Share API 不被 WebView 支持），
     * 「保存到本地」的 <a download> 对 blob: 也无效（DownloadManager 不认 blob:）
     * → 所以这两个能力由原生实现，网页通过 window.XsBridge 调用：
     *   XsBridge.saveImage(base64, filename)              存到手机相册
     *   XsBridge.shareImage(base64, filename, toWechat)   调起微信（失败退系统分享面板）发图
     */
    inner class XsBridge {
        @JavascriptInterface
        fun saveImage(base64: String, filename: String): Boolean {
            return try {
                saveToGallery(Base64.decode(base64, Base64.DEFAULT), safeName(filename))
                toast("已保存到相册")
                true
            } catch (e: Exception) {
                toast("保存失败：" + (e.message ?: "未知错误"))
                false
            }
        }

        @JavascriptInterface
        fun shareImage(base64: String, filename: String, toWechat: Boolean): Boolean {
            return try {
                val f = File(cacheDir, safeName(filename))
                f.writeBytes(Base64.decode(base64, Base64.DEFAULT))
                val uri = FileProvider.getUriForFile(
                    this@MainActivity, "$packageName.fileprovider", f
                )
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (toWechat) {
                    try {
                        startActivity(Intent(send).setPackage("com.tencent.mm"))
                        return true
                    } catch (e: Exception) {
                        toast("没找到微信，已改用系统分享")
                    }
                }
                startActivity(Intent.createChooser(send, "分享维修单"))
                true
            } catch (e: Exception) {
                toast("分享失败：" + (e.message ?: "未知错误"))
                false
            }
        }

        /** 网页里弹原生提示（可选） */
        @JavascriptInterface
        fun toastMsg(msg: String) {
            toast(msg)
        }
    }

    /** 文件名安全化（去掉路径分隔符等非法字符） */
    private fun safeName(name: String): String {
        val n = name.ifBlank { "repair_${System.currentTimeMillis()}.png" }
        return n.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
    }

    /** 写入系统相册：Android 10+ 走 MediaStore（免存储权限），以下走公共 Pictures 目录 */
    private fun saveToGallery(bytes: ByteArray, filename: String) {
        val lower = filename.lowercase()
        val name = if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) filename else "$filename.png"
        val mime = if (name.lowercase().endsWith(".png")) "image/png" else "image/jpeg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/手机维修")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法写入相册")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("无法写入相册")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                throw IllegalStateException("请先在系统设置里允许「手机维修」使用存储")
            }
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "手机维修")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, name)
            out.writeBytes(bytes)
            // 让相册立刻看到
            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(out)))
        }
    }

    private fun buildCameraIntent(): Intent? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val file = File(filesDir, "camera_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            cameraUri = uri
            Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (setupBox.visibility == View.VISIBLE) {
                if (serverUrl.isNotBlank()) showWebAgain()
                return true
            }
            if (errorBox.visibility == View.VISIBLE && serverUrl.isNotBlank()) {
                showWebAgain()
                return true
            }
            if (web.canGoBack()) {
                web.goBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 从设置/错误页回到网页 */
    private fun showWebAgain() {
        setupBox.visibility = View.GONE
        errorBox.visibility = View.GONE
        swipe.visibility = View.VISIBLE
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_USER = "last_user"
        /** 打开后直接进手机版界面 */
        private const val HOME_PATH = "/#/m/home"
    }
}
