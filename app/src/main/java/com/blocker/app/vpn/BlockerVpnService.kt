package com.blocker.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.blocker.app.BlockerApp
import com.blocker.app.BuildConfig
import com.blocker.app.MainActivity
import com.blocker.app.R
import com.blocker.app.admin.DeviceOwnerController
import com.blocker.app.admin.PackageInstallReceiver
import com.blocker.app.filter.FilterDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Local Always-On DNS Filtering VPN Service.
 *
 * Routes ONLY:
 *   1. Virtual DNS server (10.200.0.2/32 and fd00:200::2/128) -> filtered by DnsFilterEngine
 *   2. Public unfiltered DNS/DoH/DoT resolver IPs -> immediately rejected (TCP RST / dropped)
 *
 * Also dynamically monitors package installs (ACTION_PACKAGE_ADDED + PackageInstaller.SessionCallback)
 * so Tor Browser / Orbot / Tor clones are intercepted and silently uninstalled in real time on Android 8+.
 *
 * Intentionally exposes NO stop action: once active with Device Owner + DISALLOW_CONFIG_VPN,
 * it runs permanently across reboots and Direct Boot.
 */
class BlockerVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queryConcurrency = Semaphore(64)
    private val tunWriteLock = Any()

    private var vpnInterface: ParcelFileDescriptor? = null
    private var readerJob: Job? = null
    private var upstreamResolver: UpstreamDnsResolver? = null
    private var dynamicPackageReceiver: PackageInstallReceiver? = null
    private var installSessionCallback: PackageInstaller.SessionCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        upstreamResolver = UpstreamDnsResolver(this)
        registerRealtimeInstallMonitors()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.EMERGENCY_DISABLE || intent?.action == ACTION_EMERGENCY_STOP && BuildConfig.EMERGENCY_DISABLE) {
            teardownVpn()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundSafe()
        if (vpnInterface == null) {
            establishVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system")
        teardownVpn()
        // If Device Owner is active, immediately re-enforce policies & Always-On VPN
        val controller = DeviceOwnerController(this)
        if (controller.isDeviceOwner()) {
            controller.enforcePermanentLockdown()
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        unregisterRealtimeInstallMonitors()
        teardownVpn()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun registerRealtimeInstallMonitors() {
        try {
            val receiver = PackageInstallReceiver()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(receiver, filter)
            }
            dynamicPackageReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register dynamic PackageInstallReceiver: ${e.message}")
        }

        try {
            val callback = object : PackageInstaller.SessionCallback() {
                override fun onCreated(sessionId: Int) = Unit
                override fun onBadgingChanged(sessionId: Int) = Unit
                override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit
                override fun onProgressChanged(sessionId: Int, progress: Float) = Unit
                override fun onFinished(sessionId: Int, success: Boolean) {
                    if (!success) return
                    val controller = DeviceOwnerController(this@BlockerVpnService)
                    if (controller.isDeviceOwner()) {
                        val blockMixed = (application as? BlockerApp)
                            ?.preferences?.hardening?.value?.lockMihonMixedExtensions ?: false
                        serviceScope.launch {
                            controller.purgeTorApps()
                            controller.purgeNsfwMihonExtensions(blockMixed)
                            controller.suspendBypassApps()
                        }
                    }
                }
            }
            packageManager.packageInstaller.registerSessionCallback(
                callback,
                Handler(Looper.getMainLooper())
            )
            installSessionCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register PackageInstaller.SessionCallback: ${e.message}")
        }
    }

    private fun unregisterRealtimeInstallMonitors() {
        dynamicPackageReceiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        dynamicPackageReceiver = null

        installSessionCallback?.let {
            runCatching { packageManager.packageInstaller.unregisterSessionCallback(it) }
        }
        installSessionCallback = null
    }

    private fun startForegroundSafe() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun establishVpn() {
        try {
            val builder = Builder()
                .setSession("Blocker DNS Firewall")
                .setMtu(1500)
                .setBlocking(true)
                .addAddress(BypassIpCatalog.VPN_IPV4_ADDRESS, 32)
                .addDnsServer(BypassIpCatalog.VPN_IPV4_DNS)
                .addRoute(BypassIpCatalog.VPN_IPV4_DNS, 32)

            // IPv6 virtual DNS route (falls back gracefully on IPv4-only kernels)
            try {
                builder
                    .addAddress(BypassIpCatalog.VPN_IPV6_ADDRESS, 128)
                    .addDnsServer(BypassIpCatalog.VPN_IPV6_DNS)
                    .addRoute(BypassIpCatalog.VPN_IPV6_DNS, 128)
            } catch (e: Exception) {
                Log.w(TAG, "IPv6 TUN route skipped: ${e.message}")
            }

            // Route known unfiltered public DNS/DoH/DoT IPs into TUN to blackhole bypass attempts
            for (ip in BypassIpCatalog.BLOCKED_RESOLVER_IPV4) {
                try {
                    builder.addRoute(ip, 32)
                } catch (_: Exception) {
                }
            }
            for (ip in BypassIpCatalog.BLOCKED_RESOLVER_IPV6) {
                try {
                    builder.addRoute(ip, 128)
                } catch (_: Exception) {
                }
            }

            // Exclude our own app so blocklist sync & protected upstream sockets never loop
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Could not exclude self from VPN: ${e.message}")
            }

            val tun = builder.establish()
            if (tun == null) {
                Log.e(TAG, "VpnService.Builder.establish() returned null (VPN permission missing?)")
                _isRunning.value = false
                return
            }

            vpnInterface = tun
            _isRunning.value = true
            Log.i(TAG, "Blocker local DNS VPN established")

            // Refresh engine rules from latest preferences/blocklists
            val app = application as BlockerApp
            app.ensureFilterEngineLoaded()

            startPacketLoop(tun)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish VPN", e)
            _isRunning.value = false
        }
    }

    private fun startPacketLoop(tun: ParcelFileDescriptor) {
        readerJob?.cancel()
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)

        readerJob = serviceScope.launch {
            val buffer = ByteArray(4096)
            while (isActive && vpnInterface != null) {
                val len = try {
                    input.read(buffer)
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "TUN read ended: ${e.message}")
                    break
                }
                if (len <= 0) continue

                val packet = PacketParser.parse(buffer, len) ?: continue
                when (packet) {
                    is ParsedIpPacket.Udp -> handleUdpPacket(packet, output)
                    is ParsedIpPacket.TcpSyn -> handleTcpSynPacket(packet, output)
                }
            }
            _isRunning.value = false
        }
    }

    private fun handleTcpSynPacket(syn: ParsedIpPacket.TcpSyn, output: FileOutputStream) {
        // Immediately return TCP RST+ACK for:
        // 1) Android Opportunistic Private DNS probes to our virtual DNS port 853 (<1ms UDP fallback)
        // 2) Blocked DoH/DoT TCP attempts to blackholed resolver IPs (8.8.8.8:443, 1.1.1.1:443, etc.)
        val rst = PacketParser.buildTcpRstResponse(syn)
        if (rst.isNotEmpty()) {
            writeToTun(output, rst)
        }
    }

    private fun handleUdpPacket(udp: ParsedIpPacket.Udp, output: FileOutputStream) {
        // Only answer DNS queries sent to our virtual DNS server IP on port 53.
        // Packets sent to blackholed public DNS/DoH IPs (e.g. 8.8.8.8:53 or 1.1.1.1:443 QUIC) are silently dropped.
        val isToVirtualDns = udp.dstPort == 53 && (
            udp.dstAddress.contentEquals(BypassIpCatalog.VPN_IPV4_DNS_BYTES) ||
                udp.dstAddress.contentEquals(BypassIpCatalog.VPN_IPV6_DNS_BYTES)
            )
        if (!isToVirtualDns) return

        val dnsQuery = DnsCodec.parseQuery(udp.payload) ?: return

        serviceScope.launch {
            queryConcurrency.withPermit {
                val app = application as BlockerApp
                val decision = app.filterEngine.evaluate(dnsQuery.domain)
                val resolver = upstreamResolver ?: return@withPermit

                val dnsResponse: ByteArray? = when (decision) {
                    is FilterDecision.Block -> {
                        app.preferences.recordBlocked(dnsQuery.domain, decision.reason)
                        DnsCodec.buildNxDomainResponse(udp.payload, dnsQuery)
                    }

                    is FilterDecision.SafeSearchRewrite -> {
                        when (dnsQuery.qType) {
                            DnsCodec.TYPE_A, DnsCodec.TYPE_AAAA -> {
                                val ips = resolver.resolveSafeSearchVip(
                                    decision.targetHost,
                                    dnsQuery.qType
                                )
                                DnsCodec.buildAddressResponse(udp.payload, dnsQuery, ips)
                            }
                            // Suppress HTTPS/SVCB records so Chrome cannot bypass SafeSearch via IP hints
                            DnsCodec.TYPE_HTTPS, DnsCodec.TYPE_SVCB -> {
                                DnsCodec.buildNoDataResponse(udp.payload, dnsQuery)
                            }
                            else -> {
                                resolver.forward(udp.payload, dnsQuery)
                            }
                        }
                    }

                    FilterDecision.Allow -> {
                        resolver.forward(udp.payload, dnsQuery)
                    }
                }

                if (dnsResponse != null && dnsResponse.isNotEmpty()) {
                    val responsePacket = PacketParser.buildUdpResponse(udp, dnsResponse)
                    if (responsePacket.isNotEmpty()) {
                        writeToTun(output, responsePacket)
                    }
                }
            }
        }
    }

    private fun writeToTun(output: FileOutputStream, packet: ByteArray) {
        synchronized(tunWriteLock) {
            try {
                output.write(packet)
            } catch (_: Exception) {
                // TUN closed during teardown
            }
        }
    }

    private fun teardownVpn() {
        readerJob?.cancel()
        readerJob = null
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        _isRunning.value = false
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()
    }

    companion object {
        private const val TAG = "BlockerVpnService"
        private const val CHANNEL_ID = "blocker_vpn_protection"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_EMERGENCY_STOP = "com.blocker.app.ACTION_EMERGENCY_STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun start(context: Context) {
            if (BuildConfig.EMERGENCY_DISABLE) return
            val intent = Intent(context, BlockerVpnService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to start VPN foreground service: ${e.message}")
            }
        }

        fun stopForEmergency(context: Context) {
            if (!BuildConfig.EMERGENCY_DISABLE) return
            try {
                val intent = Intent(context, BlockerVpnService::class.java).apply {
                    action = ACTION_EMERGENCY_STOP
                }
                context.startService(intent)
            } catch (_: Exception) {
                runCatching { context.stopService(Intent(context, BlockerVpnService::class.java)) }
            }
        }
    }
}
