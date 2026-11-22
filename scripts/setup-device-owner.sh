#!/usr/bin/env bash
set -euo pipefail

APK_PATH="${1:-app/build/outputs/apk/debug/app-debug.apk}"
COMPONENT="com.blocker.app/.admin.BlockerDeviceAdminReceiver"

echo "=== Installation & Scellage Device Owner : Blocker ==="

if ! command -v adb >/dev/null 2>&1; then
  echo "[ERREUR] 'adb' n'est pas installé ou introuvable dans le PATH."
  echo "Installez Android Platform Tools puis relancez ce script."
  exit 1
fi

if [ ! -f "$APK_PATH" ]; then
  echo "[INFO] APK introuvable à $APK_PATH — compilation avec ./gradlew assembleDebug..."
  ./gradlew assembleDebug
fi

echo "[1/4] Vérification de l'appareil Android connecté..."
adb wait-for-device
adb devices

echo "[2/4] Installation de l'APK ($APK_PATH)..."
adb install -r "$APK_PATH"

echo "[3/4] Activation du mode Device Owner (Propriétaire de l'appareil)..."
echo "      Note: tous les comptes (Google, Samsung, etc.) et profils secondaires"
echo "      doivent être temporairement retirés dans Paramètres > Comptes avant cette étape."
if ! adb shell dpm set-device-owner "$COMPONENT"; then
  echo ""
  echo "[ÉCHEC] Impossible de définir Device Owner."
  echo "Causes les plus fréquentes :"
  echo "  1. Un compte (Google / Samsung / WhatsApp / etc.) est encore présent sur le téléphone."
  echo "     -> Supprimez temporairement les comptes dans Paramètres > Comptes (vous pourrez"
  echo "        les remettre immédiatement après cette commande)."
  echo "  2. Un profil professionnel, invité ou espace privé existe sur l'appareil."
  echo "     -> Supprimez les autres utilisateurs/profils temporairement."
  exit 1
fi

echo "[4/4] Lancement de Blocker pour appliquer le verrouillage permanent..."
adb shell am start -n com.blocker.app/.MainActivity

echo ""
echo "[SUCCÈS] Blocker est maintenant Device Owner et verrouillé !"
echo "Vous pouvez maintenant reconnecter votre compte Google dans Paramètres > Comptes."
echo "Testez votre navigation, puis activez les verrous ultimes (ex: ADB) dans l'onglet Verrouillage."
