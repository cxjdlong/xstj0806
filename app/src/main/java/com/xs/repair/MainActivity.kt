package com.xs.repair

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
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
import java.io.File

/**
 * 「手机维修」App —— 全屏 WebView 壳。
 *
 * 服务器地址**不内置**：首次打开让用户自己填（填过就记住，以后直接进）。
 * 需要换服务器时，从错误页点「修改服务器地址」即可。
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

    /** 服务器地址填写界面 */
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
            text = "请填写服务器地址"
            textSize = 14f
            setTextColor(0xFF6B7280.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 22)
        }
        serverInput = EditText(this).apply {
            hint = "例如  192.168.10.10:19117"
            textSize = 15f
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setPadding(28, 30, 28, 30)
        }
        val tip = TextView(this).apply {
            text = "在店里/家里填内网地址（如 192.168.10.10:19117）；\n在外面填外网域名（如 xs.dx66.top:8888）。\n填好后会自动记住，下次直接进。"
            textSize = 12f
            setTextColor(0xFF9AA1AE.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        }
        val save = Button(this).apply {
            text = "保存并打开"
            setOnClickListener {
                val v = serverInput.text.toString().trim()
                if (v.isBlank()) {
                    toast("请填写服务器地址")
                    return@setOnClickListener
                }
                loadServer(v)
            }
        }
        box.addView(title)
        box.addView(sub)
        box.addView(serverInput, LinearLayout.LayoutParams(-1, -2))
        box.addView(tip)
        box.addView(save, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    private fun showSetup() {
        serverInput.setText(serverUrl)
        serverInput.setSelection(serverInput.text.length)
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
        /** 打开后直接进手机版界面 */
        private const val HOME_PATH = "/#/m/home"
    }
}
