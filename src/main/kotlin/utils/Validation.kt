import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

fun validateUserCreateRequest(request: UserCreateRequest): String? = when {
    request.fullName.isNullOrBlank() -> "full_name"
    request.username.isNullOrBlank() -> "username"
    request.password.isNullOrBlank() -> "password"
    else -> null
}

fun validateLoginRequest(request: LoginRequest): String? = when {
    request.username.isNullOrBlank() -> "username"
    request.password.isNullOrBlank() -> "password"
    else -> null
}

fun validateEventCreateRequest(request: EventCreateRequest): String? {
    if (request.title.isNullOrBlank()) return "title"
    if (request.address.isNullOrBlank()) return "address"
    if (request.startedAt.isNullOrBlank()) return "started_at"
    if (request.finishedAt.isNullOrBlank()) return "finished_at"

    val startedAt = parseRfc3339(request.startedAt) ?: return "started_at"
    val finishedAt = parseRfc3339(request.finishedAt) ?: return "finished_at"
    if (finishedAt.isBefore(startedAt)) return "finished_at"

    return null
}

fun parseRfc3339(value: String): OffsetDateTime? = try {
    OffsetDateTime.parse(value)
} catch (_: DateTimeParseException) {
    null
}

fun parseUIntParameter(value: String?): Int? {
    if (value == null) return null
    if (value.isBlank()) return null
    return value.toIntOrNull()?.takeIf { it >= 0 }
}
