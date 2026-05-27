import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.gte
import com.mongodb.client.model.Filters.lte
import com.mongodb.client.model.Filters.or
import com.mongodb.client.model.Filters.regex
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
class MongoEventRepository(database: MongoDatabase) {
    private val collection: MongoCollection<Document> = database.getCollection("events")

    fun ensureIndexes() {
        collection.createIndex(ascending("title"))
        collection.createIndex(ascending("title", "created_by"))
        collection.createIndex(ascending("created_by"))
        collection.createIndex(ascending("category"))
        collection.createIndex(ascending("price"))
        collection.createIndex(ascending("location.city"))
        collection.createIndex(ascending("started_at"))
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
            .append("category", "other")
            .append("price", 0)
            .append("description", description)
            .append("location", Document("address", address))
            .append("created_at", OffsetDateTime.now(ZoneOffset.UTC).toString())
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

    fun findEventsByIds(ids: List<String>): List<EventResponse> {
        val objectIds = ids.mapNotNull { parseObjectId(it) }
        if (objectIds.isEmpty()) {
            return emptyList()
        }

        val documents = collection.find(com.mongodb.client.model.Filters.`in`("_id", objectIds))
            .map { documentToEventResponse(it) }
            .toList()

        val order = ids.withIndex().associate { it.value to it.index }

        return documents.sortedBy { event ->
            order[event.id] ?: Int.MAX_VALUE
        }
    }

    fun patchEvent(
        id: ObjectId,
        organizerId: String,
        category: String?,
        price: Int?,
        city: String?,
    ): Boolean {
        val organizerFilter = parseObjectId(organizerId)?.let { organizerObjectId ->
            or(eq("created_by", organizerId), eq("created_by", organizerObjectId))
        } ?: eq("created_by", organizerId)

        val filter = and(eq("_id", id), organizerFilter)
        val setDoc = Document()
        val unsetDoc = Document()

        if (category != null) {
            setDoc["category"] = category
        }
        if (price != null) {
            setDoc["price"] = price
        }
        if (city != null) {
            if (city.isBlank()) {
                unsetDoc["location.city"] = ""
            } else {
                setDoc["location.city"] = city
            }
        }

        val update = Document()
        if (!setDoc.isEmpty()) {
            update["\$set"] = setDoc
        }
        if (!unsetDoc.isEmpty()) {
            update["\$unset"] = unsetDoc
        }

        val result = collection.updateOne(filter, update)
        return result.matchedCount > 0
    }

    fun findEventById(id: ObjectId): EventResponse? {
        val document = collection.find(eq("_id", id)).firstOrNull() ?: return null
        return documentToEventResponse(document)
    }

    fun findEventTitleById(id: ObjectId): String? {
        val document = collection.find(eq("_id", id)).firstOrNull() ?: return null
        return documentString(document.get("title")).ifBlank { null }
    }

    fun findEventIdsByExactTitle(title: String): List<String> {
        return collection.find(eq("title", title))
            .map { document -> documentIdToString(document.get("_id")) }
            .toList()
            .filterNotNull()
    }

    fun findEvents(query: EventSearchQuery): List<EventResponse> {
        if (query.createdByUserId == "__no_such_user__") {
            return emptyList()
        }

        val filters = mutableListOf<Bson>()
        query.id?.let { filters += eq("_id", it) }
        query.title?.let { filters += regex("title", ".*${Regex.escape(it)}.*", "i") }
        query.category?.let { filters += eq("category", it) }
        query.priceFrom?.let { filters += gte("price", it) }
        query.priceTo?.let { filters += lte("price", it) }
        query.city?.let { filters += eq("location.city", it) }
        query.createdByUserId?.let { userId ->
            val createdByFilter = parseObjectId(userId)?.let { objectId ->
                or(eq("created_by", userId), eq("created_by", objectId))
            } ?: eq("created_by", userId)
            filters += createdByFilter
        }

        val filter = when (filters.size) {
            0 -> Document()
            1 -> filters.first()
            else -> and(filters)
        }

        val events = collection.find(filter)
            .sort(ascending("_id"))
            .map(::documentToEventResponse)
            .toList()
            .filter { eventMatchesDateRange(it, query.dateFrom, query.dateTo) }

        return events
            .drop(query.offset ?: 0)
            .let { if (query.limit != null) it.take(query.limit) else it }
    }

    private fun eventMatchesDateRange(event: EventResponse, dateFrom: LocalDate?, dateTo: LocalDate?): Boolean {
        if (dateFrom == null && dateTo == null) return true

        val eventDate = parseStartedAtToLocalDate(event.startedAt) ?: return false
        if (dateFrom != null && eventDate.isBefore(dateFrom)) return false
        if (dateTo != null && eventDate.isAfter(dateTo)) return false
        return true
    }

    private fun parseStartedAtToLocalDate(value: String): LocalDate? {
        if (value.isBlank()) return null

        return try {
            OffsetDateTime.parse(value).toLocalDate()
        } catch (_: Exception) {
            try {
                Instant.parse(value).atOffset(ZoneOffset.UTC).toLocalDate()
            } catch (_: Exception) {
                try {
                    LocalDate.parse(value.substring(0, 10))
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private fun documentToEventResponse(document: Document): EventResponse {
        val locationDocument = document.get("location", Document::class.java) ?: Document()
        return EventResponse(
            id = documentIdToString(document.get("_id")) ?: "",
            title = documentString(document.get("title")),
            category = documentString(document.get("category")).ifBlank { "other" },
            price = (document.get("price") as? Number)?.toInt() ?: 0,
            description = documentString(document.get("description")),
            location = EventLocationResponse(
                city = locationDocument.get("city")?.let { documentString(it) }?.ifBlank { null },
                address = documentString(locationDocument.get("address")),
            ),
            createdAt = documentString(document.get("created_at")),
            createdBy = documentIdToString(document.get("created_by")) ?: "",
            startedAt = documentString(document.get("started_at")),
            finishedAt = documentString(document.get("finished_at")),
        )
    }
}