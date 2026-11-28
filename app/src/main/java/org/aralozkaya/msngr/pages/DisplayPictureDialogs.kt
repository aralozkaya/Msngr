@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package org.aralozkaya.msngr.pages

import org.aralozkaya.msngr.pages.components.MsngrCircularWavyProgressIndicator
import org.aralozkaya.msngr.pages.components.DestructiveOutlinedButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PhotoSizeSelectActual
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.localizedDefaultPictureVersion
import org.aralozkaya.msngr.localizedName
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import org.aralozkaya.msngr.DefaultDisplayPicture
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.loadDefaultDisplayPictures
import org.aralozkaya.msngr.pages.components.Avatar
import org.aralozkaya.msngr.rememberAvatarImage
import org.aralozkaya.msngr.ui.theme.OwnDisplayPictureCornerRadius
import org.aralozkaya.msngr.ui.theme.ownDisplayPicture

@Composable
internal fun DisplayPictureOptionsDialog(
    name: String,
    pictureUri: String?,
    onTakePhoto: () -> Unit,
    onSelectPhoto: () -> Unit,
    onDefaultPhoto: () -> Unit,
    onViewPicture: () -> Unit,
    onRemovePicture: () -> Unit,
    onDismiss: () -> Unit,
) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(
                Modifier.heightIn(max = (windowHeight - 64.dp).coerceAtLeast(200.dp))
                    .verticalScroll(rememberScrollState()).padding(24.dp).testTag("display-picture-options"),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Avatar(name, pictureUri, Presence.Offline, modifier = Modifier.size(80.dp),
                        showPresenceIndicator = false, shape = MaterialTheme.shapes.ownDisplayPicture)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.display_picture_dialog_title), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.display_picture_dialog_subtitle_you_singular), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (pictureUri != null) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = onViewPicture) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.display_picture_action_view), Modifier.padding(start = 8.dp))
                        }
                        DestructiveOutlinedButton(onClick = onRemovePicture) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.common_remove), Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    PictureSourceOption(
                        0,
                        stringResource(R.string.display_picture_option_take_photo),
                        stringResource(R.string.display_picture_option_take_photo_sub),
                        Icons.Default.CameraAlt,
                        onTakePhoto,
                    )
                    PictureSourceOption(
                        1,
                        stringResource(R.string.display_picture_option_select_photo),
                        stringResource(R.string.display_picture_option_select_photo_sub_you_singular),
                        Icons.Default.PhotoLibrary,
                        onSelectPhoto,
                    )
                    PictureSourceOption(
                        2,
                        stringResource(R.string.display_picture_option_default_photo),
                        stringResource(R.string.display_picture_option_default_photo_sub),
                        Icons.Default.PhotoSizeSelectActual,
                        onDefaultPhoto,
                    )
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        }
    }
}

@Composable
private fun PictureSourceOption(index: Int, title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, 3),
        modifier = Modifier.fillMaxWidth().testTag("picture-source-$index"),
        leadingContent = {
            Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
    ) { Text(title, style = MaterialTheme.typography.titleMedium) }
}

private const val DP_FILTER_ALL_KEY = "__all__"

