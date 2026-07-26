package com.melomaniac.app.telegram

import com.melomaniac.app.data.AppSettings
import com.melomaniac.app.data.SettingsRepository
import com.melomaniac.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Validates Telegram bot token + channel access using current settings.
 */
class TelegramConfig(private val settingsRepo: SettingsRepository) {

    suspend fun current(): AppSettings = settingsRepo.get()

    suspend fun isConfigured(): Boolean = settingsRepo.get().isTelegramConfigured

    suspend fun clientOrNull(): TelegramBotClient? {
        val s = settingsRepo.get()
        if (!s.isTelegramConfigured) return null
        return TelegramBotClient.fromToken(s.telegramBotToken)
    }

    suspend fun requireClient(): Pair<TelegramBotClient, String> {
        val s = settingsRepo.get()
        if (!s.isTelegramConfigured) {
            error("Configurá el bot token y el channel ID de Telegram en Ajustes")
        }
        return TelegramBotClient.fromToken(s.telegramBotToken) to s.telegramChannelId.trim()
    }

    /**
     * Probes getMe + a short message to the channel.
     * @return human-readable success message
     */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val (client, chatId) = requireClient()
        val me = client.getMe()
        val label = me.username?.let { "@$it" } ?: me.firstName ?: "bot"
        AppLog.i(TAG, "getMe ok: $label")
        client.sendMessage(chatId, "MeloManiac online OK ✓")
        "Conectado como $label → canal $chatId"
    }

    companion object {
        private const val TAG = "TelegramConfig"
    }
}
