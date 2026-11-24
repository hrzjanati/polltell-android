package app.tether.helper

/**
 * What a bridged call silences on the Android phone itself (issue #2: on a Galaxy Z Fold 6 the far end heard nothing
 * of the iPhone while the downlink was fine).
 *
 * The earpiece is always silenced: that never touches the uplink. The phone's own microphone only when the user asks
 * for it (default off), because on several audio HALs a microphone mute also silences the audio injected into the
 * uplink: Android's mic mute (through Telecom) on MediaTek for certain, and by their sources on Qualcomm HALs whenever
 * the injection is not their in-call music use case (the interception API's track is not); Samsung's tx control mode
 * was never checked against the interception API on a real call. The phone lies in a bag, so its own microphone picks
 * up little, and a muted far end is far worse than some bag noise.
 *
 * When the user does turn the mute on, the helper watches the uplink during the call ([UplinkCheck]) and undoes the
 * mute if the iPhone's voice does not reach the call; the app remembers the result for this phone model.
 */
object LocalMutes {
    /** What the app remembers for this phone model (wire names as in status `helper.uplink`). */
    enum class Memory(val wire: String) {
        /** The local mic mute silenced the uplink: never mute again on this phone unless the user turns it on anew. */
        BREAKS_UPLINK("muted-locally-fixed"),
        /** A check heard the iPhone's voice in the uplink with the mute on: no need to check again. */
        OK("ok");

        companion object {
            fun of(wire: String?): Memory? = entries.firstOrNull { it.wire == wire }
        }
    }

    /**
     * How this call mutes the phone's microphone: through the vendor's own keys (Samsung tx control, MediaTek UL
     * mute), through Android's mic mute (Telecom, done by the app), and whether the helper checks the uplink.
     */
    data class Plan(val vendorMic: Boolean, val telecomMic: Boolean, val check: Boolean) {
        val micMuted: Boolean get() = vendorMic || telecomMic

        companion object {
            val NONE = Plan(vendorMic = false, telecomMic = false, check = false)
        }
    }

    /** App side: ask the helper to mute the phone's microphone this call? */
    fun wantMicMute(setting: Boolean, memory: Memory?): Boolean = setting && memory != Memory.BREAKS_UPLINK

    /** App side: should the helper check the uplink while the mic is muted (not when a check on this phone already saw it work)? */
    fun wantCheck(memory: Memory?): Boolean = memory != Memory.OK

    /**
     * Helper side: one way to mute the mic per vendor (never both: each extra mute is one more way to silence the
     * uplink): the vendor's keys where it has them, else Android's mic mute.
     */
    fun plan(silence: CallAudio.Silence, micMute: Boolean, check: Boolean): Plan =
        if (!micMute) Plan.NONE
        else Plan(vendorMic = silence.vendorMicKeys, telecomMic = !silence.vendorMicKeys, check = check)

    /** The plan after the uplink check found the mute silencing the iPhone's voice: no mic mute of any kind. */
    fun undone(p: Plan): Plan = p.copy(vendorMic = false, telecomMic = false)

    /**
     * Status `helper.silent`: "full" with the mic muted too or out of the call ([redirected]: a redirect audio mode, see
     * [MicPath]), "earpiece" with the earpiece only, "none" when muting failed and no mode took the call off the phone.
     */
    fun silent(p: Plan, ok: Boolean, redirected: Boolean = false): String = when {
        redirected -> "full"
        !ok -> "none"
        p.micMuted -> "full"
        else -> "earpiece"
    }
}
