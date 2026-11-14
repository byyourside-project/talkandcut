package com.byyourside.talkandcut.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.byyourside.talkandcut.booth.BoothState
import com.byyourside.talkandcut.booth.Command
import com.byyourside.talkandcut.filter.EmotionInterpreter
import com.byyourside.talkandcut.filter.MOODS
import com.byyourside.talkandcut.filter.moodById
import com.byyourside.talkandcut.frame.Layouts
import com.byyourside.talkandcut.frame.ResultRenderer
import com.byyourside.talkandcut.frame.THEMES
import com.byyourside.talkandcut.frame.themeById
import kotlinx.coroutines.delay

/**
 * 합성 결과를 그린다. 컷·레이아웃·테마·보정·문구가 바뀌면 잠깐 기다렸다가 다시 렌더
 * (웹 scheduleStrip). 렌더 중에는 이전 결과를 계속 보여준다.
 */
@Composable
fun rememberComposite(renderer: ResultRenderer, state: BoothState<Bitmap>, neutral: Boolean = false): Bitmap? {
    val key = listOf(state.shots, state.layout, state.themeId, if (neutral) null else state.params, state.caption)
    val image by produceState<Bitmap?>(null, key) {
        delay(150)
        value = runCatching { renderer.render(state, neutral) }.getOrNull() ?: value
    }
    return image
}

/** 결과 보기: 크게 보기 + 프레임·보정·문구 바꾸기 + 저장. 네 컷을 다 찍으면 자동으로 열린다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ResultSheet(
    renderer: ResultRenderer,
    state: BoothState<Bitmap>,
    onCommand: (Command) -> Unit,
) {
    var compare by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = { onCommand(Command.Result(false)) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF111111), contentColor = Color.White) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${themeById(state.themeId).name} · ${Layouts.LAYOUTS.getValue(state.layout).name}",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onCommand(Command.Result(false)) }) { Text("촬영으로") }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                rememberComposite(renderer, state, neutral = compare)?.let {
                    Image(it.asImageBitmap(), contentDescription = "네컷 결과", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.reply, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("보정 전", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))
                Switch(checked = compare, onCheckedChange = { compare = it })
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FrameControls(state, onCommand, liveCaption = true)
                Section("보정")
                FilterControls(state, onCommand)
                Section("다시 찍기")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.shots.forEachIndexed { i, shot ->
                        OutlinedButton(onClick = { onCommand(Command.Retake(i)) }, enabled = shot != null && !state.busy) {
                            Text("${i + 1}번째 컷")
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCommand(Command.Start) }, modifier = Modifier.weight(1f)) { Text("처음부터") }
                Button(onClick = { onCommand(Command.Save) }, modifier = Modifier.weight(2f)) { Text("갤러리에 저장") }
            }
        }
        }
    }
}

/** 프레임 추천(상위 3개 + 이유), 견본 10종, 문구. 메뉴와 결과 보기가 같이 쓴다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FrameControls(state: BoothState<*>, onCommand: (Command) -> Unit, liveCaption: Boolean) {
    Section("프레임")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onCommand(Command.FrameRecommend) }) { Text("프레임 추천") }
        OutlinedButton(onClick = { onCommand(Command.FrameNext) }) { Text("다음 추천") }
    }
    state.recs.take(3).forEach { r ->
        val t = themeById(r.themeId)
        val selected = state.themeId == r.themeId
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, if (selected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                .clickable { onCommand(Command.Theme(r.themeId)) }
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp).background(Color(android.graphics.Color.parseColor(t.bg)), RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(t.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(r.reasons.joinToString(". "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        THEMES.forEach { t ->
            val selected = state.themeId == t.id
            Box(
                Modifier
                    .size(36.dp)
                    .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFFFFD54F) else Color.White.copy(alpha = 0.4f), CircleShape)
                    .padding(3.dp)
                    .background(Color(android.graphics.Color.parseColor(t.bg)), CircleShape)
                    .clickable { onCommand(Command.Theme(t.id)) },
            )
        }
    }
    CaptionField(state.caption, onCommand, live = liveCaption)
}

/**
 * 문구 입력. live면 입력할 때마다 바로 반영(웹 input 이벤트, 응답 없음),
 * 아니면 "넣기"를 눌러야 반영하고 응답한다(메뉴는 명령마다 닫히므로).
 */
@Composable
private fun CaptionField(caption: String, onCommand: (Command) -> Unit, live: Boolean) {
    var text by remember { mutableStateOf(caption) }
    LaunchedEffect(caption) { if (caption != text && !live) text = caption }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it.take(30)
                if (live) onCommand(Command.Caption(text, quiet = true))
            },
            label = { Text("문구") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onCommand(Command.Caption(text)) }),
            modifier = Modifier.weight(1f),
        )
        if (!live) {
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onCommand(Command.Caption(text)) }) { Text("넣기") }
        }
    }
}

/** 분위기 칩 15종 + 조금 더/덜 + 원본. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterControls(state: BoothState<*>, onCommand: (Command) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MOODS.forEach { mood ->
            FilterChip(
                selected = state.moodId == mood.id,
                onClick = { onCommand(Command.Filter(EmotionInterpreter.moodChip(mood))) },
                label = { Text(mood.label) },
            )
        }
    }
    val current = moodById(state.moodId)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { current?.let { onCommand(Command.Filter(EmotionInterpreter.nudge(it, less = false))) } },
            enabled = current != null,
        ) { Text("조금 더") }
        OutlinedButton(
            onClick = { current?.let { onCommand(Command.Filter(EmotionInterpreter.nudge(it, less = true))) } },
            enabled = current != null,
        ) { Text("조금 덜") }
        OutlinedButton(
            onClick = { onCommand(Command.ResetFilter) },
            enabled = !state.params.isNeutral,
        ) { Text("원본") }
    }
}

@Composable
fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}
