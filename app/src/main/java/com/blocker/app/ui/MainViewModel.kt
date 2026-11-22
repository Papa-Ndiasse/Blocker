package com.blocker.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blocker.app.BlockerApp
import com.blocker.app.admin.DeviceOwnerController
import com.blocker.app.admin.ProtectionStatus
import com.blocker.app.data.BlockedEvent
import com.blocker.app.data.HardeningFlags
import com.blocker.app.updater.GitHubAutoUpdater
import com.blocker.app.updater.UpdateCheckResult
import com.blocker.app.vpn.BlockerVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HardeningOption {
    LOCK_ADB,
    LOCK_UNKNOWN_SOURCES,
    LOCK_ALL_APP_INSTALLS,
    LOCK_CHROME_INCOGNITO,
    LOCK_REDDIT_TOTAL,
    LOCK_X_TOTAL,
    LOCK_MIHON_MIXED,
    LOCK_FACTORY_RESET
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BlockerApp
    private val controller = DeviceOwnerController(application)
    private val updater = GitHubAutoUpdater(application, app.preferences)

    val vpnRunning: StateFlow<Boolean> = BlockerVpnService.isRunning
    val totalBlocked: StateFlow<Long> = app.preferences.totalBlocked
    val todayBlocked: StateFlow<Long> = app.preferences.todayBlocked
    val blocklistCount: StateFlow<Int> = app.preferences.blocklistCount
    val lastSyncEpochMs: StateFlow<Long> = app.preferences.lastSyncEpochMs
    val lastUpdateCheckMs: StateFlow<Long> = app.preferences.lastUpdateCheckMs
    val githubUpdateToken: StateFlow<String> = app.preferences.githubUpdateToken
    val customDomains: StateFlow<Set<String>> = app.preferences.customDomains
    val hardening: StateFlow<HardeningFlags> = app.preferences.hardening
    val recentEvents: StateFlow<List<BlockedEvent>> = app.preferences.recentEvents

    private val _protectionStatus = MutableStateFlow(controller.getProtectionStatus())
    val protectionStatus: StateFlow<ProtectionStatus> = _protectionStatus.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    fun refreshStatus() {
        val status = controller.getProtectionStatus()
        _protectionStatus.value = status
        if (status.isDeviceOwner) {
            controller.enforcePermanentLockdown()
            _protectionStatus.value = controller.getProtectionStatus()
        }
    }

    fun sealAndEnforceLockdown() {
        viewModelScope.launch(Dispatchers.IO) {
            val status = controller.getProtectionStatus()
            if (status.isDeviceOwner) {
                controller.enforcePermanentLockdown()
                _protectionStatus.value = controller.getProtectionStatus()
                _statusBanner.value = "Protection maximale Device Owner scellée et verrouillée."
                return@launch
            }

            // No-PC / No-Shell / No-Account-Deletion permanent seal:
            // Requires VPN + Device Admin (anti-uninstall) + Accessibility Guard (locks all Settings tamper paths)
            if (!vpnRunning.value || !status.isDeviceAdminActive || !status.isAccessibilityGuardEnabled) {
                _statusBanner.value =
                    "Activez d'abord les 3 étapes (VPN, Admin anti-désinstallation et Accessibilité)."
                return@launch
            }

            app.preferences.latchPermanentSeal()
            _protectionStatus.value = controller.getProtectionStatus()
            _statusBanner.value =
                "Protection permanente scellée sans PC ! Paramètres, désinstallation, VPN, DNS privé et Tor verrouillés."
        }
    }

    fun syncBlocklistsNow() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _statusBanner.value = null
            val result = app.blocklistRepository.syncRemoteBlocklists()
            if (result.success) {
                app.reloadFilterEngine()
                _statusBanner.value =
                    "Listes mises à jour : ${result.totalDomains} domaines bloqués (${result.sourcesSucceeded} sources)."
            } else {
                _statusBanner.value = "Échec de synchronisation : ${result.errorMessage ?: "hors ligne"}"
            }
            _isSyncing.value = false
        }
    }

    fun addCustomDomainPermanently(input: String): Boolean {
        val added = app.preferences.addCustomDomain(input)
        return if (added != null) {
            app.filterEngine.addCustomDomain(added)
            _statusBanner.value = "Domaine \"$added\" bloqué définitivement."
            true
        } else {
            _statusBanner.value = "Format de domaine invalide (ex: exemple.com)."
            false
        }
    }

    /**
     * Permanently activates an extra irreversible hardening rule (strictly one-way).
     * Reddit, X, and Mihon-Mixed locks work 100% WITHOUT Device Owner (via DNS + Accessibility Guard).
     */
    fun activateIrreversibleHardening(option: HardeningOption) {
        val requiresDeviceOwner = option in setOf(
            HardeningOption.LOCK_ADB,
            HardeningOption.LOCK_UNKNOWN_SOURCES,
            HardeningOption.LOCK_ALL_APP_INSTALLS,
            HardeningOption.LOCK_CHROME_INCOGNITO,
            HardeningOption.LOCK_FACTORY_RESET
        )
        if (requiresDeviceOwner && !controller.isDeviceOwner()) {
            _statusBanner.value = "Ce verrou système avancé nécessite le mode Device Owner."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            when (option) {
                HardeningOption.LOCK_ADB -> app.preferences.latchAdbLock()
                HardeningOption.LOCK_UNKNOWN_SOURCES -> app.preferences.latchUnknownSourcesLock()
                HardeningOption.LOCK_ALL_APP_INSTALLS -> app.preferences.latchAllAppInstallsLock()
                HardeningOption.LOCK_CHROME_INCOGNITO -> app.preferences.latchChromeIncognitoLock()
                HardeningOption.LOCK_REDDIT_TOTAL -> {
                    app.preferences.latchRedditTotalLock()
                    app.filterEngine.blockRedditEntirely = true
                }
                HardeningOption.LOCK_X_TOTAL -> {
                    app.preferences.latchXTotalLock()
                    app.filterEngine.blockXEntirely = true
                }
                HardeningOption.LOCK_MIHON_MIXED -> {
                    app.preferences.latchMihonMixedLock()
                    if (controller.isDeviceOwner()) {
                        controller.purgeNsfwMihonExtensions(blockMixed = true)
                    }
                    val sync = app.blocklistRepository.syncRemoteBlocklists()
                    if (sync.success) app.reloadFilterEngine()
                }
                HardeningOption.LOCK_FACTORY_RESET -> app.preferences.latchFactoryResetLock()
            }
            if (controller.isDeviceOwner()) {
                controller.applyIrreversibleHardening(app.preferences.hardening.value)
            }
            _protectionStatus.value = controller.getProtectionStatus()
            _statusBanner.value = "Verrouillage supplémentaire appliqué définitivement."
        }
    }

    fun checkGitHubUpdateNow() {
        if (_isCheckingUpdate.value) return
        viewModelScope.launch {
            _isCheckingUpdate.value = true
            _statusBanner.value = null
            when (val res = updater.checkAndInstallIfAvailable()) {
                is UpdateCheckResult.UpToDate -> {
                    _statusBanner.value = "Blocker est déjà à jour (v${res.currentVersionName})."
                }
                is UpdateCheckResult.InstallingSilently -> {
                    _statusBanner.value =
                        "Installation silencieuse de la v${res.versionName} en cours..."
                }
                is UpdateCheckResult.ReadyNeedsDeviceOwner -> {
                    _statusBanner.value =
                        "v${res.versionName} téléchargée — confirmez la mise à jour Android."
                }
                is UpdateCheckResult.Error -> {
                    _statusBanner.value = "Erreur MAJ GitHub : ${res.message}"
                }
            }
            _isCheckingUpdate.value = false
        }
    }

    fun saveGithubToken(token: String) {
        app.preferences.saveGithubUpdateToken(token)
        _statusBanner.value = "Token GitHub (lecture seule) enregistré."
    }

    fun clearBanner() {
        _statusBanner.value = null
    }
}
