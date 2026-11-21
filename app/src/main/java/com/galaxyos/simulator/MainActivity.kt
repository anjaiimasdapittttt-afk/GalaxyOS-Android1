package com.galaxyos.simulator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var bridge: AndroidBridge
    private val host = "appassets.androidplatform.net"
    private val base = "https://$host/assets/www/"

    // Setiap perubahan status sistem -> perbarui tampilan Quick Settings
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refresh()
    }

    // Launcher izin/dialog harus didaftarkan sebelum Activity STARTED (sebagai properti = aman)
    private val btEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    private val btPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchBluetoothEnable()
            } else {
                notice("Izin Bluetooth ditolak. Bluetooth hanya bisa dinyalakan dari pengaturan sistem. Membuka pengaturan Bluetooth…")
                openSystem(Settings.ACTION_BLUETOOTH_SETTINGS)
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        web = findViewById(R.id.webview)
        bridge = AndroidBridge(this) { refresh() }

        // Semua file dimuat dari assets/www lewat origin lokal (tanpa internet)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.setBackgroundColor(Color.BLACK)
        web.overScrollMode = View.OVER_SCROLL_NEVER
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true      // localStorage untuk Catatan & pengaturan
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportZoom(false)
            textZoom = 100
        }
        WebView.setWebContentsDebuggingEnabled(
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        )
        web.webViewClient = object : WebViewClient() {
            // Hanya konten lokal proyek yang boleh dimuat; selain itu diblokir (iframe/gambar/skrip luar)
            override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): WebResourceResponse? {
                val u = r!!.url
                if (!isLocal(u)) return blocked(403)
                return loader.shouldInterceptRequest(u) ?: blocked(404)
            }

            // Navigasi ke luar konten lokal dibatalkan
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = !isLocal(r!!.url)

            // Bridge hanya aktif selama halaman yang tampil adalah halaman lokal proyek
            override fun onPageStarted(v: WebView?, url: String?, f: Bitmap?) {
                bridge.trusted = url?.startsWith(base) == true
            }

            override fun onPageFinished(v: WebView?, url: String?) {
                bridge.trusted = url?.startsWith(base) == true
                refresh()
            }

            override fun onRenderProcessGone(v: WebView?, d: RenderProcessGoneDetail?): Boolean {
                recreate(); return true
            }
        }
        web.addJavascriptInterface(bridge, "AndroidBridge")
        web.loadUrl(base + "index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.gosBack?gosBack():false") {
                    if (it != "true") moveTaskToBack(true)
                }
            }
        })
    }

    private fun isLocal(u: Uri) =
        u.scheme == "https" && u.host == host && (u.path ?: "").startsWith("/assets/www/")

    private fun blocked(code: Int) = WebResourceResponse(
        "text/plain", "UTF-8", code, if (code == 404) "Not Found" else "Blocked",
        emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    // ---------- Dipanggil AndroidBridge (selalu di thread UI) ----------
    fun openSystem(action: String) {
        try { startActivity(Intent(action)) }
        catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    fun requestBluetoothEnable() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> notice("Perangkat ini tidak mendukung Bluetooth.")
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> launchBluetoothEnable()
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED -> launchBluetoothEnable()
            else -> btPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun launchBluetoothEnable() {
        try {
            btEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (e: Exception) {
            notice("Dialog Bluetooth tidak dapat dibuka. Membuka pengaturan Bluetooth…")
            openSystem(Settings.ACTION_BLUETOOTH_SETTINGS)
        }
    }

    fun notice(msg: String) {
        web.post {
            web.evaluateJavascript("window.gosNotice&&gosNotice(${JSONObject.quote(msg)})", null)
        }
    }

    // ---------- Siklus hidup ----------
    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        refresh() // juga setelah kembali dari panel/halaman pengaturan
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        bridge.release()
        web.destroy()
        super.onDestroy()
    }

    private fun refresh() {
        web.post { web.evaluateJavascript("window.gosRefresh&&gosRefresh()", null) }
    }
}
