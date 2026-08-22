package ua.starky.audiokniga.data.provider

import ua.starky.audiokniga.data.model.CustomSource

/**
 * Реестр источников: встроенные плюс добавленные пользователем.
 *
 * Встроенные заданы кодом, пользовательские приходят из базы и могут меняться
 * на ходу, поэтому список пересобирается через [setCustomSources].
 */
object ProviderRegistry {

    private val builtIn: List<AudiobookProvider> = listOf(
        ArchiveOrgProvider(),
        LibriVoxProvider(),
        PodcastProvider(),
        RssProvider(),
    )

    @Volatile
    private var custom: List<CustomProvider> = emptyList()

    fun setCustomSources(sources: List<CustomSource>) {
        custom = sources.filter { it.enabled }.map { CustomProvider(it) }
    }

    val all: List<AudiobookProvider> get() = builtIn + custom

    val customProviders: List<CustomProvider> get() = custom

    /**
     * Источники, по которым имеет смысл искать словом. Ленту по ссылке обрабатывает
     * [RssProvider] отдельно — ему на вход нужен адрес, а не запрос.
     */
    val searchable: List<AudiobookProvider>
        get() = all.filter { it.id != RssProvider.ID }

    fun byId(providerId: String): AudiobookProvider =
        all.firstOrNull { it.id == providerId }
            ?: throw ProviderException(
                if (CustomProvider.isCustom(providerId)) "Этот источник удалён из настроек"
                else "Источник «$providerId» не подключён"
            )

    fun forBook(bookId: String): AudiobookProvider = byId(providerIdOf(bookId))

    fun displayName(providerId: String): String =
        all.firstOrNull { it.id == providerId }?.displayName
            ?: if (CustomProvider.isCustom(providerId)) "Свой источник" else providerId
}
