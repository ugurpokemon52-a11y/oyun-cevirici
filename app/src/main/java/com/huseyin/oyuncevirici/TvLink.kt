package com.huseyin.oyuncevirici

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Telefon tarafi: TV'deki aliciyi ayni Wi-Fi'da otomatik bulur (NSD) ve
 * altyazilari satir satir JSON olarak gonderir.
 */
object TvLink {
    const val SERVICE_TYPE = "_oyuncevirici._tcp"
    const val PORT = 47321

    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newSingleThreadExecutor()
    private var appCtx: Context? = null
    private var nsd: NsdManager? = null
    private var discovering = false
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolving = false

    @Volatile private var host: String? = null
    @Volatile private var port = PORT
    @Volatile private var tvName: String? = null
    @Volatile var connected = false
        private set

    // sadece exec is parcaciginda
    private var socket: Socket? = null
    private var out: BufferedWriter? = null

    /** Durum degisince ana is parcaciginda cagrilir */
    var onStatus: (() -> Unit)? = null

    fun status(c: Context): String = when {
        !Prefs.tvSend(c) -> "TV'ye gönderme kapalı"
        connected -> "📺 TV bağlı ✓ (${tvName ?: host})"
        host != null -> "📺 TV bulundu, bağlanıyor… ($host)"
        else -> "📺 TV aranıyor… (TV'de Oyun Çevirici açık olmalı)"
    }

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (!Prefs.tvSend(ctx)) return
        val manual = Prefs.tvHost(ctx)
        if (manual.isNotBlank()) {
            stopDiscovery()
            host = manual; port = PORT; tvName = manual
            exec.execute { ensureConnected() }
            return
        }
        if (host != null) {
            exec.execute { ensureConnected() }
            return
        }
        startDiscovery()
    }

    /** Ayar degisince (elle adres, ac/kapa) bastan baslat */
    fun restart(ctx: Context) {
        stopDiscovery()
        host = null; tvName = null
        exec.execute { closeSock() }
        notifyStatus()
        start(ctx)
    }

    private fun startDiscovery() {
        if (discovering) return
        val c = appCtx ?: return
        val m = c.getSystemService(NsdManager::class.java) ?: return
        nsd = m
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering = false
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (host == null) resolve(serviceInfo)
            }
        }
        discoveryListener = l
        discovering = true
        try {
            m.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            discovering = false
        }
    }

    private fun stopDiscovery() {
        val l = discoveryListener ?: return
        try { nsd?.stopServiceDiscovery(l) } catch (_: Exception) {}
        discoveryListener = null
        discovering = false
    }

    @Suppress("DEPRECATION")
    private fun resolve(s: NsdServiceInfo) {
        if (resolving) return
        resolving = true
        try {
            nsd?.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolving = false
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolving = false
                    val h = serviceInfo.host?.hostAddress ?: return
                    host = h
                    port = serviceInfo.port
                    tvName = serviceInfo.serviceName
                    main.post { stopDiscovery() }
                    notifyStatus()
                    exec.execute { ensureConnected() }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    // exec is parcaciginda
    private fun ensureConnected(): Boolean {
        if (socket != null && out != null) return true
        val h = host ?: return false
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(h, port), 2500)
            s.tcpNoDelay = true
            socket = s
            out = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            connected = true
            notifyStatus()
            true
        } catch (e: Exception) {
            closeSock()
            // Otomatik bulunmus adres eskimis olabilir: yeniden ara
            val c = appCtx
            if (c != null && Prefs.tvHost(c).isBlank()) {
                host = null; tvName = null
                main.post { startDiscovery() }
            }
            false
        }
    }

    private fun closeSock() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        out = null
        if (connected) {
            connected = false
            notifyStatus()
        }
    }

    private fun send(obj: JSONObject) {
        val c = appCtx ?: return
        if (!Prefs.tvSend(c)) return
        if (host == null) {
            main.post { startDiscovery() }
            return
        }
        val line = obj.toString() + "\n"
        exec.execute {
            if (!ensureConnected()) return@execute
            try {
                out!!.write(line); out!!.flush()
            } catch (e: Exception) {
                closeSock()
                if (ensureConnected()) {
                    try { out!!.write(line); out!!.flush() } catch (_: Exception) { closeSock() }
                }
            }
        }
    }

    fun sendSub(ctx: Context, text: String) {
        if (appCtx == null) appCtx = ctx.applicationContext
        send(
            JSONObject().put("t", "sub").put("text", text)
                .put("size", Prefs.tvSize(ctx)).put("pos", Prefs.tvPos(ctx))
        )
    }

    fun sendClear() = send(JSONObject().put("t", "clear"))

    private fun notifyStatus() {
        main.post { onStatus?.invoke() }
    }
}
