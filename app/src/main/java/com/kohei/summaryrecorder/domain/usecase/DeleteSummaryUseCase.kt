package com.kohei.summaryrecorder.domain.usecase

import com.kohei.summaryrecorder.data.db.SummaryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class DeleteSummaryUseCase @Inject constructor(
    private val summaryDao: SummaryDao
) {
    suspend fun execute(sessionId: String, audioFilePath: String) {
        withContext(Dispatchers.IO) {
            val file = File(audioFilePath)
            if (file.exists() && !file.delete()) {
                android.util.Log.w("DeleteSummaryUseCase", "Failed to delete file: $audioFilePath")
            }
        }
        summaryDao.delete(sessionId)
    }
}
