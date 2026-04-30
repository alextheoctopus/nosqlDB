import redis.clients.jedis.JedisPool
import java.security.MessageDigest

class RedisEventReactionCache(
    private val jedisPool: JedisPool,
    private val ttlSeconds: Long,
) {
    fun get(title: String): ReactionsResponse? {
        val values = jedisPool.resource.use { jedis ->
            jedis.hgetAll(cacheKey(title))
        }

        if (values.isEmpty()) {
            return null
        }

        return ReactionsResponse(
            likes = values["likes"]?.toIntOrNull() ?: 0,
            dislikes = values["dislikes"]?.toIntOrNull() ?: 0,
        )
    }

    fun put(title: String, reactions: ReactionsResponse) {
        val key = cacheKey(title)

        jedisPool.resource.use { jedis ->
            jedis.hset(
                key,
                mapOf(
                    "likes" to reactions.likes.toString(),
                    "dislikes" to reactions.dislikes.toString(),
                )
            )
            jedis.expire(key, ttlSeconds)
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