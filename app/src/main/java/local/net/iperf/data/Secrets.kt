package local.net.iperf.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.net.iperf.core.IperfAuth
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-server logins. Kept only in [SecretStore] (encrypted), never in servers.json, history or exports. */
data class ServerSecrets(
    val webUser: String = "",
    val webPassword: String = "",
    val iperfUser: String = "",
    val iperfPassword: String = "",
    val iperfPublicKey: String = "",
    /** Manager API login; blank = reuse the webpage login. */
    val apiUser: String = "",
    val apiPassword: String = "",
) {
    val hasWebLogin: Boolean get() = webUser.isNotEmpty() || webPassword.isNotEmpty()
    val hasIperfAuth: Boolean get() = iperfUser.isNotEmpty() && iperfPassword.isNotEmpty() && iperfPublicKey.isNotBlank()
    val hasSeparateApiLogin: Boolean get() = apiUser.isNotEmpty() || apiPassword.isNotEmpty()
    val isEmpty: Boolean get() = this == ServerSecrets()

    /** Credentials for the manager API: the separate API login if set, otherwise the webpage login. */
    val managerLogin: Pair<String, String>? get() = when {
        hasSeparateApiLogin -> apiUser to apiPassword
        hasWebLogin -> webUser to webPassword
        else -> null
    }

    val iperfAuth: IperfAuth? get() = if (hasIperfAuth) IperfAuth(iperfUser, iperfPassword, iperfPublicKey) else null

    override fun toString() = "ServerSecrets(web=${hasWebLogin}, iperf=${hasIperfAuth}, api=${hasSeparateApiLogin})"

    fun toJson(): JSONObject = JSONObject().put("web_user", webUser).put("web_password", webPassword)
        .put("iperf_user", iperfUser).put("iperf_password", iperfPassword).put("iperf_public_key", iperfPublicKey)
        .put("api_user", apiUser).put("api_password", apiPassword)

    companion object {
        fun fromJson(o: JSONObject) = ServerSecrets(
            o.optString("web_user"), o.optString("web_password"),
            o.optString("iperf_user"), o.optString("iperf_password"), o.optString("iperf_public_key"),
            o.optString("api_user"), o.optString("api_password"),
        )
    }
}

/** Authenticated encryption for secrets at rest. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

/** AES-256-GCM with a non-exportable key in the Android Keystore. Blob = 12-byte IV ‖ ciphertext+tag. */
class KeystoreCipher(private val alias: String = "iperf_local_secrets_v1") : SecretCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

/**
 * Encrypted per-server secrets: a JSON map of server id → base64(AES-GCM blob). Lives in noBackupFilesDir so it's
 * excluded from cloud backup and device-to-device transfer (the Keystore key couldn't move anyway).
 */
class SecretStore(private val file: File, private val cipher: SecretCipher) {
    private val _items = MutableStateFlow(load())
    val items: StateFlow<Map<String, ServerSecrets>> = _items.asStateFlow()

    private fun load(): Map<String, ServerSecrets> {
        if (!file.exists()) return emptyMap()
        val o = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().mapNotNull { id ->
            runCatching {
                val blob = java.util.Base64.getDecoder().decode(o.getString(id))
                id to ServerSecrets.fromJson(JSONObject(String(cipher.decrypt(blob), Charsets.UTF_8)))
            }.getOrNull() // undecryptable (e.g. key lost after restore): drop rather than crash
        }.toMap()
    }

    fun get(id: String?): ServerSecrets = id?.let { _items.value[it] } ?: ServerSecrets()

    @Synchronized
    fun put(id: String, secrets: ServerSecrets) {
        val next = _items.value.toMutableMap().apply { if (secrets.isEmpty) remove(id) else put(id, secrets) }
        write(next); _items.value = next
    }

    @Synchronized
    fun delete(id: String) = put(id, ServerSecrets())

    private fun write(map: Map<String, ServerSecrets>) {
        file.parentFile?.mkdirs()
        val o = JSONObject()
        map.forEach { (id, s) ->
            o.put(id, java.util.Base64.getEncoder().encodeToString(cipher.encrypt(s.toJson().toString().toByteArray(Charsets.UTF_8))))
        }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
