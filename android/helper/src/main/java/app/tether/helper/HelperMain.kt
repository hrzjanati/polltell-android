package app.tether.helper

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

/**
 * Entry point run as the shell user:
 *
 *   CLASSPATH=$(pm path app.tether.android | cut -d: -f2) app_process / app.tether.helper.HelperMain <cmd>
 *
 * Commands: `serve <port> <token>` (talks to the app over 127.0.0.1), `probe`, `selftest`,
 * `record <method> <seconds> <file.pcm>`, `tone <method> <seconds>`, `silence [on|off]`.
 */
object HelperMain {
    const val VERSION = 3   // 3: mutual challenge-response instead of a token on the wire

    object F {
        const val HELLO = 1; const val START = 2; const val STOP = 3; const val DOWN = 4; const val UP = 5
        const val INFO = 6; const val PROBE = 7; const val QUIT = 8; const val SELFTEST = 9; const val AUTH = 10; const val CHALLENGE = 12; const val PROOF = 14
        const val NET = 11
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            ShellEnv.init()
            when (args.firstOrNull() ?: "serve") {
                "probe" -> println(CallAudio.probe().toString(2))
                "selftest" -> println(CallAudio.selfTest().toString(2))
                "record" -> record(args[1], args[2].toInt(), args[3])
                "tone" -> tone(args[1], args[2].toInt())
                "param" -> { // param <key=value>... : set vendor audio parameters, then read each key back
                    for (kv in args.drop(1)) {
                        if ('=' in kv) ShellEnv.audioManager.setParameters(kv)
                        val k = kv.substringBefore('=')
                        println("$k -> '${ShellEnv.audioManager.getParameters(k)}'")
                    }
                }
                "silence" -> { // silence [on|off] : the phone's own call silencing (CallAudio.Silence) without a call, for testing
                    println("silence mode: ${CallAudio.silence.name.lowercase()}")
                    if (args.size >= 2) println(CallAudio.localCallAudio(args[1] == "on", JSONArray()))
                }
                "net" -> { // net [data|hotspot|bt on|off] : flip a network switch, then print the state
                    if (args.size >= 3 && args[1] == "datasub") println("data SIM ${args[2]}: ${Net.setDataSub(args[2].toInt()) ?: "ok"}")
                    else if (args.size >= 4 && args[1] == "rat") println("slot ${args[2]} → ${args[3]}: ${Net.setRat(args[2].toInt(), args[3]) ?: "ok"}")
                    else if (args.size >= 3) println("set ${args[1]} ${args[2]}: ${Net.set(args[1], args[2] == "on") ?: "ok"}")
                    if (args.size >= 3) Thread.sleep(2500)
                    println(Net.state())
                }
                "serve" -> Server(args[1].toInt(), args[2]).serveForever()
                else -> { System.err.println("unknown command"); exitProcess(2) }
            }
            exitProcess(0)
        } catch (t: Throwable) {
            t.printStackTrace()
            exitProcess(1)
        }
    }

    private fun record(method: String, secs: Int, path: String) {
        val rate = 16000
        val c = CallAudio.openCapture(method, rate)
        val buf = ShortArray(rate / 50)
        FileOutputStream(File(path)).use { out ->
            val bytes = ByteArray(buf.size * 2)
            repeat(secs * 50) {
                val n = c.read(buf)
                for (i in 0 until maxOf(0, n)) { bytes[2 * i] = buf[i].toInt().toByte(); bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                if (n > 0) out.write(bytes, 0, n * 2)
            }
        }
        c.stop()
        println("recorded $secs s via $method to $path")
    }

    private fun tone(method: String, secs: Int) {
        val rate = 16000
        val i = CallAudio.openInject(method, rate)
        println("routed to ${i.routedTo()}")
        repeat(secs * 50) { i.write(CallAudio.tone(800.0, rate, rate / 50, phase0 = it * rate / 50)) }
        i.stop()
    }

    /**
     * Listens on 127.0.0.1:[port]. SELinux forbids shell → app Unix sockets, so the app connects to us.
     * Mutual challenge-response (protocol §6): we send a random challenge, the peer answers with its own nonce and
     * HMAC-SHA256(token, "app" ‖ ours ‖ its), we prove ourselves with HMAC(token, "helper" ‖ its ‖ ours). The token
     * (known only to the app that launched us) never crosses the socket. Each connection authenticates on its own
     * thread with a short timeout, so nothing that merely connects can keep the app out.
     */
    class Server(private val port: Int, private val token: String) {
        @Volatile private lateinit var out: DataOutputStream
        /** The iPhone's voice on its way to the inject track (see UplinkBuffer); a new one per call. */
        @Volatile private var up = UplinkBuffer()
        @Volatile private var capture: Capture? = null
        @Volatile private var inject: Inject? = null
        @Volatile private var running = false
        private var threads = ArrayList<Thread>()
        @Volatile private var localMuted = false

        @Volatile private var current: Socket? = null

        fun serveForever() {
            // Reuse the address: a just-stopped helper's connections linger in TIME_WAIT on this port.
            val ss = ServerSocket().apply { reuseAddress = true; bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8) }
            ShellEnv.log("listening on 127.0.0.1:$port")
            // A helper that died mid-call would leave the phone muted for its next call: always start clean.
            CallAudio.restoreLocalCallAudio()
            // At most a few connections authenticating at once, each with one 2 s deadline for the whole exchange
            // (not per read). When all are taken the oldest is dropped, so a local app holding them open cannot lock
            // the real app out; it can only slow its connect a little.
            val pending = java.util.concurrent.Semaphore(4)
            val waiting = java.util.concurrent.ConcurrentLinkedDeque<Socket>()
            val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "auth-deadline").apply { isDaemon = true } }
            while (true) {
                val s = ss.accept()
                if (!pending.tryAcquire()) {
                    waiting.pollFirst()?.let { runCatching { it.close() } }
                    if (!pending.tryAcquire(500, java.util.concurrent.TimeUnit.MILLISECONDS)) { runCatching { s.close() }; continue }
                }
                waiting.addLast(s)
                val released = java.util.concurrent.atomic.AtomicBoolean(false)
                val authDone = { if (released.compareAndSet(false, true)) { waiting.remove(s); pending.release() } }
                // Claims the slot itself, so it can never close a connection that authenticated at the same moment.
                deadlines.schedule({ if (released.compareAndSet(false, true)) { waiting.remove(s); pending.release(); runCatching { s.close() } } },
                    2, java.util.concurrent.TimeUnit.SECONDS)
                Thread({
                    var quit = false
                    try {
                        s.tcpNoDelay = true
                        quit = session(s, authDone)
                    } catch (e: Exception) {
                        ShellEnv.log("session ended: $e")
                    } finally {
                        // Only the session that owns the audio may stop it (a newer one may already have started).
                        synchronized(this) { if (current === s) { stop(); current = null } }
                        runCatching { s.close() }
                        authDone()
                    }
                    if (quit) exitProcess(0)
                }, "session").start()
            }
        }

        /** Returns true when the app asked us to quit. */
        private fun session(s: Socket, authDone: () -> Unit): Boolean {
            val input = DataInputStream(s.getInputStream().buffered())
            val o = DataOutputStream(s.getOutputStream().buffered())
            s.soTimeout = 2000
            val ours = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val proof = HelperAuth.helperVerify(input, o, token, ours) ?: run { ShellEnv.log("bad proof"); return false }
            authDone()   // before PROOF: the deadline or an eviction must never close an authenticated connection
            HelperAuth.writeProof(o, proof)
            s.soTimeout = 0
            // Authenticated: this connection takes over (an older one is closed and stops nothing of ours).
            synchronized(this) { current?.let { old -> if (old !== s) runCatching { old.close() } }; current = s; stop() }
            out = o
            send(F.HELLO, JSONObject().put("pid", android.os.Process.myPid()).put("uid", android.os.Process.myUid())
                .put("sdk", android.os.Build.VERSION.SDK_INT).put("version", VERSION)
                .put("apk", System.getProperty("java.class.path") ?: "").toString().toByteArray())
            while (true) {
                val type = try { input.readUnsignedByte() } catch (e: Exception) { return false }
                val len = input.readInt()
                if (len < 0 || len > 1 shl 20) return false
                val payload = ByteArray(len).also { input.readFully(it) }
                when (type) {
                    F.START -> start(JSONObject(String(payload)))
                    F.STOP -> stop()
                    F.UP -> {
                        val sh = ShortArray(len / 2) { (payload[2 * it].toInt() and 0xff or (payload[2 * it + 1].toInt() shl 8)).toShort() }
                        // Frames arrive in bursts (Bluetooth connection events carry 0-3, more after a hold-up): the
                        // buffer keeps them and steers the delay ahead of each frame (queue + track) in quiet frames.
                        up.push(sh, System.nanoTime(), inject?.pendingFrames() ?: 0)
                    }
                    F.PROBE -> { stop(); info(JSONObject().put("probe", CallAudio.probe())) }
                    F.SELFTEST -> { stop(); info(JSONObject().put("selftest", CallAudio.selfTest())) }
                    F.NET -> {   // {"what": "data"|"hotspot"|"bt", "on": bool} or {} to just read the state
                        val req = runCatching { JSONObject(String(payload)) }.getOrDefault(JSONObject())
                        Thread({
                            val err = when {
                                req.optString("what") == "dataSub" -> Net.setDataSub(req.optInt("sub", -1))
                                req.optString("what") == "rat" -> Net.setRat(req.optInt("slot", -1), req.optString("mode"))
                                req.has("what") -> Net.set(req.optString("what"), req.optBoolean("on"))
                                else -> null
                            }
                            if (req.has("what")) Thread.sleep(1500)
                            info(JSONObject().put("net", Net.state().apply { err?.let { put("err", it) } }))
                        }, "net").start()
                    }
                    F.QUIT -> return true
                }
            }
        }

        /** Its own lock: a takeover holds the object's monitor while stop() joins the audio threads, which may be
         *  sending right then (on the monitor they could not finish, and every join waited its full 500 ms). */
        private val sendLock = Any()
        private fun send(type: Int, payload: ByteArray) = synchronized(sendLock) {
            out.writeByte(type); out.writeInt(payload.size); out.write(payload); out.flush()
        }

        private fun info(o: JSONObject) = runCatching { send(F.INFO, o.toString().toByteArray()) }

        private fun start(cfg: JSONObject) {
            stop()
            val rate = cfg.optInt("rate", 16000)
            this.rate = rate
            val errors = JSONArray()
            if (android.os.Build.VERSION.SDK_INT < CallAudio.MIN_SDK_AUDIO) errors.put("call audio is not available: it needs Android 11 or later")
            val capOrder = cfg.optString("capture", "auto").let { if (it == "auto") CallAudio.captureMethods else listOf(it) }
            val injOrder = cfg.optString("inject", "auto").let { if (it == "auto") CallAudio.injectMethods else listOf(it) }
            // Right at answer/dial the audio mode may still be NORMAL; the interception API refuses until
            // Telecom switches to IN_CALL. Wait for it (up to 4 s) before falling back to other methods.
            val deadline = System.currentTimeMillis() + cfg.optLong("waitMs", 4000)
            fun <T> open(order: List<String>, kind: String, f: (String) -> T): T? {
                for (m in order) {
                    while (true) {
                        try { return f(m) } catch (t: Throwable) {
                            val msg = CallAudio.describe(t)
                            // Polled often: every ms here delays both directions' first audio (the iPhone's first
                            // words wait in the uplink meanwhile).
                            if (m == "intercept" && msg.contains("not available in mode") && System.currentTimeMillis() < deadline) {
                                Thread.sleep(30); continue
                            }
                            errors.put("$kind:$m $msg"); break
                        }
                    }
                }
                return null
            }
            capture = open(capOrder, "capture") { CallAudio.openCapture(it, rate) }
            val injectMs = cfg.optInt("injectMs", 40)
            inject = open(injOrder, "inject") { CallAudio.openInject(it, rate, injectMs) }
            up = UplinkBuffer(frame = rate / 50, rate = rate)
            running = true
            localMuted = true
            // The call is bridged to the iPhone: silence the Android phone's own earpiece and microphone where the
            // phone has a way to (per vendor; see CallAudio.Silence). The downlink is still captured and the
            // injected uplink still goes out.
            val local = CallAudio.localCallAudio(muted = true, errors = errors)
            stats.reset()
            capture?.let { c ->
                threads.add(Thread({
                    audioPriority("down")
                    val buf = ShortArray(rate / 50)
                    val bytes = ByteArray(buf.size * 2)
                    // Standing delay guard: the smallest backlog over a 1 s window is delay that never drains (a slow
                    // start, a GC pause); when it is over one frame, drop down to half a frame. The minimum, not the
                    // instant value: some capture paths deliver in bursts of 100+ ms (remote submix on the test line),
                    // and right after a burst the backlog is high without any lasting delay — trimming then threw away
                    // far-end voice (1.3 s of a 6.5 s call, 2026-11-23). Only audio already buffered is discarded
                    // (non-blocking reads), since the estimate comes from timestamps and can be wrong.
                    var windowMin = Int.MAX_VALUE
                    var windowReads = 0
                    while (running) {
                        val n = c.read(buf)
                        if (n <= 0) { if (n < 0) break; Thread.sleep(2); continue }   // a stuck record: no busy spin
                        stats.captureClock.mark(System.nanoTime(), c.capturedFrames())
                        val b = c.backlogFrames()
                        if (b >= 0) {
                            stats.backlog(b * 1000 / rate)
                            windowMin = minOf(windowMin, b)
                            if (++windowReads >= 50) {
                                if (windowMin > rate / 50) stats.trim(windowMin - rate / 100, c.drain(windowMin - rate / 100))
                                windowMin = Int.MAX_VALUE; windowReads = 0
                            }
                        }
                        for (i in 0 until n) { bytes[2 * i] = buf[i].toInt().toByte(); bytes[2 * i + 1] = (buf[i].toInt() shr 8).toByte() }
                        try { send(F.DOWN, bytes.copyOf(n * 2)) } catch (e: Exception) { break }
                    }
                }, "down").apply { start() })
            }
            // Tether's test line has no network behind it: on its VoIP path nothing reads the injected uplink, and an
            // unread remote submix takes it ~10 % slower than real time (14.4 kHz for 16 kHz on the reference phone,
            // 2026-11-23), which no real call does. Read it as the far end would, so test calls run at the call's clock.
            val farEnd = cfg.optBoolean("testLine") && inject?.name == "intercept" && ShellEnv.audioManager.mode == android.media.AudioManager.MODE_IN_COMMUNICATION
            // Opened on its own thread: start() does not wait for it.
            if (farEnd) threads.add(Thread({
                runCatching { CallAudio.openCapture("voice_communication", rate) }
                    .onFailure { ShellEnv.log("test line far end: ${CallAudio.describe(it)}") }.getOrNull()?.let { far ->
                        val b = ShortArray(rate / 50)
                        while (running) { val n = far.read(b); if (n < 0) break; if (n == 0) Thread.sleep(2) }
                        far.stop()
                    }
            }, "far-end").apply { start() })
            inject?.let { inj ->
                threads.add(Thread({
                    // No silence padding: an underrunning AudioTrack plays silence by itself, while padded
                    // silence would sit in the buffer and push every later frame back. The buffer corrects the delay
                    // in quiet frames instead (see UplinkBuffer).
                    audioPriority("up")
                    val buf = up
                    var writes = 0
                    while (running) {
                        val s = buf.take(100) ?: continue
                        if (s.isEmpty()) continue   // a quiet frame cut whole
                        try { inj.write(s) } catch (e: Exception) { break }
                        stats.injectClock.mark(System.nanoTime(), inj.playedFrames())
                        if (++writes % 10 == 0) inj.queuedFrames().takeIf { it >= 0 }?.let { stats.queued(it * 1000 / rate) }
                        if (writes == 50) stats.startUnderruns = inj.underruns()   // the first second: the path starting up
                        if (writes >= 50) buf.noteUnderruns(inj.underruns())
                    }
                }, "up").apply { start() })
            }
            info(JSONObject().put("capture", capture?.name ?: JSONObject.NULL).put("inject", inject?.name ?: JSONObject.NULL).put("testLineFarEnd", farEnd)
                .put("routed", inject?.routedTo() ?: JSONObject.NULL).put("earpiece", local.note).put("silent", local.silent)
                .put("silence", CallAudio.silence.name.lowercase()).put("telecomMicMute", local.telecomMicMute).put("errors", errors)
                .put("buffers", JSONObject().put("captureFrames", capture?.bufferFrames() ?: 0)
                    .put("injectFrames", inject?.bufferFrames() ?: 0).put("injectLatencyMs", inject?.latencyMs() ?: -1)))
        }

        /** Standing-delay figures for one call (sent with INFO every 5 s and at stop). */
        private class Stats {
            var backlogSum = 0L; var backlogN = 0; var backlogMax = 0
            var queuedSum = 0L; var queuedN = 0; var queuedMax = 0
            @Volatile var capDropped = 0
            /** Capture trims: how often the guard fired, and how often its estimate found nothing buffered to drop. */
            var trims = 0; var trimMisses = 0
            /** The capture's and the track's sample clocks against the monotonic clock (a real call: the modem's). */
            val captureClock = ClockRate(); val injectClock = ClockRate()
            @Volatile var startUnderruns = -1
            fun reset() { backlogSum = 0; backlogN = 0; backlogMax = 0; queuedSum = 0; queuedN = 0; queuedMax = 0; capDropped = 0; trims = 0; trimMisses = 0; captureClock.reset(); injectClock.reset(); startUnderruns = -1 }
            fun trim(estimated: Int, dropped: Int) {
                trims++; capDropped += dropped
                if (dropped * 2 < estimated) trimMisses++
            }
            fun backlog(ms: Int) { backlogSum += ms; backlogN++; backlogMax = maxOf(backlogMax, ms) }
            fun queued(ms: Int) { queuedSum += ms; queuedN++; queuedMax = maxOf(queuedMax, ms) }
            fun json(rate: Int, up: UplinkBuffer): JSONObject {
                val ms = { samples: Int -> samples * 1000 / rate }
                return JSONObject()
                    .put("captureBacklogMs", if (backlogN > 0) backlogSum / backlogN else -1).put("captureBacklogMaxMs", backlogMax)
                    .put("injectQueuedMs", if (queuedN > 0) queuedSum / queuedN else -1).put("injectQueuedMaxMs", queuedMax)
                    .put("captureDroppedMs", capDropped * 1000L / rate).put("captureTrims", trims).put("captureTrimMisses", trimMisses)
                    // Uplink: frames lost to a full buffer (voice), and what the delay steering did (quiet frames cut /
                    // repeated, speech sped up / slowed down), the delay ahead of an arriving frame and its target.
                    .put("uplinkFrames", up.arrived).put("uplinkDroppedFrames", up.overflowFrames)
                    .put("uplinkQuietCutMs", ms(up.quietCut)).put("uplinkQuietAddedMs", ms(up.inserted))
                    .put("uplinkSpedUpMs", ms(up.compressed)).put("uplinkSlowedMs", ms(up.stretched))
                    .put("uplinkAheadMs", ms(up.levelAvg)).put("uplinkAheadMaxMs", ms(up.levelMax)).put("uplinkTargetMs", ms(up.target)).put("uplinkSinkExtraMs", ms(up.sinkExtra))
                    .put("captureClockHz", captureClock.hz()).put("injectClockHz", injectClock.hz()).put("injectUnderrunsFirstSecond", startUnderruns)
            }
        }
        /** Samples per second of a sample clock, from its first and latest position after a short settling time. */
        class ClockRate {
            private var n = 0; private var t0 = 0L; private var f0 = 0L; private var t1 = 0L; private var f1 = 0L
            fun reset() { n = 0 }
            fun mark(nowNs: Long, frames: Long) {
                if (frames < 0) return
                if (++n == 25) { t0 = nowNs; f0 = frames } else if (n > 25) { t1 = nowNs; f1 = frames }
            }
            /** Rounded to 0.1 Hz; -1 until ~1 s was seen. */
            fun hz(): Double = if (n > 75 && t1 > t0) Math.round((f1 - f0) * 1e10 / (t1 - t0)) / 10.0 else -1.0
        }
        private val stats = Stats()
        @Volatile private var rate = 16000

        private fun stop() {
            if (running) info(JSONObject().put("stats", stats.json(rate, up).put("injectUnderruns", inject?.underruns() ?: -1)
                .put("injectBufferFrames", inject?.bufferFrames() ?: -1)))
            running = false
            threads.forEach { it.join(500) }
            threads.clear()
            capture?.stop(); capture = null
            inject?.stop(); inject = null
            if (localMuted) { localMuted = false; CallAudio.localCallAudio(muted = false, errors = JSONArray()) }
        }
    }

/** Urgent-audio priority for the helper's audio threads (logged when the shell user may not raise it). */
internal fun audioPriority(name: String) {
    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
    catch (t: Throwable) { System.err.println("helper: $name thread keeps default priority: ${t.message}") }
}

}
