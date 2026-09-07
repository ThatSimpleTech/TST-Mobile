package com.thatsimpletech.assist.core.secrets

/**
 * Where the provider key lives. On the phone the backing is an Android Keystore key wrapping
 * a ciphertext blob in app-private storage, so the plaintext never touches disk. Accounts are
 * `tst-<credentialId>`, mirroring TST Desk's keychain contract.
 */
interface SecretStore {
    @Throws(SecretStoreLockedException::class)
    fun get(account: String): String?

    @Throws(SecretStoreLockedException::class)
    fun set(account: String, secret: String)

    fun delete(account: String)

    companion object {
        const val SERVICE = "com.thatsimpletech.assist"
        fun account(credentialId: String) = "tst-$credentialId"
    }
}

/** Maps to the wire error code `keychain_locked`, the same one TST Desk's UI already handles. */
class SecretStoreLockedException(message: String = "secret store is locked") : Exception(message) {
    val code: String get() = "keychain_locked"
}

/** For tests and for the JVM harness. Never used on the phone. */
class InMemorySecretStore : SecretStore {
    private val map = HashMap<String, String>()
    var locked: Boolean = false

    override fun get(account: String): String? {
        if (locked) throw SecretStoreLockedException()
        return map[account]
    }

    override fun set(account: String, secret: String) {
        if (locked) throw SecretStoreLockedException()
        map[account] = secret
    }

    override fun delete(account: String) {
        map.remove(account)
    }
}
