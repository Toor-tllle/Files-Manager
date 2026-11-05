package com.rui.filesmanager

import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

private fun fmtTime(ms: Long): String {
    if (ms < 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

/**
 * アプリ内動画・音声プレーヤー（ExoPlayer、独自コントロール）。
 * ローカル Uri でも NAS の http ストリーミング URL でも同じ画面で再生する。
 */
@Composable
fun PlayerScreen(source: String, title: String) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(source))
            prepare()
            playWhenReady = true
        }
    }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var seekPos by remember { mutableStateOf(0f) }

    DisposableEffect(player) {
        onDispose { player.release() }
    }
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            if (!seeking) {
                position = player.currentPosition.coerceAtLeast(0)
                duration = player.duration.coerceAtLeast(0)
            }
            delay(400)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).also { sv -> player.setVideoSurfaceView(sv) }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(if (seeking) seekPos.toLong() else position), color = Color.White)
                Slider(
                    value = if (seeking) seekPos else if (duration > 0) position.toFloat() else 0f,
                    onValueChange = { seeking = true; seekPos = it },
                    onValueChangeFinished = {
                        player.seekTo(seekPos.toLong())
                        seeking = false
                    },
                    valueRange = 0f..maxOf(duration.toFloat(), 1f),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text(fmtTime(duration), color = Color.White)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { player.seekTo((player.currentPosition - 10000).coerceAtLeast(0)) }) {
                    Text("-10", color = Color.White)
                }
                IconButton(onClick = {
                    if (player.isPlaying) player.pause() else player.play()
                    playing = player.isPlaying
                }) {
                    Icon(
                        if (playing) AppIcons.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "一時停止" else "再生",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { player.seekTo(player.currentPosition + 30000) }) {
                    Text("+30", color = Color.White)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
