@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.protocol.MsnVoiceClip
import org.aralozkaya.msngr.protocol.VoiceClip
import java.util.Locale

/** The action anchor stays mounted while the composer changes, so a held pointer is never lost. */
@Composable
internal fun VoiceClipComposer(
    enabled: Boolean,
    voiceButtonVisible: Boolean = true,
    onSend: suspend (VoiceClip) -> Result<Unit>,
    onRecordingStarted: () -> Unit = {},
    onRecordingFinished: () -> Unit = {},
    textComposer: @Composable () -> Unit = {},
    sendButton: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var recorder by remember { mutableStateOf<VoiceRecorder?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    var recording by remember { mutableStateOf(false) }
    var locked by remember { mutableStateOf(false) }
    var lockHovered by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var levels by remember { mutableStateOf(emptyList<Float>()) }
    var clip by remember { mutableStateOf<VoiceClip?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var lockCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var anchor by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val active = recording || processing || clip != null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lockHoverPadding = with(LocalDensity.current) { 20.dp.toPx() }
    val motion = MaterialTheme.motionScheme

    val lockSpace by animateDpAsState(if (recording) 76.dp else 0.dp,
        motion.defaultSpatialSpec(), label = "voiceLockSpace")
    val editorAlpha by animateFloatAsState(if (active) 0f else 1f,
        motion.fastEffectsSpec(), label = "voiceEditorAlpha")
    val microphoneScale by animateFloatAsState(if (recording) 1.08f else 1f,
        motion.fastSpatialSpec(), label = "voiceMicrophoneScale")
    val microphoneElevation by animateDpAsState(if (recording) 4.dp else 0.dp,
        motion.fastSpatialSpec(), label = "voiceMicrophoneElevation")
    val lockScale by animateFloatAsState(when {
        lockHovered -> 1.16f
        locked -> 1.06f
        else -> 1f
    }, motion.fastSpatialSpec(), label = "voiceLockScale")
    val lockElevation by animateDpAsState(when {
        lockHovered -> 12.dp
        locked -> 8.dp
        else -> 6.dp
    }, motion.fastSpatialSpec(), label = "voiceLockElevation")
    val lockColor by animateColorAsState(when {
        locked -> MaterialTheme.colorScheme.primary
        lockHovered -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }, motion.fastEffectsSpec(), label = "voiceLockColor")
    val lockContentColor by animateColorAsState(when {
        locked -> MaterialTheme.colorScheme.onPrimary
        lockHovered -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }, motion.fastEffectsSpec(), label = "voiceLockContentColor")
    // Retain the preview during its native exit transition after send or delete clears the draft.
    var exitingClip by remember { mutableStateOf<VoiceClip?>(null) }
    SideEffect { if (active) exitingClip = clip }
    val displayedClip = if (active) clip else exitingClip

    fun finishRecording() {
        if (recording) {
            recording = false
            lockHovered = false
            onRecordingFinished()
        }
    }

    fun discard() {
        recorder?.stop()
        recordingJob?.cancel()
        recordingJob = null
        recorder = null
        finishRecording()
        processing = false
        holding = false
        locked = false
        lockHovered = false
        clip = null
        levels = emptyList()
        error = null
    }

    fun stop() {
        if (!recording) return
        finishRecording()
        locked = false
        processing = true
        recorder?.stop()
    }

    fun start(handsFree: Boolean) {
        if (!enabled || active || sending) return
        clip = null
        error = null
        elapsed = 0
        levels = emptyList()
        recording = true
        locked = handsFree
        onRecordingStarted()
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        val capture = VoiceRecorder()
        recorder = capture
        recordingJob = scope.launch {
            try {
                val pcm = capture.record { time, level ->
                    elapsed = time
                    levels = (levels + level).takeLast(40)
                }
                finishRecording()
                locked = false
                processing = true
                check(pcm.isNotEmpty()) { "Hold the microphone to record a voice clip." }
                clip = withContext(Dispatchers.Default) { MsnVoiceClip.encode(pcm) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Voice clip recording failed."
            } finally {
                if (recorder === capture) {
                    finishRecording()
                    locked = false
                    processing = false
                    recorder = null
                }
            }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // The permission sheet interrupts the press. A fresh hold starts capture after granting access.
        error = if (granted) null else "Allow microphone access to record a voice clip."
    }
    fun requestStart(handsFree: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start(handsFree)
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun send() {
        val ready = clip ?: return
        if (sending) return
        sending = true
        error = null
        scope.launch {
            try {
                val result = onSend(ready)
                if (result.isSuccess) discard()
                else error = result.exceptionOrNull()?.message ?: "Voice clip could not be sent."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Voice clip could not be sent."
            } finally { sending = false }
        }
    }

    val currentDiscard by rememberUpdatedState { discard() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !sending) currentDiscard()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); currentDiscard() }
    }
    LaunchedEffect(enabled, voiceButtonVisible) { if (!enabled || !voiceButtonVisible) discard() }
    BackHandler(active && !sending) { discard() }

    val beginPress by rememberUpdatedState {
        if (!active && !sending) {
            holding = true
            requestStart(false)
            true
        } else false
    }
    val updateLockDrag by rememberUpdatedState<(Offset?) -> Unit> { position ->
        // The lock enters from behind the microphone, so its animated bounds briefly overlap the held finger.
        // Only a drag that has left the microphone upwards may hover or engage the lock.
        val microphoneTop = anchor?.takeIf { it.isAttached }?.boundsInRoot()?.top
        val bounds = lockCoordinates?.takeIf { it.isAttached }?.boundsInRoot()
        val target = position?.takeIf { recording && microphoneTop != null && it.y < microphoneTop }
        lockHovered = target != null && bounds?.inflate(lockHoverPadding)?.contains(target) == true
        if (target != null && !locked && bounds?.contains(target) == true) {
            locked = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    val releasePress by rememberUpdatedState {
        holding = false
        lockHovered = false
        if (recording && !locked) stop()
    }
    val primaryAction by rememberUpdatedState {
        when {
            clip != null -> send()
            recording -> stop()
            !processing && !sending -> requestStart(true) // Accessible click starts hands-free capture.
        }
    }
    val actionDescription = when {
        clip != null -> "Send voice clip"
        recording && locked -> "Stop recording"
        recording -> "Recording voice clip"
        else -> "Record voice clip"
    }
    val canPress by rememberUpdatedState(enabled && !processing && !sending)

    Column(Modifier.fillMaxWidth()) {
        // Space belongs to the composer so the floating lock also accepts ordinary taps.
        Box(Modifier.fillMaxWidth()) {
            androidx.compose.animation.AnimatedVisibility(
                visible = recording,
                enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.defaultSpatialSpec(), initialScale = 0.7f) +
                    slideInVertically(motion.defaultSpatialSpec()) { it / 2 },
                exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 16.dp)
                    .then(if (!recording) Modifier.clearAndSetSemantics {} else Modifier),
                label = "voiceLockVisibility",
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = {
                            locked = !locked
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (!locked && !holding) stop()
                        },
                        shape = CircleShape,
                        enabled = recording,
                        color = lockColor,
                        contentColor = lockContentColor,
                        shadowElevation = lockElevation.coerceAtLeast(0.dp),
                        modifier = Modifier.size(48.dp).onGloballyPositioned { lockCoordinates = it }
                            .semantics {
                                contentDescription = if (locked) "Unlock recording" else "Lock recording"
                                stateDescription = if (locked) "Recording locked" else "Drag up to lock recording"
                            }
                            .graphicsLayer { scaleX = lockScale; scaleY = lockScale },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Crossfade(locked, animationSpec = motion.fastEffectsSpec(), label = "voiceLockIcon") { isLocked ->
                                Icon(if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = null)
                            }
                        }
                    }
                    AnimatedVisibility(!locked, enter = fadeIn(motion.fastEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = lockSpace.coerceAtLeast(0.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(Modifier.weight(1f).animateContentSize(motion.defaultSpatialSpec(), alignment = Alignment.BottomEnd)) {
                    // Retain the focused native editor so the IME stays put throughout a hold.
                    // Its content is hidden behind the recording/review surface.
                    Box((if (active) Modifier.matchParentSize().clearAndSetSemantics {} else Modifier.fillMaxWidth())
                        .graphicsLayer { alpha = editorAlpha }) {
                        textComposer()
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = active,
                        enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.96f),
                        exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.96f),
                        modifier = Modifier.testTag("voice-recording-bar")
                            .then(if (!active) Modifier.clearAndSetSemantics {} else Modifier),
                        label = "voiceRecordingBarVisibility",
                    ) {
                        Surface(shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                            AnimatedContent(
                                targetState = displayedClip,
                                contentKey = { it != null },
                                transitionSpec = {
                                    (fadeIn(motion.fastEffectsSpec()) + slideInVertically(motion.fastSpatialSpec()) { it / 4 })
                                        .togetherWith(fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { -it / 4 })
                                },
                                label = "voiceRecordingToReview",
                            ) { readyClip ->
                                Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (readyClip != null) {
                                        IconButton(onClick = { discard() }, enabled = active && !sending) {
                                            Icon(Icons.Default.DeleteOutline, "Delete voice clip", tint = MaterialTheme.colorScheme.error)
                                        }
                                        VoiceClipPlayer(readyClip, Modifier.weight(1f), compact = true)
                                    } else {
                                        Box(Modifier.padding(4.dp).size(8.dp)
                                            .background(MaterialTheme.colorScheme.error, CircleShape))
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(String.format(Locale.ROOT, "%.1f / 15 s", elapsed / 1_000f),
                                                style = MaterialTheme.typography.labelLarge)
                                            Crossfade(if (processing) "Preparing…" else if (locked) "Locked" else "Release to review",
                                                animationSpec = motion.fastEffectsSpec(), label = "voiceRecordingStatus") { status ->
                                                Text(status, style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        VoiceWaveform(levels, Modifier.weight(1f).height(32.dp), live = true)
                                    }
                                }
                            }
                        }
                    }
                }
                // This node never leaves the tree during capture or review.
                if (voiceButtonVisible || active) {
                    val actionable = enabled && !processing && !sending
                    Surface(
                        shape = CircleShape,
                        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        shadowElevation = microphoneElevation.coerceAtLeast(0.dp),
                        modifier = Modifier.size(56.dp).onGloballyPositioned { anchor = it }
                            .semantics {
                                role = Role.Button
                                contentDescription = actionDescription
                                if (!actionable) disabled()
                                onClick { if (actionable) primaryAction(); actionable }
                            }
                            .pointerInput(enabled) {
                                if (!enabled) return@pointerInput
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    down.consume()
                                    val acceptsPress = canPress
                                    val tracksRecording = acceptsPress && beginPress()
                                    var released = false
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            change.consume()
                                            if (tracksRecording && change.pressed) {
                                                val position = anchor?.takeIf { it.isAttached }?.localToRoot(change.position)
                                                updateLockDrag(position)
                                            }
                                            if (!change.pressed) { released = true; break }
                                        }
                                    } finally {
                                        if (tracksRecording) releasePress()
                                        else if (released && acceptsPress && canPress) primaryAction()
                                    }
                                }
                            }
                            .graphicsLayer { scaleX = microphoneScale; scaleY = microphoneScale },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Crossfade(when {
                                processing || sending -> VoiceActionIcon.Busy
                                clip != null -> VoiceActionIcon.Send
                                recording && locked -> VoiceActionIcon.Stop
                                else -> VoiceActionIcon.Microphone
                            }, animationSpec = motion.fastEffectsSpec(), label = "voiceActionIcon") { icon ->
                                if (icon == VoiceActionIcon.Busy) CircularProgressIndicator(Modifier.size(22.dp),
                                    color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                                else Icon(when (icon) {
                                    VoiceActionIcon.Send -> Icons.AutoMirrored.Filled.Send
                                    VoiceActionIcon.Stop -> Icons.Default.Stop
                                    else -> Icons.Default.Mic
                                }, contentDescription = null)
                            }
                        }
                    }
                } else sendButton()
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) }
    }
}

private enum class VoiceActionIcon { Microphone, Stop, Send, Busy }
