package com.kohei.summaryrecorder.domain.usecase

import com.kohei.summaryrecorder.data.db.SummaryDao
import java.io.File
import javax.inject.Inject

class DeleteSummaryUseCase @Inject constructor(
    private val summaryDao: SummaryDao
) {
    suspend fun execute(sessionId: String, audioFilePath: String) {
        File(audioFilePath).delete()
        summaryDao.delete(sessionId)
    }
}
