package com.byyourside.talkandcut.ui

import android.content.Context
import android.opengl.GLSurfaceView
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.SideEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.byyourside.talkandcut.booth.BoothState
import com.byyourside.talkandcut.booth.BoothViewModel
import com.byyourside.talkandcut.camera.AndroidBoothSounds
import com.byyourside.talkandcut.camera.CameraController
import com.byyourside.talkandcut.camera.FilterRenderer
import com.byyourside.talkandcut.frame.Layouts

private val shadowText = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 8f))

@Composable
fun CameraScreen(viewModel: BoothViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val camera = remember { CameraController(context) }
    val preview = remember { GlPreview(context, camera) { viewModel.setCameraOn(true) } }
    var menuOpen by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val sounds = AndroidBoothSounds()
        camera.bind(lifecycleOwner)
        viewModel.attach(camera, sounds)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> preview.view.onResume()
                Lifecycle.Event.ON_PAUSE -> preview.view.onPause()
                Lifecycle.Event.ON_STOP -> {
                    viewModel.setCameraOn(false)
                    preview.renderer.resetStreaming()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            camera.unbind()
            viewModel.detach()
            sounds.release()
        }
    }

    // 보정값은 미리보기에만 적용한다 (컷은 원본 보관)
    SideEffect { preview.renderer.params = state.params }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Viewfinder(state, preview.view)

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
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                state.currentPose?.let { pose ->
                    Text(pose.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, style = shadowText)
                    Text(pose.tip, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, style = shadowText)
                    Spacer(Modifier.height(8.dp))
                }
                Text(text = state.reply, color = Color.White, fontSize = 16.sp, style = shadowText)
            }
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

/** 보정 셰이더가 적용된 카메라 미리보기 뷰. */
private class GlPreview(context: Context, camera: CameraController, onFirstFrame: () -> Unit) {
    val view = GLSurfaceView(context)
    val renderer: FilterRenderer

    init {
        view.setEGLContextClientVersion(3)
        view.preserveEGLContextOnPause = true
        lateinit var r: FilterRenderer
        r = FilterRenderer(view, onSurfaceTexture = { st -> camera.attach(r, st) }, onFirstFrame = onFirstFrame)
        renderer = r
        view.setRenderer(r)
        view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }
}

/** 미리보기를 레이아웃 칸 비율(cellAspect)로 가운데 배치. 바깥은 어둡게 둔다. */
@Composable
private fun Viewfinder(state: BoothState<*>, previewView: GLSurfaceView) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val aspect = Layouts.cellAspect(state.layout).toFloat()
        val w = minOf(maxWidth, maxHeight * aspect)
        val h = w / aspect
        Box(Modifier.size(w, h)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            val pose = state.currentPose
            if (pose != null && state.cameraOn && !state.shutterOn) {
                PoseGuideOverlay(state.headcount, pose, aspect.toDouble())
            }
        }
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
