package com.blocker.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blocker.app.BuildConfig
import com.blocker.app.admin.DeviceOwnerController
import com.blocker.app.admin.ProtectionStatus
import com.blocker.app.data.BlockedEvent
import com.blocker.app.data.HardeningFlags
import com.blocker.app.ui.theme.AmberContainer
import com.blocker.app.ui.theme.AmberWarning
import com.blocker.app.ui.theme.CrimsonAlert
import com.blocker.app.ui.theme.EmeraldContainer
import com.blocker.app.ui.theme.EmeraldPrimary
import com.blocker.app.ui.theme.SurfaceDark
import com.blocker.app.ui.theme.SurfaceVariantDark
import com.blocker.app.ui.theme.TextSecondary
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BlockerRootScreen(
    viewModel: MainViewModel,
    onRequestTestVpn: () -> Unit
) {
    val status by viewModel.protectionStatus.collectAsState()
    val vpnRunning by viewModel.vpnRunning.collectAsState()
    val totalBlocked by viewModel.totalBlocked.collectAsState()
    val todayBlocked by viewModel.todayBlocked.collectAsState()
    val blocklistCount by viewModel.blocklistCount.collectAsState()
    val lastSyncMs by viewModel.lastSyncEpochMs.collectAsState()
    val lastUpdateCheckMs by viewModel.lastUpdateCheckMs.collectAsState()
    val githubToken by viewModel.githubUpdateToken.collectAsState()
    val customDomains by viewModel.customDomains.collectAsState()
    val hardening by viewModel.hardening.collectAsState()
    val recentEvents by viewModel.recentEvents.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val isCheckingUpdate by viewModel.isCheckingUpdate.collectAsState()
    val banner by viewModel.statusBanner.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(banner) {
        banner?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearBanner()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(containerColor = SurfaceDark) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Shield, contentDescription = "Bouclier") },
                    label = { Text("Bouclier") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Block, contentDescription = "Domaines") },
                    label = { Text("Domaines") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Lock, contentDescription = "Verrouillage") },
                    label = { Text("Verrouillage") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> DashboardTab(
                    status = status,
                    vpnRunning = vpnRunning,
                    totalBlocked = totalBlocked,
                    todayBlocked = todayBlocked,
                    blocklistCount = blocklistCount,
                    lastSyncMs = lastSyncMs,
                    lastUpdateCheckMs = lastUpdateCheckMs,
                    hasGithubToken = githubToken.isNotBlank() || BuildConfig.GITHUB_UPDATE_TOKEN.isNotBlank(),
                    recentEvents = recentEvents,
                    isSyncing = isSyncing,
                    isCheckingUpdate = isCheckingUpdate,
                    onRequestTestVpn = onRequestTestVpn,
                    onRefreshStatus = viewModel::refreshStatus,
                    onSealPermanent = viewModel::sealAndEnforceLockdown,
                    onSyncNow = viewModel::syncBlocklistsNow,
                    onCheckUpdateNow = viewModel::checkGitHubUpdateNow,
                    onSaveGithubToken = viewModel::saveGithubToken
                )

                1 -> CustomDomainsTab(
                    customDomains = customDomains.sorted(),
                    onAddDomain = viewModel::addCustomDomainPermanently
                )

                2 -> HardeningTab(
                    isDeviceOwner = status.isDeviceOwner,
                    hardening = hardening,
                    onActivateHardening = viewModel::activateIrreversibleHardening
                )
            }
        }
    }
}

