package com.example.DSH_Mobile.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "dsh_settings")

data class ConnectionSettings(
    val host: String = "",
    val cookie: String = "",
    val cookieName: String = "",
    val mode: String = "steer",
    val channel: String = "core",
    val trustInsecure: Boolean = false,
)

/**
 * Persists the host URL and pairing cookie. The cookie value is encrypted at
 * rest with an AES-256/GCM key held in the Android keystore.
 */
class SettingsStore(private val context: Context) {

    suspend fun load(): ConnectionSettings {
        val prefs = context.dataStore.data.first()
        val host = prefs[KEY_HOST] ?: ""
        val cookie = prefs[KEY_COOKIE_ENC]?.let { enc ->
            runCatching { decrypt(enc) }.getOrDefault("")
        } ?: prefs[KEY_COOKIE_PLAIN] ?: ""
        return ConnectionSettings(
            host = host,
            cookie = cookie,
            cookieName = prefs[KEY_COOKIE_NAME] ?: "",
            mode = prefs[KEY_MODE] ?: "steer",
            channel = prefs[KEY_CHANNEL] ?: "core",
            trustInsecure = prefs[KEY_TRUST] == true,
        )
    }

    suspend fun saveHostCookie(host: String, cookie: String, cookieName: String, channel: String, trustInsecure: Boolean) {
        context.dataStore.edit { p ->
            p[KEY_HOST] = host
            if (cookie.isEmpty()) p.remove(KEY_COOKIE_ENC) else p[KEY_COOKIE_ENC] = encrypt(cookie)
            p[KEY_COOKIE_NAME] = cookieName
            p[KEY_CHANNEL] = channel
            p[KEY_TRUST] = trustInsecure
        }
    }

    suspend fun saveMode(mode: String) {
        context.dataStore.edit { it[KEY_MODE] = mode }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("dsh_master", null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                "dsh_master",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        iv.copyInto(out)
        ct.copyInto(out, iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        if (enc.isEmpty()) return ""
        val all = Base64.decode(enc, Base64.NO_WRAP)
        if (all.size <= 12) return ""
        val iv = all.copyOfRange(0, 12)
        val ct = all.copyOfRange(12, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        private val KEY_HOST = stringPreferencesKey("host")
        private val KEY_COOKIE_ENC = stringPreferencesKey("cookie_enc")
        @Deprecated("legacy plaintext key, read-only migration")
        private val KEY_COOKIE_PLAIN = stringPreferencesKey("cookie")
        private val KEY_COOKIE_NAME = stringPreferencesKey("cookie_name")
        private val KEY_MODE = stringPreferencesKey("send_mode")
        private val KEY_CHANNEL = stringPreferencesKey("channel")
        private val KEY_TRUST = booleanPreferencesKey("trust_insecure")
    }
}
