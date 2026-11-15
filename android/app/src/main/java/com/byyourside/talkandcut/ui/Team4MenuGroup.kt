package com.byyourside.talkandcut.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.byyourside.talkandcut.command.Team4Interpreter

@Composable
fun Team4MenuGroup(model: Team4Interpreter) {
    val enabled by model.enabled.collectAsStateWithLifecycle()
    val status by model.status.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    Section("명령 해석 모델")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text("Team4 AI 모델 사용")
        Switch(checked = enabled, onCheckedChange = model::setEnabled, enabled = !busy)
    }
    Text(if (busy) "AI가 명령을 판단하고 있어요…" else status)
}
