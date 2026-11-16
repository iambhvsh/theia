package `in`.iambhvsh.theia

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object Groq {
    private const val TAG = "Groq"
    private const val PREFS = "groq_prefs"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    const val DEFAULT_MODEL = "qwen/qwen3.8-27b"
    const val FAST_MODEL = "qwen/qwen3.8-27b"

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
        if (m.contains("llama-3.3") || m.contains("llama-3.1") || m.contains("llama3-") || m == "openai/gpt-oss-20b") {
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

        // System prompt instructing conversational brilliance, empathy, and tool coordination
        val sysMsg = JSONObject().apply {
            put("role", "system")
            put("content", """
                You are Theia, a brilliant, warm, proactive, and natural AI assistant running natively on the user's Android device.
                You are not a generic robot; you are Theia: insightful, perceptive, articulate, and genuinely helpful.

                REAL-TIME CAPABILITIES & TOOLS:
                - `open_app`: launch an application installed on the device.
                - `open_play_store`: search and install apps directly from Google Play Store.
                - `open_url`: open any web link or website directly in the browser.
                - `search_web`: search Google on the web for live information, current events, or web queries.
                - `set_timer`: set countdown timers with minutes and labels.
                - `set_alarm`: set clock alarms for specific times.
                - `search_files`: locate local documents, photos, and files on storage.

                BEHAVIOR & CONVERSATIONAL INTELLIGENCE:
                - Speak naturally, warmly, and concisely in 1 to 2 spoken sentences.
                - NEVER output raw status messages, error codes, JSON, bullet points, asterisks, bold text, emojis, or markdown, because every word you generate is spoken directly aloud to the user via TTS.
                - When the user asks to open or use an app, always attempt `open_app(name="...")`.
                - If `open_app` indicates an app is not installed, DO NOT regurgitate system errors. Offer smart, thoughtful assistance: let the user know it is not currently on their device, and ask if they would like you to open the Play Store to download it, or open its website in the browser.
                - When following up on user answers (e.g., "yes", "open play store", "download it", "open website", "check web"), immediately call the appropriate tool (`open_play_store` or `open_url`).
                - Understand conversational context and pronouns ("it", "that", "the first one", "do it").
                - For general questions, thoughts, knowledge, calculations, or casual conversation, respond with wit, clarity, and genuine intelligence.
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
            put("max_tokens", 300)
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
                throw IOException("Groq error ($code): $responseBody")
            }

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
        } finally {
            conn.disconnect()
        }
    }

    private fun tryParseFailedToolCall(errJson: String): Response? {
        try {
            val root = JSONObject(errJson)
            val error = root.optJSONObject("error") ?: return null
            val failedGen = error.optString("failed_generation")
            if (failedGen.isNullOrBlank()) return null

            val fnMatch = Regex("<function=([a-zA-Z0-9_]+)>").find(failedGen) ?: return null
            val fnName = fnMatch.groupValues[1]

            val args = JSONObject()
            val paramMatches = Regex("<parameter[\\t ]*([a-zA-Z0-9_]*)>([\\s\\S]*?)</parameter>").findAll(failedGen)
            for (pm in paramMatches) {
                var pName = pm.groupValues[1].trim()
                val pVal = pm.groupValues[2].trim()
                if (pName.isEmpty()) {
                    if (fnName == "open_app") pName = "name"
                    else if (fnName == "search_files") pName = "query"
                }
                args.put(pName, pVal)
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
