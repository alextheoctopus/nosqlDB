import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters.regex
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import org.bson.Document
import org.bson.conversions.Bson
import java.time.OffsetDateTime

class MongoEventRepository(database: MongoDatabase) {
    private val collection: MongoCollection<Document> = database.getCollection("events")

    fun ensureIndexes() {
        collection.createIndex(ascending("title"), IndexOptions().unique(true))
        collection.createIndex(ascending("title", "created_by"))
        collection.createIndex(ascending("created_by"))
    }

    fun createEvent(
        title: String,
        description: String,
        address: String,
        createdBy: String,
        startedAt: String,
        finishedAt: String,
    ): String? {
        val document = Document()
            .append("title", title)
            .append("description", description)
            .append("location", Document("address", address))
            .append("created_at", OffsetDateTime.now().toString())
            .append("created_by", createdBy)
            .append("started_at", startedAt)
            .append("finished_at", finishedAt)

        return try {
            collection.insertOne(document)
            document.getObjectId("_id").toHexString()
        } catch (_: MongoWriteException) {
            null
        }
    }


    fun findEvents(title: String?, limit: Int?, offset: Int?): List<EventResponse> {
        val filter: Bson = if (title.isNullOrBlank()) {
            Document()
        } else {
            regex("title", ".*${Regex.escape(title)}.*", "i")
        }

        val iterable = collection.find(filter)
            .sort(ascending("_id"))
            .skip(offset ?: 0)
            .let { if (limit != null) it.limit(limit) else it }

        return iterable.map { document ->
            EventResponse(
                id = document.getObjectId("_id").toHexString(),
                title = document.getString("title"),
                description = document.getString("description"),
                location = EventLocationResponse(
                    address = document.get("location", Document::class.java).getString("address")
                ),
                createdAt = document.getString("created_at"),
                createdBy = document.getString("created_by"),
                startedAt = document.getString("started_at"),
                finishedAt = document.getString("finished_at"),
            )
        }.toList()
    }
}