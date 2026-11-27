package com.blocker.app

import android.app.Application
import android.os.UserManager
import com.blocker.app.admin.DeviceOwnerController
import com.blocker.app.admin.EmergencyKillSwitch
import com.blocker.app.data.BlockerPreferences
import com.blocker.app.filter.BlocklistRepository
import com.blocker.app.filter.DnsFilterEngine
import com.blocker.app.updater.SelfUpdateWorker
import com.blocker.app.worker.BlocklistSyncWorker
import com.blocker.app.worker.ProtectionWatchdogWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class BlockerApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineLoaded = AtomicBoolean(false)

    lateinit var preferences: BlockerPreferences
        private set

    lateinit var blocklistRepository: BlocklistRepository
        private set

    lateinit var filterEngine: DnsFilterEngine
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = BlockerPreferences(this)
        blocklistRepository = BlocklistRepository(this, preferences)
        filterEngine = DnsFilterEngine(
            blockRedditEntirely = preferences.hardening.value.lockRedditEntirely,
            blockXEntirely = preferences.hardening.value.lockXEntirely
        )

        // Remote Emergency Kill-Switch (ONLY active if built via GitHub Actions with EMERGENCY_DISABLE=true)
        if (EmergencyKillSwitch.executeIfActive(this)) {
            return
        }

        // Load seed + cached binary blocklist hashes (~40ms) asynchronously at startup
        appScope.launch(Dispatchers.Default) {
            reloadFilterEngine()
        }

        // WorkManager requires credential-encrypted storage (user unlocked)
        val userManager = getSystemService(UserManager::class.java)
        val controller = DeviceOwnerController(this)
        val isOwner = runCatching { controller.isDeviceOwner() }.getOrDefault(false)

        if (userManager.isUserUnlocked) {
            // One-time cleanup of any legacy 4G WorkManager retry jobs left over from v1.0.3-v1.0.12
            val migrationPrefs = createDeviceProtectedStorageContext()
                .getSharedPreferences("blocker_migrations", MODE_PRIVATE)
            if (!migrationPrefs.getBoolean("wifi_only_v13", false)) {
                runCatching {
                    val wm = androidx.work.WorkManager.getInstance(this)
                    wm.cancelAllWorkByTag("com.blocker.app.worker.BlocklistSyncWorker")
                    wm.cancelAllWorkByTag("com.blocker.app.updater.SelfUpdateWorker")
                    wm.pruneWork()
                }
                migrationPrefs.edit().putBoolean("wifi_only_v13", true).apply()
            }
            BlocklistSyncWorker.scheduleDaily(this)
            SelfUpdateWorker.scheduleHourly(this)
            if (isOwner) {
                ProtectionWatchdogWorker.schedule(this)
            } else {
                // Avoid 96 pointless 15-min background wakeups/day when Device Owner is not used
                ProtectionWatchdogWorker.cancel(this)
            }
            if (preferences.lastSyncEpochMs.value == 0L) {
                BlocklistSyncWorker.triggerImmediate(this)
            }
            if (preferences.quarantineState.value.hasUnreportedEntries()) {
                com.blocker.app.updater.QuarantineReportWorker.enqueue(this)
            }
        }

        // If Device Owner is active, enforce permanent lockdown on app launch
        if (isOwner) {
            controller.enforcePermanentLockdown()
        }
    }

    fun ensureFilterEngineLoaded() {
        if (!engineLoaded.get()) {
            reloadFilterEngine()
        }
    }

    fun reloadFilterEngine() {
        val adultHashes = blocklistRepository.loadActiveHashes()
        filterEngine.updateAdultBlocklistHashes(adultHashes)
        filterEngine.updateCustomBlocklist(preferences.customDomains.value)
        val flags = preferences.hardening.value
        filterEngine.blockRedditEntirely = flags.lockRedditEntirely
        filterEngine.blockXEntirely = flags.lockXEntirely
        engineLoaded.set(true)
    }
}
