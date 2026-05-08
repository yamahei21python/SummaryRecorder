import SwiftUI

struct RecordingToolbar: ToolbarContent {
    @ObservedObject var viewModel: MainViewModel

    var body: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            HStack(spacing: 12) {
                if viewModel.isRecording || viewModel.isPaused {
                    Text("録音中")
                        .font(.body.bold())
                        .foregroundStyle(.red)
                        .frame(minWidth: 120)

                } else if viewModel.isTranscribing {
                    Text("文字起こし中")
                        .font(.body.bold())
                        .foregroundStyle(.orange)
                        .frame(minWidth: 120)

                } else if viewModel.isSummarizing {
                    Text("要約中")
                        .font(.body.bold())
                        .foregroundStyle(.blue)
                        .frame(minWidth: 120)

                } else {
                    Button(action: { Task { await viewModel.startRecording() } }) {
                        Text("新規録音")
                            .font(.body.bold())
                    }
                    .tint(.red)
                    .frame(minWidth: 120)
                }
            }
        }
    }
}

