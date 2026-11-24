package app.tether.audio

import app.tether.helper.CallAudio.Silence
import app.tether.helper.LocalMutes
import app.tether.helper.LocalMutes.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the phone's own mutes a bridged call uses (issue #2: a muted uplink on a Galaxy Z Fold 6). */
class LocalMutesTest {
    private fun plan(s: Silence, setting: Boolean, memory: Memory?) =
        LocalMutes.plan(s, LocalMutes.wantMicMute(setting, memory), LocalMutes.wantCheck(memory))

    @Test fun byDefaultNoVendorMutesTheMicrophone() {
        for (s in Silence.entries) {
            val p = plan(s, setting = false, memory = null)
            assertFalse("$s", p.micMuted)
            assertFalse("$s: nothing to check", p.check)
            assertEquals("$s: earpiece only", "earpiece", LocalMutes.silent(p, ok = true))
        }
    }

    @Test fun onRequestEachVendorUsesExactlyOneMicMute() {
        val samsung = plan(Silence.SAMSUNG, true, null)
        assertTrue(samsung.vendorMic); assertFalse("never also Android's mute", samsung.telecomMic)
        val mediatek = plan(Silence.MEDIATEK, true, null)
        assertTrue(mediatek.vendorMic); assertFalse("Android's mute kills MediaTek's whole uplink", mediatek.telecomMic)
        for (s in listOf(Silence.QUALCOMM, Silence.OTHER)) {
            val p = plan(s, true, null)
            assertFalse("$s has no key of its own", p.vendorMic); assertTrue(p.telecomMic)
        }
        for (s in Silence.entries) {
            assertTrue("$s: the uplink is checked", plan(s, true, null).check)
            assertEquals("full", LocalMutes.silent(plan(s, true, null), ok = true))
        }
    }

    @Test fun aRememberedBreakKeepsTheMicOnEvenWithTheSetting() {
        for (s in Silence.entries) assertEquals(LocalMutes.Plan.NONE, plan(s, setting = true, memory = Memory.BREAKS_UPLINK))
    }

    @Test fun aRememberedSuccessSkipsTheCheck() {
        val p = plan(Silence.QUALCOMM, setting = true, memory = Memory.OK)
        assertTrue(p.telecomMic)
        assertFalse(p.check)
    }

    @Test fun undoingClearsEveryMicMute() {
        for (s in Silence.entries) {
            val u = LocalMutes.undone(plan(s, true, null))
            assertFalse("$s", u.micMuted)
            assertEquals("earpiece", LocalMutes.silent(u, ok = true))
        }
        assertEquals("none", LocalMutes.silent(LocalMutes.Plan.NONE, ok = false))
    }

    @Test fun memoryWireNames() {
        assertEquals(Memory.BREAKS_UPLINK, Memory.of("muted-locally-fixed"))
        assertEquals(Memory.OK, Memory.of("ok"))
        assertNull(Memory.of(null))
        assertNull(Memory.of("checking"))
    }
}
