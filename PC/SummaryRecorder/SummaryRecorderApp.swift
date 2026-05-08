import SwiftUI
import SwiftData

@main
struct SummaryRecorderApp: App {
    @StateObject private var appConfig = AppConfig.shared
    @State private var showDownloadSheet = false

    var body: some Scene {
        WindowGroup {
            ContentView()
                .sheet(isPresented: $showDownloadSheet) {
                    ModelDownloadView(isPresented: $showDownloadSheet)
                }
                .onAppear {
                    // E2E test bypasses download UI
                    if !CommandLine.arguments.contains("-E2E")
                        && !CommandLine.arguments.contains("-E2E-LOCAL")
                        && !appConfig.areModelsDownloaded
                        && appConfig.transcriptionMode == .mlx {
                        showDownloadSheet = true
                    }
                }
        }
        .modelContainer(for: [Session.self, Chunk.self])

        Settings {
            SettingsWindowView(appConfig: appConfig)
        }
    }
}

// MARK: - Settings Window Wrapper (Cmd+,)

struct SettingsWindowView: View {
    @ObservedObject var appConfig: AppConfig
    @State private var dummy = true

    var body: some View {
        SettingsView(appConfig: appConfig, showSettings: $dummy)
    }
}
