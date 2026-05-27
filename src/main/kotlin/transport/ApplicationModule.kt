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

    val cassandraSession = createCassandraSession(config)

    val neo4jDriver = createNeo4jDriver(config)
    val graphRepository = Neo4jRecommendationRepository(neo4jDriver)
    val recommendationsCache = RedisRecommendationsCache(
        jedisPool = jedisPool,
        ttlSeconds = config.recommendationsTtlSeconds,
    )
    val recommendationService = RecommendationService(
        graphRepository = graphRepository,
        eventRepository = eventRepository,
        cache = recommendationsCache,
    )

    val reactionRepository = CassandraEventReactionRepository(
        session = cassandraSession,
        consistency = config.cassandraConsistency,
    )
    val reactionCache = RedisEventReactionCache(
        jedisPool = jedisPool,
        ttlSeconds = config.likeTtlSeconds,
    )
    val reactionService = EventReactionService(
        eventRepository = eventRepository,
        reactionRepository = reactionRepository,
        reactionCache = reactionCache,
        graphRepository = graphRepository
    )

    val reviewRepository = CassandraEventReviewRepository(
        session = cassandraSession,
        consistency = config.cassandraConsistency,
    )

    val reviewCache = RedisEventReviewCache(
        jedisPool = jedisPool,
        ttlSeconds = config.eventReviewsTtlSeconds,
    )

    val reviewService = EventReviewService(
        eventRepository = eventRepository,
        reviewRepository = reviewRepository,
        reviewCache = reviewCache,
    )

    monitor.subscribe(ApplicationStopped) {
        jedisPool.close()
        mongoClient.close()
        cassandraSession.close()
        neo4jDriver.close()
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
            graphRepository.createUser(createdUserId)

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
            val includeReactions = shouldIncludeReactions(call)
            val includeReviews = shouldIncludeReviews(call)
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

            val responseEvents = applyEventIncludes(
                events = events,
                includeReactions = includeReactions,
                includeReviews = includeReviews,
                reactionService = reactionService,
                reviewService = reviewService,
            )

            call.respond(EventsListResponse(responseEvents, responseEvents.size))
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

            graphRepository.createEvent(
                eventId = eventId,
                title = payload.title!!,
            )

            call.respond(HttpStatusCode.Created, CreateEventResponse(eventId))
        }

        post("/events/{event_id}/like") {
            handleEventReaction(
                call = call,
                sessionService = sessionService,
                ttlSeconds = config.sessionTtlSeconds,
                reactionService = reactionService,
                likeValue = 1,
                expireUnauthorizedCookie = false,
            )
        }

        post("/events/{event_id}/dislike") {
            handleEventReaction(
                call = call,
                sessionService = sessionService,
                ttlSeconds = config.sessionTtlSeconds,
                reactionService = reactionService,
                likeValue = -1,
                expireUnauthorizedCookie = true,
            )
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

        get("/recommendations") {
            val sid = extractValidSid(call.request)
            if (sid == null || !sessionService.refreshIfExists(sid)) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }

            setSessionCookie(call, sid, config.sessionTtlSeconds)

            val userId = sessionService.getUserId(sid)
            if (userId == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@get
            }

            val response = recommendationService.getRecommendations(userId)
            call.respond(response)
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
            val includeReactions = shouldIncludeReactions(call)
            val includeReviews = shouldIncludeReviews(call)

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

            val responseEvents = applyEventIncludes(
                events = events,
                includeReactions = includeReactions,
                includeReviews = includeReviews,
                reactionService = reactionService,
                reviewService = reviewService,
            )

            call.respond(EventsListResponse(responseEvents, responseEvents.size))
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

            val includeReactions = shouldIncludeReactions(call)
            val includeReviews = shouldIncludeReviews(call)

            val responseEvent = applyEventIncludes(
                event = event,
                includeReactions = includeReactions,
                includeReviews = includeReviews,
                reactionService = reactionService,
                reviewService = reviewService,
            )

            call.respond(responseEvent)
        }

        post("/events/{event_id}/reviews") {
            handleCreateEventReview(
                call = call,
                sessionService = sessionService,
                ttlSeconds = config.sessionTtlSeconds,
                reviewService = reviewService,
            )
        }

        get("/events/{event_id}/reviews") {
            handleGetEventReviews(
                call = call,
                sessionService = sessionService,
                ttlSeconds = config.sessionTtlSeconds,
                reviewService = reviewService,
            )
        }

        patch("/events/{event_id}/reviews/{review_id}") {
            handleUpdateEventReview(
                call = call,
                sessionService = sessionService,
                ttlSeconds = config.sessionTtlSeconds,
                reviewService = reviewService,
            )
        }
    }
}

private fun shouldIncludeReactions(call: ApplicationCall): Boolean =
    requestIncludes(call, "reactions")

private fun shouldIncludeReviews(call: ApplicationCall): Boolean =
    requestIncludes(call, "reviews")

private fun requestIncludes(call: ApplicationCall, value: String): Boolean =
    call.request.queryParameters.getAll("include")
        ?.flatMap { raw -> raw.split(",") }
        ?.map { it.trim() }
        ?.contains(value) == true

private fun applyEventIncludes(
    event: EventResponse,
    includeReactions: Boolean,
    includeReviews: Boolean,
    reactionService: EventReactionService,
    reviewService: EventReviewService,
): EventResponse {
    var response = event

    if (includeReactions) {
        response = reactionService.withReactions(response)
    }

    if (includeReviews) {
        response = reviewService.withReviews(response)
    }

    return response
}

