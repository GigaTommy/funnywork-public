package com.funnyworker.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import android.content.pm.ApplicationInfo
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var model: AppViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val liveEntry = intent.getStringExtra("com.funnyworker.android.test.OFFICIAL_ENTRY")
            ?.takeIf { applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
            ?.takeIf { it == ServiceIdentity.INTERNATIONAL || it == ServiceIdentity.MAINLAND }
        model = if (liveEntry == null) ViewModelProvider(this)[AppViewModel::class.java]
        else ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(application, AccountApi(automaticRouting = false, fixedOrigin = liveEntry)) as T
        })[AppViewModel::class.java]
        setContent { val state by model.state.collectAsState(); FunnyWorkTheme(appearance = state.appearance) { FunnyWork(state, model) } }
    }
    override fun onStart() { super.onStart(); model.foreground(true) }
    override fun onStop() { model.foreground(false); super.onStop() }
}

@Composable fun FunnyWorkTheme(appearance: String = "system", content: @Composable () -> Unit) {
    val dark = when (appearance) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFEEEAE2), onPrimary = Color(0xFF24221F),
        background = Color(0xFF201F1D), surface = Color(0xFF201F1D), surfaceVariant = Color(0xFF2A2825),
        onSurface = Color(0xFFEEEAE2), onBackground = Color(0xFFEEEAE2), outline = Color(0xFF45413C),
        outlineVariant = Color(0xFF45413C), onSurfaceVariant = Color(0xFFC7C1B7), surfaceTint = Color(0xFFEEEAE2),
        secondary = Color(0xFFC7C1B7), onSecondary = Color(0xFF24221F),
        secondaryContainer = Color(0xFF45413C), onSecondaryContainer = Color(0xFFEEEAE2),
        surfaceContainerLowest = Color(0xFF201F1D), surfaceContainerLow = Color(0xFF24221F),
        surfaceContainer = Color(0xFF2A2825), surfaceContainerHigh = Color(0xFF302E2A), surfaceContainerHighest = Color(0xFF38352F))
    else lightColorScheme(primary = Color(0xFF292724), onPrimary = Color.White,
        background = Color(0xFFF8F6F1), surface = Color(0xFFF8F6F1), surfaceVariant = Color(0xFFFFFDFA),
        onSurface = Color(0xFF292724), onBackground = Color(0xFF292724), outline = Color(0xFFDDD9D2),
        outlineVariant = Color(0xFFDDD9D2), onSurfaceVariant = Color(0xFF5D5954), surfaceTint = Color(0xFF292724),
        secondary = Color(0xFF5D5954), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE8E4DC), onSecondaryContainer = Color(0xFF292724),
        surfaceContainerLowest = Color(0xFFFFFDFA), surfaceContainerLow = Color(0xFFF2EFE9),
        surfaceContainer = Color(0xFFF2EFE9), surfaceContainerHigh = Color(0xFFEDEAE3), surfaceContainerHighest = Color(0xFFE8E4DC))
    MaterialTheme(colorScheme = colors, shapes = Shapes(extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp),
        extraLarge = RoundedCornerShape(28.dp))) {
        CompositionLocalProvider(LocalStatusColors provides statusColors(dark)) {
            Surface(Modifier.fillMaxSize(), color = colors.surface, contentColor = colors.onSurface) { content() }
        }
    }
}

@Composable private fun ErrorLine(state: AppState, retry: () -> Unit) {
    state.error?.let { message -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(stringResource(message), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = retry) { Text(stringResource(R.string.retry)) }
    } }
}

