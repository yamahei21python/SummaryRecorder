import SwiftData

@Model
final class Chunk {
    var chunkIndex: Int
    var status: String
    var filePath: String
    var transcriptionText: String?
    var session: Session?

    init(chunkIndex: Int = 0,
         status: ChunkStatus.RawValue = ChunkStatus.pending.rawValue,
         filePath: String = "",
         transcriptionText: String? = nil) {
        self.chunkIndex = chunkIndex
        self.status = status
        self.filePath = filePath
        self.transcriptionText = transcriptionText
    }
}
