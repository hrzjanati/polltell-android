package app.tether.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.tether.R
import app.tether.TetherApp
import app.tether.adb.Adb
import app.tether.adb.ConnectOrder
import app.tether.link.LinkService
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class MainActivity : Activity() {
    private lateinit var app: TetherApp
    private lateinit var root: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var qrUntil = 0L
    private var qrBitmap: Bitmap? = null
    private val listener: () -> Unit = { main.post { render() } }

    /** Runtime permissions this Android version knows: Bluetooth ones from 12, notifications from 13. */
    private val perms = (
        (if (android.os.Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE) else emptyList()) +
        (if (android.os.Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()) + listOf(
        Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
    )).toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as TetherApp
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(24)) }
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(color(R.color.background)) })
        if (missingPerms().isEmpty()) LinkService.start(this) else requestPermissions(missingPerms(), 1)
    }

    override fun onResume() {
        super.onResume()
        app.hub.addListener(listener)
        render()
        tick()
    }

    override fun onPause() {
        app.hub.removeListener(listener)
        main.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        LinkService.start(this)
        render()
    }

    private fun tick() {
        main.postDelayed({ if (qrUntil > 0) render(); tick() }, 1000)
    }

    private fun missingPerms() = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    private fun render() {
        root.removeAllViews()
        root.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 28f; setTypeface(typeface, Typeface.BOLD); setTextColor(color(R.color.text))
            setPadding(dp(4), 0, 0, dp(16))
        })

        // Link
        val link = card(getString(R.string.section_link))
        val svc = LinkService.running
        val conn = app.hub.conn
        val (state, stateColor) = when {
            svc?.peripheral?.enabled == false -> getString(R.string.state_bt_off) to R.color.bad
            conn?.isUp == true -> getString(R.string.state_connected) + " · " + conn.transport.label to R.color.ok
            !app.keys.isPaired -> getString(R.string.state_unpaired) to R.color.warn
            else -> getString(R.string.state_waiting) to R.color.muted
        }
        link.addView(label(state, stateColor, 18f))
        // Paired devices: connected ones are marked; each can be removed on its own.
        val live = app.hub.targets()
        for (cl in app.keys.centrals()) {
            val on = live.any { it.peerKey.contentEquals(cl.key) }
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(label((if (on) "● " else "○ ") + cl.name + (if (on) " · " + getString(R.string.state_connected) else ""),
                if (on) R.color.ok else R.color.muted).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            r.addView(button(getString(R.string.unpair), secondary = true) {
                AlertDialog.Builder(this).setMessage(getString(R.string.unpair_confirm_named, cl.name))
                    .setPositiveButton(R.string.unpair) { _, _ ->
                        app.keys.removeCentral(cl.key)
                        app.hub.conns.filter { it.peerKey.contentEquals(cl.key) }.forEach { it.close("unpaired") }
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null).show()
            })
            link.addView(r)
        }
        val remaining = (qrUntil - System.currentTimeMillis()) / 1000
        if (qrUntil > 0 && remaining > 0 && app.keys.currentToken() != null) {
            link.addView(label(getString(R.string.pair_hint), R.color.text))
            link.addView(ImageView(this).apply {
                setImageBitmap(qrBitmap); adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(dp(260), dp(260)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) }
            })
            link.addView(label(getString(R.string.pair_expires, remaining), R.color.muted).apply { gravity = Gravity.CENTER })
        } else if (qrUntil > 0) {
            qrUntil = 0
            qrBitmap = null
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(getString(R.string.pair_iphone)) { showQr() })
        link.addView(row)

        // Setup
        val setup = card(getString(R.string.section_setup))
        setup.addView(step(getString(R.string.step_permissions), missingPerms().isEmpty(), getString(R.string.action_grant)) {
            requestPermissions(missingPerms(), 1)
        })
        setup.addView(step(getString(R.string.step_dialer), app.sims.isDialer(), getString(R.string.action_set)) {
            startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER), 2)
        })
        val pm = getSystemService(PowerManager::class.java)
        setup.addView(step(getString(R.string.step_battery), pm.isIgnoringBatteryOptimizations(packageName), getString(R.string.action_set)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        })
        setup.addView(step(getString(R.string.step_adb), Adb.hasSecureSettings(this) && app.prefs.getBoolean("adb_paired", false), getString(R.string.action_pair)) {
            Adb.showPairingNotification(this)
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
        })

        // Legacy TCP adb: opt-in, for Android 10 (no Wireless debugging) or a phone without a trusted Wi-Fi network.
        val tcpOn = app.prefs.getBoolean(Adb.PREF_LEGACY, false)
        val tcpPort = app.prefs.getInt(Adb.PREF_LEGACY_PORT, ConnectOrder.DEFAULT_LEGACY_PORT)
        setup.addView(step(getString(R.string.step_adb_tcp), tcpOn && Adb.hasSecureSettings(this), getString(R.string.action_enable),
            if (tcpOn) getString(R.string.adb_tcp_on, tcpPort) else getString(R.string.adb_tcp_off)) { enableTcpAdb(tcpPort) })
        if (tcpOn) setup.addView(button(getString(R.string.action_turn_off), secondary = true) {
            app.prefs.edit().putBoolean(Adb.PREF_LEGACY, false).apply(); Adb.reset(); render()
        })

        // Audio
        val audio = card(getString(R.string.section_audio))
        // Call audio: interception API on Android 13+, voice-call capture on 11-12, nothing on 10.
        when (app.hub.helper.audioLevel()) {
            "unavailable" -> audio.addView(label(getString(R.string.audio_needs_android11), R.color.warn, 13f))
            "fallback" -> audio.addView(label(getString(R.string.audio_android11_12), R.color.muted, 13f))
        }
        if (app.hub.helper.audioLevel() != "unavailable" && !android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) audio.addView(label(getString(R.string.audio_local_stays_on), R.color.muted, 13f))
        val h = app.hub.helper.state()
        val running = h.optString("state") == "running"
        audio.addView(step(getString(R.string.step_helper), running, getString(R.string.action_start),
            if (running) getString(R.string.helper_running, h.optString("method", "auto")) else getString(R.string.helper_stopped)) {
            Thread { app.hub.helper.ensureRunning(); app.hub.refresh() }.start()
        })
        app.hub.lastSelftest?.let { st ->
            val methods = st.optJSONArray("methods")
            val txt = (0 until (methods?.length() ?: 0)).joinToString("\n") {
                val m = methods!!.getJSONObject(it); "${if (m.optBoolean("ok")) "✓" else "✗"} ${m.optString("name")}  ${m.optString("note")}"
            }
            audio.addView(label(getString(R.string.selftest_result, st.optString("chosen", "–")) + "\n" + txt, R.color.muted, 13f))
        }
        audio.addView(button(getString(R.string.action_selftest), secondary = true) {
            Thread { app.hub.selftest() }.start()
        })
    }

    /** Explains the steps, then (on Enable) connects to adbd's TCP mode, grants what the app needs and starts the helper. */
    private fun enableTcpAdb(port: Int) {
        AlertDialog.Builder(this).setMessage(getString(R.string.adb_tcp_howto))
            .setPositiveButton(R.string.action_enable) { _, _ ->
                app.prefs.edit().putBoolean(Adb.PREF_LEGACY, true).apply()
                Toast.makeText(this, R.string.adb_tcp_connecting, Toast.LENGTH_SHORT).show()
                Thread {
                    Adb.reset()
                    val ok = try { Adb.get(this).ensureConnected() } catch (_: Throwable) { false }
                    if (ok) {
                        runCatching { Adb.bootstrap(this) }
                        app.prefs.edit().putBoolean("adb_paired", true).apply()
                        app.hub.helper.ensureRunning(); app.hub.refresh()
                    }
                    main.post {
                        Toast.makeText(this, if (ok) getString(R.string.adb_tcp_connected) else getString(R.string.adb_tcp_failed, port), Toast.LENGTH_LONG).show()
                        render()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showQr() {
        val code = app.keys.openPairing()
        val size = 720
        val m = QRCodeWriter().encode(code.encode(), BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.BLACK else Color.WHITE }
        qrBitmap = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        qrUntil = app.keys.pairingExpiry
        render()
    }

    // ------------------------------------------------------------------ tiny view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = getColor(id)

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(color(R.color.surface)); cornerRadius = dp(16).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) }
        }
        c.addView(label(title.uppercase(), R.color.accent, 12f).apply { setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.08f })
        root.addView(c)
        return c
    }

    private fun label(t: String, colorId: Int, size: Float = 15f) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color(colorId)); setPadding(0, dp(4), 0, dp(4))
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun button(t: String, secondary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false
        setTextColor(if (secondary) color(R.color.accent) else color(R.color.background))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            if (secondary) setStroke(dp(1), color(R.color.accent)) else setColor(color(R.color.accent))
        }
        layoutParams = LinearLayout.LayoutParams(-2, dp(42)).apply { topMargin = dp(8); marginEnd = dp(8) }
        setPadding(dp(18), 0, dp(18), 0)
        setOnClickListener { onClick() }
    }

    private fun step(title: String, done: Boolean, action: String, detail: String? = null, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        texts.addView(label(title, R.color.text, 16f))
        texts.addView(label(detail ?: getString(if (done) R.string.done else R.string.missing), if (done) R.color.ok else R.color.warn, 13f))
        row.addView(texts)
        if (!done) row.addView(button(action) { onClick() })
        return row
    }
}
