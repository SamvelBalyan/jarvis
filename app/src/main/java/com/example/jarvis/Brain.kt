package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.concurrent.thread

class NoSuchAppException(message: String) : Exception(message)

/** Thrown when the built-in rules can't make sense of a sentence (the AI layer gets a try). */
class UnknownCommand(message: String) : Exception(message)

/**
 * Understands a spoken/typed command and carries it out.
 * Works from an Activity (button) or from a Service (Hey Jarvis).
 * [report] is always called on the main thread.
 */
class Brain(private val ctx: Context, private val report: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())

    init {
        Store.init(ctx)
    }

    private val sites = mapOf(
        "google" to "google.com", "youtube" to "youtube.com", "gmail" to "mail.google.com",
        "github" to "github.com", "facebook" to "facebook.com", "instagram" to "instagram.com",
        "netflix" to "netflix.com", "maps" to "maps.google.com", "claude" to "claude.ai",
        "chatgpt" to "chatgpt.com", "twitter" to "x.com", "reddit" to "reddit.com",
    )

    private val calendarRe = Regex(
        "\\bremind me\\b|\\breminder\\b|\\b(?:add|put|create|make|schedule|set)\\b.*\\b(?:event|appointment|meeting)\\b|" +
            "\\bto (?:my )?(?:google )?calendar\\b"
    )

    private val helpText =
        "Say “Hey Jarvis” then:\n" +
            "• open <app> (phone, or PC if not on phone)\n" +
            "• open google and search for <text>\n" +
            "• set alarm for 7:30 am called gym\n" +
            "• remind me to <thing> tomorrow at 5 pm\n" +
            "• call <contact> • play <song> • navigate to <place>\n" +
            "• turn on pc • put pc to sleep • lock pc • is pc online\n" +
            "• flashlight on/off • volume up/down • battery • what time is it\n" +
            "• or any command you add in the Commands tab"

    private fun cfg(key: String, default: String = ""): String = Store.str(key, default)

    private fun say(msg: String) {
        main.post { report(msg) }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- entry points

    /** Run something you said or typed. */
    fun handle(raw: String) {
        val t = clean(raw)
        if (t.isEmpty()) return
        val custom = Store.findCustom(t)
        if (custom != null) {
            say("🎤 $raw\n→ Running “${custom.phrase}” (${custom.actions.size} step${if (custom.actions.size == 1) "" else "s"})")
            custom.actions.forEachIndexed { i, step ->
                main.postDelayed({ runLine(step) }, 1200L * (i + 1))
            }
            return
        }
        runLine(raw)
    }

    private fun clean(raw: String) =
        Parsers.normalizeSpeech(raw).replace(" dot ", ".").trim().trimEnd('.', '!', '?')

    private fun runLine(raw: String, allowAi: Boolean = true) {
        val t = clean(raw)
        if (t.isEmpty()) return
        if (allowAi && (t.startsWith("ask ") || t.startsWith("hey ") || t.startsWith("tell me "))) {
            if (Ai.enabled()) {
                askAi(raw.trim().replace(Regex("^(ask|hey|tell me)\\s+", RegexOption.IGNORE_CASE), ""))
            } else {
                say("🎤 $raw\n⚠ Add a free AI key in Settings → AI first")
            }
            return
        }
        try {
            say("🎤 $raw\n→ " + route(t))
        } catch (e: UnknownCommand) {
            when {
                allowAi && Ai.enabled() -> askAi(raw)
                allowAi -> say("🎤 $raw\n⚠ ${e.message} Add a free AI key in Settings → AI and I'll understand much more.")
                else -> say("🎤 $raw\n⚠ ${e.message}")
            }
        } catch (e: Exception) {
            say("🎤 $raw\n⚠ ${e.message}")
        }
    }

    // ---------------------------------------------------------------- AI layer

    private fun askAi(text: String) {
        say("🎤 $text\n→ 🤖 Thinking…")
        thread {
            try {
                val r = Ai.interpret(text)
                main.post {
                    r.commands.forEachIndexed { i, cmd ->
                        main.postDelayed({ runAiStep(cmd) }, 700L * (i + 1))
                    }
                }
                val reply = if (r.reply.isNotEmpty()) r.reply
                else if (r.commands.isNotEmpty()) "Doing: " + r.commands.joinToString(", ")
                else "Hmm, I'm not sure what you meant."
                say("🤖 $reply")
                if (r.reply.isNotEmpty()) speak(r.reply)
            } catch (e: Ai.AiException) {
                say("⚠ ${e.message}")
            } catch (e: Exception) {
                say("⚠ Couldn't reach the AI (${e.message}). Check your internet.")
            }
        }
    }

    /** Settings → Test AI: checks the connection first, then asks a sample question. */
    fun testAi() {
        say("🤖 Checking the AI connection…")
        thread {
            val d = Ai.diagnose()
            say(d)
            if (d.startsWith("✅")) main.post { askAi("say hello and tell me one fun fact") }
        }
    }

    private fun runAiStep(cmd: String) {
        val custom = Store.findCustom(clean(cmd))
        if (custom != null) {
            custom.actions.forEachIndexed { i, step -> main.postDelayed({ runLine(step, false) }, 1200L * i) }
        } else {
            runLine(cmd, false)
        }
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    private var track: AudioTrack? = null

    private fun speak(text: String) {
        if (!Store.flag("speak")) return
        if (Store.flag("naturalvoice") && Ai.enabled()) {
            thread {
                val r = Ai.tts(text)
                if (r != null) main.post { playPcm(r.first, r.second) } else main.post { localSpeak(text) }
            }
        } else {
            main.post { localSpeak(text) }
        }
    }

    /** Cuts the last 0.1 s (the model sometimes ends with a burst), fades out, and adds a little silence. */
    private fun cleanTail(pcm: ByteArray, rate: Int): ByteArray {
        val keep = pcm.size - (rate / 10) * 2
        if (keep < rate) return pcm
        val buf = pcm.copyOf(keep - keep % 2)
        val total = buf.size / 2
        val fade = minOf(total, (rate * 0.15).toInt())
        for (k in 0 until fade) {
            val idx = (total - fade + k) * 2
            val v = ((buf[idx].toInt() and 0xFF) or (buf[idx + 1].toInt() shl 8)).toShort().toInt()
            val o = (v * (1f - (k + 1).toFloat() / fade)).toInt()
            buf[idx] = (o and 0xFF).toByte()
            buf[idx + 1] = ((o shr 8) and 0xFF).toByte()
        }
        return buf + ByteArray((rate * 0.2).toInt() * 2)
    }

    private fun playPcm(raw: ByteArray, rate: Int) {
        val pcm = cleanTail(raw, rate)
        try {
            track?.let {
                try { it.stop() } catch (_: Exception) { }
                it.release()
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            t.write(pcm, 0, pcm.size)
            t.play()
            track = t
            main.postDelayed({
                try { t.release() } catch (_: Exception) { }
                if (track === t) track = null
            }, pcm.size / 2L * 1000L / rate + 800L)
        } catch (e: Exception) {
            // never let a sound problem break a command
        }
    }

    private fun localSpeak(text: String) {
        if (tts == null) {
            pendingSpeech = text
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setLanguage(Locale.UK)
                    try {
                        val all = tts?.voices?.toList() ?: emptyList()
                        val uk = all.filter { it.locale == Locale.UK }
                        val male = uk.firstOrNull { it.name.contains("gbd") || it.name.contains("gbb") }
                        val pick = male ?: uk.firstOrNull()
                        if (pick != null) tts?.setVoice(pick)
                    } catch (_: Exception) {
                    }
                    pendingSpeech?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "jarvis") }
                    pendingSpeech = null
                }
            }
        } else if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        }
    }

    private fun route(t: String): String {
        builtin(t)?.let { return it }
        val pcWord = Regex("\\b(pc|computer|laptop)\\b")
        val wake = Regex("(wake|turn on|power on|boot|start up).*\\b(pc|computer|laptop)\\b")
        if (wake.containsMatchIn(t)) {
            wakePc()
            return "Sending wake signal to the PC…"
        }
        if (pcWord.containsMatchIn(t)) return pcCommand(t)
        return phoneCommand(t)
    }

    // ---------------------------------------------------------------- built-in little helpers

    private fun builtin(t: String): String? {
        if (t == "help" || t == "what can you do" || t == "what can i say" || t == "commands") return helpText
        if (t.contains("what time") || t == "time") {
            return "It's " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }
        if (t.contains("what day") || t.contains("the date") || t.contains("today's date")) {
            return SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date())
        }
        if (t.contains("battery") && !Regex("\\b(pc|computer|laptop)\\b").containsMatchIn(t)) {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            return "Battery ${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}%"
        }
        if (Regex("\\b(flashlight|torch)\\b").containsMatchIn(t)) return torch(!t.contains(" off"))
        if (Regex("\\b(volume up|louder|turn it up)\\b").containsMatchIn(t)) return volume(AudioManager.ADJUST_RAISE)
        if (Regex("\\b(volume down|quieter|turn it down)\\b").containsMatchIn(t)) return volume(AudioManager.ADJUST_LOWER)
        if (t == "mute" || t == "mute the volume") return volume(AudioManager.ADJUST_MUTE)
        if (t == "unmute") return volume(AudioManager.ADJUST_UNMUTE)
        val nav = Regex("^(?:navigate to|directions to|take me to|route to) (.+)$").find(t)
        if (nav != null) {
            val place = nav.groupValues[1].trim()
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + enc(place))))
            return "Showing “$place” on the map"
        }
        return null
    }

    private fun torch(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw Exception("No flashlight on this phone")
        cm.setTorchMode(id, on)
        return if (on) "🔦 Flashlight on" else "Flashlight off"
    }

    private fun volume(direction: Int): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val times = if (direction == AudioManager.ADJUST_RAISE || direction == AudioManager.ADJUST_LOWER) 3 else 1
        repeat(times) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI) }
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return "Volume ${cur * 100 / max}%"
    }

    // ---------------------------------------------------------------- launching screens

    /** Starts an activity. From a service (Hey Jarvis) Android needs a visible overlay to allow it. */
    private fun launch(intent: Intent) {
        if (ctx is Activity) {
            ctx.startActivity(intent)
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Settings.canDrawOverlays(ctx)) {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = View(ctx)
            val lp = WindowManager.LayoutParams(
                1, 1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            )
            try { wm.addView(v, lp) } catch (_: Exception) { }
            main.postDelayed({
                try { ctx.startActivity(intent) } catch (e: Exception) { report("⚠ ${e.message}") }
            }, 250)
            main.postDelayed({ try { wm.removeView(v) } catch (_: Exception) { } }, 4000)
        } else {
            ctx.startActivity(intent)
        }
    }

    // ---------------------------------------------------------------- PC commands

    private fun searchUrl(s: Parsers.Search): String =
        if (s.engine == "youtube") "https://www.youtube.com/results?search_query=" + enc(s.query)
        else "https://www.google.com/search?q=" + enc(s.query)

    private fun pcCommand(t: String): String {
        Parsers.parseSearch(t)?.let { return send("open_url", searchUrl(it)) }
        if (Regex("\\b(status|online|awake|ping)\\b").containsMatchIn(t)) return send("ping")
        val c = t.replace(Regex("\\b(on|in|at|my|the|to|please|pc|computer|laptop)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        return when {
            c.contains("sleep") -> send("sleep")
            c.contains("lock") -> send("lock")
            c.contains("restart") || c.contains("reboot") -> send("restart")
            c.contains("shut down") || c.contains("shutdown") || c.contains("turn off") ||
                c.contains("power off") -> send("shutdown")
            c.startsWith("open ") || c.startsWith("launch ") || c.startsWith("start ") ->
                openOnPc(c.substringAfter(" ").trim())
            else -> throw UnknownCommand("Didn't understand that PC command.")
        }
    }

    private fun openOnPc(target: String): String = when {
        target.contains(".") -> send("open_url", target.replace(" ", ""))
        sites.containsKey(target) -> send("open_url", sites[target])
        else -> send("open_app", target)
    }

    /** Sends one command to the agent on the PC. Throws on network problems. */
    private fun postCommand(action: String, arg: String?): JSONObject {
        val ip = cfg("ip")
        val port = cfg("port", "8765")
        val token = cfg("token")
        if (ip.isEmpty() || token.isEmpty()) throw Exception("Fill in PC IP and token in Settings, then Save")
        val c = URL("http://$ip:$port/command").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 4000
        c.readTimeout = 8000
        c.doOutput = true
        val body = JSONObject().put("token", token).put("action", action)
        if (arg != null) body.put("arg", arg)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return JSONObject(stream.bufferedReader().readText())
    }

    private fun send(action: String, arg: String? = null): String {
        if (cfg("ip").isEmpty() || cfg("token").isEmpty()) {
            throw Exception("Fill in PC IP and token in Settings, then Save")
        }
        thread {
            try {
                val resp = postCommand(action, arg)
                val ok = resp.optBoolean("ok")
                main.post { Store.pcOnline.value = true }
                say(
                    if (!ok) "⚠ PC: ${resp.optString("error")}"
                    else if (action == "ping") "✅ PC is online"
                    else "✅ PC: ${resp.optString("result")}"
                )
            } catch (e: Exception) {
                main.post { Store.pcOnline.value = false }
                say("⚠ Can't reach PC (${e.message}). Is it awake and on the same Wi-Fi?")
            }
        }
        return "Sending '$action' to PC…"
    }

    /** Quiet background check used by the status dot. */
    fun pingPc(announce: Boolean = false) {
        if (cfg("ip").isEmpty() || cfg("token").isEmpty()) {
            main.post { Store.pcOnline.value = null }
            return
        }
        thread {
            val ok = try { postCommand("ping", null).optBoolean("ok") } catch (e: Exception) { false }
            main.post {
                Store.pcOnline.value = ok
                if (announce) report(if (ok) "✅ PC is online" else "⚠ PC not reachable")
            }
        }
    }

    private fun wakePc() {
        val parts = cfg("mac").split("-", ":")
        if (parts.size != 6) throw Exception("Enter the PC MAC address in Settings (A1-B2-C3-D4-E5-F6)")
        val mac = try {
            parts.map { it.toInt(16).toByte() }
        } catch (e: NumberFormatException) {
            throw Exception("MAC address looks wrong")
        }
        val packet = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { mac[it % 6] }
        val targets = listOf(cfg("broadcast", "255.255.255.255"), "255.255.255.255")
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
            say(if (sent > 0) "✅ Wake packet sent" else "⚠ Couldn't send wake packet (on Wi-Fi?)")
        }
    }

    // ---------------------------------------------------------------- phone commands

    private fun phoneCommand(t: String): String {
        val first = t.substringBefore(" ")
        val opening = first == "open" || first == "launch"
        val search = Parsers.parseSearch(t)
        return when {
            !opening && (t.contains("alarm") || t.contains("wake me")) -> setAlarm(t)
            !opening && t.contains("timer") -> setTimer(t)
            calendarRe.containsMatchIn(t) -> addEvent(t)
            search != null -> {
                launch(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl(search))))
                "Searching ${search.engine} for \"${search.query}\""
            }
            opening -> {
                val name = t.substringAfter(" ").trim()
                try {
                    openApp(name)
                } catch (e: NoSuchAppException) {
                    "Not on phone, trying PC. " + openOnPc(name) // e.g. games, AnyDesk
                }
            }
            t.startsWith("call ") || t.startsWith("phone ") -> call(t.substringAfter(" ").trim())
            t.startsWith("play ") || t.contains("youtube") -> play(t)
            else -> throw UnknownCommand("Didn't understand that. Say “help” to see what I can do.")
        }
    }

    private fun setAlarm(t: String): String {
        val a = Parsers.parseAlarm(t)
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, a.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, a.minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (a.label != null) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.label)
        if (a.days.isNotEmpty()) i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList<Int>(a.days))
        launch(i)
        return "⏰ Alarm %02d:%02d".format(a.hour, a.minute) +
            (if (a.label != null) " “${a.label}”" else "") +
            (if (a.days.isNotEmpty()) " (repeating)" else "")
    }

    private fun setTimer(t: String): String {
        val m = Regex("(\\d+)\\s*(second|sec|minute|min|hour)").find(t)
            ?: throw Exception("Say e.g. \"timer for 10 minutes\"")
        val n = m.groupValues[1].toInt()
        val seconds = when (m.groupValues[2]) {
            "hour" -> n * 3600
            "minute", "min" -> n * 60
            else -> n
        }
        launch(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "⏳ Timer set for ${m.value}"
    }

    // ---- calendar

    private fun addEvent(t: String): String {
        val ev = Parsers.parseEvent(t)
        val whenText = SimpleDateFormat(if (ev.allDay) "EEE d MMM" else "EEE d MMM HH:mm", Locale.getDefault())
            .format(Date(ev.start))
        if (granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR)) {
            val calId = findCalendarId()
            if (calId != null) {
                insertEvent(calId, ev)
                return "📅 Added “${ev.title}” — $whenText"
            }
        }
        // no permission / no calendar: open the Calendar app with the details filled in
        val end = ev.start + 3_600_000L
        launch(
            Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, ev.title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ev.start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, ev.allDay)
        )
        return "Opening Calendar: “${ev.title}” — $whenText (tap Save)"
    }

    private fun findCalendarId(): Long? {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        var best: Long? = null
        var bestScore = -1
        ctx.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj,
            "${CalendarContract.Calendars.VISIBLE} = 1", null, null
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(4) < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                var score = 0
                if (c.getString(3) == "com.google") score += 2
                if (c.getString(1) == c.getString(2)) score += 1
                if (score > bestScore) {
                    bestScore = score
                    best = c.getLong(0)
                }
            }
        }
        return best
    }

    private fun insertEvent(calId: Long, ev: Parsers.Event) {
        val values = ContentValues()
        values.put(CalendarContract.Events.CALENDAR_ID, calId)
        values.put(CalendarContract.Events.TITLE, ev.title)
        if (ev.allDay) {
            val local = Calendar.getInstance().apply { timeInMillis = ev.start }
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
            }
            values.put(CalendarContract.Events.DTSTART, utc.timeInMillis)
            values.put(CalendarContract.Events.DTEND, utc.timeInMillis + 86_400_000L)
            values.put(CalendarContract.Events.ALL_DAY, 1)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
        } else {
            values.put(CalendarContract.Events.DTSTART, ev.start)
            values.put(CalendarContract.Events.DTEND, ev.start + 3_600_000L)
            values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val uri = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw Exception("Calendar refused the event")
        if (!ev.allDay) {
            val r = ContentValues()
            r.put(CalendarContract.Reminders.EVENT_ID, ContentUris.parseId(uri))
            r.put(CalendarContract.Reminders.MINUTES, 10)
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r)
        }
    }

    // ---- calls, apps, music

    private fun call(name: String): String {
        var number: String? = null
        var shown = name
        if (granted(Manifest.permission.READ_CONTACTS)) {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    shown = c.getString(0)
                    number = c.getString(1)
                }
            }
        }
        if (number == null && name.all { it.isDigit() || it == ' ' || it == '+' }) number = name.replace(" ", "")
        val n = number ?: throw Exception("No contact matching \"$name\" (contacts permission allowed?)")
        val uri = Uri.parse("tel:" + Uri.encode(n))
        if (granted(Manifest.permission.CALL_PHONE)) launch(Intent(Intent.ACTION_CALL, uri))
        else launch(Intent(Intent.ACTION_DIAL, uri))
        return "📞 Calling $shown"
    }

    private fun openApp(name: String): String {
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        fun label(i: Int) = apps[i].loadLabel(pm).toString().lowercase()
        val idx = apps.indices.firstOrNull { label(it) == name }
            ?: apps.indices.firstOrNull { label(it).startsWith(name) }
            ?: apps.indices.firstOrNull { label(it).contains(name) }
            ?: throw NoSuchAppException("No installed app called \"$name\"")
        val intent = pm.getLaunchIntentForPackage(apps[idx].activityInfo.packageName)
            ?: throw Exception("Can't launch that app")
        launch(intent)
        return "Opening ${apps[idx].loadLabel(pm)}"
    }

    private fun installed(pkg: String) = ctx.packageManager.getLaunchIntentForPackage(pkg) != null

    private fun play(t: String): String {
        var q = t.removePrefix("play ").trim()
        val viaYoutube = q.contains("youtube")
        q = q.replace(Regex("\\b(on |in )?youtube( music)?\\b"), "").trim()
        if (q.isEmpty()) return openApp("youtube")

        if (!viaYoutube && installed("com.google.android.apps.youtube.music")) {
            launch(
                Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .setPackage("com.google.android.apps.youtube.music")
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .putExtra("query", q)
            )
            return "🎵 Playing \"$q\" on YouTube Music"
        }
        if (installed("com.google.android.youtube")) {
            launch(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q))
        } else {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(q))))
        }
        return "Searching YouTube for \"$q\""
    }
}
