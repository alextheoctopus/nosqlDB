class RecommendationService(
    private val graphRepository: Neo4jRecommendationRepository,
    private val eventRepository: MongoEventRepository,
    private val cache: RedisRecommendationsCache,
) {
    fun getRecommendations(userId: String): RecommendationsResponse {
        val cached = cache.get(userId)
        if (cached != null) {
            return cached
        }

        val recommendedIds = graphRepository.findRecommendedEventIds(userId)
        val events = eventRepository.findEventsByIds(recommendedIds)

        val deduplicated = events
            .groupBy { it.title }
            .map { (_, sameTitleEvents) ->
                sameTitleEvents.minByOrNull { it.startedAt }!!
            }

        val response = RecommendationsResponse(deduplicated)
        cache.put(userId, response)

        return response
    }
}