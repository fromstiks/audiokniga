package ua.starky.audiokniga.playback

import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import ua.starky.audiokniga.R

/**
 * Фоновое воспроизведение с уведомлением и управлением с гарнитуры.
 *
 * Уведомление — это же и управление на заблокированном экране. По умолчанию Media3
 * ставит там «предыдущий трек / пауза / следующий трек», но для книги листать главы
 * целиком почти всегда не то, что нужно: нужна перемотка на десяток-другой секунд.
 * Поэтому кнопки заменены на свои, с тем же шагом, что и в самом приложении.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val selector = SourceSelectorHolder.get(this)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(selector))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        session = MediaSession.Builder(this, player)
            .setCallback(SkipCallback(player))
            .setCustomLayout(buildLayout())
            .build()
    }

    /** Подписи пересобираются под текущий шаг: «20» и «30» — разные кнопки. */
    private fun buildLayout(): ImmutableList<CommandButton> {
        val seconds = SkipSettings.seconds
        return ImmutableList.of(
            CommandButton.Builder()
                .setDisplayName("Назад $seconds с")
                .setIconResId(R.drawable.ic_media_rewind)
                .setSessionCommand(SessionCommand(ACTION_REWIND, Bundle.EMPTY))
                .build(),
            CommandButton.Builder()
                .setDisplayName("Вперёд $seconds с")
                .setIconResId(R.drawable.ic_media_forward)
                .setSessionCommand(SessionCommand(ACTION_FORWARD, Bundle.EMPTY))
                .build(),
        )
    }

    private inner class SkipCallback(private val player: Player) : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_REWIND, Bundle.EMPTY))
                .add(SessionCommand(ACTION_FORWARD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(buildLayout())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            val step = SkipSettings.skipMs
            when (customCommand.customAction) {
                ACTION_REWIND -> player.seekTo((player.currentPosition - step).coerceAtLeast(0L))
                ACTION_FORWARD -> player.seekTo(player.currentPosition + step)
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_REWIND = "ua.starky.audiokniga.REWIND"
        const val ACTION_FORWARD = "ua.starky.audiokniga.FORWARD"
    }
}
