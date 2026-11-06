package com.example.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

/**
 * Always-on "Jarvis" listener, fully offline (Vosk).
 *
 * Stage 1 (cheap): a tiny recognizer that only knows the word "Jarvis".
 * Stage 2 (after the chime): normal speech recognition for ~7 seconds to catch your command.
 */
class WakeService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var recognizer: Recognizer? = null
    private var loading = false
    private var gen = 0
    private var inCommand = false
    private lateinit var brain: Brain
    private lateinit var overlay: JarvisOverlay

    // words the small recognizer may produce for "Jarvis"
    private val wakeWords = setOf("jarvis", "travis", "jarvas", "gervais", "jarvie")
    private val wakeGrammar = "[\"jarvis\", \"travis\", \"jarvas\", \"gervais\", \"jarvie\", \"[unk]\"]"

    private val finishRunnable = Runnable { finish(quiet = false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hey Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
        overlay = JarvisOverlay(this)
        brain = Brain(this) { msg -> onBrainReport(msg) }
        Store.wakeRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification("Loading voice model…")
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Android refused (e.g. restarted in the background). Open the app and switch it on again.
            Store.wakeRunning.value = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (model == null) loadModel() else if (speech == null) startWakeStage()
        return START_STICKY
    }

    private fun loadModel() {
        if (loading) return
        loading = true
        StorageService.unpack(
            this, "model-en-us", "model",
            { m: Model ->
                loading = false
                model = m
                startWakeStage()
            },
            { e: IOException ->
                loading = false
                update("Voice model failed: ${e.message}")
            }
        )
    }

    /** Stops the microphone and frees the recognizer (order matters for Vosk). */
    private fun stopSpeech() {
        try { speech?.stop() } catch (_: Exception) { }
        try { speech?.shutdown() } catch (_: Exception) { }
        try { recognizer?.close() } catch (_: Exception) { }
        speech = null
        recognizer = null
    }

    // ------------------------------------------------------------ stage 1: wait for "Jarvis"

    private fun startWakeStage() {
        val m = model ?: return
        inCommand = false
        val g = ++gen
        try {
            stopSpeech()
            val rec = Recognizer(m, 16000.0f, wakeGrammar)
            val s = SpeechService(rec, 16000.0f)
            recognizer = rec
            speech = s
            s.startListening(listener(g))
            update("Listening for “Jarvis”")
        } catch (e: Exception) {
            speech = null
            update("Microphone problem: ${e.message}. Retrying…")
            main.postDelayed({ if (gen == g) startWakeStage() }, 3000)
        }
    }

    private fun onWake() {
        if (inCommand) return
        inCommand = true
        val g = ++gen
        stopSpeech()
        if (Store.flag("sound")) Chime.play(Chime.Kind.WAKE)
        if (Store.flag("overlay")) overlay.show("Listening…")
        update("Listening…")
        main.postDelayed({ if (gen == g) startCommandStage(g) }, 320)
        scheduleFinish(10000)
    }

    // ------------------------------------------------------------ stage 2: hear the command

    private fun startCommandStage(g: Int) {
        val m = model ?: return
        try {
            stopSpeech()
            val rec = Recognizer(m, 16000.0f)
            val s = SpeechService(rec, 16000.0f)
            recognizer = rec
            speech = s
            s.startListening(listener(g), 7000)
        } catch (e: Exception) {
            update("Microphone problem: ${e.message}")
            finish(quiet = true)
        }
    }

    private fun handleCommand(text: String) {
        gen++ // ignore anything else the old recognizer still delivers
        stopSpeech()
        overlay.setText(text, "Working…")
        overlay.pulse()
        scheduleFinish(2600)
        brain.handle(text)
    }

    private fun onBrainReport(msg: String) {
        Store.addLog(msg)
        val lines = msg.split("\n")
        val heard = if (lines[0].startsWith("🎤")) lines[0].removePrefix("🎤").trim() else null
        val result = (if (heard != null) lines.drop(1).joinToString(" ") else msg).removePrefix("→").trim()
        update(result.ifEmpty { heard ?: "" })
        if (overlay.isShowing) {
            overlay.setText(heard ?: result, if (heard != null) result else null)
            overlay.pulse()
            scheduleFinish(if (msg.contains("🤖")) 8000 else 2600)
        }
        if (msg.contains("⚠")) errorSeen = true
    }

    private var errorSeen = false

    private fun scheduleFinish(ms: Long) {
        main.removeCallbacks(finishRunnable)
        main.postDelayed(finishRunnable, ms)
    }

    private fun finish(quiet: Boolean) {
        main.removeCallbacks(finishRunnable)
        val hadCommand = !quiet && gen > 0 && inCommand && speech == null
        if (hadCommand && Store.flag("sound")) {
            Chime.play(if (errorSeen) Chime.Kind.ERROR else Chime.Kind.DONE)
        }
        errorSeen = false
        overlay.hide()
        startWakeStage()
    }

    // ------------------------------------------------------------ speech callbacks

    private fun listener(g: Int) = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            if (g != gen) return
            val text = field(hypothesis, "partial")
            if (!inCommand) {
                if (text.split(" ").any { it in wakeWords }) onWake()
            } else if (text.isNotEmpty()) {
                overlay.setText(text, null)
                overlay.pulse()
            }
        }

        override fun onResult(hypothesis: String?) {
            if (g != gen) return
            val text = field(hypothesis, "text")
            if (text.isEmpty()) return
            if (!inCommand) {
                if (text.split(" ").any { it in wakeWords }) onWake()
            } else {
                handleCommand(text)
            }
        }

        override fun onFinalResult(hypothesis: String?) {}

        override fun onError(exception: Exception?) {
            if (g != gen) return
            update("Microphone problem: ${exception?.message}. Retrying…")
            main.postDelayed({ if (gen == g) startWakeStage() }, 3000)
        }

        override fun onTimeout() {
            if (g == gen && inCommand) finish(quiet = true)
        }
    }

    private fun field(json: String?, key: String): String {
        if (json == null) return ""
        return try {
            JSONObject(json).optString(key).trim()
        } catch (e: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------ notification

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis")
            .setContentText(text.lines().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        gen++
        stopSpeech()
        try { model?.close() } catch (_: Exception) { }
        model = null
        overlay.hide()
        Store.wakeRunning.value = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "jarvis_wake"
        private const val NOTIF_ID = 1
    }
}
