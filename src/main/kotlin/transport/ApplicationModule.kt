import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.mindrot.jbcrypt.BCrypt

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