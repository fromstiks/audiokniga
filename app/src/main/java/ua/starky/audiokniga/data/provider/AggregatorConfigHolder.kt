package ua.starky.audiokniga.data.provider

/**
 * Адрес своего сервера-агрегатора (см. server/ в репозитории) меняется через настройки,
 * а читает его провайдер на каждый поиск — без похода в DataStore из suspend-функции.
 * Пусто = источник выключен.
 */
object AggregatorConfigHolder {
    @Volatile var baseUrl: String = ""
}
