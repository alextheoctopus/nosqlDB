import org.bson.types.ObjectId
import java.time.LocalDate

data class EventSearchQuery(
    val id: ObjectId? = null,
    val title: String? = null,
    val category: String? = null,
    val priceFrom: Int? = null,
    val priceTo: Int? = null,
    val city: String? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    val createdByUserId: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)