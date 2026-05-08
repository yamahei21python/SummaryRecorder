import SwiftUI
import SwiftData
import AVFoundation
import Combine

@MainActor
final class MainViewModel: ObservableObject {
    // MARK: - Published State
    @Published var sessions: [Session] = []
    @Published var selectedSession: Session?
    @Published var recorderState: RecorderState = .idle
    @Published var recordingDuration: TimeInterval = 0
    @Published var audioLevel: Double = 0.0
    @Published var isTranscribing = false
    @Published var isSummarizing = false
    @Published var errorMessage: String?
    @Published var showError = false

    // MARK: - Dependencies
    private let recorder: AudioRecorder
    private let appConfig: AppConfig
    private let modelContext: ModelContext
    private var currentRecordingURL: URL?
    private var currentCancelState: CancelState?
    private var cancellables = Set<AnyCancellable>()

    // MARK: - Computed
    var isRecording: Bool { recorderState == .recording }
    var isPaused: Bool { recorderState == .paused }
    var canStartRecording: Bool { recorderState == .idle }
    var selectedSessionChunks: [Chunk] {
        selectedSession?.chunks.sorted(by: { $0.chunkIndex < $1.chunkIndex }) ?? []
    }
    var isInGroqMode: Bool {
        appConfig.transcriptionMode == .groq
    }

