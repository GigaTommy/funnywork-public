package com.funnyworker.android

import org.json.JSONArray
import org.json.JSONObject

enum class DeviceConnection { CHECKING, ONLINE, OFFLINE, UNLINKED, UNKNOWN, REVOKED, FAILED }

/** Device identity comes from the account directory, never inferred from task history. */
data class DeviceInfo(val id: String, val name: String, val online: Boolean, val revoked: Boolean,
    val platform: String, val lastSeen: Long?, val epoch: String, val workspaces: List<Row>) {
    val canCreate: Boolean get() = online && !revoked && ServiceIdentity.validId(epoch) && workspaces.isNotEmpty()
    companion object {
        fun parse(value: JSONObject): DeviceInfo {
            val id = value.getString("id")
            require(ServiceIdentity.validId(id) && value.opt("name") is String && value.opt("online") is Boolean)
            val epoch = value.getString("epoch")
            require(!value.getBoolean("online") || ServiceIdentity.validId(epoch))
            val spaces = value.getJSONArray("workspaces")
            require(spaces.length() <= 1000)
            val workspaces = spaces.objects().map { row ->
                val key = row.getString("id"); require(ServiceIdentity.validId(key))
                Row(key, row.getString("title"), row)
            }
            require(workspaces.size == spaces.length() && workspaces.map { it.id }.distinct().size == workspaces.size)
            val revoked = value.optBoolean("revoked")
            val platform = when (val raw = value.text("platform")) { "darwin" -> "macOS"; "win32" -> "Windows"; else -> raw }
            return DeviceInfo(id, remoteHostName(value.getString("name"), id), value.getBoolean("online") && !revoked,
                revoked, platform, value.sequence("lastSeen")?.takeIf { it > 0 }, epoch, workspaces)
        }
        fun page(array: JSONArray, after: String): List<Row> {
            require(array.length() <= 100)
            var cursor = after
            return array.objects().map { json ->
                val device = parse(json)
                require(device.id > cursor); cursor = device.id
                Row(device.id, device.name, json)
            }.also { require(it.size == array.length()) }
        }
    }
}

internal fun remoteHostName(name: String, id: String): String {
    if (id.isBlank()) return name
    for (suffix in listOf(" ($id)", " · $id", " - $id", "_$id", "-$id")) {
        if (name.endsWith(suffix)) name.removeSuffix(suffix).trim().takeIf { it.isNotEmpty() }?.let { return it }
    }
    return name
}

internal fun AppState.deviceConnection(): DeviceConnection = when {
    !devicesFresh -> if (devicesError) DeviceConnection.FAILED else if (devicesLoading) DeviceConnection.CHECKING else DeviceConnection.UNKNOWN
    devices.none { !it.json.optBoolean("revoked") } -> DeviceConnection.UNLINKED
    devices.any { it.json.optBoolean("online") && !it.json.optBoolean("revoked") } -> DeviceConnection.ONLINE
    else -> DeviceConnection.OFFLINE
}

internal fun AppState.availableDevice(id: String): DeviceInfo? = if (!devicesFresh) null else
    devices.firstOrNull { it.id == id }?.let { DeviceInfo.parse(it.json) }?.takeIf { it.canCreate }

internal fun AppState.canCreateConversation(): Boolean = pendingCreate == null && createdTask == null && creationText.length <= 16000 &&
    availableDevice(creationDevice)?.workspaces?.any { it.id == creationWorkspace } == true

internal fun AppState.taskConnection(): DeviceConnection = when {
    !remoteReady -> if (error != null) DeviceConnection.FAILED else if (loading || commandBusy) DeviceConnection.CHECKING else DeviceConnection.UNKNOWN
    remoteState?.online == true -> DeviceConnection.ONLINE
    else -> DeviceConnection.OFFLINE
}
