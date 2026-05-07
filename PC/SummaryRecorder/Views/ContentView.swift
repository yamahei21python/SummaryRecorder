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

    init(modelContext: ModelContext) {
        _viewModel = StateObject(wrappedValue: MainViewModel(modelContext: modelContext))
    }

    var body: some View {
        NavigationSplitView {
            SidebarView(viewModel: viewModel)
        } detail: {
            if let session = viewModel.selectedSession {
                SessionDetailView(viewModel: viewModel, session: session)
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
        .onAppear {
            print("[ContentView] onAppear")
            viewModel.fetchSessions()
        }
        .task {
            print("[ContentView] task started, calling startE2ETest")
            await viewModel.startE2ETest()
            print("[ContentView] startE2ETest completed")
        }
        .alert("エラー", isPresented: $viewModel.showError) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(viewModel.errorMessage ?? "Unknown error")
        }
    }
}
