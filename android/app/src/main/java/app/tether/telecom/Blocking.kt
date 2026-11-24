package app.tether.telecom

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import app.tether.PolTellApp

/**
 * Rejects calls from blocked numbers before they ring anywhere (Android phone, iPhone, Mac). Telecom binds the default
 * phone app's screening service for every incoming call; numbers in Android's own block list are normally
 * stopped even earlier by the system, this also covers Tether's own list.
 */
class TetherScreeningService : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val num = details.handle?.schemeSpecificPart ?: ""
        val blocked = details.callDirection == Call.Details.DIRECTION_INCOMING &&
            runCatching { (application as PolTellApp).lists.isBlocked(num) }.getOrDefault(false)
        if (blocked) Log.i(CallRegistry.TAG, "screening: blocked caller rejected")
        respondToCall(details, CallResponse.Builder()
            .setDisallowCall(blocked).setRejectCall(blocked).setSkipNotification(blocked)
            .setSkipCallLog(false).build())
    }
}
