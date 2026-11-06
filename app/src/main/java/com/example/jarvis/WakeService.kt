package com.example.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
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
 * Always-on "Hey Jarvis" listener. Runs fully offline with the Vosk speech model.
 * Say "Jarvis, open youtube" in one go, or say "Jarvis", wait for the beep, then the command.
 */
class WakeService : Service(), RecognitionListener {

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var loading = false
    private lateinit var brain: Brain
    private var awaitingUntil = 0L

    // words the recognizer sometimes produces for "Jarvis"
    private val wakeWords = setOf("jarvis", "jarvas", "jarvice", "gervais", "travis", "jarves")

    private val tone: ToneGenerator? by lazy {
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) } catch (e: Exception) { null }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hey Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
        brain = Brain(this) { msg -> update(msg) }
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
            // Android refused (e.g. restarted in the background). Open the app and tap Start again.
            stopSelf()
            return START_NOT_STICKY
        }
        if (model == null) loadModel() else if (speech == null) startListening()
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
                startListening()
            },
            { e: IOException ->
                loading = false
                update("Voice model failed: ${e.message}")
            }
        )
    }

    private fun startListening() {
        val m = model ?: return
        try {
            speech?.shutdown()
            val rec = Recognizer(m, 16000.0f)
            val s = SpeechService(rec, 16000.0f)
            s.startListening(this)
            speech = s
            update("Listening for “Hey Jarvis”")
        } catch (e: Exception) {
            speech = null
            update("Microphone problem: ${e.message}")
        }
    }

    // ------------------------------------------------------------ speech callbacks

    override fun onPartialResult(hypothesis: String?) {}

    override fun onResult(hypothesis: String?) {
        handleText(hypothesis)
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(exception: Exception?) {
        update("Microphone problem: ${exception?.message}. Retrying…")
        main.postDelayed({ startListening() }, 3000)
    }

    override fun onTimeout() {}

    private fun handleText(json: String?) {
        if (json == null) return
        val text = try {
            JSONObject(json).optString("text").trim()
        } catch (e: Exception) {
            return
        }
        if (text.isEmpty()) return

        val now = System.currentTimeMillis()
        val words = text.split(" ")
        val idx = words.indexOfFirst { it in wakeWords }
        if (idx >= 0) {
            val cmd = words.drop(idx + 1).joinToString(" ").trim()
            if (cmd.isNotEmpty()) {
                runCommand(cmd)
            } else {
                awaitingUntil = now + 8000
                beep()
                update("Yes? Say your command…")
            }
        } else if (now < awaitingUntil) {
            awaitingUntil = 0
            runCommand(text)
        }
    }

    private fun runCommand(cmd: String) {
        beep()
        brain.handle(cmd)
    }

    private fun beep() {
        try { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (_: Exception) { }
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
        try { speech?.shutdown() } catch (_: Exception) { }
        speech = null
        try { model?.close() } catch (_: Exception) { }
        model = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "jarvis_wake"
        private const val NOTIF_ID = 1
    }
}
