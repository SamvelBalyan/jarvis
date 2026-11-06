package com.example.jarvis

import android.content.Context
import com.github.csukuangfj.piper.phonemize.piperPhonemizeInitialize
import com.github.csukuangfj.piper.phonemize.piperPhonemizeText
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig

/** Offline voices (Piper, run by sherpa-onnx). The built-in voice is in assets/piper; others are downloaded. */
object PiperTts {
    private var tts: OfflineTts? = null
    private var loadedId: String? = null
    private var phonemizerReady = false
    private val failed = HashSet<String>()

    @Synchronized
    private fun engine(ctx: Context, id: String): OfflineTts? {
        if (loadedId == id) tts?.let { return it }
        if (id in failed) return null
        return try {
            val app = ctx.applicationContext
            if (!phonemizerReady) {
                piperPhonemizeInitialize(app)
                phonemizerReady = true
            }
            try { tts?.release() } catch (_: Throwable) { }
            tts = null
            loadedId = null
            val e = if (id == Voices.BUILTIN) {
                val name = app.assets.list("piper")?.firstOrNull { it.endsWith(".onnx") }
                    ?: throw IllegalStateException("no model")
                OfflineTts(assetManager = app.assets, config = getOfflineTtsConfig(modelDir = "piper", modelName = name, numThreads = 2))
            } else {
                val d = Voices.dir(app, id)
                OfflineTts(config = getOfflineTtsConfig(modelDir = d.absolutePath, modelName = "$id.onnx", numThreads = 2))
            }
            tts = e
            loadedId = id
            e
        } catch (e: Throwable) {
            failed.add(id)
            null
        }
    }

    /** Returns PCM16 little-endian + sample rate, or null if anything fails. */
    fun speak(ctx: Context, text: String, id: String): Pair<ByteArray, Int>? {
        val t = engine(ctx, id) ?: return null
        return try {
            var lang = t.lang()
            if (lang.isEmpty()) lang = if (id.startsWith("en_GB")) "en-gb" else "en-us"
            val ph = piperPhonemizeText(text, lang) ?: return null
            val sentences = try { Array(ph.numSentences) { i -> ph.getPhonemes(i) } } finally { ph.close() }
            val audio = t.generateWithConfig("", GenerationConfig(speed = Voices.speed(), phonemeCodepoints = sentences))
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
