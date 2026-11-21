package com.galaxyos.simulator

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dipanggil dari JavaScript sebagai AndroidBridge.namaFungsi().
 * - Hanya API resmi Android (tanpa root/Shizuku).
 * - Setiap fungsi menolak panggilan bila halaman yang tampil BUKAN halaman lokal proyek ([trusted]).
 * - Izin tidak tersedia / fitur tidak didukung -> JSON {"error": ...}, tidak pernah crash.
 */
class AndroidBridge(private val act: MainActivity, private val onChange: () -> Unit) {
    @Volatile var trusted = false          // diatur MainActivity dari URL halaman yang tampil
    private val app = act.applicationContext
    private val cam = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    @Volatile private var torchOn = false
    @Volatile private var torchAvailable = true
    @Volatile private var winBrightness = -1f

    private val camId: String? = try {
        cam.cameraIdList.firstOrNull { id ->
            val c = cam.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (e: Exception) { null }

    // Status senter yang SEBENARNYA datang dari sistem (termasuk saat diubah aplikasi lain)
    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, enabled: Boolean) {
            if (id == camId) { torchAvailable = true; torchOn = enabled; onChange() }
        }
        override fun onTorchModeUnavailable(id: String) {
            if (id == camId) { torchAvailable = false; torchOn = false; onChange() }
        }
    }

    init { cam.registerTorchCallback(torchCb, Handler(Looper.getMainLooper())) }
    fun release() = cam.unregisterTorchCallback(torchCb)

    private fun obj(vararg p: Pair<String, Any?>) =
        JSONObject().also { o -> p.forEach { (k, v) -> o.put(k, v) } }

    private fun deny() = obj("ok" to false, "error" to "untrusted").toString()
    private fun err(code: String) = obj("ok" to false, "error" to code).toString()

    private fun safe(b: () -> JSONObject): JSONObject = try { b() }
    catch (e: SecurityException) { obj("error" to "permission") }
    catch (e: Exception) { obj("error" to (e.message ?: "error")) }

    // Buka panel/halaman pengaturan resmi (dijalankan di thread UI)
    private fun open(action: String): String {
        if (!trusted) return deny()
        act.runOnUiThread { act.openSystem(action) }
        return obj("ok" to true).toString()
    }

    // ---- Wi-Fi: Android 10+ tidak mengizinkan aplikasi mengubahnya -> panel resmi ----
    @JavascriptInterface
    fun getWifiStatus(): String {
        if (!trusted) return deny()
        return safe {
            val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            obj(
                "supported" to true,
                "enabled" to wm.isWifiEnabled,
                "connected" to (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
            )
        }.toString()
    }

    @JavascriptInterface
    fun openWifiPanel(): String = open(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)

    // ---- Bluetooth: status dibaca langsung; jika sistem menolak -> {"error":"permission"} ----
    @JavascriptInterface
    fun getBluetoothStatus(): String {
        if (!trusted) return deny()
        return safe {
            val ad = (app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            if (ad == null) obj("supported" to false)
            else obj("supported" to true, "enabled" to ad.isEnabled)
        }.toString()
    }

    @JavascriptInterface
    fun openBluetoothSettings(): String = open(Settings.ACTION_BLUETOOTH_SETTINGS)

    // Meminta izin BLUETOOTH_CONNECT (bila perlu) lalu menampilkan dialog sistem "Nyalakan Bluetooth"
    @JavascriptInterface
    fun requestBluetoothEnable(): String {
        if (!trusted) return deny()
        act.runOnUiThread { act.requestBluetoothEnable() }
        return obj("ok" to true).toString()
    }

    // ---- Senter: tanpa izin CAMERA. Status tombol mengikuti callback sistem ----
    @JavascriptInterface
    fun toggleFlashlight(): String {
        if (!trusted) return deny()
        val id = camId ?: return err("unsupported")
        if (!torchAvailable) return err("unavailable")
        return try {
            cam.setTorchMode(id, !torchOn)
            obj("ok" to true).toString()
        } catch (e: SecurityException) { err("permission") }
        catch (e: CameraAccessException) { err("unavailable") }
        catch (e: Exception) { err("failed") }
    }

    // ---- Baterai ----
    @JavascriptInterface
    fun getBatteryInfo(): String {
        if (!trusted) return deny()
        return safe {
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
    }

    // ---- Mode pesawat & lokasi: status dibaca, perubahan lewat pengaturan sistem ----
    @JavascriptInterface
    fun getAirplaneStatus(): Boolean =
        trusted && Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    @JavascriptInterface
    fun getLocationStatus(): Boolean = trusted && try {
        (app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
    } catch (e: Exception) { false }

    @JavascriptInterface
    fun openAirplaneModeSettings(): String = open(Settings.ACTION_AIRPLANE_MODE_SETTINGS)

    @JavascriptInterface
    fun openLocationSettings(): String = open(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    // ---- Kecerahan (0-100): HANYA layar aplikasi ini, bukan kecerahan sistem global ----
    @JavascriptInterface
    fun getBrightness(): Int {
        if (!trusted) return 0
        return if (winBrightness >= 0) (winBrightness * 100).toInt()
        else Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
    }

    @JavascriptInterface
    fun setBrightness(value: Int): String {
        if (!trusted) return deny()
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

    // ---- Aplikasi terpasang (PackageManager) ----
    // Daftar tanpa ikon; ikon dimuat malas lewat /appicon/<paket>.png (lihat MainActivity)
    @JavascriptInterface
    fun getInstalledApps(): String {
        if (!trusted) return deny()
        return try {
            val arr = JSONArray()
            act.loadApps().forEach { (p, n) -> arr.put(obj("p" to p, "n" to n)) }
            obj("ok" to true, "apps" to arr).toString()
        } catch (e: SecurityException) { err("permission") }
        catch (e: Exception) { err("failed") }
    }

    // Paket divalidasi (format + harus ada di daftar launcher) sebelum dibuka
    @JavascriptInterface
    fun launchApp(packageName: String?): String {
        if (!trusted) return deny()
        val p = packageName?.trim().orEmpty()
        if (!act.validPackage(p)) return err("invalid")
        if (!act.isLaunchable(p)) return err("not_found")
        act.runOnUiThread { act.launchPackage(p) }
        return obj("ok" to true).toString()
    }

    // ---- Seluruh status sekaligus (dipakai Quick Settings) ----
    @JavascriptInterface
    fun getStatus(): String {
        if (!trusted) return deny()
        return obj(
            "wifi" to JSONObject(getWifiStatus()),
            "bt" to JSONObject(getBluetoothStatus()),
            "air" to getAirplaneStatus(),
            "loc" to getLocationStatus(),
            "torch" to obj("supported" to (camId != null), "on" to torchOn, "available" to torchAvailable),
            "battery" to JSONObject(getBatteryInfo()),
            "brightness" to getBrightness()
        ).toString()
    }
}
