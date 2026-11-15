package com.byyourside.talkandcut.ui

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.foundation.layout.widthIn
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
import com.byyourside.talkandcut.booth.Command
import com.byyourside.talkandcut.camera.AndroidBoothSounds
import com.byyourside.talkandcut.camera.CameraController
import com.byyourside.talkandcut.camera.FilterRenderer
import com.byyourside.talkandcut.frame.Layouts

internal val shadowText = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 2f), 8f))

@Composable
fun CameraScreen(viewModel: BoothViewModel) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val wide = configuration.screenWidthDp >= 840 && configuration.screenWidthDp > configuration.screenHeightDp
    val state by viewModel.state.collectAsStateWithLifecycle()
    val modelBusy by viewModel.team4.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val devices by viewModel.devices.state.collectAsStateWithLifecycle()
    val camera = remember(devices.camera, devices.cameras) { CameraController(context, viewModel.devices, devices.camera) }
    val preview = remember(camera) { GlPreview(context, camera) { viewModel.setCameraOn(true) } }
    var menuOpen by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner, camera) {
        cameraError = null
        viewModel.setCameraOn(false)
        val sounds = AndroidBoothSounds(viewModel.devices)
        camera.bind(lifecycleOwner) { message ->
            cameraError = message
            viewModel.setCameraOn(false)
        }
        viewModel.attach(camera, sounds)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.voice.start()
                Lifecycle.Event.ON_RESUME -> preview.view.onResume()
                Lifecycle.Event.ON_PAUSE -> preview.view.onPause()
                Lifecycle.Event.ON_STOP -> {
                    viewModel.voice.stop()
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
        androidx.compose.runtime.key(camera, wide) { Viewfinder(state, preview.view, wide) }

        // 셔터 플래시
        val flash by animateFloatAsState(
            targetValue = if (state.shutterOn) 0.85f else 0f,
            animationSpec = tween(if (state.shutterOn) 60 else 420),
            label = "flash",
        )
        if (flash > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash)))

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(state, onMenu = { menuOpen = true })
            VoiceBar(viewModel.voice, modifier = if (wide) Modifier.widthIn(max = 480.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp)) else Modifier)
            if (modelBusy) Text("AI가 명령을 판단하고 있어요…", color = Color(0xFFFFD54F),
                modifier = Modifier.padding(horizontal = 20.dp))
            cameraError?.let { message ->
                Text(
                    text = message,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().background(Color(0xFF7F1D1D)).padding(16.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Countdown(state.countdown)
            }
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.Bottom) {
                Thumbnail(viewModel, state) { viewModel.execute(Command.Result(true)) }
                Column(Modifier.weight(1f)) {
                    state.currentPose?.let { pose ->
                        Text(pose.name, color = Color.White, fontSize = if (wide) 24.sp else 18.sp, fontWeight = FontWeight.Bold, style = shadowText)
                        Text(pose.tip, color = Color.White.copy(alpha = 0.85f), fontSize = if (wide) 17.sp else 14.sp, style = shadowText)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(text = state.reply, color = Color.White, fontSize = if (wide) 18.sp else 16.sp, style = shadowText)
                }
            }
        }
    }

    if (menuOpen) {
        BoothMenuSheet(
            state = state,
            voice = viewModel.voice,
            devices = viewModel.devices,
            model = viewModel.team4,
            onCommand = { cmd ->
                // 견본 고르기는 여러 번 눌러 보므로 메뉴를 닫지 않는다
                if (cmd !is Command.Theme) menuOpen = false
                viewModel.execute(cmd)
            },
            onDismiss = { menuOpen = false },
        )
    }

    if (state.resultOpen) {
        ResultSheet(viewModel.result, state, onCommand = { viewModel.execute(it) })
    }
}

/** 왼쪽 아래 합성 미리보기. 누르면 결과 보기. 찍은 컷이 없으면 숨긴다. */
@Composable
private fun Thumbnail(viewModel: BoothViewModel, state: BoothState<Bitmap>, onClick: () -> Unit) {
    if (state.shots.all { it == null }) return
    val image = rememberComposite(viewModel.result, state) ?: return
    Image(
        bitmap = image.asImageBitmap(),
        contentDescription = "결과 보기",
        modifier = Modifier
            .padding(end = 14.dp)
            .height(84.dp)
            .aspectRatio(image.width.toFloat() / image.height)
            .clip(RoundedCornerShape(4.dp))
            .border(1.5.dp, Color.White, RoundedCornerShape(4.dp))
            .clickable(enabled = !state.busy, onClick = onClick),
    )
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
private fun Viewfinder(state: BoothState<*>, previewView: GLSurfaceView, wide: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val aspect = Layouts.cellAspect(state.layout).toFloat()
        val w = minOf(maxWidth, maxHeight * aspect)
        val h = w / aspect
        if (wide) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(Modifier.size(w, h).then(if (wide) Modifier.border(1.dp, Color.White.copy(alpha = 0.3f)) else Modifier)) {
            if (!wide) AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
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
