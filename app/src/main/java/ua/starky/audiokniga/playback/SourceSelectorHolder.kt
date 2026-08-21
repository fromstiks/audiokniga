package ua.starky.audiokniga.playback

import android.content.Context

/**
 * Селектор источника один на процесс: его меняет интерфейс, а читает плеер в сервисе.
 */
object SourceSelectorHolder {

    @Volatile private var instance: SourceSelector? = null

    fun get(context: Context): SourceSelector = instance ?: synchronized(this) {
        instance ?: SourceSelector(context.applicationContext).also { instance = it }
    }
}
