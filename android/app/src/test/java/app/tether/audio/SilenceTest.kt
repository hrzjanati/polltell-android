package app.tether.audio

import app.tether.helper.CallAudio
import app.tether.helper.CallAudio.Silence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The helper's choice of how a bridged call silences the Android phone itself (CallAudio.Silence). */
class SilenceTest {
    @Test fun samsungAlwaysUsesItsOwnKeys() {
        assertEquals(Silence.SAMSUNG, CallAudio.pickSilence("samsung", "Samsung", "s5e8835", "s5e8835"))
        assertEquals(Silence.SAMSUNG, CallAudio.pickSilence("samsung", "QTI", "kalama", "qcom"))
    }

    @Test fun qualcommFromSocOrPlatform() {
        assertEquals(Silence.QUALCOMM, CallAudio.pickSilence("Xiaomi", "QTI", "taro", "qcom"))
        assertEquals(Silence.QUALCOMM, CallAudio.pickSilence("OnePlus", null, "lahaina", ""))
        assertEquals(Silence.QUALCOMM, CallAudio.pickSilence("motorola", null, "sm6150", null))
    }

    @Test fun mediatekNeverUsesTheAndroidMicMute() {
        val s = CallAudio.pickSilence("Xiaomi", "Mediatek", "mt6893", "mt6893")
        assertEquals(Silence.MEDIATEK, s)
        assertTrue("its own UL key instead", s.vendorMicKeys)
        assertEquals(Silence.MEDIATEK, CallAudio.pickSilence("TECNO", null, "mt6789", null))
    }

    @Test fun onlySamsungAndMediatekHaveTheirOwnMicKeys() {
        assertTrue(Silence.SAMSUNG.vendorMicKeys)
        assertFalse(Silence.QUALCOMM.vendorMicKeys)
        assertFalse(Silence.OTHER.vendorMicKeys)
    }

    @Test fun othersFallBack() {
        assertEquals(Silence.OTHER, CallAudio.pickSilence("Google", "Google", "zuma", "zuma"))
        assertEquals(Silence.OTHER, CallAudio.pickSilence(null, null, null, null))
    }
}
