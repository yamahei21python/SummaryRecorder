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

        // Bind recorder state
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

        // Forward appConfig changes to trigger view updates
        appConfig.objectWillChange
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                self?.objectWillChange.send()
            }
            .store(in: &cancellables)
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
    }

    func selectSession(_ session: Session) {
        selectedSession = session
        if !session.isRead {
            session.isRead = true
            try? modelContext.save()
        }
    }

    // MARK: - Recording

    func startRecording() async {
        guard canStartRecording else { return }

        // Request microphone permission if needed
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
            let recordingsDir = FileManager.default
                .urls(for: .applicationSupportDirectory, in: .userDomainMask)
                .first!
                .appendingPathComponent("recordings", isDirectory: true)
            try FileManager.default.createDirectory(at: recordingsDir, withIntermediateDirectories: true)

            let url = try await recorder.start(outputDirectory: recordingsDir)
            currentRecordingURL = url

            let sessionId = UUID(uuidString: url.deletingPathExtension().lastPathComponent)!.uuidString
            let session = Session(
                sessionId: sessionId,
                wavFileName: url.lastPathComponent,
                durationMs: 0,
                status: SessionStatus.recorded.rawValue
            )
            modelContext.insert(session)
            try? modelContext.save()

            selectedSession = session
            fetchSessions()
        } catch {
            errorMessage = "録音開始失敗: \(error.localizedDescription)"
            showError = true
        }
    }

    func pauseRecording() async {
        await recorder.pause()
    }

    func resumeRecording() async {
        await recorder.resume()
    }

    func stopRecording() async {
        guard recorderState != .idle else { return }

        // Capture duration BEFORE stop resets it
        let finalDuration = recordingDuration

        do {
            _ = try await recorder.stop()
            currentRecordingURL = nil

            if let session = selectedSession {
                session.durationMs = finalDuration * 1000
                try? modelContext.save()
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
        session.status = SessionStatus.summarizing.rawValue
        let cancelState = CancelState()
        currentCancelState = cancelState
        try? modelContext.save()

        do {
            let service = appConfig.makeTranscriptionService()
            let text = try await service.transcribe(wavURL: wavURL, cancelState: cancelState)

            session.transcriptionText = text
            session.status = SessionStatus.recorded.rawValue

            if appConfig.autoSummarize {
                await summarize()
            }
        } catch is CancellationError {
            session.status = SessionStatus.recorded.rawValue
        } catch {
            session.status = SessionStatus.error.rawValue
            session.errorMessage = error.localizedDescription
            errorMessage = "文字起こし失敗: \(error.localizedDescription)"
            showError = true
        }

        try? modelContext.save()
        isTranscribing = false
        currentCancelState = nil
        fetchSessions()
    }

    // MARK: - Summarization

    func summarize() async {
        guard let session = selectedSession,
              let status = SessionStatus(rawValue: session.status),
              status != .summarizing else { return }

        isSummarizing = true
        session.status = SessionStatus.summarizing.rawValue
        let cancelState = CancelState()
        currentCancelState = cancelState
        try? modelContext.save()

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
            session.status = SessionStatus.done.rawValue
        } catch is CancellationError {
            session.status = SessionStatus.recorded.rawValue
        } catch {
            session.status = SessionStatus.error.rawValue
            session.errorMessage = error.localizedDescription
            errorMessage = "要約失敗: \(error.localizedDescription)"
            showError = true
        }

        try? modelContext.save()
        isSummarizing = false
        currentCancelState = nil
        fetchSessions()
    }

    // MARK: - Delete

    func deleteSession(_ session: Session) {
        // Delete audio file
        let recordingsDir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first!
            .appendingPathComponent("recordings", isDirectory: true)
        let fileURL = recordingsDir.appendingPathComponent(session.wavFileName)
        try? FileManager.default.removeItem(at: fileURL)

        // Delete from SwiftData
        modelContext.delete(session)
        try? modelContext.save()

        if selectedSession?.sessionId == session.sessionId {
            selectedSession = nil
        }
        fetchSessions()
    }

    // MARK: - Title Edit

    func updateTitle(_ newTitle: String) {
        guard let session = selectedSession else { return }
        let truncated = String(newTitle.prefix(20))
        session.title = truncated
        if newTitle.count > 0 {
            session.isTitleEdited = true
        }
        try? modelContext.save()
    }

    // MARK: - Retry

    func retryTranscription() async {
        guard let session = selectedSession else { return }
        session.status = SessionStatus.recorded.rawValue
        try? modelContext.save()
        await transcribe()
    }

    func retrySummarization() async {
        guard let session = selectedSession else { return }
        session.status = SessionStatus.recorded.rawValue
        session.errorMessage = nil
        try? modelContext.save()
        await summarize()
    }

    // MARK: - E2E Test

    /// E2Eテスト開始
    func startE2ETest() async {
        print("[E2E] args=\(CommandLine.arguments)")
        guard let bundledURL = Bundle.main.url(forResource: "jfk", withExtension: "wav")
        else { return }

        let isLocal = CommandLine.arguments.contains("-E2E-LOCAL")
        guard CommandLine.arguments.contains("-E2E") || isLocal else { return }

        if isLocal {
            setupLocalModes()
        } else {
            setupCloudModes()
        }

        try? copyTestFile(from: bundledURL)
        let session = createTestSession()

        modelContext.insert(session)
        try? modelContext.save()
        selectedSession = session
        fetchSessions()

        await transcribe()
        // summarize() is called inside transcribe() when autoSummarize is true
        if !appConfig.autoSummarize {
            await summarize()
        }

        // E2E test complete — print result and exit
        if let s = selectedSession {
            print("[E2E-RESULT] title='\(s.title)' summaryText='\(s.summaryText ?? "")' status=\(s.status)")
        }
        print("[E2E] DONE — exiting")
        fflush(stdout)
        exit(0)
    }

    private func setupCloudModes() {
        appConfig.transcriptionMode = .groq
        appConfig.summarizationMode = .gemini
    }

    private func setupLocalModes() {
        print("[E2E-LOCAL] llamaModelPath='\(appConfig.llamaModelPath)'")
        print("[E2E-LOCAL] areModelsDownloaded=\(appConfig.areModelsDownloaded)")
        appConfig.transcriptionMode = .mlx
        appConfig.summarizationMode = .local
    }

    private func copyTestFile(from bundledURL: URL) throws {
        let recordingsDir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first!
            .appendingPathComponent("recordings", isDirectory: true)
        try FileManager.default.createDirectory(at: recordingsDir, withIntermediateDirectories: true)

        let destURL = recordingsDir.appendingPathComponent("jfk_e2e.wav")
        try? FileManager.default.removeItem(at: destURL)
        try FileManager.default.copyItem(at: bundledURL, to: destURL)
    }

    private func createTestSession() -> Session {
        Session(
            sessionId: UUID().uuidString,
            wavFileName: "jfk_e2e.wav",
            durationMs: 11000,
            status: SessionStatus.recorded.rawValue
        )
    }

    // MARK: - Private

    private func resolveWavURL(for session: Session) -> URL? {
        let recordingsDir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first!
            .appendingPathComponent("recordings", isDirectory: true)
        let url = recordingsDir.appendingPathComponent(session.wavFileName)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    private func fullTranscriptionText(for session: Session) -> String {
        if !session.transcriptionText.isEmpty {
            return session.transcriptionText
        }
        return session.chunks
            .filter { $0.status == ChunkStatus.done.rawValue }
            .sorted { $0.chunkIndex < $1.chunkIndex }
            .compactMap { $0.transcriptionText }
            .joined(separator: "\n\n")
    }
}
