import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.gte
import com.mongodb.client.model.Filters.lte
import com.mongodb.client.model.Filters.or
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
import kotlinx.serialization.json.Json
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import org.mindrot.jbcrypt.BCrypt
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private const val SESSION_COOKIE_NAME = "X-Session-Id"
private val SID_REGEX = Regex("^[a-f0-9]{32}$")
private val secureRandom = SecureRandom()
private val EVENT_CATEGORIES = setOf("meetup", "concert", "exhibition", "party", "other")
private val BASIC_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

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
data class EventPatchRequest(
    val category: String? = null,
    val price: Int? = null,
    val city: String? = null,
)

@Serializable
data class CreateEventResponse(val id: String)

@Serializable
data class EventLocationResponse(
    val city: String? = null,
    val address: String,
)

@Serializable
data class EventResponse(
    val id: String,
    val title: String,
    val category: String,
    val price: Int,
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

@Serializable
data class PublicUserResponse(
    val id: String,
    @SerialName("full_name") val fullName: String,
    val username: String,
)

@Serializable
data class UsersListResponse(
    val users: List<PublicUserResponse>,
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

data class EventSearchQuery(
    val id: ObjectId? = null,
    val title: String? = null,
    val category: String? = null,
    val priceFrom: Int? = null,
    val priceTo: Int? = null,
    val city: String? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    val createdByUserId: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
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
        json(Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        })
    }

    routing {
        get("/") {
            call.respondText("It's root url :)")
        }

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

        get("/users") {
            setSessionCookieIfExists(call, sessionService, config.sessionTtlSeconds)

            val limitRaw = call.request.queryParameters["limit"]
            val offsetRaw = call.request.queryParameters["offset"]
            val name = call.request.queryParameters["name"]
            val idRaw = call.request.queryParameters["id"]

            val limit = parseUIntParameter(limitRaw)
            if (limitRaw != null && limit == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"limit\" field"))
                return@get
            }

            val offset = parseUIntParameter(offsetRaw)
            if (offsetRaw != null && offset == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"offset\" field"))
                return@get
            }

            if (name != null && name.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"name\" field"))
                return@get
            }

            val id = if (idRaw == null) {
                null
            } else {
                parseObjectId(idRaw) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"id\" field"))
                    return@get
                }
            }

            val users = userRepository.findUsers(
                id = id,
                name = name,
                limit = limit,
                offset = offset,
            )

            call.respond(UsersListResponse(users, users.size))
        }

        get("/users/{id}/events") {
            setSessionCookieIfExists(call, sessionService, config.sessionTtlSeconds)

            val userIdRaw = call.parameters["id"]
            val userId = if (userIdRaw == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@get
            } else {
                parseObjectId(userIdRaw) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                    return@get
                }
            }

            if (!userRepository.existsById(userId)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("User not found"))
                return@get
            }

            val events = eventRepository.findEvents(
                EventSearchQuery(createdByUserId = userId.toHexString())
            )
            call.respond(EventsListResponse(events, events.size))
        }

        get("/users/{id}") {
            setSessionCookieIfExists(call, sessionService, config.sessionTtlSeconds)

            val idRaw = call.parameters["id"]
            val id = if (idRaw == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
                return@get
            } else {
                parseObjectId(idRaw) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
                    return@get
                }
            }

            val user = userRepository.findPublicById(id)
            if (user == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
                return@get
            }

            call.respond(user)
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
            if (user == null || user.passwordHash == null || !BCrypt.checkpw(payload.password!!, user.passwordHash)) {
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

        patch("/events/{id}") {
            val sid = extractValidSid(call.request)
            if (sid == null || !sessionService.refreshIfExists(sid)) {
                call.respond(HttpStatusCode.Unauthorized)
                return@patch
            }
            setSessionCookie(call, sid, config.sessionTtlSeconds)

            val userId = sessionService.getUserId(sid)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@patch
            }

            val eventIdRaw = call.parameters["id"]
            val eventId = eventIdRaw?.let { parseObjectId(it) }
            if (eventId == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("Not found. Be sure that event exists and you are the organizer")
                )
                return@patch
            }

            val payload = runCatching { call.receive<EventPatchRequest>() }.getOrNull()
            if (payload == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"body\" field"))
                return@patch
            }

            val invalidField = validateEventPatchRequest(payload)
            if (invalidField != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"$invalidField\" field"))
                return@patch
            }

            val updated = eventRepository.patchEvent(
                id = eventId,
                organizerId = userId,
                category = payload.category,
                price = payload.price,
                city = payload.city,
            )

            if (!updated) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("Not found. Be sure that event exists and you are the organizer")
                )
                return@patch
            }

            call.respond(HttpStatusCode.NoContent)
        }

        get("/events") {
            setSessionCookieIfExists(call, sessionService, config.sessionTtlSeconds)

            val title = call.request.queryParameters["title"]
            val idRaw = call.request.queryParameters["id"]
            val category = call.request.queryParameters["category"]
            val priceFromRaw = call.request.queryParameters["price_from"]
            val priceToRaw = call.request.queryParameters["price_to"]
            val city = call.request.queryParameters["city"]
            val dateFromRaw = call.request.queryParameters["date_from"]
            val dateToRaw = call.request.queryParameters["date_to"]
            val user = call.request.queryParameters["user"]
            val limitRaw = call.request.queryParameters["limit"]
            val offsetRaw = call.request.queryParameters["offset"]

            if (title != null && title.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"title\" field"))
                return@get
            }
            if (city != null && city.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"city\" field"))
                return@get
            }
            if (user != null && user.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"user\" field"))
                return@get
            }
            if (category != null && category !in EVENT_CATEGORIES) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"category\" field"))
                return@get
            }

            val id = if (idRaw == null) {
                null
            } else {
                parseObjectId(idRaw) ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"id\" field"))
                    return@get
                }
            }

            val priceFrom = parseUIntParameter(priceFromRaw)
            if (priceFromRaw != null && priceFrom == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"price_from\" field"))
                return@get
            }

            val priceTo = parseUIntParameter(priceToRaw)
            if (priceToRaw != null && priceTo == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"price_to\" field"))
                return@get
            }

            if (priceFrom != null && priceTo != null && priceTo < priceFrom) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"price_to\" field"))
                return@get
            }

            val dateFrom = parseBasicDate(dateFromRaw)
            if (dateFromRaw != null && dateFrom == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"date_from\" field"))
                return@get
            }

            val dateTo = parseBasicDate(dateToRaw)
            if (dateToRaw != null && dateTo == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"date_to\" field"))
                return@get
            }

            if (dateFrom != null && dateTo != null && dateTo.isBefore(dateFrom)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"date_to\" field"))
                return@get
            }

            val limit = parseUIntParameter(limitRaw)
            if (limitRaw != null && limit == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"limit\" field"))
                return@get
            }

            val offset = parseUIntParameter(offsetRaw)
            if (offsetRaw != null && offset == null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"offset\" field"))
                return@get
            }

            val createdByUserId = if (user == null) {
                null
            } else {
                userRepository.findUserIdByUsername(user) ?: "__no_such_user__"
            }

            val events = eventRepository.findEvents(
                EventSearchQuery(
                    id = id,
                    title = title,
                    category = category,
                    priceFrom = priceFrom,
                    priceTo = priceTo,
                    city = city,
                    dateFrom = dateFrom,
                    dateTo = dateTo,
                    createdByUserId = createdByUserId,
                    limit = limit,
                    offset = offset,
                )
            )

            call.respond(EventsListResponse(events, events.size))
        }

        get("/events/{id}") {
            setSessionCookieIfExists(call, sessionService, config.sessionTtlSeconds)

            val idRaw = call.parameters["id"]
            val id = if (idRaw == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
                return@get
            } else {
                parseObjectId(idRaw) ?: run {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
                    return@get
                }
            }

            val event = eventRepository.findEventById(id)
            if (event == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found"))
                return@get
            }

            call.respond(event)
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
    mongoDatabase = System.getenv("MONGODB_DATABASE").trim().trim('"'),
    mongoUser = System.getenv("MONGODB_USER")?.trim()?.ifBlank { null },
    mongoPassword = System.getenv("MONGODB_PASSWORD")?.trim()?.ifBlank { null },
    mongoHost = System.getenv("MONGODB_HOST").trim(),
    mongoPort = System.getenv("MONGODB_PORT").trim().toInt(),
)

