package com.streamly.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.streamly.App

val Context.streamly: App get() = applicationContext as App

val StreamlyColors = darkColorScheme(
    primary = Color(0xFF8B7BFF), onPrimary = Color(0xFF0F1014),
    background = Color(0xFF0F1014), surface = Color(0xFF1A1C23),
    onBackground = Color(0xFFF2F3F7), onSurface = Color(0xFFF2F3F7),
    onSurfaceVariant = Color(0xFFA3A7B5), surfaceVariant = Color(0xFF2B2F45),
)

@Composable
fun SectionTitle(text: String) =
    Text(text, Modifier.padding(20.dp, 20.dp, 20.dp, 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

/** Image with source-supplied headers (some sources require a Referer). */
@Composable
fun Img(url: String?, desc: String, modifier: Modifier, headers: Map<String, String>? = null) {
    if (url == null) { Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)); return }
    val ctx = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(ctx).data(url).apply { headers?.forEach { (k, v) -> addHeader(k, v) } }.crossfade(true).build(),
        contentDescription = desc, contentScale = ContentScale.Crop, modifier = modifier,
    )
}

/** Poster tile. If the source gave no poster, it is looked up on TMDB (cached) - the same idea Zangetsu uses. */
@Composable
fun PosterCard(
    title: String, poster: String?, headers: Map<String, String>?, modifier: Modifier = Modifier,
    progress: Float? = null, onClick: () -> Unit,
) {
    val app = LocalContext.current.streamly
    val fallback by produceState<String?>(null, title, poster) { if (poster.isNullOrBlank()) value = app.meta.find(title)?.poster }
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
            val url = poster?.takeIf { it.isNotBlank() } ?: fallback
            Img(url, title, Modifier.fillMaxSize(), if (poster.isNullOrBlank()) null else headers)
            progress?.let {
                LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
            }
        }
        Text(title, Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium)
    }
}
