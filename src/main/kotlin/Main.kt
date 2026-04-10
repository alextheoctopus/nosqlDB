import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.regex
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.kotlin.client.MongoClient
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.Document
import org.bson.conversions.Bson
import org.mindrot.jbcrypt.BCrypt
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

private const val SESSION_COOKIE_NAME = "X-Session-Id"
private val SID_REGEX = Regex("^[a-f0-9]{32}$")
private val secureRandom = SecureRandom()

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class ErrorResponse(val message: String)

@Serializable
data class UserCreateRequest(
    @SerialName("full_name") val fullName: String? = null,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class LoginRequest(
    val username: String? = null,
    val password: String? = null,
)

@Serializable
data class EventCreateRequest(
    val title: String? = null,
    val address: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val description: String? = null,
)

@Serializable
data class CreateEventResponse(val id: String)

@Serializable
data class EventLocationResponse(val address: String)

@Serializable
data class EventResponse(
    val id: String,
    val title: String,
    val description: String,
    val location: EventLocationResponse,
    @SerialName("created_at") val createdAt: String,
    @SerialName("created_by") val createdBy: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String,
)

@Serializable
data class EventsListResponse(
    val events: List<EventResponse>,
    val count: Int,
)

data class AppConfig(
    val port: Int,
    val host: String,
    val sessionTtlSeconds: Long,
    val redisHost: String,
    val redisPort: Int,
    val redisPassword: String?,
    val redisDb: Int,
    val mongoDatabase: String,
    val mongoUser: String?,
    val mongoPassword: String?,
    val mongoHost: String,
    val mongoPort: Int,
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

    val mongoClient = createMongoClient(config)
    val mongoDatabase = mongoClient.getDatabase(config.mongoDatabase)
    val userRepository = MongoUserRepository(mongoDatabase)
    val eventRepository = MongoEventRepository(mongoDatabase)
    userRepository.ensureIndexes()
    eventRepository.ensureIndexes()

    monitor.subscribe(ApplicationStopped) {
        jedisPool.close()
        mongoClient.close()
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
            setSessionCookieIfExists(call, sessionService, config.sessionTtlSeconds)
            call.respond(HealthResponse("ok"))
        }
        post("/session") {
            val requestSid = extractValidSid(call.request)
            val result = sessionService.createOrRefreshSession(requestSid)
            setSessionCookie(call, result.sid, config.sessionTtlSeconds)
            call.response.status(result.status)
            call.respondBytes(ByteArray(0))

        }
        post("/users") {
            val existingSid = extractValidSid(call.request)
            val hasActiveSession = existingSid != null && sessionService.refreshIfExists(existingSid)

            val payload = runCatching { call.receive<UserCreateRequest>() }.getOrNull()
            if (payload == null) {
                if (hasActiveSession) {
                    setSessionCookie(call, existingSid!!, config.sessionTtlSeconds)
                }
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"body\" field"))
                return@post
            }

            val invalidField = validateUserCreateRequest(payload)
            if (invalidField != null) {
                if (hasActiveSession) {
                    setSessionCookie(call, existingSid!!, config.sessionTtlSeconds)
                }
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"$invalidField\" field"))
                return@post
            }

            val passwordHash = BCrypt.hashpw(payload.password!!, BCrypt.gensalt())
            val createdUserId = userRepository.createUser(payload.fullName!!, payload.username!!, passwordHash)
            if (createdUserId == null) {
                if (hasActiveSession) {
                    setSessionCookie(call, existingSid!!, config.sessionTtlSeconds)
                }
                call.respond(HttpStatusCode.Conflict, ErrorResponse("user already exists"))
                return@post
            }

            if (hasActiveSession) {
                sessionService.deleteSession(existingSid!!)
            }
            val newSid = sessionService.createBoundSession(createdUserId)
            setSessionCookie(call, newSid, config.sessionTtlSeconds)
            call.respond(HttpStatusCode.Created)
        }

        post("/auth/login") {
            val payload = runCatching { call.receive<LoginRequest>() }.getOrNull()
            val requestSid = extractValidSid(call.request)

            if (payload == null) {
                requestSid?.let {
                    if (sessionService.refreshIfExists(it)) {
                        setSessionCookie(call, it, config.sessionTtlSeconds)
                    }
                }
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"body\" field"))
                return@post
            }

            val invalidField = validateLoginRequest(payload)
            if (invalidField != null) {
                requestSid?.let {
                    if (sessionService.refreshIfExists(it)) {
                        setSessionCookie(call, it, config.sessionTtlSeconds)
                    }
                }
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"$invalidField\" field"))
                return@post
            }

            val user = userRepository.findByUsername(payload.username!!)
            if (user == null || !BCrypt.checkpw(payload.password!!, user.passwordHash)) {
                requestSid?.let {
                    if (sessionService.refreshIfExists(it)) {
                        setSessionCookie(call, it, config.sessionTtlSeconds)
                    }
                }
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid credentials"))
                return@post
            }

            val sid = if (requestSid != null && sessionService.refreshIfExists(requestSid)) {
                sessionService.bindUserToSession(requestSid, user.id)
                requestSid
            } else {
                sessionService.createBoundSession(user.id)
            }

            setSessionCookie(call, sid, config.sessionTtlSeconds)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/auth/logout") {
            val sid = extractValidSid(call.request)
            if (sid == null || !sessionService.refreshIfExists(sid)) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            sessionService.deleteSession(sid)
            expireSessionCookie(call)
            call.respond(HttpStatusCode.NoContent)
        }

        post("/events") {
            val sid = extractValidSid(call.request)
            if (sid == null || !sessionService.refreshIfExists(sid)) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }
            setSessionCookie(call, sid, config.sessionTtlSeconds)

            val userId = sessionService.getUserId(sid)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            val payload = runCatching { call.receive<EventCreateRequest>() }.getOrNull()
            if (payload == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"body\" field"))
                return@post
            }

            val invalidField = validateEventCreateRequest(payload)
            if (invalidField != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"$invalidField\" field"))
                return@post
            }

            val eventId = eventRepository.createEvent(
                title = payload.title!!,
                description = payload.description.orEmpty(),
                address = payload.address!!,
                createdBy = userId,
                startedAt = payload.startedAt!!,
                finishedAt = payload.finishedAt!!,
            )

            if (eventId == null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("event already exists"))
                return@post
            }

            call.respond(HttpStatusCode.Created, CreateEventResponse(eventId))
        }

        get("/events") {
            setSessionCookieIfExists(call, sessionService, config.sessionTtlSeconds)

            val title = call.request.queryParameters["title"]
            val limitRaw = call.request.queryParameters["limit"]
            val offsetRaw = call.request.queryParameters["offset"]

            val limit = parseUIntParameter(limitRaw)
            if (limitRaw != null && limit == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"limit\" parameter"))
                return@get
            }

            val offset = parseUIntParameter(offsetRaw)
            if (offsetRaw != null && offset == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"offset\" parameter"))
                return@get
            }

            val events = eventRepository.findEvents(
                title = title,
                limit = limit,
                offset = offset,
            )

            call.respond(EventsListResponse(events, events.size))
        }
    }
}

