import org.bson.types.ObjectId

class EventReactionService(
    private val eventRepository: MongoEventRepository,
    private val reactionRepository: CassandraEventReactionRepository,
    private val reactionCache: RedisEventReactionCache,
) {
    fun setReaction(eventId: ObjectId, userId: String, likeValue: Int): Boolean {
        val eventTitle = eventRepository.findEventTitleById(eventId) ?: return false

        reactionRepository.upsertReaction(
            eventId = eventId.toHexString(),
            userId = userId,
            likeValue = likeValue,
        )

        val eventIds = eventRepository.findEventIdsByExactTitle(eventTitle)
        val result = reactionRepository.countReactions(eventIds)
        reactionCache.put(eventTitle, result.reactions)

        return true
    }

    fun withReactions(event: EventResponse): EventResponse =
        event.copy(reactions = getReactions(event.title))

    fun withReactions(events: List<EventResponse>): List<EventResponse> =
        events.map { event -> withReactions(event) }

    private fun getReactions(title: String): ReactionsResponse {
        val cached = reactionCache.get(title)
        if (cached != null) {
            return cached
        }

        val eventIds = eventRepository.findEventIdsByExactTitle(title)
        val result = reactionRepository.countReactions(eventIds)

        if (result.hasRows) {
            reactionCache.put(title, result.reactions)
        }

        return result.reactions
    }
}