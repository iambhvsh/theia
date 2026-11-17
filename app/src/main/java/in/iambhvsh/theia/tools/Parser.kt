package `in`.iambhvsh.theia.tools

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class Call(val name: String, val args: JSONObject, val id: String? = null)

object Parser {
    // Tolerant: accepts a top-level array or an object holding the calls array.
    fun calls(raw: String): List<Call> {
        val root = try { JSONTokener(raw).nextValue() } catch (e: Exception) { return emptyList() }
        val arr: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> listOf("calls", "tool_calls", "function_calls", "tools")
                .firstNotNullOfOrNull { root.optJSONArray(it) } ?: return emptyList()
            else -> return emptyList()
        }
        val out = ArrayList<Call>()
        for (i in 0 until arr.length()) {
            var o = arr.optJSONObject(i) ?: continue
            o.optJSONObject("function")?.let { o = it }
            val name = o.optString("name")
            if (name.isEmpty()) continue
            val a = o.opt("arguments") ?: o.opt("args") ?: o.opt("parameters")
            val args = when (a) {
                is JSONObject -> a
                is String -> try { JSONObject(a) } catch (e: Exception) { JSONObject() }
                else -> JSONObject()
            }
            out += Call(name, args)
        }
        return out
    }
}
