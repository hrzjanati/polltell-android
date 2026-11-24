package app.tether.ui

import app.tether.helper.MicPath

/** How a line of text is coloured: the screen maps a tone to a theme colour, so the rules stay free of Android. */
enum class Tone { OK, WARN, BAD, MUTED, TEXT }

/** Where the link to the iPhone stands, in the order the screen reports it. */
enum class LinkStatus(val tone: Tone) {
    BLUETOOTH_OFF(Tone.BAD), CONNECTED(Tone.OK), UNPAIRED(Tone.WARN), WAITING(Tone.MUTED),
}

/** [bluetoothOn] is null while the link service is not running (the adapter is not known then). */
fun linkStatus(bluetoothOn: Boolean?, connected: Boolean, paired: Boolean): LinkStatus = when {
    bluetoothOn == false -> LinkStatus.BLUETOOTH_OFF
    connected -> LinkStatus.CONNECTED
    !paired -> LinkStatus.UNPAIRED
    else -> LinkStatus.WAITING
}

/** Whole seconds a pairing code stays valid; 0 once it ran out or when none was opened. */
fun qrSecondsLeft(until: Long, now: Long): Int = if (until <= now) 0 else ((until - now + 999) / 1000).toInt()

fun qrVisible(until: Long, now: Long, hasToken: Boolean): Boolean = hasToken && qrSecondsLeft(until, now) > 0

/** The note under the microphone choice. */
enum class MicHint(val isWarning: Boolean) {
    AUTO_UNAVAILABLE(true), AUTO(false), MUTE_BLOCKED(true), MUTE_ON(false), OPEN(false),
}

/**
 * [callRedirectUsable] and [screeningUsable] are what this phone remembered about each redirect mode (null: not tried yet);
 * automatic mode counts as unavailable below Android 13 or once both modes are known not to work.
 */
fun micHint(
    setting: MicPath.Setting, sdk: Int, callRedirectUsable: Boolean?, screeningUsable: Boolean?, mutingBreaksUplink: Boolean,
): MicHint = when {
    setting == MicPath.Setting.AUTO && (sdk < 33 || (callRedirectUsable == false && screeningUsable != true)) -> MicHint.AUTO_UNAVAILABLE
    setting == MicPath.Setting.AUTO -> MicHint.AUTO
    setting == MicPath.Setting.MUTE && mutingBreaksUplink -> MicHint.MUTE_BLOCKED
    setting == MicPath.Setting.MUTE -> MicHint.MUTE_ON
    else -> MicHint.OPEN
}

/** "auto · opus": the helper's method and, when known, what it captures with; null while it is not running. */
fun helperDetail(running: Boolean, method: String, capture: String): String? =
    if (!running) null else if (capture.isEmpty()) method else "$method · $capture"

/** One line per self-test method: ✓ or ✗, its name and note. */
fun selftestLines(methods: List<Triple<String, Boolean, String>>): String =
    methods.joinToString("\n") { (name, ok, note) -> "${if (ok) "✓" else "✗"} $name  $note" }

/** The setup steps of the Setup card; the screen maps each to its title and action. */
enum class SetupStepId { PERMISSIONS, DIALER, BATTERY, ADB, ADB_TCP }

data class SetupStep(val id: SetupStepId, val done: Boolean)

/** A paired iPhone: [connected] when a live session uses its key. */
class DeviceRow(val name: String, val key: ByteArray, val connected: Boolean)

/** What the microphone card shows. [nowPath] / [uplink] are the helper's raw words ("off", "muted", "ok", …), empty when unknown. */
data class MicSection(
    val setting: MicPath.Setting, val hint: MicHint, val nowPath: String, val nowMode: String, val uplink: String,
)

/** Everything the Main screen draws, taken from the link at one moment. The screen only renders this. */
data class MainUiState(
    val link: LinkStatus,
    val transport: String?,
    val devices: List<DeviceRow>,
    val qrSeconds: Int,
    val steps: List<SetupStep>,
    val tcpOn: Boolean,
    val tcpPort: Int,
    val audioLevel: String,
    val helperRunning: Boolean,
    val helperDetail: String?,
    val mic: MicSection?,
    val selftest: String?,
    val selftestChosen: String,
)
