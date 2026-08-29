package ua.starky.audiokniga.playback

/**
 * Чем закончится прослушивание перед сном.
 *
 * «До конца главы» привязан к той главе, на которой таймер включили: если её
 * переключить вручную, книга остановится — считается, что глава дослушана.
 */
sealed interface SleepPlan {
    data object Off : SleepPlan

    data class After(val minutes: Int) : SleepPlan

    data object EndOfChapter : SleepPlan

    companion object {
        /** Готовые варианты в окне выбора. */
        val presets: List<Int> = listOf(5, 10, 15, 20, 30, 45, 60)
    }
}

data class SleepTimerState(
    val plan: SleepPlan = SleepPlan.Off,
    val remainingMs: Long = 0L,
) {
    val armed: Boolean get() = plan != SleepPlan.Off

    /** Подпись на кнопке: пока идёт отсчёт — сам отсчёт, он и есть главное. */
    val label: String
        get() = when (plan) {
            SleepPlan.Off -> "Таймер сна"
            SleepPlan.EndOfChapter -> "До конца главы · " + formatRemaining(remainingMs)
            is SleepPlan.After -> formatRemaining(remainingMs)
        }
}

/** Остаток всегда в виде мм:сс — под сон часы не нужны, а секунды успокаивают. */
fun formatRemaining(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
