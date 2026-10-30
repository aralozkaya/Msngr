package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.pages.AboutSettingsScreen
import org.aralozkaya.msngr.pages.AppearanceSettingsScreen
import org.aralozkaya.msngr.pages.ConnectionInformationScreen
import org.aralozkaya.msngr.pages.ConnectionSettingsScreen
import org.aralozkaya.msngr.pages.ContactInvitationSettingsScreen
import org.aralozkaya.msngr.pages.CustomizedNotificationsScreen
import org.aralozkaya.msngr.pages.EmoticonSettingsScreen
import org.aralozkaya.msngr.pages.InAppNotificationSettingsScreen
import org.aralozkaya.msngr.pages.MessengerPlusSettingsScreen
import org.aralozkaya.msngr.pages.SettingsDestination
import org.aralozkaya.msngr.pages.SettingsScreen
import org.aralozkaya.msngr.pages.UpdateSettingsScreen
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme

class SettingsActivity : MsngrActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectToLoginIfNeeded()) {
            return
        }
        setContent {
            MsngrTheme {
                SettingsRoute(
                    onBack = ::finish,
                    onDeleteAllChatHistories = {
                        lifecycleScope.launch {
                            appContainer.messengerRepository.clearConversationHistory()
                        }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        redirectToLoginIfNeeded()
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsRoute(
    onBack: () -> Unit,
    onDeleteAllChatHistories: () -> Unit,
) {
    val backStack = rememberSaveable(
        saver = listSaver<SnapshotStateList<SettingsDestination>, SettingsDestination>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { mutableStateListOf(SettingsDestination.Root) }
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val navigateTo: (SettingsDestination) -> Unit = { destination ->
        if (backStack.last() != destination) backStack.add(destination)
    }
    val pop: () -> Unit = {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }
    val transition: AnimatedContentTransitionScope<*>.(
        AnimatedContentTransitionScope.SlideDirection,
    ) -> ContentTransform = { direction ->
        (
            slideIntoContainer(
                towards = direction,
                animationSpec = spatialSpec,
                initialOffset = { it / 4 },
            ) + fadeIn(animationSpec = effectsSpec)
        ).togetherWith(
            slideOutOfContainer(
                towards = direction,
                animationSpec = spatialSpec,
                targetOffset = { it / 4 },
            ) + fadeOut(animationSpec = effectsSpec),
        )
    }

    NavDisplay(
        backStack = backStack,
        onBack = pop,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            transition(AnimatedContentTransitionScope.SlideDirection.Start)
        },
        popTransitionSpec = {
            transition(AnimatedContentTransitionScope.SlideDirection.End)
        },
        entryProvider = { key ->
            NavEntry(key) { destination ->
                SecondaryPageScaffold(
                    title = destination.title,
                    onBack = {
                        if (destination == SettingsDestination.Root) {
                            onBack()
                        } else {
                            pop()
                        }
                    },
                ) { innerPadding, scrollBehavior ->
                    when (destination) {
                        SettingsDestination.Root -> SettingsScreen(
                            innerPadding = innerPadding,
                            scrollBehavior = scrollBehavior,
                            onDestinationSelected = navigateTo,
                            onDeleteAllChatHistories = onDeleteAllChatHistories,
                        )
                        SettingsDestination.Appearance -> AppearanceSettingsScreen(
                            innerPadding = innerPadding,
                            scrollBehavior = scrollBehavior,
                        )
                        SettingsDestination.InAppNotifications -> InAppNotificationSettingsScreen(
                            innerPadding = innerPadding,
                            scrollBehavior = scrollBehavior,
                            onCustomizedNotifications = { navigateTo(SettingsDestination.CustomizedNotifications) },
                        )
                        SettingsDestination.CustomizedNotifications -> CustomizedNotificationsScreen(innerPadding, scrollBehavior)
                        SettingsDestination.MessengerPlus -> MessengerPlusSettingsScreen(
                            innerPadding = innerPadding,
                            scrollBehavior = scrollBehavior,
                        )
                        SettingsDestination.Emoticons -> EmoticonSettingsScreen(
                            innerPadding = innerPadding,
                            scrollBehavior = scrollBehavior,
                        )
                        SettingsDestination.ContactInvitations -> ContactInvitationSettingsScreen(
                            innerPadding = innerPadding,
                            scrollBehavior = scrollBehavior,
                        )
                        SettingsDestination.Connection -> ConnectionSettingsScreen(
                            innerPadding = innerPadding,
                            scrollBehavior = scrollBehavior,
                            onDestinationSelected = navigateTo,
                        )
                        SettingsDestination.ConnectionInformation -> ConnectionInformationScreen(
                            innerPadding = innerPadding,
                            scrollBehavior = scrollBehavior,
                        )
                        SettingsDestination.About -> AboutSettingsScreen(
                            innerPadding = innerPadding,
                            scrollBehavior = scrollBehavior,
                            onLicenses = { navigateTo(SettingsDestination.Licenses) },
                        )
                        SettingsDestination.Licenses -> org.aralozkaya.msngr.pages.OpenSourceLicensesScreen(innerPadding)
                        SettingsDestination.Updates -> UpdateSettingsScreen(
                            innerPadding = innerPadding,
                            scrollBehavior = scrollBehavior,
                        )
                    }
                }
            }
        },
    )
}
