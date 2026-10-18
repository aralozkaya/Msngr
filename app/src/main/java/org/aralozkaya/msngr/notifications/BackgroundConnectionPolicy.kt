package org.aralozkaya.msngr.notifications

import org.aralozkaya.msngr.LoginSnapshot

internal fun canRestoreBackgroundConnection(
    enabled: Boolean,
    snapshot: LoginSnapshot,
): Boolean = enabled &&
    snapshot.savePassword &&
    snapshot.automaticSignIn &&
    snapshot.email.isNotBlank() &&
    snapshot.password.isNotEmpty()
