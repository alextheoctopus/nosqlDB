import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
data class ReviewCreateRequest(
    val comment: String? = null,
    val rating: Int? = null,
)

@Serializable
data class ReviewPatchRequest(
    val comment: String? = null,
    val rating: Int? = null,
)