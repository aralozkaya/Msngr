package org.aralozkaya.msngr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.os.Build
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.insertTextAtCursor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onImeAction
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.SoftwareKeyboardControllerCompat
import org.aralozkaya.msngr.pages.components.LocalKeyboardSheetHost
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun MsnEmoticonText(
    text: String,
    useOriginalEmoticons: Boolean,
    modifier: Modifier = Modifier,
    renderedShortcutRanges: List<MsnEmoticonRange>? = null,
    messengerPlusEnabled: Boolean = LocalMessengerPlusEnabled.current,
    hideControlCharacters: Boolean = LocalMessengerPlusHideControlCharacters.current,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    linkify: Boolean = false,
    nativeStyle: NativeTextStyle = NativeTextStyle(),
) {
    val adaptiveColor = MaterialTheme.colorScheme.primary.toArgb()
    val parsed = remember(text, messengerPlusEnabled, hideControlCharacters, adaptiveColor) {
        MessengerPlusTextCodec.parseForPresentation(text, messengerPlusEnabled, hideControlCharacters, adaptiveColor)
    }
    val mappedShortcutRanges = remember(parsed, renderedShortcutRanges) {
        renderedShortcutRanges?.mapNotNull(parsed::textRangeForSource)
    }
    val links = remember(parsed.text, linkify) {
        if (linkify) findChatMessageLinks(parsed.text) else emptyList()
    }
    val matches = remember(parsed.text, mappedShortcutRanges, links) {
        findMsnEmoticons(
            parsed.text,
            renderedShortcutRanges = mappedShortcutRanges,
        ).filterNot { match ->
            links.any { link -> match.range.start < link.end && match.range.endExclusive > link.start }
        }
    }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotatedText = remember(parsed, matches, useOriginalEmoticons, links, linkColor, nativeStyle) {
        parsed.present(matches, useOriginalEmoticons, links, linkColor, nativeStyle)
    }
    val emoticonSizeSp = with(LocalDensity.current) { MSN_EMOTICON_SIZE_DP.toSp() }
    val inlineContent = matches.mapIndexed { index, match ->
        inlineContentKey(index) to InlineTextContent(
            placeholder = Placeholder(
                width = emoticonSizeSp,
                height = emoticonSizeSp,
                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
            ),
        ) {
            MsnEmoticonArtwork(
                emoticon = match.emoticon,
                staticOnly = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }.toMap()
    val sizeScale = nativeStyle.size.toFloat() / NativeTextStyle.DEFAULT_SIZE
    Text(
        text = annotatedText,
        modifier = modifier,
        color = color,
        style = style.copy(
            fontSize = (if (style.fontSize == TextUnit.Unspecified) 16.sp else style.fontSize) * sizeScale,
            lineHeight = if (style.lineHeight == TextUnit.Unspecified) TextUnit.Unspecified else style.lineHeight * sizeScale,
        ),
        inlineContent = if (useOriginalEmoticons) inlineContent else emptyMap(),
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
internal fun MessengerPlusText(
    text: String,
    modifier: Modifier = Modifier,
    messengerPlusEnabled: Boolean = LocalMessengerPlusEnabled.current,
    hideControlCharacters: Boolean = LocalMessengerPlusHideControlCharacters.current,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val adaptiveColor = MaterialTheme.colorScheme.primary.toArgb()
    val parsed = remember(text, messengerPlusEnabled, hideControlCharacters, adaptiveColor) {
        MessengerPlusTextCodec.parseForPresentation(text, messengerPlusEnabled, hideControlCharacters, adaptiveColor)
    }
    val annotated = remember(parsed) { parsed.present(emptyList(), useOriginalEmoticons = false) }
    Text(
        text = annotated,
        modifier = modifier,
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
internal fun MsnEmoticonGlyph(
    emoticon: MsnEmoticonShortcut,
    useOriginalEmoticons: Boolean,
    modifier: Modifier = Modifier,
) {
    if (useOriginalEmoticons) {
        MsnEmoticonArtwork(
            emoticon = emoticon,
            staticOnly = true,
            modifier = modifier.size(MSN_EMOTICON_SIZE_DP),
        )
    } else {
        Text(
            text = emoticon.unicode,
            modifier = modifier,
            style = LocalTextStyle.current.copy(fontSize = 19.sp),
        )
    }
}

@Composable
internal fun MsnEmoticonEditor(
    value: String,
    onValueChange: (String) -> Unit,
    convertTypedShortcuts: Boolean,
    useOriginalEmoticons: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    messengerPlusEnabled: Boolean = LocalMessengerPlusEnabled.current,
    hideControlCharacters: Boolean = LocalMessengerPlusHideControlCharacters.current,
    controller: MessengerPlusEditorController = remember { MessengerPlusEditorController() },
    initialSelection: TextRange? = null,
    singleLine: Boolean = false,
    hint: String = "Message",
    contentDescription: String = "Message input",
    contentPadding: PaddingValues = PaddingValues(start = 4.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
    onFocusChanged: (Boolean) -> Unit = {},
    onTap: () -> Unit = {},
    allowUnicodeEmoji: Boolean = false,
    onSearch: (() -> Unit)? = null,
    onSelectionChanged: (TextRange) -> Unit = {},
    onColors: (() -> Unit)? = null,
    onTextStyle: (() -> Unit)? = null,
    nativeStyle: NativeTextStyle = NativeTextStyle(),
) {
    val currentMessengerPlusEnabled by rememberUpdatedState(messengerPlusEnabled)
    val currentOnFocusChanged by rememberUpdatedState(onFocusChanged)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnSearch by rememberUpdatedState(onSearch)
    val currentOnSelectionChanged by rememberUpdatedState(onSelectionChanged)
    val currentOnColors by rememberUpdatedState(onColors)
    val currentOnTextStyle by rememberUpdatedState(onTextStyle)
    var editorFocused by remember { mutableStateOf(false) }
    val keepSheetOpenOnTap = LocalKeyboardSheetHost.current?.keepOpenOnInputTap == true
    val contentColor = LocalContentColor.current
    val adaptiveColor = MaterialTheme.colorScheme.primary.toArgb()
    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val textSizePixels = with(density) {
        val fontSize = if (textStyle.fontSize == TextUnit.Unspecified) 16.sp else textStyle.fontSize
        fontSize.toPx()
    }
    AndroidView(
        factory = { context ->
            MsnEditText(context).also { editor ->
                editor.allowUnicodeEmoji = allowUnicodeEmoji
                editor.showSoftInputOnFocus = !keepSheetOpenOnTap
                editor.configureSingleLine(singleLine)
                editor.setExternalValue(value)
                initialSelection?.let { selection ->
                    editor.setSelection(
                        selection.start.coerceIn(0, value.length),
                        selection.end.coerceIn(0, value.length),
                    )
                }
                val actionModeCallback = MessengerPlusSelectionActionModeCallback(
                    controller = controller,
                    isEnabled = { currentMessengerPlusEnabled },
                    onColors = { currentOnColors },
                    onTextStyle = { currentOnTextStyle },
                )
                editor.customSelectionActionModeCallback = actionModeCallback
                editor.customInsertionActionModeCallback = actionModeCallback
                editor.setOnFocusChangeListener { _, focused ->
                    editorFocused = focused
                    currentOnFocusChanged(focused)
                }
                editor.setOnClickListener {
                    currentOnTap()
                }
                editor.setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_SEARCH && currentOnSearch != null) {
                        currentOnSearch?.invoke()
                        true
                    } else false
                }
                controller.attach(editor) { currentOnSelectionChanged(it) }
            }
        },
        modifier = modifier.semantics(mergeDescendants = true) {
            this.contentDescription = contentDescription
            focused = editorFocused
            if (!enabled) disabled()
            if (value.isEmpty()) text = AnnotatedString(hint)
            editableText = AnnotatedString(
                (if (messengerPlusEnabled || hideControlCharacters) {
                    MessengerPlusTextCodec.plainText(value)
                } else {
                    value
                }).withExpandedMsnMenuTokens(),
            )
            setText { replacement ->
                if (!enabled) false else {
                    onValueChange(if (allowUnicodeEmoji) replacement.text else replacement.text.withoutUnicodeEmoji())
                    true
                }
            }
            insertTextAtCursor { insertion ->
                enabled && controller.insert(insertion.text)
            }
            requestFocus { enabled && controller.requestFocus() }
            if (onSearch != null) {
                onImeAction(ImeAction.Search) {
                    if (!enabled) false else {
                        onSearch()
                        true
                    }
                }
            }
        },
        update = { editor ->
            editor.onValueChanged = onValueChange
            editor.allowUnicodeEmoji = allowUnicodeEmoji
            editor.isEnabled = enabled
            editor.showSoftInputOnFocus = !keepSheetOpenOnTap
            editor.configureSingleLine(singleLine)
            if (onSearch != null) editor.imeOptions = EditorInfo.IME_ACTION_SEARCH
            // Even an unchanged hint rebuilds TextView's layout and dismisses its
            // selection handles and action mode when selection triggers recomposition.
            if (!TextUtils.equals(editor.hint, hint)) editor.hint = hint
            editor.contentDescription = contentDescription
            with(density) {
                editor.setPaddingRelative(
                    contentPadding.calculateStartPadding(layoutDirection).roundToPx(),
                    contentPadding.calculateTopPadding().roundToPx(),
                    contentPadding.calculateEndPadding(layoutDirection).roundToPx(),
                    contentPadding.calculateBottomPadding().roundToPx(),
                )
            }
            editor.setTextAppearance(
                color = if (nativeStyle.colorRgb == 0) contentColor.toArgb() else 0xFF000000.toInt() or nativeStyle.colorRgb,
                textSizePixels = textSizePixels * nativeStyle.size / NativeTextStyle.DEFAULT_SIZE,
                adaptiveColor = adaptiveColor,
            )
            editor.setEmoticonAppearance(
                convertTypedShortcuts = convertTypedShortcuts,
                useOriginalEmoticons = useOriginalEmoticons,
                messengerPlusEnabled = messengerPlusEnabled,
                hideControlCharacters = messengerPlusEnabled || hideControlCharacters,
            )
            editor.setNativeStyle(nativeStyle)
            editor.setExternalValue(value)
            controller.attach(editor) { currentOnSelectionChanged(it) }
        },
    )
}

internal enum class MessengerPlusEditorSyntax {
    MESSAGE_CONTROLS,
    PROFILE_MARKUP,
}

internal class MessengerPlusEditorController(
    private val syntax: MessengerPlusEditorSyntax = MessengerPlusEditorSyntax.MESSAGE_CONTROLS,
) {
    private var editor: MsnEditText? = null
    private var profileValue: TextFieldValue? = null
    private val currentProfileValue: TextFieldValue?
        get() = editor?.snapshotValue() ?: profileValue
    private var onProfileValueChange: ((TextFieldValue) -> Unit)? = null
    var hasSelection by mutableStateOf(false)
        private set
    var hasContent by mutableStateOf(false)
        private set
    var hasFormatting by mutableStateOf(false)
        private set
    var activeStyle by mutableStateOf(MessengerPlusStyle())
        private set
    val supportsBackgroundFormatting: Boolean
        get() = true

    internal fun attach(editor: MsnEditText, onSelectionChanged: (TextRange) -> Unit = {}) {
        this.editor = editor
        editor.onSelectionChangedListener = { start, end ->
            updateSelectionState(editor.text?.toString().orEmpty(), start, end)
            onSelectionChanged(TextRange(start.coerceAtLeast(0), end.coerceAtLeast(0)))
        }
        updateSelectionState(
            editor.text?.toString().orEmpty(),
            editor.selectionStart,
            editor.selectionEnd,
        )
    }

    internal fun attachProfileValue(
        value: TextFieldValue,
        onValueChange: (TextFieldValue) -> Unit,
    ) {
        check(syntax == MessengerPlusEditorSyntax.PROFILE_MARKUP)
        profileValue = value
        onProfileValueChange = onValueChange
        updateSelectionState(value.text, value.selection.start, value.selection.end)
    }

    fun requestFocus(): Boolean = editor?.requestFocus() == true

    fun snapshot(): TextFieldValue = editor?.snapshotValue() ?: profileValue ?: TextFieldValue("")

    private fun MsnEditText.snapshotValue(): TextFieldValue {
        val source = text?.toString().orEmpty()
        return TextFieldValue(
            text = source,
            selection = TextRange(
                selectionStart.coerceIn(0, source.length),
                selectionEnd.coerceIn(0, source.length),
            ),
        )
    }

    fun replaceValue(value: TextFieldValue) {
        when (syntax) {
            MessengerPlusEditorSyntax.MESSAGE_CONTROLS -> editor?.replaceAllText(
                replacement = value.text,
                selectionStart = value.selection.start,
                selectionEnd = value.selection.end,
            )
            MessengerPlusEditorSyntax.PROFILE_MARKUP -> updateProfileValue(value)
        }
    }

    fun requestFocusAndShowKeyboard() {
        val target = editor ?: return
        if (target.requestFocus() && target.showSoftInputOnFocus) {
            SoftwareKeyboardControllerCompat(target).show()
        }
    }

    fun hideKeyboard() {
        editor?.let { SoftwareKeyboardControllerCompat(it).hide() }
    }

    fun clearFocusAndHideKeyboard() {
        hideKeyboard()
        editor?.clearFocus()
    }

    fun restoreSelection(selection: TextRange) {
        val target = editor
        if (target != null) {
            val length = target.text?.length ?: 0
            target.setSelection(selection.start.coerceIn(0, length), selection.end.coerceIn(0, length))
        } else {
            profileValue?.let { replaceValue(it.copy(selection = selection, composition = null)) }
        }
    }

    fun selectAll(): Boolean = when (syntax) {
        MessengerPlusEditorSyntax.MESSAGE_CONTROLS -> {
            val target = editor ?: return false
            target.selectAll()
            true
        }
        MessengerPlusEditorSyntax.PROFILE_MARKUP -> {
            val value = currentProfileValue ?: return false
            updateProfileValue(value.copy(selection = TextRange(0, value.text.length)))
            true
        }
    }

    fun insert(value: String): Boolean = when (syntax) {
        MessengerPlusEditorSyntax.MESSAGE_CONTROLS -> {
            val target = editor ?: return false
            target.replaceSelection(value, selectReplacement = false)
            true
        }
        MessengerPlusEditorSyntax.PROFILE_MARKUP ->
            replaceProfileSelection(value, selectReplacement = false)
    }

    fun deleteBackward(): Boolean = editor?.deleteBackward() == true

    fun toggle(control: Char) {
        val tag = control.profileMarkupTag() ?: return
        val value = snapshot()
        val enabled = when (control) {
            MessengerPlusTextCodec.BOLD_CONTROL -> activeStyle.bold
            MessengerPlusTextCodec.ITALIC_CONTROL -> activeStyle.italic
            MessengerPlusTextCodec.UNDERLINE_CONTROL -> activeStyle.underline
            else -> activeStyle.strikeout
        }
        if (enabled) {
            val edit = MessengerPlusTextCodec.removeStyle(
                value.text, MessengerPlusTextRange(value.selection.min, value.selection.max), control,
            )
            replaceValue(TextFieldValue(edit.text, TextRange(edit.selection.start, edit.selection.endExclusive)))
        } else {
            surroundMarkupSelection("[$tag]", "[/$tag]")
        }
    }

    fun applyForeground(color: MessengerPlusColorValue) =
        surroundMarkupSelection("[c=${color.wireToken}]", "[/c]")

    fun applyBackground(color: MessengerPlusPaletteColor) = applyBackground(color.asValue())

    fun applyBackground(color: MessengerPlusColorValue) =
        surroundMarkupSelection("[a=${color.wireToken}]", "[/a]")

    private fun surroundMarkupSelection(prefix: String, suffix: String) {
        when (syntax) {
            MessengerPlusEditorSyntax.MESSAGE_CONTROLS -> editor?.surroundSelection(prefix, suffix, wrapCollapsed = true)
            MessengerPlusEditorSyntax.PROFILE_MARKUP -> surroundProfileSelection(prefix, suffix, wrapCollapsed = true)
        }
    }

    fun removeAllFormatting() {
        val value = snapshot()
        val cleared = MessengerPlusTextCodec.removeFormatting(value.text)
        val boundaries = cleared.sourceBoundaryToOutputOffset
        replaceValue(TextFieldValue(
            text = cleared.text,
            selection = TextRange(
                boundaries[value.selection.start.coerceIn(boundaries.indices)],
                boundaries[value.selection.end.coerceIn(boundaries.indices)],
            ),
        ))
    }

    fun applyGradient(
        start: MessengerPlusColorValue,
        end: MessengerPlusColorValue,
        background: Boolean = false,
    ): Boolean {
        val value = snapshot()
        if (value.selection.collapsed) return false
        val tag = if (background) "a" else "c"
        val prefix = "[$tag=${start.wireToken}]"
        val suffix = "[/$tag=${end.wireToken}]"
        when (syntax) {
            MessengerPlusEditorSyntax.MESSAGE_CONTROLS -> editor?.surroundSelection(prefix, suffix) ?: return false
            MessengerPlusEditorSyntax.PROFILE_MARKUP -> surroundProfileSelection(prefix, suffix, wrapCollapsed = true)
        }
        return true
    }

    private fun replaceProfileSelection(
        replacement: String,
        selectReplacement: Boolean,
    ): Boolean {
        val value = currentProfileValue ?: return false
        val safeReplacement = replacement.withoutUnicodeEmoji()
        val start = minOf(value.selection.start, value.selection.end).coerceAtLeast(0)
        val end = maxOf(value.selection.start, value.selection.end).coerceAtLeast(start)
        val replacementEnd = start + safeReplacement.length
        updateProfileValue(
            value.copy(
                text = value.text.replaceRange(start, end, safeReplacement),
                selection = if (selectReplacement) {
                    TextRange(start, replacementEnd)
                } else {
                    TextRange(replacementEnd)
                },
                composition = null,
            ),
        )
        return true
    }

    private fun surroundProfileSelection(
        prefix: String,
        suffix: String,
        wrapCollapsed: Boolean,
    ) {
        val value = currentProfileValue ?: return
        val start = minOf(value.selection.start, value.selection.end).coerceAtLeast(0)
        val end = maxOf(value.selection.start, value.selection.end).coerceAtLeast(start)
        val collapsed = start == end
        val appliedSuffix = if (collapsed && !wrapCollapsed) "" else suffix
        val replacement = prefix + value.text.substring(start, end) + appliedSuffix
        updateProfileValue(
            value.copy(
                text = value.text.replaceRange(start, end, replacement),
                selection = if (collapsed) {
                    TextRange(start + prefix.length)
                } else {
                    TextRange(start + prefix.length, end + prefix.length)
                },
                composition = null,
            ),
        )
    }

    private fun updateProfileValue(value: TextFieldValue) {
        profileValue = value
        updateSelectionState(value.text, value.selection.start, value.selection.end)
        val target = editor
        if (target != null) {
            target.replaceAllText(value.text, value.selection.start, value.selection.end)
        } else {
            onProfileValueChange?.invoke(value)
        }
    }

    private fun updateSelectionState(source: String, start: Int, end: Int) {
        hasSelection = start != end
        hasContent = source.isNotEmpty()
        hasFormatting = MessengerPlusTextCodec.removeFormatting(source).text != source
        val parsed = MessengerPlusTextCodec.parse(source)
        val selected = if (hasSelection) parsed.glyphs.filter {
            it.sourceStart < maxOf(start, end) && it.sourceEndExclusive > minOf(start, end)
        } else emptyList()
        // Selections can start on hidden opening tags (notably Select all).
        // Inspect the visible selection, and only show a decoration as active
        // when it applies throughout; toggling a mixed selection applies it.
        activeStyle = selected.firstOrNull()?.style?.copy(
            bold = selected.all { it.style.bold },
            italic = selected.all { it.style.italic },
            underline = selected.all { it.style.underline },
            strikeout = selected.all { it.style.strikeout },
        ) ?: parsed.sourceStyles[start.coerceIn(0, source.length)]
    }
}

private fun Char.profileMarkupTag(): String? = when (this) {
    MessengerPlusTextCodec.BOLD_CONTROL -> "b"
    MessengerPlusTextCodec.ITALIC_CONTROL -> "i"
    MessengerPlusTextCodec.UNDERLINE_CONTROL -> "u"
    MessengerPlusTextCodec.STRIKEOUT_CONTROL -> "s"
    else -> null
}

private fun MessengerPlusParsedText.present(
    emoticons: List<MsnEmoticonMatch>,
    useOriginalEmoticons: Boolean,
    links: List<ChatMessageLink> = emptyList(),
    linkColor: Color = Color.Unspecified,
    nativeStyle: NativeTextStyle = NativeTextStyle(),
): AnnotatedString {
    val renderedBoundary = IntArray(text.length + 1)
    val annotated = buildAnnotatedString {
        var sourceOffset = 0
        emoticons.forEachIndexed { index, match ->
            appendMappedText(text, sourceOffset, match.range.start, renderedBoundary)
            val renderedStart = length
            renderedBoundary[match.range.start] = renderedStart
            if (useOriginalEmoticons) {
                appendInlineContent(
                    id = inlineContentKey(index),
                    alternateText = text.substring(match.range.start, match.range.endExclusive),
                )
            } else {
                append(match.emoticon.unicode)
            }
            for (boundary in match.range.start + 1 until match.range.endExclusive) {
                renderedBoundary[boundary] = renderedStart
            }
            renderedBoundary[match.range.endExclusive] = length
            sourceOffset = match.range.endExclusive
        }
        appendMappedText(text, sourceOffset, text.length, renderedBoundary)
        if (styleSpans.isEmpty() && length > 0) {
            addStyle(MessengerPlusStyle().toComposeSpanStyle(nativeStyle), 0, length)
        }
        styleSpans.forEach { span ->
            val renderedStart = renderedBoundary[span.range.start]
            val renderedEnd = renderedBoundary[span.range.endExclusive]
            if (renderedEnd > renderedStart) {
                addStyle(span.style.toComposeSpanStyle(nativeStyle), renderedStart, renderedEnd)
            }
        }
        links.forEach { link ->
            addLink(
                LinkAnnotation.Url(
                    url = link.url,
                    styles = TextLinkStyles(
                        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                    ),
                ),
                renderedBoundary[link.start],
                renderedBoundary[link.end],
            )
        }
    }
    return annotated
}

private fun AnnotatedString.Builder.appendMappedText(
    source: String,
    start: Int,
    endExclusive: Int,
    boundaryMap: IntArray,
) {
    for (index in start until endExclusive) {
        boundaryMap[index] = length
        append(source[index])
    }
    boundaryMap[endExclusive] = length
}

private fun MessengerPlusStyle.toComposeSpanStyle(nativeStyle: NativeTextStyle = NativeTextStyle()): SpanStyle {
    val decorations = buildList {
        if (underline || nativeStyle.underline) add(TextDecoration.Underline)
        if (strikeout || nativeStyle.strikeout) add(TextDecoration.LineThrough)
    }
    return SpanStyle(
        fontWeight = if (nativeStyle.fontFace != NativeFontFace.Regular) {
            FontWeight(if (bold) maxOf(700, nativeStyle.fontWeight) else nativeStyle.fontWeight)
        } else if (bold || nativeStyle.bold) FontWeight.Bold else null,
        fontStyle = if (italic || nativeStyle.italic) FontStyle.Italic else null,
        color = foreground?.let { Color(it.argb) } ?: if (nativeStyle.colorRgb != 0)
            Color(0xFF000000.toInt() or nativeStyle.colorRgb) else Color.Unspecified,
        background = background?.let { Color(it.argb) } ?: Color.Unspecified,
        textDecoration = decorations.takeIf(List<TextDecoration>::isNotEmpty)
            ?.let(TextDecoration::combine),
    )
}

private fun inlineContentKey(index: Int): String = "msn-emoticon-$index"

internal class MsnEditText(context: Context) : EditText(context) {
    private var nativeStyle = NativeTextStyle()

    fun setNativeStyle(style: NativeTextStyle) {
        if (nativeStyle == style) return
        nativeStyle = style
        renderEmoticons()
    }
    var onValueChanged: (String) -> Unit = {}
    var onSelectionChangedListener: ((Int, Int) -> Unit)? = null
    var allowUnicodeEmoji = false
    private var applyingValue = false
    private var convertTypedShortcuts = true
    private var useOriginalEmoticons = true
    private var messengerPlusEnabled = true
    private var hideControlCharacters = true
    private var singleLineEditor = false
    private var adaptiveColor = 0xFF000000.toInt()
    private val messengerPlusSpans = mutableListOf<Any>()

    init {
        background = null
        gravity = Gravity.CENTER_VERTICAL
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        imeOptions = EditorInfo.IME_ACTION_NONE
        minLines = 1
        setHorizontallyScrolling(false)
        val density = resources.displayMetrics.density
        setPaddingRelative(
            (4 * density).roundToInt(),
            (12 * density).roundToInt(),
            (12 * density).roundToInt(),
            (12 * density).roundToInt(),
        )
        hint = "Message"
        contentDescription = "Message input"
        addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(editable: Editable) {
                    if (applyingValue) return
                    val rawValue = editable.toString()
                    val sanitizedValue = if (allowUnicodeEmoji) rawValue else rawValue.withoutUnicodeEmoji()
                    if (sanitizedValue != rawValue) {
                        val cursor = selectionStart.coerceAtLeast(0)
                        applyingValue = true
                        setText(sanitizedValue)
                        setSelection(cursor.coerceAtMost(sanitizedValue.length))
                        applyingValue = false
                    }
                    renderEmoticons()
                    onValueChanged(sanitizedValue)
                }
            },
        )
    }

    fun setExternalValue(value: String) {
        if (text.toString() == value) return
        applyingValue = true
        setText(value)
        setSelection(value.length)
        applyingValue = false
        renderEmoticons()
    }

    fun configureSingleLine(singleLine: Boolean) {
        if (singleLineEditor == singleLine) return
        singleLineEditor = singleLine
        setSingleLine(singleLine)
        imeOptions = if (singleLine) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NONE
    }

    fun replaceSelection(replacement: String, selectReplacement: Boolean) {
        val safeReplacement = if (allowUnicodeEmoji) replacement else replacement.withoutUnicodeEmoji()
        mutateText { editable ->
            val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
            val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(start)
            editable.replace(start, end, safeReplacement)
            val replacementEnd = start + safeReplacement.length
            if (selectReplacement) {
                setSelection(start, replacementEnd)
            } else {
                setSelection(replacementEnd)
            }
        }
    }

    fun deleteBackward(): Boolean {
        val editable = text ?: return false
        val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(start)
        val deleteFrom = if (start == end) {
            val previous = TextUtils.getOffsetBefore(editable, start)
            editable.getSpans(previous, start, MsnGlyphSpan::class.java)
                .firstOrNull { editable.getSpanEnd(it) == start }
                ?.let(editable::getSpanStart) ?: previous
        } else {
            start
        }
        if (deleteFrom == end) return false
        mutateText { value ->
            value.delete(deleteFrom, end)
            setSelection(deleteFrom)
        }
        return true
    }

    fun surroundSelection(prefix: String, suffix: String, wrapCollapsed: Boolean = false) {
        mutateText { editable ->
            val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
            val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(start)
            if (start == end) {
                editable.insert(start, prefix + if (wrapCollapsed) suffix else "")
                setSelection(start + prefix.length)
            } else {
                editable.insert(end, suffix)
                editable.insert(start, prefix)
                setSelection(start + prefix.length, end + prefix.length)
            }
        }
    }

    fun transformSelection(transform: (String) -> String) {
        mutateText { editable ->
            val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
            val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(start)
            if (start != end) {
                val replacement = transform(editable.substring(start, end))
                editable.replace(start, end, replacement)
                setSelection(start, start + replacement.length)
            }
        }
    }

    fun replaceAllText(replacement: String, selectionStart: Int, selectionEnd: Int) {
        mutateText { editable ->
            editable.replace(0, editable.length, replacement)
            setSelection(
                selectionStart.coerceIn(0, replacement.length),
                selectionEnd.coerceIn(0, replacement.length),
            )
        }
    }

    fun setTextAppearance(color: Int, textSizePixels: Float, adaptiveColor: Int = color) {
        setTextColor(color)
        setHintTextColor(color.withAlpha(0.6f))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePixels)
        if (this.adaptiveColor != adaptiveColor) {
            this.adaptiveColor = adaptiveColor
            renderEmoticons()
        }
    }

    fun setEmoticonAppearance(
        convertTypedShortcuts: Boolean,
        useOriginalEmoticons: Boolean,
        messengerPlusEnabled: Boolean,
        hideControlCharacters: Boolean = true,
    ) {
        if (this.convertTypedShortcuts == convertTypedShortcuts &&
            this.useOriginalEmoticons == useOriginalEmoticons &&
            this.messengerPlusEnabled == messengerPlusEnabled &&
            this.hideControlCharacters == hideControlCharacters
        ) {
            return
        }
        this.convertTypedShortcuts = convertTypedShortcuts
        this.useOriginalEmoticons = useOriginalEmoticons
        this.messengerPlusEnabled = messengerPlusEnabled
        this.hideControlCharacters = hideControlCharacters
        renderEmoticons()
    }

    override fun onSelectionChanged(selectionStart: Int, selectionEnd: Int) {
        super.onSelectionChanged(selectionStart, selectionEnd)
        // EditText can dispatch this callback from its superclass constructor,
        // before Kotlin property initializers in this class have run.
        onSelectionChangedListener?.invoke(selectionStart, selectionEnd)
    }

    private fun mutateText(mutation: (Editable) -> Unit) {
        val editable = text ?: return
        applyingValue = true
        try {
            mutation(editable)
        } finally {
            applyingValue = false
        }
        renderEmoticons()
        onValueChanged(editable.toString())
    }

    private val animationTicker = Runnable {
        invalidate()
        scheduleAnimationTick()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleAnimationTick()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animationTicker)
        super.onDetachedFromWindow()
    }

    private fun renderEmoticons() {
        val editable = text ?: return
        editable.getSpans(0, editable.length, MsnGlyphSpan::class.java).forEach(editable::removeSpan)
        messengerPlusSpans.forEach(editable::removeSpan)
        messengerPlusSpans.clear()
        val entireText = MessengerPlusTextRange(0, editable.length)
        if (nativeStyle.bold) editable.addMessengerPlusSpan(StyleSpan(Typeface.BOLD), entireText)
        if (nativeStyle.italic) editable.addMessengerPlusSpan(StyleSpan(Typeface.ITALIC), entireText)
        if (nativeStyle.fontFace != NativeFontFace.Regular) {
            editable.addMessengerPlusSpan(NativeFontWeightSpan(nativeStyle.fontWeight, nativeStyle.italic), entireText)
        }
        if (nativeStyle.underline) editable.addMessengerPlusSpan(UnderlineSpan(), entireText)
        if (nativeStyle.strikeout) editable.addMessengerPlusSpan(StrikethroughSpan(), entireText)
        val parsed = MessengerPlusTextCodec.parse(editable.toString(), adaptiveColor)
        if (messengerPlusEnabled || hideControlCharacters) {
            parsed.controlRanges.forEach { range ->
                editable.addMessengerPlusSpan(HiddenMessengerPlusControlSpan(), range)
            }
        }
        if (messengerPlusEnabled) {
            parsed.sourceStyleSpans.forEach { span ->
                val style = span.style
                if (style.bold) editable.addMessengerPlusSpan(StyleSpan(Typeface.BOLD), span.range)
                if (style.italic) {
                    editable.addMessengerPlusSpan(StyleSpan(Typeface.ITALIC), span.range)
                }
                if (nativeStyle.fontFace != NativeFontFace.Regular) {
                    editable.addMessengerPlusSpan(NativeFontWeightSpan(
                        if (style.bold) maxOf(700, nativeStyle.fontWeight) else nativeStyle.fontWeight,
                        nativeStyle.italic || style.italic,
                    ), span.range)
                }
                if (style.underline) {
                    editable.addMessengerPlusSpan(UnderlineSpan(), span.range)
                }
                if (style.strikeout) {
                    editable.addMessengerPlusSpan(StrikethroughSpan(), span.range)
                }
                style.foreground?.let { color ->
                    editable.addMessengerPlusSpan(ForegroundColorSpan(color.argb), span.range)
                }
                style.background?.let { color ->
                    editable.addMessengerPlusSpan(BackgroundColorSpan(color.argb), span.range)
                }
            }
        }
        val boxPx = max(1, (MSN_EMOTICON_GRID_PX * resources.displayMetrics.density).roundToInt())
        findMsnEmoticons(
            text = editable.toString(),
            includeTypedShortcuts = convertTypedShortcuts,
        ).forEach { match ->
            val span = if (useOriginalEmoticons) {
                MsnEmoticonImages.loadFirst(context, match.emoticon.artworkAssetCandidates)
                    ?.let { image -> OriginalMsnGlyphSpan(image, boxPx) }
            } else {
                UnicodeMsnGlyphSpan(match.emoticon.unicode)
            }
            if (span != null) {
                editable.setSpan(
                    span,
                    match.range.start,
                    match.range.endExclusive,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        scheduleAnimationTick()
        invalidate()
    }

    private fun scheduleAnimationTick() {
        removeCallbacks(animationTicker)
        if (!isAttachedToWindow) return
        val editable = text ?: return
        val now = SystemClock.uptimeMillis()
        val nextDelay = editable
            .getSpans(0, editable.length, OriginalMsnGlyphSpan::class.java)
            .mapNotNull { it.image.millisUntilNextFrame(now) }
            .minOrNull()
        if (nextDelay != null) {
            postDelayed(animationTicker, nextDelay)
        }
    }

    private fun Editable.addMessengerPlusSpan(span: Any, range: MessengerPlusTextRange) {
        if (range.endExclusive <= range.start || range.endExclusive > length) return
        setSpan(span, range.start, range.endExclusive, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        messengerPlusSpans += span
    }
}

/** Uses numeric weights so Light, Semilight, Semibold and Black are not reduced to bold/regular. */
internal class NativeFontWeightSpan(private val weight: Int, private val italic: Boolean) : MetricAffectingSpan() {
    override fun updateDrawState(paint: TextPaint) = applyWeight(paint)
    override fun updateMeasureState(paint: TextPaint) = applyWeight(paint)

    private fun applyWeight(paint: TextPaint) {
        paint.typeface = if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(paint.typeface ?: Typeface.DEFAULT, weight, italic)
        } else {
            val family = when {
                weight <= 350 -> "sans-serif-light"
                weight == 600 -> "sans-serif-medium"
                weight >= 900 -> "sans-serif-black"
                else -> "sans-serif"
            }
            Typeface.create(family, if (italic) Typeface.ITALIC else Typeface.NORMAL)
        }
    }
}

private sealed class MsnGlyphSpan : ReplacementSpan()

private class HiddenMessengerPlusControlSpan : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fontMetrics: Paint.FontMetricsInt?,
    ): Int = 0

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) = Unit
}

private class UnicodeMsnGlyphSpan(private val unicode: String) : MsnGlyphSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fontMetrics: Paint.FontMetricsInt?,
    ): Int = ceil(paint.measureText(unicode).toDouble()).toInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        canvas.drawText(unicode, x, y.toFloat(), paint)
    }
}

private class OriginalMsnGlyphSpan(
    val image: MsnEmoticonImage,
    private val boxPx: Int,
) : MsnGlyphSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fontMetrics: Paint.FontMetricsInt?,
    ): Int = boxPx

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val textCenterY = y + (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val drawTop = textCenterY - boxPx / 2f
        image.draw(canvas, x, drawTop, boxPx.toFloat(), SystemClock.uptimeMillis())
    }
}

private fun Int.withAlpha(alpha: Float): Int {
    val boundedAlpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
    return (this and 0x00FFFFFF) or (boundedAlpha shl 24)
}
