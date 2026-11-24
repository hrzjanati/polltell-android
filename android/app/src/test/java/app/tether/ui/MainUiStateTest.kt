package app.tether.ui

import app.tether.helper.MicPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Main screen's decisions, as plain functions: what the link card says and which hint the microphone card shows. */
class MainUiStateTest {
    // ---- link status

    @Test fun bluetoothOffWinsOverEverything() {
        assertEquals(LinkStatus.BLUETOOTH_OFF, linkStatus(bluetoothOn = false, connected = true, paired = true))
        assertEquals(LinkStatus.BLUETOOTH_OFF, linkStatus(bluetoothOn = false, connected = false, paired = false))
    }

    @Test fun connectedWhenASessionIsUp() {
        assertEquals(LinkStatus.CONNECTED, linkStatus(bluetoothOn = true, connected = true, paired = true))
        // A running service whose adapter state is unknown does not hide a live session.
        assertEquals(LinkStatus.CONNECTED, linkStatus(bluetoothOn = null, connected = true, paired = true))
    }

    @Test fun unpairedBeforeWaiting() {
        assertEquals(LinkStatus.UNPAIRED, linkStatus(bluetoothOn = true, connected = false, paired = false))
        assertEquals(LinkStatus.WAITING, linkStatus(bluetoothOn = true, connected = false, paired = true))
        assertEquals(LinkStatus.WAITING, linkStatus(bluetoothOn = null, connected = false, paired = true))
    }

    @Test fun onlyGoodAndBadStatesHaveTheirOwnTone() {
        assertEquals(Tone.OK, LinkStatus.CONNECTED.tone)
        assertEquals(Tone.BAD, LinkStatus.BLUETOOTH_OFF.tone)
        assertEquals(Tone.WARN, LinkStatus.UNPAIRED.tone)
        assertEquals(Tone.MUTED, LinkStatus.WAITING.tone)
    }

    // ---- pairing QR countdown

    @Test fun qrCountdownRoundsDownAndStopsAtZero() {
        assertEquals(60, qrSecondsLeft(until = 61_000, now = 1_000))
        assertEquals(1, qrSecondsLeft(until = 2_999, now = 2_000))
        assertEquals(0, qrSecondsLeft(until = 5_000, now = 5_000))
        assertEquals(0, qrSecondsLeft(until = 5_000, now = 9_000))
        assertEquals(0, qrSecondsLeft(until = 0, now = 9_000))
    }

    @Test fun qrShownOnlyWhileTimeIsLeftAndATokenExists() {
        assertTrue(qrVisible(until = 10_000, now = 1_000, hasToken = true))
        assertFalse(qrVisible(until = 10_000, now = 1_000, hasToken = false))
        assertFalse(qrVisible(until = 10_000, now = 10_000, hasToken = true))
        assertFalse(qrVisible(until = 0, now = 1_000, hasToken = true))
    }

    // ---- microphone hint

    @Test fun autoNeedsAndroid13OrNoUsableRedirect() {
        assertEquals(MicHint.AUTO_UNAVAILABLE, micHint(MicPath.Setting.AUTO, sdk = 31, callRedirectUsable = null, screeningUsable = null, mutingBreaksUplink = false))
        assertEquals(MicHint.AUTO_UNAVAILABLE, micHint(MicPath.Setting.AUTO, sdk = 34, callRedirectUsable = false, screeningUsable = false, mutingBreaksUplink = false))
        assertEquals(MicHint.AUTO_UNAVAILABLE, micHint(MicPath.Setting.AUTO, sdk = 34, callRedirectUsable = false, screeningUsable = null, mutingBreaksUplink = false))
    }

    @Test fun autoIsFineWhenARedirectIsUsableOrUntried() {
        assertEquals(MicHint.AUTO, micHint(MicPath.Setting.AUTO, sdk = 34, callRedirectUsable = null, screeningUsable = null, mutingBreaksUplink = false))
        assertEquals(MicHint.AUTO, micHint(MicPath.Setting.AUTO, sdk = 34, callRedirectUsable = true, screeningUsable = false, mutingBreaksUplink = false))
        assertEquals(MicHint.AUTO, micHint(MicPath.Setting.AUTO, sdk = 34, callRedirectUsable = false, screeningUsable = true, mutingBreaksUplink = false))
    }

    @Test fun muteWarnsOnlyWhenThisPhoneBreaksTheUplink() {
        assertEquals(MicHint.MUTE_BLOCKED, micHint(MicPath.Setting.MUTE, sdk = 34, callRedirectUsable = null, screeningUsable = null, mutingBreaksUplink = true))
        assertEquals(MicHint.MUTE_ON, micHint(MicPath.Setting.MUTE, sdk = 34, callRedirectUsable = null, screeningUsable = null, mutingBreaksUplink = false))
    }

    @Test fun openJustSaysSo() {
        assertEquals(MicHint.OPEN, micHint(MicPath.Setting.OPEN, sdk = 29, callRedirectUsable = false, screeningUsable = false, mutingBreaksUplink = true))
    }

    @Test fun onlyTheTwoProblemHintsAreWarnings() {
        assertEquals(setOf(MicHint.AUTO_UNAVAILABLE, MicHint.MUTE_BLOCKED), MicHint.values().filter { it.isWarning }.toSet())
    }

    // ---- helper detail line

    @Test fun helperDetailNamesMethodAndCapture() {
        assertEquals("auto", helperDetail(running = true, method = "auto", capture = ""))
        assertEquals("auto · opus", helperDetail(running = true, method = "auto", capture = "opus"))
        assertNull(helperDetail(running = false, method = "auto", capture = "opus"))
    }

    // ---- self-test lines

    @Test fun selftestLinesMarkPassAndFail() {
        assertEquals("✓ a  fine\n✗ b  broken", selftestLines(listOf(Triple("a", true, "fine"), Triple("b", false, "broken"))))
        assertEquals("", selftestLines(emptyList()))
    }
}
