package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.SavedMediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedMediaDao {
    @Query("SELECT * FROM saved_media ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<SavedMediaEntity>>

    @Query("SELECT * FROM saved_media ORDER BY createdAt DESC, id DESC")
    suspend fun getAll(): List<SavedMediaEntity>

    @Query("SELECT * FROM saved_media WHERE isReference = 1 AND label != ''")
    suspend fun getReferences(): List<SavedMediaEntity>

    @Query("SELECT * FROM saved_media WHERE id = :id")
    suspend fun getById(id: Long): SavedMediaEntity?

    @Insert
    suspend fun insert(item: SavedMediaEntity): Long

    @Update
    suspend fun update(item: SavedMediaEntity)

    @Query("DELETE FROM saved_media WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT fileName FROM saved_media")
    suspend fun fileNamesInUse(): List<String>
}
