enum ChunkStatus: String, Codable, CaseIterable, Sendable {
    case pending
    case uploading
    case done
    case failed
}
