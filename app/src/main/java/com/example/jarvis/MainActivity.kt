package com.example.jarvis

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
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

class MainActivity : AppCompatActivity() {

    private lateinit var log: TextView
    private lateinit var brain: Brain
    private val fields = mutableMapOf<String, EditText>()
    private val prefs by lazy { getSharedPreferences("jarvis", Context.MODE_PRIVATE) }

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (text != null) brain.handle(text)
    }
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        brain = Brain(this) { say(it) }

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
                if (s.isNotBlank()) {
                    brain.handle(s)
                    typed.setText("")
                }
            }
        })

        root.addView(TextView(this).apply { text = "Hey Jarvis (always listening)"; textSize = 18f; setPadding(0, dp(12), 0, 0) })
        root.addView(Button(this).apply {
            text = "🟢  Start “Hey Jarvis”"
            setOnClickListener { startWake() }
        })
        root.addView(Button(this).apply {
            text = "⏹  Stop “Hey Jarvis”"
            setOnClickListener {
                stopService(Intent(this@MainActivity, WakeService::class.java))
                say("Hey Jarvis stopped")
            }
        })
        root.addView(Button(this).apply {
            text = "Allow display over other apps (needed to open apps)"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        })
        root.addView(Button(this).apply {
            text = "Allow running in background (battery)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })

        log = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
            text = "Try (say \"Jarvis\" first, or tap the mic):\n" +
                "• alarm at 7:30 am called gym\n• timer for 10 minutes\n• call mom\n" +
                "• open camera\n• play despacito\n• open google and search for cats\n" +
                "• remind me to call mom tomorrow at 5 pm\n• open far cry 5\n" +
                "• turn on pc\n• put pc to sleep"
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

        val wanted = mutableListOf(
            Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        val needed = wanted.filter { !granted(it) }
        if (needed.isNotEmpty()) permissionRequest.launch(needed.toTypedArray())
    }

    private fun startWake() {
        val need = mutableListOf<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) {
            permissionRequest.launch(need.toTypedArray())
            say("Allow the permissions, then tap Start again")
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, WakeService::class.java))
        say(
            "Hey Jarvis is starting (first start unpacks the voice model, ~20 s). " +
                "Look for the Jarvis notification." +
                if (Settings.canDrawOverlays(this)) "" else
                    "\nTip: allow \"display over other apps\" so Jarvis can open apps in the background."
        )
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
        try {
            speech.launch(i)
        } catch (e: ActivityNotFoundException) {
            say("⚠ No speech recognizer on this phone")
        }
    }
}
