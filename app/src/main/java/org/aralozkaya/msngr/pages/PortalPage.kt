@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package org.aralozkaya.msngr.pages

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.aralozkaya.msngr.EmoticonPrefs
import org.aralozkaya.msngr.MsnEmoticonText
import org.aralozkaya.msngr.PortalWebTarget
import org.aralozkaya.msngr.R
import org.aralozkaya.msngr.data.Contact
import org.aralozkaya.msngr.data.MessengerBannerAd
import org.aralozkaya.msngr.data.MessengerTextAd
import org.aralozkaya.msngr.data.MsnpClientConfigService
import org.aralozkaya.msngr.data.PortalState
import org.aralozkaya.msngr.data.WhatsNewActivity
import org.aralozkaya.msngr.data.WhatsNewKind
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.messengerPlusPlainText
import org.aralozkaya.msngr.pages.components.Avatar
import org.aralozkaya.msngr.pages.components.MsngrLinearWavyProgressIndicator
import org.aralozkaya.msngr.pages.components.PageContentList
import org.aralozkaya.msngr.rememberRemoteImagePainter
import java.net.URL

@Composable
fun PortalPage(
    innerPadding: PaddingValues,
    scrollBehavior: TopAppBarScrollBehavior,
    portalState: PortalState,
    contacts: List<Contact>,
    showAds: Boolean,
    isMockServer: Boolean,
    onRefresh: () -> Unit,
    onOpenWebTarget: (PortalWebTarget) -> Unit,
    onOpenContactProfile: (String) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val fallbackUriHandler = LocalUriHandler.current
    val portalUriHandler = remember(onOpenWebTarget, fallbackUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                val safeUrl = MsnpClientConfigService.normalizeWebUrl(uri)
                if (safeUrl != null) {
                    val hostTitle = runCatching { URL(safeUrl).host }.getOrNull().orEmpty().ifBlank { safeUrl }
                    onOpenWebTarget(PortalWebTarget(title = hostTitle, url = safeUrl))
                } else {
                    fallbackUriHandler.openUri(uri)
                }
            }
        }
    }
    val emoticonSettings by remember(context) { EmoticonPrefs.state(context) }.collectAsState()
    val contactsByName = remember(contacts) {
        contacts.associateBy { it.displayName.messengerPlusPlainText().lowercase() }
    }
    var selectedActivity by remember { mutableStateOf<WhatsNewActivity?>(null) }
    val hasBannerOrTextAd = showAds && (portalState.bannerAd != null || portalState.textAd != null)
    val pullRefreshState = rememberPullToRefreshState()
    var isPullRefreshing by remember { mutableStateOf(false) }
    val isBackendLoading = portalState.isLoadingWhatsNew || portalState.isLoadingConfig
    val currentBackendLoading by rememberUpdatedState(isBackendLoading)

    LaunchedEffect(isPullRefreshing, isBackendLoading) {
        if (isPullRefreshing && !isBackendLoading) {
            delay(350)
            if (!currentBackendLoading) {
                isPullRefreshing = false
            }
        }
    }

    CompositionLocalProvider(LocalUriHandler provides portalUriHandler) {
        PullToRefreshBox(
            isRefreshing = isPullRefreshing,
            onRefresh = {
                if (!isMockServer && !isBackendLoading) {
                    isPullRefreshing = true
                    onRefresh()
                }
            },
            state = pullRefreshState,
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullRefreshState,
                    isRefreshing = isPullRefreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = innerPadding.calculateTopPadding()),
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .testTag("portal_pull_refresh"),
        ) {
            PageContentList(
                listState = listState,
                innerPadding = innerPadding,
                scrollBehavior = scrollBehavior,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("portal_page"),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                // Keep index 0's item key stable before the feed header so late-arriving ads
                // expand downward instead of shifting the initial scroll position below the ad.
                item(key = "portal-ad-slot") {
                    if (hasBannerOrTextAd) {
                        AdvertisementCard(
                            bannerAd = portalState.bannerAd,
                            textAd = portalState.textAd,
                            onOpenWebTarget = onOpenWebTarget,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                    }
                }

                item(key = "whats-new-header") {
                    Text(
                        text = stringResource(R.string.portal_whats_new_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }

                if (portalState.isLoadingWhatsNew && !isPullRefreshing) {
                    item(key = "whats-new-loading") {
                        MsngrLinearWavyProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        )
                    }
                }

        val items = portalState.whatsNew
        when {
            isMockServer -> {
                item(key = "whats-new-mock-empty") {
                    SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                        modifier = Modifier.fillMaxWidth(),
                        content = {
                            Text(
                                text = stringResource(R.string.portal_whats_new_unavailable),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            items.isEmpty() && portalState.whatsNewError != null -> {
                item(key = "whats-new-error") {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = portalState.whatsNewError,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            FilledTonalButton(
                                onClick = onRefresh,
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            items.isEmpty() && !portalState.isLoadingWhatsNew -> {
                item(key = "whats-new-empty") {
                    SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
                        modifier = Modifier.fillMaxWidth(),
                        content = {
                            Text(
                                text = stringResource(R.string.portal_whats_new_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            else -> {
                itemsIndexed(
                    items = items,
                    key = { index, item -> "${item.id}_$index" },
                ) { index, activity ->
                    val matchedContact = contactsByName[activity.publisherName.lowercase()]
                    WhatsNewRow(
                        index = index,
                        count = items.size,
                        activity = activity,
                        matchedContact = matchedContact,
                        useOriginalEmoticons = emoticonSettings.useOriginalEmoticons,
                        onClick = { selectedActivity = activity },
                    )
                }
            }
        }
        }
    }

    selectedActivity?.let { activity ->
        val matchedContact = contactsByName[activity.publisherName.lowercase()]
        WhatsNewDetailDialog(
            activity = activity,
            matchedContact = matchedContact,
            useOriginalEmoticons = emoticonSettings.useOriginalEmoticons,
            onOpenContactProfile = matchedContact?.let { contact ->
                {
                    selectedActivity = null
                    onOpenContactProfile(contact.id)
                }
            },
            onDismiss = { selectedActivity = null },
        )
    }
    }
}

@Composable
private fun AdvertisementCard(
    bannerAd: MessengerBannerAd?,
    textAd: MessengerTextAd?,
    onOpenWebTarget: (PortalWebTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val adLabel = stringResource(R.string.portal_ad_label)
    val bannerPainter = rememberRemoteImagePainter(bannerAd?.imageUrl)

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("portal_ad_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = adLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (bannerAd != null && bannerPainter != null) {
                Image(
                    painter = bannerPainter,
                    contentDescription = adLabel,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp, max = 110.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable {
                            onOpenWebTarget(PortalWebTarget(adLabel, bannerAd.targetUrl))
                        }
                        .testTag("portal_ad_banner"),
                )
            }
            if (textAd != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .then(
                            if (textAd.hitUrl.isNotBlank()) {
                                Modifier.clickable {
                                    onOpenWebTarget(PortalWebTarget(adLabel, textAd.hitUrl))
                                }
                            } else {
                                Modifier
                            },
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = textAd.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (textAd.hitUrl.isNotBlank()) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WhatsNewRow(
    index: Int,
    count: Int,
    activity: WhatsNewActivity,
    matchedContact: Contact?,
    useOriginalEmoticons: Boolean,
    onClick: () -> Unit,
) {
    val photoPainter = rememberRemoteImagePainter(
        if (activity.kind == WhatsNewKind.Photo) activity.mediaUrl else null,
    )
    val timestamp = remember(activity.publishedAtMillis) {
        if (activity.publishedAtMillis > 0L) {
            DateUtils.getRelativeTimeSpanString(
                activity.publishedAtMillis,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            ).toString()
        } else {
            ""
        }
    }
    val displayText = activity.resolvedDisplayText()

    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("whats_new_item_${activity.id}"),
        leadingContent = {
            WhatsNewPublisherAvatar(
                activity = activity,
                matchedContact = matchedContact,
                modifier = Modifier.testTag("whats_new_avatar_${activity.id}"),
            )
        },
        supportingContent = if (photoPainter != null || !activity.sourceLabel.isNullOrBlank()) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (photoPainter != null) {
                        Image(
                            painter = photoPainter,
                            contentDescription = displayText,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .clip(MaterialTheme.shapes.medium),
                        )
                    }
                    activity.sourceLabel?.takeIf(String::isNotBlank)?.let { source ->
                        Text(
                            text = source,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            null
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = activity.publisherName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (timestamp.isNotEmpty()) {
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            if (displayText.isNotEmpty()) {
                MsnEmoticonText(
                    text = displayText,
                    useOriginalEmoticons = useOriginalEmoticons,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (activity.kind == WhatsNewKind.Photo) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    linkify = true,
                    modifier = Modifier.testTag("whats_new_description_${activity.id}"),
                )
            }
        }
    }
}

private val WhatsNewAvatarSize = 44.dp

@Composable
private fun WhatsNewPublisherAvatar(
    activity: WhatsNewActivity,
    matchedContact: Contact?,
    modifier: Modifier = Modifier,
) {
    val remoteAvatarPainter = rememberRemoteImagePainter(
        if (matchedContact?.avatarUri.isNullOrBlank()) activity.publisherAvatarUrl else null,
    )
    when {
        activity.kind == WhatsNewKind.Announcement -> {
            Box(
                modifier = modifier
                    .size(WhatsNewAvatarSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        remoteAvatarPainter != null -> {
            Image(
                painter = remoteAvatarPainter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .size(WhatsNewAvatarSize)
                    .clip(CircleShape),
            )
        }
        else -> {
            Avatar(
                name = activity.publisherName,
                avatarUri = matchedContact?.avatarUri,
                presence = matchedContact?.presence ?: Presence.Online,
                showPresenceIndicator = matchedContact != null,
                size = WhatsNewAvatarSize,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun WhatsNewDetailDialog(
    activity: WhatsNewActivity,
    matchedContact: Contact?,
    useOriginalEmoticons: Boolean,
    onOpenContactProfile: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val photoPainter = rememberRemoteImagePainter(
        if (activity.kind == WhatsNewKind.Photo || activity.kind == WhatsNewKind.DisplayPicture) {
            activity.mediaUrl
        } else {
            null
        },
    )
    val fullTimestamp = remember(activity.publishedAtMillis, context) {
        if (activity.publishedAtMillis > 0L) {
            DateUtils.formatDateTime(
                context,
                activity.publishedAtMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
            )
        } else {
            ""
        }
    }
    val displayText = activity.resolvedDisplayText()

    AlertDialog(
        modifier = Modifier.testTag("whats_new_detail_dialog"),
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WhatsNewPublisherAvatar(
                    activity = activity,
                    matchedContact = matchedContact,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = activity.publisherName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (fullTimestamp.isNotEmpty()) {
                        Text(
                            text = fullTimestamp,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (displayText.isNotEmpty()) {
                    MsnEmoticonText(
                        text = displayText,
                        useOriginalEmoticons = useOriginalEmoticons,
                        style = MaterialTheme.typography.bodyLarge,
                        linkify = true,
                    )
                }
                if (photoPainter != null) {
                    Image(
                        painter = photoPainter,
                        contentDescription = displayText,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .clip(MaterialTheme.shapes.medium),
                    )
                }
                activity.sourceLabel?.takeIf(String::isNotBlank)?.let { source ->
                    Text(
                        text = source,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        dismissButton = onOpenContactProfile?.let { openProfile ->
            {
                TextButton(
                    onClick = openProfile,
                    modifier = Modifier.testTag("whats_new_detail_profile"),
                ) {
                    Text("View profile")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("whats_new_detail_close"),
            ) {
                Text(stringResource(R.string.portal_webview_close))
            }
        },
    )
}

private fun WhatsNewActivity.resolvedDisplayText(): String = text.ifBlank {
    when (kind) {
        WhatsNewKind.DisplayPicture -> "has a new display picture"
        WhatsNewKind.Photo -> "shared a photo"
        else -> ""
    }
}
