package com.funnyworker.android

import android.app.Application
import androidx.activity.compose.LocalActivity
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections
import java.io.IOException

/** Real Compose UI and encrypted restart recovery, isolated transport; no production model calls. */
class HistoryParityUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val secure get() = SecureStore(context)
    private var owners = ViewModelStore()
    private lateinit var model: AppViewModel
    private lateinit var credential: Credential
    private var previous: JSONObject? = null
    private val lost = AtomicBoolean(false)
    private val invalidBudget = AtomicBoolean(false)
    private val conflict = AtomicBoolean(false)
    private val directoryReads = java.util.concurrent.atomic.AtomicInteger(0)
    private val posts = Collections.synchronizedList(mutableListOf<JSONObject>())
    private var receipt: JSONObject? = null
    private var record = JSONObject()
    private lateinit var engine: OkHttpClient
    @Before fun setup() {
        previous = secure.read("session")
        credential = Credential(ServiceIdentity.INTERNATIONAL,"fixture-history-token","fixture",UUID.randomUUID().toString(),"Gigi")
        record = JSONObject().put("id","chat-1").put("title","Original title").put("revision",10).put("history_revision",0)
            .put("archived",false).put("modelId","model-1").put("updated_at",System.currentTimeMillis())
        secure.write("session",credential.json())
        secure.write(credential.key,JSONObject().put("chat","chat-1").put("drafts",JSONObject().put("mobile:chat-1","Draft must remain")))
        engine = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request();val path = request.url.encodedPath.substringAfter("/api/account/")
            var status=200
            val data: Any = synchronized(this) {
                when {
                    path == "chats/chat-1/history" && request.method=="POST" -> {
                        val buffer=okio.Buffer();request.body!!.writeTo(buffer);val body=JSONObject(buffer.readUtf8());posts.add(body)
                        if (conflict.get()) {status=409;JSONObject().put("code","CHAT_HISTORY_REVISION_CONFLICT")}
                        else if (receipt != null && posts.first().text("id")==body.text("id")) receipt!!
                        else {
                            check(body.getLong("expectedHistoryRevision")==record.getLong("history_revision"))
                            if(body.has("title"))record.put("title",body.getString("title"))
                            if(body.has("archived"))record.put("archived",body.getBoolean("archived"))
                            record.put("history_revision",record.getLong("history_revision")+1)
                            receipt=JSONObject(record.toString())
                            if(lost.getAndSet(false))throw IOException("Response lost after durable server commit")
                            receipt!!
                        }
                    }
                    path == "chats/chat-1/history" -> record
                    path == "chats" -> {
                        directoryReads.incrementAndGet()
                        val archived = request.url.queryParameter("archived")=="true"
                        val item=JSONObject(record.toString())
                        if(!request.url.queryParameter("query").isNullOrBlank())item.put("search_match",JSONObject().put("field","reply").put("turnId","turn-17").put("excerpt","Needle reply 17").put("created_at",System.currentTimeMillis()))
                        JSONObject().put("items",if(archived==record.getBoolean("archived"))JSONArray().put(item) else JSONArray()).put("next","").put("historyManagement",true)
                    }
                    path == "chats/models" -> JSONObject().put("items",JSONArray().put(JSONObject().put("id","model-1").put("name","Fixture model").put("available",true)))
                    path == "chats/chat-1" -> JSONObject().put("chat",record).put("turns",JSONArray((0..19).map { i -> JSONObject().put("id","turn-$i").put("user_text","Question $i").put("reply_text","Needle reply $i").put("ticket_state","SUCCEEDED").put("usage_quality","COMPLETE").put("input_tokens",10).put("output_tokens",5).put("cached_tokens",2) }))
                    path == "inference/budget" -> if(invalidBudget.get()) JSONObject() else JSONObject().put("configured",true).put("enabled",true).put("remaining_micros",1234567).put("held",200000).put("daily_limit",5000000).put("daily_spent",1000000).put("expires_at",1999999999999).put("promotion",JSONObject().put("mode","DISABLED").put("available_micros",0))
                    else -> JSONObject()
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("History fixture")
                .body(data.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        createModel()
        compose.setContent {
            val activity=requireNotNull(LocalActivity.current)
            SideEffect {activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);WindowCompat.setDecorFitsSystemWindows(activity.window,false)}
            val state by model.state.collectAsState();FunnyWorkTheme(state.appearance){FunnyWork(state,model)}
        }
    }
    private fun createModel() {
        instrumentation.runOnMainSync { model=ViewModelProvider(owners,object:ViewModelProvider.Factory{
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=AppViewModel(context.applicationContext as Application,AccountApi(engine,automaticRouting=false,fixedOrigin=ServiceIdentity.INTERNATIONAL)) as T
        })[AppViewModel::class.java] }
        compose.waitUntil(10000){model.state.value.ready && !model.state.value.loading && model.state.value.historyFresh}
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync{owners.clear()}
        if(previous==null)secure.remove("session") else secure.write("session",previous!!)
        if(::credential.isInitialized)secure.remove(credential.key)
    }
    private fun menu(){compose.onNodeWithTag("open-sidebar").performClick()}
    private fun organize(){compose.onNodeWithTag("conversation-chat-1").performTouchInput{longClick()}}
    @Test fun longPressRenameArchiveAndRestoreKeepMessagesAndDraft() {
        menu();compose.onNodeWithText(context.getString(R.string.history_today)).assertIsDisplayed()
        organize();compose.onNodeWithText(context.getString(R.string.history_rename)).performClick()
        compose.onNodeWithTag("history-rename-title").performTextReplacement("Renamed conversation")
        compose.onNodeWithText(context.getString(R.string.done)).performClick()
        compose.waitUntil(5000){!model.state.value.historyBusy && model.state.value.chats.firstOrNull()?.title=="Renamed conversation" && !model.state.value.loading}
        organize();compose.onNodeWithText(context.getString(R.string.history_archive)).performClick()
        compose.waitUntil(5000){model.state.value.chats.isEmpty() && !model.state.value.loading && !model.state.value.historyBusy}
        compose.onNodeWithText(context.getString(R.string.archived)).performClick()
        compose.waitUntil(5000){model.state.value.chats.size==1 && !model.state.value.loading}
        organize();compose.onNodeWithTag("history-archive-menu-chat-1").performClick()
        compose.waitUntil(5000){!model.state.value.historyBusy && model.state.value.chats.isEmpty()}
        assertFalse(record.getBoolean("archived"));assertEquals(3,posts.size)
        assertEquals("Draft must remain",model.state.value.draft);assertEquals(40,model.state.value.messages.size)
        assertEquals(10L,record.getLong("revision"))
    }
    @Test fun pullToRefreshReadsAgainWithoutSendingOrLosingDraft() {
        val timeline = compose.onNodeWithTag("message-timeline")
        timeline.performScrollToIndex(0)
        val reads = directoryReads.get()
        timeline.performTouchInput { swipeDown(startY = height * .25f, endY = height * .9f, durationMillis = 600) }
        compose.waitUntil(5000) { directoryReads.get() > reads && !model.state.value.loading }
        assertEquals("Draft must remain", model.state.value.draft)
        assertTrue(posts.isEmpty())
    }
    @Test fun menuHasOneSearchEntryAndCancelKeepsActiveDraft() {
        compose.onNodeWithText(context.getString(R.string.refresh)).assertDoesNotExist()
        menu()
        val avatar = compose.onNodeWithTag("account-avatar").fetchSemanticsNode().boundsInRoot
        val nickname = compose.onNodeWithText("Gigi").fetchSemanticsNode().boundsInRoot
        assertTrue(nickname.left - avatar.right <= 24 * context.resources.displayMetrics.density)
        val menuImage = compose.onRoot().captureToImage().asAndroidBitmap()
        context.filesDir.resolve("android-ux-menu.png").outputStream().use { menuImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("open-sidebar").assertDoesNotExist()
        compose.onNodeWithTag("conversation-search").assertDoesNotExist()
        compose.onNodeWithTag("sidebar-search-toggle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("conversation-search").assertIsDisplayed().performTextReplacement("Needle")
        compose.waitUntil(5000) { model.state.value.query == "Needle" && model.state.value.historyFresh && !model.state.value.loading }
        val searchImage = compose.onRoot().captureToImage().asAndroidBitmap()
        context.filesDir.resolve("android-ux-search.png").outputStream().use { searchImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithTag("sidebar-search-cancel").performClick()
        compose.waitUntil(5000) { model.state.value.query.isEmpty() && !model.state.value.loading }
        compose.onNodeWithTag("conversation-search").assertDoesNotExist()
        compose.onNodeWithTag("sidebar-search-toggle").assertIsDisplayed()
        assertEquals("Draft must remain", model.state.value.draft)
        assertEquals("chat-1", model.state.value.chat)
        assertTrue(posts.isEmpty())
    }
    @Test fun fullTextHitOpensExactReplyAndCanReturnToSearch() {
        menu();compose.onNodeWithTag("sidebar-search-toggle").performClick();compose.onNode(hasSetTextAction() and hasText(context.getString(R.string.search))).performTextReplacement("Needle")
        compose.waitUntil(5000){model.state.value.query=="Needle" && model.state.value.historyFresh && !model.state.value.loading}
        compose.onNodeWithText("Needle reply 17").assertIsDisplayed()
        compose.onNodeWithTag("conversation-chat-1").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("message-turn-17-reply").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("message-turn-17-reply").assertIsDisplayed()
        compose.onNodeWithTag("history-return-search").performClick()
        compose.onNodeWithTag("conversation-chat-1").assertIsDisplayed()
        assertEquals("Draft must remain",model.state.value.draft);assertTrue(posts.isEmpty())
    }
    @Test fun unknownRenameSurvivesRestartAndReplaysOnlySameRequestThenReadsNewerMetadata(): Unit = runBlocking {
        lost.set(true)
        withContext(Dispatchers.Main){assertTrue(model.updateHistory(model.state.value.chats.first(),title="Lost receipt"))}
        compose.waitUntil(5000){!model.state.value.historyBusy && model.state.value.historyPending!=null}
        assertEquals(1,posts.size);val id=posts.first().text("id")
        withContext(Dispatchers.Main){owners.clear()};owners=ViewModelStore()
        synchronized(this@HistoryParityUiTest){record.put("history_revision",2).put("title","Changed on iOS")}
        createModel();assertNotNull(model.state.value.historyPending);assertEquals(1,posts.size)
        withContext(Dispatchers.Main){model.reconcileHistory()}
        compose.waitUntil(5000){!model.state.value.historyBusy && model.state.value.historyPending==null}
        assertEquals(2,posts.size);assertEquals(id,posts.last().text("id"));assertEquals(posts.first().toString(),posts.last().toString())
        assertEquals("Changed on iOS",model.state.value.chatMetadata?.text("title"));assertEquals("Draft must remain",model.state.value.draft)
    }
    @Test fun revisionConflictDoesNotOverwriteAnotherClientOrLoseDraft(): Unit = runBlocking {
        conflict.set(true)
        withContext(Dispatchers.Main){assertTrue(model.updateHistory(model.state.value.chats.first(),title="Must not apply"))}
        compose.waitUntil(5000){!model.state.value.historyBusy && model.state.value.historyError!=null}
        assertNull(model.state.value.historyPending);assertEquals("Original title",record.text("title"));assertEquals("Draft must remain",model.state.value.draft)
    }
    @Test fun allowanceFailureShowsUnknownAndUsageIsConfirmedFromSnapshot() {
        menu();compose.onNodeWithTag("account-settings").performClick()
        compose.waitUntil(5000){model.state.value.allowance!=null}
        compose.onNodeWithTag("allowance-remaining").performScrollTo().assertTextEquals(context.getString(R.string.allowance_remaining,"¥1.234567"))
        invalidBudget.set(true);compose.onNodeWithTag("allowance-refresh").performScrollTo().performClick()
        compose.waitUntil(5000){!model.state.value.allowanceBusy && model.state.value.allowanceError!=null}
        compose.onNodeWithTag("allowance-unknown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("allowance-remaining").assertDoesNotExist()
        assertEquals(20,model.state.value.phoneUsage.calls);assertEquals(200L,model.state.value.phoneUsage.input);assertEquals(100L,model.state.value.phoneUsage.output)
    }
}
