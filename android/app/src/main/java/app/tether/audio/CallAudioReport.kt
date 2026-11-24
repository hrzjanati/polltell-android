package app.tether.audio

import app.tether.helper.LocalMutes
import org.json.JSONObject

/**
 * Link-log and self-test lines about a bridged call's audio path, written for people reporting "the other side cannot
 * hear me" (docs/TROUBLESHOOTING.md): did the uplink injection open, with which method, was a local mute on.
 */
object CallAudioReport {
    private fun JSONObject.str(k: String): String? = optString(k).takeIf { has(k) && !isNull(k) && it.isNotEmpty() && it != "null" }

    /** One line from the helper's start INFO. [setting]: the user's mic-mute setting, [memory]: what this phone remembered. */
    fun pathLine(info: JSONObject, setting: Boolean, memory: LocalMutes.Memory?): String {
        val inject = info.str("inject")
        val u = info.optJSONObject("uplink")
        val errors = info.optJSONArray("errors")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val injectErrors = errors.filter { it.startsWith("inject:") }
        return buildString {
            append("call audio path: capture ").append(info.str("capture") ?: "NONE (the iPhone hears nothing)")
            if (inject != null) append(", uplink injection open via ").append(inject).append(" (routed ").append(info.str("routed") ?: "?").append(')')
            else append(", uplink injection NOT open: the other side cannot hear the iPhone")
            if (inject != null && injectErrors.isNotEmpty()) append(" after ").append(injectErrors.joinToString("; "))
            if (inject == null && injectErrors.isNotEmpty()) append(" (").append(injectErrors.joinToString("; ")).append(')')
            append("; ").append(micLine(u, setting, memory, info.optJSONObject("mic")))
            info.str("earpiece")?.let { append("; earpiece ").append(it) }
            val other = errors - injectErrors.toSet()
            if (other.isNotEmpty()) append("; errors: ").append(other.joinToString("; "))
        }
    }

    /** Whether and how the phone's own microphone is kept out of the call (a redirect mode, [mic]) or muted, and why. */
    fun micLine(u: JSONObject?, setting: Boolean, memory: LocalMutes.Memory?, mic: JSONObject? = null): String = when {
        mic?.str("mode") != null -> when (mic.optString("state")) {
            "on" -> "phone mic OFF: the call is in ${mic.optString("mode")}"
            "waiting" -> "phone mic open until ${mic.optString("mode")} is set (${mic.optString("note")})"
            else -> "phone mic open (${mic.optString("mode")} ${mic.optString("state")}: ${mic.optString("note")})"
        }
        mic != null && mic.optString("setting") == "open" -> "phone mic open (setting open)"
        mic != null && mic.optString("setting") == "auto" && u?.optBoolean("micMuted") != true -> "phone mic open (${mic.optString("note")})"
        u == null -> "phone mic mute unknown (old helper)"
        u.optBoolean("micMuted") -> "phone mic MUTED via " + (if (u.optBoolean("telecomMicMute")) "Android (Telecom)" else "the vendor key") +
            (if (u.optString("state") == "checking") ", uplink check running" else ", uplink checked before on this phone")
        !setting -> "phone mic not muted (setting off)"
        memory == LocalMutes.Memory.BREAKS_UPLINK -> "phone mic not muted (setting on, but on this phone the mute silenced the uplink)"
        u.optBoolean("unmuted") -> "phone mic mute undone by the uplink check"
        else -> "phone mic not muted"
    }

    /** A line for an uplink report that changed something (null: nothing worth logging). */
    fun uplinkEvent(u: JSONObject, before: JSONObject?): String? {
        val state = u.optString("state")
        val unmutedNow = u.optBoolean("unmuted") && before?.optBoolean("unmuted") != true
        return when {
            unmutedNow && state == "checking" -> "call audio: ${u.optString("note")}"
            state == before?.optString("state") && !unmutedNow -> null
            state == "checking" -> null
            state == "off" && before == null -> null   // no mic mute this call: the path line says so already
            else -> "call audio: uplink check $state" + (u.optString("note").takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
        }
    }

    /** A line for a mic report that changed the redirect mode's state or verdict (null: nothing worth logging). */
    fun micEvent(m: JSONObject, before: JSONObject?): String? {
        val mode = m.str("mode") ?: return null
        val state = m.optString("state"); val verdict = m.str("verdict")
        if (before != null && before.optString("state") == state && before.str("verdict") == verdict) return null
        if (before == null && state == "waiting") return null   // the path line says so already
        return "call audio: $mode $state" + (verdict?.let { " ($it)" } ?: "") + (m.str("note")?.let { ": $it" } ?: "") +
            (m.optInt("reasserts").takeIf { it > 0 }?.let { "; set again $it times" } ?: "")
    }

    /** From the helper's call figures: the iPhone's voice never had any speech in it (a denied or muted microphone). */
    fun silentIphone(stats: JSONObject): String? {
        val frames = stats.optInt("uplinkFrames", -1)
        val speech = stats.optInt("uplinkSpeechFrames", -1)
        return if (frames >= 250 && speech == 0)
            "call audio: the iPhone sent $frames frames without any speech: is its microphone allowed for PolTell (iOS Settings › Privacy › Microphone) and the call not muted?"
        else null
    }
}
