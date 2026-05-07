import SwiftUI

struct RecordingToolbar: ToolbarContent {
    @ObservedObject var viewModel: MainViewModel

    var body: some ToolbarContent {
        ToolbarItem(placement: .principal) {
            HStack(spacing: 12) {
                // Start button
                Button(action: { Task { await viewModel.startRecording() } }) {
                    Label("録音", systemImage: "circle.fill")
                        .labelStyle(.titleAndIcon)
                }
                .disabled(!viewModel.canStartRecording)
                .tint(.red)

                // Pause/Resume button
                if viewModel.isRecording {
                    Button(action: { Task { await viewModel.pauseRecording() } }) {
                        Image(systemName: "pause.fill")
                    }
                } else if viewModel.isPaused {
                    Button(action: { Task { await viewModel.resumeRecording() } }) {
                        Image(systemName: "play.fill")
                    }
                }

                // Stop button
                if viewModel.isRecording || viewModel.isPaused {
                    Button(action: { Task { await viewModel.stopRecording() } }) {
                        Image(systemName: "stop.fill")
                    }
                    .tint(.red)
                }

                // Duration
                if viewModel.isRecording || viewModel.isPaused {
                    Text(formatDuration(viewModel.recordingDuration))
                        .font(.system(.body, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .frame(minWidth: 70)
                }

                // Audio level meter
                if viewModel.isRecording {
                    AudioLevelView(level: viewModel.audioLevel)
                        .frame(width: 80)
                }

                Divider()
                    .frame(height: 20)

                // Transcription status
                if viewModel.isTranscribing {
                    Label("文字起こし中", systemImage: "waveform")
                        .font(.caption)
                        .foregroundStyle(.orange)
                }

                // Summarization status
                if viewModel.isSummarizing {
                    Label("要約中", systemImage: "text.bubble")
                        .font(.caption)
                        .foregroundStyle(.blue)
                }
            }
        }
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let hours = Int(duration) / 3600
        let minutes = Int(duration) / 60 % 60
        let seconds = Int(duration) % 60
        if hours > 0 {
            return String(format: "%02d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%02d:%02d", minutes, seconds)
    }
}

// MARK: - Audio Level Meter

struct AudioLevelView: View {
    let level: Double

    var body: some View {
        GeometryReader { geometry in
            HStack(spacing: 2) {
                ForEach(0..<8, id: \.self) { index in
                    RoundedRectangle(cornerRadius: 1)
                        .fill(barColor(for: index))
                        .frame(width: 6, height: barHeight(for: index, maxHeight: geometry.size.height))
                }
            }
        }
    }

    private func barColor(for index: Int) -> Color {
        let threshold = Double(8 - index) / 8.0
        if level > threshold {
            return index >= 6 ? .red : .green
        }
        return .gray.opacity(0.3)
    }

    private func barHeight(for index: Int, maxHeight: CGFloat) -> CGFloat {
        let base = maxHeight * (0.3 + Double(index) * 0.08)
        let active = level > Double(8 - index) / 8.0
        return active ? base : maxHeight * 0.2
    }
}
