package com.cq61tech.cqstore.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object CatalogRepository {
    private const val CATALOG_URL =
        "https://raw.githubusercontent.com/cq61tech/cq-store/main/catalog.json"

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun fetchCatalog(): Catalog = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(CATALOG_URL)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", "CQStore")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("HTTP ${resp.code}")
            }
            val body = resp.body?.string() ?: error("Empty response")
            json.decodeFromString<Catalog>(body)
        }
    }
}
