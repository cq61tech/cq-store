package com.cq61tech.cqstore.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

data class InstalledAppInfo(
    val packageId: String,
    val versionName: String,
    val versionCode: Long,
)

data class PendingInstall(
    val itemId: String,
    val assetName: String,
    val assetUrl: String,
    val assetSize: Long,
)

object InstallManager {

    private const val PENDING_PREFS = "cq_store_pending_install"
    private const val PENDING_TTL_MS = 10 * 60 * 1000L

    @Suppress("DEPRECATION")
    fun getInstalledInfo(context: Context, item: StoreItem): InstalledAppInfo? {
        if (item.kind != "apk") return null
        val candidates = buildList {
            item.packageId?.let { add(it) }
            addAll(item.altPackageIds)
        }
        val pm = context.packageManager
        for (pkg in candidates) {
            try {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                } else {
                    pm.getPackageInfo(pkg, 0)
                }
                val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    info.versionCode.toLong()
                }
                return InstalledAppInfo(
                    packageId = pkg,
                    versionName = info.versionName.orEmpty(),
                    versionCode = code,
                )
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return null
    }

    fun canLaunch(context: Context, packageId: String): Boolean {
        return context.packageManager.getLaunchIntentForPackage(packageId) != null
    }

    fun openApp(context: Context, packageId: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageId) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    fun openUrl(context: Context, url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun isUpdateAvailable(installed: String, latest: String): Boolean {
        val a = extractVersionNums(installed)
        val b = extractVersionNums(latest)
        if (a.isEmpty() || b.isEmpty()) return false
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return y > x
        }
        return false
    }

    private fun extractVersionNums(raw: String): List<Int> {
        val cleaned = raw.trim().substringBefore(" ").substringBefore("(")
        return Regex("\\d+").findAll(cleaned)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
    }

    fun canInstallPackages(context: Context): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    fun requestInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun savePendingInstall(context: Context, itemId: String, asset: ReleaseAsset) {
        context.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("item_id", itemId)
            .putString("asset_name", asset.name)
            .putString("asset_url", asset.url)
            .putLong("asset_size", asset.size)
            .putLong("timestamp", System.currentTimeMillis())
            .apply()
    }

    fun consumePendingInstall(context: Context): PendingInstall? {
        val prefs = context.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
        val itemId = prefs.getString("item_id", null) ?: return null
        val assetName = prefs.getString("asset_name", null) ?: return null
        val assetUrl = prefs.getString("asset_url", null) ?: return null
        val assetSize = prefs.getLong("asset_size", 0L)
        val ts = prefs.getLong("timestamp", 0L)
        prefs.edit().clear().apply()
        if (System.currentTimeMillis() - ts > PENDING_TTL_MS) return null
        return PendingInstall(itemId, assetName, assetUrl, assetSize)
    }

    fun clearStalePendingInstall(context: Context) {
        val prefs = context.getSharedPreferences(PENDING_PREFS, Context.MODE_PRIVATE)
        val ts = prefs.getLong("timestamp", 0L)
        if (ts > 0L && System.currentTimeMillis() - ts > 1500L) {
            prefs.edit().clear().apply()
        }
    }

    private fun cacheFileFor(context: Context, url: String, fileName: String): File {
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(dir, "${hash}_$safeName")
    }

    fun getCachedApk(context: Context, asset: ReleaseAsset): File? {
        val file = cacheFileFor(context, asset.url, asset.name)
        if (!file.exists() || !file.isFile) return null
        val len = file.length()
        if (len <= 0L) return null
        if (asset.size > 0L && len != asset.size) return null
        return file
    }

    private fun pruneApkCache(dir: File, keep: File) {
        val files = dir.listFiles().orEmpty()
        val now = System.currentTimeMillis()
        files.filter { it.name.endsWith(".part") && now - it.lastModified() > 60 * 60 * 1000L }
            .forEach { it.delete() }
        val apks = files
            .filter { it.isFile && it.name.endsWith(".apk") && it.absolutePath != keep.absolutePath }
            .sortedByDescending { it.lastModified() }
        if (apks.size > 4) {
            apks.drop(4).forEach { it.delete() }
        }
    }

    fun installApk(context: Context, file: File): Boolean {
        if (!canInstallPackages(context)) {
            requestInstallPermission(context)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    suspend fun downloadToCache(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val outFile = cacheFileFor(context, url, fileName)
        val dir = outFile.parentFile ?: File(context.cacheDir, "apks").apply { mkdirs() }
        val partFile = File(dir, "${outFile.name}.part")
        if (partFile.exists()) partFile.delete()

        try {
            val req = Request.Builder().url(url).header("User-Agent", "CQStore").build()
            CatalogRepository.httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed (${resp.code})")
                val body = resp.body ?: error("Empty download")
                val total = body.contentLength()
                var readTotal = 0L
                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            if (!coroutineContext.isActive) {
                                throw CancellationException("Cancelled")
                            }
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            readTotal += n
                            if (total > 0L) {
                                onProgress((readTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (total > 0L && readTotal != total) {
                    error("Incomplete download")
                }
            }
            if (outFile.exists()) outFile.delete()
            if (!partFile.renameTo(outFile)) {
                partFile.copyTo(outFile, overwrite = true)
                partFile.delete()
            }
            pruneApkCache(dir, outFile)
        } catch (t: Throwable) {
            partFile.delete()
            throw t
        }
        onProgress(1f)
        outFile
    }

    suspend fun downloadToDownloads(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Float) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val req = Request.Builder().url(url).header("User-Agent", "CQStore").build()

        CatalogRepository.httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed (${resp.code})")
            val body = resp.body ?: error("Empty download")
            val total = body.contentLength()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CQStore")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Cannot create file in Downloads")
                try {
                    resolver.openOutputStream(uri)?.use { output ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(16 * 1024)
                            var readTotal = 0L
                            while (true) {
                                if (!coroutineContext.isActive) {
                                    throw CancellationException("Cancelled")
                                }
                                val n = input.read(buf)
                                if (n == -1) break
                                output.write(buf, 0, n)
                                readTotal += n
                                if (total > 0L) {
                                    onProgress((readTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                                }
                            }
                        }
                    } ?: error("Cannot open output stream")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null)
                    throw t
                }
                onProgress(1f)
                "Download/CQStore/$safeName"
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "CQStore"
                ).apply { mkdirs() }
                val outFile = File(dir, safeName)
                FileOutputStream(outFile).use { output ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        var readTotal = 0L
                        while (true) {
                            if (!coroutineContext.isActive) {
                                outFile.delete()
                                throw CancellationException("Cancelled")
                            }
                            val n = input.read(buf)
                            if (n == -1) break
                            output.write(buf, 0, n)
                            readTotal += n
                            if (total > 0L) {
                                onProgress((readTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                onProgress(1f)
                outFile.absolutePath
            }
        }
    }
}
