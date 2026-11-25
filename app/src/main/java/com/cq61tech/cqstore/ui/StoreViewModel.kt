package com.cq61tech.cqstore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cq61tech.cqstore.data.CatalogRepository
import com.cq61tech.cqstore.data.InstallManager
import com.cq61tech.cqstore.data.InstalledAppInfo
import com.cq61tech.cqstore.data.ReleaseAsset
import com.cq61tech.cqstore.data.StoreItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StoreTab { APK, ALL }

data class DownloadState(
    val itemId: String,
    val fileName: String,
    val progress: Float,
)

data class StoreUiState(
    val items: List<StoreItem> = emptyList(),
    val selectedTab: StoreTab = StoreTab.APK,
    val selectedItemId: String? = null,
    val installed: Map<String, InstalledAppInfo> = emptyMap(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val download: DownloadState? = null,
    val notice: String? = null,
)

class StoreViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(StoreUiState())
    val state: StateFlow<StoreUiState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    init {
        refreshCatalog()
    }

    fun refreshCatalog() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val catalog = CatalogRepository.fetchCatalog()
                val installedMap = scanInstalled(catalog.items)
                _state.update {
                    it.copy(
                        items = catalog.items,
                        installed = installedMap,
                        isLoading = false,
                        error = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = t.message ?: "Failed to load catalog",
                    )
                }
            }
        }
    }

    fun refreshInstalled() {
        val items = _state.value.items
        if (items.isEmpty()) return
        _state.update { it.copy(installed = scanInstalled(items)) }
    }

    private fun scanInstalled(items: List<StoreItem>): Map<String, InstalledAppInfo> {
        val ctx = getApplication<Application>()
        val result = mutableMapOf<String, InstalledAppInfo>()
        for (item in items) {
            InstallManager.getInstalledInfo(ctx, item)?.let { result[item.id] = it }
        }
        return result
    }

    fun selectTab(tab: StoreTab) {
        _state.update { it.copy(selectedTab = tab) }
    }

    fun openItem(id: String) {
        _state.update { it.copy(selectedItemId = id) }
    }

    fun closeItem() {
        _state.update { it.copy(selectedItemId = null) }
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun showNotice(message: String) {
        _state.update { it.copy(notice = message) }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.update { it.copy(download = null) }
    }

    fun downloadAsset(item: StoreItem, asset: ReleaseAsset) {
        downloadJob?.cancel()
        val ctx = getApplication<Application>()
        downloadJob = viewModelScope.launch {
            _state.update {
                it.copy(download = DownloadState(item.id, asset.name, 0f))
            }
            try {
                val isApk = asset.name.endsWith(".apk", ignoreCase = true)
                if (isApk) {
                    val file = InstallManager.downloadToCache(ctx, asset.url, asset.name) { progress ->
                        _state.update { st ->
                            st.copy(download = st.download?.copy(progress = progress))
                        }
                    }
                    _state.update { it.copy(download = null) }
                    val started = InstallManager.installApk(ctx, file)
                    if (!started) {
                        showNotice("Allow install unknown apps for CQ Store, then tap again")
                    }
                } else {
                    val savedPath = InstallManager.downloadToDownloads(ctx, asset.url, asset.name) { progress ->
                        _state.update { st ->
                            st.copy(download = st.download?.copy(progress = progress))
                        }
                    }
                    _state.update { it.copy(download = null) }
                    showNotice("Saved to $savedPath")
                }
            } catch (_: CancellationException) {
                _state.update { it.copy(download = null) }
            } catch (t: Throwable) {
                _state.update { it.copy(download = null) }
                showNotice(t.message ?: "Download failed")
            }
        }
    }
}
