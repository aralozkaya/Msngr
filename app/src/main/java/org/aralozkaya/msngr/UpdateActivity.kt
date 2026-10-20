package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import org.aralozkaya.msngr.pages.UpdateSettingsScreen
import org.aralozkaya.msngr.pages.components.SecondaryPageScaffold
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.aralozkaya.msngr.updates.updateManager

/** Updates remain accessible from a notification even when the user is signed out. */
class UpdateActivity : MsngrActivity() {
    override val observesConnectionLoss = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateManager.dismissNotification()
        setContent {
            MsngrTheme {
                SecondaryPageScaffold(title = getString(R.string.updates_title), onBack = ::finish) { padding, behavior ->
                    UpdateSettingsScreen(padding, behavior)
                }
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, UpdateActivity::class.java)
    }
}
