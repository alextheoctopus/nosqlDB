import com.datastax.oss.driver.api.core.DefaultConsistencyLevel

data class AppConfig(
    val port: Int,
    val host: String,
    val sessionTtlSeconds: Long,
    val likeTtlSeconds: Long,
    val eventReviewsTtlSeconds: Long,

    val redisHost: String,
    val redisPort: Int,
    val redisPassword: String?,
    val redisDb: Int,

    val mongoDatabase: String,
    val mongoUser: String?,
    val mongoPassword: String?,
    val mongoHost: String,
    val mongoPort: Int,

    val cassandraHosts: List<String>,
    val cassandraPort: Int,
    val cassandraUsername: String?,
    val cassandraPassword: String?,
    val cassandraKeyspace: String,
    val cassandraConsistency: DefaultConsistencyLevel,
)

