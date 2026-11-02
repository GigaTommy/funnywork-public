package com.funnyworker.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.ImageDecoder
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max

internal object AvatarImage {
    fun normalize(bytes: ByteArray): String {
        require(bytes.size in 1..20 * 1024 * 1024)
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            require(info.size.width > 0 && info.size.height > 0)
            val scale = minOf(1.0, 512.0 / max(info.size.width, info.size.height))
            decoder.setTargetSize(max(1, floor(info.size.width * scale).toInt()), max(1, floor(info.size.height * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(opaque).apply { drawColor(AndroidColor.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                val output = ByteArrayOutputStream(); require(opaque.compress(Bitmap.CompressFormat.JPEG, 85, output))
                val jpeg = output.toByteArray(); require(jpeg.size <= 256000)
                return "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
            } finally { opaque.recycle() }
        } finally { bitmap.recycle() }
    }
}

@Composable internal fun AccountPicture(name: String, picture: String, modifier: Modifier = Modifier) {
    val bitmap = remember(picture) { runCatching {
        require(picture.length <= 350000 && (picture.startsWith("data:image/jpeg;base64,") || picture.startsWith("data:image/png;base64,")))
        val bytes = Base64.decode(picture.substringAfter(','), Base64.DEFAULT)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..512 && bounds.outHeight in 1..512)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull() }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(name.firstOrNull()?.uppercase().orEmpty(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable internal fun AvatarEditor(state: AppState, model: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(model::prepareAvatar) }
    AlertDialog(onDismissRequest = model::closeAvatar, title = { Text(stringResource(R.string.avatar_title)) }, text = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AccountPicture(state.credential?.name.orEmpty(), state.avatarCandidate ?: state.avatar, Modifier.size(112.dp).testTag("avatar-preview"))
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !state.avatarBusy,
                modifier = Modifier.testTag("avatar-select")) { Text(stringResource(R.string.avatar_choose)) }
            if (state.avatarBusy) CircularProgressIndicator(Modifier.size(24.dp))
            if (state.avatarError) Text(stringResource(R.string.avatar_failed))
        }
    }, confirmButton = { TextButton(onClick = model::saveAvatar, enabled = state.avatarCandidate != null && !state.avatarBusy, modifier = Modifier.testTag("avatar-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = model::closeAvatar, enabled = !state.avatarBusy) { Text(stringResource(R.string.cancel)) } })
}

@Composable internal fun AvatarButton(state: AppState, action: () -> Unit) {
    val label = stringResource(R.string.avatar_change)
    IconButton(onClick = action, modifier = Modifier.size(48.dp).testTag("account-avatar").semantics { contentDescription = label }) {
        AccountPicture(state.credential?.name.orEmpty(), state.avatar, Modifier.size(38.dp))
    }
}
