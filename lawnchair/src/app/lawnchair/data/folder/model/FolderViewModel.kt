package app.lawnchair.data.folder.model

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.service.FolderService
import app.lawnchair.preferences2.ReloadHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FolderViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val repository: FolderService = FolderService.INSTANCE.get(application)

    val folders: StateFlow<List<FolderEntry>?> = repository.getFoldersFlow()
        .retryWhen { cause, attempt ->
            Log.e("FolderViewModel", "Error in folders flow; retrying", cause)
            delay(minOf(30_000L, 1_000L * (1L shl attempt.coerceAtMost(5).toInt())))
            true
        }
        .distinctUntilChanged()
        .catch { exception ->
            Log.e("FolderViewModel", "Folder flow stopped unexpectedly", exception)
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
        val normalizedTitle = title.trim()
        if (normalizedTitle.isEmpty()) return
        viewModelScope.launch {
            repository.renameFolderInfo(folderId, normalizedTitle)
            reloadHelper.reloadGrid()
        }
    }

    fun updateFolderItems(id: Int, title: String, componentKeys: List<String>) {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isEmpty()) return
        val uniqueComponentKeys = componentKeys.distinct()
        viewModelScope.launch {
            repository.updateFolderWithItems(id, normalizedTitle, uniqueComponentKeys)
            reloadHelper.reloadGrid()
        }
    }

    fun createFolder(title: String) {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isEmpty()) return
        viewModelScope.launch {
            repository.saveFolderInfo(normalizedTitle)
        }
    }

    fun deleteFolder(id: Int) {
        viewModelScope.launch {
            repository.deleteFolderInfo(id)
            reloadHelper.reloadGrid()
        }
    }

    fun updateFolderOrder(orderedIds: List<Int>) {
        viewModelScope.launch {
            repository.updateFolderRanks(orderedIds)
        }
    }
}
