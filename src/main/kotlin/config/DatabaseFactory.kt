import com.mongodb.kotlin.client.MongoClient
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig

fun createJedisPool(config: AppConfig): JedisPool {
    val poolConfig = JedisPoolConfig().apply {
        maxTotal = 8
        maxIdle = 8
        minIdle = 0
    }

    return if (config.redisPassword != null) {
        JedisPool(poolConfig, config.redisHost, config.redisPort, 2_000, config.redisPassword, config.redisDb)
    } else {
        JedisPool(poolConfig, config.redisHost, config.redisPort, 2_000, null, config.redisDb)
    }
}


fun createMongoClient(config: AppConfig): MongoClient {
    val connectionString =
        if (!config.mongoUser.isNullOrBlank() && !config.mongoPassword.isNullOrBlank()) {
            "mongodb://${config.mongoUser}:${config.mongoPassword}@${config.mongoHost}:${config.mongoPort}/${config.mongoDatabase}?authSource=${config.mongoDatabase}"
        } else {
            "mongodb://${config.mongoHost}:${config.mongoPort}/${config.mongoDatabase}"
        }

    return MongoClient.create(connectionString)
}
