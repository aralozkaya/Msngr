@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.pages.components.MsngrLinearWavyProgressIndicator
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
    val resources = LocalResources.current
    val player = rememberEmotionSoundController()
    val scope = rememberCoroutineScope()
    val soundService = remember(player.server, service) {
        service ?: org.aralozkaya.msngr.protocol.MsnEmotionSound.server(player.server)?.let(::EmotionSoundService)
    }
    var language by remember { mutableIntStateOf(0) }
    var candidate by remember { mutableStateOf<Pair<EmotionSound, ByteArray>?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = downloading || saving
    val categoryOptions = localizedEmotionSoundCategories()
    val languageOptions = localizedEmotionSoundLanguages()
    val categoryLabel = candidate?.first?.let { sound ->
        localizedEmotionSoundCategory(sound.category)
    } ?: categoryOptions.getOrElse(category) { stringResource(R.string.common_unknown) }

    fun download() {
        val activeService = soundService ?: run {
            error = resources.getString(R.string.emotion_sound_not_configured)
            return
        }
        player.stop()
        candidate = null
        downloading = true
        error = null
        scope.launch {
            try {
                player.checkAccount()
                val result = activeService.random(if (category == 0) (1..5).random() else category, language)
                player.checkAccount()
                candidate = result
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: resources.getString(R.string.emotion_sound_download_failed) }
            finally { downloading = false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.testTag("emotion_sound_random_dialog"),
        title = { Text(stringResource(R.string.random_sound_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.random_sound_instructions))
                Text(stringResource(R.string.random_sound_category_format, categoryLabel), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.random_sound_language_label), Modifier.weight(1f))
                    EmotionSoundDropdown(language, languageOptions, stringResource(R.string.random_sound_language_desc), "emotion_sound_language",
                        enabled = !busy, onSelected = {
                            player.stop(); language = it; candidate = null; error = null
                        })
                }
                OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = ::download, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("emotion_sound_random_fetch")) {
                    Text(stringResource(if (candidate == null) R.string.common_download else R.string.random_sound_try_another))
                }
                if (busy) MsngrLinearWavyProgressIndicator(Modifier.fillMaxWidth())
                candidate?.let { (sound, bytes) ->
                    Text(localizedEmotionSoundName(sound.name).messengerPlusPlainText(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        numberSentenceResource(R.string.emotion_sound_id_before_number, sound.id, R.string.emotion_sound_id_after_number),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    val active = player.activeId == sound.id
                    OutlinedButton(
                        shapes = ButtonDefaults.shapes(),
                        onClick = { if (active) player.stop() else player.play(sound, previewBytes = bytes) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("emotion_sound_random_preview"),
                    ) {
                        Icon(if (active) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (active) R.string.common_stop else R.string.common_preview))
                    }
                }
                (error ?: player.error)?.let { Text(localizeUserMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(shapes = ButtonDefaults.shapes(), enabled = candidate != null && !busy, onClick = {
                val (sound, bytes) = candidate ?: return@Button
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
                    catch (failure: Exception) { error = failure.message ?: resources.getString(R.string.emotion_sound_save_failed) }
                    finally { saving = false }
                }
            }, modifier = Modifier.testTag("emotion_sound_random_keep")) { Text(stringResource(R.string.random_sound_keep)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(if (candidate == null) R.string.common_cancel else R.string.random_sound_discard)) } },
    )
}
