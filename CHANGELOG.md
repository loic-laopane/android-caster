# Changelog

## v1.0.0

### Ajouté
- Découverte automatique des périphériques Chromecast via mDNS (`_googlecast._tcp.local`)
- Découverte automatique des Smart TV et appareils DLNA/UPnP via SSDP
- Diffusion de médias par URL vers Chromecast (protocole CASTV2 sur TLS port 8009)
- Diffusion de médias par URL vers appareils DLNA (commandes SOAP SetAVTransportURI + Play)
- Diffusion d'écran via l'API MediaProjection (service foreground avec notification)
- Interface à 3 onglets : Périphériques · Média · Écran
- Icônes adaptatives pour toutes les densités d'écran (mdpi → xxhdpi)
- Pipeline de build sans Android Gradle Plugin : `kotlinc` + `dx` + `aapt2` + `apksigner`
- APK signé et aligné, installable via `adb install`

### Compatibilité
- Android 6.0+ (API 23+)
- Kotlin 1.3 / JVM 1.6
