package `in`.iambhvsh.theia.ai

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import `in`.iambhvsh.theia.data.Message
import `in`.iambhvsh.theia.tools.Call
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Groq {
    private const val TAG = "Groq"
    private const val PREFS = "groq_prefs"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    const val DEFAULT_MODEL = "qwen/qwen3.8-27b"
    const val FAST_MODEL = "openai/gpt-oss-20b"

    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    data class Response(
        val content: String?,
        val toolCalls: List<Call>,
        val rawToolCallsJson: String?
    )

    fun getApiKey(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_API_KEY, null)?.trim()
            ?: prefs.getString("groq_api_key", null)?.trim()
            ?: ""
    }

    fun setApiKey(ctx: Context, key: String) {
        val trimmed = key.trim()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, trimmed)
            .putString("groq_api_key", trimmed)
            .apply()
    }

    fun clearApiKey(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_API_KEY)
            .remove("groq_api_key")
            .apply()
    }

    fun hasApiKey(ctx: Context): Boolean = getApiKey(ctx).isNotEmpty()

    fun getModel(ctx: Context): String {
        val m = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        if (m.contains("llama") || m.contains("120b") || m.isEmpty()) {
            return DEFAULT_MODEL
        }
        return m
    }

    fun setModel(ctx: Context, model: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL, model.trim()).apply()
    }

    private val TOOLS = JSONArray("""
    [
      {
        "type": "function",
        "function": {
          "name": "open_app",
          "description": "Open an installed Android app on the device by its name (e.g. YouTube, Spotify, Camera, Settings, Chrome, Maps, Instagram, Twitter)",
          "parameters": {
            "type": "object",
            "properties": {
              "name": {
                "type": "string",
                "description": "The exact or common name of the app to launch"
              }
            },
            "required": ["name"]
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "open_play_store",
          "description": "Open Google Play Store to install, download, or search for an app (e.g. Twitter, Netflix, Duolingo)",
          "parameters": {
            "type": "object",
            "properties": {
              "query": {
                "type": "string",
                "description": "The name of the app to search or download on Google Play Store"
              }
            },
            "required": ["query"]
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "open_url",
          "description": "Open a website URL in the device web browser (e.g. https://x.com, https://twitter.com, https://reddit.com)",
          "parameters": {
            "type": "object",
            "properties": {
              "url": {
                "type": "string",
                "description": "The website URL to open"
              }
            },
            "required": ["url"]
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "search_web",
          "description": "Search Google on the web for information or queries",
          "parameters": {
            "type": "object",
            "properties": {
              "query": {
                "type": "string",
                "description": "Search query keywords"
              }
            },
            "required": ["query"]
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "set_timer",
          "description": "Start a countdown timer or reminder in minutes from now",
          "parameters": {
            "type": "object",
            "properties": {
              "minutes": {
                "type": "integer",
                "description": "Duration in minutes"
              },
              "label": {
                "type": "string",
                "description": "What the timer is for (e.g. tea, pasta, workout)"
              }
            },
            "required": ["minutes"]
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "set_alarm",
          "description": "Set an alarm for a specific hour and minute (24-hour format)",
          "parameters": {
            "type": "object",
            "properties": {
              "hour": {
                "type": "integer",
                "description": "Hour of the alarm (0-23)"
              },
              "minute": {
                "type": "integer",
                "description": "Minute of the alarm (0-59)"
              },
              "label": {
                "type": "string",
                "description": "Label or description for the alarm"
              }
            },
            "required": ["hour", "minute"]
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "control_flashlight",
          "description": "Turn the device flashlight or torch on or off (e.g. lumos, torch on, turn off flashlight)",
          "parameters": {
            "type": "object",
            "properties": {
              "enable": {
                "type": "boolean",
                "description": "True to turn on flashlight, False to turn off"
              }
            },
            "required": ["enable"]
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "adjust_volume",
          "description": "Adjust or set device media volume, or mute audio",
          "parameters": {
            "type": "object",
            "properties": {
              "direction": {
                "type": "string",
                "enum": ["up", "down"],
                "description": "Turn volume up or down"
              },
              "percent": {
                "type": "integer",
                "description": "Set volume to an exact percentage from 0 to 100"
              },
              "mute": {
                "type": "boolean",
                "description": "Set to true to mute audio"
              }
            }
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "open_settings",
          "description": "Open a device system settings panel (wifi, bluetooth, sound, display, apps, battery, general)",
          "parameters": {
            "type": "object",
            "properties": {
              "setting": {
                "type": "string",
                "enum": ["wifi", "bluetooth", "display", "sound", "apps", "battery", "general"],
                "description": "The specific setting to open"
              }
            },
            "required": ["setting"]
          }
        }
      },
      {
        "type": "function",
        "function": {
          "name": "search_files",
          "description": "Search local files stored on the device storage by name or keyword",
          "parameters": {
            "type": "object",
            "properties": {
              "query": {
                "type": "string",
                "description": "File name or search keywords"
              }
            },
            "required": ["query"]
          }
        }
      }
    ]
    """.trimIndent())

    fun getSystemContext(ctx: Context): String {
        return try {
            val now = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.US).format(Date())
            val tz = TimeZone.getDefault().id

            val bIntent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = bIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val chargeStatus = if (isCharging) "charging" else "not charging"

            val model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
            val os = "Android ${Build.VERSION.RELEASE}"

            """
            DEVICE & SYSTEM STATUS:
            - Current Date & Time: $now ($tz)
            - Device: $model running $os
            - Battery Level: ${if (batteryPct >= 0) "$batteryPct%" else "Unknown"} ($chargeStatus)
            """.trimIndent()
        } catch (_: Exception) {
            val now = SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.US).format(Date())
            "DEVICE & SYSTEM STATUS:\n- Current Date & Time: $now"
        }
    }

    /**
     * Sends prompt and conversation history to Groq chat completions API.
     * Blocks on the calling thread (should be invoked on a background thread).
     */
    fun complete(
        ctx: Context,
        history: List<Message>,
        userPrompt: String
    ): Response {
        val apiKey = getApiKey(ctx)
        if (apiKey.isEmpty()) {
            throw IllegalStateException("No Groq API key configured")
        }

        val messages = JSONArray()
        val sysContext = getSystemContext(ctx)

        // System prompt
        val sysMsg = JSONObject().apply {
            put("role", "system")
            put("content", """
                You are Theia, an exceptionally intelligent, proactive, articulate, and natural voice AI assistant running natively on Android.
                You are not a shallow search engine or a robotic command parser; you are Theia: deeply capable, perceptive, clear-thinking, and insightful across every topic from science, math, coding, philosophy, and history to daily device management.

                $sysContext

                CORE HARDWARE & TOOL CAPABILITIES:
                - `open_app`: launch an application installed on the device.
                - `open_play_store`: search and install apps directly from Google Play Store.
                - `open_url`: open any web link or website directly in the browser.
                - `search_web`: search Google on the web for live facts, current events, news, or lookups.
                - `control_flashlight`: turn on or off the device flashlight/torch (e.g. "turn on flashlight", "lumos", "luminous", "Knox", "turn off torch").
                - `adjust_volume`: change media volume up/down, set specific percentage (0-100), or mute.
                - `open_settings`: open device system settings (wifi, bluetooth, sound, display, apps, battery, general).
                - `set_timer`: set countdown timers with minutes and labels.
                - `set_alarm`: set clock alarms for specific times.
                - `search_files`: locate local documents, photos, and files on storage.

                TOOL CALLING CONVENTION:
                - When calling tools, you MUST use the EXACT tool function name as defined: `open_app`, `open_play_store`, `open_url`, `search_web`, `control_flashlight`, `adjust_volume`, `open_settings`, `set_timer`, `set_alarm`, `search_files`. Never insert spaces or alter function names.

                BEHAVIOR, SPEECH & INTELLIGENCE PRINCIPLES:
                - STRICT SPOKEN BREVITY: You communicate exclusively via voice TTS directly into the user's ear. ALWAYS keep your responses strictly to 1 or 2 concise, punchy spoken sentences (under 35 words).
                - STRICTLY FORBIDDEN: NEVER write multiple paragraphs, bullet points, numbered lists, markdown, or lengthy essays. Even for complex science, philosophy, history, or explanations, provide a brilliant, direct, intuitive summary in 1 or 2 sentences max.
                - PURE SPOKEN TEXT: Everything you return is spoken aloud directly by Google Text-to-Speech. NEVER use markdown symbols, asterisks (*, **), bullet characters (•, -), hashes (#), emojis, or code fences. Speak numbers, dates, and times naturally.
                - PROACTIVE PROBLEM SOLVING: If an app is not installed, inform the user pleasantly and offer alternatives (Play Store download or website). If a command is ambiguous, clarify smartly.
                - REAL-WORLD GROUNDING: You know the current time, date, battery level, and device from your environment context. When asked for time, date, battery, or device info, use this context directly with total accuracy.
                - MULTI-TURN MEMORY: You track the flow of conversation, maintaining context across turns and seamlessly resolving pronouns like "it", "that", "do it", or "open the first one".
            """.trimIndent())
        }
        messages.put(sysMsg)

        // Append historical context from database
        for ((_, _, role, content, toolCallsJson, toolCallId) in history) {
            val msgObj = JSONObject()
            msgObj.put("role", role)
            when (role) {
                "user" -> {
                    msgObj.put("content", content ?: "")
                    messages.put(msgObj)
                }
                "assistant" -> {
                    if (!toolCallsJson.isNullOrBlank()) {
                        if (!content.isNullOrBlank()) {
                            msgObj.put("content", content)
                        } else {
                            msgObj.put("content", JSONObject.NULL)
                        }
                        msgObj.put("tool_calls", JSONArray(toolCallsJson))
                    } else {
                        msgObj.put("content", content ?: "")
                    }
                    messages.put(msgObj)
                }
                "tool" -> {
                    val tid = toolCallId ?: ""
                    if (tid.isNotEmpty()) {
                        msgObj.put("tool_call_id", tid)
                        msgObj.put("content", content ?: "")
                        messages.put(msgObj)
                    }
                }
            }
        }

        // Add current user prompt if provided
        if (userPrompt.isNotBlank()) {
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", userPrompt)
            })
        }

        val model = getModel(ctx)
        val reqBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("tools", TOOLS)
            put("tool_choice", "auto")
            if (model.contains("gpt-oss")) {
                put("reasoning_effort", "low")
            }
            put("temperature", 0.6)
            put("max_tokens", 90)
        }

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(reqBody.toString())
                writer.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseBody = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

            if (code !in 200..299) {
                Log.e(TAG, "Groq API error HTTP $code: $responseBody")
                val rescued = tryParseFailedToolCall(responseBody)
                if (rescued != null) {
                    return rescued
                }
                // Automatic fallback on 429 rate limit to FAST_MODEL
                if (code == 429 && model != FAST_MODEL) {
                    Log.w(TAG, "Rate limit hit on $model, attempting automatic fallback to $FAST_MODEL")
                    reqBody.put("model", FAST_MODEL)
                    val fbConn = URL(ENDPOINT).openConnection() as HttpURLConnection
                    try {
                        fbConn.requestMethod = "POST"
                        fbConn.connectTimeout = 15000
                        fbConn.readTimeout = 25000
                        fbConn.setRequestProperty("Authorization", "Bearer $apiKey")
                        fbConn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        fbConn.setRequestProperty("Accept", "application/json")
                        fbConn.doOutput = true
                        OutputStreamWriter(fbConn.outputStream, Charsets.UTF_8).use { w ->
                            w.write(reqBody.toString())
                            w.flush()
                        }
                        val fbCode = fbConn.responseCode
                        val fbStream = if (fbCode in 200..299) fbConn.inputStream else fbConn.errorStream
                        val fbBody = BufferedReader(InputStreamReader(fbStream, Charsets.UTF_8)).use { it.readText() }
                        if (fbCode in 200..299) {
                            return parseResponse(fbBody)
                        }
                    } finally {
                        fbConn.disconnect()
                    }
                }
                throw IOException("Groq error ($code): $responseBody")
            }

            return parseResponse(responseBody)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(responseBody: String): Response {
        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices") ?: JSONArray()
        if (choices.length() == 0) {
            return Response(content = null, toolCalls = emptyList(), rawToolCallsJson = null)
        }

        val choice = choices.getJSONObject(0)
        val message = choice.optJSONObject("message") ?: JSONObject()
        val content = if (message.isNull("content")) null else message.optString("content").trim()
        val rawToolCalls = message.optJSONArray("tool_calls")

        val toolCallsList = ArrayList<Call>()
        var toolCallsJsonString: String? = null

        if (rawToolCalls != null && rawToolCalls.length() > 0) {
            toolCallsJsonString = rawToolCalls.toString()
            for (i in 0 until rawToolCalls.length()) {
                val tc = rawToolCalls.getJSONObject(i)
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val id = tc.optString("id").ifEmpty { null }
                val args = when (val a = fn.opt("arguments")) {
                    is JSONObject -> a
                    is String -> try { JSONObject(a) } catch (_: Exception) { JSONObject() }
                    else -> JSONObject()
                }
                toolCallsList.add(Call(name, args, id))
            }
        }

        return Response(
            content = content,
            toolCalls = toolCallsList,
            rawToolCallsJson = toolCallsJsonString
        )
    }

    private fun tryParseFailedToolCall(errJson: String): Response? {
        try {
            val root = JSONObject(errJson)
            val error = root.optJSONObject("error") ?: return null
            val failedGen = error.optString("failed_generation")
            if (failedGen.isNullOrBlank()) return null

            val fnMatch = Regex("<function=([a-zA-Z0-9_ ]+)>").find(failedGen) ?: return null
            var fnName = fnMatch.groupValues[1].trim().replace(" ", "_").lowercase()
            if (fnName == "control_flash_light") fnName = "control_flashlight"
            if (fnName == "play_store" || fnName == "open_playstore") fnName = "open_play_store"
            if (fnName == "open_web" || fnName == "open_browser") fnName = "open_url"

            val args = JSONObject()
            val paramMatches = Regex("<parameter(?:=|name=|\"|'|[\\t ])*([a-zA-Z0-9_]+)[\"']?>([\\s\\S]*?)</parameter>").findAll(failedGen)
            for (pm in paramMatches) {
                var pName = pm.groupValues[1].trim()
                val pVal = pm.groupValues[2].trim()
                if (pName.isEmpty()) {
                    if (fnName == "open_app") pName = "name"
                    else if (fnName == "search_files") pName = "query"
                }
                when {
                    pVal.equals("true", ignoreCase = true) -> args.put(pName, true)
                    pVal.equals("false", ignoreCase = true) -> args.put(pName, false)
                    pVal.toIntOrNull() != null -> args.put(pName, pVal.toInt())
                    else -> args.put(pName, pVal)
                }
            }

            if (fnName == "open_app" && !args.has("name")) {
                val valMatch = Regex("<parameter[\\s\\S]*?>([\\s\\S]*?)</parameter>").find(failedGen)
                if (valMatch != null) {
                    args.put("name", valMatch.groupValues[1].trim())
                }
            }

            Log.d(TAG, "Successfully rescued failed tool call from Groq: $fnName args=$args")
            val toolCalls = listOf(Call(fnName, args, "rescued_" + System.currentTimeMillis()))
            return Response(
                content = null,
                toolCalls = toolCalls,
                rawToolCallsJson = JSONArray().put(JSONObject().apply {
                    put("id", toolCalls[0].id)
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", fnName)
                        put("arguments", args.toString())
                    })
                }).toString()
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Could not rescue failed tool call", e)
            return null
        }
    }
}
