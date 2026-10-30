@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaDataSource
import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import org.aralozkaya.msngr.data.EmotionSoundService
import org.aralozkaya.msngr.pages.components.KeyboardPickerSheet
import org.aralozkaya.msngr.protocol.EmotionSound
import org.aralozkaya.msngr.protocol.MsnEmotionSound
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun Context.emotionSoundStore() = (applicationContext as MsngrApplication).emotionSoundStore

/** At most one player, with media volume and transient audio focus; no background playback. */
internal object EmotionSoundAudio {
    private var cancelCurrent: (() -> Unit)? = null
    private var currentPlayer: MediaPlayer? = null
    internal val isPlaying: Boolean get() = runCatching { currentPlayer?.isPlaying == true }.getOrDefault(false)
    @Suppress("DEPRECATION")
    suspend fun play(context: Context, bytes: ByteArray) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val player = MediaPlayer()
        val activity = context as? android.app.Activity
        val previousStream = activity?.volumeControlStream
        var cancel: (() -> Unit)? = null
        val focus = AudioManager.OnAudioFocusChangeListener { change -> if (change < 0) cancel?.invoke() }
        try {
            cancelCurrent?.invoke()
            currentPlayer = player
            check(audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio is in use. Try again." }
            activity?.volumeControlStream = AudioManager.STREAM_MUSIC
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            player.setDataSource(object : MediaDataSource() {
                override fun getSize() = bytes.size.toLong()
                override fun close() = Unit
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (position < 0 || position >= bytes.size) return -1
                    val count = minOf(size, bytes.size - position.toInt())
                    bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                    return count
                }
            })
            withTimeout(20_000) {
                suspendCancellableCoroutine { continuation ->
                    cancel = { continuation.cancel() }; cancelCurrent = cancel
                    player.setOnPreparedListener {
                        if (continuation.isActive) {
                            if (it.duration !in 1..15_000) continuation.resumeWithException(IllegalStateException("The emotion sound is too long."))
                            else it.start()
                        }
                    }
                    player.setOnCompletionListener { if (continuation.isActive) continuation.resume(Unit) }
                    player.setOnErrorListener { _, _, _ ->
                        if (continuation.isActive) continuation.resumeWithException(IllegalStateException("The emotion sound could not be played.")); true
                    }
                    player.prepareAsync()
                }
            }
        } finally {
            if (cancelCurrent === cancel) cancelCurrent = null
            if (currentPlayer === player) currentPlayer = null
            player.release()
            audio.abandonAudioFocus(focus)
            if (previousStream != null) activity.volumeControlStream = previousStream
        }
    }
}

@Stable
internal class EmotionSoundController(private val context: Context, val account: String?, val server: String, private val scope: CoroutineScope) {
    var activeId by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    fun checkAccount() { check(account != null && SessionPrefs.preferenceAccount(context)?.storageId == account && SessionPrefs.readServerConfig(context).emotionSoundServer == server) { "The selected account changed. Open the sound picker again." } }
    suspend fun load(sound: EmotionSound, save: Boolean = false): Pair<EmotionSound, ByteArray> {
        checkAccount()
        val cached = withContext(Dispatchers.IO) { context.emotionSoundStore().bytes(account!!, server, sound) }
        val result = if (cached != null) MsnEmotionSound.downloaded(cached, sound) to cached else EmotionSoundService(server).download(sound)
        checkAccount()
        withContext(Dispatchers.IO) {
            val saveCustom = save && result.first.builtInCode == null
            if (cached == null || (saveCustom && !context.emotionSoundStore().isSaved(account!!, server, sound)))
                context.emotionSoundStore().put(account!!, server, result.first, result.second, saveCustom)
        }
        return result
    }
    fun stop() { job?.cancel(); job = null; activeId = null; error = null }
    fun play(sound: EmotionSound, previewBytes: ByteArray? = null) {
        stop(); error = null; activeId = sound.id
        job = scope.launch {
            try {
                val bytes = previewBytes ?: load(sound).second
                checkAccount()
                EmotionSoundAudio.play(context, bytes)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "The sound could not be played." }
            finally { if (job === currentCoroutineContext()[Job]) { activeId = null; job = null } }
        }
    }
}

@Composable
internal fun rememberEmotionSoundController(): EmotionSoundController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val account = SessionPrefs.preferenceAccount(context)?.storageId
    val server = SessionPrefs.readServerConfig(context).emotionSoundServer
    val controller = remember(account, server, scope) { EmotionSoundController(context, account, server, scope) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) controller.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.stop() }
    }
    return controller
}

internal val emotionSoundCategories = listOf("Any category", "Cartoons / Games", "TV / Movies", "Music", "Vocals", "Sound Effects")

/** Indexes are the native Sounds.Languages / Lang01..Lang24 protocol IDs. Zero omits lngId. */
internal val emotionSoundLanguages = listOf(
    "Any language", "Arabic", "Catalan", "Chinese", "Danish", "German", "Greek", "English",
    "Spanish", "Finnish", "French", "Hebrew", "Portuguese", "Italian", "Japanese", "Korean",
    "Dutch", "Norwegian", "Russian", "Swedish", "Thai", "Turkish", "Kurdish", "Estonian", "Hungarian",
)

