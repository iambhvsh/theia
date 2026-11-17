package `in`.iambhvsh.theia.tools

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
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
            "control_flashlight" -> toggleTorch(c.args.optBoolean("enable", true), announce)
            "adjust_volume" -> adjustVolume(
                direction = if (c.args.has("direction")) c.args.optString("direction") else null,
                percent = if (c.args.has("percent")) c.args.optInt("percent") else null,
                mute = if (c.args.has("mute")) c.args.optBoolean("mute") else null,
                announce = announce
            )
            "open_settings" -> openSettings(c.args.optString("setting"), announce)
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
            hit = when (target) {
                "x" -> {
                    // Exact label match or twitter package only; never substring match "x"
                    apps.firstOrNull { label(it) == "x" || pkg(it) == "com.twitter.android" }
                }
                "twitter" -> {
                    apps.firstOrNull { label(it) == "twitter" || pkg(it) == "com.twitter.android" }
                        ?: apps.firstOrNull { label(it).startsWith("twitter") }
                        ?: apps.firstOrNull { label(it).contains("twitter") }
                }
                else -> {
                    apps.firstOrNull { label(it) == target }
                        ?: apps.firstOrNull { label(it).startsWith(target) }
                        ?: apps.firstOrNull { target.length >= 3 && label(it).contains(target) }
                }
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

    fun toggleTorch(enable: Boolean, announce: (String) -> Unit = {}): String {
        return try {
            val cm = a.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "No flashlight available on this device"
            cm.setTorchMode(cameraId, enable)
            val msg = if (enable) "Turning on the flashlight." else "Turning off the flashlight."
            announce(msg)
            msg
        } catch (e: Exception) {
            "Could not control flashlight: ${e.message}"
        }
    }

    fun adjustVolume(direction: String? = null, percent: Int? = null, mute: Boolean? = null, announce: (String) -> Unit = {}): String {
        return try {
            val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (mute == true) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                val msg = "Muting media volume."
                announce(msg)
                return msg
            }
            if (percent != null && percent >= 0) {
                val p = percent.coerceIn(0, 100)
                val target = ((p / 100f) * max).toInt()
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                val msg = "Media volume set to $p percent."
                announce(msg)
                return msg
            }
            if (direction == "up") {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                val msg = "Volume turned up."
                announce(msg)
                return msg
            } else if (direction == "down") {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                val msg = "Volume turned down."
                announce(msg)
                return msg
            }
            val curr = ((am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) * 100).toInt()
            "Current media volume is $curr percent."
        } catch (e: Exception) {
            "Could not adjust volume: ${e.message}"
        }
    }

    fun openSettings(setting: String, announce: (String) -> Unit = {}): String {
        val s = setting.lowercase().trim()
        val (action, label) = when {
            s.contains("wifi") || s.contains("wi-fi") || s.contains("internet") || s.contains("network") ->
                Settings.ACTION_WIFI_SETTINGS to "Wi-Fi settings"
            s.contains("bluetooth") || s.contains("bt") ->
                Settings.ACTION_BLUETOOTH_SETTINGS to "Bluetooth settings"
            s.contains("display") || s.contains("brightness") || s.contains("screen") ->
                Settings.ACTION_DISPLAY_SETTINGS to "Display settings"
            s.contains("sound") || s.contains("audio") || s.contains("volume") ->
                Settings.ACTION_SOUND_SETTINGS to "Sound settings"
            s.contains("app") ->
                Settings.ACTION_APPLICATION_SETTINGS to "Apps settings"
            s.contains("battery") ->
                Settings.ACTION_BATTERY_SAVER_SETTINGS to "Battery settings"
            else ->
                Settings.ACTION_SETTINGS to "Settings"
        }
        val msg = "Opening $label"
        announce(msg)
        try {
            val intent = Intent(action).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            a.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            a.startActivity(fallback)
        }
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
