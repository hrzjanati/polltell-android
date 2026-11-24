package app.tether.audio

import app.tether.helper.MicPath.Mode
import app.tether.helper.MicPath.Redirect
import app.tether.helper.MicPath.Verdict
import app.tether.helper.RedirectGuard
import app.tether.helper.RedirectGuard.Action
import app.tether.helper.RedirectGuard.Second
import app.tether.helper.RedirectGuard.State
import app.tether.helper.UplinkCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A redirect audio mode during one call, against synthetic one-second traces (downlink, audio mode, uplink). */
class RedirectGuardTest {
    private val r = Redirect.CALL_REDIRECT
    private val inCall = Mode.IN_CALL
    private val on = r.audioMode
    private fun s(mode: Int = on, down: Boolean = true, uplink: UplinkCheck.Window? = null) =
        Second(downFrames = 50, downNonZero = if (down) 16000 else 0, audioMode = mode, uplink = uplink)
    private val silentDown = Second(50, 0, on)
    private val blocked = Second(0, 0, on)   // the downlink record stopped delivering
    private val talkHeard = UplinkCheck.Window(40, 2000.0, 1500.0)
    private val talkQuiet = UplinkCheck.Window(40, 2000.0, 0.0)

    private fun run(g: RedirectGuard, trace: List<Second>) = trace.map { g.second(it) }

    @Test fun waitsForTheDownlinkThenSetsTheMode() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        val acts = run(g, listOf(s(inCall, down = false), s(inCall, down = false), s(inCall)))
        assertEquals(listOf(Action.NONE, Action.NONE, Action.APPLY), acts)
        assertEquals(State.ON, g.state)
        assertEquals("on", g.state.wire)
    }

    @Test fun theTestLineSetsItAtOnce() {
        val g = RedirectGuard(Redirect.COMMUNICATION_REDIRECT, waitDownlink = false, speechCheck = false)
        assertEquals(Action.APPLY, g.second(Second(0, 0, Mode.IN_COMMUNICATION)))
        // No downlink check without a far end: a silent downlink changes nothing.
        assertTrue(run(g, List(10) { Second(50, 0, Mode.COMMUNICATION_REDIRECT) }).all { it == Action.NONE })
        assertEquals(State.ON, g.state)
    }

    @Test fun telecomTakesTheModeBackItIsSetAgainThenGivenUp() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false, maxReasserts = 2)
        g.second(s(inCall))
        val acts = run(g, listOf(s(inCall), s(), s(inCall), s(), s(inCall)))
        assertEquals(listOf(Action.REASSERT, Action.NONE, Action.REASSERT, Action.NONE, Action.REVERT), acts)
        assertEquals(State.REVERTED, g.state)
        assertNull("the mode worked in between (hold / unhold): not a refusal", g.verdict)
        assertEquals(2, g.reasserts)
    }

    @Test fun aModeThePhoneNeverEntersIsRefused() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false, maxReasserts = 2)
        g.second(s(inCall))
        assertEquals(listOf(Action.REASSERT, Action.REASSERT, Action.REVERT), run(g, List(3) { s(inCall) }))
        assertEquals(Verdict.REFUSED, g.verdict)
    }

    @Test fun anotherModeMeansTheCallEndedNothingIsRemembered() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        g.second(s(inCall))
        assertEquals(Action.REVERT, g.second(s(Mode.NORMAL)))
        assertEquals(State.STOPPED, g.state)
        assertNull(g.verdict)
    }

    @Test fun downlinkDiesInTheModeAndComesBackWithoutIt() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        g.second(s(inCall))   // APPLY
        val acts = run(g, listOf(s(), silentDown, blocked, silentDown))   // a second of settling, then 3 s silent
        assertEquals(listOf(Action.NONE, Action.NONE, Action.NONE, Action.REVERT), acts)
        assertEquals(State.REVERTED, g.state)
        g.second(s(inCall, down = false))
        g.second(s(inCall))
        assertEquals(Verdict.BREAKS_DOWNLINK, g.verdict)
    }

    @Test fun downlinkSilentWithAndWithoutTheModeIsNotBlamedOnIt() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        g.second(s(inCall))
        run(g, listOf(s(), silentDown, silentDown, silentDown))
        run(g, List(6) { s(inCall, down = false) })
        assertNull(g.verdict)
        assertEquals(State.REVERTED, g.state)
    }

    @Test fun aShortGapInTheDownlinkIsFine() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = false)
        g.second(s(inCall))
        assertTrue(run(g, listOf(s(), silentDown, silentDown, s(), silentDown, silentDown, s())).all { it == Action.NONE })
        assertEquals(State.ON, g.state)
    }

    @Test fun untestedModeTheUplinkHearsTheIphoneOk() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = true)
        g.second(s(inCall))
        run(g, listOf(s(uplink = talkQuiet), s(uplink = talkHeard), s(uplink = talkHeard), s(uplink = talkHeard)))
        assertEquals(State.ON, g.state)
        assertEquals(Verdict.OK, g.verdict)
    }

    @Test fun untestedModeSilencesTheUplinkUndoneAndRemembered() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = true)
        g.second(s(inCall))
        // UplinkCheck skips its first second, then 3 quiet seconds of speech: revert.
        val acts = run(g, listOf(s(uplink = talkQuiet), s(uplink = talkQuiet), s(uplink = talkQuiet), s(uplink = talkQuiet), s(uplink = talkQuiet)))
        assertEquals(Action.REVERT, acts.last { it != Action.NONE })
        assertEquals(State.REVERTED, g.state)
        run(g, listOf(s(inCall, uplink = talkQuiet), s(inCall, uplink = talkHeard), s(inCall, uplink = talkHeard)))
        assertEquals(Verdict.BREAKS_UPLINK, g.verdict)
    }

    @Test fun blindUplinkIsUnverifiedNotBroken() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = true)
        g.second(s(inCall))
        run(g, List(5) { s(uplink = talkQuiet) })
        run(g, List(5) { s(inCall, uplink = talkQuiet) })
        assertEquals(Verdict.UNVERIFIED, g.verdict)
    }

    @Test fun noUplinkCaptureMeansUnverified() {
        val g = RedirectGuard(r, waitDownlink = true, speechCheck = true)
        g.uplinkUnavailable("voice_uplink capture: refused")
        assertEquals(Verdict.UNVERIFIED, g.verdict)
        g.second(s(inCall))
        assertEquals(State.ON, g.state)
    }
}
