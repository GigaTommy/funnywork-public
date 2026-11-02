package com.funnyworker.android

import android.app.Application
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Controlled transport regression; separate from the real Mac acceptance. */
class AccountRefreshRecoveryTest {
    @Test fun reloginDuringColdRefreshRestoresNavigationAndDraftWithoutStuckLoading(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secure = SecureStore(context)
        val previousSession = secure.read("session")
        val credential = Credential(ServiceIdentity.MAINLAND, "old-test-token", "test-tenant", UUID.randomUUID().toString(), "Test")
        val task = UUID.randomUUID().toString()
        val previousAccount = secure.read(credential.key)
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val engine = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val path = request.url.encodedPath.removePrefix("/api/account/")
            if (path == "session" && request.header("Authorization") == "Bearer old-test-token") {
                started.countDown(); check(release.await(20, TimeUnit.SECONDS))
            }
            val body = when (path) {
                "login" -> "{\"token\":\"new-test-token\"}"
                "session" -> JSONObject().put("tenant", credential.tenant).put("principalId", credential.principal).toString()
                "sync/sessions" -> "[]"
                "sync/sessions/$task/state" -> "{\"online\":false,\"epoch\":\"\",\"state\":{}}"
                "sync/sessions/$task/commands" -> "[]"
                "sync/sessions/$task/events" -> "{\"events\":[],\"nextSeq\":0,\"durableNextSeq\":0}"
                "chats" -> "{\"items\":[]}"
                "chats/models" -> "{\"items\":[]}"
                else -> "{}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Controlled test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val models = ViewModelStore()
        try {
            secure.write(credential.key, JSONObject().put("desktop", true).put("task", task)
                .put("drafts", JSONObject().put("desktop:$task", "Keep the newer draft"))
                .put("creationText", "Keep creation draft"))
            secure.write("session", credential.json())
            val model = withContext(Dispatchers.Main) { ViewModelProvider(models, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(context.applicationContext as Application,
                    AccountApi(engine, automaticRouting = false, fixedOrigin = ServiceIdentity.MAINLAND)) as T
            })[AppViewModel::class.java] }
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            withContext(Dispatchers.Main) { model.login(ServiceIdentity.MAINLAND, "test-user", "test-password") }
            withTimeout(8000) { while (model.state.value.credential?.token != "new-test-token" || model.state.value.loading) delay(100) }
            assertTrue(model.state.value.desktop); assertEquals(task, model.state.value.task)
            assertEquals("Keep the newer draft", model.state.value.draft)
            assertEquals("Keep creation draft", model.state.value.creationText)
            release.countDown(); delay(300)
            assertFalse(model.state.value.loading); assertEquals("new-test-token", model.state.value.credential?.token)
        } finally {
            release.countDown()
            withContext(Dispatchers.Main) { models.clear() }
            if (previousSession == null) secure.remove("session") else secure.write("session", previousSession)
            if (previousAccount == null) secure.remove(credential.key) else secure.write(credential.key, previousAccount)
        }
    }
}
