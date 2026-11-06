package com.example.jarvis

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class PiperVoice(val id: String, val label: String)

/** The voices you can pick in Settings, plus helpers for downloading offline ones. */
object Voices {
    const val BUILTIN = "en_GB-alan-medium"
    const val SAMPLE = "Hi, this is how I sound."

    val gemini = listOf(
        "Charon", "Orus", "Fenrir", "Puck", "Kore", "Zephyr", "Leda", "Aoede", "Callirrhoe", "Autonoe",
        "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi",
        "Laomedeia", "Achernar", "Alnilam", "Schedar", "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi",
        "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat"
    )

    val piper = listOf(
        PiperVoice(BUILTIN, "British male – Alan (built in)"),
        PiperVoice("en_GB-northern_english_male-medium", "British male – Northern"),
        PiperVoice("en_GB-southern_english_male-medium", "British male – Southern"),
        PiperVoice("en_GB-alba-medium", "British female – Alba"),
        PiperVoice("en_GB-jenny_dioco-medium", "British female – Jenny"),
        PiperVoice("en_GB-southern_english_female-medium", "British female – Southern"),
        PiperVoice("en_US-ryan-medium", "American male – Ryan"),
        PiperVoice("en_US-joe-medium", "American male – Joe"),
        PiperVoice("en_US-john-medium", "American male – John"),
        PiperVoice("en_US-hfc_male-medium", "American male – HFC"),
        PiperVoice("en_US-lessac-medium", "American female – Lessac"),
        PiperVoice("en_US-amy-medium", "American female – Amy"),
        PiperVoice("en_US-hfc_female-medium", "American female – HFC"),
    )

    /** "gemini:Name", "piper:id" or "phone:". */
    fun current(): String {
        val v = Store.str("voice")
        if (v.isNotEmpty()) return v
        return when {
            Store.flag("naturalvoice") -> "gemini:" + Store.str("ttsvoice", "Charon")
            else -> "piper:$BUILTIN"
        }
    }

    fun speed(): Float = (Store.str("speed", "1.0").toFloatOrNull() ?: 1f).coerceIn(0.6f, 1.6f)

    fun dir(ctx: Context, id: String) = File(ctx.applicationContext.filesDir, "piper/$id")

    fun downloaded(ctx: Context, id: String): Boolean =
        id == BUILTIN || (File(dir(ctx, id), "$id.onnx").exists() && File(dir(ctx, id), "tokens.txt").exists())

    /** Downloads a Piper voice (about 60 MB). Blocking; progress 0..100. */
    fun download(ctx: Context, id: String, progress: (Int) -> Unit): Boolean {
        val d = dir(ctx, id)
        try {
            d.mkdirs()
            val base = "https://huggingface.co/csukuangfj/vits-piper-$id/resolve/main/"
            for (name in listOf("tokens.txt", "$id.onnx")) {
                val c = URL(base + name).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "Jarvis-Android")
                if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                val part = File(d, "$name.part")
                var done = 0L
                var last = -1
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0 && name.endsWith(".onnx")) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) { last = p; progress(p) }
                            }
                        }
                    }
                }
                val target = File(d, name)
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw Exception("could not save file")
            }
            return true
        } catch (e: Exception) {
            File(d, "$id.onnx").delete()
            File(d, "tokens.txt").delete()
            return false
        }
    }
}
