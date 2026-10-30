package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.MilestoneEntity
import com.example.data.model.ProjectArtifactEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.ProjectStatus
import com.example.data.model.ProjectWithDetails
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    fun getProjectByIdFlow(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects")
    suspend fun getAllProjectsSnapshot(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE status = :status ORDER BY createdAt DESC")
    fun getProjectsByStatus(status: ProjectStatus): Flow<List<ProjectEntity>>

    @Query("SELECT COUNT(*) FROM projects")
    fun getProjectsCount(): Flow<Int>

    // Composite Transactional Relations
    @Transaction
    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    fun getProjectWithDetails(id: Long): Flow<ProjectWithDetails?>

    @Transaction
    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    suspend fun getProjectWithDetailsSnapshot(id: Long): ProjectWithDetails?

    @Transaction
    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun getAllProjectsWithDetails(): Flow<List<ProjectWithDetails>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Update
    suspend fun updateProject(project: ProjectEntity)

    @Delete
    suspend fun deleteProject(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteProjectById(id: Long)

    // Milestones
    @Query("SELECT * FROM project_milestones WHERE projectId = :projectId ORDER BY targetDateMillis ASC, id ASC")
    fun getMilestonesForProject(projectId: Long): Flow<List<MilestoneEntity>>

    @Query("SELECT * FROM project_milestones WHERE projectId = :projectId")
    suspend fun getMilestonesForProjectList(projectId: Long): List<MilestoneEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMilestone(milestone: MilestoneEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMilestones(milestones: List<MilestoneEntity>): List<Long>

    @Update
    suspend fun updateMilestone(milestone: MilestoneEntity)

    @Delete
    suspend fun deleteMilestone(milestone: MilestoneEntity)

    @Query("DELETE FROM project_milestones WHERE id = :id")
    suspend fun deleteMilestoneById(id: Long)

    @Query("UPDATE project_milestones SET isCompleted = :completed WHERE id = :id")
    suspend fun setMilestoneCompleted(id: Long, completed: Boolean)

    // Artifacts
    @Query("SELECT * FROM project_artifacts WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun getArtifactsForProject(projectId: Long): Flow<List<ProjectArtifactEntity>>

    @Query("SELECT * FROM project_artifacts WHERE id = :id LIMIT 1")
    suspend fun getArtifactById(id: Long): ProjectArtifactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtifact(artifact: ProjectArtifactEntity): Long

    @Update
    suspend fun updateArtifact(artifact: ProjectArtifactEntity)

    @Delete
    suspend fun deleteArtifact(artifact: ProjectArtifactEntity)

    @Query("DELETE FROM project_artifacts WHERE id = :id")
    suspend fun deleteArtifactById(id: Long)
}
