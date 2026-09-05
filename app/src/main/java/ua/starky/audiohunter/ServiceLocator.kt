package ua.starky.audiohunter

import android.content.Context

/** Минимальная замена DI: приложение маленькое, отдельный фреймворк тут лишний. */
val Context.app: App get() = applicationContext as App
