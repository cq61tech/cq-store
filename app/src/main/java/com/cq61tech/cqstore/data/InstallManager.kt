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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

data class InstalledAppInfo(
    val packageId: String,
    val versionName: String,
    val versionCode: Long,
)

object InstallManager {

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

    fun installApk(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
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
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outFile = File(dir, safeName)
        if (outFile.exists()) outFile.delete()

        val req = Request.Builder().url(url).header("User-Agent", "CQStore").build()
        CatalogRepository.httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed (${resp.code})")
            val body = resp.body ?: error("Empty download")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(16 * 1024)
                    var readTotal = 0L
                    while (true) {
                        if (!coroutineContext.isActive) {
                            outFile.delete()
                            error("Cancelled")
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
                                if (!coroutineContext.isActive) error("Cancelled")
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
                                error("Cancelled")
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
