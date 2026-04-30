import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import java.time.Instant

class CassandraEventReactionRepository(
    private val session: CqlSession,
    private val consistency: DefaultConsistencyLevel,
) {
    fun upsertReaction(eventId: String, userId: String, likeValue: Int) {
        val statement = SimpleStatement.builder(
            """
            INSERT INTO event_reactions (event_id, created_by, like_value, created_at)
            VALUES (?, ?, ?, ?)
            """.trimIndent()
        )
            .addPositionalValue(eventId)
            .addPositionalValue(userId)
            .addPositionalValue(likeValue.toByte())
            .addPositionalValue(Instant.now())
            .setConsistencyLevel(consistency)
            .build()

        session.execute(statement)
    }

    fun countReactions(eventIds: List<String>): ReactionCountResult {
        var likes = 0
        var dislikes = 0
        var hasRows = false

        eventIds.distinct().forEach { eventId ->
            val statement = SimpleStatement.builder(
                "SELECT like_value FROM event_reactions WHERE event_id = ?"
            )
                .addPositionalValue(eventId)
                .setConsistencyLevel(consistency)
                .build()

            val rows = session.execute(statement)
            rows.forEach { row ->
                hasRows = true
                when (row.getByte("like_value").toInt()) {
                    1 -> likes += 1
                    -1 -> dislikes += 1
                }
            }
        }

        return ReactionCountResult(
            reactions = ReactionsResponse(likes = likes, dislikes = dislikes),
            hasRows = hasRows,
        )
    }
}