package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository for managing archived group posts stored locally in Room.
 * Implements clean architecture abstraction between ViewModels and PostDao.
 */
class PostRepository(private val postDao: PostDao) {

    fun getAllPostsByGroup(groupName: String): Flow<List<ArchivedPost>> {
        return postDao.getAllPostsByGroup(groupName)
    }

    suspend fun getPostsSnapshotByGroup(groupName: String): List<ArchivedPost> = withContext(Dispatchers.IO) {
        postDao.getPostsSnapshotByGroup(groupName)
    }

    fun getAllPosts(): Flow<List<ArchivedPost>> {
        return postDao.getAllPosts()
    }

    fun searchPosts(groupName: String, query: String): Flow<List<ArchivedPost>> {
        val cleanQuery = query.trim()
        return if (groupName.isBlank()) {
            postDao.searchPosts(cleanQuery)
        } else {
            postDao.searchPosts(groupName, cleanQuery)
        }
    }

    fun getPostCountByGroup(groupName: String): Flow<Int> {
        return postDao.getPostCountByGroup(groupName)
    }

    fun countPosts(): Flow<Int> {
        return postDao.countPosts()
    }

    suspend fun countPostsSync(): Int = withContext(Dispatchers.IO) {
        postDao.countPostsSync()
    }

    fun getTotalPostCount(): Flow<Int> {
        return postDao.getTotalPostCount()
    }

    suspend fun insertIgnore(posts: List<ArchivedPost>): List<Long> = withContext(Dispatchers.IO) {
        if (posts.isNotEmpty()) {
            postDao.insertIgnore(posts)
        } else {
            emptyList()
        }
    }

    suspend fun savePosts(posts: List<ArchivedPost>): List<Long> = withContext(Dispatchers.IO) {
        if (posts.isNotEmpty()) {
            postDao.insertAll(posts)
        } else {
            emptyList()
        }
    }

    suspend fun savePost(post: ArchivedPost): Long = withContext(Dispatchers.IO) {
        postDao.insert(post)
    }

    suspend fun deleteOlderThanDays(days: Int): Int = withContext(Dispatchers.IO) {
        postDao.deleteOlderThanDays(days)
    }

    suspend fun clearGroupPosts(groupName: String): Int = withContext(Dispatchers.IO) {
        postDao.clearGroupPosts(groupName)
    }

    suspend fun deletePostById(id: String): Int = withContext(Dispatchers.IO) {
        postDao.deletePostById(id)
    }

    suspend fun getExistingKeysForGroup(groupName: String): Set<String> = withContext(Dispatchers.IO) {
        postDao.getExistingKeysForGroup(groupName).toSet()
    }
}