@Composable
internal fun EmotionSoundDropdown(
    selected: Int,
    options: List<String>,
    label: String,
    tag: String,
    onSelected: (Int) -> Unit,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.testTag(tag).semantics { contentDescription = label },
        ) {
            Text(options[selected])
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenuPopup(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            DropdownMenuGroup(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("${tag}_menu"),
                shapes = MenuDefaults.groupShape(0, 1),
                interactionSource = interactionSource,
            ) {
                options.forEachIndexed { index, name ->
                    SelectableDropdownMenuItem(
                        selected = index == selected,
                        text = { Text(name) },
                        shapes = MenuDefaults.itemShape(index, options.size),
                        trailingContent = if (index == selected) {
                            { Icon(Icons.Default.Check, "Selected") }
                        } else null,
                        modifier = Modifier.testTag("${tag}_$index"),
                        onClick = { expanded = false; onSelected(index) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmotionSoundPicker(visible: Boolean, enabled: Boolean, onDismiss: () -> Unit,
    onSend: suspend (EmotionSound) -> Result<Unit>, catalogService: EmotionSoundService? = null) {
    val context = LocalContext.current
    val player = rememberEmotionSoundController()
    val scope = rememberCoroutineScope()
    val store = context.emotionSoundStore()
    val changes by store.changes.collectAsState()
    val search = rememberTextFieldState()
    val searchController = remember { MessengerPlusEditorController() }
    val iconButtonSize = IconButtonDefaults.smallContainerSize(widthOption = IconButtonDefaults.IconButtonWidthOption.Narrow)
    var catalog by remember(player.account, player.server) { mutableStateOf(MsnEmotionSound.builtIns) }
    val soundService = remember(player.server, catalogService) { catalogService ?: EmotionSoundService(player.server) }
    // Null means not loaded; a successful empty catalog is still a completed load.
    var popular by remember(soundService) { mutableStateOf<List<EmotionSound>?>(null) }
    var catalogError by remember(soundService) { mutableStateOf<String?>(null) }
    var catalogAttempt by remember(soundService) { mutableIntStateOf(0) }
    var online by remember { mutableStateOf(false) }
    var category by remember { mutableIntStateOf(0) }
    var randomDialog by remember(player.account, player.server) { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val loadingOnline = online && popular == null && catalogError == null
    val busy = actionBusy || loadingOnline
    LaunchedEffect(visible, changes, player.account) {
        if (visible && player.account != null) catalog = withContext(Dispatchers.IO) { store.catalog(player.account, player.server) }
        if (!visible) { player.stop(); randomDialog = false }
    }
    LaunchedEffect(visible, online, soundService, catalogAttempt) {
        if (visible && online && popular == null && catalogError == null) {
            try { popular = soundService.popular() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { catalogError = failure.message ?: "Online sounds could not be loaded." }
        }
    }
    fun action(block: suspend () -> Unit) { scope.launch {
        actionBusy = true; error = null; status = null
        try { block() } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = failure.message ?: "The sound could not be downloaded." }
        finally { actionBusy = false }
    } }
    KeyboardPickerSheet(visible, onDismiss, "Emotion sounds", "emotion_sound_picker", expandable = true,
        header = {
            CompactSearchField(search, "Search sounds", "Clear sound search",
                Modifier.fillMaxWidth().testTag("emotion_sound_search"), controller = searchController)
            PrimaryTabRow(
                selectedTabIndex = if (online) 1 else 0,
                modifier = Modifier.fillMaxWidth().testTag("emotion_sound_source"),
                containerColor = Color.Transparent,
            ) {
                Tab(
                    selected = !online, onClick = { online = false; error = null },
                    text = { Text("Local") },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("emotion_sound_local"),
                )
                Tab(
                    selected = online, onClick = { online = true; error = null },
                    text = { Text("Online") },
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("emotion_sound_online"),
                )
            }
        }, footer = {
            val hash = MsnEmotionSound.hash(search.text.toString())
            if (actionBusy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            (error ?: player.error ?: status)?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (error != null || player.error != null) MaterialTheme.colorScheme.error else LocalContentColor.current) }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                EmotionSoundDropdown(category, emotionSoundCategories, "Sound category", "emotion_sound_category",
                    onSelected = { category = it }, enabled = !busy)
                if (hash != null) Button(enabled = !busy, onClick = { action {
                    val (sound, _) = player.load(EmotionSound(hash, "Emotion sound"), save = true)
                    status = "Added ${sound.name.messengerPlusPlainText()}"; online = false
                } }) { Icon(Icons.Default.Download, null); Text("Download") }
                else FilledTonalButton(enabled = !busy, onClick = {
                    player.stop(); searchController.clearFocusAndHideKeyboard(); randomDialog = true
                }, modifier = Modifier.testTag("emotion_sound_random")) { Icon(Icons.Default.Shuffle, null); Text("Random") }
            }
        }) {
        val query = search.text.toString().trim().removePrefix("#")
        val results = (if (online) popular.orEmpty() else catalog).filter { sound ->
            (category == 0 || sound.category == 0 || sound.category == category) &&
                (query.isBlank() || sound.name.messengerPlusPlainText().contains(query, true) || sound.id.contains(query, true))
        }
        when {
            loadingOnline -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                LoadingIndicator(Modifier.testTag("emotion_sound_online_loading").semantics { contentDescription = "Loading online sounds" })
            }
            online && catalogError != null -> {
                Text(catalogError!!, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { catalogError = null; catalogAttempt++ }, modifier = Modifier.testTag("emotion_sound_online_retry")) { Text("Retry") }
            }
            results.isEmpty() -> Text(if (online) "No matching sounds. Enter a sound ID to download, or try Random." else "No matching sounds.",
                modifier = Modifier.testTag("emotion_sound_empty"))
        }
        results.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { sound ->
                    val name = sound.name.messengerPlusPlainText()
                    Row(Modifier.weight(1f).testTag("emotion_sound_${sound.id}"), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(modifier = Modifier.size(iconButtonSize), enabled = !busy, onClick = { if (player.activeId == sound.id) player.stop() else player.play(sound) }) {
                            Icon(if (player.activeId == sound.id) Icons.Default.Stop else Icons.Default.PlayArrow, if (player.activeId == sound.id) "Stop sound" else "Preview $name")
                        }
                        // TooltipBox applies its modifier to an inner anchor, so weight its outer wrapper.
                        Box(Modifier.weight(1f)) {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                                tooltip = { PlainTooltip { Text(name) } },
                                state = rememberTooltipState(),
                            ) {
                                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled && !busy, onClickLabel = "Send sound") { action {
                                        val (loaded, _) = player.load(sound)
                                        onSend(loaded).getOrThrow()
                                        onDismiss()
                                    } }.padding(vertical = 6.dp))
                            }
                        }
                        if (online) IconButton(modifier = Modifier.size(iconButtonSize), enabled = !busy, onClick = { action { val (saved, _) = player.load(sound, save = true); status = "Added ${saved.name.messengerPlusPlainText()}" } }) {
                            Icon(Icons.Default.Download, "Download $name")
                        } else if (sound.builtInCode == null) IconButton(modifier = Modifier.size(iconButtonSize), enabled = !busy, onClick = { action {
                            player.checkAccount(); withContext(Dispatchers.IO) { store.remove(player.account!!, player.server, sound) }
                        } }) { Icon(Icons.Default.Delete, "Remove $name") }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (visible && randomDialog) {
        RandomEmotionSoundDialog(category, onDismiss = { randomDialog = false }, onKept = { sound ->
            status = "Added ${sound.name.messengerPlusPlainText()}"; online = false; randomDialog = false
        })
    }
}

@Composable
internal fun EmotionSoundMessageSheet(message: org.aralozkaya.msngr.data.ChatMessage, onDismiss: () -> Unit) {
    val sound = message.emotionSound?.sound ?: return
    val context = LocalContext.current
    val player = rememberEmotionSoundController()
    val scope = rememberCoroutineScope()
    val store = context.emotionSoundStore()
    val changes by store.changes.collectAsState()
    var saved by remember(sound.id, player.account) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(changes, sound.id) { saved = withContext(Dispatchers.IO) { player.account?.let { store.isSaved(it, player.server, sound) } == true } }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
        modifier = Modifier.testTag("emotion_sound_message_sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, Modifier.size(64.dp))
            Text(sound.name.messengerPlusPlainText(), Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.titleLarge)
            if (sound.builtInCode == null) Text("#${sound.id}", style = MaterialTheme.typography.labelMedium)
            val active = player.activeId == sound.id
            SegmentedListItem(onClick = { if (active) player.stop() else player.play(sound) }, enabled = !busy,
                shapes = ListItemDefaults.segmentedShapes(0, if (sound.builtInCode == null) 2 else 1),
                leadingContent = { Icon(if (active) Icons.Default.Stop else Icons.Default.PlayArrow, null) },
                content = { Text(if (active) "Stop" else "Play") })
            if (sound.builtInCode == null) SegmentedListItem(onClick = { scope.launch {
                busy = true; error = null
                try {
                    player.checkAccount()
                    if (saved) withContext(Dispatchers.IO) { store.remove(player.account!!, player.server, sound) }
                    else player.load(sound, save = true)
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Exception) { error = failure.message }
                finally { busy = false }
            } }, enabled = !busy,
                shapes = ListItemDefaults.segmentedShapes(1, 2),
                leadingContent = { Icon(if (saved) Icons.Default.Delete else Icons.Default.Add, null) },
                content = { Text(if (saved) "Remove from saved sounds" else "Add to saved sounds") })
            if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            (error ?: player.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
