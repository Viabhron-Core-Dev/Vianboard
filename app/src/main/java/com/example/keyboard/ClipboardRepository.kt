package com.example.keyboard

import kotlinx.coroutines.flow.Flow

class ClipboardRepository(private val clipboardDao: ClipboardDao) {

    val allItems: Flow<List<ClipboardItem>> = clipboardDao.getAllItemsFlow()

    fun insert(text: String, isSensitive: Boolean = false) {
        val existing = clipboardDao.getItemByText(text)
        if (existing != null) {
            // Update timestamp to bubble it to the top
            clipboardDao.insert(existing.copy(timestamp = System.currentTimeMillis(), isSensitive = isSensitive))
        } else {
            clipboardDao.insert(ClipboardItem(text = text, isSensitive = isSensitive))
            cleanup()
        }
    }

    fun togglePin(item: ClipboardItem) {
        clipboardDao.update(item.copy(isPinned = !item.isPinned))
    }

    fun delete(item: ClipboardItem) {
        clipboardDao.delete(item)
    }

    fun deleteAllUnpinned() {
        clipboardDao.deleteAllUnpinned()
    }

    fun cleanup() {
        // Purge sensitive clipboard items older than 60 seconds
        val sixtySecondsAgo = System.currentTimeMillis() - 60_000L
        clipboardDao.deleteSensitiveOlderThan(sixtySecondsAgo)

        // Time limit: delete items older than 1 hour
        val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000)
        clipboardDao.deleteOldUnpinned(oneHourAgo)

        val maxUnpinnedCount = 20
        val currentUnpinnedCount = clipboardDao.getUnpinnedCount()
        if (currentUnpinnedCount > maxUnpinnedCount) {
            val toDelete = currentUnpinnedCount - maxUnpinnedCount
            clipboardDao.deleteOldestUnpinned(toDelete)
        }
    }

    fun getAllItemsSync(): List<ClipboardItem> {
        return clipboardDao.getAllItems()
    }
}
