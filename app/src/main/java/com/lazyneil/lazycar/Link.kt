package com.lazyneil.lazycar

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.Writer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Phone-tablet link: TCP [PORT] for settings sync and Shut down, a UDP probe on the same number to find the phone.
 *  Lines of JSON: {"t": "prefs", "items", "full"}, {"t": "shutdown"}, {"t": "hb"}. */
object Link {
    const val PORT = 47477
    const val PROBE = "CARMEDIA"
}

/** Phone side: serves tablets between app open or GO and Shut down. A plain in-process service, no grant needed. */
class LinkService : Service() {
    companion object {
        @Volatile var self: LinkService? = null
        @Volatile var clients = 0
        val bound get() = self != null
        fun wake(ctx: Context) { if (!bound && !Role.isTablet(ctx)) try { ctx.startService(Intent(ctx, LinkService::class.java)) } catch (e: Exception) { Log.e("LazyCar", "link start: $e") } }
        fun sleep() { self?.stopSelf() }
        fun shutDownTablets() = self?.sendAll(JSONObject().put("t", "shutdown").toString())
    }

    private class Client(val s: Socket) {
        val w: Writer = s.getOutputStream().bufferedWriter()
        val q = Executors.newSingleThreadExecutor()   // one writer per tablet: a dead socket blocks only its own queue
        @Volatile var heardAt = SystemClock.elapsedRealtime()
    }

    private val main = Handler(Looper.getMainLooper())
    private val out = CopyOnWriteArrayList<Client>()
    @Volatile private var alive = false
    private var server: ServerSocket? = null
    private var probe: DatagramSocket? = null

    override fun onBind(i: Intent?) = null
    override fun onStartCommand(i: Intent?, flags: Int, id: Int) = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        // Shut down: nothing runs until the app is opened or GO. The tablet never serves.
        if (Prefs(this).shutDown || Role.isTablet(this)) { stopSelf(); return }
        alive = true; self = this
        SettingsSync.init(this) { items -> sendAll(JSONObject().put("t", "prefs").put("items", items).toString()) }
        thread(name = "link-server") { serve() }
        thread(name = "link-probe") { answerProbes() }
        thread(name = "link-hb", isDaemon = true) { heartbeat() }
    }

    override fun onDestroy() {
        if (alive) { alive = false; self = null; server?.close(); probe?.close(); out.forEach(::drop); clients = 0 }
        super.onDestroy()
    }

    fun sendAll(line: String) = out.forEach { write(it, line) }

    /** 1 Hz; the tablet answers, so 15 s of silence = dead socket. */
    private fun heartbeat() {
        val line = JSONObject().put("t", "hb").toString()
        while (alive) {
            val now = SystemClock.elapsedRealtime()
            for (cl in out) if (now - cl.heardAt > 15_000) drop(cl) else write(cl, line)
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        }
    }

    private fun write(cl: Client, line: String) = try {
        cl.q.execute { try { cl.w.write(line); cl.w.write("\n"); cl.w.flush() } catch (_: Exception) { drop(cl) } }
    } catch (_: java.util.concurrent.RejectedExecutionException) {}

    private fun drop(cl: Client) { try { cl.s.close() } catch (_: Exception) {}; cl.q.shutdownNow(); out.remove(cl); clients = out.size }

    private fun serve() { try {
        ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Link.PORT)) }.use { ss ->
            server = ss
            while (alive) {
                val s = ss.accept().apply { tcpNoDelay = true }
                val cl = Client(s); out += cl; clients = out.size
                Log.e("LazyCar", "link accept ${s.inetAddress?.hostAddress} clients=${out.size}")
                thread(name = "link-client") {
                    try { s.getInputStream().bufferedReader().forEachLine { l -> cl.heardAt = SystemClock.elapsedRealtime(); main.post { handle(cl, JSONObject(l)) } } }
                    catch (_: Exception) {}
                    drop(cl)
                }
            }
        }
    } catch (e: Exception) { if (alive) Log.e("LazyCar", "link server", e) } }

    private fun answerProbes() { try {
        DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(Link.PORT)) }.use { s ->
            probe = s; val buf = ByteArray(32)
            while (alive) {
                val p = DatagramPacket(buf, buf.size); s.receive(p)
                if (String(buf, 0, p.length) == Link.PROBE) s.send(DatagramPacket(Link.PROBE.toByteArray(), Link.PROBE.length, p.socketAddress))
            }
        }
    } catch (e: Exception) { if (alive) Log.e("LazyCar", "link probe", e) } }

    private fun handle(cl: Client, j: JSONObject) {
        when (j.optString("c")) {
            "shutdown" -> try { startForegroundService(Intent(this, GoService::class.java).putExtra("action", "stopPeer")) }
                catch (e: Exception) { GoAction.stop(applicationContext, fromPeer = true) }
            "prefs" -> { SettingsSync.apply(j.optJSONArray("items"))
                if (j.optBoolean("full")) write(cl, JSONObject().put("t", "prefs").put("items", SettingsSync.all()).toString()) }
        }
    }
}

