# Blocker — Bloqueur Android Permanent & Incontournable (Usage Personnel)

Application Android personnelle conçue pour bloquer les sites pour adultes **sans aucune possibilité de désactivation ni de désinstallation dans l'application**, en combinant le mode **Android Device Owner** et un **pare-feu DNS local Always-On**.

---

## Architecture de protection (6 couches)

1. **Verrouillage système via Android Device Owner (`DevicePolicyManager`)**
   - `setUninstallBlocked` : empêche la désinstallation de Blocker.
   - `setUserControlDisabledPackages` (Android 11+) : désactive **Forcer l'arrêt** et **Effacer les données** spécifiquement sur Blocker (sans gêner vos autres apps).
   - `DISALLOW_CONFIG_VPN` : impossible de couper le VPN ou d'activer un VPN tiers.
   - `DISALLOW_CONFIG_PRIVATE_DNS` + réinitialisation DNS Privé : impossible de configurer un DNS privé non filtré (`dns.google`, etc.).
   - `DISALLOW_SAFE_BOOT` : empêche le démarrage en Mode Sans Échec.
   - `DISALLOW_ADD_USER`, `DISALLOW_USER_SWITCH`, `DISALLOW_ADD_MANAGED_PROFILE`, `no_add_private_profile` (Android 15+) : bloque le contournement via compte invité, profil pro ou Espace Privé.
   - **Aucun bouton de désactivation** ni de suppression de droits administrateur dans le code.

2. **VPN DNS local Always-On (`BlockerVpnService`, compatible Direct Boot)**
   - Démarre automatiquement au démarrage du téléphone (même avant le premier déverrouillage).
   - Route uniquement le serveur DNS virtuel local (`10.200.0.2` / `fd00:200::2`) et les IP de contournement DoH/DoT : le trafic HTTPS normal circule nativement à pleine vitesse et sans surconsommation de batterie.
   - Répond par `TCP RST` immédiat aux sondes port 853 (repli < 1 ms) et aux connexions DoH bloquées.

3. **Blocklists NSFW massives + mise à jour quotidienne automatique**
   - Liste embarquée hors-ligne dès la première seconde (`assets/blocklist_seed.txt`).
   - Synchronisation automatique quotidienne (`WorkManager`) de trois listes communautaires de référence :
     - **HaGeZi NSFW**
     - **OISD NSFW**
     - **StevenBlack Porn**
   - Filtre heuristique par mots-clés explicites et TLD adultes (`.xxx`, `.porn`, `.adult`, `.sex`, `.cam`, `.sexy`) avec liste blanche anti-faux-positifs.
   - **Liste personnalisée en ajout uniquement** : vous pouvez ajouter des domaines à bloquer définitivement, jamais les retirer.

4. **SafeSearch forcé au niveau DNS + navigateur**
   - Redirige au niveau DNS (et supprime les enregistrements `HTTPS`/`SVCB` de contournement) :
     - **Google** (tous les TLD : `.com`, `.fr`, `.be`, `.ch`, `.ca`, `.co.uk`, etc.) → `forcesafesearch.google.com`
     - **Bing** → `strict.bing.com`
     - **DuckDuckGo** → `safe.duckduckgo.com`
     - **Brave Search** → `forcesafe.search.brave.com`
     - **Yandex** → `familysearch.yandex.ru`
     - **Pixabay** → `safesearch.pixabay.com`

5. **Anti-Tor & Anti-contournement DoH / Proxy / Navigateurs VPN**
   - **Blocage APK hors Play Store par défaut** (`DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY` + `DISALLOW_INSTALL_UNKNOWN_SOURCES`) : impossible de sideloader un APK Tor (`torproject.org`, F-Droid, GitHub, etc.).
   - **Interception temps réel + désinstallation silencieuse de Tor** : écoute dynamique `ACTION_PACKAGE_ADDED` + `PackageInstaller.SessionCallback` dans le service VPN (indispensable sur Android 8–15). Dès qu'une app Tor (Tor Browser, Tor Alpha, Orbot, Orfox, Onion Browser, InviZible Pro, OrNET, ou clone/fork détecté par [`TorAppPolicy`](file:///home/antigravity/Papa-Ndiasse/Blocker/app/src/main/java/com/blocker/app/admin/TorAppPolicy.kt)) est installée depuis le Play Store ou ailleurs, Blocker la **masque**, la **suspend** et la **désinstalle silencieusement sans aucune confirmation** via `PackageInstaller.uninstall()`.
   - **Option irréversible `DISALLOW_INSTALL_APPS`** dans l'onglet *Verrouillage* si vous voulez bloquer totalement toute nouvelle installation depuis le Play Store.
   - Bloque par DNS les domaines Tor (`torproject.org`, `torproject.net`, `bridges.torproject.org`, `snowflake.torproject.org`, `orbot.app`, `onionbrowser.com`, `guardianproject.info`) ainsi que les endpoints DoH/DoT publics et leurs adresses IP (`8.8.8.8`, `1.1.1.1`, `9.9.9.9`, etc.).
   - Applique les **politiques d'entreprise Android** sur Chrome, Edge, Brave et Vivaldi (`DnsOverHttpsMode = "off"`, `ForceGoogleSafeSearch = true`, blocage de `chrome://flags` et `chrome://settings/security`).
   - Suspend automatiquement les navigateurs à proxy/VPN intégré (Opera, Opera Mini, Opera GX, Aloha, Puffin, UC Browser).

