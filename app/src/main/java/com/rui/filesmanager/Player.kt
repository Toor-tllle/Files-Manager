package com.rui.filesmanager

import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
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
 * 画面サイズ調整：全体表示（レターボックス）／画面いっぱい（クロップ）の切替と、
 * ピンチでのズーム（1〜4倍）＋ドラッグでの移動、ダブルタップでリセット。
 */
@Composable
fun PlayerScreen(
    source: String,
    title: String,
    hasPrev: Boolean = false,
    hasNext: Boolean = false,
    queueLabel: String = "",
    onBack: () -> Unit = {},
    onPrev: () -> Unit = {},
    onNext: () -> Unit = {},
) {
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
    var videoAspect by remember { mutableStateOf(16f / 9f) }
    var videoDispW by remember { mutableStateOf(0f) } // 表示画素幅（ピクセルアスペクト補正後）
    var videoDispH by remember { mutableStateOf(0f) }
    var fillMode by remember { mutableStateOf(false) } // false=そのまま, true=画面いっぱい
    var zoom by remember { mutableStateOf(1f) }
    var swipeSeeking by remember { mutableStateOf(false) }
    var swipeDeltaMs by remember { mutableStateOf(0L) }
    var swipeStartPos by remember { mutableStateOf(0L) }
    // ファイル名と再生バーは、操作がなければ自動で隠れる（タップで出し入れ）
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableStateOf(0) }
    var lastTapMs by remember { mutableStateOf(0L) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    videoDispW = videoSize.width * videoSize.pixelWidthHeightRatio
                    videoDispH = videoSize.height.toFloat()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(controlsVisible, interactionTick, playing, seeking) {
        if (controlsVisible && playing && !seeking) {
            delay(3500)
            controlsVisible = false
        }
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
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val contW = maxWidth
            val contH = maxHeight
            val contAspect = contW / contH
            val baseW: androidx.compose.ui.unit.Dp
            val baseH: androidx.compose.ui.unit.Dp
            if (fillMode) {
                // 画面いっぱい：短い辺に合わせてはみ出させる（クロップ）
                if (contAspect > videoAspect) { baseW = contW; baseH = contW / videoAspect }
                else { baseH = contH; baseW = contH * videoAspect }
            } else {
                // そのまま表示：全体が収まる大きさにしつつ、動画本来の大きさよりは拡大しない
                var w: androidx.compose.ui.unit.Dp
                var h: androidx.compose.ui.unit.Dp
                if (contAspect > videoAspect) { h = contH; w = contH * videoAspect }
                else { w = contW; h = contW / videoAspect }
                if (videoDispW > 0f && videoDispH > 0f) {
                    val density = LocalDensity.current.density
                    val nativeW = videoDispW / density
                    val k = minOf(nativeW / w.value, 1f)
                    w = (w.value * k).dp
                    h = (h.value * k).dp
                }
                baseW = w; baseH = h
            }
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).also { sv -> player.setVideoSurfaceView(sv) }
                },
                modifier = Modifier.size(baseW, baseH).graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = panX
                    translationY = panY
                },
            )
            // ジェスチャー一括受付：タップ=バー表示切替／横スワイプ=シーク／縦スワイプ=前後／ピンチ=ズーム
            // （検出器を重ねると競合してタップが死ぬため、1つの受付で全部を判定する）
            Box(
                modifier = Modifier.fillMaxSize()
                    .pointerInput(baseW, baseH) {
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            interactionTick++
                            val startX = first.position.x
                            val startY = first.position.y
                            val startPosMs = player.currentPosition
                            val startZoom = zoom
                            val startPanX = panX
                            val startPanY = panY
                            var mode = 0 // 0=未定 1=シーク 2=縦 3=パン 4=ピンチ 5=終了待ち
                            var pinchD0 = 0f
                            var pinchC0 = Offset.Zero
                            var secondId: PointerId? = null
                            var totalDx = 0f
                            var totalDy = 0f
                            var allUp = false
                            while (!allUp) {
                                val event = awaitPointerEvent()
                                val changes = event.changes
                                val pressed = changes.filter { it.pressed }
                                val firstChange = changes.firstOrNull { it.id == first.id }
                                if (mode != 4 && mode != 5 && pressed.size >= 2 && firstChange != null) {
                                    val other = pressed.first { it.id != first.id }
                                    secondId = other.id
                                    pinchD0 = (other.position - firstChange.position).getDistance()
                                    pinchC0 = (other.position + firstChange.position) / 2f
                                    mode = 4
                                    swipeSeeking = false
                                }
                                when (mode) {
                                    0, 1, 2, 3 -> {
                                        if (firstChange != null) {
                                            totalDx = firstChange.position.x - startX
                                            totalDy = firstChange.position.y - startY
                                            if (mode == 0 && (abs(totalDx) > viewConfiguration.touchSlop || abs(totalDy) > viewConfiguration.touchSlop)) {
                                                mode = when {
                                                    startZoom > 1f -> 3
                                                    abs(totalDx) >= abs(totalDy) -> 1
                                                    hasPrev || hasNext -> 2
                                                    else -> 3
                                                }
                                                if (mode == 1) {
                                                    swipeStartPos = startPosMs
                                                    swipeSeeking = true
                                                }
                                            }
                                            when (mode) {
                                                1 -> {
                                                    // 画面幅いっぱいのスワイプで動画の30%（30秒〜5分の範囲）。指の総移動量に追従
                                                    val window = (duration * 0.3).toLong().coerceIn(30_000L, 300_000L)
                                                    val w = size.width.toFloat().coerceAtLeast(1f)
                                                    swipeDeltaMs = (totalDx / w * window).toLong()
                                                }
                                                3 -> {
                                                    if (startZoom > 1f) {
                                                        val maxPanX = (baseW.toPx() * startZoom - size.width) / 2f
                                                        val maxPanY = (baseH.toPx() * startZoom - size.height) / 2f
                                                        panX = (startPanX + totalDx).coerceIn(-maxOf(maxPanX, 0f), maxOf(maxPanX, 0f))
                                                        panY = (startPanY + totalDy).coerceIn(-maxOf(maxPanY, 0f), maxOf(maxPanY, 0f))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    4 -> {
                                        val a = firstChange
                                        val b = secondId?.let { sid -> changes.firstOrNull { it.id == sid } }
                                        if (a != null && b != null && pinchD0 > 0f) {
                                            val d = (b.position - a.position).getDistance()
                                            val newZoom = (startZoom * d / pinchD0).coerceIn(1f, 4f)
                                            zoom = newZoom
                                            if (newZoom <= 1f) {
                                                panX = 0f; panY = 0f
                                            } else {
                                                val c = (a.position + b.position) / 2f
                                                val maxPanX = (baseW.toPx() * newZoom - size.width) / 2f
                                                val maxPanY = (baseH.toPx() * newZoom - size.height) / 2f
                                                panX = (startPanX + (c.x - pinchC0.x)).coerceIn(-maxOf(maxPanX, 0f), maxOf(maxPanX, 0f))
                                                panY = (startPanY + (c.y - pinchC0.y)).coerceIn(-maxOf(maxPanY, 0f), maxOf(maxPanY, 0f))
                                            }
                                        }
                                        if (pressed.size < 2) mode = 5
                                    }
                                }
                                if (pressed.isEmpty()) {
                                    allUp = true
                                    when (mode) {
                                        0 -> {
                                            val now = System.currentTimeMillis()
                                            if (now - lastTapMs < 320) {
                                                // ダブルタップ：ズームリセット
                                                zoom = 1f; panX = 0f; panY = 0f
                                                lastTapMs = 0L
                                            } else {
                                                controlsVisible = !controlsVisible
                                                lastTapMs = now
                                            }
                                        }
                                        1 -> {
                                            val target = (swipeStartPos + swipeDeltaMs).coerceIn(0L, maxOf(duration, 0L))
                                            player.seekTo(target)
                                            swipeSeeking = false
                                        }
                                        2 -> {
                                            if (totalDy < -90f) onNext()
                                            else if (totalDy > 90f) onPrev()
                                        }
                                    }
                                }
                            }
                        }
                    },
            )
            if (swipeSeeking) {
                val target = (swipeStartPos + swipeDeltaMs).coerceIn(0L, maxOf(duration, 0L))
                val deltaSec = (target - swipeStartPos) / 1000
                Text(
                    text = (if (deltaSec >= 0) "+${deltaSec}秒" else "${deltaSec}秒") +
                        "　" + fmtTime(target) + " / " + fmtTime(duration),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                        .background(Color(0x99000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
            FadeVisible(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.background(Color(0x66000000)),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る", tint = Color.White)
                    }
                    Text(title, color = Color.White, maxLines = 1)
                    if (queueLabel.isNotEmpty()) {
                        Text(queueLabel, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, end = 12.dp))
                    } else {
                        Spacer(Modifier.width(12.dp))
                    }
                }
            }
            if (zoom > 1f) {
                Text(
                    text = "${(zoom * 100).toInt()}%",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                        .background(Color(0x66000000)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut()) {
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
                if (hasPrev || hasNext) {
                    TextButton(onClick = onPrev, enabled = hasPrev) {
                        Text("前へ", color = if (hasPrev) Color.White else Color.Gray)
                    }
                }
                TextButton(onClick = {
                    fillMode = !fillMode
                    zoom = 1f; panX = 0f; panY = 0f
                }) {
                    Text(if (fillMode) "そのまま表示" else "画面いっぱい", color = Color.White)
                }
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
                if (hasPrev || hasNext) {
                    TextButton(onClick = onNext, enabled = hasNext) {
                        Text("次へ", color = if (hasNext) Color.White else Color.Gray)
                    }
                }
                if (zoom > 1f) {
                    TextButton(onClick = { zoom = 1f; panX = 0f; panY = 0f }) {
                        Text("100%に戻す", color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            }
        }
    }
}
