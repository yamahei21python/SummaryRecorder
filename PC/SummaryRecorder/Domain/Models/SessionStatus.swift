enum SessionStatus: String, Codable, CaseIterable, Sendable {
    case recorded
    case summarizing
    case done
    case error
}