6. **Double filtrage DNS amont (sans mode restreint YouTube)**
   - Même si un domaine récent n'est pas encore dans les listes locales, les requêtes autorisées sont résolues via des serveurs DNS filtrant le contenu adulte **sans activer le mode restreint YouTube** :
     - **Cloudflare for Families** (`1.1.1.3` / `1.0.0.3`)
     - **CleanBrowsing Adult Filter** (`185.228.168.10` / `185.228.169.11`)
     - **OpenDNS FamilyShield** (`208.67.222.123`)

---

## Compilation de l'APK

Prérequis : JDK 17+ et Android SDK (API 35).

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

L'APK compilé se trouve dans :
`app/build/outputs/apk/debug/app-debug.apk`

---

## Installation et activation permanente (Device Owner via ADB)

### Phase 1 — Test rapide (optionnel mais recommandé)
1. Installez l'APK sur votre téléphone (`adb install app/build/outputs/apk/debug/app-debug.apk`).
2. Ouvrez **Blocker** → cliquez sur **Démarrer le filtre DNS (mode test)** et lancez une **Synchro**.
3. Vérifiez que les sites normaux chargent rapidement, que SafeSearch est actif sur Google, et que les sites adultes sont bloqués.

### Phase 2 — Scellage permanent (Device Owner)
Android impose qu'aucun compte utilisateur ne soit connecté **au moment exact** où l'on active Device Owner (vous pourrez reconnecter votre compte Google immédiatement après).

1. Activez le **Débogage USB** sur votre téléphone et branchez-le au PC.
2. Retirez temporairement vos comptes dans **Paramètres → Comptes** (Google, Samsung, etc.) ainsi que tout profil Invité / Travail / Espace Privé.
3. Lancez le script automatique :
   ```bash
   chmod +x scripts/setup-device-owner.sh
   ./scripts/setup-device-owner.sh
   ```
   *(Ou exécutez manuellement : `adb shell dpm set-device-owner com.blocker.app/.admin.BlockerDeviceAdminReceiver`)*
4. Ouvrez **Blocker** : l'application applique immédiatement toutes les restrictions permanentes et démarre le VPN Always-On.
5. **Reconnectez votre compte Google** dans **Paramètres → Comptes** — Blocker reste verrouillé définitivement !

### Phase 3 — Renforcement Ultime (onglet *Verrouillage*, à sens unique)
Une fois sûr que tout fonctionne parfaitement sur votre appareil, vous pouvez activer de manière **irréversible** :
- **Désactiver la navigation privée Chrome**
- **Bloquer l'installation d'APK externes** (`DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY`)
- **Verrouiller ADB** (`DISALLOW_DEBUGGING_FEATURES`) — à activer en dernier une fois vos tests terminés
- **Bloquer la réinitialisation dans Paramètres** (`DISALLOW_FACTORY_RESET`) — attention : seule une réinitialisation matérielle (Recovery) restera possible.

---

## Mises à jour automatiques silencieuses depuis GitHub

À chaque `git push` sur `main`, le workflow [`.github/workflows/release-apk.yml`](.github/workflows/release-apk.yml) compile, signe et publie une nouvelle GitHub Release (`blocker-release.apk` + `update.json` avec empreinte SHA-256). Sur votre téléphone, `SelfUpdateWorker` vérifie toutes les heures et installe la nouvelle version **silencieusement sans aucun clic** via `PackageInstaller.Session` (grâce au mode Device Owner), puis redémarre le VPN automatiquement (`MY_PACKAGE_REPLACED`).

### Configuration unique (à faire 1 seule fois)
Pour qu'Android accepte les mises à jour automatiques sans erreur de signature, tous les APK doivent être signés avec la même clé persistante :

1. Générez un keystore unique :
   ```bash
   keytool -genkeypair -v -keystore release-keystore.jks \
     -keyalg RSA -keysize 2048 -validity 10000 -alias blocker
   base64 -w 0 release-keystore.jks
   ```
2. Dans **GitHub → Settings → Secrets and variables → Actions** du dépôt `Papa-Ndiasse/Blocker`, ajoutez :
   - `SIGNING_KEYSTORE_BASE64` : la sortie base64 ci-dessus
   - `SIGNING_KEYSTORE_PASSWORD` : mot de passe du keystore
   - `SIGNING_KEY_ALIAS` : `blocker`
   - `SIGNING_KEY_PASSWORD` : mot de passe de la clé
   - `UPDATE_READ_TOKEN` *(indispensable car le dépôt est privé)* : un **Fine-Grained Personal Access Token** GitHub en lecture seule (`Contents: Read-only`) uniquement sur `Papa-Ndiasse/Blocker` (peut aussi être saisi dans l'app).
3. Installez **le premier APK** généré par GitHub Releases sur le téléphone + activez Device Owner. Toutes les futures mises à jour poussées sur `main` s'installeront ensuite **100 % automatiquement**.

