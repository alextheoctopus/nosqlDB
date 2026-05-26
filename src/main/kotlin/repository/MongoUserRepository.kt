import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters.and
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.Filters.regex
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import org.bson.Document
import org.bson.conversions.Bson
import org.bson.types.ObjectId

class MongoUserRepository(database: MongoDatabase) {
    private val collection: MongoCollection<Document> = database.getCollection("users")

    fun ensureIndexes() {
        collection.createIndex(ascending("username"), IndexOptions().unique(true))
    }

    fun createUser(fullName: String, username: String, passwordHash: String): String? {
        val document = Document()
            .append("full_name", fullName)
            .append("username", username)
            .append("password_hash", passwordHash)

        return try {
            collection.insertOne(document)
            document.getObjectId("_id").toHexString()
        } catch (_: MongoWriteException) {
            null
        }
    }

    fun findByUsername(username: String): UserRecord? {
        val doc = collection.find(eq("username", username)).firstOrNull() ?: return null
        return UserRecord(
            id = documentIdToString(doc.get("_id")) ?: return null,
            passwordHash = doc.getString("password_hash")
        )
    }

    fun findUserIdByUsername(username: String): String? {
        val doc = collection.find(eq("username", username)).firstOrNull() ?: return null
        return documentIdToString(doc.get("_id"))
    }

    fun findPublicById(id: ObjectId): PublicUserResponse? {
        val doc = collection.find(eq("_id", id)).firstOrNull() ?: return null
        return PublicUserResponse(
            id = documentIdToString(doc.get("_id")) ?: return null,
            fullName = documentString(doc.get("full_name")),
            username = documentString(doc.get("username")),
        )
    }

    fun existsById(id: ObjectId): Boolean =
        collection.find(eq("_id", id)).limit(1).firstOrNull() != null

    fun findUsers(id: ObjectId?, name: String?, limit: Int?, offset: Int?): List<PublicUserResponse> {
        val filters = mutableListOf<Bson>()
        if (id != null) {
            filters += eq("_id", id)
        }
        if (!name.isNullOrBlank()) {
            filters += regex("full_name", ".*${Regex.escape(name)}.*", "i")
        }

        val filter = when (filters.size) {
            0 -> Document()
            1 -> filters.first()
            else -> and(filters)
        }

        val iterable = collection.find(filter)
            .sort(ascending("_id"))
            .skip(offset ?: 0)
            .let { if (limit != null) it.limit(limit) else it }

        return iterable.map {
            PublicUserResponse(
                id = documentIdToString(it.get("_id")) ?: "",
                fullName = documentString(it.get("full_name")),
                username = documentString(it.get("username")),
            )
        }.toList()
    }
}

