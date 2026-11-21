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
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.LruCache
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
import java.io.ByteArrayOutputStream
import java.text.Collator
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var bridge: AndroidBridge
    private val host = "appassets.androidplatform.net"
    private val base = "https://$host/assets/www/"

    // Setiap perubahan status sistem -> perbarui tampilan Quick Settings
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = refresh()
    }

    // Daftar aplikasi terpasang berubah -> beri tahu JavaScript agar menandai daftar usang
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            iconCache.evictAll()
            web.post { web.evaluateJavascript("window.gosAppsChanged&&gosAppsChanged()", null) }
        }
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
                if (isIcon(u)) return iconResponse(u)   // ikon aplikasi (hanya untuk halaman lokal tepercaya)
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
        ContextCompat.registerReceiver(
            this, pkgReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

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

    // ---------- Katalog aplikasi terpasang (PackageManager) ----------
    private val pkgRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    @Volatile private var launchable: Map<String, String> = emptyMap()   // paket -> nama
    private val iconCache = object : LruCache<String, ByteArray>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    @Suppress("DEPRECATION")
    private fun queryLaunchers(q: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33)
            packageManager.queryIntentActivities(q, PackageManager.ResolveInfoFlags.of(0L))
        else
            packageManager.queryIntentActivities(q, 0)

    /** Aplikasi dengan launcher activity: tanpa duplikat, tanpa GalaxyOS sendiri, terurut nama (locale id). */
    fun loadApps(): List<Pair<String, String>> {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val names = LinkedHashMap<String, String>()
        for (ri in queryLaunchers(q)) {
            val p = ri.activityInfo.packageName
            if (p == packageName || names.containsKey(p)) continue
            names[p] = ri.loadLabel(packageManager).toString().trim().ifEmpty { p }
        }
        val col = Collator.getInstance(Locale("id")).apply { strength = Collator.SECONDARY }
        val sorted = names.entries
            .sortedWith(Comparator { a, b -> col.compare(a.value, b.value) })
            .map { it.key to it.value }
        launchable = sorted.toMap()
        return sorted
    }

    fun validPackage(p: String) = pkgRegex.matches(p) && p != packageName

    fun isLaunchable(p: String): Boolean {
        if (launchable.isEmpty()) try { loadApps() } catch (e: Exception) {}
        return launchable.containsKey(p)
    }

    fun launchPackage(p: String) { // thread UI
        val i = packageManager.getLaunchIntentForPackage(p)
        if (i == null) {
            notice("Aplikasi tidak dapat dibuka (tidak ditemukan atau dinonaktifkan).")
            return
        }
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (e: Exception) {
            notice("Aplikasi tidak dapat dibuka.")
        }
    }

    // Ikon asli dilayani lewat https://appassets.androidplatform.net/appicon/<paket>.png (PNG 96px, di-cache)
    private fun isIcon(u: Uri) =
        u.scheme == "https" && u.host == host && (u.path ?: "").startsWith("/appicon/")

    private fun iconResponse(u: Uri): WebResourceResponse {
        if (!bridge.trusted) return blocked(403)
        val p = (u.lastPathSegment ?: "").removeSuffix(".png")
        if (!pkgRegex.matches(p) || !isLaunchable(p)) return blocked(404)
        val bytes = iconCache.get(p) ?: renderIcon(p)?.also { iconCache.put(p, it) } ?: return blocked(404)
        return WebResourceResponse(
            "image/png", null, 200, "OK",
            mapOf("Cache-Control" to "max-age=3600"), ByteArrayInputStream(bytes)
        )
    }

    @Synchronized
    private fun renderIcon(p: String): ByteArray? = try {
        val size = 96
        val d = packageManager.getApplicationIcon(p)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        out.toByteArray()
    } catch (e: Exception) { null }

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
        unregisterReceiver(pkgReceiver)
        bridge.release()
        web.destroy()
        super.onDestroy()
    }

    private fun refresh() {
        web.post { web.evaluateJavascript("window.gosRefresh&&gosRefresh()", null) }
    }
}
