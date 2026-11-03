# ADB Pilot pour Android

Version Android (tablette) d'[ADB Pilot](https://github.com/rerem8433/adb_pilot), par **ParadoxXx Squad**.
Gère les box Android TV et téléphones **depuis une tablette Android**, en Wi-Fi (port 5555) ou en USB OTG — sans PC, sans root.

> 🧪 Version de test.

## Fonctionnalités
- Protocole ADB intégré en Java (Wi-Fi + USB OTG, clé RSA, shell v2, push/pull) — aucun binaire adb.
- Applications : noms, icônes et versions lus sur l'appareil, filtres, actions, sauvegarde, permissions, presets.
- Assistant debloat (listes debloat0-3 du dépôt ADB Pilot), installateur APK / XAPK / APKS / APKM, Store.
- Outils : fiche appareil (RAM / Android réels), télécommande, captures, scrcpy intégré (serveur v3.3.4, décodage matériel, son Android 11+, enregistrement MP4).
- Processus, console adb / fastboot (USB), shell interactif, logcat.

Dossier de travail : `/sdcard/ADB_Pilot` (images, videos, install, apps, store, presets).

## Mises à jour
L'appli vérifie au démarrage la dernière release de ce dépôt et propose de télécharger puis d'installer l'APK par-dessus.
Publier chaque version avec un tag `vX.Y.Z` (supérieur à `versionName`) et l'APK en pièce jointe (`ADB-Pilot-X.Y.Z-android.apk`), **toujours signé avec la même clé**.

## Compiler (sans Gradle)
Testé sous Ubuntu 24.04 :
```bash
sudo apt install openjdk-21-jdk-headless aapt dalvik-exchange apksigner zipalign
mkdir -p ../sdk && curl -L -o ../sdk/android-30.jar https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar
./build.sh            # -> build/ADB-Pilot-<version>-android.apk
```
Adapter les chemins `SDK=` et `KS=` en tête de `build.sh`. Le keystore de signature n'est pas dans le dépôt.

`helper/` : petit programme exécuté **sur l'appareil cible** (`app_process`) pour lire noms / icônes / versions et la vraie version d'Android ; compilé en `assets/helper.jar` par `build.sh`.
`tools/mkicons.py` : regénère les icônes depuis les SVG de la version PC (PySide6).

## Licence
GPL-3.0. scrcpy-server : Apache-2.0 (Genymobile). Descriptions UAD-ng : GPL-3.0.
