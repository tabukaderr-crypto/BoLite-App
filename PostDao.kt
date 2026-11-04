package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for local indexed Facebook posts stored in Room DB.
 */
@Dao
interface PostDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRaw(posts: List<ArchivedPost>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRaw(post: ArchivedPost): Long

    suspend fun insertIgnore(posts: List<ArchivedPost>): List<Long> {
        val valid = posts.filter { com.example.util.PostSanitizer.isValidPostContent(it.postContent) }
        if (valid.isEmpty()) return emptyList()
        return insertRaw(valid)
    }

    suspend fun insertIgnore(post: ArchivedPost): Long {
        if (!com.example.util.PostSanitizer.isValidPostContent(post.postContent)) {
            return -1L
        }
        return insertRaw(post)
    }

    suspend fun insertAll(posts: List<ArchivedPost>): List<Long> = insertIgnore(posts)

    suspend fun insert(post: ArchivedPost): Long = insertIgnore(post)

    suspend fun insertPost(post: ArchivedPost): Long = insertIgnore(post)

    @Query("SELECT * FROM archived_posts WHERE groupName = :groupName ORDER BY scanDate DESC")
    fun getAllPostsByGroup(groupName: String): Flow<List<ArchivedPost>>

    @Query("SELECT * FROM archived_posts WHERE groupName = :groupName ORDER BY scanDate DESC")
    suspend fun getPostsSnapshotByGroup(groupName: String): List<ArchivedPost>

    @Query("SELECT * FROM archived_posts ORDER BY scanDate DESC")
    fun getAllPosts(): Flow<List<ArchivedPost>>

    fun getPosts(): Flow<List<ArchivedPost>> = getAllPosts()

    @Query("""
        SELECT * FROM archived_posts 
        WHERE (groupName = :groupName OR :groupName = '') 
          AND (text LIKE '%' || :query || '%' OR fullText LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%')
        ORDER BY scanDate DESC
    """)
    fun searchPosts(groupName: String, query: String): Flow<List<ArchivedPost>>

    @Query("""
        SELECT * FROM archived_posts 
        WHERE text LIKE '%' || :query || '%' OR fullText LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%'
        ORDER BY scanDate DESC
    """)
    fun searchPosts(query: String): Flow<List<ArchivedPost>>

    @Query("SELECT COUNT(*) FROM archived_posts WHERE groupName = :groupName")
    fun getPostCountByGroup(groupName: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM archived_posts")
    fun countPosts(): Flow<Int>

    @Query("SELECT COUNT(*) FROM archived_posts")
    suspend fun countPostsSync(): Int

    @Query("SELECT COUNT(*) FROM archived_posts")
    fun getTotalPostCount(): Flow<Int>

    @Query("DELETE FROM archived_posts WHERE scanDate < :cutoffTimestamp")
    suspend fun deleteOlderThan(cutoffTimestamp: Long): Int

    suspend fun deleteOlderThanDays(days: Int): Int {
        val cutoff = System.currentTimeMillis() - (days.toLong() * 24L * 60L * 60L * 1000L)
        return deleteOlderThan(cutoff)
    }

    @Query("DELETE FROM archived_posts WHERE groupName = :groupName")
    suspend fun clearGroupPosts(groupName: String): Int

    @Query("DELETE FROM archived_posts WHERE id = :id")
    suspend fun deletePostById(id: String): Int

    @Query("SELECT normalizedKey FROM archived_posts WHERE groupName = :groupName")
    suspend fun getExistingKeysForGroup(groupName: String): List<String>
}
