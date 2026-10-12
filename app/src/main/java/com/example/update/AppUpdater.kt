package com.example.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer build of this app that is on offer. */
data class UpdateInfo(val versionCode: Long, val versionName: String, val url: String)

/**
 * The phone app's own update, so a new version does not mean finding, downloading and re-installing an APK by hand.
 *
 * How it works: every signed build on the repository's main branch is published as the GitHub release `android-latest` (one
 * APK, replaced each time; its notes say `versionCode=N versionName=X`). This looks at that release, and when its versionCode is
 * higher than the installed one, downloads the APK and hands it to Android's installer. Android itself refuses an APK signed
 * with a different key than the installed app, so a tampered download cannot replace the app. The person still confirms the
 * install on the system screen: apps cannot install themselves silently.
 */
object AppUpdater {
    private const val RELEASE_API = "https://api.github.com/repos/Zohaib8090/zohara-link/releases/tags/android-latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/Zohaib8090/zohara-link/"

    fun installed(context: Context): Pair<Long, String> {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info) to (info.versionName ?: "")
    }

    /** The release as GitHub describes it, to what is on offer. `null` when it does not look like one of our releases. */
    fun parseRelease(json: String): UpdateInfo? {
        return try {
            val o = JSONObject(json)
            val body = o.optString("body")
            val code = Regex("versionCode=(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val name = Regex("versionName=(\\S+)").find(body)?.groupValues?.get(1) ?: code.toString()
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val url = a.optString("browser_download_url")
                // Only ever download from this project's own releases.
                if (a.optString("name").endsWith(".apk") && url.startsWith(DOWNLOAD_PREFIX)) return UpdateInfo(code, name, url)
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** The newest build on offer, or `null` when there is none or it cannot be reached. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val c = URL(RELEASE_API).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            if (c.responseCode != 200) return@withContext null
            parseRelease(c.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            null
        }
    }

    /** Downloads the APK into the app's cache; `null` on any problem. [onProgress] gets 0..100 (or -1 when the size is not known). */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "zohara-link.apk")
            val c = URL(info.url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 20000
            if (c.responseCode != 200) return@withContext null
            val total = c.contentLengthLong
            var done = 0L
            var last = -2
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != last) {
                            last = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            // A complete APK is a zip file and as long as the server said.
            val header = file.inputStream().use { s -> ByteArray(2).also { s.read(it) } }
            if (header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte() || (total > 0 && file.length() != total)) {
                file.delete()
                return@withContext null
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    fun canInstall(context: Context): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" setting for this app (it is off until the person allows it once). */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hands the downloaded APK to Android's installer, which asks the person to confirm. */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
