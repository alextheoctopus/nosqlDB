import io.ktor.http.Cookie
import io.ktor.server.application.*
import io.ktor.server.request.*

const val SESSION_COOKIE_NAME = "X-Session-Id"
val SID_REGEX = Regex("^[a-f0-9]{32}$")

fun extractValidSid(request: ApplicationRequest): String? {
    val sid = request.cookies[SESSION_COOKIE_NAME] ?: return null
    return sid.takeIf { SID_REGEX.matches(it) }
}

fun setSessionCookie(call: ApplicationCall, sid: String, ttlSeconds: Long) {
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

fun setSessionCookieIfExists(
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

fun expireSessionCookie(call: ApplicationCall) {
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
