package com.byyourside.talkandcut.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.byyourside.talkandcut.voice.VoiceSession
import com.byyourside.talkandcut.voice.VoiceState
import com.byyourside.talkandcut.voice.VoiceTexts

/** 상단 바 아래 음성 상태 줄(웹 voicebar): 상태 점 + 라벨 + 안내, 오류면 원인. */
@Composable
fun VoiceBar(voice: VoiceSession, modifier: Modifier = Modifier) {
    val s by voice.input.state.collectAsStateWithLifecycle()
    val reason by voice.input.reason.collectAsStateWithLifecycle()
    val dotColor = when (s) {
        VoiceState.LISTENING -> Color(0xFF4CAF50)
        VoiceState.SPEAKING -> Color(0xFF64B5F6)
        VoiceState.CONNECTING -> Color(0xFFFFD54F)
        VoiceState.OFF -> Color.Gray
        else -> Color(0xFFEF5350)
    }
    val pulse by rememberInfiniteTransition(label = "mic").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    Column(modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(if (s == VoiceState.LISTENING) pulse else 1f)
                    .background(dotColor, CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(VoiceTexts.label(s), color = Color.White, fontSize = 13.sp, style = shadowText)
            Spacer(Modifier.width(10.dp))
            Text(VoiceTexts.prompt(s, reason), color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, style = shadowText)
        }
        val status = VoiceTexts.status(s, reason)
        if (status.isNotEmpty() && s != VoiceState.OFF) {
            Text(status, color = Color(0xFFFFCDD2), fontSize = 13.sp, style = shadowText)
        }
    }
}

/** 메뉴 음성 그룹: 마이크 재연결/끄기, 안내 켜기·끄기, 문장 직접 입력, 마지막 명령 JSON. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceMenuGroup(voice: VoiceSession, onClose: () -> Unit) {
    val s by voice.input.state.collectAsStateWithLifecycle()
    val tts by voice.ttsEnabled.collectAsStateWithLifecycle()
    val lastJson by voice.lastJson.collectAsStateWithLifecycle()
    var typed by remember { mutableStateOf("") }
    var showJson by remember { mutableStateOf(false) }

    fun send() {
        val t = typed.trim()
        if (t.isEmpty()) return
        typed = ""
        onClose()
        voice.typed(t)
    }

    Section("음성")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
        FilledTonalButton(
            onClick = { onClose(); voice.toggleMic() },
            enabled = s != VoiceState.UNSUPPORTED,
        ) { Text(if (s == VoiceState.LISTENING || s == VoiceState.SPEAKING) "마이크 끄기" else "마이크 다시 연결") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("음성 안내", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(8.dp))
            Switch(checked = tts, onCheckedChange = voice::setTtsEnabled)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("문장 직접 입력") },
            placeholder = { Text("예: 몽환적인 느낌으로 3초 뒤에 찍어줘") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = ::send) { Text("보내기") }
    }
    if (lastJson.isNotEmpty()) {
        TextButton(onClick = { showJson = !showJson }) { Text(if (showJson) "마지막 명령 JSON 숨기기" else "마지막 명령 JSON 보기") }
        if (showJson) {
            Text(
                lastJson,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(10.dp),
            )
        }
    }
}
