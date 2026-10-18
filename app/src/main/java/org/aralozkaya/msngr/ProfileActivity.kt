package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import org.aralozkaya.msngr.pages.ProfilePage
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme

class ProfileActivity : MsngrActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (redirectToLoginIfNeeded()) {
            return
        }
        setContent {
            MsngrTheme {
                ProfileRoute(
                    onBack = ::finish,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        redirectToLoginIfNeeded()
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, ProfileActivity::class.java)
    }
}

@Composable
private fun ProfileRoute(
    onBack: () -> Unit,
) {
    SecondaryPageScaffold(
        title = "Edit profile",
        onBack = onBack,
    ) { innerPadding, scrollBehavior ->
        ProfilePage(
            innerPadding = innerPadding,
            scrollBehavior = scrollBehavior,
            onProfileSaved = onBack,
        )
    }
}
