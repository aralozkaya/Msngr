package org.aralozkaya.msngr

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.data.EmotionSoundService
import org.aralozkaya.msngr.protocol.EmotionSound

/** A downloaded candidate stays in memory until the user explicitly keeps it. */
@Composable
internal fun RandomEmotionSoundDialog(
    category: Int,
    onDismiss: () -> Unit,
    onKept: (EmotionSound) -> Unit,
    service: EmotionSoundService? = null,
) {
    val context = LocalContext.current
    val player = rememberEmotionSoundController()
    val scope = rememberCoroutineScope()
    val soundService = remember(player.server, service) { service ?: EmotionSoundService(player.server) }
    var language by remember { mutableIntStateOf(0) }
    var candidate by remember { mutableStateOf<Pair<EmotionSound, ByteArray>?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = downloading || saving

    fun download() {
        player.stop()
        candidate = null
        downloading = true
        error = null
        scope.launch {
            try {
                player.checkAccount()
                val result = soundService.random(if (category == 0) (1..5).random() else category, language)
                player.checkAccount()
                candidate = result
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "The sound could not be downloaded." }
            finally { downloading = false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.testTag("emotion_sound_random_dialog"),
        title = { Text("Random sound") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Find a sound, preview it, then choose whether to keep it.")
                Text("Category: ${emotionSoundCategories[category]}", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Language", Modifier.weight(1f))
                    EmotionSoundDropdown(language, emotionSoundLanguages, "Sound language", "emotion_sound_language",
                        enabled = !busy, onSelected = {
                            player.stop(); language = it; candidate = null; error = null
                        })
                }
                OutlinedButton(onClick = ::download, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("emotion_sound_random_fetch")) {
                    Text(if (candidate == null) "Download" else "Try another")
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                candidate?.let { (sound, bytes) ->
                    Text(sound.name.messengerPlusPlainText(), style = MaterialTheme.typography.titleMedium)
                    Text("#${sound.id}", style = MaterialTheme.typography.labelSmall)
                    val active = player.activeId == sound.id
                    OutlinedButton(
                        onClick = { if (active) player.stop() else player.play(sound, previewBytes = bytes) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("emotion_sound_random_preview"),
                    ) {
                        Icon(if (active) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (active) "Stop" else "Preview")
                    }
                }
                (error ?: player.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = candidate != null && !busy, onClick = {
                val (sound, bytes) = candidate ?: return@TextButton
                player.stop()
                saving = true
                error = null
                scope.launch {
                    try {
                        player.checkAccount()
                        withContext(Dispatchers.IO) { context.emotionSoundStore().put(player.account!!, player.server, sound, bytes, save = true) }
                        player.checkAccount()
                        onKept(sound)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { error = failure.message ?: "The sound could not be saved." }
                    finally { saving = false }
                }
            }, modifier = Modifier.testTag("emotion_sound_random_keep")) { Text("Keep") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(if (candidate == null) "Cancel" else "Discard") } },
    )
}
