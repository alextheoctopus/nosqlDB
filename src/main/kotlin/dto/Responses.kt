import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class ErrorResponse(val message: String)

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
    val reactions: ReactionsResponse? = null,
    val reviews: ReviewsSummaryResponse? = null,

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
@Serializable
data class ReactionsResponse(
    val likes: Int,
    val dislikes: Int,
)

@Serializable
data class ReviewsSummaryResponse(
    val count: Int,
    val rating: Double,
)

@Serializable
data class CreateReviewResponse(val id: String)

@Serializable
data class ReviewResponse(
    val id: String,
    @SerialName("event_id") val eventId: String,
    val comment: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("created_by") val createdBy: String,
    val rating: Int,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class ReviewsListResponse(
    val reviews: List<ReviewResponse>,
    val count: Int,
)