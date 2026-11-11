package com.rhys.financetracker.data.claude

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rhys.financetracker.domain.claude.ClaudeModel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Whether Ask Claude is set up, and how. The key itself is never in here. */
data class ClaudeSetup(
    val loaded: Boolean = false,
    val hasKey: Boolean = false,
    /** The key's last four characters, to tell keys apart. */
    val keyEnding: String = "",
    val model: ClaudeModel = ClaudeModel.DEFAULT,
)

/**
 * The user's own Anthropic API key, and which Claude model to ask.
 *
 * The key is kept in encrypted storage whose key lives in the Android
 * keystore, as the app PIN is. It is never shown again once saved, never
 * backed up and never put in an export.
 */
@Singleton
class ClaudeSettings @Inject constructor(
    private val context: Context,
) : ClaudeAccess {

    private val _setup = MutableStateFlow(ClaudeSetup())
    val setup: StateFlow<ClaudeSetup> = _setup.asStateFlow()

    /** Touches the keystore, so built on first use, away from the main thread. */
    private val prefs: SharedPreferences by lazy {
        // Storage that can no longer be opened — a phone restored from a
        // backup has a new keystore — is started afresh: the key is asked
        // for again, rather than Ask Claude never opening.
        runCatching { open() }.getOrElse {
            context.deleteSharedPreferences(FILE_NAME)
            open()
        }
    }

    private fun open(): SharedPreferences {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun load() = withContext(Dispatchers.IO) { publish() }

    override fun model(): ClaudeModel = _setup.value.model

    /** The key, for the request only. */
    override suspend fun apiKey(): String? = withContext(Dispatchers.IO) { prefs.getString(KEY_API, null)?.takeIf { it.isNotBlank() } }

    suspend fun saveKey(key: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_API, key.trim()).apply()
        publish()
    }

    suspend fun removeKey() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_API).apply()
        publish()
    }

    suspend fun setModel(model: ClaudeModel) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_MODEL, model.id).apply()
        publish()
    }

    private fun publish() {
        val key = prefs.getString(KEY_API, null).orEmpty()
        _setup.value = ClaudeSetup(
            loaded = true,
            hasKey = key.isNotBlank(),
            keyEnding = key.takeLast(4),
            model = ClaudeModel.byId(prefs.getString(KEY_MODEL, null)),
        )
    }

    private companion object {
        const val FILE_NAME = "claude_prefs"
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
    }
}
