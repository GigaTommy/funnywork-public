package com.funnyworker.android

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

data class Row(val id: String, val title: String, val json: JSONObject)
data class ChatMessage(val id: String, val text: String, val user: Boolean)
data class AppState(val appearance: String = "system", val credential: Credential? = null, val ready: Boolean = false, val loading: Boolean = false,
    val desktop: Boolean = false, val chat: String? = null, val task: String? = null,
    val chats: List<Row> = emptyList(), val tasks: List<Row> = emptyList(), val devices: List<Row> = emptyList(),
    val devicesFresh: Boolean = false, val devicesLoading: Boolean = false, val devicesError: Boolean = false,
    val avatar: String = "", val avatarCandidate: String? = null, val avatarEditor: Boolean = false, val avatarBusy: Boolean = false, val avatarError: Boolean = false,
    val tasksFresh: Boolean = false, val tasksReceivedAt: Long = 0, val taskReads: Map<String, Long> = emptyMap(),
    val messageSearch: Boolean = false, val searchHits: List<TaskSearchHit> = emptyList(), val searchCursor: SearchCursor = SearchCursor(),
    val searchMore: Boolean = false, val searchBusy: Boolean = false, val searchError: Boolean = false,
    val remoteLatest: Long = 0, val remoteAtTail: Boolean = false, val historyTarget: Long? = null,
    val creationBusy: Boolean = false, val createdTask: String? = null, val creationNavigation: Long = 0,
    val models: List<Row> = emptyList(), val model: String = "", val reasoningEffort: String = "off", val messages: List<ChatMessage> = emptyList(),
    val desktopMessages: List<ChatMessage> = emptyList(), val draft: String = "", val phase: TurnPhase? = null,
    val live: String = "", val error: Int? = null, val query: String = "", val archived: Boolean = false,
    val historySupported: Boolean = false, val historyFresh: Boolean = false, val historyBusy: Boolean = false,
    val historyPending: JSONObject? = null, val historyError: Int? = null, val phonePhases: Map<String, String> = emptyMap(),
    val searchLocation: SearchLocation? = null, val chatMetadata: JSONObject? = null, val phoneUsage: PhoneUsage = PhoneUsage(),
    val allowance: ModelAllowance? = null, val allowanceBusy: Boolean = false, val allowanceError: Int? = null,
    val next: String = "", val remote: JSONObject? = null, val pendingCreate: JSONObject? = null,
    val creationText: String = "", val creationDevice: String = "", val creationWorkspace: String = "", val historyStart: Long = 0,
    val desktopEvents: List<DesktopEvent> = emptyList(),
    val remoteState: RemoteState? = null, val remoteReady: Boolean = false, val commandBusy: Boolean = false,
    val commandRecords: List<JSONObject> = emptyList(), val answers: JSONObject = JSONObject(),
    val uploads: List<JSONObject> = emptyList(), val transferProgress: Map<String, Long> = emptyMap(),
    val downloadUsage: ClientFiles.Usage? = null, val cacheBusy: Boolean = false, val cacheError: Int? = null,
    val resultFiles: List<JSONObject> = emptyList(), val queueEdits: JSONObject = JSONObject(), val review: JSONObject? = null, val reviewDrafts: List<JSONObject> = emptyList())

