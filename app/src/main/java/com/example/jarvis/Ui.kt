package com.example.jarvis

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

class UiActions(
    val listen: () -> Unit,
    val setWake: (Boolean) -> Unit,
    val overlaySettings: () -> Unit,
    val batterySettings: () -> Unit,
    val notificationSettings: () -> Unit,
)

@Composable
fun JarvisApp(brain: Brain, actions: UiActions) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        containerColor = J.bg,
        bottomBar = {
            NavigationBar(containerColor = J.card) {
                val items = listOf("🏠" to "Home", "⚡" to "Commands", "⚙️" to "Settings")
                items.forEachIndexed { i, item ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Text(item.first, fontSize = 20.sp) },
                        label = { Text(item.second) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(brain, actions)
                1 -> CommandsScreen(brain)
                else -> SettingsScreen(brain, actions)
            }
        }
    }
}

// ------------------------------------------------------------------------ small pieces

@Composable
fun Orb(modifier: Modifier, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "orb")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "phase"
    )
    Canvas(modifier.clickable(onClick = onClick)) {
        val c = center
        val r = size.minDimension / 2f
        for (i in 0..2) {
            val p = (phase + i / 3f) % 1f
            drawCircle(
                color = J.cyan.copy(alpha = (1f - p) * 0.38f),
                radius = r * (0.52f + 0.48f * p),
                center = c,
                style = Stroke(width = 2.5.dp.toPx())
            )
        }
        val breathe = 1f + 0.035f * sin(phase * 2f * PI.toFloat())
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFC9F3FF), Color(0xFF3B9DF6), Color(0xFF7C3AED)),
                center = c + Offset(-r * 0.12f, -r * 0.14f),
                radius = r * 0.62f
            ),
            radius = r * 0.48f * breathe,
            center = c
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.28f),
            radius = r * 0.11f,
            center = c + Offset(-r * 0.14f, -r * 0.17f)
        )
    }
}

@Composable
fun Chip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(J.cardHi)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(label, color = J.text, fontSize = 14.sp)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, color = J.cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(J.card)
            .padding(14.dp)
    ) { content() }
}

@Composable
fun PcBadge() {
    val state = Store.pcOnline.value
    val color = when (state) {
        true -> J.good
        false -> J.bad
        null -> J.dim
    }
    val text = when (state) {
        true -> "PC online"
        false -> "PC offline"
        null -> "PC not set"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text("  $text", color = J.dim, fontSize = 13.sp)
    }
}

@Composable
fun LogCard(msg: String) {
    val lines = msg.split("\n")
    val heard = if (lines[0].startsWith("🎤")) lines[0].removePrefix("🎤").trim() else null
    val result = (if (heard != null) lines.drop(1).joinToString("\n") else msg).removePrefix("→").trim()
    Card {
        Column {
            if (heard != null) Text("“$heard”", color = J.dim, fontSize = 13.sp)
            if (result.isNotEmpty()) {
                val bad = result.startsWith("⚠")
                Text(result, color = if (bad) J.bad else J.text, fontSize = 15.sp)
            }
        }
    }
}

// ------------------------------------------------------------------------ home

private val quickChips = listOf(
    "🔌 Wake PC" to "turn on pc",
    "😴 Sleep PC" to "put pc to sleep",
    "🔒 Lock PC" to "lock pc",
    "🔦 Flashlight" to "flashlight on",
    "🔋 Battery" to "battery",
    "❓ Help" to "help",
)

@Composable
fun HomeScreen(brain: Brain, actions: UiActions) {
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            brain.pingPc()
            delay(15000)
        }
    }
    val run = {
        if (typed.isNotBlank()) {
            brain.handle(typed)
            typed = ""
        }
    }
    val custom = Store.customCommands.filter { it.chip }.map { "⚡ ${it.phrase}" to it.phrase }
    val chips = quickChips + custom

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Jarvis", color = J.text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f))
            PcBadge()
        }
        Spacer(Modifier.height(10.dp))
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hey Jarvis", color = J.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (Store.wakeRunning.value) "Listening. Say “Hey Jarvis”, wait for the chime, then your command."
                        else "Off. Switch on to talk hands-free.",
                        color = J.dim, fontSize = 13.sp
                    )
                }
                Switch(checked = Store.wakeRunning.value, onCheckedChange = { actions.setWake(it) })
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
            Orb(Modifier.size(170.dp), onClick = actions.listen)
        }
        Text("Tap the orb to speak", color = J.dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("…or type a command") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { run() }),
            trailingIcon = { TextButton(onClick = { run() }) { Text("Run") } }
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chips) { chip -> Chip(chip.first) { brain.handle(chip.second) } }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (Store.log.isEmpty()) {
                item { Text("Your commands and results will show up here.", color = J.dim, fontSize = 13.sp) }
            }
            items(Store.log) { msg -> LogCard(msg) }
        }
    }
}

