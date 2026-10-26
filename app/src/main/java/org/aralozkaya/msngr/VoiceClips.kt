package org.aralozkaya.msngr

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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

/** Recording stays in memory and stops when its dialog or activity leaves the foreground. */
private class VoiceRecorder {
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var stopping = false

    fun stop() {
        stopping = true
        runCatching { recorder?.stop() }
    }

    suspend fun record(onProgress: (Int) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "A microphone is not available." }
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 3_200))
        stopping = false
        recorder = audio
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be opened." }
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone could not be started." }
            val bytes = ByteArrayOutputStream()
            val block = ByteArray(640)
            val deadline = SystemClock.elapsedRealtime() + MsnVoiceClip.MAX_DURATION_MILLIS
            while (!stopping && currentCoroutineContext().isActive && bytes.size() < 480_000 &&
                SystemClock.elapsedRealtime() < deadline) {
                val count = audio.read(block, 0, minOf(block.size, 480_000 - bytes.size()), AudioRecord.READ_BLOCKING)
                if (count < 0) {
                    check(stopping) { "Microphone recording failed." }
                    break
                }
                if (count > 0) bytes.write(block, 0, count - count % 2)
                else delay(10)
                withContext(Dispatchers.Main) { onProgress(bytes.size() / 32) }
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
    suspend fun play(clip: VoiceClip) = withContext(Dispatchers.IO) {
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
            while (current === track && currentCoroutineContext().isActive && track.playbackHeadPosition < pcm.size / 2) delay(30)
        } finally {
            synchronized(this@VoicePlayback) { if (current === track) current = null }
            runCatching { track.stop() }
            track.release()
        }
    }
}

@Composable
internal fun VoiceClipPlayer(clip: VoiceClip, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var job by remember(clip) { mutableStateOf<Job?>(null) }
    var error by remember(clip) { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(clip, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) job?.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel() }
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (job?.isActive == true) { job?.cancel(); job = null }
                else job = scope.launch {
                    error = null
                    try { VoicePlayback.play(clip) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Voice clip could not be played." }
                    finally { if (job === currentCoroutineContext()[Job]) job = null }
                }
            }) { Icon(if (job?.isActive == true) Icons.Default.Stop else Icons.Default.PlayArrow,
                contentDescription = if (job?.isActive == true) "Stop voice clip" else "Play voice clip") }
            Column {
                Text("Voice clip", style = MaterialTheme.typography.labelLarge)
                Text(String.format(Locale.ROOT, "%.1f s", clip.durationMillis / 1_000f), style = MaterialTheme.typography.bodySmall)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun VoiceClipMessage(message: ChatMessage) {
    when {
        message.voiceClip != null -> VoiceClipPlayer(message.voiceClip)
        message.isVoiceClipPending -> Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp))
            Text("Downloading voice clip…", style = MaterialTheme.typography.bodySmall)
        }
        else -> Text(message.voiceClipTransferError ?: "Voice clip unavailable", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun VoiceClipDialog(onDismiss: () -> Unit, onSend: suspend (VoiceClip) -> Result<Unit>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder() }
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var clip by remember { mutableStateOf<VoiceClip?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            recorder.stop(); recordingJob?.cancel(); onDismiss()
        } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); recorder.stop(); recordingJob?.cancel() }
    }
    fun start() {
        if (recording || busy) return
        clip = null; error = null; elapsed = 0; recording = true
        recordingJob = scope.launch {
            try {
                val pcm = recorder.record { elapsed = it }
                recording = false; busy = true
                check(pcm.isNotEmpty()) { "Record a voice clip first." }
                clip = withContext(Dispatchers.Default) { MsnVoiceClip.encode(pcm) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Voice clip recording failed." }
            finally { recording = false; busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else error = "Allow microphone access to record a voice clip."
    }
    AlertDialog(onDismissRequest = { recorder.stop(); onDismiss() }, title = { Text("Voice clip") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Record up to 15 seconds. Voice clips work in one-to-one chats.")
            if (recording) Text(String.format(Locale.ROOT, "Recording %.1f / 15 s", elapsed / 1_000f))
            clip?.let { VoiceClipPlayer(it) }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(enabled = !busy, onClick = {
                if (recording) recorder.stop()
                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text(if (recording) "Stop recording" else if (clip != null) "Record again" else "Start recording") }
        }
    }, confirmButton = {
        TextButton(enabled = clip != null && !recording && !busy, onClick = {
            val ready = clip ?: return@TextButton
            busy = true
            scope.launch {
                val result = onSend(ready)
                busy = false
                if (result.isSuccess) onDismiss() else error = result.exceptionOrNull()?.message ?: "Voice clip could not be sent."
            }
        }) { Text("Send voice clip") }
    }, dismissButton = { TextButton(onClick = { recorder.stop(); onDismiss() }) { Text("Cancel") } })
}
