package com.funnyworker.android

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeviceDirectoryTest {
    private fun json(id: String = "mac-one", online: Boolean = true) = JSONObject()
        .put("id", id).put("name", "Mac mini ($id)").put("online", online).put("epoch", if (online) "epoch-one" else "")
        .put("platform", "darwin").put("lastSeen", 1792800000000L)
        .put("workspaces", JSONArray().put(JSONObject().put("id", "workspace-one").put("title", "Operations")))
    private fun row(value: JSONObject) = Row(value.getString("id"), value.getString("name"), value)
    @Test fun mapsRealHostPlatformAndLastConnection() {
        val device = DeviceInfo.parse(json())
        assertEquals("Mac mini", device.name); assertEquals("macOS", device.platform)
        assertEquals(1792800000000L, device.lastSeen); assertTrue(device.canCreate)
        assertEquals("Windows", DeviceInfo.parse(json().put("platform", "win32")).platform)
    }
    @Test fun removesOnlyExactDeviceSuffix() {
        assertEquals("Mac mini-Pro", remoteHostName("Mac mini-Pro", "mac-one"))
        assertEquals("Mac mini", remoteHostName("Mac mini · mac-one", "mac-one"))
        assertEquals("mac-one", remoteHostName("mac-one", "mac-one"))
    }
    @Test fun offlineRevokedAndWorkspaceLessDevicesCannotCreate() {
        assertFalse(DeviceInfo.parse(json(online = false)).canCreate)
        assertFalse(DeviceInfo.parse(json().put("revoked", true)).canCreate)
        assertFalse(DeviceInfo.parse(json().put("workspaces", JSONArray())).canCreate)
    }
    @Test fun directoryStatusNeverUsesCachedOnlineFlagWhenNotFresh() {
        val state = AppState(devices = listOf(row(json())), devicesFresh = true)
        assertEquals(DeviceConnection.ONLINE, state.deviceConnection())
        assertEquals(DeviceConnection.UNKNOWN, state.copy(devicesFresh = false).deviceConnection())
        assertEquals(DeviceConnection.CHECKING, state.copy(devicesFresh = false, devicesLoading = true).deviceConnection())
        assertEquals(DeviceConnection.FAILED, state.copy(devicesFresh = false, devicesError = true).deviceConnection())
        assertEquals(DeviceConnection.FAILED, state.copy(devicesFresh = false, devicesError = true, devicesLoading = true).deviceConnection())
        assertEquals(DeviceConnection.ONLINE, state.copy(devicesLoading = true).deviceConnection())
        assertNull(state.copy(devicesFresh = false, devicesError = true).availableDevice("mac-one"))
        assertNotNull(state.availableDevice("mac-one"))
    }
    @Test fun noEnabledDeviceIsUnlinkedAndMixedDirectoryFindsOnlineDevice() {
        assertEquals(DeviceConnection.UNLINKED, AppState(devicesFresh = true).deviceConnection())
        val revoked = row(json().put("revoked", true))
        assertEquals(DeviceConnection.UNLINKED, AppState(devicesFresh = true, devices = listOf(revoked)).deviceConnection())
        val offline = row(json(online = false))
        val state = AppState(devicesFresh = true, devices = listOf(offline))
        assertEquals(DeviceConnection.OFFLINE, state.deviceConnection())
        assertEquals(DeviceConnection.ONLINE, state.copy(devices = listOf(offline, row(json("mac-two")))).deviceConnection())
    }
    @Test fun rejectsMalformedOnlineEpochAndWorkspaceIdentity() {
        for (value in listOf(json().put("epoch", ""), json().put("online", "true"), json().put("workspaces", JSONArray().put(JSONObject.NULL))))
            assertThrows(Exception::class.java) { DeviceInfo.parse(value) }
        val value = json(); value.getJSONArray("workspaces").put(value.getJSONArray("workspaces").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { DeviceInfo.parse(value) }
    }
    @Test fun paginationRejectsDuplicateOutOfOrderAndNonAdvancingIds() {
        assertEquals("mac-two", DeviceInfo.page(JSONArray().put(json("mac-two")), "mac-one").single().id)
        for (page in listOf(JSONArray().put(json()).put(json()), JSONArray().put(json("mac-two")).put(json("mac-one"))))
            assertThrows(IllegalArgumentException::class.java) { DeviceInfo.page(page, "") }
        assertThrows(IllegalArgumentException::class.java) { DeviceInfo.page(JSONArray().put(json()), "mac-one") }
    }
    @Test fun missingOptionalMetadataIsOmitted() {
        val value = json(); value.remove("lastSeen"); value.remove("platform")
        val device = DeviceInfo.parse(value)
        assertNull(device.lastSeen); assertEquals("", device.platform)
    }
    @Test fun taskConnectionSeparatesCheckingFailedAndConfirmedOffline() {
        val online = RemoteState(true, "epoch-one", false, null, emptyList(), false)
        val state = AppState(remoteState = online)
        assertEquals(DeviceConnection.UNKNOWN, state.taskConnection())
        assertEquals(DeviceConnection.CHECKING, state.copy(loading = true).taskConnection())
        assertEquals(DeviceConnection.FAILED, state.copy(loading = true, error = R.string.network_error).taskConnection())
        assertEquals(DeviceConnection.ONLINE, state.copy(remoteReady = true).taskConnection())
        assertEquals(DeviceConnection.OFFLINE, state.copy(remoteReady = true, remoteState = online.copy(online = false)).taskConnection())
    }
    @Test fun creationRequiresValidFreshPositionBoundedDraftAndNoPendingCommand() {
        val state = AppState(devices = listOf(row(json())), devicesFresh = true, creationDevice = "mac-one", creationWorkspace = "workspace-one")
        assertTrue(state.canCreateConversation())
        assertFalse(state.copy(devicesFresh = false).canCreateConversation())
        assertFalse(state.copy(creationWorkspace = "missing").canCreateConversation())
        assertFalse(state.copy(creationDevice = "missing").canCreateConversation())
        assertFalse(state.copy(pendingCreate = JSONObject()).canCreateConversation())
        assertTrue(state.copy(creationText = "x".repeat(16000)).canCreateConversation())
        assertFalse(state.copy(creationText = "x".repeat(16001)).canCreateConversation())
    }
}
