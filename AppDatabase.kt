package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SavedCard::class, ArchivedPost::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun savedCardDao(): SavedCardDao
    abstract fun postDao(): PostDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE archived_posts ADD COLUMN authorAvatarUrl TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE archived_posts ADD COLUMN isMediaRich INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE archived_posts ADD COLUMN cleanSnippet TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bo_lite.db"
                )
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
