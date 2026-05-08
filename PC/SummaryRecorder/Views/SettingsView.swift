import SwiftUI

struct SettingsView: View {
    @ObservedObject var appConfig: AppConfig
    @Binding var showSettings: Bool
    @State private var showGroqKey = false
    @State private var showGeminiKey = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // ヘッダー
                HStack {
                    Text("設定")
                        .font(.title2.bold())
                    Spacer()
                    Button("閉じる") {
                        showSettings = false
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                .padding(.bottom, 4)

                // 文字起こしセクション
                GroupBox {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("文字起こし")
                            .font(.headline)
                        Picker("モード", selection: $appConfig.transcriptionMode) {
                            ForEach(TranscriptionMode.allCases, id: \.self) { mode in
                                Text(mode.displayName).tag(mode)
                            }
                        }
                        .pickerStyle(.segmented)

                        if appConfig.transcriptionMode == .groq {
                            apiKeyField(
                                title: "Groq API Key",
                                key: $appConfig.groqAPIKey,
                                showKey: $showGroqKey
                            )
                        }

                        if appConfig.transcriptionMode == .mlx {
                            Text("whisper.cpp (in-process)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                // 要約セクション
                GroupBox {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("要約")
                            .font(.headline)

                        Picker("モード", selection: $appConfig.summarizationMode) {
                            ForEach(SummarizationMode.allCases, id: \.self) { mode in
                                Text(mode.displayName).tag(mode)
                            }
                        }
                        .pickerStyle(.segmented)

                        Toggle("自動要約", isOn: $appConfig.autoSummarize)

                        if appConfig.summarizationMode == .gemini {
                            apiKeyField(
                                title: "Gemini API Key",
                                key: $appConfig.geminiAPIKey,
                                showKey: $showGeminiKey
                            )
                        }

                        if appConfig.summarizationMode == .local {
                            Text("Gemma 4 (local)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                // 要約指示セクション
                GroupBox {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("要約指示")
                            .font(.headline)

                        TextField("要約の指示", text: $appConfig.summaryInstruction, axis: .vertical)
                            .textFieldStyle(.roundedBorder)
                            .lineLimit(2...5)

                        HStack {
                            Text("出力形式は固定（JSON）")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Spacer()
                            Button("デフォルトに戻す") {
                                appConfig.summaryInstruction = AppConfig.defaultSummaryInstruction
                            }
                            .controlSize(.small)
                        }
                    }
                }

                // データセクション
                GroupBox {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("データ")
                            .font(.headline)

                        HStack {
                            Text("録音ファイル")
                                .font(.body)
                            Spacer()
                            Button {
                                NSWorkspace.shared.open(AppPaths.recordingsDirectory)
                            } label: {
                                Text("フォルダを開く")
                            }
                            .controlSize(.small)
                        }
                    }
                }
            }
            .padding(20)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - API Key Field

    private func apiKeyField(title: String, key: Binding<String>, showKey: Binding<Bool>) -> some View {
        HStack {
            if showKey.wrappedValue {
                TextField(title, text: key)
                    .textFieldStyle(.roundedBorder)
            } else {
                SecureField(title, text: key)
                    .textFieldStyle(.roundedBorder)
            }
            Button {
                showKey.wrappedValue.toggle()
            } label: {
                Image(systemName: showKey.wrappedValue ? "eye.slash" : "eye")
            }
            .buttonStyle(.borderless)
        }
    }
}
