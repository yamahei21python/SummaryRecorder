import SwiftUI
import SwiftData

struct ContentView: View {
    @Environment(\.modelContext) private var modelContext

    var body: some View {
        ContentBody(modelContext: modelContext)
    }
}

private struct ContentBody: View {
    @StateObject private var viewModel: MainViewModel
    @State private var overlayDismissed = false
    @State private var summarizeOverlayDismissed = false
    @State private var showSettings = false
    @ObservedObject private var appConfig = AppConfig.shared

    init(modelContext: ModelContext) {
        _viewModel = StateObject(wrappedValue: MainViewModel(modelContext: modelContext))
    }

    private var showRecordingOverlay: Bool {
        (viewModel.isRecording || viewModel.isPaused || viewModel.isTranscribing) && !overlayDismissed
    }

    private var showSummarizeOverlay: Bool {
        viewModel.isSummarizing
            && !viewModel.isRecording
            && !viewModel.isTranscribing
            && !summarizeOverlayDismissed
    }

    var body: some View {
        ZStack {
            NavigationSplitView {
                SidebarView(viewModel: viewModel, showSettings: $showSettings)
            } detail: {
                if showSettings {
                    SettingsView(appConfig: appConfig, showSettings: $showSettings)
                } else if let session = viewModel.selectedSession {
                    SessionDetailView(viewModel: viewModel, session: session)
                        .id(session.id)
                } else {
                    VStack(spacing: 16) {
                        Image(systemName: "waveform")
                            .font(.system(size: 64))
                            .foregroundStyle(.secondary)
                        Text("録音を開始してください")
                            .font(.title2)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .toolbar {
                RecordingToolbar(viewModel: viewModel)
            }

            if showRecordingOverlay {
                RecordingOverlayView(viewModel: viewModel) {
                    overlayDismissed = true
                }
            }

            if showSummarizeOverlay {
                SummarizingOverlayView(viewModel: viewModel) {
                    summarizeOverlayDismissed = true
                }
            }
        }
        .onChange(of: viewModel.isRecording) { _, isRecording in
            if isRecording {
                overlayDismissed = false
                summarizeOverlayDismissed = false
                showSettings = false
            }
        }
        .onChange(of: viewModel.isSummarizing) { _, isSummarizing in
            if isSummarizing {
                summarizeOverlayDismissed = false
            }
        }
        .onAppear {
            viewModel.fetchSessions()
        }
        .task {
            #if DEBUG
            await viewModel.startE2ETest()
            #endif
        }
        .alert("エラー", isPresented: $viewModel.showError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(viewModel.errorMessage ?? "Unknown error")
        }
    }
}
