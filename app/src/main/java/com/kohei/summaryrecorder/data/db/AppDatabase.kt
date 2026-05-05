package com.kohei.summaryrecorder.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ChunkEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chunkDao(): ChunkDao

    companion object {
        const val DB_NAME = "summary_recorder.db"

        /**
         * テスト用: inMemoryインスタンスを生成
         */
        fun createInMemory(context: Context): AppDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java
            ).allowMainThreadQueries().build()
        }
    }
}
