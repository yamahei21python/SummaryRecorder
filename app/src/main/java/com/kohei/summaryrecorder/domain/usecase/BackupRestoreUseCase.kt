package com.kohei.summaryrecorder.domain.usecase

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.kohei.summaryrecorder.data.db.SummaryDao
import com.kohei.summaryrecorder.data.db.SummaryEntity
import com.kohei.summaryrecorder.data.db.SummaryStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

data class BackupData(
    val version: Int = 1,
    val summaries: List<SummaryEntity>
)

class BackupRestoreUseCase @Inject constructor(
    private val summaryDao: SummaryDao,
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private val recordingsDir get() = File(context.filesDir, "recordings")

    /**
     * DB内のDONE要約 + 対応WAVファイルをZIPにエクスポート。
     * ZIP構造:
     *   backup.json              — SummaryEntityのJSON配列
     *   audio/{sessionId}.wav    — 音声ファイル
     *
     * @return エクスポート件数
     */
    suspend fun exportToUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val summaries = summaryDao.getAll()
            val doneSummaries = summaries.filter { it.status == SummaryStatus.DONE }

            context.contentResolver.openOutputStream(uri, "w")?.use { outputStream ->
                BufferedOutputStream(outputStream).use { buffered ->
                    ZipOutputStream(buffered).use { zip ->
                        // 1. backup.json
                        val json = gson.toJson(BackupData(summaries = doneSummaries))
                        zip.putNextEntry(ZipEntry("backup.json"))
                        zip.write(json.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()

                        // 2. WAV files
                        var audioCount = 0
                        for (entity in doneSummaries) {
                            if (entity.audioFilePath.isBlank()) continue
                            val wavFile = File(entity.audioFilePath)
                            if (!wavFile.exists()) continue
                            zip.putNextEntry(ZipEntry("audio/${wavFile.name}"))
                            FileInputStream(wavFile).use { input ->
                                val buffer = ByteArray(8192)
                                var len = input.read(buffer)
                                while (len > 0) {
                                    zip.write(buffer, 0, len)
                                    len = input.read(buffer)
                                }
                            }
                            zip.closeEntry()
                            audioCount++
                        }

                        Log.i("BackupRestore", "Exported ${doneSummaries.size} summaries, $audioCount audio files")
                    }
                }
            } ?: throw IllegalStateException("Cannot open output stream for URI: $uri")

            doneSummaries.size
        }
    }

    /**
     * ZIPから要約データをインポート。
     * 既存データとの重複(sessionId一致)はREPLACE戦略で上書き。
     *
     * @return インポート件数
     */
    suspend fun importFromUri(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var importedCount = 0

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedInputStream(inputStream).use { buffered ->
                    ZipInputStream(buffered).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            when {
                                name == "backup.json" -> {
                                    val json = zip.readBytes().toString(Charsets.UTF_8)
                                    val backupData: BackupData = gson.fromJson(
                                        json,
                                        object : TypeToken<BackupData>() {}.type
                                    )
                                    for (entity in backupData.summaries) {
                                        // sessionIdが重複時はREPLACE（DAOのinsertはOnConflictStrategy.REPLACE）
                                        summaryDao.insert(entity)
                                        importedCount++
                                    }
                                }
                                name.startsWith("audio/") -> {
                                    val fileName = name.substringAfter("audio/")
                                    val outFile = File(recordingsDir, fileName)
                                    recordingsDir.mkdirs()
                                    FileOutputStream(outFile).use { output ->
                                        val buffer = ByteArray(8192)
                                        var len = zip.read(buffer)
                                        while (len > 0) {
                                            output.write(buffer, 0, len)
                                            len = zip.read(buffer)
                                        }
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                }
            } ?: throw IllegalStateException("Cannot open input stream for URI: $uri")

            Log.i("BackupRestore", "Imported $importedCount summaries")
            importedCount
        }
    }
}
