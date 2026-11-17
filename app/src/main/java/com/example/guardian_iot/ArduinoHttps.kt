package com.example.guardian_iot

import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/** Creates only HTTPS connections whose server certificate matches an out-of-band enrolled pin. */
internal object ArduinoHttps {
    fun normalizeFingerprint(value: String): String {
        val withoutLabel = value.trim().replace(Regex("(?i)^sha-?256\\s*[=:]\\s*"), "")
        val compact = withoutLabel.filterNot { it.isWhitespace() || it == ':' }
        require(compact.length == FINGERPRINT_HEX_LENGTH && compact.all { it in '0'..'9' || it.uppercaseChar() in 'A'..'F' }) {
            "Escribe la huella SHA-256 completa del certificado HTTPS (64 dígitos hexadecimales)."
        }
        return compact.uppercase(Locale.ROOT)
    }

    fun openPinnedConnection(rawUrl: String, fingerprint: String): HttpsURLConnection {
        val url = URL(rawUrl.trim())
        require(url.protocol.equals("https", ignoreCase = true) && url.host.isNotBlank() && url.userInfo == null &&
            url.query == null && url.ref == null && (url.port == -1 || url.port in 1..65_535) &&
            (url.path.isBlank() || url.path == "/" || url.path.startsWith("/api/v1/")) && !url.path.contains("..")) {
            "La dirección del UNO Q debe ser HTTPS; HTTP sin cifrado está deshabilitado."
        }
        val expected = fingerprintBytes(fingerprint)
        val connection = url.openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("El UNO Q debe ofrecer HTTPS.")
        connection.sslSocketFactory = pinnedSocketFactory(expected)
        connection.instanceFollowRedirects = false
        return connection
    }

    internal fun certificateFingerprint(encodedCertificate: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(encodedCertificate).joinToString("") { "%02X".format(Locale.ROOT, it) }

    internal fun matchesCertificate(encodedCertificate: ByteArray, fingerprint: String): Boolean =
        MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(encodedCertificate), fingerprintBytes(fingerprint))

    private fun fingerprintBytes(value: String): ByteArray = normalizeFingerprint(value)
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun pinnedSocketFactory(expected: ByteArray): SSLSocketFactory {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("El servidor no presentó un certificado.")
                try {
                    leaf.checkValidity()
                } catch (error: Exception) {
                    throw CertificateException("El certificado HTTPS del UNO Q está vencido o aún no es válido.", error)
                }
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                if (!MessageDigest.isEqual(actual, expected)) {
                    throw CertificateException("La huella HTTPS del UNO Q no coincide con la que guardaste.")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory
    }

    private const val FINGERPRINT_HEX_LENGTH = 64
}
