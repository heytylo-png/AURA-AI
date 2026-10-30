package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.MemoryCategory
import com.example.data.model.MemoryEntity
import com.example.data.model.MemoryItem
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) interface defining CRUD operations for the [MemoryItem] / [MemoryEntity]
 * persistent storage in the local Room database.
 */
@Dao
interface MemoryDao {

    // --- Core CRUD Operations ---

    /**
     * Inserts a memory item into the database. If it already exists, replaces it.
     * @return The row ID of the inserted memory item.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MemoryItem): Long

    /**
     * Inserts a memory entity into the database. If it already exists, replaces it.
     * @return The row ID of the inserted memory.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryEntity): Long

    /**
     * Inserts multiple memory items in a single transaction.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MemoryItem>): List<Long>

    /**
     * Inserts multiple memory entities in a single transaction.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemories(memories: List<MemoryEntity>): List<Long>

    /**
     * Updates an existing memory item.
     */
    @Update
    suspend fun update(item: MemoryItem)

    /**
     * Updates an existing memory entity.
     */
    @Update
    suspend fun updateMemory(memory: MemoryEntity)

    /**
     * Deletes a specific memory item.
     */
    @Delete
    suspend fun delete(item: MemoryItem)

    /**
     * Deletes a specific memory entity.
     */
    @Delete
    suspend fun deleteMemory(memory: MemoryEntity)

    /**
     * Deletes a memory item by its unique ID.
     */
    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Deletes a memory entity by its unique ID.
     */
    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    /**
     * Clears all stored memories from the database.
     */
    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()

    // --- Query Operations ---

    /**
     * Queries all stored memories ordered by importance and recent access.
     */
    @Query("SELECT * FROM memories ORDER BY importance DESC, lastAccessedAt DESC")
    fun getAllMemories(): Flow<List<MemoryItem>>

    /**
     * Queries all active memories for AI contextual reasoning.
     */
    @Query("SELECT * FROM memories WHERE isActive = 1 ORDER BY importance DESC")
    suspend fun getActiveMemories(): List<MemoryItem>

    /**
     * Observes all active memories as a reactive stream.
     */
    @Query("SELECT * FROM memories WHERE isActive = 1 ORDER BY importance DESC")
    fun getActiveMemoriesFlow(): Flow<List<MemoryItem>>

    /**
     * Queries a memory by its unique ID as a reactive Flow.
     */
    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    fun getMemoryById(id: Long): Flow<MemoryItem?>

    /**
     * Queries a memory by its unique ID as a reactive Flow.
     */
    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    fun getMemory(id: Long): Flow<MemoryItem?>

    /**
     * Retrieves a single memory snapshot by its unique ID.
     */
    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun getMemoryByIdSnapshot(id: Long): MemoryItem?

    /**
     * Queries memories filtered by a specific category.
     */
    @Query("SELECT * FROM memories WHERE category = :category ORDER BY importance DESC")
    fun getMemoriesByCategory(category: MemoryCategory): Flow<List<MemoryItem>>

    /**
     * Searches memories by text content with case-insensitive matching.
     */
    @Query("SELECT * FROM memories WHERE content LIKE '%' || :query || '%' ORDER BY importance DESC")
    fun searchMemories(query: String): Flow<List<MemoryItem>>

    /**
     * Observes the total count of stored memories.
     */
    @Query("SELECT COUNT(*) FROM memories")
    fun getMemoriesCount(): Flow<Int>

    /**
     * Sets whether a memory is active for contextual AI prompts.
     */
    @Query("UPDATE memories SET isActive = :isActive WHERE id = :id")
    suspend fun setMemoryActive(id: Long, isActive: Boolean)

    /**
     * Updates the last accessed timestamp for a memory item.
     */
    @Query("UPDATE memories SET lastAccessedAt = :timestamp WHERE id = :id")
    suspend fun updateAccessTimestamp(id: Long, timestamp: Long)
}
