import java.security.SecureRandom
val secureRandom = SecureRandom()
fun generateSid(): String {
    val bytes = ByteArray(16)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