/** Tablet side: keeps a connection to the phone from app open until Shut down. */
object PhoneLink {
    @Volatile var connected = false
    @Volatile var phone = ""
    @Volatile private var w: Writer? = null
    @Volatile private var sock: Socket? = null
    @Volatile private var gen = 0   // bumped by stop(): the old link thread ends
    private var started = false
    private val io = Executors.newSingleThreadExecutor()

    fun send(j: JSONObject) = io.execute { try { w?.apply { write(j.toString()); write("\n"); flush() } } catch (_: Exception) {} }

    fun stop() = io.execute { synchronized(this) { started = false; gen++ }; try { sock?.close() } catch (_: Exception) {} }

    @Synchronized fun start(ctx: Context) {
        if (started) return
        started = true; val g = gen
        val app = ctx.applicationContext
        SettingsSync.init(app) { items -> if (connected) send(JSONObject().put("c", "prefs").put("items", items)) }
        thread(name = "phone-link", isDaemon = true) {
            while (g == gen) {
                try {
                    val ip = find(app) ?: throw Exception("no phone")
                    Socket().use { s -> sock = s
                        s.connect(InetSocketAddress(ip, Link.PORT), 3000); s.tcpNoDelay = true
                        s.soTimeout = 15_000   // the phone heartbeats every second
                        w = s.getOutputStream().bufferedWriter(); phone = ip; connected = true
                        Log.e("LazyCar", "phone link up $ip")
                        send(JSONObject().put("c", "prefs").put("items", SettingsSync.all()).put("full", true))
                        s.getInputStream().bufferedReader().forEachLine { l ->
                            send(JSONObject().put("c", "hb"))
                            val j = JSONObject(l)
                            when (j.optString("t")) {
                                "prefs" -> SettingsSync.apply(j.optJSONArray("items"))
                                "shutdown" -> Handler(Looper.getMainLooper()).post { GoAction.stop(app, fromPeer = true) }
                            }
                        }
                    }
                } catch (_: Exception) {}
                if (connected) { connected = false; w = null; Log.e("LazyCar", "phone link down") }
                Thread.sleep(500)
            }
        }
    }

    /** The broadcast probe, else the last phone, else the Wi-Fi gateway (the phone hotspot in the car). */
    private fun find(ctx: Context): String? {
        @Suppress("DEPRECATION")
        val gw = ctx.getSystemService(WifiManager::class.java).dhcpInfo?.gateway ?: 0
        val gateway = if (gw != 0) "${gw and 255}.${gw shr 8 and 255}.${gw shr 16 and 255}.${gw shr 24 and 255}" else null
        return probe() ?: phone.ifEmpty { null } ?: gateway
    }

    private fun probe(): String? = try {
        DatagramSocket().use { s ->
            s.broadcast = true; s.soTimeout = 1500
            val msg = Link.PROBE.toByteArray()
            val targets = mutableSetOf(InetAddress.getByName("255.255.255.255"))
            NetworkInterface.getNetworkInterfaces()?.toList()?.filter { it.isUp && !it.isLoopback }
                ?.forEach { ni -> ni.interfaceAddresses.mapNotNullTo(targets) { it.broadcast } }
            targets.forEach { try { s.send(DatagramPacket(msg, msg.size, it, Link.PORT)) } catch (_: Exception) {} }
            val buf = ByteArray(32); val p = DatagramPacket(buf, buf.size); s.receive(p)
            p.address.hostAddress
        }
    } catch (_: Exception) { null }
}
