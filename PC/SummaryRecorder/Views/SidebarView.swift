import SwiftUI

struct SidebarView: View {
    @ObservedObject var viewModel: MainViewModel
    @ObservedObject private var appConfig = AppConfig.shared
    @Binding var showSettings: Bool

    var body: some View {
        VStack(spacing: 0) {
            List(viewModel.sessions, selection: $viewModel.selectedSession) { session in
                SessionRowView(session: session)
                    .tag(session)
                    .contextMenu {
                        Button("削除", role: .destructive) {
                            viewModel.deleteSession(session)
                        }
                    }
            }
            .listStyle(.sidebar)

            // モード切替バー + 設定ボタン
            VStack(spacing: 6) {
                HStack {
                    Text("文字起こし")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Picker("", selection: $appConfig.transcriptionMode) {
                        ForEach(TranscriptionMode.allCases, id: \.self) { mode in
                            Text(mode.displayName).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 140)
                    .onChange(of: appConfig.transcriptionMode) { _, new in
                        if new == .groq && appConfig.groqAPIKey.isEmpty {
                            appConfig.transcriptionMode = .mlx
                        }
                    }
                }
                HStack {
                    Text("要約")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Picker("", selection: $appConfig.summarizationMode) {
                        ForEach(SummarizationMode.allCases, id: \.self) { mode in
                            Text(mode.displayName).tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 140)
                    .onChange(of: appConfig.summarizationMode) { _, new in
                        if new == .gemini && appConfig.geminiAPIKey.isEmpty {
                            appConfig.summarizationMode = .local
                        }
                    }
                }
                Divider()
                Button {
                    showSettings = true
                } label: {
                    Label("設定", systemImage: "gearshape")
                        .font(.caption)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color(.controlBackgroundColor))
        }
        .navigationSplitViewColumnWidth(min: 200, ideal: 250)
    }
}

// MARK: - Session Row

struct SessionRowView: View {
    let session: Session

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(displayTitle)
                    .font(.headline)
                    .lineLimit(1)
                if !session.isRead {
                    Circle()
                        .fill(.blue)
                        .frame(width: 8, height: 8)
                }
                Spacer()
                statusIcon
            }
            HStack {
                Text(dateText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(durationText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
    }

    private var displayTitle: String {
        session.title.isEmpty ? "Untitled" : session.title
    }

    private var dateText: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM/dd"
        return formatter.string(from: session.createdAt)
    }

    private var durationText: String {
        let minutes = Int(session.durationMs / 60_000)
        let seconds = Int((session.durationMs.truncatingRemainder(dividingBy: 60_000)) / 1000)
        return String(format: "%d:%02d", minutes, seconds)
    }

    @ViewBuilder
    private var statusIcon: some View {
        switch session.status {
        case .recorded:
            Image(systemName: "circle.fill").foregroundStyle(.gray).font(.caption2)
        case .summarizing:
            ProgressView().controlSize(.mini)
        case .done:
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).font(.caption2)
        case .error:
            Image(systemName: "exclamationmark.circle.fill").foregroundStyle(.red).font(.caption2)
        }
    }
}
