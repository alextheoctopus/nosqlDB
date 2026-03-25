import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.security.SecureRandom
import java.time.Instant

private const val SESSION_COOKIE_NAME = "X-Session-Id"
private val SID_REGEX = Regex("^[a-f0-9]{32}$")
private val secureRandom = SecureRandom()

@Serializable
data class HealthResponse(val status: String)

data class AppConfig(
    val port: Int,
    val host: String,
    val sessionTtlSeconds: Long,
    val redisHost: String,
    val redisPort: Int,
    val redisPassword: String?,
    val redisDb: Int,
)

fun main() {
    val config = loadConfig()
    println("EventHub started on port=${config.port}, host=${config.host}")

    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig = loadConfig()) {
    val jedisPool = createJedisPool(config)
    val sessionService = RedisSessionService(jedisPool, config.sessionTtlSeconds)

    monitor.subscribe(ApplicationStopped) {
        jedisPool.close()
    }


    install(ContentNegotiation) {
        json()
    }

    routing {
        //корневой url
        get("/") {
            call.respondText("It's root url :)")
        }
        //проверка health
        get("/health") {
            extractValidSid(call.request)?.let { sid ->
                setSessionCookie(call, sid, config.sessionTtlSeconds)
            }
            call.respond(HealthResponse("ok"))
        }
        post("/session") {
            val requestSid = extractValidSid(call.request)
            val result = sessionService.createOrRefreshSession(requestSid)
            setSessionCookie(call, result.sid, config.sessionTtlSeconds)
            call.response.status(result.status)
            call.respondBytes(ByteArray(0))
        }
    }
}

private fun loadConfig(): AppConfig = AppConfig(
    port = System.getenv("APP_PORT").trim().toInt(),
    host = System.getenv("APP_HOST").trim().trim('"') ,
    sessionTtlSeconds = System.getenv("APP_USER_SESSION_TTL").trim().substringBefore("#").trim().toLong(),
    redisHost = System.getenv("REDIS_HOST").trim(),
    redisPort = System.getenv("REDIS_PORT").trim().toInt(),
    redisPassword = System.getenv("REDIS_PASSWORD").trim().ifBlank { null },
    redisDb = System.getenv("REDIS_DB").trim().toInt(),
)

private fun createJedisPool(config: AppConfig): JedisPool {
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

private fun extractValidSid(request: ApplicationRequest): String? {
    val sid = request.cookies[SESSION_COOKIE_NAME] ?: return null
    return sid.takeIf { SID_REGEX.matches(it) }
}

private fun setSessionCookie(call: ApplicationCall, sid: String, ttlSeconds: Long) {
    call.response.cookies.append(
        Cookie(
            name = SESSION_COOKIE_NAME,
            value = sid,
            httpOnly = true,
            path = "/",
            maxAge = ttlSeconds.toInt(),
        )
    )
}

private fun generateSid(): String {
    val bytes = ByteArray(16)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private data class SessionResult(val sid: String, val status: HttpStatusCode)

private class RedisSessionService(
    private val jedisPool: JedisPool,
    private val ttlSeconds: Long,
) {
    fun createOrRefreshSession(requestSid: String?): SessionResult {
        if (requestSid == null) {
            val sid = createNewSessionWithRetry()
            return SessionResult(sid, HttpStatusCode.Created)
        }

        val exists = jedisPool.resource.use { jedis ->
            val key = sessionKey(requestSid)
            if (jedis.exists(key)) {
                val now = Instant.now().toString()
                jedis.hset(key, mapOf("updated_at" to now))
                jedis.expire(key, ttlSeconds)
                true
            } else {
                false
            }
        }

        if (exists) {
            return SessionResult(requestSid, HttpStatusCode.OK)
        }

        val sid = createNewSessionWithRetry()
        return SessionResult(sid, HttpStatusCode.Created)
    }
    private fun createNewSessionWithRetry(maxAttempts: Int = 5): String {
        repeat(maxAttempts) {
            val sid = generateSid()
            if (createSessionAtomically(sid)) {
                return sid
            }
        }
        error("Failed to create unique session after $maxAttempts attempts")
    }

    private fun createSessionAtomically(sid: String): Boolean {
        val script = """
        local key = KEYS[1]
        if redis.call('EXISTS', key) == 1 then
            return 0
        end
        redis.call('HSET', key, 'created_at', ARGV[1], 'updated_at', ARGV[1])
        redis.call('EXPIRE', key, ARGV[2])
        return 1
    """.trimIndent()

        val now = Instant.now().toString()

        val result = jedisPool.resource.use { jedis ->
            jedis.eval(script, listOf(sessionKey(sid)), listOf(now, ttlSeconds.toString()))
        }

        return when (result) {
            is Long -> result == 1L
            is Int -> result == 1
            else -> false
        }
    }

    private fun sessionKey(sid: String): String = "sid:$sid"
}
