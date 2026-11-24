package app.tether.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import app.tether.PolTellApp
import app.tether.adb.Adb
import app.tether.adb.ConnectOrder
import app.tether.helper.LocalMutes
import app.tether.helper.MicPath
import app.tether.link.LinkService

/**
 * Reads the link, the setup state and the audio helper and turns them into one [MainUiState].
 * The Activity keeps what needs Android (permission prompts, intents, the QR bitmap) and calls the few actions here.
 */
class MainViewModel(private val app: PolTellApp) {
    /** When the open pairing code runs out (ms since epoch); 0 while none is shown. */
    var qrUntil = 0L

    fun state(context: Context, missingPermissions: Boolean, now: Long = System.currentTimeMillis()): MainUiState {
        val conn = app.hub.conn
        val link = linkStatus(
            bluetoothOn = LinkService.running?.peripheral?.enabled,
            connected = conn?.isUp == true,
            paired = app.keys.isPaired,
        )
        val live = app.hub.targets()
        val devices = app.keys.centrals().map { c ->
            DeviceRow(c.name, c.key, live.any { it.peerKey.contentEquals(c.key) })
        }
        val tcpOn = app.prefs.getBoolean(Adb.PREF_LEGACY, false)
        val pm = context.getSystemService(PowerManager::class.java)
        val steps = listOf(
            SetupStep(SetupStepId.PERMISSIONS, !missingPermissions),
            SetupStep(SetupStepId.DIALER, app.sims.isDialer()),
            SetupStep(SetupStepId.BATTERY, pm.isIgnoringBatteryOptimizations(context.packageName)),
            SetupStep(SetupStepId.ADB, Adb.hasSecureSettings(context) && app.prefs.getBoolean("adb_paired", false)),
            SetupStep(SetupStepId.ADB_TCP, tcpOn && Adb.hasSecureSettings(context)),
        )
        val level = app.hub.helper.audioLevel()
        val h = app.hub.audio.helperStatus()
        val running = h.optString("state") == "running"
        val mic = if (level == "unavailable") null else micSection(h)
        val st = app.hub.lastSelftest
        val methods = st?.optJSONArray("methods")
        return MainUiState(
            link = link,
            transport = conn?.takeIf { it.isUp }?.transport?.label,
            devices = devices,
            qrSeconds = if (qrVisible(qrUntil, now, app.keys.currentToken() != null)) qrSecondsLeft(qrUntil, now) else 0,
            steps = steps,
            tcpOn = tcpOn,
            tcpPort = app.prefs.getInt(Adb.PREF_LEGACY_PORT, ConnectOrder.DEFAULT_LEGACY_PORT),
            audioLevel = level,
            helperRunning = running,
            helperDetail = helperDetail(running, h.optString("method", "auto"), h.optString("capture")),
            mic = mic,
            selftest = if (st == null) null else selftestLines((0 until (methods?.length() ?: 0)).map {
                val m = methods!!.getJSONObject(it); Triple(m.optString("name"), m.optBoolean("ok"), m.optString("note"))
            }),
            selftestChosen = st?.optString("chosen", "–") ?: "–",
        )
    }

    private fun micSection(h: org.json.JSONObject): MicSection {
        val setting = app.hub.audio.mic
        val memory = app.hub.audio.redirectMemory()
        val hint = micHint(
            setting, android.os.Build.VERSION.SDK_INT,
            callRedirectUsable = memory[MicPath.Redirect.CALL_REDIRECT]?.usable,
            screeningUsable = memory[MicPath.Redirect.CALL_SCREENING]?.usable,
            mutingBreaksUplink = app.hub.audio.memory() == LocalMutes.Memory.BREAKS_UPLINK,
        )
        return MicSection(setting, hint, h.optString("micPath"), app.hub.helper.lastMic?.optString("mode") ?: "", h.optString("uplink"))
    }

    /** Opens a pairing code and remembers when it expires. */
    fun openPairing(): String {
        val code = app.keys.openPairing().encode()
        qrUntil = app.keys.pairingExpiry
        return code
    }

    fun closePairing() { qrUntil = 0 }

    fun unpair(key: ByteArray) {
        app.keys.removeCentral(key)
        app.hub.conns.filter { it.peerKey.contentEquals(key) }.forEach { it.close("unpaired") }
    }

    fun setMic(choice: MicPath.Setting) { Thread { app.hub.audio.setMic(choice); app.hub.pushStatus() }.start() }
    fun startHelper() { Thread { app.hub.helper.ensureRunning(); app.hub.refresh() }.start() }
    fun runSelftest() { Thread { app.hub.selftest() }.start() }

    fun turnOffTcpAdb() { app.prefs.edit().putBoolean(Adb.PREF_LEGACY, false).apply(); Adb.reset() }
    fun markTcpAdbOn() { app.prefs.edit().putBoolean(Adb.PREF_LEGACY, true).apply() }

    /** Connects to adbd's TCP mode, grants what the app needs and starts the helper; returns whether it connected. Blocking. */
    fun connectTcpAdb(context: Context): Boolean {
        Adb.reset()
        val ok = try { Adb.get(context).ensureConnected() } catch (_: Throwable) { false }
        if (ok) {
            runCatching { Adb.bootstrap(context) }
            app.prefs.edit().putBoolean("adb_paired", true).apply()
            app.hub.helper.ensureRunning(); app.hub.refresh()
        }
        return ok
    }

    companion object {
        fun missingPermissions(context: Context, perms: Array<String>) =
            perms.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }
}
