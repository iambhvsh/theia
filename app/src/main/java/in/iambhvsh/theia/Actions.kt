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
            "open_app" -> openApp(c.args.optString("name").ifEmpty { c.args.optString("app") }, announce)
            "open_play_store" -> openPlayStore(c.args.optString("query").ifEmpty { c.args.optString("name").ifEmpty { c.args.optString("app") } }, announce)
            "open_url" -> openUrl(c.args.optString("url").ifEmpty { c.args.optString("query").ifEmpty { c.args.optString("name") } }, announce)
            "search_web" -> searchWeb(c.args.optString("query").ifEmpty { c.args.optString("q") }, announce)
            "set_timer" -> timer(c.args.optInt("minutes", -1), c.args.optString("label"))
            "set_alarm" -> alarm(c.args.optInt("hour", -1), c.args.optInt("minute", -1), c.args.optString("label"))
            else -> "Unsupported tool: ${c.name}"
        }
    } catch (e: ActivityNotFoundException) {
        "No app can handle ${c.name}"
    } catch (e: SecurityException) {
        "Permission denied for ${c.name}"
    }

    fun openApp(name: String, announce: (String) -> Unit = {}): String {
        val pm = a.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(q, 0)
        val n = norm(name)
        if (n.isEmpty()) return "No app name"
        fun label(i: android.content.pm.ResolveInfo) = norm(i.loadLabel(pm).toString())
        fun pkg(i: android.content.pm.ResolveInfo) = i.activityInfo.packageName.lowercase()

        val searchTargets = when (n) {
            "twitter" -> listOf("twitter", "x")
            "x" -> listOf("x", "twitter")
            "playstore", "googleplay", "googleplaystore" -> listOf("playstore", "googleplay", "vending")
            else -> listOf(n)
        }

        var hit: android.content.pm.ResolveInfo? = null
        for (target in searchTargets) {
            if (target == "x") {
                // Exact label match or twitter package only; never substring match "x"
                hit = apps.firstOrNull { label(it) == "x" || pkg(it) == "com.twitter.android" }
            } else if (target == "twitter") {
                hit = apps.firstOrNull { label(it) == "twitter" || pkg(it) == "com.twitter.android" }
                    ?: apps.firstOrNull { label(it).startsWith("twitter") }
                    ?: apps.firstOrNull { label(it).contains("twitter") }
            } else {
                hit = apps.firstOrNull { label(it) == target }
                    ?: apps.firstOrNull { label(it).startsWith(target) }
                    ?: apps.firstOrNull { target.length >= 3 && label(it).contains(target) }
            }
            if (hit != null) break
        }

        if (hit == null) {
            return "App '$name' is not installed on this device."
        }

        val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            ?: return "Cannot launch $name"
        val msg = "Opening ${hit.loadLabel(pm)}"
        announce(msg)
        a.startActivity(intent)
        return msg
    }

    fun openPlayStore(query: String, announce: (String) -> Unit = {}): String {
        val q = query.trim()
        if (q.isEmpty()) return "No app name provided for Play Store"
        val msg = "Opening Play Store for $q"
        announce(msg)
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(q)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            a.startActivity(marketIntent)
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(q)}")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            a.startActivity(webIntent)
        }
        return msg
    }

    fun openUrl(url: String, announce: (String) -> Unit = {}): String {
        var u = url.trim()
        if (u.isEmpty()) return "No URL provided"
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://$u"
        }
        val msg = "Opening $u"
        announce(msg)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(u)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        a.startActivity(intent)
        return msg
    }

    fun searchWeb(query: String, announce: (String) -> Unit = {}): String {
        val q = query.trim()
        if (q.isEmpty()) return "No search query provided"
        val msg = "Searching Google for $q"
        announce(msg)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(q)}")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
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
        return "Timer: $min min $label".trim()
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
        val root = Environment.getExternalStorageDirectory()
        val androidDir = File(root, "Android")
        val all = ArrayList<File>()
        for (f in root.walkTopDown().onEnter { !it.name.startsWith(".") && it != androidDir }) {
            if (f.isFile) all += f
            if (all.size > 200000) break
        }
        val hits = if (tokens.isEmpty()) {
            all
        } else {
            var filtered = all.filter { f -> val n = f.name.lowercase(); tokens.all { n.contains(it) } }
            if (filtered.isEmpty()) {
                val long = tokens.filter { it.length >= 3 }
                filtered = all.filter { f -> val n = f.name.lowercase(); long.any { n.contains(it) } }
            }
            filtered
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
