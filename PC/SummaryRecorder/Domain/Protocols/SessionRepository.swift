import Foundation

protocol SessionRepository: AnyObject, Sendable {
    func fetchAll() async throws -> [Session]
    func fetch(by id: String) async throws -> Session?
    func save(_ session: Session) async throws
    func delete(_ session: Session) async throws
}