// ------------------------------------------------------------------------ commands

@Composable
fun CommandsScreen(brain: Brain) {
    var editing by remember { mutableStateOf<CustomCommand?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item {
            Column {
                Text("My commands", color = J.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Make your own phrases. For example, “gaming mode” can open Far Cry 5 on the PC and set the volume. " +
                        "Each line is a command, written the way you would say it.",
                    color = J.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp)
                )
                Button(onClick = { creating = true }) { Text("＋ New command") }
            }
        }
        items(Store.customCommands) { c ->
            Card {
                Column {
                    Text("“${c.phrase}”", color = J.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    c.actions.forEach { Text("→ $it", color = J.dim, fontSize = 13.sp) }
                    Row {
                        TextButton(onClick = { brain.handle(c.phrase) }) { Text("▶ Run") }
                        TextButton(onClick = { editing = c }) { Text("Edit") }
                        TextButton(onClick = { Store.removeCommand(c) }) { Text("Delete", color = J.bad) }
                    }
                }
            }
        }
        item {
            Card {
                Column {
                    Text("What can I say?", color = J.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "• open youtube / open far cry 5 (phone first, then PC)\n" +
                            "• open google and search for cute cats\n" +
                            "• search pizza on youtube on pc\n" +
                            "• set alarm for 7:30 am called gym\n" +
                            "• set alarm at 6 every weekday\n" +
                            "• timer for 10 minutes\n" +
                            "• remind me to call mom tomorrow at 5 pm\n" +
                            "• add event dentist on november 20 at 4 pm\n" +
                            "• call mom • play despacito • navigate to Republic Square\n" +
                            "• turn on pc • put pc to sleep • lock pc • is pc online\n" +
                            "• flashlight on • volume up • what time is it • battery\n" +
                            "• with a free AI key: say anything natural, or “ask …” for questions",
                        color = J.dim, fontSize = 13.sp
                    )
                }
            }
        }
    }

    if (creating) {
        CommandDialog(null, onDismiss = { creating = false }) { cmd ->
            Store.upsertCommand(null, cmd)
            creating = false
        }
    }
    val e = editing
    if (e != null) {
        CommandDialog(e, onDismiss = { editing = null }) { cmd ->
            Store.upsertCommand(e, cmd)
            editing = null
        }
    }
}

