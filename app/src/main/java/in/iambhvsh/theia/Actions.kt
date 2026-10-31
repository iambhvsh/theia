package `in`.iambhvsh.theia

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.AlarmClock
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

class Actions(private val a: Activity) {

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Returns a status line. [announce] is called before the app switch so Theia can speak first. */
    fun run(c: Call, announce: (String) -> Unit = {}): String = try {
        when (c.name) {
            "open_app" -> openApp(c.args.optString("name"), announce)
            "set_timer" -> timer(c.args.optInt("minutes", -1), c.args.optString("label"))
            "set_alarm" -> alarm(c.args.optInt("hour", -1), c.args.optInt("minute", -1), c.args.optString("label"))
            else -> "Unsupported tool: ${c.name}"
        }
    } catch (e: ActivityNotFoundException) {
        "No app can handle ${c.name}"
    } catch (e: SecurityException) {
        "Permission denied for ${c.name}"
    }

    private fun openApp(name: String, announce: (String) -> Unit): String {
        val pm = a.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(q, 0)
        val n = norm(name)
        if (n.isEmpty()) return "No app name"
        fun label(i: android.content.pm.ResolveInfo) = norm(i.loadLabel(pm).toString())
        val hit = apps.firstOrNull { label(it) == n }
            ?: apps.firstOrNull { label(it).startsWith(n) }
            ?: apps.firstOrNull { label(it).contains(n) }
            ?: return "App not found: $name"
        val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            ?: return "Cannot launch $name"
        val msg = "Opening ${hit.loadLabel(pm)}"
        announce(msg)
        a.startActivity(intent)
        return msg
    }

    private fun timer(min: Int, label: String): String {
        if (min < 1) return "Timer needs minutes"
        val secs = (min * 60).coerceAtMost(86400)
        a.startActivity(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, secs)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "Timer: $min min ${label}".trim()
    }

    private fun alarm(h: Int, m: Int, label: String): String {
        if (h !in 0..23 || m !in 0..59) return "Alarm needs a valid time"
        a.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        )
        return "Alarm %02d:%02d %s".format(h, m, label).trim()
    }

    fun hasFileAccess() = Environment.isExternalStorageManager()

    fun requestFileAccess() {
        a.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${a.packageName}"))
        )
    }

    /** Blocking; call off the UI thread. */
    fun searchFiles(query: String): List<File> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val root = Environment.getExternalStorageDirectory()
        val androidDir = File(root, "Android")
        val all = ArrayList<File>()
        for (f in root.walkTopDown().onEnter { !it.name.startsWith(".") && it != androidDir }) {
            if (f.isFile) all += f
            if (all.size > 200000) break
        }
        var hits = all.filter { f -> val n = f.name.lowercase(); tokens.all { n.contains(it) } }
        if (hits.isEmpty()) {
            val long = tokens.filter { it.length >= 3 }
            hits = all.filter { f -> val n = f.name.lowercase(); long.any { n.contains(it) } }
        }
        return hits.sortedByDescending { it.lastModified() }.take(20)
    }

    fun openFile(f: File) {
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", f)
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        try {
            a.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: ActivityNotFoundException) { /* no viewer installed */ }
    }
}
