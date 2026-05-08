import SwiftUI
import SwiftData

@Model
final class Session {
    @Attribute(.unique) var sessionId: String
    var createdAt: Date
    var title: String
    var isTitleEdited: Bool
    var summaryText: String
    var transcriptionText: String
    var wavFileName: String
    var durationMs: Double
    var status: SessionStatus
    var isRead: Bool
    var errorMessage: String?
    @Relationship(deleteRule: .cascade) var chunks: [Chunk]

    init(sessionId: String = UUID().uuidString,
         createdAt: Date = .now,
         title: String = "",
         isTitleEdited: Bool = false,
         summaryText: String = "",
         transcriptionText: String = "",
         wavFileName: String = "",
         durationMs: Double = 0,
         status: SessionStatus = .recorded,
         isRead: Bool = false,
         errorMessage: String? = nil) {
        self.sessionId = sessionId
        self.createdAt = createdAt
        self.title = title
        self.isTitleEdited = isTitleEdited
        self.summaryText = summaryText
        self.transcriptionText = transcriptionText
        self.wavFileName = wavFileName
        self.durationMs = durationMs
        self.status = status
        self.isRead = isRead
        self.errorMessage = errorMessage
        self.chunks = []
    }
}
