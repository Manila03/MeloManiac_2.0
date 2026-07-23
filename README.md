# MeloManiac 2.0 (Kotlin nativo)

App Android personal para buscar, descargar y reproducir música offline en FLAC.

## Stack

- Kotlin + Jetpack Compose
- Room (SQLite)
- Media3 (reproductor + notificación)
- yt-dlp + ffmpeg vía [youtubedl-android](https://github.com/yausername/youtubedl-android) (binarios nativos Android)

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

## Actualizaciones (GitHub Releases)

El repo público publica APKs en [Releases](https://github.com/Manila03/MeloManiac_2.0/releases).

En el teléfono: **Ajustes → Buscar actualizaciones**. Si hay un release más nuevo, descargá e instalá el APK (Android puede pedirte permiso para instalar desde esta app).

### Una sola vez: secret de firma en GitHub

CI firma con el mismo `app/debug.keystore` local (no se sube al repo). Exportalo y cargalo como secret:

```powershell
.\scripts\export-keystore-secret.ps1
```

En GitHub: **Settings → Secrets and variables → Actions → New repository secret**

- Name: `RELEASE_KEYSTORE_BASE64`
- Value: el base64 del portapapeles

Sin este secret, el workflow de release falla a propósito (así no publicás APKs con otra firma).

### Publicar una versión nueva

1. Subí el `versionName` / `versionCode` en `app/build.gradle.kts` (ej. `2.0.2` / `4`).
2. Commit + push a `master`.
3. Creá y pusheá un tag que coincida con la versión:

```powershell
git tag v2.0.2
git push origin v2.0.2
```

GitHub Actions construye el APK y lo adjunta al Release como `MeloManiac.apk`.

**Importante:** si regenerás el keystore, no se podrá actualizar encima de instalaciones anteriores.

## Uso

1. Ajustes → Spotify credentials
2. Ajustes → Descargar binarios (yt-dlp/ffmpeg)
3. Buscar → pegá URL YouTube/Spotify o texto
4. Reproducí desde Biblioteca

## Notas

- No hay backend ni login.
- YouTube no entrega FLAC nativo: se convierte con ffmpeg.
- El código Expo/React Native anterior quedó en `_legacy_rn/`.
