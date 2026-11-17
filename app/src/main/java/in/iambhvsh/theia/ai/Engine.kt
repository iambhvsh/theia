package `in`.iambhvsh.theia.ai

import android.content.Context
import java.io.File

object Engine {
    init { System.loadLibrary("assistant") }

    private external fun nativeLoad(path: String): Int
    private external fun nativeModels(): Int
    private external fun nativeInit(tools: ByteArray): Int
    private external fun nativeTranscribe(pcm: FloatArray): ByteArray
    private external fun nativeComplete(text: ByteArray): ByteArray
    private external fun nativeReset()

    private var ready = false

    private val TOOLS = """
    [
     {"type":"function","function":{"name":"open_app","description":"Open an installed app by its name","parameters":{"type":"object","properties":{"name":{"type":"string","description":"app name"}},"required":["name"]}}},
     {"type":"function","function":{"name":"set_timer","description":"Start a countdown timer or reminder in minutes from now","parameters":{"type":"object","properties":{"minutes":{"type":"integer","description":"minutes from now"},"label":{"type":"string","description":"what to be reminded of"}},"required":["minutes"]}}},
     {"type":"function","function":{"name":"set_alarm","description":"Set an alarm or reminder at a clock time","parameters":{"type":"object","properties":{"hour":{"type":"integer","description":"hour 0-23"},"minute":{"type":"integer","description":"minute 0-59"},"label":{"type":"string","description":"what to be reminded of"}},"required":["hour","minute"]}}},
     {"type":"function","function":{"name":"search_files","description":"Find files on the phone by name","parameters":{"type":"object","properties":{"query":{"type":"string","description":"words in the file name"}},"required":["query"]}}}
    ]
    """.trimIndent()

    @Synchronized
    fun ensureReady(ctx: Context) {
        if (ready) return
        val files = listOf("whistle.cact", "needle3.cact").map { name ->
            val f = File(ctx.filesDir, name)
            if (!f.exists()) {
                val tmp = File(ctx.filesDir, "$name.tmp")
                ctx.assets.open(name).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                tmp.renameTo(f)
            }
            f
        }
        files.forEach { nativeLoad(it.absolutePath) }
        val m = nativeModels()
        check(m == 3) { "engine reports models=$m, expected 3 (text+speech)" }
        nativeInit(TOOLS.toByteArray(Charsets.UTF_8))
        ready = true
    }

    @Synchronized
    fun transcribe(pcm: FloatArray): String =
        String(nativeTranscribe(pcm), Charsets.UTF_8)

    @Synchronized
    fun complete(text: String): String {
        nativeReset()
        return String(nativeComplete(text.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)
    }
}
