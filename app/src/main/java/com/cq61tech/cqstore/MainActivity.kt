package com.cq61tech.cqstore

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.cq61tech.cqstore.ui.DetailScreen
import com.cq61tech.cqstore.ui.HomeScreen
import com.cq61tech.cqstore.ui.StoreViewModel
import com.cq61tech.cqstore.ui.theme.CQStoreTheme

class MainActivity : ComponentActivity() {
    private val viewModel: StoreViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CQStoreTheme {
                val state by viewModel.state.collectAsState()
                val selectedItem = state.items.firstOrNull { it.id == state.selectedItemId }

                LaunchedEffect(state.notice) {
                    val msg = state.notice ?: return@LaunchedEffect
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    viewModel.clearNotice()
                }

                BackHandler(enabled = selectedItem != null) {
                    viewModel.closeItem()
                }

                Crossfade(targetState = selectedItem, label = "screen") { item ->
                    if (item == null) {
                        HomeScreen(
                            state = state,
                            onSelectTab = viewModel::selectTab,
                            onOpenItem = viewModel::openItem,
                            onRefresh = viewModel::refreshCatalog,
                        )
                    } else {
                        DetailScreen(
                            item = item,
                            installed = state.installed[item.id],
                            download = state.download,
                            onBack = viewModel::closeItem,
                            onDownloadAsset = viewModel::downloadAsset,
                            onCancelDownload = viewModel::cancelDownload,
                            onShowNotice = viewModel::showNotice,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshInstalled()
        viewModel.resumePendingInstall()
    }
}
