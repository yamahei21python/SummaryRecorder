import SwiftUI

struct SettingsView: View {
    @ObservedObject var appConfig = AppConfig.shared
    @State private var lastSaved: Date?

    var body: some View {
        Form {
            // Transcription Mode
            Section("文字起こし") {
                Picker("モード", selection: $appConfig.transcriptionMode) {
                    ForEach(TranscriptionMode.allCases, id: \.self) { mode in
                        Text(mode.displayName).tag(mode)
                    }
                }
                .pickerStyle(.segmented)

                if appConfig.transcriptionMode == .groq {
                    SecureField("Groq API Key", text: $appConfig.groqAPIKey)
                        .onSubmit { save() }
                }

                if appConfig.transcriptionMode == .mlx {
                    Text("pip install lightning-whisper-mlx")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            // Summarization Mode
            Section("要約") {
                Picker("モード", selection: $appConfig.summarizationMode) {
                    ForEach(SummarizationMode.allCases, id: \.self) { mode in
                        Text(mode.displayName).tag(mode)
                    }
                }
                .pickerStyle(.segmented)

                Toggle("自動要約", isOn: $appConfig.autoSummarize)

                if appConfig.summarizationMode == .gemini {
                    SecureField("Gemini API Key", text: $appConfig.geminiAPIKey)
                        .onSubmit { save() }
                }

                if appConfig.summarizationMode == .local {
                    ModelDownloadView()
                }
            }

            // Save status
            if let saved = lastSaved {
                Section {
                    Text("保存済: \(saved.formatted(date: .omitted, time: .shortened))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .formStyle(.grouped)
        .frame(width: 450, height: 380)
        .onDisappear { save() }
    }

    private func save() {
        appConfig.saveAPIKeys()
        lastSaved = Date()
    }
}
