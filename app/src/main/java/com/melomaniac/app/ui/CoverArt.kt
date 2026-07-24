package com.melomaniac.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melomaniac.app.ui.theme.Accent
import com.melomaniac.app.ui.theme.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

private val coverHttp by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

@Composable
fun CoverArt(
    path: String?,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            if (path.isNullOrBlank()) return@withContext null
            val file = File(path)
            if (!file.exists()) return@withContext null
            runCatching {
                BitmapFactory.Options().run {
                    inSampleSize = 2
                    BitmapFactory.decodeFile(file.absolutePath, this)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    CoverBox(bitmap, size, modifier)
}

@Composable
fun RemoteCoverArt(
    url: String?,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) {
            if (url.isNullOrBlank() || !url.startsWith("http")) return@withContext null
            runCatching {
                val req = Request.Builder().url(url).get().build()
                coverHttp.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bytes = resp.body?.bytes() ?: return@use null
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }
    CoverBox(bitmap, size, modifier)
}

@Composable
private fun CoverBox(bitmap: ImageBitmap?, size: Dp, modifier: Modifier) {
    val shape = remember { RoundedCornerShape(8.dp) }
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Surface),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text("♪", color = Accent, fontSize = (size.value * 0.35f).sp)
        }
    }
}
