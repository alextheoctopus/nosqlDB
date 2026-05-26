fun loadConfig(): AppConfig = AppConfig(
    port = System.getenv("APP_PORT").trim().toInt(),
    host = System.getenv("APP_HOST").trim().trim('"'),
    sessionTtlSeconds = System.getenv("APP_USER_SESSION_TTL").trim().substringBefore("#").trim().toLong(),
    redisHost = System.getenv("REDIS_HOST").trim(),
    redisPort = System.getenv("REDIS_PORT").trim().toInt(),
    redisPassword = System.getenv("REDIS_PASSWORD")?.trim()?.ifBlank { null },
    redisDb = System.getenv("REDIS_DB").trim().toInt(),

    mongoDatabase = System.getenv("MONGODB_DATABASE").trim().trim('"'),
    mongoUser = System.getenv("MONGODB_USER").trim(),
    mongoPassword = System.getenv("MONGODB_PASSWORD").trim(),
    mongoHost = System.getenv("MONGODB_HOST").trim(),
    mongoPort = System.getenv("MONGODB_PORT").trim().toInt(),
)
