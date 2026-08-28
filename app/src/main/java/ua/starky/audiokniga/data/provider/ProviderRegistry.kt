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

    /** Встроенные источники, выключенные пользователем в настройках. */
    @Volatile
    private var disabledBuiltIn: Set<String> = emptySet()

    /**
     * Выключенные источники остаются в реестре — иначе книга, добавленная из такого
     * источника, перестала бы открываться. Выключение убирает источник только из поиска.
     */
    fun setCustomSources(sources: List<CustomSource>) {
        custom = sources.map { CustomProvider(it) }
    }

    fun setDisabledBuiltIn(ids: Set<String>) {
        disabledBuiltIn = ids
    }

    val all: List<AudiobookProvider> get() = builtIn + custom

    val builtInProviders: List<AudiobookProvider> get() = builtIn

    val customProviders: List<CustomProvider> get() = custom

    fun isEnabled(provider: AudiobookProvider): Boolean = when (provider) {
        is CustomProvider -> provider.source.enabled
        else -> provider.id !in disabledBuiltIn
    }

    val enabled: List<AudiobookProvider> get() = all.filter { isEnabled(it) }

    /**
     * Источники, по которым имеет смысл искать словом. Ленту по ссылке обрабатывает
     * [RssProvider] отдельно — ему на вход нужен адрес, а не запрос.
     */
    val searchable: List<AudiobookProvider>
        get() = enabled.filter { it.id != RssProvider.ID }

    fun byId(providerId: String): AudiobookProvider =
        all.firstOrNull { it.id == providerId }
            ?: throw ProviderException(
                if (CustomProvider.isCustom(providerId)) "Этот источник удалён из настроек"
                else "Источник «$providerId» не подключён"
            )

    fun forBook(bookId: String): AudiobookProvider = byId(providerIdOf(bookId))

    fun displayName(providerId: String): String = when {
        providerId == LOCAL_ID -> "С устройства"
        else -> all.firstOrNull { it.id == providerId }?.displayName
            ?: if (CustomProvider.isCustom(providerId)) "Свой источник" else providerId
    }

    /** Книги с самого телефона провайдера не имеют: их главы уже лежат в базе. */
    const val LOCAL_ID = "local"
}
