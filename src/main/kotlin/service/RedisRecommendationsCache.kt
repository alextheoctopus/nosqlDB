import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import redis.clients.jedis.JedisPool

class RedisRecommendationsCache(
    private val jedisPool: JedisPool,
    private val ttlSeconds: Long,
) {
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    fun get(userId: String): RecommendationsResponse? {
        return jedisPool.resource.use { jedis ->
            val raw = jedis.hget(key(userId), "payload") ?: return null
            runCatching {
                json.decodeFromString<RecommendationsResponse>(raw)
            }.getOrNull()
        }
    }

    fun put(userId: String, response: RecommendationsResponse) {
        jedisPool.resource.use { jedis ->
            val redisKey = key(userId)
            jedis.hset(redisKey, "payload", json.encodeToString(response))
            jedis.expire(redisKey, ttlSeconds)
        }
    }

    private fun key(userId: String): String = "user:$userId:recomms"
}