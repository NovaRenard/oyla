package kz.oyla.app.ui.session

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

class ExerciseAudioPlayer(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    fun play(assetKey: String?, fallbackText: String) {
        val resourceId = assetKey?.let { context.resources.getIdentifier(it, "raw", context.packageName) } ?: 0
        if (resourceId != 0) {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer.create(context, resourceId)?.also { player ->
                player.setOnCompletionListener { it.release(); if (mediaPlayer === it) mediaPlayer = null }
                player.start()
            }
            return
        }
        val player = tts ?: TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale("ru", "RU")
                tts?.speak(fallbackText, TextToSpeech.QUEUE_FLUSH, null, "exercise-instruction")
            }
        }.also { tts = it }
        if (ttsReady) player.speak(fallbackText, TextToSpeech.QUEUE_FLUSH, null, "exercise-instruction")
    }

    fun release() {
        mediaPlayer?.release(); mediaPlayer = null
        tts?.stop(); tts?.shutdown(); tts = null
    }
}

@Composable
fun rememberExerciseAudioPlayer(): ExerciseAudioPlayer {
    val context = LocalContext.current.applicationContext
    val player = remember(context) { ExerciseAudioPlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}
