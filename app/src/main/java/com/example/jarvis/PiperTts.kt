package com.example.jarvis

import android.content.Context
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig

/** Offline voice (Piper, run by sherpa-onnx). The model lives in assets/piper. Returns 16-bit PCM. */
object PiperTts {
    private var tts: OfflineTts? = null
    private var failed = false

    @Synchronized
    private fun engine(ctx: Context): OfflineTts? {
        tts?.let { return it }
        if (failed) return null
        return try {
            val app = ctx.applicationContext
            val name = app.assets.list("piper")?.firstOrNull { it.endsWith(".onnx") } ?: throw IllegalStateException("no model")
            piperPhonemizeInitialize(app)
            val cfg = getOfflineTtsConfig(modelDir = "piper", modelName = name, numThreads = 2)
            OfflineTts(assetManager = app.assets, config = cfg).also { tts = it }
        } catch (e: Throwable) {
            failed = true
            null
        }
    }

    fun available(ctx: Context) = engine(ctx) != null

    /** Returns PCM16 little-endian + sample rate, or null if anything fails. */
    fun speak(ctx: Context, text: String): Pair<ByteArray, Int>? {
        val t = engine(ctx) ?: return null
        return try {
            var lang = t.lang()
            if (lang.isEmpty()) lang = "en-gb"
            val ph = piperPhonemizeText(text, lang) ?: return null
            val sentences = try { Array(ph.numSentences) { i -> ph.getPhonemes(i) } } finally { ph.close() }
            val audio = t.generateWithConfig("", GenerationConfig(speed = Store.str("piperspeed", "1.0").toFloatOrNull() ?: 1.0f, phonemeCodepoints = sentences))
            val s = audio.samples
            if (s.isEmpty()) return null
            val out = ByteArray(s.size * 2)
            for (i in s.indices) {
                val v = (s[i].coerceIn(-1f, 1f) * 32767f).toInt()
                out[i * 2] = (v and 0xFF).toByte()
                out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            Pair(out, audio.sampleRate)
        } catch (e: Throwable) {
            null
        }
    }
}