class AppViewModel @JvmOverloads constructor(app: Application, private val api: AccountApi = AccountApi(), private val store: SecureStore = SecureStore(app)) : AndroidViewModel(app) {
    private val preferences = app.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AppState(appearance = preferences.getString("mode", "system").orEmpty()))
    val state = mutable.asStateFlow()
    private var account = JSONObject()
    private var generation = 0L
    private var polling: Job? = null
    private var deviceJob: Job? = null
    private var deviceGeneration = 0L
    private var visible = true
    private var visibilityGeneration = 0L
    private var avatarJob: Job? = null
    private var profileGeneration = 0L
    private var searchGeneration = 0L
    private var creationOpen = false
    private var creationCall: Job? = null
    private var creationPolling: Job? = null
    private var creationGeneration = 0L
    private var taskOnscreen = false
    private val workers = mutableMapOf<String, Job>()
    private var loginJob: Job? = null
    private var refreshing: Job? = null
    private var searching: Job? = null
    private val remoteWorkers = mutableMapOf<String, Job>()
    private val transferJobs = mutableMapOf<String, Job>()
    private val files = ClientFiles(app)
    private val activeFileWorkers = java.util.Collections.synchronizedSet(mutableSetOf<Job>())
    private fun launchFileWork(block: suspend CoroutineScope.() -> Unit): Job {
        val job = viewModelScope.launch(block = block)
        activeFileWorkers.add(job)
        job.invokeOnCompletion { activeFileWorkers.remove(job) }
        return job
    }
    private fun id() = UUID.randomUUID().toString()
    private fun current(c: Credential, gen: Long) = state.value.credential == c && generation == gen
    private fun draftKey(desktop: Boolean = state.value.desktop, selected: String? = if (desktop) state.value.task else state.value.chat) = "${if (desktop) "desktop" else "mobile"}:${selected ?: "home"}"
    private fun objectFor(name: String): JSONObject = account.optJSONObject(name) ?: JSONObject().also { account.put(name, it) }
    private fun save() { val c = state.value.credential ?: return; store.write(c.key, account) }
    private fun restoredAccount(c: Credential) = AppState(appearance = state.value.appearance, credential = c,
        chat = account.optString("chat").ifBlank { null }, task = account.optString("task").ifBlank { null },
        desktop = account.optBoolean("desktop"), model = account.optString("model"), historyPending = account.optJSONObject("historyUpdate"),
        creationText = account.optString("creationText"), creationDevice = account.optString("creationDevice"),
        creationWorkspace = account.optString("creationWorkspace"), pendingCreate = account.optJSONObject("pendingCreate"))
    private fun problem(error: Throwable) {
        if (error is CancellationException) return
        if (error is ApiFailure && error.code == "SESSION_EXPIRED") {
            ++generation
            cancelAccountWork()
            ++deviceGeneration; deviceJob?.cancel(); deviceJob = null
            workers.values.forEach { it.cancel() }; workers.clear()
            runCatching { store.remove("session") }
            mutable.value = AppState(appearance = state.value.appearance, ready = true, error = R.string.session_expired)
            return
        }
        mutable.update { it.copy(error = when (error) {
            is ApiFailure -> when {
                error.code == "SESSION_EXPIRED" -> R.string.session_expired
                error.code == "DESKTOP_PERMISSION_REQUIRED" -> R.string.client_access_required
                error.code == "BUSINESS_SCOPE_REQUIRED" -> R.string.business_access_required
                error.code in setOf("USER_MODEL_FUNDS_RESERVED", "GLOBAL_MODEL_FUNDS_RESERVED") -> R.string.funds_reserved
                error.code == "RESULT_NOT_SYNCED" -> R.string.file_not_synced
                error.status == 400 -> R.string.update_service
                error.status == 403 -> R.string.unavailable
                error.status == 409 -> R.string.conflict
                error.status == 429 -> R.string.capacity_error
                else -> R.string.network_error
            }
            is java.io.IOException -> R.string.network_error
            else -> R.string.local_error
        }) }
    }
    init {
        viewModelScope.launch {
            try {
                store.read("session")?.let { data ->
                    val c = Credential.from(data)
                    account = store.read(c.key) ?: JSONObject()
                    mutable.value = restoredAccount(c)
                    restoreDraft(); restoreAccountFields()
                }
            } catch (error: Exception) { problem(error) }
            mutable.update { it.copy(ready = true) }
            refresh()
        }
    }
    fun login(server: String, username: String, password: String) {
        val attempt = ++generation
        cancelAccountWork()
        ++deviceGeneration; deviceJob?.cancel(); deviceJob = null
        refreshing?.cancel(); refreshing = null
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            mutable.update { it.copy(loading = true, error = null) }
            var uncommitted: Credential? = null
            var refreshingWorkspace = false
            try {
                val c = api.login(server, username, password)
                uncommitted = c
                if (attempt != generation) return@launch
                val saved = store.read(c.key) ?: JSONObject()
                store.write("session", c.json())
                // The old generation cannot clear this login's loading state.
                // Release its refresh slot before starting the new account read.
                refreshing?.cancel(); refreshing = null
                account = saved
                mutable.value = restoredAccount(c).copy(ready = true, loading = true)
                uncommitted = null
                restoreDraft(); restoreAccountFields(); refresh()
                refreshingWorkspace = refreshing?.isActive == true
            } catch (error: Exception) {
                if (attempt == generation) {
                    if (error is ApiFailure && error.status == 401) mutable.update { it.copy(error = R.string.login_failed) }
                    else problem(error)
                }
            } finally {
                // A cancelled/stale attempt or local save failure must not leave its new login active.
                uncommitted?.let { c -> withContext(NonCancellable) { runCatching { api.call(c, "logout", JSONObject()) } } }
                if (attempt == generation && !refreshingWorkspace) mutable.update { it.copy(loading = false) }
            }
        }
    }
    fun logout() = viewModelScope.launch {
        val c = state.value.credential ?: return@launch
        val gen = generation
        try {
            api.call(c, "logout", JSONObject())
            if (!current(c, gen)) return@launch
            ++generation; workers.values.forEach { it.cancel() }; workers.clear()
            cancelAccountWork()
            ++deviceGeneration; deviceJob?.cancel(); deviceJob = null
            transferJobs.values.forEach { it.cancel() }; transferJobs.clear()
            store.remove("session"); store.remove(c.key); files.clear(c.key); account = JSONObject()
            mutable.value = AppState(appearance = state.value.appearance, ready = true)
        } catch (error: Exception) { if (current(c, gen)) problem(error) }
    }
    fun appearance(value: String) {
        if (value !in setOf("system", "light", "dark")) return
        try {
            check(preferences.edit().putString("mode", value).commit())
            mutable.update { it.copy(appearance = value, error = null) }
        } catch (error: Exception) { problem(error) }
    }
    private fun protectedUploads(): Set<String> = objectFor("uploads").let { uploads ->
        uploads.keys().asSequence().flatMap { uploads.optJSONArray(it)?.objects().orEmpty().asSequence() }
            .map { it.text("hash") }.toSet()
    }
    private var storageRead = 0L
    fun refreshDownloadUsage() {
        if (state.value.cacheBusy) return
        val read = ++storageRead
        val c = state.value.credential ?: return; val gen = generation
        val protected = protectedUploads()
        viewModelScope.launch {
            try {
                val usage = withContext(Dispatchers.IO) { files.downloadedUsage(c.key, protected) }
                if (current(c, gen) && read == storageRead) mutable.update { it.copy(downloadUsage = usage, cacheError = null) }
            } catch (error: Exception) { if (current(c, gen) && read == storageRead && error !is CancellationException) mutable.update { it.copy(downloadUsage = null, cacheError = R.string.local_error) } }
        }
    }
    fun clearDownloads() {
        val c = state.value.credential ?: return; val gen = generation
        if (state.value.cacheBusy) return
        // Do not interrupt uploads, imports, downloads or receipt reconciliation.
        if (transferJobs.values.any { !it.isCompleted } || synchronized(activeFileWorkers) { activeFileWorkers.any { !it.isCompleted } }) {
            mutable.update { it.copy(cacheError = R.string.downloads_transfer_busy) }; return
        }
        val protected = protectedUploads()
        ++storageRead // A delayed usage read must not overwrite the post-cleanup total.
        mutable.update { it.copy(cacheBusy = true, cacheError = null) }
        viewModelScope.launch {
            try {
                val usage = withContext(Dispatchers.IO) {
                    files.clearDownloads(c.key, protected); files.downloadedUsage(c.key, protected)
                }
                if (current(c, gen)) mutable.update { it.copy(downloadUsage = usage, cacheError = null) }
            } catch (error: Exception) { if (current(c, gen) && error !is CancellationException) mutable.update { it.copy(cacheError = R.string.local_error) } }
            finally { if (current(c, gen)) mutable.update { it.copy(cacheBusy = false) } }
        }
    }
    private fun phonePhases(): Map<String, String> = objectFor("pending").let { rows -> rows.keys().asSequence().associateWith { rows.optJSONObject(it)?.text("phase").orEmpty() } }
    private fun restoreDraft() {
        val s = state.value
        val pending = if (s.chat != null) objectFor("pending").optJSONObject(s.chat) else null
        mutable.update { it.copy(phonePhases = phonePhases(), draft = objectFor("drafts").optString(draftKey()),
            phase = pending?.let { row -> runCatching { TurnPhase.valueOf(row.getString("phase")) }.getOrDefault(TurnPhase.NEEDS_CHECK) }, live = "",
            commandRecords = s.task?.let { remoteRecords(it).objects() }.orEmpty(), answers = s.task?.let { remoteAnswers(it) } ?: JSONObject(),
            uploads = s.task?.let { objectFor("uploads").optJSONArray(it)?.objects() }.orEmpty(),
            resultFiles = s.task?.let { objectFor("results").optJSONArray(it)?.objects() }.orEmpty(),
            queueEdits = s.task?.let { objectFor("queueEdits").optJSONObject(it) } ?: JSONObject(),
            review = s.task?.let { objectFor("reviews").optJSONObject(it) },
            reviewDrafts = s.task?.let { ReviewDrafts.rounds(account, it) }.orEmpty()) }
    }
    fun draft(text: String) {
        if (text.toByteArray().size > 65536) return
        try { objectFor("drafts").put(draftKey(), text); save(); mutable.update { it.copy(draft = text, error = null) } }
        catch (error: Exception) { problem(error) }
    }
    fun creation(text: String = state.value.creationText, device: String = state.value.creationDevice, workspace: String = state.value.creationWorkspace) {
        if (state.value.pendingCreate != null || state.value.createdTask != null) return
        try {
            account.put("creationText", text).put("creationDevice", device).put("creationWorkspace", workspace); save()
            mutable.update { it.copy(creationText = text, creationDevice = device, creationWorkspace = workspace) }
        } catch (error: Exception) { problem(error) }
    }
    fun source(desktop: Boolean) {
        ++searchGeneration; searching?.cancel()
        refreshing?.cancel(); refreshing = null
        try { account.put("desktop", desktop); save(); mutable.update { it.copy(desktop = desktop, query = "", archived = false, error = null, remoteReady = false, tasksFresh = false, historyFresh = false, searchLocation = null, messageSearch = false, searchHits = emptyList(), searchMore = false, searchBusy = false, searchError = false) }; restoreDraft(); refresh() }
        catch (error: Exception) { problem(error) }
    }
    fun select(row: Row?, target: Long? = null) {
        refreshing?.cancel(); refreshing = null
        try {
            val desktop = state.value.desktop
            account.put(if (desktop) "task" else "chat", row?.id ?: ""); save()
            mutable.update { if (desktop) it.copy(task = row?.id, desktopMessages = emptyList(), desktopEvents = emptyList(), remote = null, remoteState = null, remoteReady = false, commandBusy = false, historyStart = 0, remoteLatest = 0, remoteAtTail = false, historyTarget = target) else it.copy(chat = row?.id, messages = emptyList(), phoneUsage = PhoneUsage(), chatMetadata = row?.json,
                searchLocation = row?.takeIf { state.value.query.isNotBlank() }?.let { selected -> selected.json.optJSONObject("search_match").let { match -> SearchLocation(selected.id, match?.text("turnId").orEmpty(), match?.text("field") ?: "title", state.value.query.trim()) } }) }
            restoreDraft(); refresh()
        } catch (error: Exception) { problem(error) }
    }
    fun chooseModel(id: String) {
        if (state.value.phase != null) return
        val row = state.value.models.firstOrNull { it.id == id } ?: return
        val effort = objectFor("reasoningEfforts").optString(id, row.json.optString("defaultReasoningEffort", "off"))
            .takeIf { it in mobileThinkingLevels(row) } ?: row.json.optString("defaultReasoningEffort", "off")
        try { account.put("model", id); save(); mutable.update { it.copy(model = id, reasoningEffort = effort) } } catch (error: Exception) { problem(error) }
    }
    fun chooseReasoningEffort(value: String) {
        val s = state.value
        if (s.phase != null || value !in mobileThinkingLevels(s.models.firstOrNull { it.id == s.model })) return
        try { objectFor("reasoningEfforts").put(s.model, value); save(); mutable.update { it.copy(reasoningEffort = value) } }
        catch (error: Exception) { problem(error) }
    }
    fun search(query: String, archived: Boolean = state.value.archived) {
        if (query.length > 200) return
        ++searchGeneration
        mutable.update { it.copy(query = query, archived = archived, searchHits = emptyList(), searchCursor = SearchCursor(), searchMore = query.isNotBlank(), searchBusy = false, searchError = false, historyFresh = false, chats = if (it.desktop) it.chats else emptyList(), next = if (it.desktop) it.next else "") }
        searching?.cancel()
        if (state.value.desktop) {
            if (state.value.messageSearch && query.isNotBlank()) searching = viewModelScope.launch { delay(300); loadTaskSearch() }
        } else searching = viewModelScope.launch { delay(300); refreshing?.cancel(); refreshing = null; refresh() }
    }
    fun updateHistory(row: Row, title: String? = null, archived: Boolean? = null): Boolean {
        val c = state.value.credential ?: return false
        val s = state.value
        if (s.desktop || !s.historySupported || !s.historyFresh || s.loading || s.historyBusy || s.historyPending != null) return false
        val currentRow = s.chats.firstOrNull { it.id == row.id } ?: s.chatMetadata?.takeIf { s.chat == row.id && it.text("id") == row.id }?.let { Row(row.id, it.text("title"), it) } ?: return false
        return try {
            val request = MobileHistory.request(currentRow, title, archived, id())
            val next = JSONObject(account.toString()).put("historyUpdate", request)
            store.write(c.key, next); account = next
            mutable.update { it.copy(historyPending = request, historyError = null) }
            reconcileHistory(); true
        } catch (error: Exception) { mutable.update { it.copy(historyError = R.string.local_error) }; false }
    }
    fun reconcileHistory() {
        val c = state.value.credential ?: return; val request = state.value.historyPending ?: return; val gen = generation
        if (state.value.historyBusy) return
        mutable.update { it.copy(historyBusy = true, historyError = null) }
        viewModelScope.launch {
            try {
                val chat = request.getString("chatID")
                val receipt = MobileHistory.metadata(JSONObject(api.call(c, "chats/$chat/history", MobileHistory.body(request))))
                require(receipt.text("id") == chat && receipt.sequence("history_revision") == request.sequence("expectedHistoryRevision")!! + 1)
                if (request.has("archived")) require(receipt.getBoolean("archived") == request.getBoolean("archived"))
                if (!current(c, gen)) return@launch
                commitHistoryReceipt(c, receipt)
                // An idempotent receipt may predate a change from the other client.
                val latest = MobileHistory.metadata(JSONObject(api.call(c, "chats/$chat/history")))
                require(latest.text("id") == chat)
                if (!current(c, gen)) return@launch
                commitHistoryReceipt(c, latest)
                refreshing?.cancel(); refreshing = null; refresh()
            } catch (error: Exception) {
                if (!current(c, gen) || error is CancellationException) return@launch
                if (error is ApiFailure && error.status in setOf(400, 403, 404, 409)) {
                    try {
                        val next = JSONObject(account.toString()); next.remove("historyUpdate"); store.write(c.key, next); account = next
                        mutable.update { it.copy(historyPending = null, historyFresh = false) }
                        refreshing?.cancel(); refreshing = null; refresh()
                    } catch (_: Exception) { mutable.update { it.copy(historyError = R.string.local_error) }; return@launch }
                }
                mutable.update { it.copy(historyError = if (error is ApiFailure && error.status == 409) R.string.conflict else R.string.history_check_failed) }
                if (error is ApiFailure && error.code == "SESSION_EXPIRED") problem(error)
            } finally { if (current(c, gen)) mutable.update { it.copy(historyBusy = false) } }
        }
    }
    private fun commitHistoryReceipt(c: Credential, receipt: JSONObject) {
        val chat = receipt.text("id"); val previous = objectFor("historyMetadata").optJSONObject(chat)
        val latest = if ((previous?.sequence("history_revision") ?: -1) > receipt.sequence("history_revision")!!) previous!! else receipt
        val next = JSONObject(account.toString()); next.remove("historyUpdate")
        val metadata = next.optJSONObject("historyMetadata") ?: JSONObject().also { next.put("historyMetadata", it) }
        metadata.put(chat, latest); store.write(c.key, next); account = next
        mutable.update { it.copy(historyPending = null, chatMetadata = if (it.chat == chat) latest else it.chatMetadata,
            chats = it.chats.map { row -> if (row.id == chat) Row(chat, latest.text("title"), latest) else row }.filter { row -> row.json.optBoolean("archived") == it.archived }) }
    }
    fun retryHistoryRead() {
        mutable.update { it.copy(historyError = null) }; refreshing?.cancel(); refreshing = null; refresh()
    }
    fun allowance(claim: Boolean = false) {
        val c = state.value.credential ?: return; val gen = generation
        if (state.value.allowanceBusy) return
        mutable.update { it.copy(allowanceBusy = true, allowanceError = null) }
        viewModelScope.launch {
            try {
                val value = ModelAllowance.parse(JSONObject(api.call(c, if (claim) "inference/budget/claim" else "inference/budget", if (claim) JSONObject() else null)), claim)
                if (current(c, gen)) mutable.update { it.copy(allowance = value) }
            } catch (error: Exception) { if (current(c, gen) && error !is CancellationException) {
                mutable.update { it.copy(allowance = null, allowanceError = R.string.allowance_failed) }
                if (error is ApiFailure && error.code == "SESSION_EXPIRED") problem(error)
            } } finally { if (current(c, gen)) mutable.update { it.copy(allowanceBusy = false) } }
        }
    }
    private fun rows(array: JSONArray?, name: String = "title"): List<Row> = (0 until (array?.length() ?: 0)).mapNotNull { index ->
        val row = array!!.optJSONObject(index) ?: return@mapNotNull null
        val id = row.optString("id"); if (!ServiceIdentity.validId(id)) return@mapNotNull null
        Row(id, row.optString(name).ifBlank { getApplication<Application>().getString(R.string.untitled) }, row)
    }
    fun foreground(active: Boolean) {
        ++visibilityGeneration
        visible = active
        polling?.cancel()
        if (!active) {
            ++searchGeneration; searching?.cancel()
            stopCreationTransport()
            ++deviceGeneration; deviceJob?.cancel(); deviceJob = null
            refreshing?.cancel(); refreshing = null
            workers.values.forEach { it.cancel() }; workers.clear()
            remoteWorkers.values.forEach { it.cancel() }; remoteWorkers.clear()
            transferJobs.values.forEach { it.cancel() }; transferJobs.clear()
            mutable.update { it.copy(remoteReady = false, devicesFresh = false, devicesLoading = false, tasksFresh = false, searchBusy = false) }
            return
        }
        resumeCreationPolling()
        if (state.value.messageSearch && state.value.searchMore && state.value.searchHits.isEmpty()) loadTaskSearch()
        polling = viewModelScope.launch { while (isActive) { refresh(); delay(15_000) } }
    }
    fun refresh(more: Boolean = false) {
        val c = state.value.credential ?: return
        loadDevices()
        if (refreshing?.isActive == true) return
        val gen = generation; val before = state.value
        val profile = profileGeneration
        refreshing = viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            var directoryLoaded = false
            try {
                try {
                    val session = JSONObject(api.call(c, "session"))
                    if (current(c, gen) && profile == profileGeneration && !state.value.avatarBusy) mutable.update { it.copy(avatar = session.text("avatarUrl")) }
                }
                catch (error: ApiFailure) { if (error.status == 401) throw ApiFailure(401, "SESSION_EXPIRED") else throw error }
                if (!current(c, gen)) return@launch
                if (before.desktop) {
                    val tasks = mutableListOf<Row>(); var cursor = ""
                    while (true) {
                        val page = TaskDirectory.page(JSONArray(api.call(c, "sync/sessions?limit=100&after=$cursor")), cursor)
                        ensureActive(); if (!current(c, gen) || !state.value.desktop) return@launch
                        val received = SystemClock.elapsedRealtime(); page.forEach { it.json.put("_receivedAt", received) }
                        tasks += page; if (page.size < 100) break; cursor = page.last().id
                    }
                    mutable.update { it.copy(tasks = tasks.sortedByDescending { row -> row.json.sequence("updated_at") ?: 0 }, tasksFresh = true, tasksReceivedAt = SystemClock.elapsedRealtime()) }
                    directoryLoaded = true
                } else {
                    val directory = api.call(c, "chats?history=true&archived=${before.archived}&query=${URLEncoder.encode(before.query, "UTF-8")}&after=${if (more) URLEncoder.encode(before.next, "UTF-8") else ""}")
                    if (!current(c, gen)) return@launch
                    val data = JSONObject(directory)
                    if (state.value.query == before.query && state.value.archived == before.archived)
                        mutable.update { it.copy(chats = (if (more) (it.chats + rows(data.optJSONArray("items"))).distinctBy { row -> row.id } else rows(data.optJSONArray("items"))).map { row ->
                            val saved = objectFor("historyMetadata").optJSONObject(row.id)
                            if (saved != null && (saved.sequence("history_revision") ?: -1) > (row.json.sequence("history_revision") ?: -1)) Row(row.id, saved.text("title"), saved) else row
                        }.filter { row -> row.json.optBoolean("archived") == before.archived }, next = data.optString("next"), historySupported = data.optBoolean("historyManagement"), historyFresh = true, phonePhases = phonePhases()) }
                    val models = JSONObject(api.call(c, "chats/models"))
                    if (!current(c, gen)) return@launch
                    val available = rows(models.optJSONArray("items"), "name").filter { it.json.optBoolean("available") }
                    val selected = state.value.model.ifBlank { available.firstOrNull()?.id.orEmpty() }
                    val row = available.firstOrNull { it.id == selected }
                    val effort = objectFor("reasoningEfforts").optString(selected, row?.json?.optString("defaultReasoningEffort", "off") ?: "off")
                    mutable.update { it.copy(models = available, model = selected, reasoningEffort = effort.takeIf { value -> value in mobileThinkingLevels(row) } ?: "off") }
                }
                if (before.desktop && before.task != null) {
                    refreshRemote(c, before.task, gen)
                    val remote = JSONObject(api.call(c, "sync/sessions/${before.task}/state"))
                    val head = JSONObject(api.call(c, "sync/sessions/${before.task}/events?offset=0&limit=1"))
                    val start = before.historyTarget?.let { maxOf(0L, it - 20) } ?: maxOf(0L, head.getLong("durableNextSeq") - 100)
                    val end = if (before.historyTarget == null) head.getLong("durableNextSeq") else minOf(head.getLong("durableNextSeq"), start + 100)
                    val page = RemoteHistory.range(start, end) { offset, limit ->
                        ensureActive()
                        if (!current(c, gen) || state.value.task != before.task) throw CancellationException()
                        JSONObject(api.call(c, "sync/sessions/${before.task}/events?offset=$offset&limit=$limit"))
                    }
                    if (current(c, gen) && state.value.task == before.task) mutable.update {
                        it.copy(remote = remote, remoteLatest = page.next, remoteAtTail = page.next == page.durable, historyStart = if (it.desktopMessages.isEmpty()) start else minOf(start, it.historyStart),
                            desktopEvents = RemoteHistory.merge(it.desktopEvents, page.events),
                            desktopMessages = visibleEvents(RemoteHistory.merge(it.desktopEvents, page.events)))
                    }
                } else if (before.chat != null) snapshot(c, before.chat, gen)
                if (current(c, gen)) mutable.update { it.copy(error = null) }
            } catch (error: Exception) { if (current(c, gen)) { mutable.update { it.copy(remoteReady = false, historyFresh = false, tasksFresh = it.tasksFresh && (!before.desktop || directoryLoaded)) }; problem(error) } }
            finally { if (current(c, gen)) mutable.update { it.copy(loading = false) } }
        }
    }
    private fun visibleEvents(events: List<DesktopEvent>): List<ChatMessage> = RemoteHistory.messages(events)
        .map { ChatMessage(it.seq.toString(), it.text, it.user) }
    private fun remoteRecords(task: String): JSONArray = objectFor("remoteCommands").optJSONArray(task) ?: JSONArray()
    private fun remoteAnswers(task: String): JSONObject = objectFor("remoteAnswers").optJSONObject(task) ?: JSONObject()
    private fun commitRemote(task: String, records: JSONArray, answers: JSONObject? = null, clearDraft: String? = null, clearUploads: Set<String> = emptySet(), replaceDraft: String? = null) {
        val c = state.value.credential ?: return
        val next = JSONObject(account.toString())
        val commands = next.optJSONObject("remoteCommands") ?: JSONObject().also { next.put("remoteCommands", it) }
        commands.put(task, records)
        if (answers != null) {
            val values = next.optJSONObject("remoteAnswers") ?: JSONObject().also { next.put("remoteAnswers", it) }
            values.put(task, answers)
        }
        if (replaceDraft != null) {
            val drafts = next.optJSONObject("drafts") ?: JSONObject().also { next.put("drafts", it) }
            drafts.put("desktop:$task", replaceDraft)
        }
        if (clearDraft != null) next.optJSONObject("drafts")?.let { drafts ->
            if (drafts.text("desktop:$task") == clearDraft) drafts.put("desktop:$task", "")
        }
        next.optJSONObject("uploads")?.let { uploads -> uploads.optJSONArray(task)?.let { rows -> uploads.put(task, JSONArray(rows.objects().filter { it.text("hash") !in clearUploads })) } }
        store.write(c.key, next) // Commit the entire outbox and associated draft atomically, before updating UI.
        account = next
        if (state.value.task == task) {
            mutable.update { it.copy(commandRecords = records.objects(), answers = remoteAnswers(task)) }
            if (state.value.desktop) restoreDraft()
        }
    }
    fun answerDraft(interaction: RemoteInteraction, question: RemoteQuestion, selected: List<String>, custom: String) {
        val task = state.value.task ?: return
        if (custom.length > 8000 || state.value.commandRecords.any { RemoteCommands.blocksInteraction(it, interaction.id) }) return
        try {
            val all = JSONObject(remoteAnswers(task).toString())
            val value = all.optJSONObject(interaction.id) ?: JSONObject().also { all.put(interaction.id, it) }
            value.put(question.id, JSONObject().put("selected", JSONArray(selected)).put("custom", custom))
            commitRemote(task, remoteRecords(task), all)
        } catch (error: Exception) { problem(error) }
    }
    private fun applyReceipt(task: String, receipt: JSONObject) {
        val records = remoteRecords(task).objects()
        val old = records.firstOrNull { it.text("id") == receipt.text("id") } ?: return
        val (next, draft) = RemoteQueue.reconcile(old, receipt, objectFor("drafts").text("desktop:$task"))
        commitRemote(task, JSONArray(records.map { if (it.text("id") == old.text("id")) next else it }),
            clearDraft = if (old.text("kind") == "prompt" && next.text("status") in RemoteCommands.received) old.getJSONObject("payload").text("text") else null,
            replaceDraft = if (next.optBoolean("withdrawalApplied") && !old.optBoolean("withdrawalApplied")) draft else null,
            clearUploads = if (old.text("kind") == "prompt" && next.text("status") in RemoteCommands.received) old.getJSONObject("payload").optJSONArray("attachments")?.objects().orEmpty().map { it.text("hash") }.toSet() else emptySet())
    }
    private suspend fun refreshRemote(c: Credential, task: String, gen: Long): RemoteState {
        val visibility = visibilityGeneration
        val remote = RemoteState.parse(JSONObject(api.call(c, "sync/sessions/$task/state")))
        val receipts = JSONArray(api.call(c, "sync/sessions/$task/commands")).objects()
        if (!current(c, gen) || !visible || visibility != visibilityGeneration) throw CancellationException()
        receipts.forEach { applyReceipt(task, it) }
        // The list is bounded; older unresolved IDs must be queried individually, never inferred missing.
        for (record in remoteRecords(task).objects().filter { it.text("status") !in RemoteCommands.terminal && receipts.none { row -> row.text("id") == it.text("id") } }) {
            try {
                val receipt = JSONObject(api.call(c, "sync/sessions/$task/commands/${record.getString("id")}"))
                if (!current(c, gen)) throw CancellationException()
                applyReceipt(task, receipt)
            } catch (error: ApiFailure) { if (error.status != 404) throw error }
        }
        if (!visible || visibility != visibilityGeneration) throw CancellationException()
        if (state.value.desktop && state.value.task == task) mutable.update { it.copy(remoteState = remote, remoteReady = true) }
        return remote
    }
    fun checkRemote() {
        val c = state.value.credential ?: return; val task = state.value.task ?: return; val gen = generation
        if (remoteWorkers[task]?.isActive == true) return
        remoteWorkers[task] = viewModelScope.launch {
            mutable.update { it.copy(commandBusy = true, remoteReady = false, error = null) }
            try { refreshRemote(c, task, gen) }
            catch (error: Exception) { if (current(c, gen)) problem(error) }
            finally { if (current(c, gen) && state.value.task == task) mutable.update { it.copy(commandBusy = false) } }
        }
    }
    private fun submitRemote(interaction: RemoteInteraction? = null, metadata: JSONObject = JSONObject(), build: (RemoteState) -> Pair<String, JSONObject>) {
        val c = state.value.credential ?: return; val task = state.value.task ?: return; val gen = generation
        if (!state.value.desktop || remoteWorkers[task]?.isActive == true) return
        remoteWorkers[task] = viewModelScope.launch {
            mutable.update { it.copy(commandBusy = true, remoteReady = false, error = null) }
            var submitted: JSONObject? = null
            try {
                val remote = refreshRemote(c, task, gen)
                if (!current(c, gen) || state.value.task != task || !state.value.desktop) return@launch
                val previous = remoteRecords(task).objects()
                if (!remote.online) throw ApiFailure(409, "SYNC_OFFLINE")
                if (previous.any { it.optBoolean("pending") }) throw ApiFailure(409, "REMOTE_PENDING")
                if (interaction != null) {
                    check(remote.epoch == interaction.epoch && remote.interactions.any { it.id == interaction.id })
                    check(previous.none { RemoteCommands.blocksInteraction(it, interaction.id) })
                }
                val (kind, payload) = build(remote)
                val record = RemoteCommands.prepare(remote, kind, payload, interaction?.id)
                metadata.keys().forEach { record.put(it, metadata.get(it)) }
                val kept = previous.filter { it.text("status") !in RemoteCommands.terminal } + previous.filter { it.text("status") in RemoteCommands.terminal }.takeLast(20)
                check(kept.size < 100)
                commitRemote(task, JSONArray(kept + record))
                submitted = record
                val receipt = JSONObject(api.call(c, "sync/sessions/$task/commands", RemoteCommands.body(record)))
                if (current(c, gen)) applyReceipt(task, receipt)
            } catch (error: Exception) {
                if (current(c, gen)) {
                    if (error is ApiFailure) submitted?.let { record ->
                        RemoteCommands.admissionRejected(record, error)?.let { rejected ->
                            try { commitRemote(task, JSONArray(remoteRecords(task).objects().map { if (it.text("id") == rejected.text("id")) rejected else it })) }
                            catch (storageError: Exception) { problem(storageError); return@launch }
                        }
                    }
                    mutable.update { it.copy(remoteReady = false, historyFresh = false) }; problem(error)
                }
            } finally { if (current(c, gen) && state.value.task == task) mutable.update { it.copy(commandBusy = false) }; refresh() }
        }
    }
    fun remotePrompt(steer: Boolean = false) {
        val text = state.value.draft
        val expected = state.value.remoteState ?: return
        val task = state.value.task ?: return
        val uploads = objectFor("uploads").optJSONArray(task)?.objects().orEmpty()
        if (RemoteQueue.withdrawing(state.value.commandRecords)) return
        submitRemote { remote ->
            if (remote.epoch != expected.epoch || steer && remote.turn != expected.turn) throw ApiFailure(409, "SYNC_RUNTIME_CHANGED")
            check(uploads.all { it.optBoolean("complete") })
            val payload = JSONArray(uploads.map { SyncedFile.from(it).json() })
            RemoteCommands.prompt(text, steer, remote, payload)
        }
    }
    fun remoteStop() {
        val expected = state.value.remoteState ?: return
        submitRemote { remote -> check(remote.epoch == expected.epoch && remote.turn == expected.turn); RemoteCommands.cancel(remote) }
    }
    fun remoteDecide(item: RemoteInteraction, allow: Boolean) = submitRemote(item) { RemoteCommands.decision(item, allow) }
    fun remoteAnswer(item: RemoteInteraction) {
        val values = state.value.answers.optJSONObject(item.id) ?: JSONObject()
        submitRemote(item) { remote -> RemoteCommands.answer(remote.interactions.first { it.id == item.id }, values) }
    }

    fun saveQueueEdit(item: String, text: String) {
        val task = state.value.task ?: return
        if (text.length > 16000 || RemoteQueue.waiting(state.value.commandRecords, item)) return
        try {
            val edits = JSONObject(state.value.queueEdits.toString()).put(item, text)
            section("queueEdits", task, edits)
        } catch (error: Exception) { problem(error) }
    }
    fun updateQueue(item: RemoteQueueItem, action: String) {
        val expected = state.value.remoteState ?: return
        if (RemoteQueue.waiting(state.value.commandRecords, item.id) || action == "remove" && RemoteQueue.withdrawing(state.value.commandRecords)) return
        val text = state.value.queueEdits.opt(item.id) as? String ?: item.text
        val metadata = if (action == "remove") JSONObject().put("withdrawalText", item.text) else JSONObject()
        submitRemote(metadata = metadata) { remote ->
            check(remote.epoch == expected.epoch && !RemoteQueue.waiting(state.value.commandRecords, item.id))
            "queueEdit" to RemoteQueue.payload(remote, item, action, text)
        }
    }
    fun keepWithdrawalCopy() {
        val task = state.value.task ?: return
        if (state.value.commandBusy || state.value.loading) return
        try {
            val (records, draft) = RemoteQueue.recover(remoteRecords(task).objects(), objectFor("drafts").text("desktop:$task"))
            commitRemote(task, JSONArray(records), replaceDraft = draft)
        } catch (error: Exception) { problem(error) }
    }
    fun recoverQueueEdit(item: String) {
        val c = state.value.credential ?: return; val task = state.value.task ?: return
        if (RemoteQueue.waiting(state.value.commandRecords, item)) return
        try {
            val next = JSONObject(account.toString()); val edits = next.getJSONObject("queueEdits").getJSONObject(task)
            val text = edits.text(item); if (text.isBlank()) return
            val drafts = next.getJSONObject("drafts"); val current = drafts.text("desktop:$task")
            drafts.put("desktop:$task", if (current.isBlank()) text else "$current\n\n$text"); edits.remove(item)
            store.write(c.key, next); account = next; restoreDraft()
        } catch (error: Exception) { problem(error) }
    }

    private fun section(name: String, task: String, value: Any) {
        val c = state.value.credential ?: return
        val next = JSONObject(account.toString())
        val section = next.optJSONObject(name) ?: JSONObject().also { next.put(name, it) }
        section.put(task, value)
        store.write(c.key, next); account = next
        if (state.value.task == task && state.value.desktop) restoreDraft()
    }
    fun importFile(uri: android.net.Uri) {
        if (state.value.cacheBusy) return
        val c = state.value.credential ?: return; val task = state.value.task ?: return; val gen = generation
        if (state.value.commandBusy || state.value.commandRecords.any { it.optBoolean("pending") } || transferJobs["import:$task"]?.isActive == true) return
        transferJobs["import:$task"] = launchFileWork {
            try {
                check(objectFor("uploads").optJSONArray(task)?.length().let { (it ?: 0) < 4 })
                val file = withContext(Dispatchers.IO) { files.import(c.key, uri) }
                if (!current(c, gen)) return@launchFileWork
                val rows = objectFor("uploads").optJSONArray(task)?.objects().orEmpty()
                check(rows.size < 4)
                check(rows.sumOf { it.getLong("bytes") } + file.bytes <= 33554432)
                if (rows.none { it.text("hash") == file.hash }) section("uploads", task, JSONArray(rows + file.json().put("complete", false)))
            } catch (error: Exception) { if (current(c, gen)) problem(error) }
        }
    }
    internal fun transferActive(hash: String) = transferJobs[hash]?.isActive == true
    fun pauseTransfer(hash: String) { transferJobs[hash]?.cancel() }
    fun removeUpload(hash: String) {
        if (state.value.cacheBusy) return
        val c = state.value.credential ?: return; val task = state.value.task ?: return; val gen = generation
        if (state.value.commandBusy || state.value.commandRecords.any { it.optBoolean("pending") }) return
        val previous = transferJobs[hash]
        transferJobs[hash] = launchFileWork {
            previous?.cancelAndJoin()
            try {
                val path = "sync/sessions/$task/uploads/$hash"
                var present = true
                try { api.call(c, path) } catch (error: ApiFailure) { if (error.status == 404) present = false else throw error }
                if (present) api.delete(c, path)
                if (current(c, gen)) section("uploads", task, JSONArray(objectFor("uploads").optJSONArray(task)?.objects().orEmpty().filter { it.text("hash") != hash }))
            } catch (error: Exception) { if (current(c, gen)) problem(error) }
        }
    }
    fun upload(file: SyncedFile) {
        if (state.value.cacheBusy) return
        val c = state.value.credential ?: return; val task = state.value.task ?: return; val gen = generation
        if (transferJobs[file.hash]?.isActive == true) return
        transferJobs[file.hash] = launchFileWork {
            try {
                check(withContext(Dispatchers.IO) { files.verified(c.key, file) } != null)
                val path = "sync/sessions/$task/uploads/${file.hash}"
                var status = try { JSONObject(api.call(c, path)) } catch (error: ApiFailure) {
                    if (error.status != 404) throw error
                    JSONObject(api.call(c, path, JSONObject().put("bytes", file.bytes).put("name", file.name).put("mediaType", file.mediaType)))
                }
                val meta = status.getJSONObject("metadata")
                check(meta.getLong("bytes") == file.bytes && meta.text("name") == file.name && meta.text("mediaType") == file.mediaType)
                var offset = status.getLong("nextOffset"); check(offset in 0..file.bytes && file.bytes <= status.getLong("maxBytes"))
                check(!status.getBoolean("complete") || offset == file.bytes)
                while (!status.getBoolean("complete")) {
                    ensureActive(); if (!current(c, gen)) return@launchFileWork
                    val bytes = withContext(Dispatchers.IO) { java.io.RandomAccessFile(files.local(c.key, file), "r").use { input ->
                        input.seek(offset); ByteArray(minOf(196608L, file.bytes - offset).toInt()).also { input.readFully(it) }
                    } }
                    status = JSONObject(api.call(c, "$path/chunks", JSONObject().put("offset", offset).put("data", java.util.Base64.getEncoder().encodeToString(bytes))))
                    check(status.getLong("nextOffset") == offset + bytes.size && status.getBoolean("complete") == (offset + bytes.size == file.bytes))
                    offset += bytes.size
                    if (current(c, gen)) mutable.update { it.copy(transferProgress = it.transferProgress + (file.hash to offset)) }
                }
                if (current(c, gen)) section("uploads", task, JSONArray(objectFor("uploads").optJSONArray(task)?.objects().orEmpty().map { if (it.text("hash") == file.hash) JSONObject(it.toString()).put("complete", true) else it }))
            } catch (error: Exception) { if (current(c, gen)) problem(error) }
            finally { if (current(c, gen)) mutable.update { it.copy(transferProgress = it.transferProgress - file.hash) } }
        }
    }
    fun stagePreviewRetry(source: ResultPreviewSource): Boolean {
        val c = state.value.credential ?: return false
        val task = state.value.task ?: return false
        if (!state.value.desktop || source.account != c.key || source.task != task) return false
        val manifest = source.manifest
        val id = manifest.text("id"); val version = manifest.optInt("version")
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}")) || version < 1) return false
        if (state.value.resultFiles.none {
            val current = it.optJSONObject("manifest")
            current?.text("id") == id && current.optInt("version") == version && current.text("previewState") == "failed"
        }) return false
        return try {
            val text = getApplication<Application>().getString(R.string.result_retry_preview_request, id, version)
            val next = JSONObject(account.toString())
            val drafts = next.optJSONObject("drafts") ?: JSONObject().also { next.put("drafts", it) }
            val before = drafts.text("desktop:$task")
            val draft = if (before.isEmpty()) text else "$before\n\n$text"
            check(draft.toByteArray().size <= 65536)
            drafts.put("desktop:$task", draft)
            store.write(c.key, next); account = next; restoreDraft()
            mutable.update { it.copy(error = null) }
            true
        } catch (error: Exception) { problem(error); false }
    }
    fun loadResults() {
        val c = state.value.credential ?: return; val task = state.value.task ?: return; val gen = generation
        if (transferJobs["results:$task"]?.isActive == true) return
        transferJobs["results:$task"] = viewModelScope.launch {
            try {
                var cursor = -1L; val rows = mutableListOf<JSONObject>()
                do {
                    val page = JSONObject(api.call(c, "sync/sessions/$task/results?after=$cursor"))
                    val incoming = page.getJSONArray("items").objects(); rows += incoming; check(rows.size <= 1000)
                    val after = page.getLong("after"); check(!page.getBoolean("hasMore") || after > cursor)
                    cursor = after
                } while (page.getBoolean("hasMore"))
                if (current(c, gen)) section("results", task, JSONArray(rows))
            } catch (error: Exception) { if (current(c, gen)) problem(error) }
        }
    }
    fun download(file: SyncedFile, path: String) {
        if (state.value.cacheBusy) return
        val c = state.value.credential ?: return; val gen = generation
        if (transferJobs[file.hash]?.isActive == true) return
        transferJobs[file.hash] = launchFileWork {
            try {
                if (withContext(Dispatchers.IO) { files.verified(c.key, file) } != null) return@launchFileWork
                val part = files.partial(c.key, file); check(part.length() <= file.bytes)
                check(files.directory(c.key).listFiles().orEmpty().count { it.isFile } < 128)
                check(files.directory(c.key).listFiles().orEmpty().sumOf { it.length() } + (file.bytes - part.length()) <= 268435456)
                var offset = part.length()
                if (file.bytes == 0L) api.fileRange(c, path, file, 0)
                while (offset < file.bytes) {
                    ensureActive(); if (!current(c, gen)) return@launchFileWork
                    mutable.update { it.copy(transferProgress = it.transferProgress + (file.hash to offset)) }
                    val bytes = api.fileRange(c, path, file, offset)
                    withContext(Dispatchers.IO) { java.io.FileOutputStream(part, true).use { it.write(bytes); it.fd.sync() } }
                    offset += bytes.size
                    if (current(c, gen)) mutable.update { it.copy(transferProgress = it.transferProgress + (file.hash to offset)) }
                }
                if (!part.exists()) withContext(Dispatchers.IO) { java.io.FileOutputStream(part).use { it.fd.sync() } }
                withContext(Dispatchers.IO) { if (ClientFiles.digest(part) != file.hash) { part.delete(); throw java.io.IOException("Downloaded checksum mismatch") } }
                check(part.renameTo(files.local(c.key, file)))
            } catch (error: Exception) { if (current(c, gen)) problem(error) }
            finally { if (current(c, gen)) mutable.update { it.copy(transferProgress = it.transferProgress - file.hash) } }
        }
    }
    fun freezeReview() {
        val task = state.value.task ?: return
        if (objectFor("reviews").optJSONObject(task) != null) return
        val latest = ReviewSnapshot.latest(state.value.desktopEvents)?.json()
            ?: ReviewDrafts.rounds(account, task).firstOrNull() ?: return
        selectReview(latest.getLong("end"))
    }
    fun selectReview(end: Long) {
        val c = state.value.credential ?: return; val task = state.value.task ?: return
        val selected = ReviewDrafts.rounds(account, task).firstOrNull { it.sequence("end") == end }
            ?: ReviewSnapshot.rounds(state.value.desktopEvents).firstOrNull { it.end == end }?.json()
            ?: ReviewSnapshot.latest(state.value.desktopEvents)?.takeIf { it.end == end }?.json() ?: return
        try {
            val next = ReviewDrafts.select(account, task, selected)
            store.write(c.key, next); account = next; restoreDraft()
        } catch (error: Exception) { problem(error) }
    }
    fun reviewComment(comment: String, fileKey: String? = null) {
        val task = state.value.task ?: return; val review = state.value.review ?: return
        if (comment.length > 16000) return
        try {
            val next = JSONObject(review.toString())
            if (fileKey == null) next.put("comment", comment) else next.getJSONObject("notes").put(fileKey, comment)
            section("reviews", task, next)
        } catch (error: Exception) { problem(error) }
    }
    fun reviewLine(sequence: Long, changeKey: String, index: Int) {
        val review = state.value.review ?: return
        val message = review.getJSONArray("messages").objects().firstOrNull { it.sequence("seq") == sequence } ?: return
        val change = ReviewChanges.parse(sequence, message.text("text")).firstOrNull { it.key == changeKey } ?: return
        val line = ReviewDiff.parse(change.patch).getOrNull(index) ?: return
        val reference = line.reference(sequence, change.path, getApplication<Application>().getString(R.string.old_line), getApplication<Application>().getString(R.string.new_line)) ?: return
        val comment = review.text("comment")
        if (!comment.contains(reference)) reviewComment(if (comment.isEmpty()) reference else "$comment\n\n$reference")
    }
    fun stageReview(): Boolean {
        val c = state.value.credential ?: return false; val task = state.value.task ?: return false; val review = state.value.review ?: return false
        if (state.value.commandBusy || state.value.commandRecords.any { it.optBoolean("pending") }) return false
        return try {
            val next = ReviewDrafts.stage(account, task, getApplication<Application>().getString(R.string.review_feedback))
            store.write(c.key, next); account = next; restoreDraft(); true
        } catch (error: Exception) { problem(error); false }
    }

    fun olderEvents() {
        val s = state.value; val c = s.credential ?: return; val task = s.task ?: return
        if (s.historyStart <= 0 || s.loading) return
        val start = maxOf(0L, s.historyStart - 100); val gen = generation
        viewModelScope.launch {
            mutable.update { it.copy(loading = true) }
            try {
                val decoded = RemoteHistory.range(start, s.historyStart) { offset, limit ->
                    ensureActive()
                    if (!current(c, gen) || state.value.task != task) throw CancellationException()
                    JSONObject(api.call(c, "sync/sessions/$task/events?offset=$offset&limit=$limit"))
                }
                if (current(c, gen) && state.value.task == task) mutable.update {
                    it.copy(historyStart = start, desktopEvents = RemoteHistory.merge(it.desktopEvents, decoded.events), desktopMessages = visibleEvents(RemoteHistory.merge(it.desktopEvents, decoded.events)))
                }
            } catch (error: Exception) { if (current(c, gen)) problem(error) }
            finally { if (current(c, gen)) mutable.update { it.copy(loading = false) } }
        }
    }
    private suspend fun snapshot(c: Credential, chat: String, gen: Long): JSONObject {
        val data = JSONObject(api.call(c, "chats/$chat"))
        if (!current(c, gen)) return data
        val turns = data.optJSONArray("turns") ?: JSONArray()
        val terminal = setOf("SUCCEEDED", "FAILED", "CANCELLED", "EXPIRED", "REJECTED")
        val pending = objectFor("pending").optJSONObject(chat)
        if (pending != null) for (i in 0 until turns.length()) {
            val turn = turns.getJSONObject(i)
            if (turn.optString("id") == pending.optString("id") && (turn.optString("ticket_state") in terminal)) {
                objectFor("pending").remove(chat); save()
            }
        }
        if (state.value.chat == chat) {
            val messages = buildList { for (i in 0 until turns.length()) {
                val turn = turns.getJSONObject(i); val id = turn.getString("id")
                add(ChatMessage("$id-user", turn.optString("user_text"), true))
                if (turn.optString("reply_text").isNotEmpty()) add(ChatMessage("$id-reply", turn.getString("reply_text"), false))
            } }
            val phase = objectFor("pending").optJSONObject(chat)?.let { TurnPhase.valueOf(it.getString("phase")) }
            val metadata = data.optJSONObject("chat")
            val saved = objectFor("historyMetadata").optJSONObject(chat)
            val latest = if ((saved?.sequence("history_revision") ?: -1) > (metadata?.sequence("history_revision") ?: -1)) saved else metadata
            mutable.update { it.copy(messages = messages, phase = phase, phonePhases = phonePhases(), chatMetadata = latest,
                phoneUsage = PhoneUsage.parse(turns.objects()), live = if (phase == null) "" else it.live) }
        }
        return data
    }
    fun send() {
        val s = state.value; val c = s.credential ?: return
        if (s.desktop || s.chatMetadata?.optBoolean("archived") == true || s.phase != null || s.draft.isBlank() || s.draft.length > 16000 || s.model.isBlank()) return
        val selectedModel = s.models.firstOrNull { it.id == s.model } ?: return
        val chat = s.chat ?: id(); val gen = generation
        if (workers[chat]?.isActive == true) return
        val pending = JSONObject().put("id", id()).put("modelId", s.model).put("text", s.draft)
            .put("phase", TurnPhase.PREPARING.name).put("executionAttempted", false)
        if (mobileThinkingLevels(selectedModel).isNotEmpty()) pending.put("reasoningEffort", s.reasoningEffort)
        try {
            objectFor("pending").put(chat, pending); account.put("chat", chat)
            // Keep the submitted copy until registration is confirmed; persistence failure prevents sending.
            save(); mutable.update { it.copy(chat = chat, phonePhases = phonePhases(), phase = TurnPhase.PREPARING, live = "", error = null) }
        } catch (error: Exception) { problem(error); return }
        workers[chat] = viewModelScope.launch {
            try {
                var revision = 0L
                if (s.chat != null) revision = snapshot(c, chat, gen).getJSONObject("chat").getLong("revision")
                if (!current(c, gen)) return@launch
                pending.put("expectedRevision", revision); save()
                val request = JSONObject().put("id", pending.getString("id")).put("modelId", pending.getString("modelId")).put("text", pending.getString("text")).put("expectedRevision", revision)
                if (pending.has("reasoningEffort")) request.put("reasoningEffort", pending.getString("reasoningEffort"))
                val prepared = JSONObject(api.call(c, "chats/$chat/turns", request))
                require(prepared.getString("turnId") == pending.getString("id"))
                if (!current(c, gen)) return@launch
                // Clear only the exact submitted draft, preserving any later input.
                if (objectFor("drafts").optString(draftKey(false, s.chat)) == s.draft) objectFor("drafts").put(draftKey(false, s.chat), "")
                pending.put("phase", TurnPhase.WAITING.name); save()
                if (state.value.chat == chat) mutable.update { it.copy(draft = objectFor("drafts").optString(draftKey(false, chat)), phase = TurnPhase.WAITING) }
                while (current(c, gen)) {
                    val snap = snapshot(c, chat, gen); val ticket = snap.getJSONObject("ticket")
                    if (ticket.optString("state") == "GRANTED") break
                    if (ticket.optString("state") != "WAITING") return@launch
                    delay(1000)
                }
                if (!current(c, gen)) return@launch
                pending.put("phase", TurnPhase.EXECUTING.name).put("executionAttempted", true); save()
                if (state.value.chat == chat) mutable.update { it.copy(phase = TurnPhase.EXECUTING) }
                val turn = PendingTurn(chat, pending.getString("id"), s.model, s.draft, revision, TurnPhase.EXECUTING, true)
                api.execute(c, turn) { delta -> viewModelScope.launch {
                    if (current(c, gen) && state.value.chat == chat) mutable.update { it.copy(live = it.live + delta) }
                } }
                snapshot(c, chat, gen)
            } catch (error: Exception) { if (current(c, gen)) problem(error) }
            finally {
                if (current(c, gen) && objectFor("pending").has(chat)) {
                    pending.put("phase", TurnPhase.NEEDS_CHECK.name); runCatching { save() }.onFailure { problem(it) }
                    if (state.value.chat == chat) mutable.update { it.copy(phase = TurnPhase.NEEDS_CHECK, phonePhases = phonePhases()) }
                }
            }
        }
    }
    fun checkTurn() {
        val c = state.value.credential ?: return; val chat = state.value.chat ?: return; val gen = generation
        viewModelScope.launch { try { snapshot(c, chat, gen) } catch (error: Exception) { if (current(c, gen)) problem(error) } }
    }
    fun stop() {
        val c = state.value.credential ?: return; val chat = state.value.chat ?: return
        val pending = objectFor("pending").optJSONObject(chat) ?: return
        val gen = generation
        viewModelScope.launch {
            try { api.call(c, "chats/$chat/turns/${pending.getString("id")}/cancel", JSONObject()); if (current(c, gen)) snapshot(c, chat, gen) }
            catch (error: Exception) { if (current(c, gen)) problem(error) }
        }
    }
    private fun cancelAccountWork() {
        ++profileGeneration; avatarJob?.cancel()
        ++searchGeneration; searching?.cancel()
        ++creationGeneration; creationCall?.cancel(); creationPolling?.cancel(); creationOpen = false
    }
    private fun restoreAccountFields() {
        val reads = account.optJSONObject("taskReads") ?: JSONObject()
        mutable.update { it.copy(pendingCreate = account.optJSONObject("pendingCreate"), createdTask = account.text("createdTask").takeIf(String::isNotBlank),
            creationText = account.text("creationText"), creationDevice = account.text("creationDevice"), creationWorkspace = account.text("creationWorkspace"),
            taskReads = reads.keys().asSequence().mapNotNull { key -> reads.sequence(key)?.let { key to it } }.toMap()) }
    }
    fun openAvatar() { mutable.update { it.copy(avatarEditor = true, avatarCandidate = null, avatarError = false) } }
    fun closeAvatar() { if (!state.value.avatarBusy) mutable.update { it.copy(avatarEditor = false, avatarCandidate = null, avatarError = false) } }
    fun prepareAvatar(uri: android.net.Uri) {
        val c = state.value.credential ?: return; val gen = generation
        if (state.value.avatarBusy) return
        avatarJob = viewModelScope.launch {
            mutable.update { it.copy(avatarBusy = true, avatarError = false) }
            try {
                val value = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input)
                        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 20 * 1024 * 1024); output.write(buffer, 0, count) }
                        AvatarImage.normalize(output.toByteArray())
                    }
                }
                if (current(c, gen)) mutable.update { it.copy(avatarCandidate = value) }
            } catch (error: Exception) { if (current(c, gen) && error !is CancellationException) mutable.update { it.copy(avatarError = true) } }
            finally { if (current(c, gen)) mutable.update { it.copy(avatarBusy = false) } }
        }
    }
    fun saveAvatar() {
        val c = state.value.credential ?: return; val image = state.value.avatarCandidate ?: return; val gen = generation
        if (state.value.avatarBusy) return
        ++profileGeneration
        avatarJob = viewModelScope.launch {
            mutable.update { it.copy(avatarBusy = true, avatarError = false) }
            try {
                var writeError: Exception? = null
                try { api.call(c, "avatar", JSONObject().put("image", image)) } catch (error: Exception) { if (error is CancellationException) throw error; writeError = error }
                val profile = JSONObject(api.call(c, "session"))
                if (!current(c, gen)) return@launch
                if (profile.text("avatarUrl") != image) throw writeError ?: java.io.IOException("Avatar not confirmed")
                mutable.update { it.copy(avatar = image, avatarCandidate = null, avatarEditor = false) }
            } catch (error: Exception) {
                if (current(c, gen) && error !is CancellationException) {
                    mutable.update { it.copy(avatarError = true) }
                    if (error is ApiFailure && error.status == 401) problem(ApiFailure(401, "SESSION_EXPIRED"))
                }
            } finally { if (current(c, gen)) mutable.update { it.copy(avatarBusy = false) } }
        }
    }
    fun taskSearchMode(messages: Boolean) { mutable.update { it.copy(messageSearch = messages) }; search(state.value.query) }
    fun loadTaskSearch() {
        val s = state.value; val c = s.credential ?: return; val gen = generation; val revision = searchGeneration
        if (!visible || !s.desktop || !s.messageSearch || s.searchBusy || !s.searchMore || s.query.isBlank()) return
        if (s.query.trim().length > 160) { mutable.update { it.copy(searchError = true, searchMore = false) }; return }
        searching = viewModelScope.launch {
            mutable.update { it.copy(searchBusy = true, searchError = false) }
            try {
                var cursor = s.searchCursor; var found = 0
                for (pageIndex in 0 until 20) {
                    val path = "sync/search?query=${URLEncoder.encode(s.query.trim(), "UTF-8")}&afterSession=${cursor.session}&afterSeq=${cursor.sequence}&archived=${s.archived}&deviceID="
                    val page = TaskSearchPage.parse(JSONObject(api.call(c, path)), cursor)
                    ensureActive(); if (!current(c, gen) || revision != searchGeneration || !visible || !state.value.desktop) return@launch
                    mutable.update { it.copy(searchHits = (it.searchHits + page.items).distinctBy { hit -> hit.id }, searchCursor = page.cursor, searchMore = page.more) }
                    found += page.items.size; cursor = page.cursor
                    if (!page.more || found >= 50) break
                }
            } catch (error: Exception) {
                if (current(c, gen) && revision == searchGeneration && error !is CancellationException) {
                    mutable.update { it.copy(searchError = true) }
                    if (error is ApiFailure && error.status == 401) problem(ApiFailure(401, "SESSION_EXPIRED"))
                }
            } finally { if (current(c, gen) && revision == searchGeneration) mutable.update { it.copy(searchBusy = false) } }
        }
    }
    fun taskVisible(active: Boolean) { taskOnscreen = active }
    fun markTaskRead(next: Long, taskID: String? = state.value.task) {
        val s = state.value; val task = s.task ?: return
        if (task != taskID || !visible || !taskOnscreen || !s.desktop || !s.remoteAtTail || next <= (s.taskReads[task] ?: 0) || next > s.remoteLatest) return
        try {
            objectFor("taskReads").put(task, next); save()
            mutable.update { it.copy(taskReads = it.taskReads + (task to next)) }
        } catch (error: Exception) { problem(error) }
    }
    fun latestTaskHistory() { state.value.tasks.firstOrNull { it.id == state.value.task }?.let { select(it) } }
    fun chooseDevice(id: String) {
        val s = state.value; val device = s.availableDevice(id) ?: return
        val workspace = if (s.creationDevice == id && device.workspaces.any { it.id == s.creationWorkspace }) s.creationWorkspace
            else device.workspaces.singleOrNull()?.id.orEmpty()
        creation(device = id, workspace = workspace)
    }
    fun prepareCreation() {
        val s = state.value
        if (s.creationDevice.isBlank()) s.devices.filter { s.availableDevice(it.id) != null }.singleOrNull()?.let { chooseDevice(it.id) }
        loadDevices()
    }
    fun retryDevices() {
        if (deviceJob?.isActive == true) return
        mutable.update { it.copy(devicesError = false) }
        loadDevices()
    }
    fun loadDevices() {
        val c = state.value.credential ?: return; val gen = generation
        if (!visible || deviceJob?.isActive == true) return
        val attempt = ++deviceGeneration
        deviceJob = viewModelScope.launch {
            mutable.update { it.copy(devicesLoading = true) }
            try {
                val devices = mutableListOf<Row>(); var cursor = ""
                while (true) {
                    val page = DeviceInfo.page(JSONArray(api.call(c, "sync/devices?after=$cursor")), cursor)
                    ensureActive()
                    if (!current(c, gen) || attempt != deviceGeneration || !visible) return@launch
                    devices.addAll(page)
                    if (page.size < 100) break
                    cursor = page.last().id
                }
                mutable.update { it.copy(devices = devices.sortedBy { row -> row.title.lowercase() }, devicesFresh = true, devicesError = false) }
            } catch (error: Exception) {
                if (current(c, gen) && attempt == deviceGeneration && visible) {
                    mutable.update { it.copy(devicesFresh = false, devicesError = error !is CancellationException) }
                    if (error is ApiFailure && error.status == 401) problem(ApiFailure(401, "SESSION_EXPIRED"))
                }
            } finally {
                if (current(c, gen) && attempt == deviceGeneration) mutable.update { it.copy(devicesLoading = false) }
            }
        }
    }
    fun create() {
        val s = state.value; val c = s.credential ?: return
        if (!s.canCreateConversation()) return
        val device = s.devices.firstOrNull { it.id == s.creationDevice } ?: return
        val workspaces = device.json.optJSONArray("workspaces") ?: return
        if ((0 until workspaces.length()).none { workspaces.optJSONObject(it)?.optString("id") == s.creationWorkspace }) return
        require(ServiceIdentity.validId(device.json.getString("epoch")))
        val payload = JSONObject().put("workspaceId", s.creationWorkspace)
        val request = JSONObject().put("id", id()).put("epoch", device.json.getString("epoch")).put("kind", "create").put("payload", payload)
        val record = JSONObject().put("device", device.id).put("request", request).put("text", s.creationText).put("createdAt", System.currentTimeMillis()).put("status", "sending")
        try { persistCreation(record) }
        catch (error: Exception) { problem(error); return }
        creationRequest(submit = true)
    }
    private fun persistCreation(record: JSONObject) {
        val c = state.value.credential ?: return
        val next = JSONObject(account.toString()).put("pendingCreate", record)
        store.write(c.key, next); account = next; mutable.update { it.copy(pendingCreate = record) }
    }
    fun creationVisible(open: Boolean) { creationOpen = open; if (open) resumeCreationPolling() else { creationPolling?.cancel(); creationPolling = null } }
    private fun resumeCreationPolling() {
        if (!visible || !creationOpen || creationPolling?.isActive == true) return
        creationPolling = viewModelScope.launch {
            while (visible && creationOpen) {
                val record = state.value.pendingCreate
                if (record != null && CreationRecovery.status(record) !in CreationRecovery.terminal) checkCreation()
                delay(2000)
            }
        }
    }
    private fun stopCreationTransport() {
        ++creationGeneration; creationCall?.cancel(); creationCall = null; creationPolling?.cancel(); creationPolling = null
        val record = state.value.pendingCreate
        if (record != null && CreationRecovery.status(record) == "sending") runCatching { persistCreation(JSONObject(record.toString()).put("status", "unknown")) }.onFailure(::problem)
        mutable.update { it.copy(creationBusy = false) }
    }
    fun checkCreation() = creationRequest(submit = false)
    fun retryCreation() {
        val s = state.value; val record = s.pendingCreate ?: return
        if (s.devicesFresh && CreationRecovery.canRetry(record, s.availableDevice(record.text("device")), System.currentTimeMillis())) creationRequest(submit = true)
    }
    private fun creationRequest(submit: Boolean) {
        val s = state.value; val c = s.credential ?: return; val record = s.pendingCreate ?: return; val gen = generation
        if (!visible || s.creationBusy || !CreationRecovery.valid(record)) return
        val revision = ++creationGeneration; val operation = CreationRecovery.id(record)
        fun valid() = current(c, gen) && revision == creationGeneration && state.value.pendingCreate?.let(CreationRecovery::id) == operation
        creationCall = viewModelScope.launch {
            mutable.update { it.copy(creationBusy = true) }
            try {
                if (submit) persistCreation(JSONObject(record.toString()).put("status", "sending"))
                val path = "sync/devices/${record.getString("device")}/commands"
                val result = JSONObject(if (submit) api.call(c, path, record.getJSONObject("request")) else api.call(c, "$path/$operation"))
                ensureActive(); if (!valid()) return@launch
                val nextRecord = CreationRecovery.reconcile(record, result)
                if (CreationRecovery.status(nextRecord) == "accepted") {
                    val next = CreationRecovery.accepted(account, nextRecord)
                    store.write(c.key, next); account = next
                    mutable.update { it.copy(pendingCreate = null, creationText = "", createdTask = next.text("createdTask")) }
                    if (creationOpen && visible) openCreatedTask()
                } else persistCreation(nextRecord)
            } catch (error: Exception) {
                if (valid() && error !is CancellationException) {
                    runCatching { persistCreation(JSONObject(record.toString()).put("status", if (!submit && error is ApiFailure && error.status == 404) "missing" else "unknown").put("error", true)) }.onFailure(::problem)
                    if (error is ApiFailure && error.status == 401) problem(ApiFailure(401, "SESSION_EXPIRED"))
                }
            } finally { if (current(c, gen) && revision == creationGeneration) mutable.update { it.copy(creationBusy = false) } }
        }
    }
    fun clearCreation() {
        val s = state.value; val c = s.credential ?: return; val record = s.pendingCreate ?: return
        if (s.creationBusy || !CreationRecovery.canClear(record, System.currentTimeMillis())) return
        try {
            val next = JSONObject(account.toString()); next.remove("pendingCreate")
            if (next.text("creationText").isBlank()) next.put("creationText", record.text("text"))
            store.write(c.key, next); account = next; mutable.update { it.copy(pendingCreate = null, creationText = next.text("creationText")) }
        } catch (error: Exception) { problem(error) }
    }
    fun openCreatedTask() {
        val s = state.value; val c = s.credential ?: return; val session = s.createdTask ?: return
        try {
            val next = JSONObject(account.toString()).put("task", session).put("desktop", true); next.remove("createdTask")
            store.write(c.key, next); account = next
            refreshing?.cancel(); refreshing = null
            mutable.update { it.copy(desktop = true, task = session, createdTask = null, creationNavigation = it.creationNavigation + 1, desktopMessages = emptyList(), desktopEvents = emptyList(), remoteLatest = 0, remoteAtTail = false, historyTarget = null, historyStart = 0, remote = null, remoteState = null, remoteReady = false) }
            restoreDraft(); refresh()
        } catch (error: Exception) { problem(error) }
    }
}
