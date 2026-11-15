package com.byyourside.talkandcut.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.byyourside.talkandcut.devices.DeviceOption
import com.byyourside.talkandcut.devices.DeviceSettings

@Composable
fun DeviceMenuGroup(devices: DeviceSettings, enabled: Boolean, onMicrophoneChanged: () -> Unit, onSpeakerChanged: () -> Unit) {
    val state by devices.state.collectAsStateWithLifecycle()
    Section("장치 설정")
    DeviceDropdown("카메라", state.cameras, state.camera, enabled, devices::selectCamera, devices::refresh)
    DeviceDropdown("마이크", state.microphones, state.microphone, enabled, {
        devices.selectMicrophone(it); onMicrophoneChanged()
    }, devices::refresh)
    DeviceDropdown("스피커", state.speakers, state.speaker, enabled, {
        devices.selectSpeaker(it); onSpeakerChanged()
    }, devices::refresh)
}

@Composable
private fun DeviceDropdown(label: String, options: List<DeviceOption>, selected: String, enabled: Boolean, onSelect: (String) -> Unit, refresh: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selection = options.firstOrNull { it.id == selected }?.label ?: "기본"
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { refresh(); expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $selection  ▾", modifier = Modifier.weight(1f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text((if (option.id == selected) "✓  " else "") + option.label) },
                    onClick = { expanded = false; onSelect(option.id) })
            }
        }
    }
}
