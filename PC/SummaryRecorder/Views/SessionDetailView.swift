import SwiftUI

struct SessionDetailView: View {
    @ObservedObject var viewModel: MainViewModel
    let session: Session
    @State private var selectedTab = 1
    @State private var isEditingTitle = false
    @State private var titleText = ""
    @StateObject private var playbackManager = AudioPlaybackManager()

    var body: some View {
        VStack(spacing: 0) {
            headerView
            Divider()
            TabView(selection: $selectedTab) {
                transcriptionTab
                    .tabItem { Label("文字起こし", systemImage: "text.quote") }
                    .tag(0)
                summaryTab
                    .tabItem { Label("要約", systemImage: "doc.text") }
                    .tag(1)
                if viewModel.isInGroqMode && !session.chunks.isEmpty {
                    chunksTab
                        .tabItem { Label("チャンク", systemImage: "list.bullet") }
                        .tag(2)
                }
            }
            Divider()
            playbackControls
        }
        .padding()
        .onAppear {
            playbackManager.stop()
        }
        .onChange(of: session.id) { _, _ in playbackManager.stop() }
        .onDisappear { playbackManager.stop() }
    }

    // MARK: - Header

    @ViewBuilder
    private var headerView: some View {
        HStack {
            if isEditingTitle {
                TextField("タイトル", text: $titleText)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit { viewModel.updateTitle(titleText); isEditingTitle = false }
                    .onAppear { titleText = session.title }
            } else {
                Text(session.title.isEmpty ? "Untitled" : session.title)
                    .font(.title2.bold())
                    .onTapGesture(count: 2) { isEditingTitle = true }
            }
            Spacer()
            statusBadge
            Menu {
                Button("要約実行") { Task { await viewModel.summarize() } }
                    .disabled(session.status == .summarizing)
                Button("リセット") { viewModel.resetStuckSession() }
                    .disabled(session.status != .summarizing)
                Divider()
                Button("削除", role: .destructive) { viewModel.deleteSession(session) }
            } label: {
                Image(systemName: "ellipsis")
            }
        }
    }

    @ViewBuilder
    private var statusBadge: some View {
        Group {
            switch session.status {
            case .recorded:
                Label("録音完了", systemImage: "circle.fill").font(.caption).foregroundStyle(.gray)
            case .summarizing:
                Label("処理中", systemImage: "arrow.2.circlepath").font(.caption).foregroundStyle(.orange)
            case .done:
                Label("完了", systemImage: "checkmark.circle.fill").font(.caption).foregroundStyle(.green)
            case .error:
                Label("エラー", systemImage: "exclamationmark.triangle.fill").font(.caption).foregroundStyle(.red)
            }
        }
    }

    // MARK: - Summary Tab

    private var summaryTab: some View {
        ScrollView {
            if session.summaryText.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "doc.text").font(.largeTitle).foregroundStyle(.secondary)
                    switch session.status {
                    case .summarizing:
                        Label("処理中", systemImage: "arrow.2.circlepath").foregroundStyle(.orange)
                        Button("リセット") { viewModel.resetStuckSession() }.buttonStyle(.bordered).controlSize(.small)
                    case .error:
                        Text(session.errorMessage ?? "エラーが発生しました").foregroundStyle(.red)
                        Button("リセット") { viewModel.resetStuckSession() }.buttonStyle(.borderedProminent)
                    default:
                        Text("要約未実行").foregroundStyle(.secondary)
                        Button("要約実行") { Task { await viewModel.summarize() } }.buttonStyle(.borderedProminent)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 200)
            } else {
                Text(session.summaryText).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
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
                        Image(systemName: "text.quote").font(.largeTitle).foregroundStyle(.secondary)
                        Text("文字起こし未実行").foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 200)
            } else {
                Text(transcriptionContent).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding()
    }

    private var transcriptionContent: String {
        if !session.transcriptionText.isEmpty { return session.transcriptionText }
        return session.chunks
            .sorted { $0.chunkIndex < $1.chunkIndex }
            .compactMap { $0.transcriptionText }
            .joined(separator: "\n\n")
    }

    // MARK: - Chunks Tab

    private var chunksTab: some View {
        List(viewModel.selectedSessionChunks) { chunk in
            HStack {
                Text("#\(chunk.chunkIndex)").font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(width: 30)
                chunkStatusIcon(chunk.status)
                if let text = chunk.transcriptionText { Text(text).lineLimit(2) }
                else { Text("—").foregroundStyle(.secondary) }
            }
        }
        .listStyle(.inset)
    }

    @ViewBuilder
    private func chunkStatusIcon(_ status: ChunkStatus) -> some View {
        switch status {
        case .pending: Image(systemName: "clock").foregroundStyle(.gray)
        case .uploading: ProgressView().controlSize(.mini)
        case .done: Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
        case .failed: Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.red)
        }
    }

    // MARK: - Playback Controls

    private var playbackControls: some View {
        HStack(spacing: 16) {
            Button(action: {
                if playbackManager.isPlaying {
                    playbackManager.toggle()
                } else {
                    playbackManager.setup(for: session.wavFileName)
                }
            }) {
                Image(systemName: playbackManager.isPlaying ? "pause.fill" : "play.fill").font(.title2)
            }
            Slider(value: $playbackManager.playbackTime, in: 0...playbackManager.totalDuration) { editing in
                if !editing { playbackManager.seek(to: playbackManager.playbackTime) }
            }
            Text(playbackManager.formatTime(playbackManager.playbackTime)).font(.caption.monospacedDigit()).frame(width: 50)
            Text("/").foregroundStyle(.secondary)
            Text(playbackManager.formatTime(playbackManager.totalDuration)).font(.caption.monospacedDigit()).foregroundStyle(.secondary).frame(width: 50)
            Picker("速度", selection: $playbackManager.playbackSpeed) {
                Text("0.5x").tag(Float(0.5)); Text("1.0x").tag(Float(1.0))
                Text("1.5x").tag(Float(1.5)); Text("2.0x").tag(Float(2.0))
            }
            .pickerStyle(.segmented).frame(width: 160).id("speedPicker")
            .onChange(of: playbackManager.playbackSpeed) { _, _ in
                if playbackManager.isPlaying {
                    playbackManager.toggle()
                    playbackManager.toggle()
                }
            }
        }
        .padding(.horizontal).padding(.vertical, 8)
    }
}