@Composable
fun CommandDialog(initial: CustomCommand?, onDismiss: () -> Unit, onSave: (CustomCommand) -> Unit) {
    var phrase by remember { mutableStateOf(initial?.phrase ?: "") }
    var steps by remember { mutableStateOf(initial?.actions?.joinToString("\n") ?: "") }
    var chip by remember { mutableStateOf(initial?.chip ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = J.card,
        title = { Text(if (initial == null) "New command" else "Edit command") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = { Text("When I say…") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = steps,
                    onValueChange = { steps = it },
                    label = { Text("Do this (one command per line)") },
                    minLines = 3
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = chip, onCheckedChange = { chip = it })
                    Text("Show as a button on Home", fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val lines = steps.lines().map { it.trim() }.filter { it.isNotEmpty() }
                if (phrase.isNotBlank() && lines.isNotEmpty()) {
                    onSave(CustomCommand(phrase.trim().lowercase(), lines, chip))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ------------------------------------------------------------------------ settings

@Composable
fun SettingsScreen(brain: Brain, actions: UiActions) {
    val ctx = LocalContext.current
    var ip by remember { mutableStateOf(Store.str("ip")) }
    var port by remember { mutableStateOf(Store.str("port", "8765")) }
    var token by remember { mutableStateOf(Store.str("token")) }
    var mac by remember { mutableStateOf(Store.str("mac")) }
    var broadcast by remember { mutableStateOf(Store.str("broadcast", "192.168.15.255")) }
    var sound by remember { mutableStateOf(Store.flag("sound")) }
    var overlayOn by remember { mutableStateOf(Store.flag("overlay")) }
    var aiKey by remember { mutableStateOf(Store.str("aikey")) }
    var aiModel by remember { mutableStateOf(Store.str("aimodel", "gemini-flash-latest")) }
    var speak by remember { mutableStateOf(Store.flag("speak")) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        item { Text("Settings", color = J.text, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
        item {
            OutlinedButton(
                onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val txt = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
                    var n = 0
                    for (line in txt.lines()) {
                        val i = line.indexOf('=')
                        if (i <= 0) continue
                        val k = line.substring(0, i).trim().lowercase()
                        val v = line.substring(i + 1).trim()
                        if (v.isNotEmpty() && k in setOf("ip", "port", "token", "mac", "broadcast", "aikey", "aimodel")) {
                            Store.putStr(k, v)
                            n++
                        }
                    }
                    ip = Store.str("ip")
                    port = Store.str("port", "8765")
                    token = Store.str("token")
                    mac = Store.str("mac")
                    broadcast = Store.str("broadcast", "192.168.15.255")
                    aiKey = Store.str("aikey")
                    aiModel = Store.str("aimodel", "gemini-flash-latest")
                    Toast.makeText(ctx, if (n > 0) "Imported $n settings" else "Nothing to import. Copy the settings text first.", Toast.LENGTH_LONG).show()
                    if (n > 0) brain.pingPc()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("📋 Paste settings from clipboard") }
        }
        item { SectionTitle("YOUR PC") }
        item { SettingField("PC IP address (e.g. 192.168.15.111)", ip) { ip = it } }
        item { SettingField("Port", port) { port = it } }
        item { SettingField("Secret token (from config.json on the PC)", token) { token = it } }
        item { SettingField("PC MAC address (A1-B2-C3-D4-E5-F6)", mac) { mac = it } }
        item { SettingField("Broadcast address", broadcast) { broadcast = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    Store.putStr("ip", ip)
                    Store.putStr("port", port)
                    Store.putStr("token", token)
                    Store.putStr("mac", mac)
                    Store.putStr("broadcast", broadcast)
                    Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                    brain.pingPc()
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    Store.putStr("ip", ip)
                    Store.putStr("port", port)
                    Store.putStr("token", token)
                    brain.pingPc(announce = true)
                    Toast.makeText(ctx, "Testing connection…", Toast.LENGTH_SHORT).show()
                }) { Text("Test connection") }
            }
        }
        item { SectionTitle("HEY JARVIS") }
        item {
            ToggleRow("Chime sound", "A short sound when Jarvis hears you and when it finishes", sound) {
                sound = it
                Store.putFlag("sound", it)
            }
        }
        item {
            ToggleRow("Glowing panel", "Show the animated panel at the bottom of the screen", overlayOn) {
                overlayOn = it
                Store.putFlag("overlay", it)
            }
        }
        item { SectionTitle("SMART MODE (FREE AI, OPTIONAL)") }
        item {
            Text(
                "Get a free key at aistudio.google.com/apikey and paste it here. When I don't understand a sentence, " +
                    "the AI works out what you meant and can answer questions. Say or type “ask …” to talk to it directly.",
                color = J.dim, fontSize = 12.sp
            )
        }
        item {
            OutlinedTextField(
                value = aiKey,
                onValueChange = { aiKey = it; Store.putStr("aikey", it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gemini API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                shape = RoundedCornerShape(14.dp)
            )
        }
        item { SettingField("AI model", aiModel) { aiModel = it; Store.putStr("aimodel", it) } }
        item {
            ToggleRow("Speak answers", "Read the AI's answers out loud", speak) {
                speak = it
                Store.putFlag("speak", it)
            }
        }
        item {
            OutlinedButton(onClick = { brain.handle("ask say hello and tell me one fun fact") }) { Text("Test AI") }
        }
        item { SectionTitle("PERMISSIONS (needed for Hey Jarvis)") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.overlaySettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow display over other apps")
                }
                OutlinedButton(onClick = actions.batterySettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Allow running in background (battery)")
                }
                OutlinedButton(onClick = actions.notificationSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("App settings (microphone, notifications…)")
                }
            }
        }
        item {
            Text(
                "Jarvis 4.1 • offline voice; the cloud is used only if you add an AI key.",
                color = J.dim, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
fun SettingField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp)
    )
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = J.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = J.dim, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
