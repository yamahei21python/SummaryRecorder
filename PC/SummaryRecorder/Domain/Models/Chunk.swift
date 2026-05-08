import SwiftData

@Model
final class Chunk {
    var chunkIndex: Int
    var status: ChunkStatus
    var filePath: String
    var transcriptionText: String?
    var session: Session?

    init(chunkIndex: Int = 0,
         status: ChunkStatus = .pending,
         filePath: String = "",
         transcriptionText: String? = nil) {
        self.chunkIndex = chunkIndex
        self.status = status
        self.filePath = filePath
        self.transcriptionText = transcriptionText
    }
}
