import org.bson.types.ObjectId
import java.time.Instant
import java.time.OffsetDateTime

fun documentIdToString(value: Any?): String? = when (value) {
    is ObjectId -> value.toHexString()
    is String -> value
    else -> value?.toString()
}

fun documentString(value: Any?): String = when (value) {
    null -> ""
    is String -> value
    is java.util.Date -> value.toInstant().toString()
    is Instant -> value.toString()
    is OffsetDateTime -> value.toString()
    is ObjectId -> value.toHexString()
    else -> value.toString()
}