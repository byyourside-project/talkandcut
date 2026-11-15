package com.byyourside.talkandcut

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.byyourside.talkandcut.booth.BoothViewModel
import com.byyourside.talkandcut.ui.CameraScreen

class MainActivity : ComponentActivity() {
    private val viewModel: BoothViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metrics = resources.displayMetrics
        val longestDp = maxOf(metrics.widthPixels, metrics.heightPixels) / metrics.density
        val shortestDp = minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density
        val monitor = resources.configuration.smallestScreenWidthDp >= 600 || (longestDp >= 900 && shortestDp >= 480)
        requestedOrientation = if (monitor) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(if (monitor) WindowInsetsCompat.Type.systemBars() else WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CameraPermissionGate(onMicGranted = { viewModel.voice.start() }) { CameraScreen(viewModel) }
            }
        }
        handleDebugUtterance(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugUtterance(intent)
    }

    /**
     * 디버그 빌드 전용 리허설·테스트 입구. 메뉴 "문장 직접 입력"과 같은 해석 경로를 탄다.
     * adb shell am start -n com.byyourside.talkandcut/.MainActivity --es utterance "찍어줘"
     */
    private fun handleDebugUtterance(intent: Intent?) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (intent?.getBooleanExtra("audio_probe", false) == true) viewModel.offlineSpeech.beginAudioProbe()
        intent?.getStringExtra("benchmark_wave")?.let(viewModel.offlineSpeech::benchmarkWave)
        intent?.getStringExtra("utterance")?.let(viewModel.voice::typed)
        intent?.getStringExtra("verify_wave")?.let { path ->
            viewModel.offlineSpeech.verifyWave(path, if (intent.getBooleanExtra("verify_command", false)) viewModel.voice::typed else null,
                intent.getBooleanExtra("verify_pipeline", false))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun CameraPermissionGate(onMicGranted: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    // 마이크(음성 명령)와 Android 9 이하 저장소 권한(갤러리 저장)도 카메라와 같이 받는다. 카메라만 필수.
    val wanted = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        granted = granted || result[Manifest.permission.CAMERA] == true
        // 화면이 먼저 떠서 마이크가 권한 없음(DENIED)이 됐을 수 있으니 허용되면 다시 연결한다
        if (result[Manifest.permission.RECORD_AUDIO] == true) onMicGranted()
    }
    LaunchedEffect(Unit) {
        if (wanted.isNotEmpty()) launcher.launch(wanted)
    }
    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("카메라 권한이 필요해요", color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { launcher.launch(arrayOf(Manifest.permission.CAMERA)) }) { Text("카메라 권한 허용") }
        }
    }
}
