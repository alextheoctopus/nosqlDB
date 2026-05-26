import io.ktor.http.HttpStatusCode

data class SessionResult(val sid: String, val status: HttpStatusCode)
