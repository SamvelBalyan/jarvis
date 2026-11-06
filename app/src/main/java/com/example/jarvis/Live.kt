package com.example.jarvis

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/** Questions the app answers itself from free live sources (no AI key needed): money, weather, time zones. */
object Live {
    sealed class Query {
        data class Money(val amount: Double, val from: String, val to: String) : Query()
        data class Weather(val city: String?, val day: Int) : Query()
        data class ZoneNow(val zone: ZoneId, val label: String) : Query()
        data class ZoneConvert(val h: Int, val m: Int, val from: ZoneId, val fromLabel: String, val to: ZoneId, val toLabel: String) : Query()
    }

    // ---------------------------------------------------------------- parsing (no network)

    fun parse(t: String): Query? = parseWeather(t) ?: parseMoney(t) ?: parseZones(t)

    private val moneyWords = mapOf(
        "dollar" to "USD", "dollars" to "USD", "usd" to "USD", "buck" to "USD", "bucks" to "USD",
        "euro" to "EUR", "euros" to "EUR", "eur" to "EUR",
        "dram" to "AMD", "drams" to "AMD", "amd" to "AMD",
        "ruble" to "RUB", "rubles" to "RUB", "rouble" to "RUB", "roubles" to "RUB", "rub" to "RUB",
        "pound" to "GBP", "pounds" to "GBP", "gbp" to "GBP",
        "lari" to "GEL", "gel" to "GEL",
        "lira" to "TRY", "liras" to "TRY",
        "yen" to "JPY", "jpy" to "JPY",
        "yuan" to "CNY", "cny" to "CNY",
        "rupee" to "INR", "rupees" to "INR", "inr" to "INR",
        "hryvnia" to "UAH", "hryvnias" to "UAH", "uah" to "UAH",
        "franc" to "CHF", "francs" to "CHF", "chf" to "CHF",
        "dirham" to "AED", "dirhams" to "AED", "aed" to "AED",
        "zloty" to "PLN", "zlotys" to "PLN", "pln" to "PLN",
        "tenge" to "KZT", "kzt" to "KZT",
        "cad" to "CAD", "aud" to "AUD",
    )

    // code -> (singular, plural)
    private val moneyNames = mapOf(
        "USD" to ("US dollar" to "US dollars"), "EUR" to ("euro" to "euros"),
        "AMD" to ("Armenian dram" to "Armenian drams"), "RUB" to ("Russian ruble" to "Russian rubles"),
        "GBP" to ("British pound" to "British pounds"), "GEL" to ("Georgian lari" to "Georgian lari"),
        "TRY" to ("Turkish lira" to "Turkish lira"), "JPY" to ("Japanese yen" to "Japanese yen"),
        "CNY" to ("Chinese yuan" to "Chinese yuan"), "INR" to ("Indian rupee" to "Indian rupees"),
        "UAH" to ("Ukrainian hryvnia" to "Ukrainian hryvnia"), "CHF" to ("Swiss franc" to "Swiss francs"),
        "AED" to ("UAE dirham" to "UAE dirhams"), "PLN" to ("Polish zloty" to "Polish zloty"),
        "KZT" to ("Kazakh tenge" to "Kazakh tenge"), "CAD" to ("Canadian dollar" to "Canadian dollars"),
        "AUD" to ("Australian dollar" to "Australian dollars"),
    )

    private fun parseMoney(t0: String): Query? {
        val s = t0.replace(",", "")
            .replace(Regex("\\b(us|american) dollars?\\b"), "usd")
            .replace(Regex("\\bcanadian dollars?\\b"), "cad")
            .replace(Regex("\\baustralian dollars?\\b"), "aud")
            .replace(Regex("\\barmenian drams?\\b"), "amd")
        val cur = "(" + moneyWords.keys.sortedByDescending { it.length }.joinToString("|") + ")"
        val num = "(\\d+(?:\\.\\d+)?)"
        Regex("how (?:many|much) $cur (?:is|are|in|do i get for|for) $num $cur\\b").find(s)?.let {
            return Query.Money(it.groupValues[2].toDouble(), moneyWords[it.groupValues[3]]!!, moneyWords[it.groupValues[1]]!!)
        }
        Regex("\\b$num $cur (?:is|in|to|into|equals?|equal to) (?:how (?:many|much) )?$cur\\b").find(s)?.let {
            return Query.Money(it.groupValues[1].toDouble(), moneyWords[it.groupValues[2]]!!, moneyWords[it.groupValues[3]]!!)
        }
        Regex("\\b(?:a|one) $cur (?:in|to|into) $cur\\b").find(s)?.let {
            return Query.Money(1.0, moneyWords[it.groupValues[1]]!!, moneyWords[it.groupValues[2]]!!)
        }
        return null
    }

    private val weatherRe = Regex("\\b(weather|forecast|temperature|rain|raining|snow|snowing|umbrella|windy|sunny)\\b")