@Composable private fun Login(state: AppState, model: AppViewModel) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var server by rememberSaveable { mutableStateOf(ServiceIdentity.INTERNATIONAL) }
    var serviceText by rememberSaveable { mutableStateOf(server) }
    var invalid by remember { mutableStateOf(false) }
    fun submit() { if (username.isNotBlank() && password.isNotEmpty() && !state.loading) model.login(server, username.trim(), password) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 400.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("FunnyWork", style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.start), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(username, { username = it }, singleLine = true, label = { Text(stringResource(R.string.username)) }, modifier = Modifier.fillMaxWidth().testTag("login-username"), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            OutlinedTextField(password, { password = it }, singleLine = true, label = { Text(stringResource(R.string.password)) },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(stringResource(if (visible) R.string.hide_password else R.string.show_password)) } },
                modifier = Modifier.fillMaxWidth().testTag("login-password"), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
            Button(onClick = { submit() }, enabled = !state.loading && username.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("login-submit")) {
                Text(stringResource(if (state.loading) R.string.loading else R.string.login))
            }
            ErrorLine(state) { submit() }
            TextButton(onClick = { showHelp = true }) { Text(stringResource(R.string.login_help)) }
        }
    }
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false }, title = { Text(stringResource(R.string.custom_service)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.privacy_local))
            OutlinedTextField(serviceText, { serviceText = it; invalid = false }, label = { Text(stringResource(R.string.service)) }, isError = invalid)
            if (invalid) Text(stringResource(R.string.invalid_service))
        }
    }, confirmButton = { TextButton(onClick = { try { server = ServiceIdentity.origin(serviceText); showHelp = false } catch (_: Exception) { invalid = true } }) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FunnyWork(state: AppState, model: AppViewModel) {
    if (!state.ready) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    if (state.credential == null) { Login(state, model); return }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var clearDownloads by rememberSaveable { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var renaming by remember { mutableStateOf<Row?>(null) }
    var renameTitle by rememberSaveable { mutableStateOf("") }
    var usage by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var logout by remember { mutableStateOf(false) }
    var creation by rememberSaveable { mutableStateOf(false) }
    var devices by rememberSaveable { mutableStateOf(false) }
    var models by remember { mutableStateOf(false) }
    var edit by rememberSaveable { mutableStateOf(false) }
    var filesPanel by rememberSaveable { mutableStateOf(false) }
    var reviewPanel by rememberSaveable { mutableStateOf(false) }
    var suggestion by remember { mutableStateOf<String?>(null) }
    val holder = rememberSaveableStateHolder()
    val newLabel = stringResource(R.string.new_chat)
    var searching by rememberSaveable { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    fun cancelSearch() {
        searching = false; focus.clearFocus(); keyboard?.hide()
        if (state.messageSearch) model.taskSearchMode(false)
        model.search("")
    }
    BackHandler(drawer.isOpen) { if (searching) cancelSearch() else scope.launch { drawer.close() } }
    LaunchedEffect(searching, drawer.isOpen) { if (searching && (drawer.isOpen || wide)) searchFocus.requestFocus() }
    LaunchedEffect(state.desktop) { searching = false }
    LaunchedEffect(drawer.isOpen) { if (!drawer.isOpen) { focus.clearFocus(); keyboard?.hide() } }
    LaunchedEffect(state.creationNavigation) { if (state.creationNavigation > 0) creation = false }
    val taskVisible = !drawer.isOpen && !settings && !creation && !devices && !filesPanel && !reviewPanel && !edit && !state.avatarEditor
    LaunchedEffect(taskVisible) { model.taskVisible(taskVisible) }
    DisposableEffect(model) { onDispose { model.taskVisible(false) } }
    fun newConversation() { if (state.desktop) { creation = true; model.prepareCreation() } else model.select(null) }
    val sidebar: @Composable () -> Unit = {
        Column(Modifier.fillMaxHeight().widthIn(max = 340.dp).padding(16.dp)) {
            SidebarHeader(searching, { searching = true }, ::cancelSearch)
            ConversationSources(state) { desktop ->
                if (desktop && state.devicesError) model.retryDevices()
                if (desktop != state.desktop) { focus.clearFocus(); keyboard?.hide(); model.source(desktop) }
            }
            if (searching && state.desktop) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(!state.messageSearch, { model.taskSearchMode(false) }, { Text(stringResource(R.string.search_titles)) })
                FilterChip(state.messageSearch, { model.taskSearchMode(true) }, { Text(stringResource(R.string.search_messages)) })
            }
            if (searching) OutlinedTextField(state.query, { model.search(it) }, label = { Text(stringResource(if (state.desktop && state.messageSearch) R.string.search_messages else R.string.search)) }, modifier = Modifier.fillMaxWidth().focusRequester(searchFocus).testTag("conversation-search"), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); keyboard?.hide() }),
                isError = state.desktop && state.query.trim().length > 160)
            if (searching && state.desktop && state.query.trim().length > 160) Text(stringResource(R.string.search_limit))
            if (!searching) {
            TextButton(onClick = { newConversation(); scope.launch { drawer.close() } }) { Text(stringResource(R.string.new_chat)) }
            TextButton(onClick = { devices = true; model.loadDevices(); scope.launch { drawer.close() } }, modifier = Modifier.testTag("sidebar-devices")) { Text(stringResource(R.string.devices)) }
            FilterChip(state.archived, { model.search(state.query, !state.archived) }, { Text(stringResource(R.string.archived)) })
            }
            LazyColumn(Modifier.weight(1f).testTag("conversation-list")) {
                if (state.desktop && state.messageSearch) {
                    if (state.searchBusy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (state.searchHits.isEmpty() && !state.searchBusy) item { Text(stringResource(if (state.query.isBlank()) R.string.search_hint else R.string.search_empty), Modifier.padding(12.dp)) }
                    items(state.searchHits, key = { it.id }) { hit -> TextButton(onClick = { model.select(hit.conversation, hit.sequence); scope.launch { drawer.close() } }, modifier = Modifier.fillMaxWidth().testTag("search-hit-${hit.id}")) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(hit.conversation.title.ifBlank { stringResource(R.string.untitled) })
                            Text(hit.excerpt, style = MaterialTheme.typography.bodySmall)
                        }
                    } }
                    if (state.searchError) item { Text(stringResource(R.string.search_failed)) }
                    if ((state.searchMore || state.searchError) && state.query.isNotBlank() && state.query.trim().length <= 160) item {
                        TextButton(onClick = model::loadTaskSearch, enabled = !state.searchBusy) { Text(stringResource(if (state.searchError) R.string.retry else R.string.load_more)) }
                    }
                } else {
                    val rows = if (state.desktop) if (state.query.trim().length > 160) emptyList() else TaskDirectory.titles(state.tasks.filter { it.json.optBoolean("archived") == state.archived }, state.query) else state.chats
                    if (rows.isEmpty()) item { Text(stringResource(if (state.query.isNotBlank()) R.string.search_empty else if (state.loading) R.string.loading else R.string.empty), modifier = Modifier.padding(12.dp)) }
                    if (state.desktop) items(rows, key = { it.id }) { row -> TextButton(onClick = { model.select(row); scope.launch { drawer.close() } }, modifier = Modifier.fillMaxWidth().testTag("sidebar-task-${row.id}")) {
                        TaskRowSummary(row, state)
                    } }
                else for (group in if (state.query.isBlank()) MobileHistory.groups else listOf(R.string.history_search)) {
                    val grouped = if (state.query.isBlank()) rows.filter { MobileHistory.group(it, state.phonePhases) == group } else rows
                    if (grouped.isNotEmpty()) {
                        item("group-$group") { Text(stringResource(group), Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge) }
                        items(grouped, key = { it.id }) { row -> PhoneHistoryRow(row, state,
                            open = { model.select(row); scope.launch { drawer.close() } },
                            rename = { renameTitle = row.title; renaming = row }, archive = { model.updateHistory(row, archived = !row.json.optBoolean("archived")) }) }
                    }
                    }
                }
                if (!state.desktop && state.next.isNotBlank()) item { TextButton(onClick = { model.refresh(true) }) { Text(stringResource(R.string.load_more)) } }
            }
            if (!state.desktop && state.historyPending != null) {
                Text(stringResource(R.string.history_pending))
                TextButton(onClick = model::reconcileHistory, enabled = !state.historyBusy, modifier = Modifier.testTag("history-reconcile")) { Text(stringResource(R.string.check)) }
            }
            if (!state.desktop) state.historyError?.let { Text(stringResource(it)); if (state.historyPending == null) TextButton(onClick = model::retryHistoryRead) { Text(stringResource(R.string.retry)) } }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarButton(state) { model.openAvatar(); scope.launch { drawer.close() } }
                TextButton(onClick = { settings = true; model.refreshDownloadUsage(); model.allowance(); scope.launch { drawer.close() } }, modifier = Modifier.weight(1f).testTag("account-settings")) { Row(Modifier.fillMaxWidth()) { Text(state.credential.name, maxLines = 1) } }
            }
        }
    }
    val content: @Composable () -> Unit = {
        Scaffold(topBar = { Column(Modifier.then(if (!wide && drawer.isOpen) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier)) { TopAppBar(title = { Text(if (state.desktop) state.tasks.firstOrNull { it.id == state.task }?.title ?: stringResource(R.string.desktop) else state.chatMetadata?.text("title")?.takeIf { it.isNotBlank() } ?: state.chats.firstOrNull { it.id == state.chat }?.title ?: "FunnyWork", maxLines = 1) },
            navigationIcon = {
                val label = stringResource(R.string.menu)
                if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }, modifier = Modifier.testTag("open-sidebar").semantics { contentDescription = label }) {
                    val color = MaterialTheme.colorScheme.onSurface
                    Canvas(Modifier.size(22.dp)) { drawLine(color, Offset(2f, size.height * .36f), Offset(size.width, size.height * .36f), 3f); drawLine(color, Offset(2f, size.height * .64f), Offset(size.width * .7f, size.height * .64f), 3f) }
                }
            }, actions = {
                TextButton(onClick = { newConversation() }, modifier = Modifier.semantics { contentDescription = newLabel }) { Text("✎") }
            }); if (state.desktop) RemoteTaskIdentity(state) } }, bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding()) {
                ErrorLine(state) { model.refresh() }
                if (state.desktop) RemoteComposer(state, model) { edit = true }
                else {
                    state.searchLocation?.let { TextButton(onClick = { searching = true; scope.launch { drawer.open() } }, modifier = Modifier.testTag("history-return-search")) { Text(stringResource(R.string.history_return_search)) } }
                    if (state.chatMetadata?.optBoolean("archived") == true) {
                        Text(stringResource(R.string.history_archived_help), Modifier.padding(12.dp))
                        TextButton(onClick = { state.chatMetadata.let { model.updateHistory(Row(it.text("id"), it.text("title"), it), archived = false) } }, enabled = state.historyFresh && !state.loading && !state.historyBusy && state.historyPending == null, modifier = Modifier.testTag("history-restore-selected")) { Text(stringResource(R.string.history_restore)) }
                    }
                    if (state.chat != null) TextButton(onClick = { usage = true }, modifier = Modifier.testTag("phone-usage")) { Text(stringResource(R.string.usage_title)) }
                    if (state.phase != null) {
                        SemanticStatus(stringResource(if (state.phase == TurnPhase.NEEDS_CHECK) R.string.needs_check else if (state.phase == TurnPhase.EXECUTING) R.string.running else R.string.waiting), if (state.phase == TurnPhase.EXECUTING) StatusKind.RUNNING else StatusKind.WAITING, Modifier.padding(horizontal = 16.dp))
                        if (state.phase == TurnPhase.NEEDS_CHECK) TextButton(onClick = { model.checkTurn() }) { Text(stringResource(R.string.check)) }
                    }
                    if (state.models.isEmpty()) Text(stringResource(if (state.loading) R.string.loading else R.string.no_model), modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    Surface(Modifier.padding(10.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                        Column(Modifier.padding(8.dp)) {
                            OutlinedTextField(state.draft, { model.draft(it) }, placeholder = { Text(stringResource(R.string.placeholder)) }, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("phone-composer"))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                val modelLabel = stringResource(R.string.model); val editLabel = stringResource(R.string.expand)
                                TextButton(onClick = { models = true }, enabled = state.phase == null, modifier = Modifier.testTag("phone-model").sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics {
                                    contentDescription = modelLabel
                                    stateDescription = state.models.firstOrNull { it.id == state.model }?.title.orEmpty()
                                }) { Text("◈") }
                                TextButton(onClick = { edit = true }, modifier = Modifier.semantics { contentDescription = editLabel }) { Text("⤢") }
                                Spacer(Modifier.weight(1f))
                                if (state.phase == TurnPhase.EXECUTING || state.phase == TurnPhase.WAITING) Button(onClick = { model.stop() }) { Text(stringResource(R.string.stop)) }
                                else Button(onClick = { model.send() }, enabled = state.chatMetadata?.optBoolean("archived") != true && state.phase == null && state.draft.isNotBlank() && state.draft.length <= 16000 && state.model.isNotBlank()) { Text(stringResource(R.string.send)) }
                            }
                            if (state.draft.length > 16000) Text(stringResource(R.string.overlong))
                        }
                    }
                }
            }
        }) { padding ->
            PullToRefreshBox(isRefreshing = state.loading, onRefresh = { model.refresh() }, modifier = Modifier.fillMaxSize().padding(padding)) {
            holder.SaveableStateProvider("${state.desktop}:${if (state.desktop) state.task else state.chat}") {
                if (!state.desktop && state.chat == null) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 42.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.hello, state.credential.name), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall)
                    if (!keyboardVisible) listOf(R.string.explain to R.string.explain_prompt, R.string.polish to R.string.polish_prompt, R.string.idea to R.string.idea_prompt).forEach { (title, prompt) ->
                        val text = stringResource(prompt)
                        OutlinedButton(onClick = { if (state.draft.isBlank()) model.draft(text) else suggestion = text }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(title)) }
                    }
                } else {
                    val list = rememberLazyListState()
                    var scrolledTarget by remember(state.task) { mutableStateOf<Long?>(null) }
                    LaunchedEffect(state.historyTarget, state.desktopMessages) {
                        val target = state.historyTarget
                        if (state.desktop && target != null && target != scrolledTarget) {
                            val index = state.desktopMessages.indexOfFirst { it.id == target.toString() }
                            if (index >= 0) {
                                list.scrollToItem(index + 4 + state.remoteState?.interactions.orEmpty().size + if (state.historyStart > 0) 1 else 0)
                                scrolledTarget = target
                            }
                        }
                    }
                    LaunchedEffect(state.task, state.remoteLatest, taskVisible) {
                        val newest = state.desktopMessages.lastOrNull()?.id
                        if (state.desktop && taskVisible) snapshotFlow {
                            val info = list.layoutInfo
                            if (newest == null) info.totalItemsCount > 0 && info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
                            else info.visibleItemsInfo.firstOrNull { it.key == newest }?.let {
                                it.offset + it.size <= info.viewportEndOffset && it.offset + it.size > info.viewportStartOffset
                            } == true
                        }.collect { read ->
                            if (read) model.markTaskRead(state.remoteLatest, state.task)
                        }
                    }
                    var located by remember(state.searchLocation) { mutableStateOf(false) }
                    LaunchedEffect(state.searchLocation, state.messages) {
                        val target = state.searchLocation ?: return@LaunchedEffect
                        if (!located && !state.desktop && state.chat == target.chat) {
                            val index = state.messages.indexOfFirst { it.id == target.messageId }
                            if (index >= 0) { list.scrollToItem(index); located = true }
                        }
                    }
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("message-timeline"), state = list, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (state.desktop && state.task != null) {
                            items(state.remoteState?.interactions.orEmpty(), key = { "interaction:${it.id}" }) { InteractionCard(it, state, model) }
                            item("remote-files-review") { Row {
                                TextButton(onClick = { filesPanel = true }, modifier = Modifier.testTag("conversation-files")) { Text(stringResource(R.string.result_files)) }
                                TextButton(onClick = { model.freezeReview(); reviewPanel = true }, enabled = state.desktopEvents.isNotEmpty() || state.review != null || state.reviewDrafts.isNotEmpty(), modifier = Modifier.testTag("review-result")) { Text(stringResource(R.string.review)) }
                            } }
                            item("queue") { QueuePanel(state, model) }
                            item("command-receipts") { CommandReceipts(state) }
                            if (state.historyTarget != null) item("search-location") { TextButton(onClick = model::latestTaskHistory) { Text(stringResource(R.string.search_latest)) } }
                        }
                        if (state.desktop && state.historyStart > 0) item("older") { TextButton(onClick = { model.olderEvents() }, enabled = !state.loading) { Text(stringResource(R.string.load_more)) } }
                        if (state.desktop && state.task == null) {
                            item("remote-home") { Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.desktop), style = MaterialTheme.typography.headlineSmall)
                                ConnectionLabel(state.deviceConnection())
                                Text(stringResource(R.string.device_setup_hint), style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { newConversation() }) { Text(stringResource(R.string.new_chat)) }
                                    OutlinedButton(onClick = { devices = true; model.loadDevices() }) { Text(stringResource(R.string.devices)) }
                                }
                            } }
                            items(state.tasks.filter { !it.json.optBoolean("archived") }, key = { it.id }) { row -> OutlinedButton(onClick = { model.select(row) }, modifier = Modifier.fillMaxWidth()) { TaskRowSummary(row, state) } }
                        } else items(if (state.desktop) state.desktopMessages else state.messages, key = { it.id }) { message ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start) {
                                Surface(modifier = Modifier.testTag("message-surface-${message.id}"), shape = MaterialTheme.shapes.large, color = if (message.user) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface) {
                                    SelectionContainer { SearchText(message.text, if (!state.desktop && state.searchLocation?.messageId == message.id) state.searchLocation.query else "", Modifier.padding(12.dp).testTag("message-${message.id}")) }
                                }
                            }
                        }
                        if (!state.desktop && state.live.isNotEmpty()) item("live") { SelectionContainer { Text(state.live, modifier = Modifier.padding(12.dp)) } }
                        item("space") { Spacer(Modifier.height(12.dp)) }
                    }
                }
            }
            }
        }
    }
    BoxWithConstraints {
        if (maxWidth >= 600.dp) PermanentNavigationDrawer(drawerContent = { PermanentDrawerSheet(Modifier.width(300.dp)) { sidebar() } }) { content() }
        else ModalNavigationDrawer(drawerState = drawer, gesturesEnabled = drawer.isOpen, drawerContent = { ModalDrawerSheet { sidebar() } }) { content() }
    }
    if (models) MobileModelDialog(state.models, state.model, state.reasoningEffort, state.phase != null,
        { model.chooseModel(it); models = false }, model::chooseReasoningEffort) { models = false }
    if (settings) AlertDialog(onDismissRequest = { settings = false }, title = { Text(stringResource(R.string.settings)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { AvatarButton(state, model::openAvatar); Text(state.credential.name) }
            Text(stringResource(R.string.app_version, androidx.compose.ui.platform.LocalContext.current.packageManager.getPackageInfo(androidx.compose.ui.platform.LocalContext.current.packageName, 0).versionName.orEmpty()))
            AllowanceSection(state, { model.allowance() }, { model.allowance(claim = true) })
            Text(stringResource(R.string.appearance_title))
            listOf("system" to R.string.appearance_system, "light" to R.string.appearance_light, "dark" to R.string.appearance_dark).forEach { (value, label) ->
                TextButton(onClick = { model.appearance(value) }, modifier = Modifier.fillMaxWidth().testTag("appearance-$value").semantics { selected = state.appearance == value }) {
                    Text((if (state.appearance == value) "✓ " else "") + stringResource(label))
                }
            }
            Text(stringResource(R.string.no_push_help))
            Text(stringResource(R.string.download_storage, state.downloadUsage?.let { android.text.format.Formatter.formatFileSize(androidx.compose.ui.platform.LocalContext.current, it.bytes) } ?: "—"), Modifier.testTag("download-storage-usage"))
            TextButton(onClick = { clearDownloads = true }, enabled = !state.cacheBusy && (state.downloadUsage?.count ?: 0) > 0, modifier = Modifier.testTag("clear-downloads")) { Text(stringResource(R.string.clear_downloads)) }
            ErrorLine(state.copy(error = state.cacheError)) { model.refreshDownloadUsage() }
            TextButton(onClick = { logout = true }, enabled = !state.cacheBusy) { Text(stringResource(R.string.logout)) }
        }
    }, confirmButton = { TextButton(onClick = { settings = false }) { Text(stringResource(R.string.done)) } })
    renaming?.let { row -> AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.history_rename)) },
        text = { OutlinedTextField(renameTitle, { if (it.length <= 160) renameTitle = it }, label = { Text(stringResource(R.string.history_title)) }, modifier = Modifier.testTag("history-rename-title")) },
        confirmButton = { TextButton(onClick = { if (model.updateHistory(row, title = renameTitle)) renaming = null }, enabled = renameTitle.isNotBlank() && !state.historyBusy && state.historyPending == null) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } }) }
    if (usage) AlertDialog(onDismissRequest = { usage = false }, title = { Text(stringResource(R.string.usage_title)) }, text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { PhoneUsageDetails(state.phoneUsage) } }, confirmButton = { TextButton(onClick = { usage = false }) { Text(stringResource(R.string.done)) } })
    if (clearDownloads) AlertDialog(onDismissRequest = { clearDownloads = false }, title = { Text(stringResource(R.string.clear_downloads)) },
        text = { Text(stringResource(R.string.clear_downloads_detail)) },
        confirmButton = { TextButton(onClick = { clearDownloads = false; model.clearDownloads() }) { Text(stringResource(R.string.clear_downloads)) } },
        dismissButton = { TextButton(onClick = { clearDownloads = false }) { Text(stringResource(R.string.cancel)) } })
    if (logout) AlertDialog(onDismissRequest = { logout = false }, title = { Text(stringResource(R.string.logout)) }, text = { Text(stringResource(R.string.logout_detail)) },
        confirmButton = { TextButton(onClick = { logout = false; settings = false; model.logout() }) { Text(stringResource(R.string.logout)) } }, dismissButton = { TextButton(onClick = { logout = false }) { Text(stringResource(R.string.cancel)) } })
    if (suggestion != null) AlertDialog(onDismissRequest = { suggestion = null }, title = { Text(stringResource(R.string.keep_draft)) }, text = { Text(suggestion.orEmpty()) },
        confirmButton = { TextButton(onClick = { model.draft(state.draft + "\n" + suggestion); suggestion = null }) { Text(stringResource(R.string.append)) } },
        dismissButton = { Row { TextButton(onClick = { model.draft(suggestion.orEmpty()); suggestion = null }) { Text(stringResource(R.string.replace)) }; TextButton(onClick = { suggestion = null }) { Text(stringResource(R.string.cancel)) } } })
    if (filesPanel && state.desktop) ResultDirectory(state, model) { filesPanel = false }
    if (reviewPanel && state.desktop) ReviewPanel(state, model) { reviewPanel = false }
    if (edit) Editor(stringResource(R.string.edit_message), state.draft, { model.draft(it) }) { edit = false }
    if (creation) Creation(state, model) { creation = false }
    if (state.avatarEditor) AvatarEditor(state, model)
    if (devices) DeviceDirectory(state, model::retryDevices, { id ->
        model.chooseDevice(id); devices = false; creation = true
    }) { devices = false }
}

