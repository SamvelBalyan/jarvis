package com.example.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Optional "smart" layer: when the built-in rules don't understand a sentence, Google's free Gemini API
 * turns it into the same simple command lines the app already runs (and can answer questions).
 * The API key is typed in Settings and only ever stored on this phone.
 */
object Ai {
    class AiException(message: String) : Exception(message)

    data class Result(val commands: List<String>, val reply: String)

    private val history = ArrayList<Pair<String, String>>() // (what you said, what the AI answered as JSON)

    fun enabled(): Boolean = Store.str("aikey").isNotEmpty()

    private fun systemPrompt(): String {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date())
        val custom = Store.customCommands.joinToString(", ") { "\"" + it.phrase + "\"" }
        return """
You are Jarvis, a voice assistant inside an Android app. The user's words come from speech-to-text, so they may be misheard: infer what they meant.
Current local date and time: $now (timezone ${TimeZone.getDefault().id}).

Reply with ONLY a JSON object: {"commands": [...], "reply": "..."}

"commands" is a list (possibly empty) of command lines that the app will run in order. Every line must use exactly one of these forms, in lowercase:
- open <app or website name>
- open <app or website name> on pc
- open google and search for <text>
- search <text> on youtube
- search <text> on pc
- search <text> on youtube on pc
- set alarm for <h:mm am/pm> called <label>   (may end with: every day / every weekday / every monday)
- timer for <number> minutes   (or seconds / hours)
- remind me to <thing> tomorrow at <h:mm am/pm>   (or "on <month> <day>" / "on <weekday>" instead of tomorrow)
- add event <title> on <month> <day> at <h:mm am/pm>
- call <contact name>
- play <song or artist>
- navigate to <place>
- turn on pc / put pc to sleep / lock pc / restart pc / shut down pc
- flashlight on / flashlight off / volume up / volume down / battery
${if (custom.isNotEmpty()) "- or one of the user's own shortcuts: $custom" else ""}
Never invent other command forms. Several commands may be combined when the user asks for several things.

"reply" is a short, natural answer (at most two sentences), shown and read aloud to the user. For questions or chit-chat, put the answer in "reply" and leave "commands" empty. When you run commands, "reply" should be a brief confirmation, or empty. For live information you cannot know (weather, news, sports scores, prices), use a google search command instead of guessing.
""".trim()
    }


    /** Quick check of key, network and model name. Blocking: run off the main thread. */
    fun diagnose(): String {
        val key = Store.str("aikey")
        if (key.isEmpty()) return "⚠ Add your Gemini key in Settings first"
        val t0 = System.currentTimeMillis()
        try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.setRequestProperty("x-goog-api-key", key)
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            val ms = System.currentTimeMillis() - t0
            if (code !in 200..299) {
                val detail = try {
                    JSONObject(text).getJSONObject("error").optString("message")
                } catch (_: Exception) {
                    ""
                }
                return "⚠ Google answered $code after $ms ms: ${detail.take(120)}"
            }
            val arr = JSONObject(text).optJSONArray("models") ?: JSONArray()
            val usable = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val methods = o.optJSONArray("supportedGenerationMethods")
                var ok = false
                if (methods != null) for (j in 0 until methods.length()) if (methods.optString(j) == "generateContent") ok = true
                if (ok) usable.add(o.optString("name").removePrefix("models/"))
            }
            val bad = listOf("lite", "image", "tts", "live", "audio", "exp", "robotics", "computer", "embedding", "learnlm", "gemma", "vision")
            val flash = usable.filter { n -> n.contains("flash") && bad.none { n.contains(it) } }
                .sortedWith(compareBy<String>({ it.contains("preview") }, { it.length }))
            val current = Store.str("aimodel", "gemini-flash-latest")
            var note = ""
            if (current !in usable) {
                val pick = flash.firstOrNull() ?: usable.firstOrNull()
                if (pick != null) {
                    Store.putStr("aimodel", pick)
                    note = " Switched model to $pick."
                }
            }
            return "✅ Connected to Google in $ms ms (${usable.size} models available).$note"
        } catch (e: java.net.SocketTimeoutException) {
            val sec = (System.currentTimeMillis() - t0) / 1000
            return "⚠ Timed out after ${sec}s talking to Google. Your network may be blocking Google AI. Try mobile data or another Wi-Fi."
        } catch (e: java.net.UnknownHostException) {
            return "⚠ No internet connection (couldn't find Google's server)."
        } catch (e: Exception) {
            return "⚠ ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Blocking network call: run it off the main thread. */
    fun interpret(said: String): Result {
        val key = Store.str("aikey")
        if (key.isEmpty()) throw AiException("Add your free AI key in Settings → AI")
        val model = Store.str("aimodel", "gemini-flash-latest")

        val contents = JSONArray()
        for ((u, m) in history) {
            contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", u))))
            contents.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", m))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", said))))

        fun call(withThinking: Boolean): Pair<Int, String> {
            val gen = JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json")
            if (withThinking) {
                // Keep answers fast: Gemini 2.5 uses a token budget, newer models use a level.
                gen.put(
                    "thinkingConfig",
                    if (model.contains("2.5")) JSONObject().put("thinkingBudget", 0)
                    else JSONObject().put("thinkingLevel", "low")
                )
            }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt()))))
                .put("contents", contents)
                .put("generationConfig", gen)

            val c = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 45000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-goog-api-key", key)
            try {
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                val t = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                return code to t
            } catch (e: java.net.SocketTimeoutException) {
                throw AiException(
                    if (e.message?.contains("connect", ignoreCase = true) == true)
                        "Couldn't connect to Google's AI server (timed out). Try mobile data or another Wi-Fi, and use Test AI in Settings."
                    else "Google connected but didn't answer in time. Tap Test AI in Settings to pick a working model."
                )
            } catch (e: java.net.UnknownHostException) {
                throw AiException("No internet connection (couldn't find Google's server).")
            }
        }

        var (code, text) = call(true)
        if (code == 400 && text.contains("think", ignoreCase = true)) {
            val r = call(false) // this model doesn't accept our thinking setting
            code = r.first
            text = r.second
        }
        if (code !in 200..299) {
            val detail = try {
                JSONObject(text).getJSONObject("error").optString("message")
            } catch (_: Exception) {
                ""
            }
            throw AiException(
                when (code) {
                    429 -> "AI limit reached for now. Try again in a minute."
                    400, 401, 403 -> "AI key was rejected. Check it in Settings. ${detail.take(80)}"
                    404 -> "AI model “$model” not found. Check Settings → AI."
                    else -> "AI error $code ${detail.take(80)}"
                }
            )
        }

        val raw = try {
            JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
        } catch (_: Exception) {
            throw AiException("AI gave no answer")
        }

        val json = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val result = try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("commands")
            val cmds = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) cmds.add(s)
                }
            }
            Result(cmds, o.optString("reply").trim())
        } catch (_: Exception) {
            Result(emptyList(), raw.trim())
        }

        history.add(said to json)
        while (history.size > 3) history.removeAt(0)
        return result
    }
}
