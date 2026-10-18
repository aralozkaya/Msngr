package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.aralozkaya.msngr.pages.AboutSettingsScreen
import org.aralozkaya.msngr.pages.AppearanceSettingsScreen
import org.aralozkaya.msngr.pages.ConnectionInformationScreen
import org.aralozkaya.msngr.pages.ConnectionSettingsScreen
import org.aralozkaya.msngr.pages.ContactInvitationSettingsScreen
import org.aralozkaya.msngr.pages.EmoticonSettingsScreen
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
private fun SettingsRoute(
    onBack: () -> Unit,
    onDeleteAllChatHistories: () -> Unit,
) {
    var selectedDestination by rememberSaveable { mutableStateOf<SettingsDestination?>(null) }
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    BackHandler(enabled = selectedDestination != null) {
        selectedDestination = selectedDestination?.parent
    }

    AnimatedContent(
        targetState = selectedDestination,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val direction = if (targetState == initialState?.parent) {
                AnimatedContentTransitionScope.SlideDirection.End
            } else {
                AnimatedContentTransitionScope.SlideDirection.Start
            }
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
        },
        label = "Settings navigation",
    ) { destination ->
        SecondaryPageScaffold(
            title = destination?.title ?: "Settings",
            onBack = {
                if (destination == null) {
                    onBack()
                } else {
                    selectedDestination = destination.parent
                }
            },
        ) { innerPadding, scrollBehavior ->
            when (destination) {
                null -> SettingsScreen(
                    innerPadding = innerPadding,
                    scrollBehavior = scrollBehavior,
                    onDestinationSelected = { selectedDestination = it },
                    onDeleteAllChatHistories = onDeleteAllChatHistories,
                )
                SettingsDestination.Appearance -> AppearanceSettingsScreen(
                    innerPadding = innerPadding,
                    scrollBehavior = scrollBehavior,
                )
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
                    onDestinationSelected = { selectedDestination = it },
                )
                SettingsDestination.ConnectionInformation -> ConnectionInformationScreen(
                    innerPadding = innerPadding,
                    scrollBehavior = scrollBehavior,
                )
                SettingsDestination.About -> AboutSettingsScreen(
                    innerPadding = innerPadding,
                    scrollBehavior = scrollBehavior,
                )
                SettingsDestination.Updates -> UpdateSettingsScreen(
                    innerPadding = innerPadding,
                    scrollBehavior = scrollBehavior,
                )
            }
        }
    }
}
