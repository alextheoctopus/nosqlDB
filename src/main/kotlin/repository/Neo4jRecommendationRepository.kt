import org.neo4j.driver.Driver
import org.neo4j.driver.TransactionContext

class Neo4jRecommendationRepository(
    private val driver: Driver,
) {
    fun createUser(userId: String) {
        driver.session().use { session ->
            session.executeWrite { tx: TransactionContext ->
                tx.run(
                    """
                    MERGE (:User {id: ${'$'}userId})
                    """.trimIndent(),
                    mapOf("userId" to userId)
                )
                null
            }
        }
    }

    fun createEvent(eventId: String, title: String) {
        driver.session().use { session ->
            session.executeWrite { tx: TransactionContext ->
                tx.run(
                    """
                    MERGE (e:Event {id: ${'$'}eventId})
                    SET e.title = ${'$'}title
                    """.trimIndent(),
                    mapOf(
                        "eventId" to eventId,
                        "title" to title
                    )
                )
                null
            }
        }
    }

    fun createLikedRelation(userId: String, eventId: String, title: String) {
        driver.session().use { session ->
            session.executeWrite { tx: TransactionContext ->
                tx.run(
                    """
                    MERGE (u:User {id: ${'$'}userId})
                    MERGE (e:Event {id: ${'$'}eventId})
                    SET e.title = ${'$'}title
                    MERGE (u)-[:LIKED]->(e)
                    """.trimIndent(),
                    mapOf(
                        "userId" to userId,
                        "eventId" to eventId,
                        "title" to title
                    )
                )
                null
            }
        }
    }

    fun findRecommendedEventIds(userId: String): List<String> {
        driver.session().use { session ->
            return session.executeRead { tx: TransactionContext ->
                val result = tx.run(
                    """
                    MATCH (u:User {id: ${'$'}userId})-[:LIKED]->(:Event)<-[:LIKED]-(other:User)-[:LIKED]->(rec:Event)
                    WHERE NOT (u)-[:LIKED]->(rec)
                    WITH rec.id AS eventId, count(DISTINCT other) AS relevance
                    ORDER BY relevance DESC
                    RETURN eventId
                    """.trimIndent(),
                    mapOf("userId" to userId)
                )

                result.list { record ->
                    record["eventId"].asString()
                }
            }
        }
    }
}