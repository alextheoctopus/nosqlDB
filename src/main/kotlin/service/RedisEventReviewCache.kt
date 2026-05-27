import redis.clients.jedis.JedisPool
import java.security.MessageDigest

class RedisEventReviewCache(
    private val jedisPool: JedisPool,
    private val ttlSeconds: Long,
) {
    fun get(title: String): ReviewsSummaryResponse? {
        val values = jedisPool.resource.use { jedis ->
            jedis.hgetAll(cacheKey(title))
        }

        if (values.isEmpty()) {
            return null
        }

        return ReviewsSummaryResponse(
            count = values["count"]?.toIntOrNull() ?: 0,
            rating = values["rating"]?.toDoubleOrNull() ?: 0.0,
        )
    }

    fun put(title: String, reviews: ReviewsSummaryResponse) {
        val key = cacheKey(title)

        jedisPool.resource.use { jedis ->
            jedis.hset(
                key,
                mapOf(
                    "count" to reviews.count.toString(),
                    "rating" to reviews.rating.toString(),
                )
            )
            jedis.expire(key, ttlSeconds)
        }
    }

    private fun cacheKey(title: String): String =
        "event:${md5(title)}:reviews"

    private fun md5(value: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}