package com.cq61tech.cqstore.data

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class Catalog(
    val updatedAt: String = "",
    val items: List<StoreItem> = emptyList(),
)

@Serializable
data class StoreItem(
    val id: String,
    val name: String,
    val repo: String,
    val kind: String,
    val packageId: String? = null,
    val altPackageIds: List<String> = emptyList(),
    val author: String = "cq61tech",
    val shortDescription: String = "",
    val description: String = "",
    val license: String = "",
    val iconUrl: String = "",
    val bannerUrl: String = "",
    val sourceUrl: String = "",
    val telegramUrl: String = "",
    val websiteUrl: String = "",
    val repoUrl: String = "",
    val latestVersion: String = "",
    val whatsNew: String = "",
    val releases: List<ReleaseInfo> = emptyList(),
)

@Serializable
data class ReleaseInfo(
    val tag: String,
    val name: String = "",
    val publishedAt: String = "",
    val notes: String = "",
    val assets: List<ReleaseAsset> = emptyList(),
)

@Serializable
data class ReleaseAsset(
    val name: String,
    val url: String,
    val size: Long = 0L,
)

fun StoreItem.primaryAsset(release: ReleaseInfo? = releases.firstOrNull()): ReleaseAsset? {
    val assets = release?.assets.orEmpty()
    if (assets.isEmpty()) return null
    return when (kind) {
        "apk" -> {
            val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            apks.firstOrNull { "universal" in it.name.lowercase(Locale.ROOT) }
                ?: apks.firstOrNull { "release" in it.name.lowercase(Locale.ROOT) }
                ?: apks.firstOrNull { "debug" !in it.name.lowercase(Locale.ROOT) }
                ?: apks.firstOrNull()
        }
        "zip" -> {
            val zips = assets.filter { it.name.endsWith(".zip", ignoreCase = true) }
            zips.firstOrNull { "calibration" !in it.name.lowercase(Locale.ROOT) }
                ?: zips.firstOrNull()
        }
        else -> assets.firstOrNull()
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    return if (mb >= 100.0) {
        String.format(Locale.US, "%.0f MB", mb)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
}
