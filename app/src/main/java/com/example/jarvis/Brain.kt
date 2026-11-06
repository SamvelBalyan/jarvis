package com.example.jarvis

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
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

/**
 * Understands a spoken/typed command and carries it out.
 * Works from an Activity (button) or from a Service (Hey Jarvis).
 * [report] is always called on the main thread.
 */
class Brain(private val ctx: Context, private val report: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

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

    private fun cfg(key: String, default: String = ""): String =
        (prefs.getString(key, null) ?: "").trim().ifEmpty { default }

    private fun say(msg: String) {
        main.post { report(msg) }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- entry point

    fun handle(raw: String) {
        val t = Parsers.normalizeSpeech(raw).replace(" dot ", ".").trim().trimEnd('.', '!', '?')
        if (t.isEmpty()) return
        try {
            say("🎤 $raw\n→ " + route(t))
        } catch (e: Exception) {
            say("🎤 $raw\n⚠ ${e.message}")
        }
    }

    private fun route(t: String): String {
        val pcWord = Regex("\\b(pc|computer|laptop)\\b")
        val wake = Regex("(wake|turn on|power on|boot|start up).*\\b(pc|computer|laptop)\\b")
        if (wake.containsMatchIn(t)) {
            wakePc()
            return "Sending wake signal to the PC…"
        }
        if (pcWord.containsMatchIn(t)) return pcCommand(t)
        return phoneCommand(t)
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
        val port = cfg("port", "8765")
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
                say(
                    if (resp.optBoolean("ok")) "✅ PC: ${resp.optString("result")}"
                    else "⚠ PC: ${resp.optString("error")}"
                )
            } catch (e: Exception) {
                say("⚠ Can't reach PC (${e.message}). Is it awake and on the same Wi-Fi?")
            }
        }
        return "Sending '$action' to PC…"
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
            else -> throw Exception("Didn't understand that")
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
        return "Alarm %02d:%02d".format(a.hour, a.minute) +
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
        return "Timer set for ${m.value}"
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
        return "Calling $shown"
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
            return "Playing \"$q\" on YouTube Music"
        }
        if (installed("com.google.android.youtube")) {
            launch(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q))
        } else {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(q))))
        }
        return "Searching YouTube for \"$q\""
    }
}
