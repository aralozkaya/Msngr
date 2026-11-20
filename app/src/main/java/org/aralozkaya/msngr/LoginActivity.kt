@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.aralozkaya.msngr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import androidx.activity.compose.setContent
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.aralozkaya.msngr.pages.components.MsngrCircularWavyProgressIndicator
import org.aralozkaya.msngr.data.SignInRequest
import org.aralozkaya.msngr.enums.Presence
import org.aralozkaya.msngr.pages.components.CustomServerDialog
import org.aralozkaya.msngr.pages.components.LoadingButtonContent
import org.aralozkaya.msngr.pages.components.MockServerBanner
import org.aralozkaya.msngr.ui.theme.MsngrTheme
import org.aralozkaya.msngr.ui.theme.contentCard

class LoginActivity : MsngrActivity() {
    override val observesConnectionLoss: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SessionPrefs.canOpenAuthenticatedScreens(this)) {
            openAuthenticatedDestination(clearTask = false)
            return
        }
        val container = appContainer
        setContent {
            MsngrTheme {
                LoginRoute(
                    appContainer = container,
                    allowAutomaticSignIn = intent.getBooleanExtra(
                        EXTRA_ALLOW_AUTOMATIC_SIGN_IN,
                        true,
                    ),
                    onLoginComplete = {
                        openAuthenticatedDestination(clearTask = true)
                    },
                    signOutReason = intent.getStringExtra(EXTRA_SIGN_OUT_REASON),
                )
            }
        }
    }

    private fun openAuthenticatedDestination(clearTask: Boolean) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    if (clearTask) {
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    } else {
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                )
            },
        )
        val shortcutAccount = intent.getStringExtra(EXTRA_SHORTCUT_ACCOUNT)
        val activeAccount = runCatching { appContainer.messengerRepository.profile.value.email }
            .getOrDefault("")
        if (shortcutAccount == null || shortcutAccount.equals(activeAccount, ignoreCase = true)) {
            intent.getStringExtra(EXTRA_OPEN_CONTACT_ID)
                ?.takeIf(String::isNotBlank)
                ?.let { contactId -> startActivity(ChatActivity.createIntent(this, contactId)) }
        }
        if ((intent.getBooleanExtra(EXTRA_OPEN_CONTACT_REQUESTS, false) ||
                intent.getBooleanExtra(EXTRA_OPEN_GROUP_INVITATIONS, false)) &&
            intent.getStringExtra(EXTRA_REQUEST_ACCOUNT) == appContainer.messengerRepository.contactRequestAccountId) {
            startActivity(RequestsActivity.createIntent(this))
        }
        finish()
    }

    companion object {
        internal const val EXTRA_SIGN_OUT_REASON = "sign_out_reason"
        private const val EXTRA_ALLOW_AUTOMATIC_SIGN_IN = "allow_automatic_sign_in"
        private const val EXTRA_OPEN_CONTACT_ID = "open_contact_id"
        private const val EXTRA_OPEN_CONTACT_NAME = "open_contact_name"
        private const val EXTRA_SHORTCUT_ACCOUNT = "shortcut_account"
        private const val EXTRA_OPEN_CONTACT_REQUESTS = "open_contact_requests"
        private const val EXTRA_OPEN_GROUP_INVITATIONS = "open_group_invitations"
        private const val EXTRA_REQUEST_ACCOUNT = "request_account"

        internal fun createContactRequestsNotificationIntent(context: Context, accountId: String): Intent =
            createIntent(context).apply {
                putExtra(EXTRA_OPEN_CONTACT_REQUESTS, true)
                putExtra(EXTRA_REQUEST_ACCOUNT, accountId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

        internal fun createGroupInvitationsNotificationIntent(context: Context, accountId: String): Intent =
            createIntent(context).apply {
                putExtra(EXTRA_OPEN_GROUP_INVITATIONS, true)
                putExtra(EXTRA_REQUEST_ACCOUNT, accountId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }

        fun createIntent(
            context: Context,
            allowAutomaticSignIn: Boolean = true,
        ): Intent = Intent(context, LoginActivity::class.java).putExtra(
            EXTRA_ALLOW_AUTOMATIC_SIGN_IN,
            allowAutomaticSignIn,
        )

        fun createNotificationIntent(
            context: Context,
            contactId: String,
            contactName: String,
        ): Intent = createIntent(context, allowAutomaticSignIn = true).apply {
            putExtra(EXTRA_OPEN_CONTACT_ID, contactId)
            putExtra(EXTRA_OPEN_CONTACT_NAME, contactName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        internal fun createRecentChatShortcutIntent(
            context: Context,
            accountEmail: String,
            contact: RecentChatShortcut,
            action: String,
        ): Intent = createIntent(context, allowAutomaticSignIn = true).apply {
            this.action = action
            putExtra(EXTRA_OPEN_CONTACT_ID, contact.contactId)
            putExtra(EXTRA_OPEN_CONTACT_NAME, contact.label)
            putExtra(EXTRA_SHORTCUT_ACCOUNT, accountEmail)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
    }
}

@Composable
private fun LoginRoute(
    appContainer: AppContainer,
    allowAutomaticSignIn: Boolean,
    onLoginComplete: () -> Unit,
    signOutReason: String? = null,
) {
    val context = LocalContext.current
    val initial by produceState<LoginSnapshot?>(initialValue = null, key1 = context) {
        value = withContext(Dispatchers.IO) { SessionPrefs.readLoginSnapshot(context) }
    }
    val loaded = initial
    if (loaded == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MsngrCircularWavyProgressIndicator()
                Text(stringResource(R.string.login_loading_storage), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    LoginReadyRoute(
        appContainer = appContainer,
        initial = loaded,
        allowAutomaticSignIn = allowAutomaticSignIn,
        onLoginComplete = onLoginComplete,
        signOutReason = signOutReason,
    )
}

@Composable
private fun LoginReadyRoute(
    appContainer: AppContainer,
    initial: LoginSnapshot,
    allowAutomaticSignIn: Boolean,
    onLoginComplete: () -> Unit,
    signOutReason: String? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf(initial.email) }
    val passwordState = rememberTextFieldState(initial.password)
    var rememberCredentials by rememberSaveable { mutableStateOf(initial.savePassword) }
    var selectedProvider by rememberSaveable { mutableStateOf(initial.provider) }
    var customServers by remember { mutableStateOf(initial.customServers) }
    var showCustomDialog by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var automaticSignInEnabled by rememberSaveable { mutableStateOf(initial.automaticSignIn) }
    var loginPresence by rememberSaveable { mutableStateOf(initial.loginPresence) }
    var errorMessage by rememberSaveable { mutableStateOf(signOutReason) }
    var autoLoginChecked by remember { mutableStateOf(false) }
    var useMockServer by rememberSaveable { mutableStateOf(initial.useMockServer) }
    var mockOptionRevealed by rememberSaveable { mutableStateOf(initial.useMockServer || BuildConfig.DEBUG) }
    var rememberedAccounts by remember { mutableStateOf(SessionPrefs.rememberedProfiles(context)) }

    if (!mockOptionRevealed) DetectShake { mockOptionRevealed = true }

    fun formIdentity() = AccountIdentity.forLogin(email, selectedProvider, customServers, useMockServer)

    fun applySavedAccount(snapshot: LoginSnapshot) {
        passwordState.setTextAndPlaceCursorAtEnd(snapshot.password)
        rememberCredentials = snapshot.savePassword
        automaticSignInEnabled = snapshot.automaticSignIn
        selectedProvider = snapshot.provider
        customServers = snapshot.customServers
        loginPresence = snapshot.loginPresence
        useMockServer = snapshot.useMockServer
        if (snapshot.useMockServer) mockOptionRevealed = true
        errorMessage = null
    }

    fun loadFormAccount() {
        val identity = formIdentity()
        val snapshot = SessionPrefs.readLoginSnapshot(context, identity)
        if (snapshot.email.isNotBlank()) {
            applySavedAccount(snapshot)
        } else {
            passwordState.setTextAndPlaceCursorAtEnd("")
            rememberCredentials = false
            automaticSignInEnabled = false
            loginPresence = Presence.Online
            errorMessage = null
        }
        SessionPrefs.selectLoginAccount(context, identity)
    }

    fun selectAccount(value: String) {
        val changed = email.normalizedAccountEmail() != value.normalizedAccountEmail()
        email = value
        if (changed) loadFormAccount()
    }

    fun changeService(provider: String, servers: ServerOverrides, mock: Boolean) {
        val previous = formIdentity()
        selectedProvider = provider
        customServers = servers.normalized()
        useMockServer = mock
        if (previous != formIdentity()) {
            loadFormAccount()
        } else {
            SessionPrefs.saveServerSettings(context, provider, customServers, account = formIdentity())
        }
    }

    fun signIn(isAutomatic: Boolean) {
        if (submitting) return
        val normalizedEmail = email.trim()
        val resolved = ServerPresets.resolveForProvider(selectedProvider, customServers)
        val mockServerSelected = useMockServer
        val requestedPresence = loginPresence
        submitting = true
        scope.launch {
            val repository = appContainer.repositoryFor(mockServerSelected)
            repository.signIn(
                SignInRequest(
                    email = normalizedEmail,
                    password = passwordState.text.toString(),
                    serverConfig = resolved,
                    initialPresence = requestedPresence,
                ),
            ).onSuccess { serverProfile ->
                if (isAutomatic) {
                    SessionPrefs.activateRememberedSession(context)
                } else {
                    SessionPrefs.completeLogin(
                        context = context,
                        email = normalizedEmail,
                        password = passwordState.text.toString(),
                        savePassword = rememberCredentials,
                        provider = selectedProvider,
                        customServers = customServers,
                        useMockServer = mockServerSelected,
                        automaticSignIn = automaticSignInEnabled,
                        loginPresence = requestedPresence,
                    )
                }
                context.restoreSignedInProfile(
                    repository = repository,
                    serverProfile = serverProfile,
                    presence = requestedPresence,
                )
                onLoginComplete()
            }.onFailure { error ->
                if (isAutomatic) {
                    SessionPrefs.invalidateRememberedCredentials(context)
                    passwordState.setTextAndPlaceCursorAtEnd("")
                    automaticSignInEnabled = false
                }
                errorMessage =
                    error.message ?: resources.getString(R.string.login_error_service_default_you_singular)
            }
            submitting = false
        }
    }

    LaunchedEffect(Unit) {
        if (!autoLoginChecked) {
            autoLoginChecked = true
            if (allowAutomaticSignIn && initial.automaticSignIn && SessionPrefs.hasRememberedCredentials(
                    context
                )
            ) {
                signIn(isAutomatic = true)
            }
        }
    }

    LoginScreen(
        email = email,
        rememberedAccounts = rememberedAccounts,
        passwordState = passwordState,
        rememberCredentials = rememberCredentials,
        automaticSignInEnabled = automaticSignInEnabled,
        loginPresence = loginPresence,
        provider = selectedProvider,
        serverConfig = ServerPresets.resolveForProvider(selectedProvider, customServers),
        submitting = submitting,
        useMockServer = useMockServer,
        mockOptionRevealed = mockOptionRevealed,
        onEmailChange = ::selectAccount,
        onSavedAccountSelected = { snapshot ->
            email = snapshot.email
            applySavedAccount(snapshot)
            SessionPrefs.selectLoginAccount(context, snapshot.accountIdentity)
        },
        onRememberChange = { remember ->
            rememberCredentials = remember
            if (!remember) {
                passwordState.setTextAndPlaceCursorAtEnd("")
                automaticSignInEnabled = false
                SessionPrefs.disableRememberedCredentials(context, account = formIdentity())
                rememberedAccounts = SessionPrefs.rememberedProfiles(context)
            }
        },
        onAutomaticSignInChange = { enabled ->
            automaticSignInEnabled = enabled
            SessionPrefs.setAutomaticSignIn(context, enabled, account = formIdentity())
        },
        onLoginPresenceChange = { presence ->
            loginPresence = presence
            SessionPrefs.setLoginPresence(context, presence, account = formIdentity())
        },
        onUseMockServerChange = { changeService(selectedProvider, customServers, it) },
        onProviderSelected = { provider ->
            if (provider == ProviderOptions.CUSTOM) {
                showCustomDialog = true
            } else {
                changeService(provider, customServers, useMockServer)
            }
        },
        onSignIn = { signIn(isAutomatic = false) },
    )

    if (showCustomDialog) {
        CustomServerDialog(
            initial = ServerPresets.initialCustomServers(selectedProvider, customServers),
            onDismiss = { showCustomDialog = false },
            onSave = { saved ->
                changeService(ProviderOptions.CUSTOM, saved, useMockServer)
                showCustomDialog = false
            },
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = {
                Text(
                    stringResource(
                        if (signOutReason != null && message == signOutReason) {
                            R.string.login_dialog_title_logged_out
                        } else {
                            R.string.login_dialog_title_error
                        },
                    ),
                )
            },
            text = { Text(localizeUserMessage(message)) },
            confirmButton = {
                TextButton(
                    onClick = { errorMessage = null },
                ) {
                    Text(stringResource(R.string.common_ok))
                }
            },
        )
    }
}

@Composable
private fun LoginScreen(
    email: String,
    rememberedAccounts: List<LoginSnapshot>,
    passwordState: TextFieldState,
    rememberCredentials: Boolean,
    automaticSignInEnabled: Boolean,
    loginPresence: Presence,
    provider: String,
    serverConfig: ServerConfig,
    submitting: Boolean,
    useMockServer: Boolean,
    mockOptionRevealed: Boolean,
    onEmailChange: (String) -> Unit,
    onSavedAccountSelected: (LoginSnapshot) -> Unit,
    onRememberChange: (Boolean) -> Unit,
    onAutomaticSignInChange: (Boolean) -> Unit,
    onLoginPresenceChange: (Presence) -> Unit,
    onUseMockServerChange: (Boolean) -> Unit,
    onProviderSelected: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    var providerMenuOpen by rememberSaveable { mutableStateOf(false) }
    var accountMenuOpen by rememberSaveable { mutableStateOf(false) }
    var presenceMenuOpen by rememberSaveable { mutableStateOf(false) }
    var passwordShown by rememberSaveable { mutableStateOf(false) }
    val providerMenuInteractionSource = remember { MutableInteractionSource() }
    val accountMenuInteractionSource = remember { MutableInteractionSource() }
    val presenceMenuInteractionSource = remember { MutableInteractionSource() }
    val validEmail = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    val passwordFocusRequester = remember { FocusRequester() }
    val canSignIn = validEmail && passwordState.text.isNotEmpty() && !submitting
    val submitSignIn: () -> Unit = { if (canSignIn) onSignIn() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentWindowInsets = WindowInsets.safeContent,
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                if (useMockServer) {
                    MockServerBanner()
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp, alignment = Alignment.CenterVertically),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.login_welcome_headline),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = numberSentenceResource(
                        R.string.login_welcome_subtitle_before_number,
                        2000,
                        R.string.login_welcome_subtitle_after_number,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                shape = MaterialTheme.shapes.contentCard,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    EmoticonOutlinedTextField(
                        value = email,
                        onValueChange = { onEmailChange(it.withoutUnicodeEmoji()) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.contact_profile_email_label)) },
                        leadingIcon = { Icon(Icons.Default.Mail, contentDescription = null) },
                        trailingIcon = if (rememberedAccounts.isNotEmpty()) {
                            {
                                Box {
                                    IconButton(
                                        onClick = { accountMenuOpen = true },
                                        enabled = !submitting,
                                    ) {
                                        Icon(Icons.Default.ArrowDropDown, stringResource(R.string.login_choose_saved_account))
                                    }
                                    DropdownMenuPopup(
                                        expanded = accountMenuOpen,
                                        onDismissRequest = { accountMenuOpen = false },
                                    ) {
                                        DropdownMenuGroup(
                                            shapes = MenuDefaults.groupShape(0, 1),
                                            interactionSource = accountMenuInteractionSource,
                                        ) {
                                            rememberedAccounts.forEachIndexed { index, account ->
                                                DropdownMenuItem(
                                                    text = { Text(account.displayAccountLabel()) },
                                                    shape = MenuDefaults.itemShape(index, rememberedAccounts.size).shape,
                                                    onClick = {
                                                        accountMenuOpen = false
                                                        onSavedAccountSelected(account)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else null,
                        enabled = !submitting,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(onNext = { passwordFocusRequester.requestFocus() }),
                        singleLine = true,
                        isError = email.isNotBlank() && !validEmail,
                        supportingText = if (email.isNotBlank() && !validEmail) {
                            { Text(stringResource(R.string.login_invalid_email)) }
                        } else null,
                    )
                    OutlinedSecureTextField(
                        state = passwordState,
                        inputTransformation = InputTransformation {
                            val text = asCharSequence().toString()
                            val filtered = text.withoutUnicodeEmoji()
                            if (filtered != text) replace(0, length, filtered)
                        },
                        modifier = Modifier.fillMaxWidth().focusRequester(passwordFocusRequester),
                        label = { Text(stringResource(R.string.login_password_label)) },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        trailingIcon = {
                            IconButton(
                                onClick = { passwordShown = !passwordShown },
                            ) {
                                if (passwordShown) {
                                    Icon(
                                        Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        Modifier.alpha(0.5f),
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Visibility,
                                        contentDescription = null,
                                        Modifier.alpha(0.5f),
                                    )
                                }
                            }
                        },
                        textObfuscationMode = if (passwordShown) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        onKeyboardAction = { submitSignIn() },
                        enabled = !submitting,
                    )
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("remember_credentials"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Switch(
                                checked = rememberCredentials,
                                onCheckedChange = onRememberChange,
                                enabled = !submitting,
                            )
                            Text(stringResource(R.string.login_remember_me))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("automatic_sign_in"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Switch(
                                checked = automaticSignInEnabled,
                                onCheckedChange = onAutomaticSignInChange,
                                enabled = rememberCredentials && !submitting,
                            )
                            Text(stringResource(R.string.login_auto_sign_in))
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.login_sign_in_as),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Box {
                            OutlinedButton(
                                onClick = { presenceMenuOpen = true },
                                shapes = ButtonDefaults.shapes(),
                                enabled = !submitting,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = loginPresence.displayOwnStatusLabel(),
                                    modifier = Modifier.weight(1f),
                                )
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = stringResource(R.string.login_choose_status),
                                )
                            }
                            DropdownMenuPopup(
                                expanded = presenceMenuOpen,
                                onDismissRequest = { presenceMenuOpen = false },
                            ) {
                                DropdownMenuGroup(
                                    shapes = MenuDefaults.groupShape(0, 1),
                                    interactionSource = presenceMenuInteractionSource,
                                ) {
                                    Presence.entries.forEachIndexed { index, presence ->
                                        DropdownMenuItem(
                                            text = { Text(presence.displayOwnStatusLabel()) },
                                            shape = MenuDefaults.itemShape(index, Presence.entries.size).shape,
                                            onClick = {
                                                presenceMenuOpen = false
                                                onLoginPresenceChange(presence)
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (mockOptionRevealed) {
                        Row(
                            modifier = Modifier.fillMaxWidth().testTag("use_mock_server"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Switch(
                                checked = useMockServer,
                                onCheckedChange = onUseMockServerChange,
                                enabled = !submitting,
                            )
                            Text(stringResource(R.string.login_use_mock_server))
                        }
                    }
                    Button(
                        onClick = submitSignIn,
                        shapes = ButtonDefaults.shapes(),
                        enabled = validEmail && passwordState.text.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        LoadingButtonContent(
                            loading = submitting,
                            label = stringResource(R.string.login_signing_in),
                            indicatorModifier = Modifier.testTag("login_progress"),
                        ) {
                            Text(stringResource(R.string.login_sign_in))
                        }
                    }
                }
            }

            Surface(
                shape = MaterialTheme.shapes.contentCard,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.login_service_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Box {
                        OutlinedButton(
                            onClick = { providerMenuOpen = true },
                            enabled = !submitting,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Public, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text(providerDisplayName(provider), modifier = Modifier.weight(1f))
                            Icon(
                                Icons.Default.ArrowDropDown,
                                contentDescription = stringResource(R.string.login_choose_service),
                            )
                        }
                        DropdownMenuPopup(
                            expanded = providerMenuOpen,
                            onDismissRequest = { providerMenuOpen = false },
                        ) {
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShape(0, 1),
                                interactionSource = providerMenuInteractionSource,
                            ) {
                                ServerPresets.providerOptions.forEachIndexed { index, option ->
                                    DropdownMenuItem(
                                        text = { Text(providerDisplayName(option)) },
                                        shape = MenuDefaults.itemShape(index, ServerPresets.providerOptions.size).shape,
                                        onClick = {
                                            providerMenuOpen = false
                                            onProviderSelected(option)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.login_server_summary, serverConfig.mainServer, serverConfig.mainPort.toString(), serverConfig.configServer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
