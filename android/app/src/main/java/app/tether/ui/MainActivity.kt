package app.tether.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.tether.PolTellApp
import app.tether.R
import app.tether.adb.Adb
import app.tether.helper.MicPath
import app.tether.link.LinkService
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * The phone's one screen: link to the iPhone, setup steps and call audio. It draws a [MainUiState] from [MainViewModel]
 * and forwards taps; permission prompts and system screens stay here because they need an Activity.
 */
class MainActivity : Activity() {
    private lateinit var vm: MainViewModel
    private lateinit var ui: Ui
    private lateinit var root: LinearLayout
    private val main = Handler(Looper.getMainLooper())
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

    private val app get() = application as PolTellApp
    private fun missingPerms() = MainViewModel.missingPermissions(this, perms)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = MainViewModel(application as PolTellApp)
        ui = Ui(this)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.padForSystemBars(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24))
        setContentView(ScrollView(this).apply {
            addView(root); setBackgroundColor(ui.color(R.color.background)); isFillViewport = true; clipToPadding = false
        })
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
        main.postDelayed({ if (vm.qrUntil > 0) render(); tick() }, 1000)
    }

    // ------------------------------------------------------------------ drawing

    private fun render() {
        val s = vm.state(this, missingPerms().isNotEmpty())
        root.removeAllViews()
        root.addView(header())
        root.addView(linkCard(s))
        root.addView(setupCard(s))
        root.addView(audioCard(s))
    }

    /** The app's name on the brand gradient, with the bridge mark. */
    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ui.brandGradient(24)
            setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(18) }
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_polltell); setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(14) }
        })
        row.addView(TextView(this).apply {
            text = getString(R.string.app_name); textSize = 28f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        })
        return row
    }

    private fun linkCard(s: MainUiState): View {
        val link = ui.card(getString(R.string.section_link))
        val text = when (s.link) {
            LinkStatus.BLUETOOTH_OFF -> getString(R.string.state_bt_off)
            LinkStatus.CONNECTED -> getString(R.string.state_connected) + (s.transport?.let { " · $it" } ?: "")
            LinkStatus.UNPAIRED -> getString(R.string.state_unpaired)
            LinkStatus.WAITING -> getString(R.string.state_waiting)
        }
        link.addView(ui.label(text, toneColor(s.link.tone), 18f).apply { setTypeface(typeface, Typeface.BOLD) })
        // Paired devices: connected ones are marked; each can be removed on its own.
        for (d in s.devices) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(ui.label((if (d.connected) "● " else "○ ") + d.name + (if (d.connected) " · " + getString(R.string.state_connected) else ""),
                if (d.connected) R.color.ok else R.color.muted).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            r.addView(ui.button(getString(R.string.unpair), secondary = true) {
                AlertDialog.Builder(this).setMessage(getString(R.string.unpair_confirm_named, d.name))
                    .setPositiveButton(R.string.unpair) { _, _ -> vm.unpair(d.key); render() }
                    .setNegativeButton(android.R.string.cancel, null).show()
            })
            link.addView(r)
        }
        if (s.qrSeconds > 0) {
            link.addView(ui.label(getString(R.string.pair_hint), R.color.text))
            link.addView(ImageView(this).apply {
                setImageBitmap(qrBitmap); adjustViewBounds = true
                // White quiet zone around the code so it scans on the dark card.
                setBackgroundColor(Color.WHITE); setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
                layoutParams = LinearLayout.LayoutParams(ui.dp(260), ui.dp(260)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(8) }
            })
            link.addView(ui.label(getString(R.string.pair_expires, s.qrSeconds.toLong()), R.color.muted).apply { gravity = Gravity.CENTER })
        } else if (vm.qrUntil > 0) {
            vm.closePairing(); qrBitmap = null
        }
        link.addView(LinearLayout(this).apply { addView(ui.button(getString(R.string.pair_iphone)) { showQr() }) })
        return link
    }

    private fun setupCard(s: MainUiState): View {
        val setup = ui.card(getString(R.string.section_setup))
        for (step in s.steps) {
            when (step.id) {
                SetupStepId.PERMISSIONS -> setup.addView(stepRow(getString(R.string.step_permissions), step.done, getString(R.string.action_grant)) {
                    requestPermissions(missingPerms(), 1)
                })
                SetupStepId.DIALER -> setup.addView(stepRow(getString(R.string.step_dialer), step.done, getString(R.string.action_set)) {
                    startActivityForResult(getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_DIALER), 2)
                })
                SetupStepId.BATTERY -> setup.addView(stepRow(getString(R.string.step_battery), step.done, getString(R.string.action_set)) {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                })
                SetupStepId.ADB -> setup.addView(stepRow(getString(R.string.step_adb), step.done, getString(R.string.action_pair)) {
                    Adb.showPairingNotification(this)
                    runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                })
                // Legacy TCP adb: opt-in, for Android 10 (no Wireless debugging) or a phone without a trusted Wi-Fi network.
                SetupStepId.ADB_TCP -> {
                    setup.addView(stepRow(getString(R.string.step_adb_tcp), step.done, getString(R.string.action_enable),
                        if (s.tcpOn) getString(R.string.adb_tcp_on, s.tcpPort) else getString(R.string.adb_tcp_off)) { enableTcpAdb(s.tcpPort) })
                    if (s.tcpOn) setup.addView(ui.button(getString(R.string.action_turn_off), secondary = true) { vm.turnOffTcpAdb(); render() })
                }
            }
        }
        return setup
    }

    private fun audioCard(s: MainUiState): View {
        val audio = ui.card(getString(R.string.section_audio))
        // Call audio: interception API on Android 13+, voice-call capture on 11-12, nothing on 10.
        when (s.audioLevel) {
            "unavailable" -> audio.addView(ui.label(getString(R.string.audio_needs_android11), R.color.warn, 13f))
            "fallback" -> audio.addView(ui.label(getString(R.string.audio_android11_12), R.color.muted, 13f))
        }
        val detail = s.helperDetail?.let { d ->
            val (method, capture) = d.split(" · ").let { it[0] to it.getOrNull(1) }
            getString(R.string.helper_running, method) + (capture?.let { " · " + getString(R.string.helper_capture, it) } ?: "")
        } ?: getString(R.string.helper_stopped)
        audio.addView(stepRow(getString(R.string.step_helper), s.helperRunning, getString(R.string.action_start), detail) { vm.startHelper() })
        // The phone's own microphone during bridged calls (issue #2): by default a redirect audio mode takes it out of the
        // call where the phone allows it (MicPath); muting it is opt-in, since on some phones that also mutes the iPhone.
        s.mic?.let { mic ->
            audio.addView(ui.label(getString(R.string.mic_title), R.color.text, 16f))
            val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; setPadding(0, ui.dp(2), 0, ui.dp(2)) }
            for ((choice, text) in listOf(MicPath.Setting.AUTO to R.string.mic_choice_auto, MicPath.Setting.MUTE to R.string.mic_choice_mute,
                    MicPath.Setting.OPEN to R.string.mic_choice_open)) {
                group.addView(RadioButton(this).apply {
                    id = View.generateViewId(); this.text = getString(text); textSize = 15f; setTextColor(ui.color(R.color.text))
                    buttonTintList = android.content.res.ColorStateList.valueOf(ui.color(R.color.accent))
                    minHeight = ui.dp(48)
                    isChecked = choice == mic.setting
                    // A click (also on the chosen one) sets it anew, which forgets what this phone remembered.
                    setOnClickListener { vm.setMic(choice) }
                })
            }
            audio.addView(group)
            audio.addView(ui.label(getString(when (mic.hint) {
                MicHint.AUTO_UNAVAILABLE -> R.string.mic_auto_unavailable
                MicHint.AUTO -> R.string.mic_auto_note
                MicHint.MUTE_BLOCKED -> R.string.mic_mute_blocked
                MicHint.MUTE_ON -> R.string.mic_mute_on
                MicHint.OPEN -> R.string.mic_open_note
            }), if (mic.hint.isWarning) R.color.warn else R.color.muted, 13f))
            mic.nowPath.takeIf { it.isNotEmpty() }?.let { p ->
                audio.addView(ui.label(when (p) {
                    "off" -> getString(R.string.mic_now_off, mic.nowMode)
                    "muted" -> getString(R.string.mic_now_muted)
                    else -> getString(R.string.mic_now_open)
                }, R.color.muted, 13f))
            }
            mic.uplink.takeIf { it.isNotEmpty() }?.let { u ->
                audio.addView(ui.label(getString(R.string.uplink_title) + ": " + getString(when (u) {
                    "ok" -> R.string.uplink_ok
                    "checking" -> R.string.uplink_checking
                    "muted-locally-fixed" -> if (mic.setting == MicPath.Setting.MUTE) R.string.uplink_fixed else R.string.uplink_fixed_redirect
                    "off" -> R.string.uplink_off
                    else -> R.string.uplink_unverified
                }), if (u == "muted-locally-fixed") R.color.warn else R.color.muted, 13f))
            }
        }
        s.selftest?.let { lines ->
            audio.addView(ui.label(getString(R.string.selftest_result, s.selftestChosen) + "\n" + lines, R.color.muted, 13f))
        }
        audio.addView(ui.button(getString(R.string.action_selftest), secondary = true) { vm.runSelftest() })
        return audio
    }

    private fun stepRow(title: String, done: Boolean, action: String, detail: String? = null, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(6), 0, ui.dp(6)) }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        texts.addView(ui.label(title, R.color.text, 16f))
        texts.addView(ui.label(detail ?: getString(if (done) R.string.done else R.string.missing), if (done) R.color.ok else R.color.warn, 13f))
        row.addView(texts)
        if (!done) row.addView(ui.button(action) { onClick() })
        return row
    }

    private fun toneColor(t: Tone) = when (t) {
        Tone.OK -> R.color.ok; Tone.WARN -> R.color.warn; Tone.BAD -> R.color.bad; Tone.MUTED -> R.color.muted; Tone.TEXT -> R.color.text
    }

    // ------------------------------------------------------------------ actions

    /** Explains the steps, then (on Enable) connects to adbd's TCP mode, grants what the app needs and starts the helper. */
    private fun enableTcpAdb(port: Int) {
        AlertDialog.Builder(this).setMessage(getString(R.string.adb_tcp_howto))
            .setPositiveButton(R.string.action_enable) { _, _ ->
                vm.markTcpAdbOn()
                Toast.makeText(this, R.string.adb_tcp_connecting, Toast.LENGTH_SHORT).show()
                Thread {
                    val ok = vm.connectTcpAdb(this)
                    main.post {
                        Toast.makeText(this, if (ok) getString(R.string.adb_tcp_connected) else getString(R.string.adb_tcp_failed, port), Toast.LENGTH_LONG).show()
                        render()
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun showQr() {
        val code = vm.openPairing()
        val size = 720
        val m = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { if (m[it % size, it / size]) Color.BLACK else Color.WHITE }
        qrBitmap = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        render()
    }
}
