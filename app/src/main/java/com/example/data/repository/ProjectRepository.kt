package com.example.data.repository

import com.example.data.local.ProjectDao
import com.example.data.model.MilestoneEntity
import com.example.data.model.ProjectArtifactEntity
import com.example.data.model.ProjectEntity
import com.example.data.model.ProjectStatus
import com.example.data.model.ProjectWithDetails
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Repository interface for managing projects, milestones, and artifacts in Room Database.
 */
interface ProjectRepository {
    fun getAllProjects(): Flow<List<ProjectEntity>>
    fun getProjectByIdFlow(id: Long): Flow<ProjectEntity?>
    suspend fun getProjectById(id: Long): ProjectEntity?
    suspend fun getAllProjectsSnapshot(): List<ProjectEntity>
    fun getProjectsByStatus(status: ProjectStatus): Flow<List<ProjectEntity>>
    fun getProjectsCount(): Flow<Int>

    fun getProjectWithDetails(id: Long): Flow<ProjectWithDetails?>
    suspend fun getProjectWithDetailsSnapshot(id: Long): ProjectWithDetails?
    fun getAllProjectsWithDetails(): Flow<List<ProjectWithDetails>>

    suspend fun insertProject(project: ProjectEntity): Long
    suspend fun updateProject(project: ProjectEntity)
    suspend fun deleteProject(project: ProjectEntity)
    suspend fun deleteProjectById(id: Long)

    // Milestones
    fun getMilestonesForProject(projectId: Long): Flow<List<MilestoneEntity>>
    suspend fun getMilestonesForProjectList(projectId: Long): List<MilestoneEntity>
    suspend fun insertMilestone(milestone: MilestoneEntity): Long
    suspend fun insertMilestones(milestones: List<MilestoneEntity>): List<Long>
    suspend fun updateMilestone(milestone: MilestoneEntity)
    suspend fun deleteMilestone(milestone: MilestoneEntity)
    suspend fun deleteMilestoneById(id: Long)
    suspend fun setMilestoneCompleted(id: Long, completed: Boolean)

    // Artifacts / Documents
    fun getArtifactsForProject(projectId: Long): Flow<List<ProjectArtifactEntity>>
    suspend fun getArtifactById(id: Long): ProjectArtifactEntity?
    suspend fun insertArtifact(artifact: ProjectArtifactEntity): Long
    suspend fun updateArtifact(artifact: ProjectArtifactEntity)
    suspend fun deleteArtifact(artifact: ProjectArtifactEntity)
    suspend fun deleteArtifactById(id: Long)
}

/**
 * Default implementation of [ProjectRepository] backed by Room SQLite via [ProjectDao].
 */
class DefaultProjectRepository(
    private val projectDao: ProjectDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ProjectRepository {

    override fun getAllProjects(): Flow<List<ProjectEntity>> =
        projectDao.getAllProjects().flowOn(ioDispatcher)

    override fun getProjectByIdFlow(id: Long): Flow<ProjectEntity?> =
        projectDao.getProjectByIdFlow(id).flowOn(ioDispatcher)

    override suspend fun getProjectById(id: Long): ProjectEntity? =
        withContext(ioDispatcher) { projectDao.getProjectById(id) }

    override suspend fun getAllProjectsSnapshot(): List<ProjectEntity> =
        withContext(ioDispatcher) { projectDao.getAllProjectsSnapshot() }

    override fun getProjectsByStatus(status: ProjectStatus): Flow<List<ProjectEntity>> =
        projectDao.getProjectsByStatus(status).flowOn(ioDispatcher)

    override fun getProjectsCount(): Flow<Int> =
        projectDao.getProjectsCount().flowOn(ioDispatcher)

    override fun getProjectWithDetails(id: Long): Flow<ProjectWithDetails?> =
        projectDao.getProjectWithDetails(id).flowOn(ioDispatcher)

    override suspend fun getProjectWithDetailsSnapshot(id: Long): ProjectWithDetails? =
        withContext(ioDispatcher) { projectDao.getProjectWithDetailsSnapshot(id) }

    override fun getAllProjectsWithDetails(): Flow<List<ProjectWithDetails>> =
        projectDao.getAllProjectsWithDetails().flowOn(ioDispatcher)

    override suspend fun insertProject(project: ProjectEntity): Long =
        withContext(ioDispatcher) { projectDao.insertProject(project) }

    override suspend fun updateProject(project: ProjectEntity) =
        withContext(ioDispatcher) { projectDao.updateProject(project) }

    override suspend fun deleteProject(project: ProjectEntity) =
        withContext(ioDispatcher) { projectDao.deleteProject(project) }

    override suspend fun deleteProjectById(id: Long) =
        withContext(ioDispatcher) { projectDao.deleteProjectById(id) }

    override fun getMilestonesForProject(projectId: Long): Flow<List<MilestoneEntity>> =
        projectDao.getMilestonesForProject(projectId).flowOn(ioDispatcher)

    override suspend fun getMilestonesForProjectList(projectId: Long): List<MilestoneEntity> =
        withContext(ioDispatcher) { projectDao.getMilestonesForProjectList(projectId) }

    override suspend fun insertMilestone(milestone: MilestoneEntity): Long =
        withContext(ioDispatcher) { projectDao.insertMilestone(milestone) }

    override suspend fun insertMilestones(milestones: List<MilestoneEntity>): List<Long> =
        withContext(ioDispatcher) { projectDao.insertMilestones(milestones) }

    override suspend fun updateMilestone(milestone: MilestoneEntity) =
        withContext(ioDispatcher) { projectDao.updateMilestone(milestone) }

    override suspend fun deleteMilestone(milestone: MilestoneEntity) =
        withContext(ioDispatcher) { projectDao.deleteMilestone(milestone) }

    override suspend fun deleteMilestoneById(id: Long) =
        withContext(ioDispatcher) { projectDao.deleteMilestoneById(id) }

    override suspend fun setMilestoneCompleted(id: Long, completed: Boolean) =
        withContext(ioDispatcher) { projectDao.setMilestoneCompleted(id, completed) }

    override fun getArtifactsForProject(projectId: Long): Flow<List<ProjectArtifactEntity>> =
        projectDao.getArtifactsForProject(projectId).flowOn(ioDispatcher)

    override suspend fun getArtifactById(id: Long): ProjectArtifactEntity? =
        withContext(ioDispatcher) { projectDao.getArtifactById(id) }

    override suspend fun insertArtifact(artifact: ProjectArtifactEntity): Long =
        withContext(ioDispatcher) { projectDao.insertArtifact(artifact) }

    override suspend fun updateArtifact(artifact: ProjectArtifactEntity) =
        withContext(ioDispatcher) { projectDao.updateArtifact(artifact) }

    override suspend fun deleteArtifact(artifact: ProjectArtifactEntity) =
        withContext(ioDispatcher) { projectDao.deleteArtifact(artifact) }

    override suspend fun deleteArtifactById(id: Long) =
        withContext(ioDispatcher) { projectDao.deleteArtifactById(id) }
}
