package com.galaxyos.simulator

import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * Dipanggil dari JavaScript sebagai AndroidBridge.namaFungsi().
 * Semua fungsi mengembalikan string JSON (atau angka/boolean sederhana).
 * Hanya API resmi Android: tanpa root, tanpa Shizuku.
 */
class AndroidBridge(private val act: Activity, private val onChange: () -> Unit) {
    private val app = act.applicationContext
    private val cam = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    @Volatile private var torchOn = false
    @Volatile private var winBrightness = -1f

    private val camId: String? = try {
        cam.cameraIdList.firstOrNull { id ->
            val c = cam.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (e: Exception) { null }

    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == camId) { torchOn = enabled; onChange() }
        }
    }

    init { cam.registerTorchCallback(torchCb, Handler(Looper.getMainLooper())) }
    fun release() = cam.unregisterTorchCallback(torchCb)

    private fun obj(vararg p: Pair<String, Any?>) =
        JSONObject().also { o -> p.forEach { (k, v) -> o.put(k, v) } }

    // Izin tidak tersedia -> {"error":"permission"} (tidak membuat aplikasi crash)
    private fun safe(b: () -> JSONObject): JSONObject = try { b() }
    catch (e: SecurityException) { obj("error" to "permission") }
    catch (e: Exception) { obj("error" to (e.message ?: "error")) }

    // Buka panel/halaman pengaturan resmi; jika tidak ada, buka Pengaturan utama
    private fun open(action: String): String {
        act.runOnUiThread {
            try { act.startActivity(Intent(action)) }
            catch (e: Exception) {
                try { act.startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
            }
        }
        return obj("ok" to true).toString()
    }

    // ---- Wi-Fi (Android 10+ tidak boleh diubah langsung -> panel resmi) ----
    @JavascriptInterface
    fun getWifiStatus(): String = safe {
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        obj(
            "supported" to true,
            "enabled" to wm.isWifiEnabled,
            "connected" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
        )
    }.toString()

    @JavascriptInterface
    fun openWifiPanel(): String = open(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)

    // ---- Bluetooth (membaca status tidak butuh izin runtime) ----
    @JavascriptInterface
    fun getBluetoothStatus(): String = safe {
        val ad = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        obj("supported" to (ad != null), "enabled" to (ad?.isEnabled == true))
    }.toString()

    @JavascriptInterface
    fun openBluetoothSettings(): String = open(Settings.ACTION_BLUETOOTH_SETTINGS)

    // ---- Senter (tidak butuh izin kamera) ----
    @JavascriptInterface
    fun toggleFlashlight(): String {
        val id = camId ?: return obj("ok" to false, "error" to "unsupported").toString()
        return try {
            val next = !torchOn
            cam.setTorchMode(id, next)
            obj("ok" to true, "on" to next).toString()
        } catch (e: Exception) {
            obj("ok" to false, "error" to (e.message ?: "error")).toString()
        }
    }

    // ---- Baterai ----
    @JavascriptInterface
    fun getBatteryInfo(): String = safe {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        obj(
            "level" to (if (level < 0) 0 else level * 100 / scale),
            "charging" to (st == BatteryManager.BATTERY_STATUS_CHARGING ||
                st == BatteryManager.BATTERY_STATUS_FULL)
        )
    }.toString()

    // ---- Mode pesawat & lokasi ----
    @JavascriptInterface
    fun getAirplaneStatus(): Boolean =
        Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    @JavascriptInterface
    fun getLocationStatus(): Boolean = try {
        (app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
    } catch (e: Exception) { false }

    @JavascriptInterface
    fun openAirplaneModeSettings(): String = open(Settings.ACTION_AIRPLANE_MODE_SETTINGS)

    @JavascriptInterface
    fun openLocationSettings(): String = open(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    // ---- Kecerahan (0-100). Hanya layar aplikasi ini: tidak butuh izin WRITE_SETTINGS ----
    @JavascriptInterface
    fun getBrightness(): Int =
        if (winBrightness >= 0) (winBrightness * 100).toInt()
        else Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255

    @JavascriptInterface
    fun setBrightness(value: Int): String {
        val v = value.coerceIn(1, 100) / 100f
        winBrightness = v
        act.runOnUiThread {
            val lp = act.window.attributes
            lp.screenBrightness = v
            act.window.attributes = lp
        }
        return obj("ok" to true, "scope" to "app").toString()
    }

    @JavascriptInterface
    fun openBrightnessSettings(): String = open(Settings.ACTION_DISPLAY_SETTINGS)

    // ---- Seluruh status sekaligus (dipakai Quick Settings) ----
    @JavascriptInterface
    fun getStatus(): String = obj(
        "wifi" to JSONObject(getWifiStatus()),
        "bt" to JSONObject(getBluetoothStatus()),
        "air" to getAirplaneStatus(),
        "loc" to getLocationStatus(),
        "torch" to obj("supported" to (camId != null), "on" to torchOn),
        "battery" to JSONObject(getBatteryInfo()),
        "brightness" to getBrightness()
    ).toString()
}