private fun applyEventIncludes(
    events: List<EventResponse>,
    includeReactions: Boolean,
    includeReviews: Boolean,
    reactionService: EventReactionService,
    reviewService: EventReviewService,
): List<EventResponse> =
    events.map { event ->
        applyEventIncludes(
            event = event,
            includeReactions = includeReactions,
            includeReviews = includeReviews,
            reactionService = reactionService,
            reviewService = reviewService,
        )
    }

private suspend fun handleCreateEventReview(
    call: ApplicationCall,
    sessionService: RedisSessionService,
    ttlSeconds: Long,
    reviewService: EventReviewService,
) {
    val sid = extractValidSid(call.request)
    if (sid == null || !sessionService.refreshIfExists(sid)) {
        call.respond(HttpStatusCode.Unauthorized)
        return
    }

    setSessionCookie(call, sid, ttlSeconds)

    val userId = sessionService.getUserId(sid)
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized)
        return
    }

    val eventIdRaw = call.parameters["event_id"]
    val eventId = eventIdRaw?.let { parseObjectId(it) }
    if (eventId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Event not found"))
        return
    }

    val payload = runCatching { call.receive<ReviewCreateRequest>() }.getOrNull()
    if (payload == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"body\" field"))
        return
    }

    val invalidField = validateReviewCreateRequest(payload)
    if (invalidField != null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"$invalidField\" field"))
        return
    }

    val result = reviewService.createReview(
        eventId = eventId,
        userId = userId,
        comment = payload.comment!!,
        rating = payload.rating!!,
    )

    when (result.status) {
        ReviewCreateStatus.CREATED -> {
            call.respond(HttpStatusCode.Created, CreateReviewResponse(result.id!!))
        }

        ReviewCreateStatus.EVENT_NOT_FOUND -> {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Event not found"))
        }

        ReviewCreateStatus.ALREADY_EXISTS -> {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Already exists"))
        }
    }
}

private suspend fun handleGetEventReviews(
    call: ApplicationCall,
    sessionService: RedisSessionService,
    ttlSeconds: Long,
    reviewService: EventReviewService,
) {
    setSessionCookieIfExists(call, sessionService, ttlSeconds)

    val eventIdRaw = call.parameters["event_id"]
    val eventId = eventIdRaw?.let { parseObjectId(it) }
    if (eventId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Event not found"))
        return
    }

    val limitRaw = call.request.queryParameters["limit"]
    val offsetRaw = call.request.queryParameters["offset"]

    val limit = parseUIntParameter(limitRaw)
    if (limitRaw != null && limit == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"limit\" field"))
        return
    }

    val offset = parseUIntParameter(offsetRaw)
    if (offsetRaw != null && offset == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"offset\" field"))
        return
    }

    val reviews = reviewService.findReviews(
        eventId = eventId,
        limit = limit,
        offset = offset,
    )

    if (reviews == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Event not found"))
        return
    }

    call.respond(ReviewsListResponse(reviews, reviews.size))
}

private suspend fun handleUpdateEventReview(
    call: ApplicationCall,
    sessionService: RedisSessionService,
    ttlSeconds: Long,
    reviewService: EventReviewService,
) {
    val sid = extractValidSid(call.request)
    if (sid == null || !sessionService.refreshIfExists(sid)) {
        call.respond(HttpStatusCode.Unauthorized)
        return
    }

    setSessionCookie(call, sid, ttlSeconds)

    val userId = sessionService.getUserId(sid)
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized)
        return
    }

    val eventIdRaw = call.parameters["event_id"]
    val eventId = eventIdRaw?.let { parseObjectId(it) }
    if (eventId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Event not found"))
        return
    }

    val reviewIdRaw = call.parameters["review_id"]
    val reviewId = reviewIdRaw?.let { parseUuid(it) }
    if (reviewId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Event not found"))
        return
    }

    val payload = runCatching { call.receive<ReviewPatchRequest>() }.getOrNull()
    if (payload == null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"body\" field"))
        return
    }

    val invalidField = validateReviewPatchRequest(payload)
    if (invalidField != null) {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid \"$invalidField\" field"))
        return
    }

    val updated = reviewService.updateReview(
        eventId = eventId,
        reviewId = reviewId,
        userId = userId,
        comment = payload.comment,
        rating = payload.rating,
    )

    if (!updated) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Event not found"))
        return
    }

    call.respond(HttpStatusCode.NoContent)
}

private suspend fun handleEventReaction(
    call: ApplicationCall,
    sessionService: RedisSessionService,
    ttlSeconds: Long,
    reactionService: EventReactionService,
    likeValue: Int,
    expireUnauthorizedCookie: Boolean,
) {
    val sid = extractValidSid(call.request)
    if (sid == null || !sessionService.refreshIfExists(sid)) {
        if (expireUnauthorizedCookie) {
            expireSessionCookie(call)
        }
        call.respond(HttpStatusCode.Unauthorized)
        return
    }

    setSessionCookie(call, sid, ttlSeconds)

    val userId = sessionService.getUserId(sid)
    if (userId == null) {
        call.respond(HttpStatusCode.Unauthorized)
        return
    }

    val eventIdRaw = call.parameters["event_id"]
    val eventId = eventIdRaw?.let { parseObjectId(it) }
    if (eventId == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Event not found"))
        return
    }

    val updated = reactionService.setReaction(
        eventId = eventId,
        userId = userId,
        likeValue = likeValue,
    )

    if (!updated) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Event not found"))
        return
    }

    call.respond(HttpStatusCode.NoContent)
}
