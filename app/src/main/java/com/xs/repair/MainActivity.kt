package com.xs.repair

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
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
import java.net.HttpURLConnection
import java.net.URL

/**
 * 「手机维修」App —— 全屏 WebView 壳，加载销售系统手机版界面（/#/m/home）。
 *
 * 关键点：
 * - 登录态（cookie / localStorage）默认持久化，不用反复登录
 * - 支持网页里的 <input type="file">：可选相册多张、也可直接拍照（维修留痕传照片必需）
 * - 下拉刷新、返回键回退、加载进度条、断网重试
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var progress: ProgressBar
    private lateinit var errorBox: LinearLayout
    private lateinit var errorMsg: TextView
    private lateinit var urlNote: TextView

    /** 当前正在用的地址（内网优先，失败可手动切外网） */
    private var currentUrl = LAN_URL

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

    private val cameraPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也没关系：只影响「拍照」选项，相册仍可用 */ }

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
            setOnRefreshListener { web.reload() }
        }

        // 断网/加载失败时的重试页（可手动在内网/外网地址间切换）
        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        errorMsg = TextView(this).apply {
            text = "打不开，请检查网络"
            setTextColor(0xFF6B7280.toInt())
            textSize = 15f
            gravity = android.view.Gravity.CENTER
        }
        urlNote = TextView(this).apply {
            setTextColor(0xFF9AA1AE.toInt())
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 12, 0, 18)
        }
        val retry = Button(this).apply {
            text = "重新加载"
            setOnClickListener { switchTo(currentUrl) }
        }
        val useLan = Button(this).apply {
            text = "用家里/店里的地址（内网）"
            setOnClickListener { switchTo(LAN_URL) }
        }
        val useWan = Button(this).apply {
            text = "用外网地址"
            setOnClickListener { switchTo(WAN_URL) }
        }
        errorBox.addView(errorMsg)
        errorBox.addView(urlNote)
        errorBox.addView(retry)
        errorBox.addView(useLan)
        errorBox.addView(useWan)

        swipe.addView(web, FrameLayout.LayoutParams(-1, -1))
        root.addView(swipe, FrameLayout.LayoutParams(-1, -1))
        root.addView(progress, FrameLayout.LayoutParams(-1, 8))
        root.addView(errorBox, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        setupWebView()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPerm.launch(Manifest.permission.CAMERA)
        }

        // 先探测家里/店里的内网地址，通了就用（快），不通自动走外网
        pickBaseUrl()
    }

    /** 自动选地址：内网可达就用内网，否则用外网 */
    private fun pickBaseUrl() {
        Thread {
            val lanOk = try {
                val c = URL(LAN_PROBE).openConnection() as HttpURLConnection
                c.connectTimeout = 1500
                c.readTimeout = 1500
                c.requestMethod = "GET"
                val code = c.responseCode
                c.disconnect()
                code in 200..499
            } catch (e: Exception) {
                false
            }
            runOnUiThread { switchTo(if (lanOk) LAN_URL else WAN_URL) }
        }.start()
    }

    private fun switchTo(url: String) {
        currentUrl = url
        errorBox.visibility = View.GONE
        web.visibility = View.VISIBLE
        web.loadUrl(url)
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
        // 让 window.open / target=_blank 在同一 WebView 内打开（报价单打印等）
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
                // 站外链接（http/https 之外，如 tel: / mailto:）交给系统
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
                // 只处理主页面失败
                if (request?.isForMainFrame == true) {
                    swipe.isRefreshing = false
                    web.visibility = View.GONE
                    errorMsg.text = "打不开，请检查网络"
                    urlNote.text = "当前地址：" + currentUrl.substringBefore("/#/")
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

                // 网页里写了 capture="environment"（点了「📷 拍照」按钮）→ 直接开相机，不再弹选择器
                if (params?.isCaptureEnabled == true) {
                    val cam = buildCameraIntent()
                    if (cam != null) {
                        return try {
                            fileChooser.launch(cam)
                            true
                        } catch (e: Exception) {
                            filePathCallback = null
                            openAlbumChooser()   // 相机不可用就退回相册
                        }
                    }
                }
                return openAlbumChooser()
            }

            private fun openAlbumChooser(): Boolean {
                val content = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                val chooser = Intent.createChooser(content, "选择照片")
                val camera = buildCameraIntent()
                if (camera != null) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
                }
                return try {
                    fileChooser.launch(chooser)
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                // 网页里如果请求摄像头/麦克风，直接放行（本机自用）
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
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** 家里/店里内网地址（优先，快） */
        private const val LAN_URL = "http://192.168.10.10:19117/#/m/home"
        /** 外网地址（内网不通时用；该域名目前只有 IPv6 解析，手机需支持 IPv6 才连得上） */
        private const val WAN_URL = "https://xs.dx66.top:8888/#/m/home"
        /** 内网连通性探测用 */
        private const val LAN_PROBE = "http://192.168.10.10:19117/"
    }
}
