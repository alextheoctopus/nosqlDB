import io.ktor.http.HttpStatusCode
import redis.clients.jedis.JedisPool
import java.time.Instant

class RedisSessionService(
    private val jedisPool: JedisPool,
    private val ttlSeconds: Long,
) {
    fun exists(sid: String): Boolean {
        return jedisPool.resource.use { jedis ->
            jedis.exists(sessionKey(sid))
        }
    }

    fun createOrRefreshSession(requestSid: String?): SessionResult {
        if (requestSid == null) {
            val sid = createNewAnonymousSession()
            return SessionResult(sid, HttpStatusCode.Created)
        }

        return if (refreshIfExists(requestSid)) {
            SessionResult(requestSid, HttpStatusCode.OK)
        } else {
            val sid = createNewAnonymousSession()
            SessionResult(sid, HttpStatusCode.Created)
        }
    }

    fun refreshIfExists(sid: String): Boolean {
        return jedisPool.resource.use { jedis ->
            val key = sessionKey(sid)
            if (jedis.exists(key)) {
                val now = Instant.now().toString()
                jedis.hset(key, mapOf("updated_at" to now))
                jedis.expire(key, ttlSeconds)
                true
            } else {
                false
            }
        }
    }

    fun createNewAnonymousSession(): String = createSessionWithRetry(null)

    fun createBoundSession(userId: String): String = createSessionWithRetry(userId)

    fun bindUserToSession(sid: String, userId: String) {
        jedisPool.resource.use { jedis ->
            val key = sessionKey(sid)
            val now = Instant.now().toString()
            jedis.hset(key, mapOf("user_id" to userId, "updated_at" to now))
            jedis.expire(key, ttlSeconds)
        }
    }

    fun getUserId(sid: String): String? {
        return jedisPool.resource.use { jedis ->
            jedis.hget(sessionKey(sid), "user_id")
        }
    }

    fun deleteSession(sid: String) {
        jedisPool.resource.use { jedis ->
            jedis.del(sessionKey(sid))
        }
    }

    private fun createSessionWithRetry(userId: String?, maxAttempts: Int = 5): String {
        repeat(maxAttempts) {
            val sid = generateSid()
            if (createSessionAtomically(sid, userId)) {
                return sid
            }
        }
        error("Failed to create unique session after $maxAttempts attempts")
    }

    private fun createSessionAtomically(sid: String, userId: String?): Boolean {
        val script = """
        local key = KEYS[1]
        if redis.call('EXISTS', key) == 1 then
            return 0
        end
        redis.call('HSET', key, 'created_at', ARGV[1], 'updated_at', ARGV[1])
        if ARGV[3] ~= '' then
            redis.call('HSET', key, 'user_id', ARGV[3])
        end
        redis.call('EXPIRE', key, ARGV[2])
        return 1
    """.trimIndent()

        val now = Instant.now().toString()
        val result = jedisPool.resource.use { jedis ->
            jedis.eval(script, listOf(sessionKey(sid)), listOf(now, ttlSeconds.toString(), userId ?: ""))
        }

        return when (result) {
            is Long -> result == 1L
            is Int -> result == 1
            else -> false
        }
    }

    private fun sessionKey(sid: String): String = "sid:$sid"
}