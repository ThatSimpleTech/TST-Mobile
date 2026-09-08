package com.thatsimpletech.assist.secrets

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import com.thatsimpletech.assist.core.secrets.SecretStore
import com.thatsimpletech.assist.core.secrets.SecretStoreLockedException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * "Key never touches disk" on Android means: the plaintext never does. An AES-256-GCM key
 * lives in the Android Keystore (hardware-backed where the phone has it) and wraps the secret;
 * only ciphertext and IV are stored, in app-private preferences. Keystore lock states map to
 * [SecretStoreLockedException], the same wire code TST Desk's UI already handles.
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    override fun get(account: String): String? {
        val stored = prefs.getString(account, null) ?: return null
        val (ivB64, ctB64) = stored.split(':', limit = 2).let { if (it.size == 2) it else return null }
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: UserNotAuthenticatedException) {
            throw SecretStoreLockedException()
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw SecretStoreLockedException("key invalidated; store the secret again")
        } catch (e: java.security.GeneralSecurityException) {
            // The Keystore key no longer matches this ciphertext (keystore reset, restore to a new
            // device). The secret is unreadable for good: forget it and let the person re-enter it.
            prefs.edit().remove(account).apply()
            null
        }
    }

    override fun set(account: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } catch (e: UserNotAuthenticatedException) {
            throw SecretStoreLockedException()
        }
        val ct = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        prefs.edit().putString(account, value).apply()
    }

    override fun delete(account: String) {
        prefs.edit().remove(account).apply()
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "tst-assist-master"
        private const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
