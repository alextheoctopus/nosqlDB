import com.mongodb.MongoWriteException
import com.mongodb.client.model.Filters.eq
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes.ascending
import com.mongodb.kotlin.client.MongoCollection
import com.mongodb.kotlin.client.MongoDatabase
import org.bson.Document

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
            id = doc.getObjectId("_id").toHexString(),
            passwordHash = doc.getString("password_hash")
        )
    }
}
