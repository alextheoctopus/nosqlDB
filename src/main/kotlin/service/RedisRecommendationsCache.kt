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
            val raw = jedis.hget(key(userId), "events") ?: return null
            runCatching {
                RecommendationsResponse(
                    events = json.decodeFromString(raw)
                )
            }.getOrNull()
        }
    }

    fun put(userId: String, response: RecommendationsResponse) {
        jedisPool.resource.use { jedis ->
            val redisKey = key(userId)
            jedis.hset(redisKey, "events", json.encodeToString(response.events))
            jedis.expire(redisKey, ttlSeconds)
        }
    }

    private fun key(userId: String): String = "user:$userId:recomms"
}