package com.local.geminiwrapper

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val GEMINI_URL = "https://gemini.google.com/app"
        private const val PREFS_NAME = "gemini_wrapper_prefs"
        private const val KEY_PROXY_ENABLED = "proxy_enabled"
        private const val KEY_PROXY_HOST = "proxy_host"
        private const val KEY_PROXY_PORT = "proxy_port"
        private const val DEFAULT_PROXY_PORT = "7890"

        private val IN_APP_HOST_SUFFIXES = listOf(
            "gemini.google.com",
            "accounts.google.com",
            "accounts.youtube.com",
            "myaccount.google.com",
            "gds.google.com",
            "consent.google.com",
            "ogs.google.com",
            "bard.google.com",
            "googleusercontent.com"
        )
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: Button
    private lateinit var errorProxyButton: Button
    private lateinit var menuFab: FloatingActionButton

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var lastBackPressedTime: Long = 0L
    private var isDesktopMode: Boolean = false
    private var defaultMobileUa: String = ""

    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileUploadCallback ?: return@registerForActivityResult
        fileUploadCallback = null

        if (result.resultCode != Activity.RESULT_OK || result.data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }

        val data = result.data!!
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clipData ->
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i).uri?.let { uris.add(it) }
            }
        } ?: data.data?.let { uri ->
            uris.add(uri)
        }

        callback.onReceiveValue(if (uris.isNotEmpty()) uris.toTypedArray() else null)
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val request = pendingWebPermissionRequest ?: return@registerForActivityResult
        pendingWebPermissionRequest = null

        val grantedResources = mutableListOf<String>()
        if (grants[Manifest.permission.RECORD_AUDIO] == true &&
            request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        ) {
            grantedResources.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        }
        if (grants[Manifest.permission.CAMERA] == true &&
            request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        ) {
            grantedResources.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        }

        if (grantedResources.isNotEmpty()) {
            request.grant(grantedResources.toTypedArray())
        } else {
            request.deny()
            Toast.makeText(this, "未授予麦克风或相机权限", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val rootContainer = findViewById<View>(R.id.rootContainer)
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorView = findViewById(R.id.errorView)
        errorMessage = findViewById(R.id.errorMessage)
        retryButton = findViewById(R.id.retryButton)
        errorProxyButton = findViewById(R.id.errorProxyButton)
        menuFab = findViewById(R.id.menuFab)

        retryButton.setOnClickListener {
            reloadPage()
        }

        errorProxyButton.setOnClickListener {
            showProxySettingsDialog()
        }

        menuFab.setOnClickListener {
            showQuickMenuDialog()
        }

        setupWebView()
        setupLongPressImageSave()
        setupBackNavigation()

        // 启动时优先应用 App 内独立代理配置，再加载页面
        applySavedProxy {
            if (savedInstanceState != null) {
                webView.restoreState(savedInstanceState)
            } else {
                webView.loadUrl(GEMINI_URL)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT

            defaultMobileUa = buildSanitizedUserAgent(userAgentString)
            userAgentString = defaultMobileUa
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }

        // 注册 Blob/DataURL 下载桥接
        webView.addJavascriptInterface(DownloadBridge(), "AndroidDownloadBridge")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            handleFileDownload(url, userAgent, contentDisposition, mimeType)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                val host = uri.host?.lowercase() ?: return false

                if (isInAppHost(host)) {
                    return false
                }

                openExternalUrl(uri)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                errorView.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    progressBar.visibility = View.GONE
                    webView.visibility = View.GONE
                    errorView.visibility = View.VISIBLE
                    val desc = error?.description?.toString() ?: "网络连接失败"
                    val proxyHint = getProxyStatusLabel()
                    errorMessage.text = "无法连接到 Gemini 网页端\n($desc)\n当前代理状态：$proxyHint"
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = try {
                    fileChooserParams?.createIntent()?.apply {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    } ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                } catch (_: Exception) {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (_: Exception) {
                    fileUploadCallback = null
                    Toast.makeText(this@MainActivity, "无法打开文件选择器", Toast.LENGTH_SHORT).show()
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val neededPermissions = mutableListOf<String>()

                if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    neededPermissions.add(Manifest.permission.RECORD_AUDIO)
                }

                if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    neededPermissions.add(Manifest.permission.CAMERA)
                }

                if (neededPermissions.isEmpty()) {
                    request.grant(request.resources)
                } else {
                    pendingWebPermissionRequest = request
                    mediaPermissionLauncher.launch(neededPermissions.toTypedArray())
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val hitTestResult = view?.hitTestResult
                val data = hitTestResult?.extra
                if (!data.isNullOrBlank()) {
                    val uri = Uri.parse(data)
                    val host = uri.host?.lowercase().orEmpty()
                    if (isInAppHost(host)) {
                        view.loadUrl(data)
                    } else {
                        openExternalUrl(uri)
                    }
                    return false
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val tempWebView = WebView(this@MainActivity)
                tempWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        req: WebResourceRequest?
                    ): Boolean {
                        val url = req?.url ?: return false
                        if (isInAppHost(url.host?.lowercase().orEmpty())) {
                            webView.loadUrl(url.toString())
                        } else {
                            openExternalUrl(url)
                        }
                        tempWebView.destroy()
                        return true
                    }
                }
                transport.webView = tempWebView
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    // ==================== App 内独立代理 (ProxyController) ====================

    private fun getProxyStatusLabel(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_PROXY_ENABLED, false)
        val host = prefs.getString(KEY_PROXY_HOST, "").orEmpty().trim()
        val port = prefs.getString(KEY_PROXY_PORT, DEFAULT_PROXY_PORT).orEmpty().trim()
        return if (enabled && host.isNotEmpty() && port.isNotEmpty()) {
            "已开启 ($host:$port)"
        } else {
            "未开启（直连）"
        }
    }

    private fun applySavedProxy(onDone: () -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            onDone()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_PROXY_ENABLED, false)
        val host = prefs.getString(KEY_PROXY_HOST, "").orEmpty().trim()
        val port = prefs.getString(KEY_PROXY_PORT, DEFAULT_PROXY_PORT).orEmpty().trim()
        val mainExecutor = ContextCompat.getMainExecutor(this)

        if (enabled && host.isNotEmpty() && port.isNotEmpty()) {
            try {
                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule("http://$host:$port")
                    .bypassSimpleHostnames()
                    .build()
                ProxyController.getInstance().setProxyOverride(proxyConfig, mainExecutor) {
                    onDone()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "代理格式有误：${e.message}", Toast.LENGTH_SHORT).show()
                onDone()
            }
        } else {
            ProxyController.getInstance().clearProxyOverride(mainExecutor) {
                onDone()
            }
        }
    }

    private fun showProxySettingsDialog() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            Toast.makeText(this, "当前手机系统 WebView 版本过低，不支持应用内独立代理", Toast.LENGTH_LONG).show()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentEnabled = prefs.getBoolean(KEY_PROXY_ENABLED, false)
        val currentHost = prefs.getString(KEY_PROXY_HOST, "").orEmpty()
        val currentPort = prefs.getString(KEY_PROXY_PORT, DEFAULT_PROXY_PORT).orEmpty()

        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val switchView = SwitchMaterial(this).apply {
            text = "启用 App 内独立 HTTP 代理"
            isChecked = currentEnabled
        }

        val hostInput = EditText(this).apply {
            hint = "电脑局域网 IP（如 192.168.1.105）"
            setText(currentHost)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        val portInput = EditText(this).apply {
            hint = "HTTP 端口（默认 7890）"
            setText(currentPort)
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val tipView = TextView(this).apply {
            text = "提示：仅对本 App 生效，不会影响手机 Wi-Fi 和其他应用联网。"
            textSize = 12f
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }

        container.addView(switchView)
        container.addView(hostInput)
        container.addView(portInput)
        container.addView(tipView)

        AlertDialog.Builder(this)
            .setTitle("App 内独立代理设置")
            .setView(container)
            .setPositiveButton("保存并刷新") { _, _ ->
                val enabled = switchView.isChecked
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().trim().ifEmpty { DEFAULT_PROXY_PORT }

                if (enabled && host.isEmpty()) {
                    Toast.makeText(this, "启用代理时请填写电脑 IP 地址", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                prefs.edit()
                    .putBoolean(KEY_PROXY_ENABLED, enabled)
                    .putString(KEY_PROXY_HOST, host)
                    .putString(KEY_PROXY_PORT, port)
                    .apply()

                applySavedProxy {
                    Toast.makeText(this, "代理设置已更新：${getProxyStatusLabel()}", Toast.LENGTH_SHORT).show()
                    reloadPage()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showQuickMenuDialog() {
        val desktopLabel = if (isDesktopMode) "切换回手机版页面" else "切换为桌面版页面（适合宽表格）"
        val items = arrayOf(
            "App 内独立代理（${getProxyStatusLabel()}）",
            "刷新当前页面",
            "回到 Gemini 首页",
            desktopLabel
        )

        AlertDialog.Builder(this)
            .setTitle("Gemini 快捷工具")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showProxySettingsDialog()
                    1 -> reloadPage()
                    2 -> {
                        errorView.visibility = View.GONE
                        webView.visibility = View.VISIBLE
                        webView.loadUrl(GEMINI_URL)
                    }
                    3 -> toggleDesktopMode()
                }
            }
            .show()
    }

    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        webView.settings.userAgentString = if (isDesktopMode) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        } else {
            defaultMobileUa
        }
        reloadPage()
    }

    private fun reloadPage() {
        errorView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        if (webView.url.isNullOrBlank()) {
            webView.loadUrl(GEMINI_URL)
        } else {
            webView.reload()
        }
    }

    // ==================== 长按保存图片到相册 ====================

    private fun setupLongPressImageSave() {
        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val type = result.type
            val imageUrl = result.extra
            if ((type == WebView.HitTestResult.IMAGE_TYPE ||
                    type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) &&
                !imageUrl.isNullOrBlank()
            ) {
                AlertDialog.Builder(this)
                    .setTitle("图片操作")
                    .setItems(arrayOf("保存图片到相册", "在外部浏览器打开图片")) { _, which ->
                        when (which) {
                            0 -> saveImageToGallery(imageUrl)
                            1 -> openExternalUrl(Uri.parse(imageUrl))
                        }
                    }
                    .show()
                true
            } else {
                false
            }
        }
    }

    private fun saveImageToGallery(imageUrl: String) {
        when {
            imageUrl.startsWith("data:", ignoreCase = true) -> {
                ioExecutor.execute {
                    saveDataUrl(imageUrl, "Gemini_${System.currentTimeMillis()}.png", isImage = true)
                }
            }
            imageUrl.startsWith("blob:", ignoreCase = true) -> {
                fetchBlobViaJs(imageUrl, "Gemini_${System.currentTimeMillis()}.png", "image/png", isImage = true)
            }
            else -> {
                Toast.makeText(this, "正在保存图片...", Toast.LENGTH_SHORT).show()
                val cookie = CookieManager.getInstance().getCookie(imageUrl).orEmpty()
                val ua = webView.settings.userAgentString
                ioExecutor.execute {
                    try {
                        val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 15000
                            readTimeout = 20000
                            if (cookie.isNotEmpty()) setRequestProperty("Cookie", cookie)
                            setRequestProperty("User-Agent", ua)
                        }
                        val contentType = conn.contentType?.substringBefore(";")?.trim().orEmpty()
                        val mime = if (contentType.startsWith("image/")) contentType else "image/png"
                        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
                        val bytes = conn.inputStream.use { it.readBytes() }
                        conn.disconnect()

                        val fileName = "Gemini_${System.currentTimeMillis()}.$ext"
                        writeBytesToStorage(bytes, fileName, mime, isImage = true)
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "保存图片失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    // ==================== 文件下载支持（HTTP / DataURL / Blob） ====================

    private fun handleFileDownload(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String
    ) {
        val guessedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            .ifBlank { "gemini_file_${System.currentTimeMillis()}" }

        when {
            url.startsWith("blob:", ignoreCase = true) -> {
                fetchBlobViaJs(url, guessedName, mimeType.ifBlank { "application/octet-stream" }, isImage = false)
            }
            url.startsWith("data:", ignoreCase = true) -> {
                ioExecutor.execute {
                    saveDataUrl(url, guessedName, isImage = mimeType.startsWith("image/"))
                }
            }
            else -> {
                try {
                    val request = DownloadManager.Request(Uri.parse(url)).apply {
                        setMimeType(mimeType)
                        val cookie = CookieManager.getInstance().getCookie(url)
                        if (!cookie.isNullOrBlank()) {
                            addRequestHeader("Cookie", cookie)
                        }
                        addRequestHeader("User-Agent", userAgent)
                        setTitle(guessedName)
                        setDescription("正在下载文件...")
                        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, guessedName)
                    }
                    val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    dm.enqueue(request)
                    Toast.makeText(this, "已开始下载：$guessedName", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "下载失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fetchBlobViaJs(blobUrl: String, fileName: String, mimeType: String, isImage: Boolean) {
        val escapedUrl = blobUrl.replace("\\", "\\\\").replace("'", "\\'")
        val escapedName = fileName.replace("\\", "\\\\").replace("'", "\\'")
        val escapedMime = mimeType.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            (function() {
                fetch('$escapedUrl')
                    .then(function(res) { return res.blob(); })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            AndroidDownloadBridge.saveBase64File(reader.result, '$escapedName', '$escapedMime', $isImage);
                        };
                        reader.readAsDataURL(blob);
                    })
                    .catch(function(err) {
                        AndroidDownloadBridge.onError(String(err));
                    });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private inner class DownloadBridge {
        @JavascriptInterface
        fun saveBase64File(dataUrl: String, fileName: String, mimeType: String, isImage: Boolean) {
            ioExecutor.execute {
                saveDataUrl(dataUrl, fileName, isImage, mimeType)
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "保存失败：$message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveDataUrl(
        dataUrl: String,
        fileName: String,
        isImage: Boolean,
        fallbackMime: String = "application/octet-stream"
    ) {
        try {
            val commaIdx = dataUrl.indexOf(',')
            if (commaIdx == -1) return
            val meta = dataUrl.substring(5, commaIdx) // 去掉 "data:"
            val detectedMime = meta.substringBefore(";").ifBlank { fallbackMime }
            val base64Part = dataUrl.substring(commaIdx + 1)
            val bytes = Base64.decode(base64Part, Base64.DEFAULT)
            writeBytesToStorage(bytes, fileName, detectedMime, isImage)
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "解析并保存失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun writeBytesToStorage(
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        isImage: Boolean
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        if (isImage) "${Environment.DIRECTORY_PICTURES}/Gemini" else Environment.DIRECTORY_DOWNLOADS
                    )
                }
                val collection = if (isImage) {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                }
                val uri = contentResolver.insert(collection, values)
                    ?: throw IllegalStateException("无法创建系统媒体存储项")
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(
                    if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS
                )
                if (!dir.exists()) dir.mkdirs()
                FileOutputStream(File(dir, fileName)).use { it.write(bytes) }
            }

            runOnUiThread {
                val targetHint = if (isImage) "相册 (Pictures/Gemini)" else "下载目录 (Downloads)"
                Toast.makeText(this, "已保存到 $targetHint：$fileName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "写入文件失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 通用工具方法 ====================

    private fun buildSanitizedUserAgent(defaultUa: String): String {
        return defaultUa
            .replace("; wv)", ")")
            .replace(Regex("""Version/\d+\.\d+\s*"""), "")
            .trim()
    }

    private fun isInAppHost(host: String): Boolean {
        return IN_APP_HOST_SUFFIXES.any { suffix ->
            host == suffix || host.endsWith(".$suffix")
        }
    }

    private fun openExternalUrl(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "未找到可用的外部浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressedTime < 2000L) {
                    finish()
                } else {
                    lastBackPressedTime = now
                    Toast.makeText(this@MainActivity, "再按一次返回键退出 Gemini", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        ioExecutor.shutdown()
        webView.destroy()
        super.onDestroy()
    }
}
