package ua.starky.audiokniga.data.provider

/**
 * Реестр источников. Чтобы добавить сайт — допишите сюда его провайдер.
 */
object ProviderRegistry {

    private val providers: List<AudiobookProvider> = listOf(
        LibriVoxProvider(),
        ArchiveOrgProvider(),
        RssProvider(),
        AggregatorApiProvider(),
    )

    /** Источники, по которым имеет смысл искать по слову. RSS ждёт ссылку, поэтому он отдельно. */
    val searchable: List<AudiobookProvider> = providers.filter { it.id != RssProvider.ID }

    val all: List<AudiobookProvider> get() = providers

    fun byId(providerId: String): AudiobookProvider =
        providers.firstOrNull { it.id == providerId }
            ?: throw ProviderException("Источник «$providerId» не подключён")

    fun forBook(bookId: String): AudiobookProvider = byId(providerIdOf(bookId))

    fun displayName(providerId: String): String =
        providers.firstOrNull { it.id == providerId }?.displayName ?: providerId
}
