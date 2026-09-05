package ua.starky.audiohunter.download

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.scheduler.Requirements
import ua.starky.audiohunter.data.provider.Http
import java.io.File
import java.util.concurrent.Executors

/**
 * Одно хранилище скачанного на всё приложение: и загрузчик, и плеер смотрят в один кэш.
 * Благодаря этому «играть офлайн» — это не отдельная ветка кода, а другой DataSource.
 */
@OptIn(UnstableApi::class)
object DownloadModule {

    private const val CACHE_DIR = "audiobooks"

    @Volatile private var cacheInstance: SimpleCache? = null
    @Volatile private var databaseProviderInstance: DatabaseProvider? = null
    @Volatile private var downloadManagerInstance: DownloadManager? = null

    fun databaseProvider(context: Context): DatabaseProvider =
        databaseProviderInstance ?: synchronized(this) {
            databaseProviderInstance ?: StandaloneDatabaseProvider(context.applicationContext)
                .also { databaseProviderInstance = it }
        }

    /** Кэш без вытеснения: скачанное удаляет только пользователь. */
    fun cache(context: Context): SimpleCache = cacheInstance ?: synchronized(this) {
        cacheInstance ?: SimpleCache(
            File(context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir, CACHE_DIR),
            NoOpCacheEvictor(),
            databaseProvider(context),
        ).also { cacheInstance = it }
    }

    fun httpDataSourceFactory(): DataSource.Factory =
        OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT)

    fun upstreamFactory(context: Context): DataSource.Factory =
        DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory())

    fun downloadManager(context: Context): DownloadManager =
        downloadManagerInstance ?: synchronized(this) {
            downloadManagerInstance ?: DownloadManager(
                context.applicationContext,
                databaseProvider(context),
                cache(context),
                httpDataSourceFactory(),
                Executors.newFixedThreadPool(3),
            ).apply {
                maxParallelDownloads = 3
                requirements = Requirements(Requirements.NETWORK)
            }.also { downloadManagerInstance = it }
        }

    /** Читает уже скачанное и ничего не тянет из сети. */
    fun cacheOnlyFactory(context: Context): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context))
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)

    /** Играет из кэша, чего нет — берёт из сети и попутно кэширует. */
    fun cacheAndNetworkFactory(context: Context): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstreamFactory(context))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun buildRequest(chapterId: String, url: String): DownloadRequest =
        DownloadRequest.Builder(chapterId, android.net.Uri.parse(url)).build()
}
