package com.example.data.repository

import com.example.data.local.MemoryDao
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Repository interface for persistent memory storage in Room Database.
 */
interface MemoryRepository {
    fun getAllMemories(): Flow<List<MemoryEntity>>
    suspend fun getActiveMemories(): List<MemoryEntity>
    fun getActiveMemoriesFlow(): Flow<List<MemoryEntity>>
    fun getMemoryById(id: Long): Flow<MemoryEntity?>
    suspend fun getMemoryByIdSnapshot(id: Long): MemoryEntity?
    fun getMemoriesByCategory(category: MemoryCategory): Flow<List<MemoryEntity>>
    fun searchMemories(query: String): Flow<List<MemoryEntity>>
    fun getMemoriesCount(): Flow<Int>
    suspend fun insertMemory(memory: MemoryEntity): Long
    suspend fun insertMemories(memories: List<MemoryEntity>): List<Long>
    suspend fun updateMemory(memory: MemoryEntity)
    suspend fun deleteMemory(memory: MemoryEntity)
    suspend fun deleteMemoryById(id: Long)
    suspend fun setMemoryActive(id: Long, isActive: Boolean)
    suspend fun updateAccessTimestamp(id: Long, timestamp: Long)
    suspend fun clearAllMemories()
}

/**
 * Default implementation of [MemoryRepository] backed by Room SQLite via [MemoryDao].
 */
class DefaultMemoryRepository(
    private val memoryDao: MemoryDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MemoryRepository {

    override fun getAllMemories(): Flow<List<MemoryEntity>> =
        memoryDao.getAllMemories().flowOn(ioDispatcher)

    override suspend fun getActiveMemories(): List<MemoryEntity> =
        withContext(ioDispatcher) { memoryDao.getActiveMemories() }

    override fun getActiveMemoriesFlow(): Flow<List<MemoryEntity>> =
        memoryDao.getActiveMemoriesFlow().flowOn(ioDispatcher)

    override fun getMemoryById(id: Long): Flow<MemoryEntity?> =
        memoryDao.getMemoryById(id).flowOn(ioDispatcher)

    override suspend fun getMemoryByIdSnapshot(id: Long): MemoryEntity? =
        withContext(ioDispatcher) { memoryDao.getMemoryByIdSnapshot(id) }

    override fun getMemoriesByCategory(category: MemoryCategory): Flow<List<MemoryEntity>> =
        memoryDao.getMemoriesByCategory(category).flowOn(ioDispatcher)

    override fun searchMemories(query: String): Flow<List<MemoryEntity>> =
        memoryDao.searchMemories(query).flowOn(ioDispatcher)

    override fun getMemoriesCount(): Flow<Int> =
        memoryDao.getMemoriesCount().flowOn(ioDispatcher)

    override suspend fun insertMemory(memory: MemoryEntity): Long =
        withContext(ioDispatcher) { memoryDao.insertMemory(memory) }

    override suspend fun insertMemories(memories: List<MemoryEntity>): List<Long> =
        withContext(ioDispatcher) { memoryDao.insertMemories(memories) }

    override suspend fun updateMemory(memory: MemoryEntity) =
        withContext(ioDispatcher) { memoryDao.updateMemory(memory) }

    override suspend fun deleteMemory(memory: MemoryEntity) =
        withContext(ioDispatcher) { memoryDao.deleteMemory(memory) }

    override suspend fun deleteMemoryById(id: Long) =
        withContext(ioDispatcher) { memoryDao.deleteMemoryById(id) }

    override suspend fun setMemoryActive(id: Long, isActive: Boolean) =
        withContext(ioDispatcher) { memoryDao.setMemoryActive(id, isActive) }

    override suspend fun updateAccessTimestamp(id: Long, timestamp: Long) =
        withContext(ioDispatcher) { memoryDao.updateAccessTimestamp(id, timestamp) }

    override suspend fun clearAllMemories() =
        withContext(ioDispatcher) { memoryDao.clearAllMemories() }
}
