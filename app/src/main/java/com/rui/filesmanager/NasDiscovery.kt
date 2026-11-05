package com.rui.filesmanager

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** ネットワーク上で見つけた SMB 機器（NAS） */
data class DiscoveredNas(val name: String, val host: String)

/**
 * NAS の自動検索。
 * - mDNS (Bonjour) の _smb._tcp 広告を拾う（Synology / QNAP / Buffalo などは名前つきで出る）
 * - 同時に同じサブネットの 445 番ポートを走査して、広告しない機器も拾う
 */
object NasDiscovery {

    suspend fun discover(
        context: Context,
        timeoutMs: Long = 6000,
        onUpdate: (List<DiscoveredNas>) -> Unit,
    ): List<DiscoveredNas> {
        val byHost = LinkedHashMap<String, DiscoveredNas>()
        fun push(d: DiscoveredNas) {
            synchronized(byHost) {
                val cur = byHost[d.host]
                if (cur == null || (cur.name.isBlank() && d.name.isNotBlank())) {
                    byHost[d.host] = d
                }
                onUpdate(byHost.values.toList())
            }
        }

        val appCtx = context.applicationContext
        val wifi = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("fm-nsd")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        val nsd = appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager
        val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
        val resolving = java.util.concurrent.atomic.AtomicBoolean(false)

        fun resolveNext() {
            if (nsd == null) return
            val info = resolveQueue.poll() ?: run { resolving.set(false); return }
            resolving.set(true)
            try {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = resolveNext()
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress
                        if (host != null) {
                            push(DiscoveredNas(serviceInfo.serviceName ?: "", host))
                        }
                        resolveNext()
                    }
                })
            } catch (e: Exception) {
                resolveNext()
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveQueue.add(serviceInfo)
                if (!resolving.get()) resolveNext()
            }
        }
        if (nsd != null) {
            runCatching { nsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
        }

        val scan = coroutineScope {
            async { scanSubnet { push(it) } }
        }
        delay(timeoutMs)
        if (nsd != null) runCatching { nsd.stopServiceDiscovery(listener) }
        scan.cancel()
        if (lock?.isHeld == true) runCatching { lock.release() }
        return synchronized(byHost) { byHost.values.toList() }
    }

    private fun localIpv4(): Inet4Address? {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return null
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && a.isSiteLocalAddress) return a
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private val dnsExecutor = Executors.newCachedThreadPool { r -> Thread(r, "fm-rdns").apply { isDaemon = true } }

    /** 逆引きDNS（最大600msだけ待つ。名前が取れなければ空） */
    private fun reverseName(ip: String): String {
        return try {
            val f = dnsExecutor.submit<String> {
                InetAddress.getByName(ip).canonicalHostName ?: ""
            }
            val name = f.get(600, TimeUnit.MILLISECONDS) ?: ""
            if (name == ip) "" else name.removeSuffix(".")
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun scanSubnet(push: (DiscoveredNas) -> Unit) = coroutineScope {
        val local = localIpv4() ?: return@coroutineScope
        val own = local.hostAddress ?: return@coroutineScope
        val prefix = own.substringBeforeLast('.')
        val sem = Semaphore(48)
        val jobs = (1..254).map { i ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val ip = "$prefix.$i"
                    if (ip == own) return@withPermit
                    val open = try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, 445), 320)
                            true
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (open) {
                        val name = withContext(Dispatchers.IO) { reverseName(ip) }
                        push(DiscoveredNas(name, ip))
                    }
                }
            }
        }
        jobs.awaitAll()
    }
}
