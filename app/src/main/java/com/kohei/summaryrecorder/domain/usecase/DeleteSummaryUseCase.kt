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
            File(audioFilePath).delete()
        }
        summaryDao.delete(sessionId)
    }
}