private fun loadConfig(): AppConfig = AppConfig(
    port = System.getenv("APP_PORT").trim().toInt(),
    host = System.getenv("APP_HOST").trim().trim('"'),
    sessionTtlSeconds = System.getenv("APP_USER_SESSION_TTL").trim().substringBefore("#").trim().toLong(),
    redisHost = System.getenv("REDIS_HOST").trim(),
    redisPort = System.getenv("REDIS_PORT").trim().toInt(),
    redisPassword = System.getenv("REDIS_PASSWORD")?.trim()?.ifBlank { null },
    redisDb = System.getenv("REDIS_DB").trim().toInt(),

    mongoDatabase = System.getenv("MONGODB_DATABASE").trim().trim('"') ,
    mongoUser = System.getenv("MONGODB_USER").trim(),
    mongoPassword = System.getenv("MONGODB_PASSWORD").trim(),
    mongoHost = System.getenv("MONGODB_HOST").trim() ,
    mongoPort = System.getenv("MONGODB_PORT").trim().toInt() ,
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


private fun createMongoClient(config: AppConfig): MongoClient {
    val connectionString =
        if (!config.mongoUser.isNullOrBlank() && !config.mongoPassword.isNullOrBlank()) {
            "mongodb://${config.mongoUser}:${config.mongoPassword}@${config.mongoHost}:${config.mongoPort}/${config.mongoDatabase}?authSource=${config.mongoDatabase}"
        } else {
            "mongodb://${config.mongoHost}:${config.mongoPort}/${config.mongoDatabase}"
        }

    return MongoClient.create(connectionString)
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
private fun setSessionCookieIfExists(
    call: ApplicationCall,
    sessionService: RedisSessionService,
    ttlSeconds: Long,
) {
    extractValidSid(call.request)?.let { sid ->
        if (sessionService.exists(sid)) {
            setSessionCookie(call, sid, ttlSeconds)
        }
    }
}

private fun expireSessionCookie(call: ApplicationCall) {
    call.response.cookies.append(
        Cookie(
            name = SESSION_COOKIE_NAME,
            value = "",
            httpOnly = true,
            path = "/",
            maxAge = 0,
        )
    )
}

private fun generateSid(): String {
    val bytes = ByteArray(16)
    secureRandom.nextBytes(bytes)
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
}

private fun validateUserCreateRequest(request: UserCreateRequest): String? = when {
    request.fullName.isNullOrBlank() -> "full_name"
    request.username.isNullOrBlank() -> "username"
    request.password.isNullOrBlank() -> "password"
    else -> null
}

private fun validateLoginRequest(request: LoginRequest): String? = when {
    request.username.isNullOrBlank() -> "username"
    request.password.isNullOrBlank() -> "password"
    else -> null
}

private fun validateEventCreateRequest(request: EventCreateRequest): String? {
    if (request.title.isNullOrBlank()) return "title"
    if (request.address.isNullOrBlank()) return "address"
    if (request.startedAt.isNullOrBlank()) return "started_at"
    if (request.finishedAt.isNullOrBlank()) return "finished_at"

    val startedAt = parseRfc3339(request.startedAt) ?: return "started_at"
    val finishedAt = parseRfc3339(request.finishedAt) ?: return "finished_at"
    if (finishedAt.isBefore(startedAt)) return "finished_at"

    return null
}

private fun parseRfc3339(value: String): OffsetDateTime? = try {
    OffsetDateTime.parse(value)
} catch (_: DateTimeParseException) {
    null
}

private fun parseUIntParameter(value: String?): Int? {
    if (value == null) return null
    if (value.isBlank()) return null
    return value.toIntOrNull()?.takeIf { it >= 0 }
}

private data class SessionResult(val sid: String, val status: HttpStatusCode)
private data class UserRecord(val id: String, val passwordHash: String)

private class RedisSessionService(
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
            jedis.hget(sessionKey(sid), "user_id")?.ifBlank { null }
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

private class MongoUserRepository(database: MongoDatabase) {
    private val collection: MongoCollection<Document> = database.getCollection("users")

    fun ensureIndexes() {
        collection.createIndex(ascending("username"), IndexOptions().unique(true))
    }

    fun createUser(fullName: String, username: String, passwordHash: String): String? {
        val document = Document()
            .append("full_name", fullName)
            .append("username", username)
            .append("password_hash", passwordHash)

        return try {
            collection.insertOne(document)
            document.getObjectId("_id").toHexString()
        } catch (_: MongoWriteException) {
            null
        }
    }

    fun findByUsername(username: String): UserRecord? {
        val doc = collection.find(eq("username", username)).firstOrNull() ?: return null
        return UserRecord(
            id = doc.getObjectId("_id").toHexString(),
            passwordHash = doc.getString("password_hash")
        )
    }
}

private class MongoEventRepository(database: MongoDatabase) {
    private val collection: MongoCollection<Document> = database.getCollection("events")

    fun ensureIndexes() {
        collection.createIndex(ascending("title"), IndexOptions().unique(true))
        collection.createIndex(ascending("title", "created_by"))
        collection.createIndex(ascending("created_by"))
    }

    fun createEvent(
        title: String,
        description: String,
        address: String,
        createdBy: String,
        startedAt: String,
        finishedAt: String,
    ): String? {
        val document = Document()
            .append("title", title)
            .append("description", description)
            .append("location", Document("address", address))
            .append("created_at", OffsetDateTime.now().toString())
            .append("created_by", createdBy)
            .append("started_at", startedAt)
            .append("finished_at", finishedAt)

        return try {
            collection.insertOne(document)
            document.getObjectId("_id").toHexString()
        } catch (_: MongoWriteException) {
            null
        }
    }


    fun findEvents(title: String?, limit: Int?, offset: Int?): List<EventResponse> {
        val filter: Bson = if (title.isNullOrBlank()) {
            Document()
        } else {
            regex("title", ".*${Regex.escape(title)}.*", "i")
        }

        val iterable = collection.find(filter)
            .sort(ascending("_id"))
            .skip(offset ?: 0)
            .let { if (limit != null) it.limit(limit) else it }

        return iterable.map { document ->
            EventResponse(
                id = document.getObjectId("_id").toHexString(),
                title = document.getString("title"),
                description = document.getString("description"),
                location = EventLocationResponse(
                    address = document.get("location", Document::class.java).getString("address")
                ),
                createdAt = document.getString("created_at"),
                createdBy = document.getString("created_by"),
                startedAt = document.getString("started_at"),
                finishedAt = document.getString("finished_at"),
            )
        }.toList()
    }
}