@Composable
private fun DashboardTab(
    status: ProtectionStatus,
    vpnRunning: Boolean,
    totalBlocked: Long,
    todayBlocked: Long,
    blocklistCount: Int,
    lastSyncMs: Long,
    lastUpdateCheckMs: Long,
    hasGithubToken: Boolean,
    recentEvents: List<BlockedEvent>,
    isSyncing: Boolean,
    isCheckingUpdate: Boolean,
    onRequestTestVpn: () -> Unit,
    onRefreshStatus: () -> Unit,
    onSealPermanent: () -> Unit,
    onSyncNow: () -> Unit,
    onCheckUpdateNow: () -> Unit,
    onSaveGithubToken: (String) -> Unit
) {
    val context = LocalContext.current
    val numberFmt = remember { NumberFormat.getIntegerInstance(Locale.FRANCE) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }

    if (showTokenDialog) {
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text("Token GitHub (dépôt privé)") },
            text = {
                Column {
                    Text(
                        "Votre dépôt Papa-Ndiasse/Blocker est privé. Collez un Fine-Grained Token GitHub en lecture seule (Contents: Read-only) pour autoriser les mises à jour automatiques.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("github_pat_...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveGithubToken(tokenInput)
                        tokenInput = ""
                        showTokenDialog = false
                    },
                    enabled = tokenInput.isNotBlank()
                ) {
                    Text("Enregistrer")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTokenDialog = false }) {
                    Text("Annuler")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Hero Shield Status Card
        item {
            val fullyLocked = status.isDeviceOwner && vpnRunning
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (fullyLocked) EmeraldContainer else AmberContainer
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (fullyLocked) EmeraldPrimary.copy(alpha = 0.25f)
                                    else AmberWarning.copy(alpha = 0.25f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (fullyLocked) Icons.Default.Shield else Icons.Default.Warning,
                                contentDescription = null,
                                tint = if (fullyLocked) EmeraldPrimary else AmberWarning,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    fullyLocked -> "Protection Permanente Active"
                                    status.isDeviceOwner -> "Device Owner Actif — Activation..."
                                    vpnRunning -> "Filtrage Actif (Non Verrouillé)"
                                    else -> "Configuration Requise"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (fullyLocked) {
                                    "Aucune désactivation ni désinstallation possible."
                                } else {
                                    "Activez le mode Device Owner via ADB pour sceller le blocage définitivement."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                        }
                    }

                    if (!status.isDeviceOwner) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.35f))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = DeviceOwnerController.ADB_SET_DEVICE_OWNER_CMD,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { copyToClipboard(context, DeviceOwnerController.ADB_SET_DEVICE_OWNER_CMD) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copier ADB", fontSize = 12.sp)
                            }
                            Button(
                                onClick = onRefreshStatus,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = AmberWarning)
                            ) {
                                Text("Vérifier", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            }
                        }
                        if (!vpnRunning) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = onRequestTestVpn,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Démarrer le filtre DNS (mode test avant scellage)")
                            }
                        }
                    } else if (!vpnRunning) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onSealPermanent,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Appliquer et démarrer le bouclier permanent")
                        }
                    }
                }
            }
        }

        // 2. Live Counters Row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard(
                    title = "Aujourd'hui",
                    value = numberFmt.format(todayBlocked),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Total bloqué",
                    value = numberFmt.format(totalBlocked),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "Domaines",
                    value = numberFmt.format(blocklistCount),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 3. Blocklists Sync Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "10 Blocklists NSFW & Anti-Contournement (>1,2M)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (lastSyncMs > 0L) {
                                "Dernière synchro : ${formatDateTime(lastSyncMs)}"
                            } else {
                                "Liste embarquée complète active — cliquez Synchro"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onSyncNow,
                        enabled = !isSyncing,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.Black
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Synchro", fontSize = 12.sp)
                    }
                }
            }
        }

        // 3a. GitHub Silent Auto-Update Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-MAJ GitHub (silencieuse) — v${BuildConfig.VERSION_NAME}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (lastUpdateCheckMs > 0L) {
                                    "Vérification auto toutes les heures • Dernière : ${formatDateTime(lastUpdateCheckMs)}"
                                } else {
                                    "Installe automatiquement chaque nouvelle release GitHub (${BuildConfig.GITHUB_REPO})"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onCheckUpdateNow,
                            enabled = !isCheckingUpdate,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.Black
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("MAJ", fontSize = 12.sp)
                        }
                    }
                    if (!hasGithubToken) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showTokenDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Configurer token GitHub (si dépôt privé)", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 3b. Real-Time In-App Guard (Reddit, X, Browsers, Social Apps) Activation Card
        if (!status.isAccessibilityGuardEnabled) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AmberContainer),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Activer le Bouclier Écran (Reddit, X, Apps)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Indispensable pour bloquer en temps réel les contenus NSFW, 18+ et limites dans Reddit, X (Twitter), Telegram, Discord et les barres d'adresse. Une fois activé, Blocker se protège contre sa propre désactivation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.88f)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                context.startActivity(intent)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AmberWarning),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Ouvrir Accessibilité → Activer Blocker",
                                color = Color.Black,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // 4. Protection Layers Checklist
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Couches de protection actives",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    ProtectionCheckRow("Propriétaire de l'appareil (Device Owner)", status.isDeviceOwner)
                    ProtectionCheckRow("VPN DNS local Always-On (>1,2M domaines)", vpnRunning)
                    ProtectionCheckRow("Bouclier Écran temps réel (Reddit / X / Apps)", status.isAccessibilityGuardEnabled)
                    ProtectionCheckRow("Anti-désinstallation Blocker", status.isUninstallBlocked)
                    ProtectionCheckRow("Anti-Forcer l'arrêt & Effacer données", status.isUserControlDisabled)
                    ProtectionCheckRow("Verrouillage VPN (impossible de couper)", status.isVpnConfigLocked)
                    ProtectionCheckRow("Verrouillage DNS Privé + Anti-DoH", status.isPrivateDnsLocked)
                    ProtectionCheckRow("Anti-Tor (APK bloqués + désinstallation silencieuse)", status.isDeviceOwner && status.isUnknownSourcesLocked)
                    ProtectionCheckRow("Extensions Keiyoushi / Mihon 18+ (DNS + purge auto)", vpnRunning && status.isDeviceOwner)
                    ProtectionCheckRow("SafeSearch forcé (Google, Bing, DDG, Brave)", vpnRunning)
                    ProtectionCheckRow("DNS Amont Adulte (1.1.1.3 / CleanBrowsing Adult / OpenDNS)", vpnRunning)
                    ProtectionCheckRow("Anti-Mode Sans Échec & Multi-Utilisateurs", status.isSafeBootLocked && status.isMultiUserLocked)
                    ProtectionCheckRow(
                        "Navigateurs verrouillés (DoH=off, SafeSearch)",
                        status.managedBrowsersSecured > 0,
                        subtitle = if (status.managedBrowsersSecured > 0) "${status.managedBrowsersSecured} navigateur(s) géré(s)" else null
                    )
                    if (status.bypassAppsSuspended > 0) {
                        ProtectionCheckRow(
                            "Apps proxy/Tor suspendues automatiquement",
                            true,
                            subtitle = "${status.bypassAppsSuspended} app(s) bloquée(s)"
                        )
                    }
                }
            }
        }

        // 5. Masked Blocked Activity Log
        item {
            Text(
                text = "Journal des blocages récents (domaines masqués)",
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (recentEvents.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Aucune tentative bloquée sur cette session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(recentEvents.take(20)) { event ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Block,
                            contentDescription = null,
                            tint = CrimsonAlert,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = event.maskedDomain,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = event.reason,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary
                            )
                        }
                        Text(
                            text = formatTime(event.timestampMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CustomDomainsTab(
    customDomains: List<String>,
    onAddDomain: (String) -> Boolean
) {
    var domainInput by remember { mutableStateOf("") }
    var pendingConfirmDomain by remember { mutableStateOf<String?>(null) }

    pendingConfirmDomain?.let { toBlock ->
        AlertDialog(
            onDismissRequest = { pendingConfirmDomain = null },
            title = { Text("Bloquer définitivement ?") },
            text = {
                Text(
                    "Le domaine \"$toBlock\" (et tous ses sous-domaines) sera bloqué de façon permanente.\n\n" +
                        "Il est impossible de le supprimer une fois ajouté."
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (onAddDomain(toBlock)) {
                        domainInput = ""
                    }
                    pendingConfirmDomain = null
                }) {
                    Text("Bloquer définitivement")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmDomain = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Liste personnelle (ajout uniquement)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Ajoutez n'importe quel domaine supplémentaire à bloquer. " +
                            "Par sécurité, aucun bouton de suppression n'existe.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        label = { Text("Domaine à bloquer (ex: exemple.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = {
                            val trimmed = domainInput.trim()
                            if (trimmed.isNotEmpty()) {
                                pendingConfirmDomain = trimmed
                            }
                        },
                        enabled = domainInput.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ajouter au blocage permanent")
                    }
                }
            }
        }

        item {
            Text(
                text = "Domaines verrouillés (${customDomains.size})",
                style = MaterialTheme.typography.titleSmall,
                color = TextSecondary
            )
        }

        if (customDomains.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Aucun domaine personnalisé ajouté pour le moment.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(customDomains) { domain ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = domain,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "VERROUILLÉ",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HardeningTab(
    isDeviceOwner: Boolean,
    hardening: HardeningFlags,
    onActivateHardening: (HardeningOption) -> Unit
) {
    var pendingOption by remember { mutableStateOf<Pair<HardeningOption, String>?>(null) }

    pendingOption?.let { (option, title) ->
        AlertDialog(
            onDismissRequest = { pendingOption = null },
            title = { Text("Verrouillage irréversible") },
            text = {
                Text(
                    "Vous êtes sur le point d'activer définitivement :\n\n• $title\n\n" +
                        "Cette action est À SENS UNIQUE et ne pourra plus être désactivée dans l'application."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onActivateHardening(option)
                        pendingOption = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary)
                ) {
                    Text("Sceller définitivement")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOption = null }) {
                    Text("Annuler")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Renforcement Ultime (à sens unique)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isDeviceOwner) {
                            "Chaque protection activée ici est verrouillée définitivement au niveau d'Android (OS). " +
                                "Testez d'abord que tout fonctionne bien avant de verrouiller ADB."
                        } else {
                            "Activez d'abord le mode Device Owner via ADB dans l'onglet Bouclier pour débloquer ces verrous système."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        item {
            OneWayHardeningCard(
                title = "Désactiver la navigation privée Chrome",
                description = "Force IncognitoModeAvailability = 1 sur Chrome, Edge, Brave et Vivaldi.",
                isLocked = hardening.lockChromeIncognito,
                enabled = isDeviceOwner,
                onRequestLock = {
                    pendingOption = HardeningOption.LOCK_CHROME_INCOGNITO to "Désactiver la navigation privée Chrome"
                }
            )
        }

        item {
            OneWayHardeningCard(
                title = "Bloquer Reddit intégralement (site + app)",
                description = "Bloque tout reddit.com / redd.it au niveau DNS et masque l'app Reddit (risque zéro même pour les contenus limites non étiquetés).",
                isLocked = hardening.lockRedditEntirely,
                enabled = isDeviceOwner,
                onRequestLock = {
                    pendingOption = HardeningOption.LOCK_REDDIT_TOTAL to "Bloquer Reddit intégralement (site + app)"
                }
            )
        }

        item {
            OneWayHardeningCard(
                title = "Bloquer X / Twitter intégralement (site + app)",
                description = "Bloque tout x.com / twitter.com / twimg.com au niveau DNS et masque l'app X (risque zéro même pour les médias limites sans avertissement).",
                isLocked = hardening.lockXEntirely,
                enabled = isDeviceOwner,
                onRequestLock = {
                    pendingOption = HardeningOption.LOCK_X_TOTAL to "Bloquer X / Twitter intégralement (site + app)"
                }
            )
        }

        item {
            OneWayHardeningCard(
                title = "Bloquer aussi les extensions Mihon Mixtes (Keiyoushi cw=2)",
                description = "Les 494+ extensions 18+ (cw=3) et Comick sont déjà bloquées par défaut. Ce verrou bloque en plus les sources mixtes (MangaDex, Bato, etc.) et ne laisse que les sources 100% SAFE (cw=1).",
                isLocked = hardening.lockMihonMixedExtensions,
                enabled = isDeviceOwner,
                onRequestLock = {
                    pendingOption = HardeningOption.LOCK_MIHON_MIXED to "Bloquer aussi les extensions Mihon Mixtes (cw=2)"
                }
            )
        }

        item {
            OneWayHardeningCard(
                title = "Bloquer l'installation d'APK externes (anti-Tor APK)",
                description = "Active automatiquement avec Device Owner (DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY).",
                isLocked = hardening.lockUnknownSources,
                enabled = isDeviceOwner,
                onRequestLock = {
                    pendingOption = HardeningOption.LOCK_UNKNOWN_SOURCES to "Bloquer l'installation d'APK externes"
                }
            )
        }

        item {
            OneWayHardeningCard(
                title = "Bloquer TOUTE nouvelle installation d'apps (Play Store)",
                description = "Active DISALLOW_INSTALL_APPS : empêche d'installer n'importe quelle nouvelle application (à activer seulement une fois toutes vos apps installées).",
                isLocked = hardening.lockAllAppInstalls,
                enabled = isDeviceOwner,
                onRequestLock = {
                    pendingOption = HardeningOption.LOCK_ALL_APP_INSTALLS to "Bloquer TOUTE nouvelle installation d'apps"
                }
            )
        }

        item {
            OneWayHardeningCard(
                title = "Verrouiller ADB & Options Développeur",
                description = "Active DISALLOW_DEBUGGING_FEATURES. Empêche toute commande ADB depuis un PC (activez-le en dernier, une fois vos tests terminés).",
                isLocked = hardening.lockAdbDebugging,
                enabled = isDeviceOwner,
                onRequestLock = {
                    pendingOption = HardeningOption.LOCK_ADB to "Verrouiller ADB & Options Développeur"
                }
            )
        }

        item {
            OneWayHardeningCard(
                title = "Bloquer la réinitialisation dans Paramètres",
                description = "Active DISALLOW_FACTORY_RESET dans les paramètres Android (attention : seule la récupération matérielle restera possible).",
                isLocked = hardening.lockFactoryResetSettings,
                enabled = isDeviceOwner,
                onRequestLock = {
                    pendingOption = HardeningOption.LOCK_FACTORY_RESET to "Bloquer la réinitialisation dans Paramètres"
                }
            )
        }
    }
}

@Composable
private fun OneWayHardeningCard(
    title: String,
    description: String,
    isLocked: Boolean,
    enabled: Boolean,
    onRequestLock: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isLocked) EmeraldContainer.copy(alpha = 0.55f) else SurfaceDark
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                if (isLocked) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Verrouillé",
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "ACTIF",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Button(
                        onClick = onRequestLock,
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Activer", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = EmeraldPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun ProtectionCheckRow(
    label: String,
    active: Boolean,
    subtitle: String? = null
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (active) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (active) EmeraldPrimary else AmberWarning,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodySmall)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
            Text(
                text = if (active) "VERROUILLÉ" else "EN ATTENTE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (active) EmeraldPrimary else AmberWarning
            )
        }
        HorizontalDivider(color = SurfaceVariantDark.copy(alpha = 0.6f))
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("ADB Command", text))
    Toast.makeText(context, "Commande ADB copiée", Toast.LENGTH_SHORT).show()
}

private fun formatDateTime(epochMs: Long): String {
    val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
    return sdf.format(Date(epochMs))
}

private fun formatTime(epochMs: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    return sdf.format(Date(epochMs))
}
