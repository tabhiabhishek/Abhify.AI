package ai.abhify.android.ui

import ai.abhify.android.chat.Identity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 7.1.0-dev.2 Settings → AI personalization → Assistant identity: suggested names, a custom name (validated like the desktop and
 * web apps), a preview, Save / Cancel / Restore default. Saved on this phone only.
 */
@Composable
fun AssistantIdentityGroup(vm: AppViewModel) {
    val x = LocalExtra.current
    var draft by remember { mutableStateOf(vm.assistantName.value) }
    var error by remember { mutableStateOf<String?>(null) }
    val check = remember(draft) { runCatching { Identity.validate(draft); null }.getOrElse { it.message } }
    SettingsGroup("AI personalization · Assistant identity", accent = Accent.CHAT) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Logo(44)
            Column(Modifier.weight(1f)) {
                Text(draft.ifBlank { vm.assistantName.value }, style = MaterialTheme.typography.titleMedium)
                Text("Your AI assistant in Abhify.AI · on this phone", style = MaterialTheme.typography.bodySmall, color = x.muted)
            }
            TextButton({ vm.voice.preview("Hi! I'm ${draft.ifBlank { vm.assistantName.value }}, your assistant in Abhify.AI.", ai.abhify.android.voice.VoiceSettings.Style.ASSISTANT,
                vm.voiceSettings.value.rate) }) { Text("▶") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Identity.SUGGESTIONS.forEach { n -> FilterChip(draft == n, { draft = n; error = null }, { Text(n) }) }
        }
        OutlinedTextField(draft, { draft = it.take(40); error = null }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true,
            isError = check != null, supportingText = { Text(error ?: check ?: "Up to ${Identity.MAX_LEN} characters. The product name stays Abhify.AI.") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton({ draft = Identity.DEFAULT_NAME; error = null }) { Text("Restore default") }
            TextButton({ draft = vm.assistantName.value; error = null }) { Text("Cancel") }
            Button({ error = vm.setAssistantName(draft); if (error == null) draft = vm.assistantName.value },
                enabled = check == null && draft.trim() != vm.assistantName.value) { Text("Save name") }
        }
        Text("Used in chat, in answers (\"What is your name?\") and in voice on this phone. Your Windows and Web apps keep their own name.",
            style = MaterialTheme.typography.bodySmall, color = x.muted)
    }
}
