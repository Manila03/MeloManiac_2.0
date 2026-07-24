package com.melomaniac.app.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.melomaniac.app.util.AppLog
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Spotify Authorization Code + PKCE.
 * Needed for playlists after Spotify's Feb 2026 Web API restrictions
 * (Client Credentials can no longer read playlist contents).
 *
 * Dashboard: add Redirect URI exactly:
 *   melomaniac://spotify-callback
 */
class SpotifyAuth(
    private val context: Context,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val prefs by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun isConnected(): Boolean =
        !prefs.getString(KEY_REFRESH, null).isNullOrBlank() ||
            !prefs.getString(KEY_ACCESS, null).isNullOrBlank()

    fun disconnect() {
        prefs.edit().clear().apply()
        AppLog.i(TAG, "disconnected")
    }

    fun beginLogin(clientId: String): Intent {
        if (clientId.isBlank()) error("Configurá Spotify Client ID en Ajustes")
        val verifier = randomUrlSafe(64)
        val challenge = sha256Base64Url(verifier)
        val state = randomUrlSafe(16)
        prefs.edit()
            .putString(KEY_VERIFIER, verifier)
            .putString(KEY_STATE, state)
            .apply()

        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("state", state)
            .appendQueryParameter(
                "scope",
                listOf(
                    "playlist-read-private",
                    "playlist-read-collaborative",
                    "user-library-read",
                ).joinToString(" "),
            )
            .build()
        AppLog.i(TAG, "opening authorize")
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    suspend fun handleRedirect(uri: Uri, clientId: String, clientSecret: String) {
        if (uri.scheme != "melomaniac" || uri.host != "spotify-callback") return
        val err = uri.getQueryParameter("error")
        if (!err.isNullOrBlank()) error("Spotify login: $err")
        val code = uri.getQueryParameter("code") ?: error("Spotify login sin code")
        val state = uri.getQueryParameter("state")
        val expected = prefs.getString(KEY_STATE, null)
        if (expected != null && state != expected) error("Spotify login: state inválido")
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: error("Spotify login: falta verifier")
        exchangeCode(code, verifier, clientId, clientSecret)
    }

    /** User access token, refreshed if needed. Null if not connected. */
    fun userAccessToken(clientId: String, clientSecret: String): String? {
        if (!isConnected()) return null
        val expires = prefs.getLong(KEY_EXPIRES, 0L)
        val access = prefs.getString(KEY_ACCESS, null)
        if (!access.isNullOrBlank() && expires > System.currentTimeMillis() + 30_000) return access
        val refresh = prefs.getString(KEY_REFRESH, null) ?: return access
        return refreshAccess(refresh, clientId, clientSecret)
    }

    private fun exchangeCode(code: String, verifier: String, clientId: String, clientSecret: String) {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .add("client_id", clientId)
            .add("code_verifier", verifier)
            .apply {
                if (clientSecret.isNotBlank()) add("client_secret", clientSecret)
            }
            .build()
        val req = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                AppLog.e(TAG, "token exchange ${resp.code}: ${raw.take(300)}")
                error("No se pudo completar login Spotify (${resp.code})")
            }
            saveTokens(JSONObject(raw))
            AppLog.i(TAG, "login ok")
        }
    }

    private fun refreshAccess(refresh: String, clientId: String, clientSecret: String): String {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", clientId)
            .apply {
                if (clientSecret.isNotBlank()) add("client_secret", clientSecret)
            }
            .build()
        val req = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(body)
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                AppLog.e(TAG, "refresh ${resp.code}: ${raw.take(300)}")
                disconnect()
                error("Sesión Spotify expirada. Volvé a conectar en Ajustes.")
            }
            val json = JSONObject(raw)
            // refresh_token may be omitted on refresh
            if (!json.has("refresh_token")) json.put("refresh_token", refresh)
            saveTokens(json)
            return json.getString("access_token")
        }
    }

    private fun saveTokens(json: JSONObject) {
        val access = json.getString("access_token")
        val refresh = json.optString("refresh_token").ifBlank {
            prefs.getString(KEY_REFRESH, "").orEmpty()
        }
        val expiresIn = json.optLong("expires_in", 3600)
        prefs.edit()
            .putString(KEY_ACCESS, access)
            .putString(KEY_REFRESH, refresh)
            .putLong(KEY_EXPIRES, System.currentTimeMillis() + expiresIn * 1000)
            .remove(KEY_VERIFIER)
            .remove(KEY_STATE)
            .apply()
    }

    private fun randomUrlSafe(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return Base64.encodeToString(buf, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun sha256Base64Url(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        const val REDIRECT_URI = "melomaniac://spotify-callback"
        private const val TAG = "SpotifyAuth"
        private const val PREFS = "spotify_oauth"
        private const val KEY_ACCESS = "access"
        private const val KEY_REFRESH = "refresh"
        private const val KEY_EXPIRES = "expires"
        private const val KEY_VERIFIER = "verifier"
        private const val KEY_STATE = "state"
    }
}
