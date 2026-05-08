import Foundation
import Combine

@MainActor
final class ModelDownloadService: NSObject, ObservableObject, URLSessionDownloadDelegate {

    // MARK: - Published State

    @Published var whisperProgress: Double = 0
    @Published var llamaProgress: Double = 0
    @Published var whisperStatus: String = "idle"
    @Published var llamaStatus: String = "idle"
    @Published var whisperError: String?
    @Published var llamaError: String?
    @Published var isDownloading: Bool = false

    // MARK: - Model URLs

    private static let whisperRemoteURL = APIEndpoint.whisperDownload
    private static let llamaRemoteURL = APIEndpoint.llamaDownload

    private static let whisperFilename = ModelFileName.whisper
    private static let llamaFilename = ModelFileName.llama

    // MARK: - Storage

    private static var modelsDirectory: URL { AppPaths.modelsDirectory }

    private var whisperTask: URLSessionDownloadTask?
    private var llamaTask: URLSessionDownloadTask?
    private var whisperContinuation: CheckedContinuation<URL, Error>?
    private var llamaContinuation: CheckedContinuation<URL, Error>?
    private var whisperDestination: URL?
    private var llamaDestination: URL?

    // MARK: - Download Methods

    func downloadWhisperModel() async throws -> URL {
        let dir = Self.modelsDirectory
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        let destination = dir.appendingPathComponent(Self.whisperFilename)
        if FileManager.default.fileExists(atPath: destination.path) {
            whisperStatus = "done"
            whisperProgress = 1.0
            return destination
        }

        whisperStatus = "downloading"
        whisperProgress = 0
        whisperError = nil
        whisperDestination = destination

        return try await withCheckedThrowingContinuation { continuation in
            self.whisperContinuation = continuation
            let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
            let task = session.downloadTask(with: Self.whisperRemoteURL)
            self.whisperTask = task
            task.resume()
            session.finishTasksAndInvalidate()
        }
    }

    func downloadLLaMAModel() async throws -> URL {
        let dir = Self.modelsDirectory
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        let destination = dir.appendingPathComponent(Self.llamaFilename)
        if FileManager.default.fileExists(atPath: destination.path) {
            llamaStatus = "done"
            llamaProgress = 1.0
            return destination
        }

        llamaStatus = "downloading"
        llamaProgress = 0
        llamaError = nil
        llamaDestination = destination

        return try await withCheckedThrowingContinuation { continuation in
            self.llamaContinuation = continuation
            let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
            let task = session.downloadTask(with: Self.llamaRemoteURL)
            self.llamaTask = task
            task.resume()
            session.finishTasksAndInvalidate()
        }
    }

    func downloadAllModels() async throws -> (whisperURL: URL, llamaURL: URL) {
        isDownloading = true
        defer { isDownloading = false }

        let whisperURL = try await downloadWhisperModel()
        let llamaURL = try await downloadLLaMAModel()
        return (whisperURL, llamaURL)
    }

    func cancelDownloads() {
        whisperTask?.cancel()
        llamaTask?.cancel()
        whisperTask = nil
        llamaTask = nil

        whisperContinuation?.resume(throwing: NSError(domain: "ModelDownload", code: -999, userInfo: [NSLocalizedDescriptionKey: "キャンセル済"]))
        llamaContinuation?.resume(throwing: NSError(domain: "ModelDownload", code: -999, userInfo: [NSLocalizedDescriptionKey: "キャンセル済"]))
        whisperContinuation = nil
        llamaContinuation = nil

        whisperStatus = "idle"
        llamaStatus = "idle"
        whisperProgress = 0
        llamaProgress = 0
    }

    // MARK: - URLSessionDownloadDelegate

    nonisolated func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        let progress = totalBytesExpectedToWrite > 0
            ? Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)
            : 0

        Task { @MainActor [weak self] in
            guard let self else { return }
            if downloadTask === self.whisperTask {
                self.whisperProgress = progress
            } else if downloadTask === self.llamaTask {
                self.llamaProgress = progress
            }
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            if downloadTask === self.whisperTask, let dest = self.whisperDestination {
                try? FileManager.default.moveItem(at: location, to: dest)
                self.whisperStatus = "done"
                self.whisperProgress = 1.0
            } else if downloadTask === self.llamaTask, let dest = self.llamaDestination {
                try? FileManager.default.moveItem(at: location, to: dest)
                self.llamaStatus = "done"
                self.llamaProgress = 1.0
            }
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        Task { @MainActor [weak self] in
            guard let self else { return }

            if task === self.whisperTask {
                if let error {
                    self.whisperStatus = "error"
                    self.whisperError = error.localizedDescription
                    self.whisperContinuation?.resume(throwing: error)
                } else if let dest = self.whisperDestination {
                    self.whisperContinuation?.resume(returning: dest)
                }
                self.whisperContinuation = nil
            } else if task === self.llamaTask {
                if let error {
                    self.llamaStatus = "error"
                    self.llamaError = error.localizedDescription
                    self.llamaContinuation?.resume(throwing: error)
                } else if let dest = self.llamaDestination {
                    self.llamaContinuation?.resume(returning: dest)
                }
                self.llamaContinuation = nil
            }
        }
    }
}
