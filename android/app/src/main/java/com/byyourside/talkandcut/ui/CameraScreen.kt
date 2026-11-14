package com.byyourside.talkandcut.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.byyourside.talkandcut.booth.BoothState
import com.byyourside.talkandcut.booth.BoothViewModel
import com.byyourside.talkandcut.camera.AndroidBoothSounds
import com.byyourside.talkandcut.camera.CameraController
import com.byyourside.talkandcut.frame.Layouts

private val shadowText = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 8f))

@Composable
fun CameraScreen(viewModel: BoothViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val camera = remember { CameraController(context) }
    val previewView = remember { PreviewView(context) }
    var menuOpen by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val sounds = AndroidBoothSounds()
        camera.bind(lifecycleOwner, previewView)
        viewModel.attach(camera, sounds)
        val streamObserver = Observer<PreviewView.StreamState> {
            viewModel.setCameraOn(it == PreviewView.StreamState.STREAMING)
        }
        previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
        onDispose {
            previewView.previewStreamState.removeObserver(streamObserver)
            camera.unbind()
            viewModel.detach()
            sounds.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Viewfinder(state, previewView)

        // 셔터 플래시
        val flash by animateFloatAsState(
            targetValue = if (state.shutterOn) 0.85f else 0f,
            animationSpec = tween(if (state.shutterOn) 60 else 420),
            label = "flash",
        )
        if (flash > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash)))

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(state, onMenu = { menuOpen = true })
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Countdown(state.countdown)
            }
            Text(
                text = state.reply,
                color = Color.White,
                fontSize = 16.sp,
                style = shadowText,
                modifier = Modifier.fillMaxWidth().padding(20.dp),
            )
        }
    }

    if (menuOpen) {
        BoothMenuSheet(
            state = state,
            onCommand = { cmd ->
                menuOpen = false
                viewModel.execute(cmd)
            },
            onDismiss = { menuOpen = false },
        )
    }
}

/** 미리보기를 레이아웃 칸 비율(cellAspect)로 가운데 배치. 바깥은 어둡게 둔다. */
@Composable
private fun Viewfinder(state: BoothState<*>, previewView: PreviewView) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val aspect = Layouts.cellAspect(state.layout).toFloat()
        val w = minOf(maxWidth, maxHeight * aspect)
        val h = w / aspect
        AndroidView(factory = { previewView }, modifier = Modifier.size(w, h))
    }
}

@Composable
private fun TopBar(state: BoothState<*>, onMenu: () -> Unit) {
    val idx = state.currentIndex
    val label = buildString {
        append("CUT ")
        append(if (idx < 0) BoothState.CUTS else idx + 1)
        append("/")
        append(BoothState.CUTS)
        if (state.retake != null) append(" · 다시 찍기")
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, style = shadowText)
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.shots.forEachIndexed { i, shot ->
                val current = i == idx
                val dot = if (shot != null && !current) {
                    Modifier.background(Color.White, CircleShape)
                } else {
                    Modifier.border(1.5.dp, if (current) Color(0xFFFFD54F) else Color.White, CircleShape)
                }
                Box(Modifier.size(10.dp).then(dot))
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onMenu) { MenuIcon() }
    }
}

@Composable
private fun MenuIcon() {
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.5.dp.toPx()
        for (i in 0..2) {
            val y = size.height * (0.25f + 0.25f * i)
            drawLine(Color.White, Offset(size.width * 0.12f, y), Offset(size.width * 0.88f, y), stroke, StrokeCap.Round)
        }
    }
}

@Composable
private fun Countdown(value: Int?) {
    if (value == null) return
    val scale = remember { Animatable(1f) }
    LaunchedEffect(value) {
        scale.snapTo(1.5f)
        scale.animateTo(1f, tween(300))
    }
    Text(
        text = value.toString(),
        color = Color.White,
        fontSize = 120.sp,
        fontWeight = FontWeight.Bold,
        style = shadowText,
        modifier = Modifier.scale(scale.value),
    )
}
