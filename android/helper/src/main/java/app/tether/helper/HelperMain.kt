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
 * `record <method> <seconds> <file.pcm>`, `tone <method> <seconds>`, `silence [on|off [mic]]`, `mode [<n> [ms]]`.
 */
object HelperMain {
    const val VERSION = 3   // 3: mutual challenge-response instead of a token on the wire

    object F {
        const val HELLO = 1; const val START = 2; const val STOP = 3; const val DOWN = 4; const val UP = 5
        const val INFO = 6; const val PROBE = 7; const val QUIT = 8; const val SELFTEST = 9; const val AUTH = 10; const val CHALLENGE = 12; const val PROOF = 14
        const val NET = 11; const val LOCAL = 15
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
                "mode" -> mode(args.getOrNull(1)?.toInt(), args.getOrNull(2)?.toLong() ?: 1000)
                "silence" -> { // silence [on|off [mic]] : the phone's own call silencing (CallAudio.Silence) without a call, for testing
                    println("silence mode: ${CallAudio.silence.name.lowercase()}")
                    if (args.size >= 2) println(CallAudio.localCallAudio(args[1] == "on", args.getOrNull(2) == "mic", JSONArray()))
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

    /**
     * `mode [<n> [ms]]`: prints the audio mode and whether call screening mode is declared; with [n], asks for audio
     * mode n (MicPath.Mode: 4 call screening, 5 call redirect, 6 communication redirect) as the shell for [ms], opens the
     * interception record and track in it, prints every mode change meanwhile, and withdraws the request.
     */
    private fun mode(n: Int?, ms: Long) {
        val am = ShellEnv.audioManager
        println("mode ${MicPath.Mode.name(am.mode)}, call screening mode declared: ${CallAudio.screeningSupported()}, interceptable: ${CallAudio.isInterceptable()}")
        if (n == null) return
        // Opened first, in the mode the phone is in (as in a call: the helper opens them in the call's own mode).
        val cap = runCatching { CallAudio.openCapture("intercept", 16000) }.onFailure { println("capture intercept: ${CallAudio.describe(it)}") }.getOrNull()
        val inj = runCatching { CallAudio.openInject("intercept", 16000) }.onFailure { println("inject intercept: ${CallAudio.describe(it)}") }.getOrNull()
        val t0 = System.nanoTime()
        val ok = CallAudio.requestMode(n)
        println("setMode(${MicPath.Mode.name(n)}): ${if (ok) "in it" else "NOT in it (mode ${MicPath.Mode.name(am.mode)})"} after ${(System.nanoTime() - t0) / 1_000_000} ms")
        cap?.let { c -> val b = ShortArray(320); var nz = 0; var r = 0; repeat(10) { val k = c.read(b); if (k > 0) { r += k; nz += CallAudio.nonZero(b, k) } }; println("capture intercept in it: read $r, non-zero $nz") }
        inj?.let { i -> repeat(10) { i.write(ShortArray(320)) }; println("inject intercept in it: routed ${i.routedTo()}, played ${i.playedFrames()}") }
        var last = am.mode
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { val m = am.mode; if (m != last) { println("mode changed to ${MicPath.Mode.name(m)}"); last = m }; Thread.sleep(50) }
        CallAudio.requestMode(MicPath.Mode.NORMAL)
        Thread.sleep(200)
        println("released: mode ${MicPath.Mode.name(am.mode)}")
        cap?.stop(); inj?.stop()
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
        /** How this call mutes the phone's microphone (see LocalMutes); the uplink check may undo it. */
        @Volatile private var plan = LocalMutes.Plan.NONE
        @Volatile private var check: UplinkCheck? = null
        /** Bumped whenever a check is started or abandoned: an older check thread stops on its own. */
        @Volatile private var checkGen = 0
        private val meter = InjectMeter()
        /** Downlink frames and their non-zero samples since the last take (RedirectGuard's downlink check). */
        private val downMeter = DownMeter()
        /** The user's "phone mic during bridged calls" and what this phone model remembers of the redirect modes. */
        @Volatile private var mic = MicPath.Setting.AUTO
        @Volatile private var redirectMemory: Map<MicPath.Redirect, MicPath.Verdict> = emptyMap()
        @Volatile private var testLine = false
        /** This call's redirect audio mode (null: none), and whether we asked Android for a mode (withdrawn at stop). */
        @Volatile private var guard: RedirectGuard? = null
        @Volatile private var modeHeld = false
        @Volatile private var redirectGen = 0
        /** Guards [plan] changes from the check thread against the session thread (never the object's monitor, see sendLock). */
        private val planLock = Any()

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
                    F.SELFTEST -> { stop(); info(JSONObject().put("selftest", selfTest(runCatching { JSONObject(String(payload)) }.getOrDefault(JSONObject())))) }
                    F.LOCAL -> setMicMute(runCatching { JSONObject(String(payload)) }.getOrDefault(JSONObject()))
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
            // The call is bridged to the iPhone: silence the Android phone's own earpiece always, its microphone only
            // when the app asks (the user's setting; see LocalMutes). The downlink is still captured and the injected
            // uplink still goes out.
            mic = MicPath.Setting.of(cfg.optString("mic").takeIf { cfg.has("mic") }, cfg.optBoolean("micMute", false))
            redirectMemory = MicPath.memoryOf(cfg.optJSONObject("redirect"))
            testLine = cfg.optBoolean("testLine")
            plan = LocalMutes.plan(CallAudio.silence, mic == MicPath.Setting.MUTE && cfg.optBoolean("micMute", true), cfg.optBoolean("micCheck", true))
            val local = CallAudio.localCallAudio(muted = true, mic = plan.vendorMic, errors = errors)
            localNote = local.note; localOk = local.ok
            stats.reset()
            meter.take()
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
                        downMeter.add(n, CallAudio.nonZero(buf, n))
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
                        val level = CallAudio.rms(s, s.size)
                        meter.add(level)
                        if (level >= UplinkCheck.SPEECH_RMS) stats.speechFrames++
                        stats.injectClock.mark(System.nanoTime(), inj.playedFrames())
                        if (++writes % 10 == 0) inj.queuedFrames().takeIf { it >= 0 }?.let { stats.queued(it * 1000 / rate) }
                        if (writes == 50) stats.startUnderruns = inj.underruns()   // the first second: the path starting up
                        if (writes >= 50) buf.noteUnderruns(inj.underruns())
                    }
                }, "up").apply { start() })
            }
            // With the phone's mic muted, watch whether the iPhone's voice still reaches the call (best effort).
            val checking = plan.micMuted && plan.check && inject != null
            if (checking) startCheck(rate)
            // Default: keep the phone's mic out of the call with a redirect audio mode, where this phone allows one.
            startRedirect(rate)
            info(JSONObject().put("capture", capture?.name ?: JSONObject.NULL).put("inject", inject?.name ?: JSONObject.NULL).put("testLineFarEnd", farEnd)
                .put("routed", inject?.routedTo() ?: JSONObject.NULL).put("earpiece", local.note).put("silent", LocalMutes.silent(plan, local.ok, guard?.state == RedirectGuard.State.ON))
                .put("mic", micJson())
                .put("silence", CallAudio.silence.name.lowercase()).put("telecomMicMute", plan.telecomMic).put("errors", errors)
                .put("uplink", uplinkJson(if (checking || guard?.check != null) null else if (inject == null) "no uplink injection" else if (guard != null) "the redirect mode was checked on this phone before" else if (!plan.micMuted) "the phone's mic is not muted" else "checked on this phone before"))
                .put("buffers", JSONObject().put("captureFrames", capture?.bufferFrames() ?: 0)
                    .put("injectFrames", inject?.bufferFrames() ?: 0).put("injectLatencyMs", inject?.latencyMs() ?: -1)))
        }

        @Volatile private var localNote = ""
        @Volatile private var localOk = true

        /**
         * The uplink as far as the helper knows: `state` checking | ok | muted-locally-fixed | unverified | off (no local
         * mic mute, nothing to check), `micMuted`, `telecomMicMute` (the app mutes the mic through Telecom), `unmuted`
         * (the check undid the mute this call), `note`. [why]: the reason when no check runs.
         */
        private fun uplinkJson(why: String? = null): JSONObject {
            val g = guard
            val c = check ?: g?.check
            val state = when {
                c != null -> c.state.wire
                inject == null -> UplinkCheck.State.UNVERIFIED.wire
                g != null -> (if (redirectMemory[g.redirect] == MicPath.Verdict.OK) UplinkCheck.State.OK else UplinkCheck.State.UNVERIFIED).wire
                !plan.micMuted -> "off"
                else -> UplinkCheck.State.OK.wire   // muted, and a check on this phone saw the uplink work before
            }
            return JSONObject().put("state", state).put("for", (c?.mitigation ?: if (g != null) UplinkCheck.Mitigation.REDIRECT else UplinkCheck.Mitigation.MUTE).wire)
                .put("micMuted", plan.micMuted).put("telecomMicMute", plan.telecomMic)
                .put("vendorMicMute", plan.vendorMic).put("unmuted", c?.unmuted == true).put("note", c?.note?.takeIf { it.isNotEmpty() } ?: why ?: "")
                .put("local", localNote)
        }

        private fun reportUplink() = info(JSONObject().put("uplink", uplinkJson()).put("mic", micJson()))

        /**
         * The phone's own mic in this call (INFO `mic`): `setting` auto | mute | open; `path` off (a redirect mode took it
         * out of the call) | muted | open; with a redirect mode: `mode`, `state` waiting | on | reverted | stopped,
         * `verdict` (what this call found out, for the app to remember), `reasserts`, `note`.
         */
        private fun micJson(): JSONObject {
            val g = guard
            val path = when {
                g?.state == RedirectGuard.State.ON -> "off"
                plan.micMuted -> "muted"
                else -> "open"
            }
            return JSONObject().put("setting", mic.wire).put("path", path).apply {
                if (g != null) {
                    put("mode", g.redirect.wire).put("state", g.state.wire).put("reasserts", g.reasserts).put("note", g.note)
                    g.verdict?.let { put("verdict", it.wire) }
                } else put("note", when {
                    mic != MicPath.Setting.AUTO -> "setting ${mic.wire}"
                    android.os.Build.VERSION.SDK_INT < CallAudio.FULL_SDK_AUDIO -> "no redirect mode before Android 13"
                    inject?.name != "intercept" -> "no redirect mode without the interception API (inject ${inject?.name ?: "none"})"
                    else -> "every redirect mode failed on this phone before"
                })
            }
        }

        /**
         * Sets this call's redirect audio mode (MicPath.choose) and watches it once a second (RedirectGuard): the mode
         * held against Telecom, the downlink alive, and, while the mode is untested on this phone, the iPhone's voice in
         * VOICE_UPLINK. A revert gives the call back its own mode (the phone's mic open) for the rest of the call.
         */
        private fun startRedirect(rate: Int) {
            val callMode = CallAudio.audioMode()
            val r = MicPath.choose(android.os.Build.VERSION.SDK_INT, mic, callMode, inject?.name, CallAudio.screeningSupported(), redirectMemory)
            if (r == null) return
            val g = RedirectGuard(r, waitDownlink = !testLine, speechCheck = MicPath.wantSpeechCheck(redirectMemory[r]))
            guard = g
            val gen = ++redirectGen
            ShellEnv.log("redirect: ${r.wire} for a call in ${MicPath.Mode.name(callMode)} (remembered ${redirectMemory[r]?.wire ?: "nothing"})")
            threads.add(Thread({
                var cap: Capture? = null
                if (g.check != null) cap = try { CallAudio.openCapture("voice_uplink", rate) } catch (t: Throwable) {
                    g.uplinkUnavailable("voice_uplink capture: ${CallAudio.describe(t)}"); null
                }
                val buf = ShortArray(rate / 50)
                var last = ""
                try {
                    meter.take(); downMeter.take()
                    while (running && redirectGen == gen) {
                        var window: UplinkCheck.Window? = null
                        val c = cap
                        if (c != null && g.check?.done == false) {
                            var frames = 0; var sum = 0.0
                            while (frames < 50 && running && redirectGen == gen) {
                                val n = c.read(buf)
                                if (n < 0) { g.uplinkUnavailable("voice_uplink capture stopped ($n)"); break }
                                if (n == 0) { Thread.sleep(2); continue }
                                sum += CallAudio.rms(buf, n); frames++
                            }
                            val (speech, speechRms) = meter.take()
                            if (frames >= 50) window = UplinkCheck.Window(speech, speechRms, sum / frames)
                        } else {
                            if (c != null) { c.stop(); cap = null }
                            repeat(10) { if (running && redirectGen == gen) Thread.sleep(100) }
                            meter.take()
                        }
                        if (!running || redirectGen != gen) break
                        val (df, dnz) = downMeter.take()
                        when (g.second(RedirectGuard.Second(df, dnz, CallAudio.audioMode(), window))) {
                            RedirectGuard.Action.APPLY, RedirectGuard.Action.REASSERT -> {
                                val ok = CallAudio.requestMode(r.audioMode)
                                modeHeld = true
                                ShellEnv.log("redirect: ${r.wire} ${if (ok) "on" else "asked, phone in ${MicPath.Mode.name(CallAudio.audioMode())}"} (reasserts ${g.reasserts})")
                            }
                            RedirectGuard.Action.REVERT -> releaseMode()
                            RedirectGuard.Action.NONE -> {}
                        }
                        val now = "${g.state}|${g.verdict}|${g.note}|${g.check?.state}|${g.reasserts}"
                        if (now != last) { last = now; ShellEnv.log("redirect: ${g.state.wire} ${g.verdict?.wire ?: ""} ${g.note}"); reportUplink() }
                    }
                } finally { cap?.stop() }
            }, "redirect").apply { start() })
        }

        /** Withdraws our audio-mode request: the call has its own mode (Telecom's) again. */
        private fun releaseMode() {
            if (!modeHeld) return
            modeHeld = false
            CallAudio.requestMode(MicPath.Mode.NORMAL)
            ShellEnv.log("redirect: released, mode ${MicPath.Mode.name(CallAudio.audioMode())}")
        }

        /**
         * Captures VOICE_UPLINK next to the call's own audio and feeds [UplinkCheck] once a second. Opened only while the
         * phone's mic is muted locally (a second capture of the call is one more thing a HAL may mishandle).
         */
        private fun startCheck(rate: Int) {
            val c = UplinkCheck()
            check = c
            val gen = ++checkGen
            threads.add(Thread({
                val cap = try { CallAudio.openCapture("voice_uplink", rate) } catch (t: Throwable) {
                    c.fail("voice_uplink capture: ${CallAudio.describe(t)}"); reportUplink(); return@Thread
                }
                try {
                    val buf = ShortArray(rate / 50)
                    var frames = 0; var sum = 0.0
                    meter.take()
                    while (running && checkGen == gen && !c.done) {
                        val n = cap.read(buf)
                        if (n < 0) { c.fail("voice_uplink capture stopped ($n)"); break }
                        if (n == 0) { Thread.sleep(2); continue }
                        sum += CallAudio.rms(buf, n); frames++
                        if (frames < 50) continue
                        val (speech, speechRms) = meter.take()
                        val act = c.window(UplinkCheck.Window(speech, speechRms, sum / frames))
                        frames = 0; sum = 0.0
                        if (act == UplinkCheck.Action.UNMUTE) synchronized(planLock) {
                            if (checkGen != gen) return@synchronized
                            val errs = JSONArray()
                            if (plan.vendorMic) CallAudio.vendorMicMute(false, errs)?.let { localNote = it }
                            plan = LocalMutes.undone(plan)
                            ShellEnv.log("uplink check: ${c.note}")
                            reportUplink()
                        }
                        if (c.done) { ShellEnv.log("uplink check: ${c.state.wire} (${c.note})"); reportUplink() }
                    }
                } finally { cap.stop() }
            }, "uplink-check").apply { start() })
        }

        /** The user changed the phone-mic setting during a call: apply it now (a redirect mode or the mute, with its checks). */
        private fun setMicMute(req: JSONObject) {
            if (!running) return
            val setting = MicPath.Setting.of(req.optString("mic").takeIf { req.has("mic") }, req.optBoolean("micMute", false))
            val want = setting == MicPath.Setting.MUTE && req.optBoolean("micMute", true)
            synchronized(planLock) {
                checkGen++   // whatever check runs stops
                check = null
                redirectGen++
                releaseMode()
                guard = null
                mic = setting
                if (req.has("redirect")) redirectMemory = MicPath.memoryOf(req.optJSONObject("redirect"))
                val next = LocalMutes.plan(CallAudio.silence, want, req.optBoolean("micCheck", true))
                val errs = JSONArray()
                if (next.vendorMic != plan.vendorMic) CallAudio.vendorMicMute(next.vendorMic, errs)?.let { localNote = it }
                plan = next
                if (plan.micMuted && plan.check && inject != null) startCheck(rate)
                startRedirect(rate)
            }
            reportUplink()
        }

        /**
         * SELFTEST `{methods?, micMute?, redirect?}`: with micMute the vendor's mic key is on during the test (the app
         * mutes Telecom); without it the redirect modes are tested too (unless `redirect` is false).
         */
        private fun selfTest(req: JSONObject): JSONObject {
            val methods = req.optJSONArray("methods")?.let { a -> (0 until a.length()).map { a.getString(it) } }?.filter { it in CallAudio.injectMethods }
                ?: CallAudio.injectMethods
            val mic = req.optBoolean("micMute", false)
            val errs = JSONArray()
            if (mic) CallAudio.vendorMicMute(true, errs)
            val r = try { CallAudio.selfTest(methods = methods, captures = !mic) } finally { if (mic) CallAudio.vendorMicMute(false, errs) }
            // The redirect modes (MicPath): which one takes this phone's mic out of the call without silencing anything.
            if (!mic && req.optBoolean("redirect", true)) {
                val red = runCatching { CallAudio.redirectSelfTest() }.getOrElse { JSONObject().put("methods", JSONArray().put(JSONObject().put("name", "mic:redirect").put("ok", false).put("note", CallAudio.describe(it)))) }
                red.optJSONArray("methods")?.let { a -> for (i in 0 until a.length()) r.getJSONArray("methods").put(a.get(i)) }
                red.optJSONObject("redirect")?.takeIf { it.length() > 0 }?.let { r.put("redirect", it) }
                r.put("callMode", red.optString("callMode"))
            }
            return r.put("silence", CallAudio.silence.name.lowercase()).put("micByTelecom", !CallAudio.silence.vendorMicKeys)
                .put("micMuted", mic).apply { if (errs.length() > 0) put("errors", errs) }
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
            /** Injected frames with speech in them: none in a whole call means the iPhone sent only silence. */
            @Volatile var speechFrames = 0
            fun reset() { speechFrames = 0; backlogSum = 0; backlogN = 0; backlogMax = 0; queuedSum = 0; queuedN = 0; queuedMax = 0; capDropped = 0; trims = 0; trimMisses = 0; captureClock.reset(); injectClock.reset(); startUnderruns = -1 }
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
                    .put("uplinkFrames", up.arrived).put("uplinkDroppedFrames", up.overflowFrames).put("uplinkSpeechFrames", speechFrames)
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
                .put("injectBufferFrames", inject?.bufferFrames() ?: -1).put("uplink", uplinkJson()).put("mic", micJson())))
            running = false
            checkGen++
            redirectGen++
            threads.forEach { it.join(1500) }
            threads.clear()
            releaseMode()   // before anything else of the call stops: the phone's own call path comes back first
            guard = null
            capture?.stop(); capture = null
            inject?.stop(); inject = null
            if (localMuted) { localMuted = false; CallAudio.localCallAudio(muted = false, mic = false, errors = JSONArray()) }
            plan = LocalMutes.Plan.NONE; check = null
        }
    }

/** The down thread adds every frame it reads; the redirect guard takes a second's worth. */
internal class DownMeter {
    private var frames = 0
    private var nonZero = 0
    @Synchronized fun add(n: Int, nz: Int) { frames++; nonZero += nz }
    /** Frames read and their non-zero samples since the last call. */
    @Synchronized fun take(): Pair<Int, Int> = (frames to nonZero).also { frames = 0; nonZero = 0 }
}

/** Urgent-audio priority for the helper's audio threads (logged when the shell user may not raise it). */
internal fun audioPriority(name: String) {
    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO) }
    catch (t: Throwable) { System.err.println("helper: $name thread keeps default priority: ${t.message}") }
}

}
