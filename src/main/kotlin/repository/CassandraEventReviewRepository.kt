import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

class CassandraEventReviewRepository(
    private val session: CqlSession,
    private val consistency: DefaultConsistencyLevel,
) {
    fun createReview(
        eventId: String,
        userId: String,
        comment: String,
        rating: Int,
    ): String? {
        val existing = session.execute(
            SimpleStatement.builder(
                "SELECT id FROM event_reviews WHERE event_id = ? AND created_by = ?"
            )
                .addPositionalValue(eventId)
                .addPositionalValue(userId)
                .setConsistencyLevel(consistency)
                .build()
        ).one()

        if (existing != null) {
            return null
        }

        val id = UUID.randomUUID()
        val now = Instant.now()

        session.execute(
            SimpleStatement.builder(
                """
                INSERT INTO event_reviews (
                    event_id,
                    created_by,
                    id,
                    rating,
                    comment,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
                .addPositionalValue(eventId)
                .addPositionalValue(userId)
                .addPositionalValue(id)
                .addPositionalValue(rating.toByte())
                .addPositionalValue(comment)
                .addPositionalValue(now)
                .addPositionalValue(now)
                .setConsistencyLevel(consistency)
                .build()
        )

        return id.toString()
    }

    fun updateOwnReview(
        eventId: String,
        reviewId: UUID,
        userId: String,
        comment: String?,
        rating: Int?,
    ): Boolean {
        val existing = session.execute(
            SimpleStatement.builder(
                "SELECT id FROM event_reviews WHERE event_id = ? AND created_by = ?"
            )
                .addPositionalValue(eventId)
                .addPositionalValue(userId)
                .setConsistencyLevel(consistency)
                .build()
        ).one() ?: return false

        if (existing.getUuid("id") != reviewId) {
            return false
        }

        val setParts = mutableListOf<String>()
        val values = mutableListOf<Any>()

        if (comment != null) {
            setParts += "comment = ?"
            values += comment
        }

        if (rating != null) {
            setParts += "rating = ?"
            values += rating.toByte()
        }

        setParts += "updated_at = ?"
        values += Instant.now()

        values += eventId
        values += userId

        val statement = SimpleStatement.builder(
            """
            UPDATE event_reviews
            SET ${setParts.joinToString(", ")}
            WHERE event_id = ? AND created_by = ?
            """.trimIndent()
        )

        values.forEach { value ->
            statement.addPositionalValue(value)
        }

        session.execute(
            statement
                .setConsistencyLevel(consistency)
                .build()
        )

        return true
    }

    fun findReviews(eventId: String, limit: Int?, offset: Int?): List<ReviewResponse> {
        val rows = session.execute(
            SimpleStatement.builder(
                """
                SELECT id, event_id, comment, created_at, created_by, rating, updated_at
                FROM event_reviews
                WHERE event_id = ?
                """.trimIndent()
            )
                .addPositionalValue(eventId)
                .setConsistencyLevel(consistency)
                .build()
        ).map { row ->
            ReviewResponse(
                id = row.getUuid("id")?.toString().orEmpty(),
                eventId = row.getString("event_id").orEmpty(),
                comment = row.getString("comment").orEmpty(),
                createdAt = row.getInstant("created_at")?.toString().orEmpty(),
                createdBy = row.getString("created_by").orEmpty(),
                rating = row.getByte("rating").toInt(),
                updatedAt = row.getInstant("updated_at")?.toString().orEmpty(),
            )
        }.toList()

        return rows
            .drop(offset ?: 0)
            .let { if (limit != null) it.take(limit) else it }
    }

    fun summarizeReviews(eventIds: List<String>): ReviewSummaryResult {
        var count = 0
        var ratingSum = 0

        eventIds.distinct().forEach { eventId ->
            val rows = session.execute(
                SimpleStatement.builder(
                    "SELECT rating FROM event_reviews WHERE event_id = ?"
                )
                    .addPositionalValue(eventId)
                    .setConsistencyLevel(consistency)
                    .build()
            )

            rows.forEach { row ->
                count += 1
                ratingSum += row.getByte("rating").toInt()
            }
        }

        val averageRating = if (count == 0) {
            0.0
        } else {
            BigDecimal(ratingSum.toDouble() / count.toDouble())
                .setScale(1, RoundingMode.HALF_UP)
                .toDouble()
        }

        return ReviewSummaryResult(
            reviews = ReviewsSummaryResponse(
                count = count,
                rating = averageRating,
            ),
            hasRows = count > 0,
        )
    }
}