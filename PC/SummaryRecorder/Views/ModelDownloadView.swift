import SwiftUI

@MainActor
struct ModelDownloadView: View {
    @StateObject private var downloadService = ModelDownloadService()
    @ObservedObject private var appConfig = AppConfig.shared
    @State private var hasStarted = false
    @Binding var isPresented: Bool

    init(isPresented: Binding<Bool> = .constant(false)) {
        _isPresented = isPresented
    }

    var body: some View {
        VStack(spacing: 24) {
            Image(systemName: "arrow.down.circle.dotted")
                .font(.system(size: 48))
                .foregroundStyle(.blue)
            Text("モデルダウンロード")
                .font(.title2)
                .bold()

            modelRow(
                label: "Whisper (文字起こし)",
                filename: "ggml-base.en.bin (~140MB)",
                progress: downloadService.whisperProgress,
                status: downloadService.whisperStatus,
                error: downloadService.whisperError
            )
            modelRow(
                label: "Gemma 4 E2B (要約)",
                filename: "google_gemma-4-E2B-it-Q4_K_M.gguf (~3.5GB)",
                progress: downloadService.llamaProgress,
                status: downloadService.llamaStatus,
                error: downloadService.llamaError
            )

            Text("初回のみ・合計約3.6GB")
                .font(.caption)
                .foregroundStyle(.secondary)

            if downloadService.isDownloading {
                Button("キャンセル", role: .destructive) {
                    downloadService.cancelDownloads()
                }
                .buttonStyle(.bordered)
            } else if downloadService.whisperStatus == "done" && downloadService.llamaStatus == "done" {
                Text("完了！").foregroundStyle(.green)
                Button("アプリを使用") {
                    isPresented = false
                }
                .buttonStyle(.borderedProminent)
            } else if let _ = downloadService.whisperError ?? downloadService.llamaError {
                Button("再試行") { startDownload() }
                    .buttonStyle(.borderedProminent)
            } else if !hasStarted {
                Button("ダウンロード開始") { startDownload() }
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding(32)
        .frame(width: 420)
        .onAppear {
            if !hasStarted && !appConfig.areModelsDownloaded {
                startDownload()
            }
        }
    }

    // MARK: - Private

    private func startDownload() {
        hasStarted = true
        Task {
            do {
                let urls = try await downloadService.downloadAllModels()
                appConfig.whisperModelPath = urls.whisperURL.path
                appConfig.llamaModelPath = urls.llamaURL.path
            } catch {
                // error handled by service's published state
            }
        }
    }

    private func modelRow(label: String, filename: String, progress: Double, status: String, error: String?) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).bold()
            Text(filename).font(.caption2).foregroundStyle(.secondary)

            switch status {
            case "done":
                HStack {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                    Text("完了").font(.caption).foregroundStyle(.green)
                }
            case "downloading":
                ProgressView(value: progress)
                    .progressViewStyle(.linear)
                Text("\(Int(progress * 100))%")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            case "error":
                HStack {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
                    Text(error ?? "エラー").font(.caption).foregroundStyle(.red)
                }
            default:
                Text("待機中").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.controlBackgroundColor))
        .cornerRadius(8)
    }
}
