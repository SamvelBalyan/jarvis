package com.example.jarvis

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONArray
import org.json.JSONObject

/** A phrase you define, and the commands it runs (one per line, same words you would say). */
data class CustomCommand(val phrase: String, val actions: List<String>, val chip: Boolean)

/** Settings + shared state used by the app screen and the Hey Jarvis service. */
object Store {
    private var prefs: SharedPreferences? = null

    val log = mutableStateListOf<String>()
    val customCommands = mutableStateListOf<CustomCommand>()
    val wakeRunning = mutableStateOf(false)
    val pcOnline = mutableStateOf<Boolean?>(null)
    val downloading = mutableStateMapOf<String, Int>()
    val voiceTick = mutableStateOf(0)

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences("jarvis", Context.MODE_PRIVATE)
        loadCustom()
    }

    fun str(key: String, default: String = ""): String =
        (prefs?.getString(key, null) ?: "").trim().ifEmpty { default }

    fun putStr(key: String, value: String) {
        prefs?.edit()?.putString(key, value.trim())?.apply()
    }

    fun flag(key: String, default: Boolean = true): Boolean = prefs?.getBoolean(key, default) ?: default

    fun putFlag(key: String, value: Boolean) {
        prefs?.edit()?.putBoolean(key, value)?.apply()
    }

    fun addLog(msg: String) {
        log.add(0, msg)
        while (log.size > 60) log.removeAt(log.size - 1)
    }

    // ---------------------------------------------------------------- custom commands

    private fun loadCustom() {
        customCommands.clear()
        val raw = prefs?.getString("custom_commands", null) ?: return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val acts = o.getJSONArray("actions")
                customCommands.add(
                    CustomCommand(
                        o.getString("phrase"),
                        List(acts.length()) { acts.getString(it) },
                        o.optBoolean("chip", true)
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun saveCustom() {
        val arr = JSONArray()
        for (c in customCommands) {
            arr.put(
                JSONObject().put("phrase", c.phrase).put("actions", JSONArray(c.actions)).put("chip", c.chip)
            )
        }
        prefs?.edit()?.putString("custom_commands", arr.toString())?.apply()
    }

    fun upsertCommand(old: CustomCommand?, new: CustomCommand) {
        val i = if (old != null) customCommands.indexOf(old) else -1
        if (i >= 0) customCommands[i] = new else customCommands.add(new)
        saveCustom()
    }

    fun removeCommand(c: CustomCommand) {
        customCommands.remove(c)
        saveCustom()
    }

    private fun squeeze(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Finds a custom command whose phrase matches what was said. */
    fun findCustom(said: String): CustomCommand? {
        val plain = squeeze(said)
        val stripped = squeeze(said.removePrefix("run ").removePrefix("start ").removePrefix("activate "))
        for (c in customCommands) {
            val p = squeeze(Parsers.normalizeSpeech(c.phrase))
            if (p.isNotEmpty() && (plain == p || stripped == p)) return c
        }
        return null
    }
}
