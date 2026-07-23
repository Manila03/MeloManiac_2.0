# MeloManiac 2.0 (Kotlin nativo)

App Android personal para buscar, descargar y reproducir música offline en FLAC.

## Stack

- Kotlin + Jetpack Compose
- Room (SQLite)
- Media3 (reproductor + notificación)
- yt-dlp + ffmpeg en el dispositivo

## Requisitos

- Android Studio / SDK
- Teléfono arm64
- Spotify Client ID/Secret (opcional, para links de Spotify)

## Build APK (un comando)

```powershell
.\scripts\build-apk.ps1
```

Salida: `dist\MeloManiac.apk`

### Instalar

```powershell
adb uninstall com.melomaniac.app
adb install -r dist\MeloManiac.apk
```

O copiá el APK al teléfono.

## Uso

1. Ajustes → Spotify credentials
2. Ajustes → Descargar binarios (yt-dlp/ffmpeg)
3. Buscar → pegá URL YouTube/Spotify o texto
4. Reproducí desde Biblioteca

## Notas

- No hay backend ni login.
- YouTube no entrega FLAC nativo: se convierte con ffmpeg.
- El código Expo/React Native anterior quedó en `_legacy_rn/`.
