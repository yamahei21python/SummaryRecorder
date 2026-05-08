package com.kohei.summaryrecorder.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Migration 1→2 test. Since exportSchema=false, we manually create v1 DB
 * then open with Room using MIGRATION_1_2.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [31], manifest = Config.NONE)
class AppDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "test-migration.db"

    @Before
    fun setUp() {
        context.getDatabasePath(dbName).delete()
    }

    @After
    fun tearDown() {
        context.getDatabasePath(dbName).delete()
    }

    /**
     * Create a v1 database with only the chunks table.
     */
    private fun createV1Database() {
        val factory = FrameworkSQLiteOpenHelperFactory()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE IF NOT EXISTS chunks (
                            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                            session_id TEXT NOT NULL,
                            chunk_index INTEGER NOT NULL,
                            file_path TEXT NOT NULL,
                            status TEXT NOT NULL,
                            transcription_text TEXT,
                            created_at INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            is_last INTEGER NOT NULL
                        )
                    """.trimIndent())
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_chunks_session_id ON chunks(session_id)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_chunks_status ON chunks(status)")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val helper = factory.create(config)
        helper.writableDatabase.close()
        helper.close()
    }

    @Test
    fun `migration 1 to 2 creates summaries table`() = runTest {
        createV1Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        val summaryDao = db.summaryDao()

        // Insert + query to verify summaries table exists
        summaryDao.insert(SummaryEntity(
            sessionId = "s1",
            createdAt = 1000L,
            title = "test",
            summaryText = "summary",
            transcriptionText = "transcription",
            audioFilePath = "/f.wav",
            durationMs = 5000L,
            status = SummaryStatus.DONE,
            isRead = false
        ))

        val result = summaryDao.getBySessionId("s1")
        assertTrue(result != null)
        assertEquals("test", result.title)
        assertEquals("summary", result.summaryText)

        db.close()
    }

    @Test
    fun `migration 1 to 2 preserves existing chunks data`() = runTest {
        createV1Database()

        // Insert chunk data into v1 DB directly
        val rawDb = SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE
        )
        val values = ContentValues().apply {
            put("session_id", "session-1")
            put("chunk_index", 0)
            put("file_path", "/recordings/chunk_0.wav")
            put("status", "DONE")
            put("transcription_text", "テスト文字起こし")
            put("created_at", 1000L)
            put("updated_at", 1000L)
            put("is_last", 1)
        }
        rawDb.insert("chunks", null, values)
        rawDb.close()

        // Open with migration
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        // Verify chunks preserved via direct query
        val cursor = db.openHelper.readableDatabase.query("SELECT * FROM chunks WHERE session_id = 'session-1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("テスト文字起こし", cursor.getString(cursor.getColumnIndexOrThrow("transcription_text")))
        cursor.close()

        db.close()
    }

    @Test
    fun `summaries table after migration supports full CRUD`() = runTest {
        createV1Database()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

        val dao = db.summaryDao()

        // Insert
        dao.insert(SummaryEntity(
            sessionId = "s1", createdAt = 2000L, title = "テスト",
            summaryText = "要約", transcriptionText = "転写",
            audioFilePath = "/f.wav", durationMs = 5000L,
            status = SummaryStatus.RECORDED
        ))

        // Read
        val entity = dao.getBySessionId("s1")!!
        assertEquals("テスト", entity.title)
        assertEquals(SummaryStatus.RECORDED, entity.status)
        assertEquals(false, entity.isRead)

        // Update status
        dao.updateStatusAndContent("s1", SummaryStatus.DONE, "新タイトル", "要約文", "転写文")
        val updated = dao.getBySessionId("s1")!!
        assertEquals(SummaryStatus.DONE, updated.status)
        assertEquals("新タイトル", updated.title)

        // Mark read
        dao.updateRead("s1", true)
        assertTrue(dao.getBySessionId("s1")!!.isRead)

        // Delete
        dao.delete("s1")
        assertTrue(dao.getBySessionId("s1") == null)

        db.close()
    }
}
