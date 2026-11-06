package com.example.jarvis

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Short pleasant sounds generated in code (no sound files needed). */
object Chime {
    enum class Kind { WAKE, DONE, ERROR }

    private const val SR = 24000
    private val cache = HashMap<Kind, ShortArray>()
    private val main = Handler(Looper.getMainLooper())

    private fun notes(kind: Kind): List<Pair<Double, Int>> = when (kind) {
        Kind.WAKE -> listOf(784.0 to 90, 1175.0 to 200)   // rising "ding-ding"
        Kind.DONE -> listOf(1175.0 to 80, 880.0 to 160)   // falling "ding-dong"
        Kind.ERROR -> listOf(330.0 to 140, 262.0 to 240)  // low "bom-bom"
    }

    private fun synth(kind: Kind): ShortArray {
        val parts = notes(kind)
        var total = 0
        for (p in parts) total += p.second * SR / 1000
        val out = ShortArray(total)
        var pos = 0
        for ((freq, ms) in parts) {
            val n = ms * SR / 1000
            for (i in 0 until n) {
                val t = i.toDouble() / SR
                val attack = min(1.0, i / (SR * 0.006))
                val decay = exp(-5.0 * i / n)
                val s = sin(2 * PI * freq * t) + 0.25 * sin(4 * PI * freq * t)
                out[pos + i] = (s * attack * decay * 0.38 * Short.MAX_VALUE).toInt().toShort()
            }
            pos += n
        }
        return out
    }

    fun play(kind: Kind) {
        try {
            val pcm = cache.getOrPut(kind) { synth(kind) }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            main.postDelayed({
                try { track.release() } catch (_: Exception) { }
            }, pcm.size * 1000L / SR + 400L)
        } catch (_: Exception) {
        }
    }
}
