data class AppConfig(
    val port: Int,
    val host: String,
    val sessionTtlSeconds: Long,
    val redisHost: String,
    val redisPort: Int,
    val redisPassword: String?,
    val redisDb: Int,
    val mongoDatabase: String,
    val mongoUser: String?,
    val mongoPassword: String?,
    val mongoHost: String,
    val mongoPort: Int,
)