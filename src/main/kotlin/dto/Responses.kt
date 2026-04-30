import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
@Serializable
data class HealthResponse(val status: String)

@Serializable
data class ErrorResponse(val message: String)

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
