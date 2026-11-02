package com.funnyworker.android

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** Real login UI and ViewModel with isolated encrypted storage and intercepted transport. No production requests. */
class LoginContractUiTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val mode = AtomicReference("UNAUTHENTICATED")
    private val requests = CopyOnWriteArrayList<String>()
    private val owners = ViewModelStore()
    private val directory = "login_review_" + UUID.randomUUID().toString().replace("-", "")
    private lateinit var model: AppViewModel
    private lateinit var secure: SecureStore
    @Before fun start() {
        secure = SecureStore(context, directory)
        val engine = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val path = request.url.encodedPath.substringAfter("/api/account/")
            requests.add(request.method + " " + path)
            var code = 200
            val data: Any = when {
                path == "login" && mode.get() != "OK" -> {
                    code = if (mode.get() == "UNAUTHENTICATED") 401 else 403
                    JSONObject().put("code", mode.get())
                }
                path == "login" -> {
                    val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
                    assertEquals("android", JSONObject(buffer.readUtf8()).getString("client"))
                    JSONObject().put("token", "isolated-session")
                }
                path == "session" -> JSONObject().put("tenant", "isolated").put("principalId", "member")
                    .put("username", "member").put("nickname", "小明").put("avatarUrl", "")
                path == "chats/models" -> JSONObject().put("items", JSONArray().put(JSONObject()
                    .put("id", "model").put("name", "DeepSeek").put("available", true)))
                path == "chats" -> JSONObject().put("items", JSONArray()).put("next", "").put("historyManagement", true)
                path == "sync/devices" || path == "sync/sessions" -> JSONArray()
                else -> JSONObject()
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Isolated contract")
                .body(data.toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        compose.runOnIdle {
            model = ViewModelProvider(owners, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(clazz: Class<T>): T =
                    AppViewModel(context.applicationContext as Application, AccountApi(engine, automaticRouting = false), secure) as T
            })[AppViewModel::class.java]
        }
        compose.setContent { val state by model.state.collectAsState(); FunnyWorkTheme { FunnyWork(state, model) } }
        compose.waitUntil(10000) { model.state.value.ready }
        compose.onNodeWithTag("login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("login-username").performTextInput("member")
        compose.onNodeWithTag("login-password").performTextInput("isolated-password")
    }
    @After fun close() {
        compose.runOnIdle { owners.clear() }
        File(context.noBackupFilesDir, directory).deleteRecursively()
    }
    private fun reject(code: String, message: Int) {
        mode.set(code); val before = requests.size
        compose.onNodeWithTag("login-submit").performClick()
        compose.waitUntil(10000) { requests.size > before && !model.state.value.loading && model.state.value.error == message }
        compose.onNodeWithText(context.getString(message)).assertIsDisplayed()
        compose.onNodeWithTag("login-submit").assertIsEnabled()
        assertNull(model.state.value.credential); assertNull(secure.read("session"))
        assertTrue(requests.drop(before).all { it == "POST login" })
    }
    @Test fun wrongPasswordRemainsEditableWithoutCreatingSession() = reject("UNAUTHENTICATED", R.string.login_failed)
    @Test fun missingClientGrantExplainsRequiredAdminAction() = reject("DESKTOP_PERMISSION_REQUIRED", R.string.client_access_required)
    @Test fun missingBusinessBindingExplainsRequiredAdminAction() = reject("BUSINESS_SCOPE_REQUIRED", R.string.business_access_required)
    @Test fun retryAfterProvisioningLoadsHomeWithoutCreatingAnotherAccount() {
        reject("BUSINESS_SCOPE_REQUIRED", R.string.business_access_required)
        mode.set("OK")
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.waitUntil(15000) { model.state.value.credential != null && !model.state.value.loading && model.state.value.models.isNotEmpty() }
        assertEquals("member", model.state.value.credential!!.principal)
        assertEquals("member", secure.read("session")!!.getString("principal"))
        assertNull(model.state.value.error)
        compose.onNodeWithTag("login-submit").assertDoesNotExist()
        assertTrue(requests.any { it == "GET session" })
        assertTrue(requests.any { it == "GET chats/models" })
        assertTrue(requests.none { it.contains("register") })
    }
}
