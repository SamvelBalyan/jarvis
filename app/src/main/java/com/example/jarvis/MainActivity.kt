package com.example.jarvis

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class NoSuchAppException(message: String) : Exception(message)

class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private val fields = mutableMapOf<String, EditText>()
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text != null) handle(text)
    }
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    // Words you can say after "open" on the PC that are websites
    private val sites = mapOf(
        "google" to "google.com", "youtube" to "youtube.com", "gmail" to "mail.google.com",
        "github" to "github.com", "facebook" to "facebook.com", "instagram" to "instagram.com",
        "netflix" to "netflix.com", "maps" to "maps.google.com", "claude" to "claude.ai",
        "chatgpt" to "chatgpt.com", "twitter" to "x.com", "reddit" to "reddit.com",
    )

    // ---------------------------------------------------------------- UI

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        root.addView(Button(this).apply {
            text = "🎤  Tap & speak"
            textSize = 22f
            minimumHeight = dp(96)
            setOnClickListener { listen() }
        })

        val typed = EditText(this).apply { hint = "…or type a command"; setSingleLine() }
        root.addView(typed)
        root.addView(Button(this).apply {
            text = "Run"
            setOnClickListener {
                val s = typed.text.toString()
                if (s.isNotBlank()) { handle(s); typed.setText("") }
            }
        })

        log = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
            text = "Try:\n• alarm at 7:30 am\n• timer for 10 minutes\n• call mom\n" +
                "• open camera\n• play despacito\n• open google on pc\n• turn on pc\n• put pc to sleep"
        }
        root.addView(log)

        root.addView(TextView(this).apply { text = "Settings"; textSize = 18f })
        addField(root, "PC IP address (e.g. 192.168.15.111)", "ip")
        addField(root, "PC port", "port", "8765")
        addField(root, "Secret token (from PC config.json)", "token")
        addField(root, "PC MAC address (A1-B2-C3-D4-E5-F6)", "mac")
        addField(root, "Broadcast address", "broadcast", "192.168.15.255")
        root.addView(Button(this).apply {
            text = "Save settings"
            setOnClickListener {
                val e = prefs.edit()
                fields.forEach { (k, v) -> e.putString(k, v.text.toString().trim()) }
                e.apply()
                say("Settings saved")
            }
        })

        setContentView(scroll)

        val needed = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
            .filter { !granted(it) }
        if (needed.isNotEmpty()) permissionRequest.launch(needed.toTypedArray())
    }

    private fun addField(parent: LinearLayout, hint: String, key: String, default: String = "") {
        val f = EditText(this).apply {
            this.hint = hint
            setSingleLine()
            setText(prefs.getString(key, default))
        }
        fields[key] = f
        parent.addView(f)
    }

    private fun cfg(key: String) = fields[key]?.text?.toString()?.trim() ?: ""
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun say(msg: String) {
        log.text = (msg + "\n\n" + log.text).take(1500)
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a command")
        try { speech.launch(i) } catch (e: ActivityNotFoundException) { say("⚠ No speech recognizer on this phone") }
    }

    // ---------------------------------------------------------------- brain

    private fun handle(raw: String) {
        val t = raw.lowercase().replace(" dot ", ".").trim().trimEnd('.', '!', '?')
        try {
            say("🎤 $raw\n→ " + route(t))
        } catch (e: Exception) {
            say("🎤 $raw\n⚠ ${e.message}")
        }
    }

    private fun route(t: String): String {
        val pcWord = Regex("\\b(pc|computer|laptop)\\b")
        val wake = Regex("(wake|turn on|power on|boot|start up).*\\b(pc|computer|laptop)\\b")
        if (wake.containsMatchIn(t)) { wakePc(); return "Sending wake signal to the PC…" }
        if (pcWord.containsMatchIn(t)) return pcCommand(t)
        return phoneCommand(t)
    }

    // ---------------------------------------------------------------- PC commands

    private fun pcCommand(t: String): String {
        val c = t.replace(Regex("\\b(on|in|at|my|the|to|please|pc|computer|laptop)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return when {
            c.contains("sleep") -> send("sleep")
            c.contains("lock") -> send("lock")
            c.contains("restart") || c.contains("reboot") -> send("restart")
            c.contains("shut down") || c.contains("shutdown") || c.contains("turn off") ||
                c.contains("power off") -> send("shutdown")
            c.startsWith("search ") -> send("search", c.removePrefix("search ").removePrefix("for ").trim())
            c.startsWith("open ") || c.startsWith("launch ") || c.startsWith("start ") ->
                openOnPc(c.substringAfter(" ").trim())
            else -> throw Exception("Didn't understand that PC command")
        }
    }

    private fun openOnPc(target: String): String = when {
        target.contains(".") -> send("open_url", target.replace(" ", ""))
        sites.containsKey(target) -> send("open_url", sites[target])
        else -> send("open_app", target)
    }

    private fun send(action: String, arg: String? = null): String {
        val ip = cfg("ip")
        val port = cfg("port").ifEmpty { "8765" }
        val token = cfg("token")
        if (ip.isEmpty() || token.isEmpty()) throw Exception("Fill in PC IP and token in Settings, then Save")
        thread {
            try {
                val c = URL("http://$ip:$port/command").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 4000
                c.readTimeout = 8000
                c.doOutput = true
                val body = JSONObject().put("token", token).put("action", action)
                if (arg != null) body.put("arg", arg)
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
                val resp = JSONObject(stream.bufferedReader().readText())
                val msg = if (resp.optBoolean("ok")) "✅ PC: ${resp.optString("result")}"
                else "⚠ PC: ${resp.optString("error")}"
                runOnUiThread { say(msg) }
            } catch (e: Exception) {
                runOnUiThread { say("⚠ Can't reach PC (${e.message}). Is it awake and on the same Wi-Fi?") }
            }
        }
        return "Sending '$action' to PC…"
    }

    private fun wakePc() {
        val parts = cfg("mac").split("-", ":")
        if (parts.size != 6) throw Exception("Enter the PC MAC address in Settings (A1-B2-C3-D4-E5-F6)")
        val mac = try { parts.map { it.toInt(16).toByte() } }
        catch (e: NumberFormatException) { throw Exception("MAC address looks wrong") }
        val packet = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { mac[it % 6] }
        val targets = listOf(cfg("broadcast").ifEmpty { "255.255.255.255" }, "255.255.255.255")
        thread {
            var sent = 0
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    for (addr in targets) for (port in listOf(9, 7)) {
                        try {
                            s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(addr), port))
                            sent++
                        } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { }
            runOnUiThread { say(if (sent > 0) "✅ Wake packet sent" else "⚠ Couldn't send wake packet (on Wi-Fi?)") }
        }
    }

    // ---------------------------------------------------------------- phone commands

    private fun phoneCommand(t: String): String = when {
        t.startsWith("open ") || t.startsWith("launch ") || t.startsWith("start ") -> {
            val name = t.substringAfter(" ").trim()
            try {
                openApp(name)
            } catch (e: NoSuchAppException) {
                "Not on phone, trying PC. " + openOnPc(name) // e.g. games, AnyDesk
            }
        }
        t.contains("alarm") || t.contains("wake me") -> setAlarm(t)
        t.contains("timer") -> setTimer(t)
        t.startsWith("call ") || t.startsWith("phone ") -> call(t.substringAfter(" ").trim())
        t.startsWith("play ") || t.contains("youtube") -> play(t)
        else -> throw Exception("Didn't understand that")
    }

    private fun setAlarm(t: String): String {
        val s = t.replace(Regex("\\b(\\d{1,2})(\\d{2})\\b"), "$1:$2") // 730 -> 7:30
        val m = Regex("(\\d{1,2})(?:[:.\\s](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?").find(s)
            ?: throw Exception("Say a time, e.g. \"alarm at 7:30 am\"")
        var hour = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[3]
        if (ampm.startsWith("p") && hour < 12) hour += 12
        if (ampm.startsWith("a") && hour == 12) hour = 0
        if (hour !in 0..23 || min !in 0..59) throw Exception("That time doesn't look right")
        startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, min)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "Alarm set for %02d:%02d".format(hour, min)
    }

    private fun setTimer(t: String): String {
        val m = Regex("(\\d+)\\s*(second|sec|minute|min|hour)").find(t)
            ?: throw Exception("Say e.g. \"timer for 10 minutes\"")
        val n = m.groupValues[1].toInt()
        val seconds = when (m.groupValues[2]) { "hour" -> n * 3600; "minute", "min" -> n * 60; else -> n }
        startActivity(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "Timer set for ${m.value}"
    }

    private fun call(name: String): String {
        var number: String? = null
        var shown = name
        if (granted(Manifest.permission.READ_CONTACTS)) {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null
            )?.use { c ->
                if (c.moveToFirst()) { shown = c.getString(0); number = c.getString(1) }
            }
        }
        if (number == null && name.all { it.isDigit() || it == ' ' || it == '+' }) number = name.replace(" ", "")
        val n = number ?: throw Exception("No contact matching \"$name\" (contacts permission allowed?)")
        val uri = Uri.parse("tel:" + Uri.encode(n))
        if (granted(Manifest.permission.CALL_PHONE)) startActivity(Intent(Intent.ACTION_CALL, uri))
        else startActivity(Intent(Intent.ACTION_DIAL, uri))
        return "Calling $shown"
    }

    private fun openApp(name: String): String {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        fun label(i: Int) = apps[i].loadLabel(pm).toString().lowercase()
        val idx = apps.indices.firstOrNull { label(it) == name }
            ?: apps.indices.firstOrNull { label(it).startsWith(name) }
            ?: apps.indices.firstOrNull { label(it).contains(name) }
            ?: throw NoSuchAppException("No installed app called \"$name\"")
        val intent = pm.getLaunchIntentForPackage(apps[idx].activityInfo.packageName)
            ?: throw Exception("Can't launch that app")
        startActivity(intent)
        return "Opening ${apps[idx].loadLabel(pm)}"
    }

    private fun play(t: String): String {
        var q = t.removePrefix("play ").trim()
        val viaYoutube = q.contains("youtube")
        q = q.replace(Regex("\\b(on |in )?youtube( music)?\\b"), "").trim()
        if (q.isEmpty()) return openApp("youtube")

        if (!viaYoutube) { // try YouTube Music first: it can start playing right away
            try {
                startActivity(
                    Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                        .setPackage("com.google.android.apps.youtube.music")
                        .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                        .putExtra("query", q)
                )
                return "Playing \"$q\" on YouTube Music"
            } catch (_: ActivityNotFoundException) { }
        }
        try {
            startActivity(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"))))
        }
        return "Searching YouTube for \"$q\""
    }
}
