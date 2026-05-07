import SwiftUI
import AVFoundation

struct SessionDetailView: View {
    @ObservedObject var viewModel: MainViewModel
    let session: Session
    @State private var selectedTab = 0
    @State private var isEditingTitle = false
    @State private var titleText = ""
    @State private var audioPlayer: AVAudioPlayer?
    @State private var isPlaying = false
    @State private var playbackTime: TimeInterval = 0
    @State private var playbackSpeed: Float = 1.0

    var body: some View {
        VStack(spacing: 0) {
            headerView

            Divider()

            TabView(selection: $selectedTab) {
                summaryTab
                    .tabItem {
                        Label("要約", systemImage: "doc.text")
                    }
                    .tag(0)

                transcriptionTab
                    .tabItem {
                        Label("文字起こし", systemImage: "text.quote")
                    }
                    .tag(1)

                if viewModel.isInGroqMode && !session.chunks.isEmpty {
                    chunksTab
                        .tabItem {
                            Label("チャンク", systemImage: "list.bullet")
                        }
                        .tag(2)
                }
            }

            Divider()

            playbackControls
        }
        .padding()
    }

    // MARK: - Header

    @ViewBuilder
    private var headerView: some View {
        HStack {
            if isEditingTitle {
                TextField("タイトル", text: $titleText)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit {
                        viewModel.updateTitle(titleText)
                        isEditingTitle = false
                    }
                    .onAppear {
                        titleText = session.title
                    }
            } else {
                Text(session.title.isEmpty ? "Untitled" : session.title)
                    .font(.title2.bold())
                    .onTapGesture(count: 2) {
                        isEditingTitle = true
                    }
            }

            Spacer()

            statusBadge

            Menu {
                Button("要約実行") {
                    Task { await viewModel.summarize() }
                }
                .disabled(session.status == SessionStatus.summarizing.rawValue)

                Button("再試行") {
                    Task {
                        if session.status == SessionStatus.error.rawValue {
                            await viewModel.retryTranscription()
                            await viewModel.retrySummarization()
                        }
                    }
                }
                .disabled(session.status != SessionStatus.error.rawValue)

                Divider()

                Button("削除", role: .destructive) {
                    viewModel.deleteSession(session)
                }
            } label: {
                Image(systemName: "ellipsis")
            }
        }
    }

    @ViewBuilder
    private var statusBadge: some View {
        if let status = SessionStatus(rawValue: session.status) {
            Group {
                switch status {
            case .recorded:
                Label("録音完了", systemImage: "circle.fill")
                    .font(.caption)
                    .foregroundStyle(.gray)
            case .summarizing:
                Label("処理中", systemImage: "arrow.2.circlepath")
                    .font(.caption)
                    .foregroundStyle(.orange)
            case .done:
                Label("完了", systemImage: "checkmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(.green)
            case .error:
                Label("エラー", systemImage: "exclamationmark.triangle.fill")
                    .font(.caption)
                    .foregroundStyle(.red)
            }
            }
        }
    }

    // MARK: - Summary Tab

    private var summaryTab: some View {
        ScrollView {
            if session.summaryText.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "doc.text")
                        .font(.largeTitle)
                        .foregroundStyle(.secondary)
                    if session.status == SessionStatus.error.rawValue {
                        Text(session.errorMessage ?? "エラーが発生しました")
                            .foregroundStyle(.red)
                        Button("再試行") {
                            Task { await viewModel.retrySummarization() }
                        }
                        .buttonStyle(.borderedProminent)
                    } else {
                        Text("要約未実行")
                            .foregroundStyle(.secondary)
                        Button("要約実行") {
                            Task { await viewModel.summarize() }
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 200)
            } else {
                Text(session.summaryText)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding()
    }

    // MARK: - Transcription Tab

    private var transcriptionTab: some View {
        ScrollView {
            if session.transcriptionText.isEmpty && session.chunks.allSatisfy({ $0.transcriptionText == nil }) {
                VStack(spacing: 12) {
                    if viewModel.isTranscribing {
                        ProgressView("文字起こし中...")
                    } else {
                        Image(systemName: "text.quote")
                            .font(.largeTitle)
                            .foregroundStyle(.secondary)
                        Text("文字起こし未実行")
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 200)
            } else {
                Text(transcriptionContent)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding()
    }

    private var transcriptionContent: String {
        if !session.transcriptionText.isEmpty {
            return session.transcriptionText
        }
        return session.chunks
            .sorted(by: { $0.chunkIndex < $1.chunkIndex })
            .compactMap { $0.transcriptionText }
            .joined(separator: "\n\n")
    }

    // MARK: - Chunks Tab

    private var chunksTab: some View {
        List(viewModel.selectedSessionChunks) { chunk in
            HStack {
                Text("#\(chunk.chunkIndex)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .frame(width: 30)
                chunkStatusIcon(chunk.status)
                if let text = chunk.transcriptionText {
                    Text(text)
                        .lineLimit(2)
                } else {
                    Text("—")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .listStyle(.inset)
    }

    @ViewBuilder
    private func chunkStatusIcon(_ status: String) -> some View {
        switch ChunkStatus(rawValue: status) {
        case .pending:
            Image(systemName: "clock")
                .foregroundStyle(.gray)
        case .uploading:
            ProgressView()
                .controlSize(.mini)
        case .done:
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .failed, .none:
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundStyle(.red)
        }
    }

    // MARK: - Playback Controls

    private var playbackControls: some View {
        HStack(spacing: 16) {
            Button(action: togglePlayback) {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                    .font(.title2)
            }

            Slider(value: $playbackTime, in: 0...totalDuration) { editing in
                if !editing {
                    audioPlayer?.currentTime = playbackTime
                }
            }

            Text(formatPlaybackTime(playbackTime))
                .font(.caption.monospacedDigit())
                .frame(width: 50)

            Text("/")
                .foregroundStyle(.secondary)

            Text(formatPlaybackTime(totalDuration))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 50)

            Picker("速度", selection: $playbackSpeed) {
                Text("0.5x").tag(0.5)
                Text("1.0x").tag(1.0)
                Text("1.5x").tag(1.5)
                Text("2.0x").tag(2.0)
            }
            .pickerStyle(.segmented)
            .frame(width: 160)
            .onChange(of: playbackSpeed) { _, newValue in
                audioPlayer?.rate = newValue
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    private var totalDuration: TimeInterval {
        session.durationMs / 1000.0
    }

    private func togglePlayback() {
        if isPlaying {
            audioPlayer?.pause()
            isPlaying = false
        } else {
            if let player = audioPlayer {
                player.play()
            } else {
                setupPlayer()
            }
            isPlaying = true
        }
    }

    private func setupPlayer() {
        let recordingsDir = FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first!
            .appendingPathComponent("recordings", isDirectory: true)
        let url = recordingsDir.appendingPathComponent(session.wavFileName)
        guard FileManager.default.fileExists(atPath: url.path) else { return }

        do {
            audioPlayer = try AVAudioPlayer(contentsOf: url)
            audioPlayer?.rate = playbackSpeed
            audioPlayer?.play()
            isPlaying = true
        } catch {
            // Silent fail for playback errors
        }
    }

    private func formatPlaybackTime(_ time: TimeInterval) -> String {
        let minutes = Int(time) / 60
        let seconds = Int(time) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}
