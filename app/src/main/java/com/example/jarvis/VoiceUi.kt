package com.example.jarvis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Settings → VOICE: pick the assistant's voice, hear a sample, set the speed. */
@Composable
fun VoiceBlock(brain: Brain) {
    val ctx = LocalContext.current
    var selected by remember { mutableStateOf(Voices.current()) }
    var open by remember { mutableStateOf("") }
    var speed by remember { mutableStateOf(Voices.speed()) }
    @Suppress("UNUSED_VARIABLE")
    val tick = Store.voiceTick.value // re-draw after a download finishes

    fun choose(id: String) {
        selected = id
        Store.putStr("voice", id)
        if (id.startsWith("gemini:")) Store.putStr("ttsvoice", id.removePrefix("gemini:"))
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Speaking speed: " + String.format(Locale.US, "%.1f", speed) + "x", color = J.text, fontSize = 14.sp)
        Slider(
            value = speed,
            onValueChange = { speed = it },
            onValueChangeFinished = { Store.putStr("speed", String.format(Locale.US, "%.2f", speed)) },
            valueRange = 0.6f..1.6f
        )
        Text(
            "Tap a voice to choose it, ▶ to hear it. If an online voice isn't available, Jarvis uses an offline one.",
            color = J.dim, fontSize = 12.sp
        )

        GroupHeader("Google AI voices (online, most natural)", open == "g") { open = if (open == "g") "" else "g" }
        if (open == "g") {
            Voices.gemini.forEach { n ->
                val id = "gemini:$n"
                VoiceRow(n, selected == id, { choose(id) }) { Action("▶") { brain.previewVoice(id) } }
            }
        }

        GroupHeader("Offline voices (work without internet)", open == "p") { open = if (open == "p") "" else "p" }
        if (open == "p") {
            Voices.piper.forEach { v ->
                val id = "piper:${v.id}"
                val ready = Voices.downloaded(ctx, v.id)
                val pct = Store.downloading[v.id]
                VoiceRow(v.label, selected == id, { if (ready) choose(id) else brain.downloadVoice(v.id) }) {
                    when {
                        pct != null -> Text("$pct%", color = J.cyan, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp))
                        ready -> Action("▶") { brain.previewVoice(id) }
                        else -> Action("⬇ 60 MB") { brain.downloadVoice(v.id) }
                    }
                }
            }
        }

        GroupHeader("Phone's own voice", open == "f") { open = if (open == "f") "" else "f" }
        if (open == "f") {
            VoiceRow("Phone's built-in voice", selected == "phone:", { choose("phone:") }) {
                Action("▶") { brain.previewVoice("phone:") }
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text((if (expanded) "▾ " else "▸ ") + title, color = J.cyan, fontSize = 14.sp)
    }
}

@Composable
private fun VoiceRow(label: String, selected: Boolean, onSelect: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onSelect() }.padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            (if (selected) "● " else "○ ") + label,
            color = if (selected) J.cyan else J.text, fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        trailing()
    }
}

@Composable
private fun Action(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text, fontSize = 13.sp) }
}