    private fun parseWeather(t: String): Query? {
        if (!weatherRe.containsMatchIn(t)) return null
        val day = when {
            t.contains("day after tomorrow") -> 2
            t.contains("tomorrow") -> 1
            else -> 0
        }
        val base = t.replace(Regex("\\b(day after tomorrow|tomorrow|today|tonight|right now|now|please|like)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        var city = Regex("\\b(?:in|for|at) (.+)$").find(base)?.groupValues?.get(1)?.trim()
        if (city != null && (city.startsWith("the ") || city.isEmpty())) city = null
        return Query.Weather(city, day)
    }

    // ---- time zones

    private val aliases = mapOf(
        "usa" to "America/New_York", "america" to "America/New_York", "new york" to "America/New_York",
        "california" to "America/Los_Angeles", "texas" to "America/Chicago", "florida" to "America/New_York",
        "uk" to "Europe/London", "england" to "Europe/London", "britain" to "Europe/London",
        "armenia" to "Asia/Yerevan", "russia" to "Europe/Moscow", "germany" to "Europe/Berlin",
        "france" to "Europe/Paris", "italy" to "Europe/Rome", "spain" to "Europe/Madrid",
        "turkey" to "Europe/Istanbul", "japan" to "Asia/Tokyo", "china" to "Asia/Shanghai",
        "india" to "Asia/Kolkata", "georgia" to "Asia/Tbilisi", "iran" to "Asia/Tehran",
        "ukraine" to "Europe/Kyiv", "kiev" to "Europe/Kyiv", "canada" to "America/Toronto",
        "australia" to "Australia/Sydney", "brazil" to "America/Sao_Paulo", "uae" to "Asia/Dubai",
        "poland" to "Europe/Warsaw", "greece" to "Europe/Athens", "egypt" to "Africa/Cairo",
        "south korea" to "Asia/Seoul", "korea" to "Asia/Seoul", "mexico" to "America/Mexico_City",
        "las vegas" to "America/Los_Angeles", "san francisco" to "America/Los_Angeles",
        "seattle" to "America/Los_Angeles", "washington" to "America/New_York", "boston" to "America/New_York",
        "miami" to "America/New_York", "hong kong" to "Asia/Hong_Kong", "saint petersburg" to "Europe/Moscow",
        "st petersburg" to "Europe/Moscow", "beijing" to "Asia/Shanghai", "bangkok" to "Asia/Bangkok",
    )

    private val cityZones: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (id in ZoneId.getAvailableZoneIds()) {
            if (!id.contains('/')) continue
            val c = id.substringAfterLast('/').replace('_', ' ').lowercase(Locale.ROOT)
            if (!m.containsKey(c)) m[c] = id
        }
        m
    }

    private fun home(): ZoneId = TimeZone.getDefault().toZoneId()

    private fun zoneFor(raw: String): Pair<ZoneId, String>? {
        val n = raw.lowercase(Locale.ROOT).replace(Regex("\\b(the|time|my|current)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (n.isEmpty()) return null
        if (n == "me" || n == "here" || n == "home" || n == "local") return home() to "your time"
        val id = aliases[n] ?: cityZones[n] ?: return null
        val label = n.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        return ZoneId.of(id) to label
    }

    private fun hour24(h: Int, mer: String?): Int? {
        if (h > 23) return null
        return when (mer) {
            "pm" -> if (h < 12) h + 12 else h
            "am" -> if (h == 12) 0 else h
            else -> h
        }
    }

    private fun parseZones(t: String): Query? {
        Regex("(?:what(?:'s| is)? the time|what time is it|time) (?:right now )?(?:in|at) ([a-z .]+)$").find(t)?.let {
            zoneFor(it.groupValues[1])?.let { (z, l) -> return Query.ZoneNow(z, l) }
        }
        Regex("(?:when it'?s|if it'?s|when its|if its) (\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)? in ([a-z ]+?)(?: what time.*| for me.*| here.*| my time.*)?$").find(t)?.let {
            val h = hour24(it.groupValues[1].toInt(), it.groupValues[3].ifEmpty { null })
            val from = zoneFor(it.groupValues[4])
            if (h != null && from != null) {
                return Query.ZoneConvert(h, it.groupValues[2].ifEmpty { "0" }.toInt(), from.first, from.second, home(), "your time")
            }
        }
        Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s*(?:in |at )?([a-z .]+?)(?: time)?\\s+(?:in|to|into)\\s+([a-z .]+)$").find(t)?.let {
            val h = hour24(it.groupValues[1].toInt(), it.groupValues[3].ifEmpty { null })
            val from = zoneFor(it.groupValues[4])
            val to = zoneFor(it.groupValues[5])
            if (h != null && from != null && to != null) {
                return Query.ZoneConvert(h, it.groupValues[2].ifEmpty { "0" }.toInt(), from.first, from.second, to.first, to.second)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- answering (may use the network)

    fun answer(q: Query): String = when (q) {
        is Query.Money -> money(q)
        is Query.Weather -> weather(q)
        is Query.ZoneNow -> zoneNow(q)
        is Query.ZoneConvert -> zoneConvert(q)
    }

    private fun getJson(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Jarvis-Android")
        if (c.responseCode !in 200..299) throw Exception("HTTP ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().readText())
    }

    private val rateCache = HashMap<String, Pair<Long, JSONObject>>()

    private fun rates(from: String): JSONObject {
        val hit = rateCache[from]
        if (hit != null && System.currentTimeMillis() - hit.first < 3_600_000) return hit.second
        val j = getJson("https://open.er-api.com/v6/latest/$from")
        if (j.optString("result") != "success") throw Exception("rates unavailable")
        val r = j.getJSONObject("rates")
        rateCache[from] = System.currentTimeMillis() to r
        return r
    }

    private fun fmtAmount(v: Double): String =
        if (v == Math.floor(v) && v < 1e12) String.format(Locale.US, "%,d", v.toLong())
        else String.format(Locale.US, "%,.2f", v)

    private fun money(q: Query.Money): String {
        val rate = rates(q.from).optDouble(q.to, Double.NaN)
        if (rate.isNaN()) return "I don't have a rate for that currency."
        val res = q.amount * rate
        val resText = if (res >= 100) String.format(Locale.US, "%,.0f", res) else String.format(Locale.US, "%,.2f", res)
        val fromName = moneyNames[q.from]?.let { if (q.amount == 1.0) it.first else it.second } ?: q.from
        val toName = moneyNames[q.to]?.second ?: q.to
        return "${fmtAmount(q.amount)} $fromName is about $resText $toName."
    }

    private fun weatherText(code: Int): String = when (code) {
        0 -> "clear"
        1 -> "mostly clear"
        2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "foggy"
        51, 53, 55, 56, 57 -> "drizzly"
        61 -> "light rain"
        63 -> "rainy"
        65, 66, 67 -> "heavy rain"
        71, 73, 75, 77 -> "snowy"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95, 96, 99 -> "thunderstorms"
        else -> "unsettled"
    }

    private fun weather(q: Query.Weather): String {
        val place = q.city ?: Store.str("city", "Yerevan")
        val g = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name=" + URLEncoder.encode(place, "UTF-8"))
        val res = g.optJSONArray("results")
        if (res == null || res.length() == 0) return "I couldn't find a place called $place."
        val loc = res.getJSONObject(0)
        val name = loc.optString("name", place)
        val f = getJson(
            "https://api.open-meteo.com/v1/forecast?latitude=${loc.getDouble("latitude")}&longitude=${loc.getDouble("longitude")}" +
                "&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=3"
        )
        val d = f.getJSONObject("daily")
        val i = q.day.coerceIn(0, d.getJSONArray("time").length() - 1)
        val hi = Math.round(d.getJSONArray("temperature_2m_max").getDouble(i))
        val lo = Math.round(d.getJSONArray("temperature_2m_min").getDouble(i))
        val cond = weatherText(d.getJSONArray("weather_code").optInt(i))
        val rainArr = d.optJSONArray("precipitation_probability_max")
        val rain = if (rainArr != null && !rainArr.isNull(i)) rainArr.optInt(i) else -1
        val rainText = if (rain >= 0) ", with a $rain percent chance of rain" else ""
        return if (q.day == 0) {
            val cur = f.optJSONObject("current")
            val nowText = if (cur != null) {
                "In $name it's ${Math.round(cur.getDouble("temperature_2m"))} degrees and ${weatherText(cur.optInt("weather_code"))} right now. "
            } else "In $name: "
            "${nowText}Today's high is $hi, low $lo$rainText."
        } else {
            val label = if (q.day == 1) "Tomorrow" else "The day after tomorrow"
            "$label in $name: $cond, high $hi, low $lo$rainText."
        }
    }

    private fun fmtTime(z: ZonedDateTime): String {
        val s = z.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).uppercase(Locale.ENGLISH)
        return s.replace(":00", "")
    }

    private fun zoneNow(q: Query.ZoneNow): String {
        val now = ZonedDateTime.now(q.zone)
        val homeDate = LocalDate.now(home())
        val day = when {
            now.toLocalDate().isAfter(homeDate) -> ", which is tomorrow for you"
            now.toLocalDate().isBefore(homeDate) -> ", which is yesterday for you"
            else -> ""
        }
        return "It's ${fmtTime(now)} in ${q.label}$day."
    }

    private fun zoneConvert(q: Query.ZoneConvert): String {
        val src = ZonedDateTime.of(LocalDate.now(q.from), LocalTime.of(q.h, q.m.coerceIn(0, 59)), q.from)
        val dst = src.withZoneSameInstant(q.to)
        val day = when {
            dst.toLocalDate().isAfter(src.toLocalDate()) -> ", the next day"
            dst.toLocalDate().isBefore(src.toLocalDate()) -> ", the previous day"
            else -> ""
        }
        val from = if (q.fromLabel == "your time") "your time" else q.fromLabel
        return "${fmtTime(src)} in $from is ${fmtTime(dst)} in ${q.toLabel}$day."
    }
}
