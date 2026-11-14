package com.byyourside.talkandcut.ui

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

/** 오른쪽 위 메뉴. 음성 명령과 같은 Command를 만든다. 촬영·포즈·보정·프레임·저장. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BoothMenuSheet(
    state: BoothState<*>,
    onCommand: (Command) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
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
