package com.blocker.app

import android.app.Application
import android.os.UserManager
import com.blocker.app.admin.DeviceOwnerController
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

        // Load seed + cached binary blocklist hashes (~40ms) asynchronously at startup
        appScope.launch {
            reloadFilterEngine()
        }

        // WorkManager requires credential-encrypted storage (user unlocked)
        val userManager = getSystemService(UserManager::class.java)
        if (userManager.isUserUnlocked) {
            BlocklistSyncWorker.scheduleDaily(this)
            ProtectionWatchdogWorker.schedule(this)
            SelfUpdateWorker.scheduleHourly(this)
            if (preferences.lastSyncEpochMs.value == 0L) {
                BlocklistSyncWorker.triggerImmediate(this)
            }
        }

        // If Device Owner is active, enforce permanent lockdown on app launch
        val controller = DeviceOwnerController(this)
        if (controller.isDeviceOwner()) {
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
