@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.data.ChatMessage
import org.aralozkaya.msngr.protocol.MsnVoiceClip
import org.aralozkaya.msngr.protocol.VoiceClip
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Each capture owns its recorder, including stop requests made before the IO coroutine starts. */
internal class VoiceRecorder {
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var stopping = false

    fun stop() {
        stopping = true
        runCatching { recorder?.stop() }
    }

    suspend fun record(onProgress: (Int, Float) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        if (stopping) return@withContext ByteArray(0)
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "A microphone is not available." }
        val audio = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, 16_000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 3_200))
        } catch (e: SecurityException) {
            throw IllegalStateException("Allow microphone access to record a voice clip.", e)
        }
        recorder = audio
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be opened." }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not be started." }
            val bytes = ByteArrayOutputStream()
            val block = ByteArray(640)
            val deadline = SystemClock.elapsedRealtime() + MsnVoiceClip.MAX_DURATION_MILLIS
            var lastProgress = 0
            while (!stopping && currentCoroutineContext().isActive && bytes.size() < 480_000 &&
                SystemClock.elapsedRealtime() < deadline) {
                val count = audio.read(block, 0, minOf(block.size, 480_000 - bytes.size()), AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    check(stopping) { "Microphone recording failed." }
                    break
                }
                if (count > 0) bytes.write(block, 0, count - count % 2)
                else delay(10)
                val elapsed = bytes.size() / 32
                if (elapsed - lastProgress >= 40) {
                    lastProgress = elapsed
                    val level = voiceClipLevel(block, count.coerceAtLeast(0))
                    withContext(Dispatchers.Main) { onProgress(elapsed, level) }
                }
            }
            bytes.toByteArray()
        } finally {
            recorder = null
            runCatching { audio.stop() }
            audio.release()
        }
    }
}

/** Only the selected bubble plays. No automatic playback of received audio. */
private object VoicePlayback {
    @Volatile private var current: AudioTrack? = null
    @Synchronized fun stop() { runCatching { current?.pause() }; current = null }
    suspend fun play(clip: VoiceClip, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val pcm = MsnVoiceClip.decode(clip)
        val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(pcm.size, 640)).setTransferMode(AudioTrack.MODE_STATIC).build()
        try {
            synchronized(this@VoicePlayback) { stop(); current = track }
            check(track.write(pcm, 0, pcm.size) == pcm.size) { "Audio playback failed." }
            track.play()
            while (current === track && currentCoroutineContext().isActive && track.playbackHeadPosition < pcm.size / 2) {
                val progress = track.playbackHeadPosition.toFloat() / (pcm.size / 2)
                withContext(Dispatchers.Main) { onProgress(progress.coerceIn(0f, 1f)) }
                delay(30)
            }
        } finally {
            synchronized(this@VoicePlayback) { if (current === track) current = null }
            runCatching { track.stop() }
            track.release()
        }
    }
}

@Composable
internal fun VoiceClipPlayer(clip: VoiceClip, modifier: Modifier = Modifier, compact: Boolean = false) {
    val scope = rememberCoroutineScope()
    var job by remember(clip) { mutableStateOf<Job?>(null) }
    var error by remember(clip) { mutableStateOf<String?>(null) }
    var progress by remember(clip) { mutableFloatStateOf(0f) }
    val levels by produceState(emptyList<Float>(), clip) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val pcm = MsnVoiceClip.decode(clip)
                List(32) { index ->
                    val start = (pcm.size / 2 * index / 32) * 2
                    val end = (pcm.size / 2 * (index + 1) / 32) * 2
                    voiceClipLevel(pcm.copyOfRange(start, end))
                }
            }.getOrDefault(emptyList())
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(clip, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) job?.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel() }
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                FilledIconButton(shapes = IconButtonDefaults.shapes(), onClick = {
                    if (job?.isActive == true) { job?.cancel(); job = null }
                    else job = scope.launch {
                        error = null
                        progress = 0f
                        try { VoicePlayback.play(clip) { progress = it } }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { error = "Voice clip could not be played." }
                        finally {
                            progress = 0f
                            if (job === currentCoroutineContext()[Job]) job = null
                        }
                    }
                }, colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ), modifier = Modifier.size(if (compact) 36.dp else 48.dp)) {
                    Icon(if (job?.isActive == true) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (job?.isActive == true) "Stop voice clip" else "Play voice clip",
                        modifier = Modifier.size(if (compact) 20.dp else 24.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                VoiceWaveform(levels, Modifier.fillMaxWidth().height(24.dp), progress)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Voice clip", style = MaterialTheme.typography.labelMedium)
                    Text(String.format(Locale.ROOT, "%.1f s", clip.durationMillis / 1_000f),
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun VoiceClipMessage(message: ChatMessage) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
        modifier = Modifier.widthIn(min = 184.dp, max = 280.dp).padding(bottom = 6.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            when {
                message.voiceClip != null -> VoiceClipPlayer(message.voiceClip)
                message.isVoiceClipPending -> Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularWavyProgressIndicator(Modifier.size(20.dp))
                    Text("Downloading voice clip…", style = MaterialTheme.typography.bodySmall)
                }
                else -> Text(message.voiceClipTransferError ?: "Voice clip unavailable", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
