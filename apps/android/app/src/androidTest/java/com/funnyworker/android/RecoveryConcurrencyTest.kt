package com.funnyworker.android

import android.app.Application
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Isolated synthetic transport for races and lost responses; never contacts any server. */
class RecoveryConcurrencyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Backend : Interceptor {
        @Volatile var avatar = ""
        @Volatile var blockProfile = false
        val profileStarted = CountDownLatch(1); val releaseProfile = CountDownLatch(1)
        val creationStarted = CountDownLatch(1); val releaseCreation = CountDownLatch(1)
        @Volatile var blockCreation = false
        @Volatile var loseCreationResponse = false
        @Volatile var accepted = true
        @Volatile var messageAvailable = false
        val posts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val firstProfile = AtomicBoolean(true)
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request(); val path = request.url.encodedPath.substringAfter("/api/account/")
            val value: Any = when {
                path == "login" -> JSONObject().put("token", "isolated-token")
                path == "session" -> {
                    val picture = avatar
                    if (blockProfile && firstProfile.getAndSet(false)) { profileStarted.countDown(); check(releaseProfile.await(10, TimeUnit.SECONDS)) }
                    JSONObject().put("tenant", "dev").put("principalId", "isolated-member").put("nickname", "Member").put("avatarUrl", picture)
                }
                path == "avatar" -> { avatar = JSONObject(request.body!!.let { body -> okio.Buffer().also { body.writeTo(it) }.readUtf8() }).getString("image"); JSONObject() }
                path == "sync/devices" -> JSONArray("""[{"id":"mac","name":"Mac mini","online":true,"epoch":"epoch-one","workspaces":[{"id":"work","title":"Work"}]}]""")
                path == "sync/sessions" -> if (messageAvailable) JSONArray("""[{"id":"new-task","title":"Sales","device_id":"mac","next_seq":1,"online":true}]""") else JSONArray()
                path.startsWith("sync/devices/mac/commands") -> {
                    val id = if (request.method == "POST") {
                        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8(); posts += body
                        creationStarted.countDown()
                        if (blockCreation) check(releaseCreation.await(10, TimeUnit.SECONDS))
                        if (loseCreationResponse) throw IOException("Deliberately lost response")
                        JSONObject(body).getString("id")
                    } else request.url.pathSegments.last()
                    JSONObject().put("id", id).put("status", if (accepted) "accepted" else "queued").put("result", JSONObject().put("sessionId", "new-task"))
                }
                path.endsWith("/commands") -> JSONArray()
                path.endsWith("/state") -> JSONObject().put("online", true).put("epoch", "epoch-one").put("state", JSONObject())
                path.endsWith("/events") -> {
                    val offset = request.url.queryParameter("offset")?.toLong() ?: 0
                    val events = if (messageAvailable && offset == 0L) JSONArray("""[{"seq":0,"type":"user/message","data":{"content":[{"type":"text","text":"Sales summary"}]}}]""") else JSONArray()
                    JSONObject().put("events", events).put("nextSeq", offset + events.length()).put("durableNextSeq", if (messageAvailable) 1 else 0)
                }
                path == "chats/models" -> JSONObject().put("items", JSONArray())
                path == "chats" -> JSONObject().put("items", JSONArray()).put("next", "")
                else -> JSONObject()
            }
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(value.toString().toResponseBody("application/json".toMediaType())).build()
        }
    }
    private suspend fun await(test: () -> Boolean) = withTimeout(12000) { while (!test()) delay(20) }
    private suspend fun main(block: () -> Unit) = withContext(Dispatchers.Main) { block() }
    private inner class Fixture(val backend: Backend = Backend()) {
        val name = "parity_" + UUID.randomUUID().toString().replace("-", "")
        val secure = SecureStore(context, name)
        var owner = ViewModelStore()
        lateinit var model: AppViewModel
        suspend fun start(login: Boolean = true) {
            withContext(Dispatchers.Main) {
                model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application,
                        AccountApi(OkHttpClient.Builder().addInterceptor(backend).build(), automaticRouting = false), secure) as T
                })[AppViewModel::class.java]
            }
            await { model.state.value.ready }
            if (login) main { model.login("https://localhost:8766", "isolated-member", "synthetic-password") }
            await { model.state.value.credential != null && !model.state.value.loading && model.state.value.devicesFresh }
        }
        suspend fun create() {
            main { model.creationVisible(true); model.chooseDevice("mac"); model.creation(text = "整理本月销售数据"); model.create() }
        }
        suspend fun close() {
            backend.releaseProfile.countDown(); backend.releaseCreation.countDown()
            main { owner.clear() }; File(context.noBackupFilesDir, name).deleteRecursively()
        }
    }
    @Test fun delayedOldProfileCannotOverwriteSavedAvatar(): Unit = runBlocking {
        val f = Fixture(); val input = File(context.filesDir, "transfers/avatar-race-${UUID.randomUUID()}.png").apply { parentFile!!.mkdirs() }
        try {
            f.start(); context.resources.openRawResource(R.drawable.brand_portrait).use { source -> input.outputStream().use { source.copyTo(it) } }
            f.backend.blockProfile = true
            main { f.model.refresh() }; await { f.backend.profileStarted.count == 0L }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", input)
            main { f.model.openAvatar(); f.model.prepareAvatar(uri) }; await { f.model.state.value.avatarCandidate != null && !f.model.state.value.avatarBusy }
            val selected = f.model.state.value.avatarCandidate
            main { f.model.saveAvatar() }; await { !f.model.state.value.avatarBusy && !f.model.state.value.avatarEditor }
            f.backend.releaseProfile.countDown(); await { !f.model.state.value.loading }
            assertEquals(selected, f.model.state.value.avatar)
            main { f.model.openAvatar(); f.model.prepareAvatar(uri) }; await { !f.model.state.value.avatarBusy }
            main { f.model.closeAvatar() }; assertEquals(selected, f.model.state.value.avatar); assertNull(f.model.state.value.avatarCandidate)
        } finally { input.delete(); f.close() }
    }
    @Test fun acceptedResultAfterClosingSheetIsSavedWithoutUnexpectedNavigation(): Unit = runBlocking {
        val f = Fixture(); f.backend.blockCreation = true
        try {
            f.start(); f.create(); await { f.backend.creationStarted.count == 0L }
            main { f.model.creationVisible(false) }; f.backend.releaseCreation.countDown()
            await { f.model.state.value.createdTask == "new-task" }
            assertNull(f.model.state.value.task)
            main { f.owner.clear(); f.owner = ViewModelStore() }; f.start(login = false)
            assertEquals("new-task", f.model.state.value.createdTask)
            main { f.model.openCreatedTask() }; assertEquals("整理本月销售数据", f.model.state.value.draft)
            assertEquals(1, f.backend.posts.size)
        } finally { f.close() }
    }
    @Test fun lostPostResponseRecoversOriginalReceiptWithoutResubmission(): Unit = runBlocking {
        val f = Fixture(); f.backend.loseCreationResponse = true
        try {
            f.start(); f.create(); main { f.model.creationVisible(false) }
            await { f.model.state.value.pendingCreate?.text("status") == "unknown" }
            val id = CreationRecovery.id(f.model.state.value.pendingCreate!!)
            main { f.model.creationVisible(true); f.model.checkCreation() }
            await { f.model.state.value.task == "new-task" }
            assertEquals(1, f.backend.posts.size)
            assertEquals(id, JSONObject(f.backend.posts.single()).getString("id"))
            assertEquals("整理本月销售数据", f.model.state.value.draft)
        } finally { f.close() }
    }
    @Test fun queuedCreationSurvivesRestartAndSeedsDraftExactlyOnce(): Unit = runBlocking {
        val f = Fixture(); f.backend.accepted = false
        try {
            f.start(); f.create(); await { f.model.state.value.pendingCreate?.text("status") == "queued" }
            val operation = CreationRecovery.id(f.model.state.value.pendingCreate!!)
            main { f.owner.clear(); f.owner = ViewModelStore() }; f.backend.accepted = true; f.start(login = false)
            assertEquals(operation, CreationRecovery.id(f.model.state.value.pendingCreate!!))
            main { f.model.creationVisible(true) }; await { f.model.state.value.task == "new-task" }
            assertEquals(1, f.backend.posts.size); assertEquals("整理本月销售数据", f.model.state.value.draft)
            main { f.owner.clear(); f.owner = ViewModelStore() }; f.start(login = false)
            assertEquals("整理本月销售数据", f.model.state.value.draft)
        } finally { f.close() }
    }
    @Test fun unreadAcknowledgementRequiresTheCurrentVisibleTaskAndLoadedTail(): Unit = runBlocking {
        val f = Fixture(); f.backend.messageAvailable = true
        try {
            f.start(); main { f.model.source(true) }; await { f.model.state.value.tasks.isNotEmpty() }
            main { f.model.select(f.model.state.value.tasks.single()) }; await { f.model.state.value.remoteLatest == 1L }
            main { f.model.markTaskRead(1) }; assertTrue(f.model.state.value.taskReads.isEmpty())
            main { f.model.taskVisible(true); f.model.markTaskRead(1, "other"); f.model.markTaskRead(2, "new-task") }
            assertTrue(f.model.state.value.taskReads.isEmpty())
            main { f.model.markTaskRead(1, "new-task") }; assertEquals(1L, f.model.state.value.taskReads["new-task"])
            main { f.owner.clear(); f.owner = ViewModelStore() }; f.start(login = false)
            assertEquals(1L, f.model.state.value.taskReads["new-task"])
        } finally { f.close() }
    }
    @Test fun malformedSavedCreationNeverCrashesOrResubmitsAndKeepsItsDraft(): Unit = runBlocking {
        val f = Fixture()
        try {
            f.start(); val key = requireNotNull(f.model.state.value.credential).key
            val saved = (f.secure.read(key) ?: JSONObject()).put("pendingCreate", JSONObject().put("status", "missing")).put("creationText", "保留草稿")
            f.secure.write(key, saved); main { f.owner.clear(); f.owner = ViewModelStore() }; f.start(login = false)
            main { f.model.creationVisible(true); f.model.checkCreation() }
            assertNotNull(f.model.state.value.pendingCreate); assertTrue(f.backend.posts.isEmpty())
            main { f.model.clearCreation() }; assertNull(f.model.state.value.pendingCreate); assertEquals("保留草稿", f.model.state.value.creationText)
        } finally { f.close() }
    }
}
