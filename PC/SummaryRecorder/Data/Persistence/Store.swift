import SwiftData
import Foundation

@MainActor
final class Store {
    let modelContainer: ModelContainer

    init() throws {
        let schema = Schema([Session.self, Chunk.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)
        modelContainer = try ModelContainer(for: schema, configurations: [config])
    }

    var modelContext: ModelContext {
        modelContainer.mainContext
    }
}
