package com.example.exp1

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Sends SMS via the same Apps Script web app used for invite/task emails (it forwards to Semaphore). */
object SmsGateway {
    private const val TAG = "SmsGateway"
    // Same URL + secret as TASK_EMAIL_APPS_SCRIPT_* in ScheduleActivity.
    private const val SCRIPT_URL =
        "https://script.google.com/macros/s/AKfycbx-_H4Jy4KTuZQSPTMCxTAIKIAJxGMAaIzGF-uKB0m05YLWb1Flgdor-wGD-ieOym_0/exec"
    private const val SCRIPT_SECRET = "Red0455"
    private val executor = Executors.newSingleThreadExecutor()

    /** Any common PH mobile format -> "09XXXXXXXXX", or null if it isn't one. */
    @JvmStatic
    fun normalizePhone(raw: String?): String? {
        val d = raw?.filter { it.isDigit() } ?: return null
        return when {
            d.length == 11 && d.startsWith("09") -> d
            d.length == 12 && d.startsWith("639") -> "0" + d.substring(2)
            d.length == 10 && d.startsWith("9") -> "0$d"
            else -> null
        }
    }

    fun send(numbers: Collection<String?>, message: String, priority: Boolean = false) {
        val clean = numbers.mapNotNull { normalizePhone(it) }.distinct()
        if (clean.isEmpty()) return
        val text = message
            .replace('\u2014', '-').replace('\u2013', '-')
            .replace('\u2018', '\'').replace('\u2019', '\'')
            .replace('\u201C', '"').replace('\u201D', '"')
            .take(300)
        executor.execute {
            try {
                val conn = (URL(SCRIPT_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    doOutput = true
                    connectTimeout = 25000
                    readTimeout = 25000
                }
                val payload = JSONObject().apply {
                    put("secret", SCRIPT_SECRET)
                    put("type", "sms")
                    put("numbers", JSONArray(clean))
                    put("message", text)
                    put("priority", priority)
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                Log.d(TAG, "SMS to ${clean.size} number(s) -> $code $body")
            } catch (e: Exception) {
                Log.e(TAG, "SMS failed: ${e.message}")
            }
        }
    }
}
