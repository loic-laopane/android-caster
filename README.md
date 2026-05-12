# Android Caster

Application Android permettant de diffuser le contenu de son téléphone ou des médias en streaming vers des périphériques compatibles (Chromecast, Smart TV DLNA/UPnP).

## Fonctionnalités

- **Découverte automatique** des périphériques Chromecast (via mDNS) et Smart TV (via DLNA/SSDP)
- **Diffusion de médias** : envoi d'une URL vidéo/audio vers n'importe quel périphérique DLNA ou Chromecast
- **Diffusion d'écran** : capture et envoi de l'écran complet vers un récepteur compatible
- **Compatibilité** : Chromecast, Android TV, Smart TV Samsung/LG/Sony/Philips, toute TV compatible DLNA

## Compatibilité YouTube / Netflix / Prime Video

- **Services avec DRM (Netflix, Prime Video)** : utilisez le bouton Cast intégré dans leurs applications officielles. La diffusion d'écran (onglet Écran) fonctionne aussi.
- **YouTube** : collez l'URL directe d'un fichier vidéo, ou utilisez le bouton Cast de l'app YouTube.

## Prérequis de build

- Java 11+
- Kotlin 1.3+ (`kotlinc`)
- Android SDK API 23 (`android.jar`)
- Android build-tools 29 (`aapt2`, `dx`, `apksigner`, `zipalign`)

Sur Ubuntu/Debian :
```bash
sudo apt-get install -y kotlin android-sdk android-sdk-build-tools libandroid-23-java dalvik-exchange
```

## Build

```bash
chmod +x build.sh
./build.sh
```

Le fichier `android-caster.apk` est créé à la racine du projet.

## Installation

```bash
adb install android-caster.apk
```

Ou transférez le fichier APK sur votre téléphone et ouvrez-le (activez "Sources inconnues" dans les paramètres).

## Architecture

```
app/src/main/java/com/caster/app/
├── CasterApplication.kt          # Application entry point
├── MainActivity.kt               # UI principale (3 onglets)
├── adapter/DeviceAdapter.kt      # Affichage des périphériques
├── cast/
│   ├── ChromecastCaster.kt       # Protocole Chromecast (CASTV2 over TLS)
│   └── DlnaCaster.kt             # Diffusion DLNA/UPnP (SOAP)
├── discovery/
│   └── DeviceDiscovery.kt        # mDNS + SSDP discovery
├── model/CastDevice.kt           # Modèle de données
└── screen/
    └── ScreenMirrorService.kt    # Service de capture d'écran (MediaProjection)
```

## Permissions requises

- `INTERNET` — connexion aux périphériques
- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` — découverte mDNS/SSDP
- `FOREGROUND_SERVICE` — service de diffusion d'écran