@Composable
internal fun DefaultDisplayPictureDialog(onSelect: (DefaultDisplayPicture) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val compact = windowHeight < 500.dp
    val filterState = rememberLazyListState()
    var pictures by remember { mutableStateOf<List<DefaultDisplayPicture>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var version by rememberSaveable { mutableStateOf(DP_FILTER_ALL_KEY) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val selection = pictures?.firstOrNull { it.id == selectedId }
    val versions = remember(pictures) { listOf(DP_FILTER_ALL_KEY) + pictures.orEmpty().map { it.version }.distinct() }
    LaunchedEffect(version, versions) {
        // Bring a partially visible selected chip fully into view on narrow screens.
        filterState.animateScrollToItem(versions.indexOf(version).coerceAtLeast(0))
    }
    LaunchedEffect(context, retry) {
        error = null
        try {
            pictures = context.loadDefaultDisplayPictures()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = resources.getString(R.string.display_picture_defaults_load_failed) }
    }
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(24.dp).widthIn(max = 560.dp).fillMaxWidth(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.height((windowHeight - 64.dp).coerceIn(240.dp, 760.dp))
                .testTag("default-display-pictures")) {
                Text(stringResource(R.string.display_picture_defaults_title), style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 8.dp))
                if (!compact) Text(stringResource(R.string.display_picture_defaults_subtitle_you_singular),
                    Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(state = filterState, contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = if (compact) 0.dp else 12.dp).selectableGroup()) {
                    items(versions) { filterKey ->
                        val chipLabel = if (filterKey == DP_FILTER_ALL_KEY) {
                            stringResource(R.string.common_all)
                        } else {
                            localizedDefaultPictureVersion(filterKey)
                        }
                        val chipTag = if (filterKey == DP_FILTER_ALL_KEY) "All" else filterKey
                        FilterChip(
                            selected = version == filterKey,
                            onClick = { version = filterKey },
                            label = { Text(chipLabel) },
                            modifier = Modifier.testTag("picture-version-$chipTag"),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    when {
                        error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(requireNotNull(error))
                            OutlinedButton(shapes = ButtonDefaults.shapes(), onClick = { retry++ }) {
                                Text(stringResource(R.string.common_try_again))
                            }
                        }
                        pictures == null -> MsngrCircularWavyProgressIndicator()
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(88.dp),
                            contentPadding = PaddingValues(24.dp, 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxSize().selectableGroup().testTag("default-picture-grid"),
                        ) {
                            items(pictures.orEmpty().filter { version == DP_FILTER_ALL_KEY || it.version == version }, key = { it.id }) { picture ->
                                DefaultPictureTile(picture, picture.id == selectedId) { selectedId = picture.id }
                            }
                        }
                    }
                }
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                        Button(onClick = { selection?.let(onSelect) }, enabled = selection != null,
                            shapes = ButtonDefaults.shapes(), modifier = Modifier.testTag("use-default-picture")) {
                            Text(stringResource(R.string.display_picture_use_photo))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DefaultPictureTile(picture: DefaultDisplayPicture, checked: Boolean, onClick: () -> Unit) {
    val cornerRadius by animateDpAsState(if (checked) OwnDisplayPictureCornerRadius else 16.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(), label = "picture selection corners")
    val borderWidth by animateDpAsState(if (checked) 3.dp else 1.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(), label = "picture selection border")
    val localizedName = picture.localizedName()
    val versionLabel = stringResource(R.string.default_picture_version_prefix, localizedDefaultPictureVersion(picture.version))
    val description = stringResource(
        if (picture.animated) R.string.display_picture_tile_desc_animated else R.string.display_picture_tile_desc_static,
        localizedName,
        versionLabel,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(cornerRadius),
            border = BorderStroke(borderWidth,
                if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).testTag("default-picture-${picture.id}")
                .semantics {
                    role = Role.RadioButton
                    selected = checked
                    contentDescription = description
                },
        ) {
            Box {
                DefaultPictureImage(picture, Modifier.fillMaxSize().padding(5.dp),
                    shape = RoundedCornerShape((cornerRadius - 5.dp).coerceAtLeast(0.dp)))
                if (picture.animated) Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = .9f),
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ) { Text(stringResource(R.string.common_animated), Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall) }
                if (checked) Icon(Icons.Default.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surface).size(24.dp))
            }
        }
        Text(localizedName, style = MaterialTheme.typography.labelMedium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
private fun DefaultPictureImage(picture: DefaultDisplayPicture, modifier: Modifier, shape: Shape) {
    val image = rememberAvatarImage(picture.uri)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer), contentAlignment = Alignment.Center) {
        image.painter?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: if (image.isLoading) MsngrCircularWavyProgressIndicator(Modifier.size(24.dp)) else
                Icon(Icons.Default.PhotoSizeSelectActual, contentDescription = stringResource(R.string.display_picture_unavailable))
    }
}
