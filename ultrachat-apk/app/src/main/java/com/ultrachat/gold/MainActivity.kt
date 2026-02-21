package com.ultrachat.gold

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    // File picker launcher
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            when {
                data?.clipData != null -> {
                    val count = data.clipData!!.itemCount
                    Array(count) { i -> data.clipData!!.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                else -> null
            }
        } else null
        fileUploadCallback?.onReceiveValue(uris)
        fileUploadCallback = null
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        webView.evaluateJavascript(
            "window.onPermissionResult && window.onPermissionResult($granted)", null
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        // Splash screen
        val splashScreen = installSplashScreen()

        super.onCreate(savedInstanceState)

        // Edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Prevent screenshots (security)
        // window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        setupWebView()

        // Load the app
        webView.loadUrl("file:///android_asset/www/index.html")

        splashScreen.setKeepOnScreenCondition { false }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "UltraChatAndroid/8.0 " + userAgentString

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                forceDark = WebSettings.FORCE_DARK_AUTO
            }
        }

        // Enable WebView debugging in debug builds
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // Add JavaScript Interface bridges
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        webView.addJavascriptInterface(StorageBridge(), "NativeStorage")

        webView.webChromeClient = object : WebChromeClient() {
            // Handle file uploads
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                val intent = fileChooserParams.createIntent()
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                try {
                    filePickerLauncher.launch(intent)
                } catch (e: Exception) {
                    fileUploadCallback = null
                    return false
                }
                return true
            }

            // Handle permission requests (camera, mic, location)
            override fun onPermissionRequest(request: PermissionRequest) {
                val permissions = request.resources.mapNotNull { res ->
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                        else -> null
                    }
                }.toTypedArray()

                if (permissions.isEmpty()) {
                    request.grant(request.resources)
                    return
                }

                val allGranted = permissions.all {
                    ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                }

                if (allGranted) {
                    request.grant(request.resources)
                } else {
                    ActivityCompat.requestPermissions(this@MainActivity, permissions, 101)
                    request.grant(request.resources)
                }
            }

            // Geolocation
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                val fineLocation = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                if (fineLocation) {
                    callback.invoke(origin, true, false)
                } else {
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                    callback.invoke(origin, true, false)
                }
            }

            // Progress indicator
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
            }

            // Console messages for debugging
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                android.util.Log.d("UltraChat_JS", "${consoleMessage.message()} [${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}]")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject native bridge info
                view?.evaluateJavascript("""
                    window.isNativeAndroid = true;
                    window.appVersion = '8.0';
                    document.dispatchEvent(new Event('nativeReady'));
                """, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return when {
                    url.startsWith("tel:") -> { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse(url))); true }
                    url.startsWith("mailto:") -> { startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(url))); true }
                    url.startsWith("file:///android_asset") -> false
                    url.startsWith("http") -> {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true
                    }
                    else -> false
                }
            }
        }
    }

    // Handle back button
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    // JavaScript Bridge for native features
    inner class AndroidBridge {

        @JavascriptInterface
        fun showToast(message: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
        }

        @JavascriptInterface
        fun getDeviceInfo(): String {
            return JSONObject().apply {
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("version", Build.VERSION.RELEASE)
                put("sdk", Build.VERSION.SDK_INT)
                put("isAndroid", true)
                put("appVersion", "8.0")
            }.toString()
        }

        @JavascriptInterface
        fun vibrate(duration: Int) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(android.os.VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(android.os.Vibrator::class.java)
            }
            vibrator?.vibrate(
                android.os.VibrationEffect.createOneShot(duration.toLong(), android.os.VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }

        @JavascriptInterface
        fun shareText(text: String, title: String) {
            runOnUiThread {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    putExtra(Intent.EXTRA_TITLE, title)
                }
                startActivity(Intent.createChooser(intent, title))
            }
        }

        @JavascriptInterface
        fun openBiometricAuth(reason: String) {
            runOnUiThread {
                val biometricManager = BiometricManager.from(this@MainActivity)
                val canAuth = biometricManager.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
                    val executor: Executor = ContextCompat.getMainExecutor(this@MainActivity)
                    val prompt = BiometricPrompt(this@MainActivity, executor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                webView.post {
                                    webView.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(true)", null)
                                }
                            }
                            override fun onAuthenticationFailed() {
                                webView.post {
                                    webView.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(false)", null)
                                }
                            }
                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                webView.post {
                                    webView.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(false, '$errString')", null)
                                }
                            }
                        })

                    val promptInfo = BiometricPrompt.PromptInfo.Builder()
                        .setTitle("UltraChat")
                        .setSubtitle(reason)
                        .setAllowedAuthenticators(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                        )
                        .build()
                    prompt.authenticate(promptInfo)
                } else {
                    webView.evaluateJavascript("window.onBiometricResult && window.onBiometricResult(false, 'not_available')", null)
                }
            }
        }

        @JavascriptInterface
        fun requestPermissions(permsJson: String) {
            val perms = mutableListOf<String>()
            if (permsJson.contains("camera")) perms.add(Manifest.permission.CAMERA)
            if (permsJson.contains("microphone")) perms.add(Manifest.permission.RECORD_AUDIO)
            if (permsJson.contains("location")) {
                perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
                perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (permsJson.contains("storage")) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            if (permsJson.contains("notifications") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
        }

        @JavascriptInterface
        fun exitApp() {
            runOnUiThread { finishAffinity() }
        }
    }

    // Native Storage bridge (persistent key-value)
    inner class StorageBridge {
        private val prefs by lazy { getSharedPreferences("ultrachat_store", MODE_PRIVATE) }

        @JavascriptInterface
        fun setItem(key: String, value: String): Boolean {
            return try {
                prefs.edit().putString(key, value).apply()
                true
            } catch (e: Exception) { false }
        }

        @JavascriptInterface
        fun getItem(key: String): String? {
            return prefs.getString(key, null)
        }

        @JavascriptInterface
        fun removeItem(key: String): Boolean {
            return try {
                prefs.edit().remove(key).apply()
                true
            } catch (e: Exception) { false }
        }

        @JavascriptInterface
        fun clear(): Boolean {
            return try {
                prefs.edit().clear().apply()
                true
            } catch (e: Exception) { false }
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
