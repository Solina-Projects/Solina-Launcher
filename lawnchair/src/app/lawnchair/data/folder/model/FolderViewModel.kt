package app.lawnchair.data.folder.model

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.service.FolderService
import app.lawnchair.preferences2.ReloadHelper
import app.lawnchair.util.App
import com.android.launcher3.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FolderViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository: FolderService = FolderService.INSTANCE.get(application)

    val folders: StateFlow<List<FolderEntry>?> = repository.getFoldersFlow()
        .distinctUntilChanged()
        .catch { exception ->
            Log.e("FolderViewModel", "Error in folders flow", exception)
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    val allFolderPackages: StateFlow<Set<String>?> = folders
        .map { folderList ->
            folderList?.flatMap { folder -> folder.itemComponentKeys }
                ?.map { key -> key.substringBefore("/") }
                ?.toSet()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null,
        )

    private val reloadHelper = ReloadHelper(application)

    fun getFolderFlowForId(folderId: Int): Flow<FolderEntry?> {
        return folders.map { list -> list?.find { it.id == folderId } }
    }

    fun renameFolder(folderId: Int, title: String) {
        viewModelScope.launch {
            repository.renameFolderInfo(folderId, title)
            reloadHelper.reloadGrid()
        }
    }

    fun updateFolderItems(id: Int, title: String, componentKeys: List<String>) {
        viewModelScope.launch {
            repository.updateFolderWithItems(id, title, componentKeys)
            reloadHelper.reloadGrid()
        }
    }

    fun createSmartFolders(apps: List<App>, context: Context) {
        viewModelScope.launch {
            val categoryTokens = listOf(
                R.string.smart_category_social to listOf("instagram", "facebook", "tiktok", "snapchat", "reddit", "threads", "twitter", "com.x."),
                R.string.smart_category_communication to listOf("whatsapp", "telegram", "signal", "messenger", "discord", "skype", "gmail", "outlook", "messages", "phone", "contacts", "meet"),
                R.string.smart_category_games to listOf("game", "games", "supercell", "roblox", "mojang", "pubg", "activision", "epicgames", "king.com"),
                R.string.smart_category_media to listOf("youtube", "netflix", "spotify", "music", "video", "photos", "gallery", "twitch", "vlc", "soundcloud", "podcast"),
                R.string.smart_category_shopping to listOf("amazon", "ebay", "aliexpress", "walmart", "shop", "paypal", "wallet", "bank", "finance", "revolut", "venmo"),
                R.string.smart_category_productivity to listOf("docs", "sheets", "drive", "office", "calendar", "notes", "tasks", "notion", "evernote", "github", "slack", "trello", "jira", "word", "excel"),
                R.string.smart_category_tools to listOf("calculator", "clock", "files", "filemanager", "settings", "weather", "maps", "browser", "chrome", "firefox", "vpn", "security"),
            )
            val grouped = mutableMapOf<Int, MutableList<App>>()
            apps.forEach { app ->
                val searchable = "${app.key.componentName.packageName} ${app.label}".lowercase()
                val category = categoryTokens.firstOrNull { (_, tokens) -> tokens.any(searchable::contains) }
                if (category != null) grouped.getOrPut(category.first) { mutableListOf() }.add(app)
            }
            grouped.forEach { (labelRes, categorizedApps) ->
                if (categorizedApps.size >= 2) {
                    val title = "Solina • ${context.getString(labelRes)}"
                    repository.createOrReplaceSmartFolder(title, categorizedApps.map { it.key.toString() })
                }
            }
            reloadHelper.reloadGrid()
        }
    }

    fun createFolder(title: String) {
        viewModelScope.launch {
            repository.saveFolderInfo(title)
        }
    }

    fun deleteFolder(id: Int) {
        viewModelScope.launch {
            repository.deleteFolderInfo(id)
        }
        reloadHelper.reloadGrid()
    }

    fun updateFolderOrder(orderedIds: List<Int>) {
        viewModelScope.launch {
            repository.updateFolderRanks(orderedIds)
        }
    }
}
