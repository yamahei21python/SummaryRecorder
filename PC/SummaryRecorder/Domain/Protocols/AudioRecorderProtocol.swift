import Foundation

protocol AudioRecorderProtocol: Sendable {
    func isRecording() async -> Bool
    func audioLevel() async -> Double
    func start(outputDirectory: URL) async throws -> URL
    func pause() async
    func resume() async
    func stop() async throws -> URL
}