private fun createJedisPool(config: AppConfig): JedisPool {
    val poolConfig = JedisPoolConfig().apply {
        maxTotal = 8
        maxIdle = 8
        minIdle = 0
    }

    return if (!config.redisPassword.isNullOrBlank()) {
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

private fun validateEventPatchRequest(request: EventPatchRequest): String? {
    if (request.category == null && request.price == null && request.city == null) {
        return "body"
    }
    if (request.category != null && request.category !in EVENT_CATEGORIES) {
        return "category"
    }
    if (request.price != null && request.price < 0) {
        return "price"
    }
    return null
}

private fun parseRfc3339(value: String): OffsetDateTime? = try {
    OffsetDateTime.parse(value)
} catch (_: DateTimeParseException) {
    null
}

private fun parseBasicDate(value: String?): LocalDate? {
    if (value == null) return null
    if (value.isBlank()) return null
    return try {
        LocalDate.parse(value, BASIC_DATE_FORMATTER)
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun parseUIntParameter(value: String?): Int? {
    if (value == null) return null
    if (value.isBlank()) return null
    return value.toIntOrNull()?.takeIf { it >= 0 }
}

private fun parseObjectId(value: String): ObjectId? = try {
    ObjectId(value)
} catch (_: IllegalArgumentException) {
    null
}

private fun documentIdToString(value: Any?): String? = when (value) {
    is ObjectId -> value.toHexString()
    is String -> value
    else -> value?.toString()
}

private fun documentString(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    is java.util.Date -> value.toInstant().toString()
    is Instant -> value.toString()
    is OffsetDateTime -> value.toString()
    is ObjectId -> value.toHexString()
    else -> value.toString()
}

private data class SessionResult(val sid: String, val status: HttpStatusCode)
private data class UserRecord(val id: String, val passwordHash: String?)

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
            id = documentIdToString(doc.get("_id")) ?: return null,
            passwordHash = doc.getString("password_hash")
        )
    }

    fun findUserIdByUsername(username: String): String? {
        val doc = collection.find(eq("username", username)).firstOrNull() ?: return null
        return documentIdToString(doc.get("_id"))
    }

    fun findPublicById(id: ObjectId): PublicUserResponse? {
        val doc = collection.find(eq("_id", id)).firstOrNull() ?: return null
        return PublicUserResponse(
            id = documentIdToString(doc.get("_id")) ?: return null,
            fullName = documentString(doc.get("full_name")),
            username = documentString(doc.get("username")),
        )
    }

    fun existsById(id: ObjectId): Boolean =
        collection.find(eq("_id", id)).limit(1).firstOrNull() != null

    fun findUsers(id: ObjectId?, name: String?, limit: Int?, offset: Int?): List<PublicUserResponse> {
        val filters = mutableListOf<Bson>()
        if (id != null) {
            filters += eq("_id", id)
        }
        if (!name.isNullOrBlank()) {
            filters += regex("full_name", ".*${Regex.escape(name)}.*", "i")
        }

        val filter = when (filters.size) {
            0 -> Document()
            1 -> filters.first()
            else -> and(filters)
        }

        val iterable = collection.find(filter)
            .sort(ascending("_id"))
            .skip(offset ?: 0)
            .let { if (limit != null) it.limit(limit) else it }

        return iterable.map {
            PublicUserResponse(
                id = documentIdToString(it.get("_id")) ?: "",
                fullName = documentString(it.get("full_name")),
                username = documentString(it.get("username")),
            )
        }.toList()
    }
}

private class MongoEventRepository(database: MongoDatabase) {
    private val collection: MongoCollection<Document> = database.getCollection("events")

    fun ensureIndexes() {
        collection.createIndex(ascending("title"))
        collection.createIndex(ascending("title", "created_by"))
        collection.createIndex(ascending("created_by"))
        collection.createIndex(ascending("category"))
        collection.createIndex(ascending("price"))
        collection.createIndex(ascending("location.city"))
        collection.createIndex(ascending("started_at"))
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
            .append("category", "other")
            .append("price", 0)
            .append("description", description)
            .append("location", Document("address", address))
            .append("created_at", OffsetDateTime.now(ZoneOffset.UTC).toString())
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

    fun patchEvent(
        id: ObjectId,
        organizerId: String,
        category: String?,
        price: Int?,
        city: String?,
    ): Boolean {
        val organizerFilter = parseObjectId(organizerId)?.let { organizerObjectId ->
            or(eq("created_by", organizerId), eq("created_by", organizerObjectId))
        } ?: eq("created_by", organizerId)

        val filter = and(eq("_id", id), organizerFilter)
        val setDoc = Document()
        val unsetDoc = Document()

        if (category != null) {
            setDoc["category"] = category
        }
        if (price != null) {
            setDoc["price"] = price
        }
        if (city != null) {
            if (city.isBlank()) {
                unsetDoc["location.city"] = ""
            } else {
                setDoc["location.city"] = city
            }
        }

        val update = Document()
        if (!setDoc.isEmpty()) {
            update["\$set"] = setDoc
        }
        if (!unsetDoc.isEmpty()) {
            update["\$unset"] = unsetDoc
        }

        val result = collection.updateOne(filter, update)
        return result.matchedCount > 0
    }

    fun findEventById(id: ObjectId): EventResponse? {
        val document = collection.find(eq("_id", id)).firstOrNull() ?: return null
        return documentToEventResponse(document)
    }

    fun findEvents(query: EventSearchQuery): List<EventResponse> {
        if (query.createdByUserId == "__no_such_user__") {
            return emptyList()
        }

        val filters = mutableListOf<Bson>()
        query.id?.let { filters += eq("_id", it) }
        query.title?.let { filters += regex("title", ".*${Regex.escape(it)}.*", "i") }
        query.category?.let { filters += eq("category", it) }
        query.priceFrom?.let { filters += gte("price", it) }
        query.priceTo?.let { filters += lte("price", it) }
        query.city?.let { filters += eq("location.city", it) }
        query.createdByUserId?.let { userId ->
            val createdByFilter = parseObjectId(userId)?.let { objectId ->
                or(eq("created_by", userId), eq("created_by", objectId))
            } ?: eq("created_by", userId)
            filters += createdByFilter
        }

        val filter = when (filters.size) {
            0 -> Document()
            1 -> filters.first()
            else -> and(filters)
        }

        val events = collection.find(filter)
            .sort(ascending("_id"))
            .map(::documentToEventResponse)
            .toList()
            .filter { eventMatchesDateRange(it, query.dateFrom, query.dateTo) }

        return events
            .drop(query.offset ?: 0)
            .let { if (query.limit != null) it.take(query.limit) else it }
    }

    private fun eventMatchesDateRange(event: EventResponse, dateFrom: LocalDate?, dateTo: LocalDate?): Boolean {
        if (dateFrom == null && dateTo == null) return true

        val eventDate = parseStartedAtToLocalDate(event.startedAt) ?: return false
        if (dateFrom != null && eventDate.isBefore(dateFrom)) return false
        if (dateTo != null && eventDate.isAfter(dateTo)) return false
        return true
    }

    private fun parseStartedAtToLocalDate(value: String): LocalDate? {
        if (value.isBlank()) return null

        return try {
            OffsetDateTime.parse(value).toLocalDate()
        } catch (_: Exception) {
            try {
                Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDate()
            } catch (_: Exception) {
                try {
                    LocalDate.parse(value.substring(0, 10))
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun documentToEventResponse(document: Document): EventResponse {
        val locationDocument = document.get("location", Document::class.java) ?: Document()
        return EventResponse(
            id = documentIdToString(document.get("_id")) ?: "",
            title = documentString(document.get("title")),
            category = documentString(document.get("category")).ifBlank { "other" },
            price = (document.get("price") as? Number)?.toInt() ?: 0,
            description = documentString(document.get("description")),
            location = EventLocationResponse(
                city = locationDocument.get("city")?.let { documentString(it) }?.ifBlank { null },
                address = documentString(locationDocument.get("address")),
            ),
            createdAt = documentString(document.get("created_at")),
            createdBy = documentIdToString(document.get("created_by")) ?: "",
            startedAt = documentString(document.get("started_at")),
            finishedAt = documentString(document.get("finished_at")),
        )
    }
}