    // MARK: - Init
    init(recorder: AudioRecorder = AudioRecorder(),
         appConfig: AppConfig = .shared,
         modelContext: ModelContext) {
        self.recorder = recorder
        self.appConfig = appConfig
        self.modelContext = modelContext

        recorder.$state
            .receive(on: RunLoop.main)
            .assign(to: \.recorderState, on: self)
            .store(in: &cancellables)

        recorder.$recordingDuration
            .receive(on: RunLoop.main)
            .assign(to: \.recordingDuration, on: self)
            .store(in: &cancellables)

        recorder.$audioLevel
            .receive(on: RunLoop.main)
            .assign(to: \.audioLevel, on: self)
            .store(in: &cancellables)

        appConfig.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                self?.objectWillChange.send()
            }
            .store(in: &cancellables)
    }

    // MARK: - Persistence Helper

    private func saveContext(_ message: String = "") {
        do {
            try modelContext.save()
        } catch {
            NSLog("[MainViewModel] save failed: %@ — %@", message, error.localizedDescription)
        }
    }

    // MARK: - Session List

    func fetchSessions() {
        let descriptor = FetchDescriptor<Session>(
            sortBy: [SortDescriptor(\.createdAt, order: .reverse)]
        )
        do {
            sessions = try modelContext.fetch(descriptor)
        } catch {
            errorMessage = "セッション取得失敗: \(error.localizedDescription)"
            showError = true
        }
        cleanupOrphanedRecordings()
    }

    private func cleanupOrphanedRecordings() {
        let fm = FileManager.default
        let recordingsDir = AppPaths.recordingsDirectory

        guard let diskFiles = try? fm.contentsOfDirectory(at: recordingsDir, includingPropertiesForKeys: nil) else { return }

        let dbFileNames = Set(sessions.map { $0.wavFileName })

        for fileURL in diskFiles {
            let fileName = fileURL.lastPathComponent
            if fileName.hasSuffix(".wav") && !dbFileNames.contains(fileName) {
                try? fm.removeItem(at: fileURL)
                NSLog("[Cleanup] Deleted orphaned WAV: \(fileName)")
            }
            var isDir: ObjCBool = false
            if fm.fileExists(atPath: fileURL.path, isDirectory: &isDir), isDir.boolValue {
                let sessionId = fileName
                let hasSession = sessions.contains { $0.sessionId == sessionId }
                if !hasSession {
                    try? fm.removeItem(at: fileURL)
                    NSLog("[Cleanup] Deleted orphaned chunk dir: \(sessionId)")
                }
            }
        }
    }

    func selectSession(_ session: Session) {
        selectedSession = session
        if !session.isRead {
            session.isRead = true
            saveContext("selectSession")
        }
    }

    // MARK: - Recording

    func startRecording() async {
        guard canStartRecording else { return }

        let permission = AVCaptureDevice.authorizationStatus(for: .audio)
        if permission == .notDetermined {
            let granted = await AVCaptureDevice.requestAccess(for: .audio)
            guard granted else {
                errorMessage = "マイク許可が必要です"
                showError = true
                return
            }
        } else if permission == .denied {
            errorMessage = "マイク許可が拒否されています。システム設定＞プライバシーとセキュリティ＞マイクから許可してください。"
            showError = true
            return
        }

        do {
            let recordingsDir = AppPaths.recordingsDirectory
            try FileManager.default.createDirectory(at: recordingsDir, withIntermediateDirectories: true)

            let url = try await recorder.start(outputDirectory: recordingsDir)
            currentRecordingURL = url

            let session = Session(
                sessionId: UUID().uuidString,
                wavFileName: url.lastPathComponent,
                durationMs: 0,
                status: .recorded
            )
            modelContext.insert(session)
            saveContext("startRecording")

            selectedSession = session
            fetchSessions()
        } catch {
            errorMessage = "録音開始失敗: \(error.localizedDescription)"
            showError = true
        }
    }

    func pauseRecording() async { await recorder.pause() }
    func resumeRecording() async { await recorder.resume() }

    func stopRecording() async {
        guard recorderState != .idle else { return }
        let finalDuration = recordingDuration

        do {
            _ = try await recorder.stop()
            recorderState = .idle
            currentRecordingURL = nil

            if let session = selectedSession {
                session.durationMs = finalDuration * 1000
                saveContext("stopRecording")
            }

            await transcribe()
            fetchSessions()
        } catch {
            errorMessage = "録音停止失敗: \(error.localizedDescription)"
            showError = true
        }
    }

    // MARK: - Transcription

    func transcribe() async {
        guard let session = selectedSession,
              let wavURL = resolveWavURL(for: session) else { return }

        isTranscribing = true
        session.status = .summarizing
        let cancelState = CancelState()
        currentCancelState = cancelState
        saveContext("transcribe-start")

        do {
            let service = appConfig.makeTranscriptionService()
            let text = try await service.transcribe(wavURL: wavURL, cancelState: cancelState)

            session.transcriptionText = text
            session.status = .recorded

            if appConfig.autoSummarize {
                isTranscribing = false
                currentCancelState = nil
                await summarize()
            }
        } catch is CancellationError {
            session.status = .recorded
        } catch {
            session.status = .error
            session.errorMessage = error.localizedDescription
            NSLog("[Transcription] error: %@", error.localizedDescription)
            errorMessage = "文字起こし失敗: \(error.localizedDescription)"
            showError = true
        }

        saveContext("transcribe-end")
        isTranscribing = false
        currentCancelState = nil
        fetchSessions()
    }

    // MARK: - Summarization

    func summarize() async {
        guard let session = selectedSession, session.status != .summarizing else { return }

        isSummarizing = true
        session.status = .summarizing
        let cancelState = CancelState()
        currentCancelState = cancelState
        saveContext("summarize-start")

        do {
            let service = appConfig.makeSummarizationService()
            let output = try await service.summarize(
                text: fullTranscriptionText(for: session),
                cancelState: cancelState
            )

            session.summaryText = output.summaryText
            if !session.isTitleEdited {
                session.title = output.title
            }
            session.status = .done
        } catch is CancellationError {
            session.status = .recorded
        } catch {
            session.status = .error
            session.errorMessage = error.localizedDescription
            NSLog("[Summarization] error: %@", error.localizedDescription)
            errorMessage = "要約失敗: \(error.localizedDescription)"
            showError = true
        }

        saveContext("summarize-end")
        isSummarizing = false
        currentCancelState = nil
        fetchSessions()
    }

    // MARK: - Delete

    func deleteSession(_ session: Session) {
        let deleteUseCase = DeleteSessionUseCase()
        try? deleteUseCase.execute(session: session)

        modelContext.delete(session)
        saveContext("deleteSession")

        if selectedSession?.sessionId == session.sessionId {
            selectedSession = nil
        }
        fetchSessions()
    }

    // MARK: - Title Edit

    func updateTitle(_ newTitle: String) {
        guard let session = selectedSession else { return }
        session.title = String(newTitle.prefix(AppLimits.titleMaxLength))
        if !newTitle.isEmpty { session.isTitleEdited = true }
        saveContext("updateTitle")
    }

    // MARK: - Retry

    func retryTranscription() async {
        guard let session = selectedSession else { return }
        session.status = .recorded
        saveContext("retryTranscription")
        await transcribe()
    }

    func retrySummarization() async {
        guard let session = selectedSession else { return }
        session.status = .recorded
        session.errorMessage = nil
        saveContext("retrySummarization")
        await summarize()
    }

    func resetStuckSession() {
        guard let session = selectedSession else { return }
        currentCancelState?.cancel()
        isSummarizing = false
        isTranscribing = false
        session.status = .recorded
        session.errorMessage = nil
        saveContext("resetStuckSession")
        fetchSessions()
    }

    // MARK: - E2E Test

    #if DEBUG
    func startE2ETest() async {
        NSLog("[E2E] args=%@", CommandLine.arguments.description)
        guard let bundledURL = Bundle.main.url(forResource: "jfk", withExtension: "wav") else { return }

        let isLocal = CommandLine.arguments.contains("-E2E-LOCAL")
        guard CommandLine.arguments.contains("-E2E") || isLocal else { return }

        if isLocal { setupLocalModes() } else { setupCloudModes() }

        try? copyTestFile(from: bundledURL)
        let session = createTestSession()

        modelContext.insert(session)
        saveContext("e2e-insert")
        selectedSession = session
        fetchSessions()

        await transcribe()
        if !appConfig.autoSummarize { await summarize() }

        if let s = selectedSession {
            NSLog("[E2E-RESULT] title='%@' summaryText='%@' status=%@", s.title, s.summaryText ?? "", String(describing: s.status))
        }
        NSLog("[E2E] DONE — exiting")
        exit(0)
    }

    private func setupCloudModes() {
        appConfig.transcriptionMode = .groq
        appConfig.summarizationMode = .gemini
    }

    private func setupLocalModes() {
        NSLog("[E2E-LOCAL] llamaModelPath='%@'", appConfig.llamaModelPath)
        NSLog("[E2E-LOCAL] areModelsDownloaded=%d", appConfig.areModelsDownloaded)
        appConfig.transcriptionMode = .mlx
        appConfig.summarizationMode = .local
    }

    private func copyTestFile(from bundledURL: URL) throws {
        let recordingsDir = AppPaths.recordingsDirectory
        try FileManager.default.createDirectory(at: recordingsDir, withIntermediateDirectories: true)
        let destURL = recordingsDir.appendingPathComponent("jfk_e2e.wav")
        try? FileManager.default.removeItem(at: destURL)
        try FileManager.default.copyItem(at: bundledURL, to: destURL)
    }

    private func createTestSession() -> Session {
        Session(sessionId: UUID().uuidString, wavFileName: "jfk_e2e.wav", durationMs: 11000, status: .recorded)
    }
    #endif

    // MARK: - Private

    private func resolveWavURL(for session: Session) -> URL? {
        let url = AppPaths.recordingsDirectory.appendingPathComponent(session.wavFileName)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    private func fullTranscriptionText(for session: Session) -> String {
        if !session.transcriptionText.isEmpty { return session.transcriptionText }
        return session.chunks
            .filter { $0.status == .done }
            .sorted { $0.chunkIndex < $1.chunkIndex }
            .compactMap { $0.transcriptionText }
            .joined(separator: "\n\n")
    }
}
