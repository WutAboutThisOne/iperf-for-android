package local.net.iperf.core

/** iperf3 server login (iperf3 --username / --rsa-public-key-path, password via IPERF3_PASSWORD). */
data class IperfAuth(val username: String, val password: String, val publicKeyPem: String) {
    /** Never let the password or key end up in logs or crash reports. */
    override fun toString(): String = "IperfAuth(username=$username, password=***, key=${publicKeyPem.length} chars)"

    /** Extra iperf3 args. The password is deliberately NOT here: it goes through the IPERF3_PASSWORD env var. */
    fun args(publicKeyPath: String): List<String> = listOf("--username", username, "--rsa-public-key-path", publicKeyPath)

    companion object {
        const val PASSWORD_ENV = "IPERF3_PASSWORD"
    }
}

object PublicKeyPem {
    private const val BEGIN = "-----BEGIN PUBLIC KEY-----"
    private const val END = "-----END PUBLIC KEY-----"
    private const val BEGIN_RSA = "-----BEGIN RSA PUBLIC KEY-----"

    sealed interface Check {
        data class Ok(val pem: String) : Check
        data class Invalid(val message: String) : Check
    }

    /** iperf3 reads the key with PEM_read_bio_PUBKEY, so it must be an X.509 SubjectPublicKeyInfo PEM. */
    fun check(raw: String): Check {
        val t = raw.trim().replace("\r\n", "\n")
        if (t.isEmpty()) return Check.Invalid("Paste the server's public key")
        if (BEGIN_RSA in t) return Check.Invalid(
            "That's a PKCS#1 \"RSA PUBLIC KEY\". iperf3 needs a \"PUBLIC KEY\" PEM — export it with: openssl rsa -in private.pem -pubout"
        )
        val start = t.indexOf(BEGIN); val end = t.indexOf(END)
        if (start < 0 || end < start) return Check.Invalid("Not a PEM public key (expected $BEGIN … $END)")
        val body = t.substring(start + BEGIN.length, end).filterNot { it.isWhitespace() }
        if (body.length < 64 || !body.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
            return Check.Invalid("The key between the BEGIN/END lines looks damaged")
        }
        return Check.Ok(BEGIN + "\n" + body.chunked(64).joinToString("\n") + "\n" + END + "\n")
    }
}
