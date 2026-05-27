import org.bson.types.ObjectId
import java.util.UUID

enum class ReviewCreateStatus {
    CREATED,
    EVENT_NOT_FOUND,
    ALREADY_EXISTS,
}

data class ReviewCreateResult(
    val status: ReviewCreateStatus,
    val id: String? = null,
)

class EventReviewService(
    private val eventRepository: MongoEventRepository,
    private val reviewRepository: CassandraEventReviewRepository,
    private val reviewCache: RedisEventReviewCache,
) {
    fun createReview(
        eventId: ObjectId,
        userId: String,
        comment: String,
        rating: Int,
    ): ReviewCreateResult {
        val eventTitle = eventRepository.findEventTitleById(eventId)
            ?: return ReviewCreateResult(ReviewCreateStatus.EVENT_NOT_FOUND)

        val reviewId = reviewRepository.createReview(
            eventId = eventId.toHexString(),
            userId = userId,
            comment = comment,
            rating = rating,
        ) ?: return ReviewCreateResult(ReviewCreateStatus.ALREADY_EXISTS)

        refreshCache(eventTitle)

        return ReviewCreateResult(
            status = ReviewCreateStatus.CREATED,
            id = reviewId,
        )
    }

    fun updateReview(
        eventId: ObjectId,
        reviewId: UUID,
        userId: String,
        comment: String?,
        rating: Int?,
    ): Boolean {
        val eventTitle = eventRepository.findEventTitleById(eventId) ?: return false

        val updated = reviewRepository.updateOwnReview(
            eventId = eventId.toHexString(),
            reviewId = reviewId,
            userId = userId,
            comment = comment,
            rating = rating,
        )

        if (!updated) {
            return false
        }

        refreshCache(eventTitle)
        return true
    }

    fun findReviews(eventId: ObjectId, limit: Int?, offset: Int?): List<ReviewResponse>? {
        eventRepository.findEventTitleById(eventId) ?: return null

        return reviewRepository.findReviews(
            eventId = eventId.toHexString(),
            limit = limit,
            offset = offset,
        )
    }

    fun withReviews(event: EventResponse): EventResponse =
        event.copy(reviews = getReviewsSummary(event.title))

    fun withReviews(events: List<EventResponse>): List<EventResponse> =
        events.map { event -> withReviews(event) }

    private fun getReviewsSummary(title: String): ReviewsSummaryResponse {
        val cached = reviewCache.get(title)
        if (cached != null) {
            return cached
        }

        val eventIds = eventRepository.findEventIdsByExactTitle(title)
        val result = reviewRepository.summarizeReviews(eventIds)

        if (result.hasRows) {
            reviewCache.put(title, result.reviews)
        }

        return result.reviews
    }

    private fun refreshCache(title: String) {
        val eventIds = eventRepository.findEventIdsByExactTitle(title)
        val result = reviewRepository.summarizeReviews(eventIds)
        reviewCache.put(title, result.reviews)
    }
}