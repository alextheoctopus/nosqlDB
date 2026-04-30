import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import redis.clients.jedis.JedisPool
import java.security.MessageDigest

class RedisEventReactionCache(
    private val jedisPool: JedisPool,
    private val ttlSeconds: Long,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun get(title: String): ReactionsResponse? {
        val value = jedisPool.resource.use { jedis ->
            jedis.get(cacheKey(title))
        } ?: return null

        return runCatching {
            json.decodeFromString<ReactionsResponse>(value)
        }.getOrNull()
    }

    fun put(title: String, reactions: ReactionsResponse) {
        jedisPool.resource.use { jedis ->
            jedis.setex(cacheKey(title), ttlSeconds, json.encodeToString(reactions))
        }
    }

    fun invalidate(title: String) {
        jedisPool.resource.use { jedis ->
            jedis.del(cacheKey(title))
        }
    }

    private fun cacheKey(title: String): String =
        "event:${md5(title)}:reactions"

    private fun md5(value: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}