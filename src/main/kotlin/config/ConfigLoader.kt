import com.datastax.oss.driver.api.core.DefaultConsistencyLevel

fun loadConfig(): AppConfig = AppConfig(
    port = System.getenv("APP_PORT").trim().toInt(),
    host = System.getenv("APP_HOST").trim().trim('"'),
    sessionTtlSeconds = System.getenv("APP_USER_SESSION_TTL").trim().substringBefore("#").trim().toLong(),
    likeTtlSeconds = System.getenv("APP_LIKE_TTL").trim().substringBefore("#").trim().toLong(),
    eventReviewsTtlSeconds = System.getenv("APP_EVENT_REVIEWS_TTL").trim().substringBefore("#").trim().toLong(),

    redisHost = System.getenv("REDIS_HOST").trim(),
    redisPort = System.getenv("REDIS_PORT").trim().toInt(),
    redisPassword = System.getenv("REDIS_PASSWORD")?.trim()?.ifBlank { null },
    redisDb = System.getenv("REDIS_DB").trim().toInt(),

    mongoDatabase = System.getenv("MONGODB_DATABASE").trim().trim('"'),
    mongoUser = System.getenv("MONGODB_USER")?.trim()?.ifBlank { null },
    mongoPassword = System.getenv("MONGODB_PASSWORD")?.trim()?.ifBlank { null },
    mongoHost = System.getenv("MONGODB_HOST").trim(),
    mongoPort = System.getenv("MONGODB_PORT").trim().toInt(),

    cassandraHosts = System.getenv("CASSANDRA_HOSTS")
        .trim()
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() },
    cassandraPort = System.getenv("CASSANDRA_PORT").trim().toInt(),
    cassandraUsername = System.getenv("CASSANDRA_USERNAME")?.trim()?.ifBlank { null },
    cassandraPassword = System.getenv("CASSANDRA_PASSWORD")?.trim()?.ifBlank { null },
    cassandraKeyspace = System.getenv("CASSANDRA_KEYSPACE").trim().trim('"'),
    cassandraConsistency = DefaultConsistencyLevel.valueOf(
        System.getenv("CASSANDRA_CONSISTENCY").trim().trim('"')
    ),

    neo4jUrl = System.getenv("NEO4J_URL").trim(),
    neo4jUsername = System.getenv("NEO4J_USERNAME").trim(),
    neo4jPassword = System.getenv("NEO4J_PASSWORD").trim(),
    recommendationsTtlSeconds = System.getenv("APP_RECOMMENDATIONS_TTL")
        .trim()
        .substringBefore("#")
        .trim()
        .toLong(),
)
