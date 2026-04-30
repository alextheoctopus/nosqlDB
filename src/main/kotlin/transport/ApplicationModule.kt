import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.mindrot.jbcrypt.BCrypt
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
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

            val title = call.request.queryParameters["title"]
            val idRaw = call.request.queryParameters["id"]
            val category = call.request.queryParameters["category"]
            val priceFromRaw = call.request.queryParameters["price_from"]
            val priceToRaw = call.request.queryParameters["price_to"]
            val city = call.request.queryParameters["city"]
            val dateFromRaw = call.request.queryParameters["date_from"]
            val dateToRaw = call.request.queryParameters["date_to"]
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
                    createdByUserId = userId.toHexString(),
                    limit = limit,
                    offset = offset,
                )
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