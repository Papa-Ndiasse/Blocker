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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Local Always-On DNS Filtering VPN Service — hardened for Samsung Galaxy A55 / One UI (Android 14-16).
 *
 * Key stability design for Samsung One UI / Exynos:
 *   - All TUN creation, teardown, and blocklist loading run off the Main Thread (zero ANR)
 *   - Clean single IPv4 /24 subnet (`10.200.0.1/24`) with on-link virtual DNS (`10.200.0.2/32`)
 *     and NO multi-dozen blackhole host routes or IPv6 ULA routes that break Samsung Wi-Fi
 *     connectivity monitors (`SemWifiConnectivityMonitor`) or trigger netd/SystemUI instabilities
 *   - Marks tunnel unmetered (`setMetered(false)`) and excludes `com.android.systemui` &
 *     `com.sec.android.app.launcher` (One UI Home) so Samsung UI can never stall on DNS
 *   - Fixed 8-worker bounded Channel (128 slots) prevents unbounded coroutine/RAM spikes during bursts
 *   - Serialized TUN lifecycle (`vpnLock`) ensures two TUN FDs are never opened concurrently
 */
class BlockerVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vpnLock = Any()
    private val tunWriteLock = Any()

    @Volatile
    private var vpnInterface: ParcelFileDescriptor? = null

    private var readerJob: Job? = null
    private var workerJobs: List<Job> = emptyList()
    private var packetChannel: Channel<ParsedIpPacket.Udp>? = null
    private var upstreamResolver: UpstreamDnsResolver? = null
    private var dynamicPackageReceiver: PackageInstallReceiver? = null
    private var installSessionCallback: PackageInstaller.SessionCallback? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        upstreamResolver = UpstreamDnsResolver(this)
        // Only register heavy system-wide package install monitors when Device Owner is active
        if (runCatching { DeviceOwnerController(this).isDeviceOwner() }.getOrDefault(false)) {
            registerRealtimeInstallMonitors()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.EMERGENCY_DISABLE ||
            (intent?.action == ACTION_EMERGENCY_STOP && BuildConfig.EMERGENCY_DISABLE)
        ) {
            serviceScope.launch { teardownVpn() }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }

        // Foreground notification must be posted immediately on Main Thread (<1ms)
        startForegroundSafe()

        // Establish TUN & load filter engine off the Main Thread so One UI / SystemUI never stalls
        serviceScope.launch {
            establishVpnSafely()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        Log.w(TAG, "VPN revoked by system")
        serviceScope.launch {
            teardownVpn()
            if (!BuildConfig.EMERGENCY_DISABLE) {
                val controller = DeviceOwnerController(this@BlockerVpnService)
                if (controller.isDeviceOwner()) {
                    controller.enforcePermanentLockdown()
                }
            }
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
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Unable to enter foreground: ${e.message}")
        }
    }

    private fun establishVpnSafely() {
        synchronized(vpnLock) {
            if (BuildConfig.EMERGENCY_DISABLE) return
            if (vpnInterface != null && readerJob?.isActive == true) {
                // Already running cleanly — never open a second concurrent TUN (causes Samsung VPN crash!)
                return
            }

            // Close any stale TUN before establishing a new one
            teardownVpnLocked()

            try {
                // Load filter engine BEFORE opening TUN so first DNS packets are filtered immediately
                val app = application as BlockerApp
                app.ensureFilterEngineLoaded()

                val builder = Builder()
                    .setSession("Blocker DNS")
                    .setMtu(1500)
                    .setBlocking(true)
                    // Clean /24 subnet so 10.200.0.2 is strictly on-link (critical on Samsung One UI!)
                    .addAddress(BypassIpCatalog.VPN_IPV4_ADDRESS, 24)
                    .addDnsServer(BypassIpCatalog.VPN_IPV4_DNS)
                    .addRoute(BypassIpCatalog.VPN_IPV4_DNS, 32)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }

                // Exclude self and core Samsung One UI system UI packages so UI/Launcher can never stall
                for (excludedPkg in EXCLUDED_SYSTEM_PACKAGES + packageName) {
                    runCatching { builder.addDisallowedApplication(excludedPkg) }
                }

                val tun = builder.establish()
                if (tun == null) {
                    Log.e(TAG, "VpnService.Builder.establish() returned null (VPN permission missing?)")
                    _isRunning.value = false
                    return
                }

                vpnInterface = tun
                _isRunning.value = true
                Log.i(TAG, "Blocker local DNS VPN established (Samsung One UI safe mode)")

                startPacketPipelineLocked(tun)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to establish VPN safely", t)
                teardownVpnLocked()
            }
        }
    }

    private fun startPacketPipelineLocked(tun: ParcelFileDescriptor) {
        val input = FileInputStream(tun.fileDescriptor)
        val output = FileOutputStream(tun.fileDescriptor)

        val channel = Channel<ParsedIpPacket.Udp>(
            capacity = MAX_QUEUED_DNS_PACKETS,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        packetChannel = channel

        // Fixed small worker pool (8 workers) handles DNS bursts with minimal RAM/GC overhead
        workerJobs = List(DNS_WORKER_COUNT) {
            serviceScope.launch {
                for (udp in channel) {
                    try {
                        processDnsUdpPacket(udp, output)
                    } catch (t: Throwable) {
                        Log.w(TAG, "DNS worker skipped packet: ${t.message}")
                    }
                }
            }
        }

        readerJob = serviceScope.launch {
            val buffer = ByteArray(4096)
            try {
                while (isActive && vpnInterface === tun) {
                    val len = try {
                        input.read(buffer)
                    } catch (e: Exception) {
                        if (isActive) Log.w(TAG, "TUN read ended: ${e.message}")
                        break
                    }
                    if (len <= 0) continue

                    val packet = try {
                        PacketParser.parse(buffer, len)
                    } catch (_: Throwable) {
                        null
                    } ?: continue

                    when (packet) {
                        is ParsedIpPacket.Udp -> {
                            if (packet.dstPort == 53 &&
                                packet.dstAddress.contentEquals(BypassIpCatalog.VPN_IPV4_DNS_BYTES)
                            ) {
                                channel.trySend(packet)
                            }
                        }
                        is ParsedIpPacket.TcpSyn -> {
                            // Fast TCP RST on port 853 probes so Android opportunistic DoT falls back to UDP/53 in <1ms
                            val rst = PacketParser.buildTcpRstResponse(packet)
                            if (rst.isNotEmpty()) {
                                writeToTun(output, rst)
                            }
                        }
                    }
                }
            } finally {
                channel.close()
                if (vpnInterface === tun) {
                    _isRunning.value = false
                }
            }
        }
    }

    private fun processDnsUdpPacket(udp: ParsedIpPacket.Udp, output: FileOutputStream) {
        val dnsQuery = DnsCodec.parseQuery(udp.payload) ?: return
        val app = application as BlockerApp
        val decision = app.filterEngine.evaluate(dnsQuery.domain)
        val resolver = upstreamResolver ?: return

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
        synchronized(vpnLock) {
            teardownVpnLocked()
        }
    }

    private fun teardownVpnLocked() {
        readerJob?.cancel()
        readerJob = null
        packetChannel?.close()
        packetChannel = null
        workerJobs.forEach { it.cancel() }
        workerJobs = emptyList()
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
        private const val DNS_WORKER_COUNT = 8
        private const val MAX_QUEUED_DNS_PACKETS = 128

        // Core Samsung One UI / Android UI components that never browse web content
        // and must remain completely unaffected by local VPN DNS transitions.
        private val EXCLUDED_SYSTEM_PACKAGES = listOf(
            "com.android.systemui",
            "com.sec.android.app.launcher",
            "com.samsung.android.honeyboard"
        )

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
