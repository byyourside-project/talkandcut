package com.byyourside.talkandcut.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.byyourside.talkandcut.booth.BoothState
import com.byyourside.talkandcut.booth.Command
import com.byyourside.talkandcut.frame.LayoutId
import com.byyourside.talkandcut.frame.Layouts
import com.byyourside.talkandcut.voice.VoiceSession

/** 오른쪽 위 메뉴. 음성 명령과 같은 Command를 만든다. 음성·촬영·포즈·보정·프레임·저장. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BoothMenuSheet(
    state: BoothState<*>,
    voice: VoiceSession,
    devices: com.byyourside.talkandcut.devices.DeviceSettings,
    model: com.byyourside.talkandcut.command.Team4Interpreter,
    onCommand: (Command) -> Unit,
    onDismiss: () -> Unit,
) {
    ResponsiveMenuSheet(onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val canShoot = state.cameraOn && !state.busy && state.currentIndex >= 0

            DeviceMenuGroup(devices, enabled = !state.busy && !state.counting,
                onMicrophoneChanged = { voice.stop(); voice.start() },
                onSpeakerChanged = { voice.stop(); voice.start() })

            Team4MenuGroup(model)

            VoiceMenuGroup(voice, onClose = onDismiss)

            Section("촬영")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { onCommand(Command.Capture(3)) }, enabled = canShoot) { Text("3초 뒤 촬영") }
                FilledTonalButton(onClick = { onCommand(Command.Capture(0)) }, enabled = canShoot) { Text("바로 촬영") }
                OutlinedButton(
                    onClick = { onCommand(Command.Cancel) },
                    enabled = state.counting || state.retake != null,
                ) { Text("취소") }
                OutlinedButton(onClick = { onCommand(Command.Start) }, enabled = !state.shutterOn) { Text("처음부터") }
            }
            if (state.shots.any { it != null }) {
                Text("다시 찍기", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.shots.forEachIndexed { i, shot ->
                        OutlinedButton(
                            onClick = { onCommand(Command.Retake(i)) },
                            enabled = shot != null && !state.busy,
                        ) { Text("${i + 1}번째 컷") }
                    }
                }
            }

            Section("포즈")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..4).forEach { n ->
                    FilterChip(
                        selected = state.headcount == n,
                        onClick = { onCommand(Command.Headcount(n)) },
                        label = { Text("${n}명") },
                    )
                }
                OutlinedButton(
                    onClick = { onCommand(Command.PoseNext) },
                    enabled = state.currentIndex >= 0,
                ) { Text("다른 포즈") }
            }

            Section("보정")
            FilterControls(state, onCommand)

            Section("네컷 모양")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(LayoutId.GRID, LayoutId.STRIP).forEach { id ->
                    FilterChip(
                        selected = state.layout == id,
                        onClick = { onCommand(Command.Layout(id)) },
                        enabled = state.shotCount == 0 && !state.busy,
                        label = { Text(Layouts.LAYOUTS.getValue(id).name) },
                    )
                }
            }

            FrameControls(state, onCommand, liveCaption = false)

            Section("저장")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { onCommand(Command.Result(true)) },
                    enabled = state.shots.any { it != null } && !state.busy,
                ) { Text("결과 보기") }
                FilledTonalButton(onClick = { onCommand(Command.Save) }) { Text("저장") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResponsiveMenuSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val wide = configuration.screenWidthDp >= 840 && configuration.screenWidthDp > configuration.screenHeightDp
    if (wide) {
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = onDismiss))
                androidx.compose.material3.Surface(Modifier.align(androidx.compose.ui.Alignment.CenterEnd)
                    .width(420.dp).fillMaxHeight(), tonalElevation = 8.dp) { content() }
            }
        }
    } else ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { content() }
}
