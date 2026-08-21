package ua.starky.audiokniga

import android.content.Context

/** Минимальная замена DI: приложение маленькое, отдельный фреймворк тут лишний. */
val Context.app: App get() = applicationContext as App