@Composable private fun Editor(title: String, text: String, change: (String) -> Unit, close: () -> Unit) {
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, modifier = Modifier.weight(1f)); TextButton(onClick = close) { Text(stringResource(R.string.done)) } }
            OutlinedTextField(text, change, modifier = Modifier.fillMaxSize(), label = { Text(title) })
        } }
    }
}

@Composable private fun Creation(state: AppState, model: AppViewModel, close: () -> Unit) {
    DisposableEffect(model) { model.creationVisible(true); onDispose { model.creationVisible(false) } }
    var editor by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.create_title), modifier = Modifier.weight(1f)); TextButton(onClick = close) { Text(stringResource(R.string.dismiss)) } }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(state.creationText, { model.creation(text = it) }, label = { Text(stringResource(R.string.task_text)) }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = state.pendingCreate == null && state.createdTask == null)
                TextButton(onClick = { editor = true }, enabled = state.pendingCreate == null && state.createdTask == null) { Text(stringResource(R.string.expand)) }
                Text(stringResource(R.string.devices), style = MaterialTheme.typography.titleSmall)
                if (state.devicesLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.devicesError) Text(stringResource(R.string.device_refresh_failed))
                if (state.devices.isEmpty() && state.devicesFresh) Text(stringResource(R.string.device_empty))
                TextButton(onClick = model::loadDevices, enabled = !state.devicesLoading) { Text(stringResource(if (state.devicesError) R.string.retry else R.string.refresh)) }
                state.devices.forEach { row ->
                    val info = DeviceInfo.parse(row.json)
                    FilterChip(state.creationDevice == row.id, { model.chooseDevice(row.id) }, {
                        Column(Modifier.padding(vertical = 8.dp)) { DeviceSummary(info, state.devicesFresh) }
                    }, enabled = state.devicesFresh && info.canCreate && state.pendingCreate == null && state.createdTask == null)
                }
                val device = state.devices.firstOrNull { it.id == state.creationDevice }
                val workspaces = device?.json?.optJSONArray("workspaces")
                Text(stringResource(R.string.workspace), style = MaterialTheme.typography.titleSmall)
                for (index in 0 until (workspaces?.length() ?: 0)) {
                    val row = workspaces!!.getJSONObject(index)
                    FilterChip(state.creationWorkspace == row.getString("id"), { model.creation(workspace = row.getString("id")) }, { Text(row.optString("title")) }, enabled = state.availableDevice(state.creationDevice) != null && state.pendingCreate == null && state.createdTask == null)
                }
                Text(stringResource(R.string.create_hint), style = MaterialTheme.typography.bodySmall)
                if (state.creationText.length > 16000) Text(stringResource(R.string.overlong))
                CreationProgress(state, model::checkCreation, model::retryCreation, model::clearCreation, model::openCreatedTask)
                if (state.pendingCreate != null) {
                    TextButton(onClick = { submitted = true }) { Text(stringResource(R.string.submitted)) }
                }
                ErrorLine(state) { model.loadDevices() }
            }
            val position = state.devices.firstOrNull { it.id == state.creationDevice }
            val workspaceTitle = position?.json?.optJSONArray("workspaces")?.objects()?.firstOrNull { it.text("id") == state.creationWorkspace }?.text("title")
            Text(stringResource(R.string.location) + ": " + listOfNotNull(position?.title, workspaceTitle).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if (state.creationWorkspace.isBlank()) Text(stringResource(R.string.choose_position), style = MaterialTheme.typography.bodySmall)
            Button(onClick = { model.create() }, enabled = state.canCreateConversation(), modifier = Modifier.fillMaxWidth().testTag("creation-submit")) { Text(stringResource(R.string.create)) }
        } }
    }
    if (editor) Editor(stringResource(R.string.task_text), state.creationText, { model.creation(text = it) }) { editor = false }
    if (submitted) AlertDialog(onDismissRequest = { submitted = false }, title = { Text(stringResource(R.string.submitted)) }, text = { SelectionContainer { Text(state.pendingCreate?.optString("text").orEmpty()) } }, confirmButton = { TextButton(onClick = { submitted = false }) { Text(stringResource(R.string.done)) } })
}
